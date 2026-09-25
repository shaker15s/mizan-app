package app.mizan.domain

import app.mizan.domain.model.CanonicalJson
import app.mizan.domain.model.CanonicalValue
import app.mizan.domain.model.CreateDraftOrderArgs
import app.mizan.domain.model.Digests
import app.mizan.domain.model.Idempotency
import app.mizan.domain.model.Money
import app.mizan.domain.model.TenantId
import app.mizan.domain.model.ToolName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Money and canonicalisation are the two things everything else hashes on. */
class MoneyAndCanonicalTest {

    @Test
    fun majorUnitsFormatIsStableAndLocaleIndependent() {
        assertEquals("2500.00", Money(250_000L, "USD").majorUnitsFormatted())
        assertEquals("0.00", Money(0L, "USD").majorUnitsFormatted())
        assertEquals("5.05", Money(505L, "EGP").majorUnitsFormatted())
        assertEquals("1234567.89", Money(123_456_789L, "SAR").majorUnitsFormatted())
    }

    @Test
    fun parsingRejectsAnythingThatIsNotAPlainPositiveAmount() {
        assertEquals(Money(250_000L, "USD"), Money.parseMajor("2,500.00", "USD"))
        assertEquals(Money(250_000L, "usd"), Money.parseMajor("2500", "usd"))
        assertNull(Money.parseMajor("-2500", "USD"))
        assertNull(Money.parseMajor("+2500", "USD"))
        assertNull(Money.parseMajor("2.500,00", "USD"))
        assertNull(Money.parseMajor("1e3", "USD"))
        assertNull(Money.parseMajor("abc", "USD"))
        assertNull(Money.parseMajor("", "USD"))
        assertNull(Money.parseMajor("1.005", "USD"))
    }

    @Test
    fun currencyRulesAreEnforced() {
        val usd = Money(100, "USD")
        assertNotEquals(0, usd.compareTo(Money(200, "USD")))
        assertEquals(Money(300, "USD"), usd + Money(200, "USD"))
        assertEquals(Money(-100, "USD"), usd - Money(200, "USD"))
        assertTrue(Money(-1, "USD").isNegative)
        assertTrue(Money(0, "USD").isZero)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            Money(1, "us")
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            Money(1, "US1")
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            Money(1, "usd") + Money(1, "egp")
        }
    }

    @Test
    fun formattingIsPresentationOnly() {
        val formatted = Money(250_000L, "USD").format(Locale.US)
        assertTrue(formatted.contains("2,500.00"))
        assertTrue(formatted.endsWith("USD"))
    }

    @Test
    fun canonicalJsonSortsKeysAndEscapesControlCharacters() {
        val value = CanonicalValue.Obj(
            listOf(
                "b" to CanonicalValue.Num("1"),
                "a" to CanonicalValue.Str("x"),
            ),
        )
        assertEquals("""{"a":"x","b":1}""", CanonicalJson.write(value))
        val control = CanonicalValue.Obj(
            listOf(
                "line" to CanonicalValue.Str("a\nb"),
                "tab" to CanonicalValue.Str("a\tb"),
                "bell" to CanonicalValue.Str("a${7.toChar()}b"),
                "quote" to CanonicalValue.Str("a\"b"),
                "back" to CanonicalValue.Str("a\\b"),
            ),
        )
        val written = CanonicalJson.write(control)
        assertTrue(written.contains("""a\nb"""))
        assertTrue(written.contains("""a\tb"""))
        assertTrue(written.contains("""a\u0007b"""))
        assertTrue(written.contains("""a\"b"""))
        assertTrue(written.contains("""a\\b"""))
        // Escaping must not change the digest of ordinary text.
        assertEquals(
            Digests.sha256(CanonicalJson.write(CanonicalValue.Str("plain"))),
            Digests.sha256("\"plain\""),
        )
    }

    @Test
    fun idempotencyKeysAreDeterministicAndTenantScoped() {
        val tenantA = TenantId("tenant-a")
        val tenantB = TenantId("tenant-b")
        val args = CreateDraftOrderArgs("Acme Corp", Money(250_000L, "USD"), "10 laptops")
        val first = Idempotency.key(tenantA, ToolName.CREATE_DRAFT_ORDER, args)
        val again = Idempotency.key(tenantA, ToolName.CREATE_DRAFT_ORDER, args)
        val otherTenant = Idempotency.key(tenantB, ToolName.CREATE_DRAFT_ORDER, args)
        val trimmed = Idempotency.key(
            tenantA,
            ToolName.CREATE_DRAFT_ORDER,
            CreateDraftOrderArgs("  Acme Corp  ", Money(250_000L, "USD"), "10 laptops "),
        )
        val otherAmount = Idempotency.key(
            tenantA,
            ToolName.CREATE_DRAFT_ORDER,
            CreateDraftOrderArgs("Acme Corp", Money(250_001L, "USD"), "10 laptops"),
        )
        assertEquals(first, again)
        assertNotEquals(first, otherTenant)
        // Trimming is part of canonicalisation, so it must not change the key.
        assertEquals(first, trimmed)
        assertNotEquals(first, otherAmount)
        assertEquals(64, first.value.length)
    }

    @Test
    fun idempotencyRefusesArgumentsThatDoNotMatchTheTool() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            Idempotency.key(
                TenantId("t"),
                ToolName.CANCEL_ORDER,
                CreateDraftOrderArgs("Acme Corp", Money(1L, "USD"), "x"),
            )
        }
    }
}
