package app.mizan.domain

import app.mizan.domain.error.DispatchState
import app.mizan.domain.execution.ExecutionJournal
import app.mizan.domain.execution.ExecutionJournalTestFactory.journal
import app.mizan.domain.execution.JournalAdvance
import app.mizan.domain.execution.JournalEvent
import app.mizan.domain.execution.JournalStage
import app.mizan.domain.execution.JournalStateMachine
import app.mizan.domain.execution.JournalTransition
import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.RiskTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The state machine is the one place that decides whether an execution may
 * move. These tests are about *forbidden* moves as much as allowed ones: a
 * machine that lets an uncertain write go back to dispatching is worse than no
 * machine at all, because it looks like governance.
 */
class ExecutionJournalTest {

    private val t0: Instant = Instant.parse("2026-09-26T09:00:00Z")
    private val machine = JournalStateMachine()

    /** An entry that has been authorised and dispatched, without an answer yet. */
    private fun dispatchedJournal(): ExecutionJournal = machine.run(
        JournalStage.PROPOSED,
        listOf(
            JournalEvent.APPROVAL_REQUESTED,
            JournalEvent.APPROVED,
            JournalEvent.AUTHORIZED,
            JournalEvent.DISPATCH_STARTED,
        ),
    ).let { step ->
        assertEquals(JournalTransition.Allowed(JournalStage.DISPATCHING), step)
        journal(stage = JournalStage.DISPATCHING, revision = 4L)
    }

    @Test
    fun happyPathReachesVerified() {
        val path = listOf(
            JournalEvent.APPROVAL_REQUESTED,
            JournalEvent.APPROVED,
            JournalEvent.AUTHORIZED,
            JournalEvent.DISPATCH_STARTED,
            JournalEvent.DISPATCH_ACCEPTED,
            JournalEvent.VERIFICATION_STARTED,
            JournalEvent.VERIFIED,
        )
        assertEquals(JournalTransition.Allowed(JournalStage.VERIFIED), machine.run(JournalStage.PROPOSED, path))
    }

    @Test
    fun aReadIsDispatchedAndAcceptedButNeverVerified() {
        // A read is a call to the ERP like any other, so it is dispatched --
        // but there is nothing to verify, so `VERIFIED` is unreachable for it.
        val read = listOf(
            JournalEvent.APPROVAL_REQUESTED,
            JournalEvent.APPROVED,
            JournalEvent.AUTHORIZED,
            JournalEvent.DISPATCH_STARTED,
            JournalEvent.DISPATCH_ACCEPTED,
        )
        assertEquals(
            JournalTransition.Allowed(JournalStage.ACCEPTED),
            machine.run(JournalStage.PROPOSED, read),
        )
        assertTrue(machine.next(JournalStage.ACCEPTED, JournalEvent.VERIFIED) is JournalTransition.Illegal)
        assertTrue(machine.next(JournalStage.AUTHORIZED, JournalEvent.VERIFIED) is JournalTransition.Illegal)
    }

    @Test
    fun aReadThatFailedIsAFailureNotAnAmbiguity() {
        // Nothing was written, so there is nothing to reconcile: the journal
        // says FAILED and the caller may safely ask again.
        val dispatching = machine.run(
            JournalStage.PROPOSED,
            listOf(
                JournalEvent.APPROVAL_REQUESTED,
                JournalEvent.APPROVED,
                JournalEvent.AUTHORIZED,
                JournalEvent.DISPATCH_STARTED,
            ),
        )
        assertEquals(JournalTransition.Allowed(JournalStage.DISPATCHING), dispatching)
        assertEquals(
            JournalTransition.Allowed(JournalStage.FAILED),
            machine.next(JournalStage.DISPATCHING, JournalEvent.FAILED),
        )
    }

    @Test
    fun verifiedCannotBeReachedWithoutVerificationStarted() {
        val illegal = machine.next(JournalStage.ACCEPTED, JournalEvent.VERIFIED)
        assertTrue(illegal is JournalTransition.Illegal)
        assertEquals(JournalStage.ACCEPTED, (illegal as JournalTransition.Illegal).from)
    }

