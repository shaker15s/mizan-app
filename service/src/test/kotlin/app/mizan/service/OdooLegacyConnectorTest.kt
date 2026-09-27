package app.mizan.service

import app.mizan.domain.model.Money
import app.mizan.domain.security.SecretMaterial
import app.mizan.domain.tool.Capability
import app.mizan.service.erp.ErpBinding
import app.mizan.service.erp.ErpResult
import app.mizan.service.erp.HttpTransport
import app.mizan.service.erp.InMemoryTenantErpRegistry
import app.mizan.service.erp.OdooLegacyConnector
import app.mizan.service.erp.OdooLegacyWire
import app.mizan.service.erp.TransportOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The legacy adapter: a read-only bridge for a customer whose Odoo predates
 * JSON-2.
 *
 * Two things are under test and they matter for different reasons. The wire
 * format is hand-written XML, so its failure mode is a value read wrongly --
 * an amount, a stock count, a customer -- and the tests feed it the shapes
 * Odoo actually sends, including the ones that should be refused. The adapter
 * is the only place in the service that talks to an ERP without atomic
 * operations, so its write methods must refuse in a way the authority turns
 * into a hidden action rather than a failed one.
 */
class OdooLegacyConnectorTest {

    private val tenant = "sim-legacy"
    private val key = SecretMaterial.of("legacy-secret-key")

    private class ScriptedTransport(
        private val responses: MutableList<() -> TransportOutcome> = ArrayList(),
    ) : HttpTransport {
        val calls = ArrayList<Call>()
        private val counter = AtomicInteger(0)

        data class Call(val url: String, val headers: Map<String, String>, val body: String)

        fun respondWith(response: () -> TransportOutcome) {
            responses += response
        }

        fun fixed(outcome: TransportOutcome) {
            responses += { outcome }
        }

        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
            timeoutMillis: Long,
        ): TransportOutcome {
            calls += Call(url, headers, body)
            val answer = responses.getOrElse(counter.getAndIncrement()) { { TransportOutcome.Received(200, one()) } }
            return answer()
        }

