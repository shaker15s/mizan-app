package app.mizan.integration

import app.mizan.domain.authority.AuthorityOutcome
import app.mizan.domain.model.ExecutionId
import app.mizan.domain.receipt.ReceiptTrust
import app.mizan.integration.api.MizanApiClient
import app.mizan.integration.http.CallKind
import app.mizan.integration.http.Redactor
import app.mizan.integration.http.RetryPolicy
import app.mizan.integration.odoo.json2.OdooJson2Request
import app.mizan.integration.odoo.legacy.XmlRpcFaultException
import app.mizan.integration.odoo.legacy.XmlRpcParser
import app.mizan.integration.odoo.legacy.XmlRpcSerializer
import app.mizan.domain.model.CanonicalValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrationTest {
    @Test
    fun redactsSecrets() {
        val raw = "Authorization: Bearer abc.def password=hunter2 api_key: sk-live"
        val clean = Redactor.redact(raw)
        assertFalse(clean.contains("hunter2"))
        assertFalse(clean.contains("abc.def"))
        assertFalse(clean.contains("sk-live"))
        assertTrue(clean.contains("[REDACTED]"))
    }

    @Test
    fun writesAreNotRetried() {
        assertFalse(RetryPolicy.mayRetry(CallKind.DESTRUCTIVE, 0, requestWasSent = true, httpStatus = 503))
        assertFalse(RetryPolicy.mayRetry(CallKind.CONDITIONAL_WRITE, 0, requestWasSent = true, httpStatus = null))
        assertTrue(RetryPolicy.mayRetry(CallKind.SAFE_READ, 0, requestWasSent = true, httpStatus = 503))
        assertFalse(RetryPolicy.mayRetry(CallKind.SAFE_READ, 0, requestWasSent = true, httpStatus = null))
    }

    @Test
    fun json2UrlRejectsCleartextAndInjection() {
        val url = OdooJson2Request.url("https://erp.example/", "sale.order", "create")
        assertEquals("https://erp.example/json/2/sale.order/create", url)
        assertThrows(IllegalArgumentException::class.java) {
            OdooJson2Request.url("http://erp.example", "sale.order", "create")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OdooJson2Request.url("https://erp.example", "sale.order", "create/../admin")
        }
    }

    @Test
    fun json2BodyDoesNotContainACredentialField() {
        val body = OdooJson2Request.body(
            CanonicalValue.Obj(listOf("partner_id" to CanonicalValue.Num("5"))),
        )
        assertFalse(body.contains("password"))
        assertFalse(body.contains("api_key"))
    }

    @Test
    fun xmlRpcEscapesAndParsesFault() {
        val xml = XmlRpcSerializer.methodCall("authenticate", listOf("db", "user<script>", "secret"))
        assertFalse(xml.contains("<script>"))
        assertTrue(xml.contains("&lt;script&gt;"))
        val fault = """
            <?xml version="1.0"?><methodResponse><fault><value><struct>
            <member><name>faultCode</name><value><int>3</int></value></member>
            <member><name>faultString</name><value><string>Access Denied: Invalid password</string></value></member>
            </struct></value></fault></methodResponse>
        """.trimIndent()
        val error = assertThrows(XmlRpcFaultException::class.java) { XmlRpcParser.parse(fault) }
        assertEquals(3, error.faultCode)
        assertEquals("Access Denied: Invalid password", error.faultString)
    }

    @Test
    fun http200IsNotVerification() {
        val client = MizanApiClient("https://api.example", { "token" })
        val accepted = client.map(200, """{"status":"accepted","executionId":"EXE-1"}""", ExecutionId("EXE-1"))
        assertTrue(accepted is AuthorityOutcome.AcceptedUnverified)
        val verified = client.map(
            200,
            """{"status":"verified","executionId":"EXE-1","erpRecordId":"SO-9","erpModel":"sale.order"}""",
            ExecutionId("EXE-1"),
        )
        assertTrue(verified is AuthorityOutcome.Verified)
        val empty = client.map(200, """{"ok":true}""", ExecutionId("EXE-1"))
        assertTrue(empty is AuthorityOutcome.Refused)
    }

    @Test
    fun aReceiptIdInTheVerifiedAnswerIsAnInvitationToCheckAndNotACheck() {
        val client = MizanApiClient("https://api.example", { "token" })
        val withReceipt = client.map(
            200,
            """{"status":"verified","executionId":"EXE-1","erpRecordId":"SO-9",""" +
                """"erpModel":"sale.order","receiptId":"RCT-9"}""",
            ExecutionId("EXE-1"),
        ) as AuthorityOutcome.Verified
        val receipt = withReceipt.receipt
        assertNotNull(receipt)
        // Untouched: the device has the id and has not looked at the signature.
        assertEquals("RCT-9", receipt?.receiptId)
        assertEquals(ReceiptTrust.NOT_CHECKED, receipt?.trust)
        assertEquals("RECEIPT_NOT_CHECKED", receipt?.reasonCode)
        assertFalse(receipt?.proven == true)
        // A deployment with no receipt configured is not accused of anything.
        val withoutReceipt = client.map(
            200,
            """{"status":"verified","executionId":"EXE-1","erpRecordId":"SO-9","erpModel":"sale.order"}""",
            ExecutionId("EXE-1"),
        ) as AuthorityOutcome.Verified
        assertNull(withoutReceipt.receipt)
    }
}
