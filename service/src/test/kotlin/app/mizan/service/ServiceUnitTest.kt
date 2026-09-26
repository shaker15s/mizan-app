package app.mizan.service

import app.mizan.domain.model.Digests
import app.mizan.domain.model.Money
import app.mizan.domain.model.Role
import app.mizan.service.erp.InMemoryErp
import app.mizan.service.ledger.AuditLedger
import app.mizan.service.ledger.ExecutionLedger
import app.mizan.service.ledger.LedgerEntry
import app.mizan.service.protocol.MizanContract
import app.mizan.service.security.PasswordHash
import app.mizan.service.security.ServiceUser
import app.mizan.service.security.SessionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the pieces that must hold before any HTTP request exists. */
class ServiceUnitTest {

    private fun user(actorId: String = "USR-1", role: Role = Role.SALES_REP) = ServiceUser.of(
        email = "$actorId@mizan.test",
        password = "correct horse battery staple",
        actorId = actorId,
        displayName = "Test Actor",
        role = role,
        tenantId = "sim-alamal",
        tenantLabel = "Al-Amal Trading",
    )

    @Test
    fun passwordHashVerifiesAndNeverStoresThePassword() {
        val stored = PasswordHash.hash("correct horse battery staple")
        assertTrue(PasswordHash.verify("correct horse battery staple", stored))
        assertFalse(PasswordHash.verify("wrong", stored))
        assertFalse(stored.contains("correct horse battery staple"))
        // A second hash of the same password differs, because the salt differs.
        val second = PasswordHash.hash("correct horse battery staple")
        assertNotEquals(stored, second)
        assertTrue(PasswordHash.verify("correct horse battery staple", second))
        assertFalse(PasswordHash.verify("correct horse battery staple", "pbkdf2${'$'}1${'$'}aaaa${'$'}bbbb"))
    }

    @Test
    fun sessionsExpireAndAreNotExtended() {
        var now = 1_000_000L
        val registry = SessionRegistry(ttlMillis = 1_000L, clock = { now })
        val issued = registry.issue(user())
        val token = issued.first
        assertEquals(1, registry.activeCount())
        assertTrue(registry.resolve(token) != null)

        now += 999L
        assertTrue(registry.resolve(token) != null)

        now += 2L
        assertNull(registry.resolve(token))
        assertEquals(0, registry.activeCount())
    }

    @Test
    fun onlyATokenHashIsKept() {
        val registry = SessionRegistry(ttlMillis = 60_000L)
        val token = registry.issue(user()).first
        assertTrue(token.length >= 40)
        val session = registry.resolve(token)
        assertNotEquals(token, session?.tokenFingerprint)
        assertEquals(Digests.sha256(token).take(16), session?.tokenFingerprint)
        registry.revoke(token)
        assertNull(registry.resolve(token))
    }

    @Test
    fun auditChainLinksEachEventToThePreviousOne() {
        val ledger = AuditLedger { 1_700_000_000_000L }
        assertEquals("CHAIN_EMPTY", ledger.verify("sim-alamal").messageCode)

        val first = ledger.append("sim-alamal", "TRC-1", "USR-1", "A", "before", "after", "one")
        val second = ledger.append("sim-alamal", "TRC-1", "USR-1", "B", "after", "later", "two")
        assertEquals(1L, first.chainIndex)
        assertEquals(2L, second.chainIndex)
        assertEquals(first.currentHash, second.previousHash)

        val report = ledger.verify("sim-alamal")
        assertTrue(report.intact)
        assertEquals(2, report.records)
        assertEquals("CHAIN_INTACT_LOCAL", report.messageCode)
        // A second tenant starts its own chain.
        assertEquals("CHAIN_EMPTY", ledger.verify("other-tenant").messageCode)
    }

    @Test
    fun executionLedgerIsScopedByTenant() {
        val ledger = ExecutionLedger()
        val entry = LedgerEntry(
            tenantId = "sim-alamal",
            key = "key-1",
            canonicalArguments = "{}",
            executionId = "EXE-1",
            traceId = "TRC-1",
            status = MizanContract.Status.VERIFIED,
            erpRecordId = "SO-1001",
            erpModel = MizanContract.ErpModel.SALE_ORDER,
            messageCode = "VERIFIED_BY_READ_BACK",
            candidateRecordIds = emptyList(),
            summary = null,
        )
        ledger.record(entry)
        assertEquals(entry, ledger.find("sim-alamal", "key-1"))
        assertNull(ledger.find("other-tenant", "key-1"))
        assertEquals(1, ledger.size())
    }

    @Test
    fun readBackIsTheOnlyThingThatMakesAWriteVerified() {
        val erp = InMemoryErp()
        val write = erp.createDraftOrder("sim-alamal", "Acme Corp", Money(250_000L, "USD"), "10 laptops")
        val read = erp.readBack("sim-alamal", write.model, write.recordId)
        assertTrue(read != null)
        assertTrue(read?.summary?.contains("draft") == true)

        // Cancellation is a mutation that is read back in its new state.
        assertTrue(erp.cancelOrder("sim-alamal", write.recordId, "withdrawn") != null)
        assertTrue(erp.readBack("sim-alamal", write.model, write.recordId)?.summary?.contains("cancelled") == true)

        // A cancelled order cannot be invoiced, so a payment chain stops here.
        assertNull(erp.createInvoice("sim-alamal", write.recordId))
        assertNull(erp.cancelOrder("sim-alamal", write.recordId, "again"))
        assertNull(erp.cancelOrder("sim-alamal", "SO-does-not-exist", "reason"))
    }

    @Test
    fun tenantScopingHoldsInsideTheErpAdapter() {
        val erp = InMemoryErp()
        val write = erp.createDraftOrder("tenant-a", "Acme Corp", Money(1_000L, "USD"), "1 lamp")
        assertNull(erp.readBack("tenant-b", write.model, write.recordId))
        assertNull(erp.cancelOrder("tenant-b", write.recordId, "reason"))
        assertEquals(0, erp.candidates("tenant-b", write.model).size)
        assertEquals(listOf(write.recordId), erp.candidates("tenant-a", write.model))
    }
}