        /** The default answer when a test forgot to script one: one row. */
        private fun one(): String = """<?xml version="1.0"?><methodResponse><params><param><value><array><data><value><struct><member><name>id</name><value><int>1</int></value></member></struct></value></data></array></value></param></params></methodResponse>"""
    }

    private fun connector(transport: HttpTransport, login: String? = "integration@alamal.test"): OdooLegacyConnector {
        val registry = InMemoryTenantErpRegistry(
            listOf(
                ErpBinding(
                    tenantId = tenant,
                    baseUrl = "https://erp.alamal.example",
                    database = "alamal",
                    apiKey = key,
                    label = "odoo-17",
                    login = login,
                ),
            ),
        )
        return OdooLegacyConnector(registry, transport, clock = { 1_700_000_000_000L })
    }

    // ------------------------------------------------------------------ wire

    /**
     * A `<methodResponse>` around the given values.
     *
     * `Xml.render()` already emits the `<value>` element, exactly as it must
     * inside an array or a struct member, so this helper must not wrap again:
     * a double-wrapped fixture would test the parser against XML no Odoo
     * sends.
     */
    private fun response(vararg values: OdooLegacyWire.Xml): String =
        """<?xml version="1.0"?><methodResponse><params><param>${values.joinToString("") { it.render() }}</param></params></methodResponse>"""

    private fun intValue(value: Long) = OdooLegacyWire.Xml.Int(value)

    @Test
    fun aCallCarriesTheCredentialTheModelAndTheKeywordArguments() {
        val body = OdooLegacyWire.executeCall(
            database = "alamal",
            uid = 7L,
            apiKey = key,
            model = "res.partner",
            method = "search_read",
            args = listOf(OdooLegacyWire.Xml.Array(listOf(OdooLegacyWire.Xml.Str("name")))),
            kwargs = linkedMapOf("fields" to OdooLegacyWire.Xml.Array(listOf(OdooLegacyWire.Xml.Str("id")))),
        )
        // XML-RPC is stateless, so the credential travels in the body of every
        // call. That is one of the reasons this path is migration-only.
        assertTrue(body.contains("<methodName>execute_kw</methodName>"))
        assertTrue(body.contains("<string>alamal</string>"))
        assertTrue(body.contains("<int>7</int>"))
        assertTrue(body.contains("legacy-secret-key"))
        assertTrue(body.contains("<string>res.partner</string>"))
        assertTrue(body.contains("<name>fields</name>"))
    }

    @Test
    fun aModelOrMethodNameThatIsNotAnIdentifierIsRefusedBeforeAnyCall() {
        // The model name lands in the request body verbatim; a name that is
        // not a plain identifier is not a name this connector will send.
        val refused = runCatching {
            OdooLegacyWire.executeCall("alamal", 1L, key, "res.partner</methodName>", "search_read")
        }
        assertTrue(refused.isFailure)
    }

    @Test
    fun aFaultBecomesAReasonCodeAndNeverAValue() {
        val body = """<?xml version="1.0"?><methodResponse><fault><value><struct>
            <member><name>faultCode</name><value><int>100</int></value></member>
            <member><name>faultString</name><value><string>Access denied</string></value></member>
        </struct></value></fault></methodResponse>"""
        val parsed = OdooLegacyWire.parse(body)
        assertTrue(parsed is OdooLegacyWire.Response.Fault)
        val fault = parsed as OdooLegacyWire.Response.Fault
        assertEquals(100, fault.code)
        assertEquals("Access denied", fault.message)
        assertEquals("ERP_AUTH_REJECTED", OdooLegacyWire.reasonForFault(fault.code, fault.message))
        assertEquals("ERP_MODEL_OR_METHOD_MISSING", OdooLegacyWire.reasonForFault(1, "object not found"))
        // Odoo reports a permissions problem as faultCode 2 on some versions
        // and as a plain message on others; both land on the same code.
        assertEquals("ERP_ACCESS_DENIED", OdooLegacyWire.reasonForFault(2, "no attribute"))
        assertEquals("ERP_ACCESS_DENIED", OdooLegacyWire.reasonForFault(3, "Access Error"))
    }

    @Test
    fun theReaderUnderstandsTheShapesOdooActuallySends() {
        val array = OdooLegacyWire.parse(
            response(
                OdooLegacyWire.Xml.Array(
                    listOf(
                        OdooLegacyWire.Xml.Struct(
                            linkedMapOf(
                                "id" to intValue(41),
                                "name" to OdooLegacyWire.Xml.Str("Al-Amal Trading & Sons"),
                                "active" to OdooLegacyWire.Xml.Bool(true),
                                "credit_limit" to OdooLegacyWire.Xml.Str("2500.00"),
                                "partner_id" to OdooLegacyWire.Xml.Array(
                                    listOf(intValue(9), OdooLegacyWire.Xml.Str("Cairo Branch")),
                                ),
                                "note" to OdooLegacyWire.Xml.Nil,
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(array is OdooLegacyWire.Response.Value)
        val items = (array as OdooLegacyWire.Response.Value).value.asArray()
        assertNotNull(items)
        val row = items!!.first().asStruct()!!
        assertEquals(41L, row["id"]!!.asLong())
        // Entities are unescaped, or a customer's name becomes markup.
        assertEquals("Al-Amal Trading & Sons", row["name"]!!.asString())
        assertEquals(true, row["active"]!!.asBool())
        assertEquals(2500.0, row["credit_limit"]!!.asDouble()!!, 0.001)
        assertEquals(9L to "Cairo Branch", row["partner_id"]!!.many2One()!!)
        assertTrue(row["note"] is OdooLegacyWire.Xml.Nil)
    }

    @Test
    fun anAnswerThatIsNotWhatItClaimsToBeIsUnreadableRatherThanEmpty() {
        assertEquals(
            "ODOO_LEGACY_EMPTY_BODY",
            (OdooLegacyWire.parse("") as OdooLegacyWire.Response.Unreadable).reasonCode,
        )
        assertEquals(
            "ODOO_LEGACY_NO_VALUE",
            (OdooLegacyWire.parse("<methodResponse></methodResponse>") as OdooLegacyWire.Response.Unreadable).reasonCode,
        )
        // An unknown type tag is not silently read as a string.
        val strange = OdooLegacyWire.parse(
            """<?xml version="1.0"?><methodResponse><params><param><value><matrix>1,2</matrix></value></param></params></methodResponse>""",
        )
        assertTrue(strange is OdooLegacyWire.Response.Unreadable)
    }

    @Test
    fun escapesRoundTripThroughTheWriterAndTheReader() {
        val original = "Al-Amal & Sons <Cairo> \"branch\" 'north'"
        val parsed = OdooLegacyWire.parse(response(OdooLegacyWire.Xml.Str(original)))
        assertEquals(original, (parsed as OdooLegacyWire.Response.Value).value.asString())
    }

    // -------------------------------------------------------------- connector

    @Test
    fun theLegacyAdapterAuthenticatesOnceAndReusesTheUid() {
        val transport = ScriptedTransport()
        transport.respondWith { TransportOutcome.Received(200, response(intValue(7))) }
        transport.respondWith {
            TransportOutcome.Received(
                200,
                response(OdooLegacyWire.Xml.Array(listOf(OdooLegacyWire.Xml.Struct(linkedMapOf("id" to intValue(3), "name" to OdooLegacyWire.Xml.Str("Nile Foods")))))),
            )
        }
        val connector = connector(transport)
        val found = connector.findCustomer(tenant, "Nile")
        assertTrue(found is ErpResult.Ok)
        assertEquals("Nile Foods", (found as ErpResult.Ok).value.single().name)
        // login, then the read: two calls, and the read carries the uid.
        assertEquals(2, transport.calls.size)
        assertTrue(transport.calls[0].url.endsWith("/xmlrpc/2/common"))
        assertTrue(transport.calls[1].url.endsWith("/xmlrpc/2/object"))
        assertTrue(transport.calls[1].body.contains("<int>7</int>"))

        // A second read does not log in again.
        transport.respondWith { TransportOutcome.Received(200, response(OdooLegacyWire.Xml.Array(emptyList()))) }
        connector.findCustomer(tenant, "Nile")
        assertEquals(3, transport.calls.size)
    }

    @Test
    fun aRefusedLoginIsRefusedAndNeverCachedAsASession() {
        val transport = ScriptedTransport()
        // Odoo answers `false` for a bad login, which is not a uid.
        transport.fixed(TransportOutcome.Received(200, response(OdooLegacyWire.Xml.Bool(false))))
        val connector = connector(transport)
        val result = connector.findCustomer(tenant, "Nile")
        assertTrue(result is ErpResult.Refused)
        assertEquals("ERP_AUTH_REJECTED", (result as ErpResult.Refused).reasonCode)
        // The next call tries again rather than reusing a session that was
        // never opened: a rotated key must not need a restart.
        connector.findCustomer(tenant, "Nile")
        assertEquals(2, transport.calls.size)
        assertTrue(transport.calls[0].url.endsWith("/xmlrpc/2/common"))
        assertTrue(transport.calls[1].url.endsWith("/xmlrpc/2/common"))
    }

    @Test
    fun aLegacyBindingWithoutALoginIsRefusedRatherThanGuessed() {
        val transport = ScriptedTransport()
        val connector = connector(transport, login = null)
        val result = connector.findCustomer(tenant, "Nile")
        assertTrue(result is ErpResult.Refused)
        assertEquals("ERP_LEGACY_LOGIN_MISSING", (result as ErpResult.Refused).reasonCode)
        assertTrue("nothing was sent", transport.calls.isEmpty())
    }

    @Test
    fun writesAreNotSupportedAndSayWhichCapabilityIsMissing() {
        val connector = connector(ScriptedTransport())
        val draft = connector.createDraftOrder(tenant, "Nile Foods", Money(250_000L, "USD"), "3 items")
        assertTrue(draft is ErpResult.NotSupported)
        assertEquals(
            Capability.SALES_ORDER_CREATE,
            (draft as ErpResult.NotSupported).capability,
        )
        assertEquals(
            Capability.INVOICE_CREATE,
            (connector.createInvoice(tenant, "41") as ErpResult.NotSupported).capability,
        )
        assertEquals(
            Capability.PAYMENT_CREATE,
            (connector.registerPayment(tenant, "41", Money(1_000L, "USD")) as ErpResult.NotSupported).capability,
        )
        assertEquals(
            Capability.SALES_ORDER_CANCEL,
            (connector.cancelOrder(tenant, "41", "customer changed their mind") as ErpResult.NotSupported).capability,
        )
    }

    @Test
    fun theAdapterAdvertisesReadsAndNeverTheTransportTheProfileDependsOn() {
        val transport = ScriptedTransport()
        // Five models discovered, then the discovered paths are cached.
        repeat(5) { transport.respondWith { TransportOutcome.Received(200, response(intValue(1))) } }
        val connector = connector(transport)
        val capabilities = connector.capabilities()
        assertTrue(Capability.LEGACY_RPC_TRANSPORT in capabilities.capabilities)
        assertTrue(Capability.CUSTOMER_SEARCH in capabilities.capabilities)
        assertTrue(Capability.STOCK_READ in capabilities.capabilities)
        assertFalse(
            "a migration adapter must not claim the primary transport",
            Capability.JSON2_TRANSPORT in capabilities.capabilities,
        )
        assertFalse(Capability.SALES_ORDER_CREATE in capabilities.capabilities)
        assertTrue(capabilities.notes.any { it.contains("read-only") })
        // And the wire contract the app renders agrees with the capability set.
        assertFalse(capabilities.toConnectorCapabilities().supportsDraftOrders)
        assertFalse(capabilities.toConnectorCapabilities().supportsJson2)
        assertTrue(capabilities.toConnectorCapabilities().supportsLegacyRpc)
    }

    @Test
    fun moneyReadOverTheLegacyWireIsConvertedToMinorUnits() {
        val transport = ScriptedTransport()
        transport.respondWith { TransportOutcome.Received(200, response(intValue(7))) }
        transport.respondWith {
            TransportOutcome.Received(
                200,
                response(
                    OdooLegacyWire.Xml.Array(
                        listOf(
                            OdooLegacyWire.Xml.Struct(
                                linkedMapOf(
                                    "id" to intValue(41),
                                    "amount_total" to OdooLegacyWire.Xml.Str("2500.50"),
                                    "currency_id" to OdooLegacyWire.Xml.Array(
                                        listOf(intValue(1), OdooLegacyWire.Xml.Str("USD")),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
        val amount = connector(transport).orderAmount(tenant, "41")
        assertTrue(amount is ErpResult.Ok)
        // 2500.50 major units is 250050 minor units. A hundredfold error here
        // is the difference between a 2,500 order and a 250,000 order.
        assertEquals(250_050L, (amount as ErpResult.Ok).value.minorUnits)
        assertEquals("USD", amount.value.currency)
    }

    @Test
    fun anAmountInTheWrongShapeIsAMalformedAnswerAndNotZero() {
        val transport = ScriptedTransport()
        transport.respondWith { TransportOutcome.Received(200, response(intValue(7))) }
        transport.respondWith {
            TransportOutcome.Received(
                200,
                response(
                    OdooLegacyWire.Xml.Array(
                        listOf(OdooLegacyWire.Xml.Struct(linkedMapOf("id" to intValue(41)))),
                    ),
                ),
            )
        }
        val amount = connector(transport).orderAmount(tenant, "41")
        assertTrue(amount is ErpResult.Malformed)
        assertEquals("ODOO_AMOUNT_MISSING", (amount as ErpResult.Malformed).reasonCode)
    }

    @Test
    fun aReadThatWasDispatchedAndNotAnsweredIsRetryableHereAndNotAmbiguous() {
        val transport = ScriptedTransport()
        transport.respondWith { TransportOutcome.Received(200, response(intValue(7))) }
        transport.respondWith { TransportOutcome.DispatchUnknown("ERP_TIMEOUT", "read timed out") }
        val result = connector(transport).findCustomer(tenant, "Nile")
        // Reads are the one place this is true, and it is true because the
        // adapter cannot write: there is nothing that could have happened.
        assertTrue(result is ErpResult.Unavailable)
        assertEquals("ERP_DISPATCH_UNKNOWN_READ", (result as ErpResult.Unavailable).reasonCode)
    }

    @Test
    fun anExpiredSessionIsDroppedWhenTheErpSaysSo() {
        val transport = ScriptedTransport()
        transport.respondWith { TransportOutcome.Received(200, response(intValue(7))) }
        transport.respondWith {
            TransportOutcome.Received(
                200,
                """<?xml version="1.0"?><methodResponse><fault><value><struct>
                    <member><name>faultCode</name><value><int>100</int></value></member>
                    <member><name>faultString</name><value><string>Session expired</string></value></member>
                </struct></value></fault></methodResponse>""",
            )
        }
        val connector = connector(transport)
        val refused = connector.findCustomer(tenant, "Nile")
        assertTrue(refused is ErpResult.Refused)
        assertEquals("ERP_AUTH_REJECTED", (refused as ErpResult.Refused).reasonCode)
        // The expired uid must not be reused: the next call opens a session.
        transport.respondWith { TransportOutcome.Received(200, response(intValue(7))) }
        transport.respondWith { TransportOutcome.Received(200, response(OdooLegacyWire.Xml.Array(emptyList()))) }
        connector.findCustomer(tenant, "Nile")
        assertEquals(4, transport.calls.size)
        assertTrue(transport.calls[2].url.endsWith("/xmlrpc/2/common"))
    }

    @Test
    fun capabilityDiscoveryReportsAnUnreadableModelInsteadOfAssumingItExists() {
        val transport = ScriptedTransport()
        transport.respondWith { TransportOutcome.Received(200, response(intValue(7))) }
        // The probe order is the list in `discover`: sale.order, account.move,
        // account.payment, stock.quant, res.partner. The first four answer with
        // a count; the last one answers with a fault, so this credential cannot
        // read partners.
        repeat(4) { transport.respondWith { TransportOutcome.Received(200, response(intValue(0))) } }
        transport.respondWith {
            TransportOutcome.Received(
                200,
                """<?xml version="1.0"?><methodResponse><fault><value><struct>
                    <member><name>faultCode</name><value><int>2</int></value></member>
                    <member><name>faultString</name><value><string>Access Error</string></value></member>
                </struct></value></fault></methodResponse>""",
            )
        }
        val capabilities = connector(transport).capabilities()
        assertEquals(false, capabilities.models["res.partner"])
        assertEquals(true, capabilities.models["sale.order"])
        assertTrue(capabilities.notes.any { it.contains("res.partner") })
        assertFalse("an unreadable model is not a batch-read capability", Capability.BATCH_READ in capabilities.capabilities)
        // A read the connector cannot do is missing; it is not reported as
        // present merely because the model name is in the probe list.
        assertTrue(Capability.CUSTOMER_SEARCH in capabilities.capabilities)
    }
}
