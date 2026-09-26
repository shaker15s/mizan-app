package app.mizan.service

import app.mizan.domain.model.CancelOrderArgs
import app.mizan.domain.model.CanonicalJson
import app.mizan.domain.model.CreateDraftOrderArgs
import app.mizan.domain.model.CreateInvoiceArgs
import app.mizan.domain.model.CustomerSearchArgs
import app.mizan.domain.model.Money
import app.mizan.domain.model.RegisterPaymentArgs
import app.mizan.domain.model.SalesSummaryArgs
import app.mizan.domain.model.StockLookupArgs
import app.mizan.domain.model.TenantId
import app.mizan.domain.model.ToolArgs
import app.mizan.domain.model.ToolName
import app.mizan.service.json.Json
import app.mizan.service.json.asObject
import app.mizan.service.json.text
import app.mizan.service.json.whole
import app.mizan.service.protocol.ExecutionRequest
import app.mizan.service.protocol.MizanContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client and the service are two modules that never see each other.
 * These tests are the only thing standing between them and a silent drift in
 * the argument names, which is why they build the exact bytes the phone
 * sends -- by calling the phone's own canonicaliser -- and then parse them
 * the way the service parses a request.
 */
class ContractParityTest {

    private val tenant = TenantId("sim-alamal")

    private fun body(args: ToolArgs, approverId: String = "USR-MGR"): String {
        val arguments = CanonicalJson.write(args.canonical())
        return """{"tool":"${args.tool.wire}","toolVersion":"${args.tool.version}",""" +
            """"tenantId":"${tenant.value}","executionId":"EXE-1","proposalId":"PRP-1",""" +
            """"traceId":"TRC-1","approverId":"$approverId","idempotencyKey":null,""" +
            """"arguments":$arguments}"""
    }

    private fun parse(args: ToolArgs): ExecutionRequest {
        val parsed = Json.parse(body(args)).asObject() ?: error("body must be JSON")
        return ExecutionRequest.parse(
            body = parsed,
            fallbackExecutionId = "EXE-FALLBACK",
            traceHeader = "TRC-HEADER",
            idempotencyHeader = "IDEM-HEADER",
        ) ?: error("the service must accept what the client sends")
    }

    @Test
    fun everyToolArgumentNameSurvivesTheTrip() {
        val cases = listOf(
            StockLookupArgs("SKU-DESK-01"),
            CustomerSearchArgs("Acme"),
            CreateDraftOrderArgs("Acme Corp", Money(250_000L, "USD"), "10 laptops"),
            CancelOrderArgs("SO-1001", "customer withdrew"),
            CreateInvoiceArgs("SO-1001"),
            RegisterPaymentArgs("INV-5001", Money(7_000L, "USD")),
            SalesSummaryArgs("current"),
        )
        for (args in cases) {
            val request = parse(args)
            assertEquals(args.tool.wire, request.toolWire)
            assertEquals(args.tool.version, request.toolVersion)
            assertEquals(tenant.value, request.tenantId)
            assertEquals("TRC-HEADER", request.traceId)
            assertEquals("IDEM-HEADER", request.idempotencyKey)
            val fields = MizanContract.ArgumentField
            when (args) {
                is StockLookupArgs -> assertEquals(args.sku, request.arguments.text(fields.SKU))
                is CustomerSearchArgs -> assertEquals(args.query, request.arguments.text(fields.QUERY))
                is CreateDraftOrderArgs -> {
                    assertEquals(args.customerName, request.arguments.text(fields.CUSTOMER_NAME))
                    assertEquals(args.itemsSummary, request.arguments.text(fields.ITEMS_SUMMARY))
                    assertEquals(args.amount.minorUnits, request.arguments.whole(fields.AMOUNT_MINOR))
                    assertEquals(args.amount.currency, request.arguments.text(fields.CURRENCY))
                }
                is CancelOrderArgs -> {
                    assertEquals(args.orderId, request.arguments.text(fields.ORDER_ID))
                    assertEquals(args.reason, request.arguments.text(fields.REASON))
                }
                is CreateInvoiceArgs -> assertEquals(args.orderId, request.arguments.text(fields.ORDER_ID))
                is RegisterPaymentArgs -> {
                    assertEquals(args.invoiceId, request.arguments.text(fields.INVOICE_ID))
                    assertEquals(args.amount.minorUnits, request.arguments.whole(fields.AMOUNT_MINOR))
                }
                is SalesSummaryArgs -> assertEquals(args.periodCode, request.arguments.text(fields.PERIOD))
            }
        }
    }

    @Test
    fun theClientCanonicalFormIsWhatTheServiceHashesForIdempotency() {
        val args = CreateDraftOrderArgs("Acme Corp", Money(250_000L, "USD"), "10 laptops")
        val request = parse(args)
        // The service stores this string and compares it on a replay, so the
        // client's canonical form must be reproduced byte for byte.
        assertEquals(CanonicalJson.write(args.canonical()), request.canonicalArguments())
    }

    @Test
    fun everyKnownToolHasAWireNameTheServiceAccepts() {
        ToolName.entries.filter { it != ToolName.UNKNOWN }.forEach { tool ->
            assertTrue(tool.wire.isNotBlank())
            assertEquals(tool, ToolName.fromWire(tool.wire))
        }
    }

    @Test
    fun theSimulatorIsNotOneOfTheServiceStatuses() {
        val statuses = setOf(
            MizanContract.Status.VERIFIED,
            MizanContract.Status.ACCEPTED,
            MizanContract.Status.AMBIGUOUS,
            MizanContract.Status.REJECTED,
            MizanContract.Status.FAILED,
        )
        assertEquals(5, statuses.size)
        assertTrue(statuses.none { it.contains("simulated", ignoreCase = true) })
    }
}
