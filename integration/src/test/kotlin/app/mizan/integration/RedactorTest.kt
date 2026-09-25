package app.mizan.integration

import app.mizan.integration.http.Redactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The redactor is the last line before a string reaches a log or a crash
 * report. It must fail toward silence, never toward leakage.
 */
class RedactorTest {

    @Test
    fun masksBearerBasicAndNamedSecrets() {
        val raw = "Authorization: Bearer abc.def.ghi password=hunter2 api_key: sk-live Basic dXNlcjpwYXNz"
        val clean = Redactor.redact(raw)
        listOf("abc.def.ghi", "hunter2", "sk-live", "dXNlcjpwYXNz").forEach { secret ->
            assertFalse("'$secret' must not survive redaction", clean.contains(secret))
        }
        assertTrue(clean.contains("[REDACTED]"))
    }

    @Test
    fun masksAJwtEvenWithoutAFieldName() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.7hK3pQ9yV2mZ1sT4uW8xR0aBcDeFgHiJ"
        val clean = Redactor.redact("forwarding $jwt upstream")
        assertFalse(clean.contains(jwt))
        assertTrue(clean.contains("[REDACTED]"))
    }

    @Test
    fun leavesOrdinaryTextAlone() {
        val text = "order SO-1001 for Acme Corp amount 2500 USD"
        assertEquals(text, Redactor.redact(text))
    }

    @Test
    fun isIdempotent() {
        val raw = "token=abcdef123456 bearer xyz.abc"
        val once = Redactor.redact(raw)
        assertEquals(once, Redactor.redact(once))
    }

    @Test
    fun handlesAnEmptyString() {
        assertEquals("", Redactor.redact(""))
    }
}