    @Test
    fun dispatchingCannotStartBeforeAuthorization() {
        assertTrue(machine.next(JournalStage.PROPOSED, JournalEvent.DISPATCH_STARTED) is JournalTransition.Illegal)
        assertTrue(machine.next(JournalStage.WAITING_APPROVAL, JournalEvent.DISPATCH_STARTED) is JournalTransition.Illegal)
    }

    @Test
    fun anUncertainWriteCanOnlyMoveForwardToReconciliation() {
        // From every state that has already touched the ERP, reconciliation is
        // legal, and going back to a dispatch is not. Before the ERP was
        // touched there is nothing to reconcile, so it is illegal there.
        assertTrue(
            machine.next(JournalStage.PROPOSED, JournalEvent.REQUIRE_RECONCILIATION) is JournalTransition.Illegal,
        )
        listOf(JournalStage.DISPATCHING, JournalStage.ACCEPTED, JournalStage.VERIFYING).forEach { stage ->
            assertTrue(
                "$stage must be able to require reconciliation",
                machine.next(stage, JournalEvent.REQUIRE_RECONCILIATION) is JournalTransition.Allowed,
            )
            assertTrue(
                "$stage must never return to dispatching",
                machine.next(stage, JournalEvent.DISPATCH_STARTED) is JournalTransition.Illegal,
            )
        }
    }

    @Test
    fun reconciliationRequiredIsTerminalForTheOrdinaryPath() {
        val moved = machine.run(
            JournalStage.ACCEPTED,
            listOf(JournalEvent.REQUIRE_RECONCILIATION, JournalEvent.VERIFIED),
        )
        assertTrue(moved is JournalTransition.Illegal)
    }

    @Test
    fun terminalStagesAreRecognised() {
        assertTrue(JournalStage.VERIFIED.terminal)
        assertTrue(JournalStage.REJECTED.terminal)
        assertTrue(JournalStage.FAILED.terminal)
        assertTrue(JournalStage.RECONCILIATION_REQUIRED.terminal)
        assertFalse(JournalStage.AUTHORIZED.terminal)
        assertFalse(JournalStage.DISPATCHING.terminal)
    }

    @Test
    fun anIllegalEventDoesNotMutateTheJournal() {
        val start = journal()
        val outcome = start.advance(JournalEvent.DISPATCH_STARTED, t0, machine)
        assertTrue(outcome is JournalAdvance.Illegal)
        assertEquals(0L, start.revision)
        assertEquals(JournalStage.PROPOSED, start.stage)
    }

    @Test
    fun everyLegalMoveBumpsTheRevisionAndTheTimestamp() {
        val start = journal()
        val moved = (start.advance(JournalEvent.APPROVAL_REQUESTED, t0, machine) as JournalAdvance.Moved).journal
        assertEquals(1L, moved.revision)
        assertEquals(t0, moved.updatedAt)
        assertEquals(JournalStage.WAITING_APPROVAL, moved.stage)

        val later = t0.plusSeconds(30)
        val approved = (moved.advance(JournalEvent.APPROVED, later, machine) as JournalAdvance.Moved).journal
        assertEquals(2L, approved.revision)
        assertEquals(later, approved.updatedAt)
        assertEquals(t0, approved.createdAt)
    }

    @Test
    fun dispatchTimesAreStampedOnceAndOnlyOnce() {
        val dispatching = (journal()
            .advance(JournalEvent.APPROVAL_REQUESTED, t0) as JournalAdvance.Moved).journal
        val authorized = (dispatching.advance(JournalEvent.APPROVED, t0) as JournalAdvance.Moved).journal
        val sent = (authorized.advance(JournalEvent.AUTHORIZED, t0) as JournalAdvance.Moved).journal
        val started = (sent.advance(JournalEvent.DISPATCH_STARTED, t0) as JournalAdvance.Moved).journal
        val accepted = (started.advance(JournalEvent.DISPATCH_ACCEPTED, t0.plusSeconds(1)) as JournalAdvance.Moved)
            .journal
        assertEquals(t0, started.dispatchStartedAt)
        assertEquals(t0.plusSeconds(1), accepted.dispatchFinishedAt)
        assertEquals(DispatchState.SENT, started.dispatch)
    }

    @Test
    fun anAmbiguousDispatchMarksTheStateAsUnknown() {
        val dispatched = dispatchedJournal()
        val moved = (dispatched.advance(JournalEvent.REQUIRE_RECONCILIATION, t0) as JournalAdvance.Moved).journal
        assertEquals(DispatchState.UNKNOWN, moved.dispatch)
        assertEquals(JournalStage.RECONCILIATION_REQUIRED, moved.stage)
    }

    @Test
    fun aFailureRecordsItsCodeOnce() {
        val failed = (dispatchedJournal().advance(JournalEvent.FAILED, t0) as JournalAdvance.Moved).journal
        assertEquals("EXECUTION_FAILED", failed.errorCode)
        assertEquals(JournalStage.FAILED, failed.stage)
        // A second failure event is illegal from a terminal stage: the first
        // code stands, and nothing overwrites it.
        assertTrue(failed.advance(JournalEvent.FAILED, t0) is JournalAdvance.Illegal)
    }

    @Test
    fun aFailureCannotBeReachedBeforeTheErpWasCalled() {
        // FAILED is a statement about a dispatch. Before one happened the only
        // honest transitions are approval states or a rejection.
        assertTrue(machine.next(JournalStage.PROPOSED, JournalEvent.FAILED) is JournalTransition.Illegal)
        assertTrue(machine.next(JournalStage.AUTHORIZED, JournalEvent.FAILED) is JournalTransition.Illegal)
        assertEquals(
            JournalTransition.Allowed(JournalStage.REJECTED),
            machine.next(JournalStage.PROPOSED, JournalEvent.REJECTED),
        )
    }

    @Test
    fun aReopenedFailureStartsOverFromProposed() {
        val reopened = machine.run(
            JournalStage.FAILED,
            listOf(JournalEvent.REOPEN, JournalEvent.APPROVAL_REQUESTED),
        )
        assertEquals(JournalTransition.Allowed(JournalStage.WAITING_APPROVAL), reopened)
    }

    @Test
    fun aLeaseNamesWhenTheWorkerLostTheExecution() {
        val leased = journal().copy(leaseExpiresAt = t0.plusSeconds(60))
        assertEquals(t0.plusSeconds(60), leased.leaseExpiresAt)
        assertNotEquals(leased, journal())
    }

    @Test
    fun theCanonicalHashIsPartOfTheEntrysIdentity() {
        val first = journal()
        val second = journal().copy(canonicalInputHash = "different")
        assertNotEquals(first, second)
        assertNotEquals(first.canonicalInputHash, second.canonicalInputHash)
    }

    @Test
    fun twoExecutionsOfTheSameArgumentsStillDifferInIdentity() {
        // The key is what makes a repeat a replay, and the execution id is what
        // makes a record a record. Both are stored, neither substitutes for
        // the other.
        val first = journal()
        val second = journal().copy(executionId = app.mizan.domain.model.ExecutionId("EXE-TEST-0002"))
        assertNotEquals(first.executionId, second.executionId)
        assertEquals(first.idempotencyKey, second.idempotencyKey)
    }

    @Test
    fun aJournalEntryCannotBeBuiltWithoutItsPolicyIdentity() {
        val entry = journal()
        assertEquals("12", entry.policyVersionId)
        assertEquals(64, entry.policyHash.length)
        assertEquals(ApprovalLevel.L2_PRIVILEGED, entry.approvalLevel)
        assertEquals(RiskTier.R2_MEDIUM, entry.riskTier)
        assertEquals(app.mizan.domain.execution.ExecutionJournalTestFactory.TOOL_VERSION, entry.toolVersion)
    }
}
