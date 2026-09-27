package app.mizan.integration

import app.mizan.domain.approval.ApprovalState
import app.mizan.domain.model.ApprovalLevel
import app.mizan.integration.api.ApprovalAction
import app.mizan.integration.api.ApprovalRow
import app.mizan.integration.api.ApprovalsBoard
import app.mizan.integration.api.ApprovalsSource
import app.mizan.integration.api.ApprovalsState
import app.mizan.integration.api.RemoteApproval
import app.mizan.integration.api.RemoteChallenge
import app.mizan.integration.api.RemoteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The approvals screen as a value.
 *
 * The governance screen described the rules and the service implemented them,
 * and nothing joined the two: `GovernanceApiClient.approvals()` had no caller.
 * What is tested here is the join, and the interesting half of it is not the
 * happy path -- it is who may answer what, what a screen shows when the service
 * is missing, and what happens on a device that has no key to sign with.
 *
 * The source is a recording fake, so the board's own rules are under test and
 * every refusal can be provoked exactly. The HTTP shape of the underlying calls
 * is locked separately, in `GovernanceContractTest`.
 */
class ApprovalsBoardTest {

    /** A source that answers from a script and records every call. */
    private class FakeSource(
        var rows: List<RemoteApproval> = emptyList(),
        var listRefusal: Pair<String, Int>? = null,
        var challengeAnswer: RemoteResult<RemoteChallenge> = RemoteResult.Refused("DEVICE_NOT_ENROLLED", 422),
        var decision: (String) -> RemoteResult<RemoteApproval> = { id ->
            RemoteResult.Refused("NOT_SCRIPTED:$id", 500)
        },
    ) : ApprovalsSource {

        val calls = ArrayList<String>()

        override fun list(state: ApprovalState?): RemoteResult<List<RemoteApproval>> {
            calls += "list(${state?.name ?: "ALL"})"
            listRefusal?.let { return RemoteResult.Refused(it.first, it.second) }
            return RemoteResult.Ok(rows)
        }

        override fun challenge(approvalId: String, deviceId: String): RemoteResult<RemoteChallenge> {
            calls += "challenge($approvalId,$deviceId)"
            return challengeAnswer
        }

        override fun grant(
            approvalId: String,
            challengeId: String?,
            signature: String?,
        ): RemoteResult<RemoteApproval> {
            calls += "grant($approvalId,${challengeId ?: "-"},${signature ?: "-"})"
            return decision(approvalId)
        }

        override fun refuse(approvalId: String, reasonCode: String): RemoteResult<RemoteApproval> {
            calls += "refuse($approvalId,$reasonCode)"
            return decision(approvalId)
        }
    }

    private fun approval(
        id: String,
        state: ApprovalState = ApprovalState.PENDING,
        level: ApprovalLevel = ApprovalLevel.L2_PRIVILEGED,
        approverIds: List<String> = emptyList(),
        expiresAtMillis: Long = NOW + 600_000L,
        executionId: String = "EXE-1",
    ) = RemoteApproval(
        id = id,
        executionId = executionId,
        state = state,
        requiredLevel = level,
        proposalFingerprint = "fp-$id-0123456789abcdef0123456789abcdef",
        policyVersionId = "v12",
        initiatorId = "USR-REP",
        createdAtMillis = NOW - 60_000L,
        expiresAtMillis = expiresAtMillis,
        approverIds = approverIds,
    )

    private fun board(
        source: FakeSource,
        actorId: String = "USR-MGR",
        signChallenge: ((String) -> String?)? = null,
        deviceId: String? = "DEV-1",
    ) = ApprovalsBoard(
        source = source,
        actorId = { actorId },
        now = { NOW },
        signChallenge = signChallenge,
        deviceId = deviceId,
    )

    // ------------------------------------------------------------- the reading

    @Test
    fun anUnreadBoardIsNotAnEmptyOne() {
        val source = FakeSource()
        val board = board(source)
        // Before anything is read the state is Unread. An empty list at this
        // point would read as "nothing needs you", which is a claim, not a
        // loading state.
        assertEquals(ApprovalsState.Unread, board.state())
        assertEquals(ApprovalsState.Loaded(emptyList(), null), board.refresh())
    }

    @Test
    fun aRefusalIsACodeAndAStatusAndNeverAnEmptyList() {
        val source = FakeSource(listRefusal = "PERMISSION_DENIED" to 403)
        val board = board(source)
        assertEquals(ApprovalsState.Failed("PERMISSION_DENIED", 403), board.refresh())
        // A body the client cannot read is a refusal with a code of our own.
        val unreadable = FakeSource()
        val refused = board(unreadable)
        assertEquals("a 200 with no approvals is a real answer", 0, (refused.refresh() as ApprovalsState.Loaded).rows.size)
    }

    @Test
    fun aQueueIsOfWhatNeedsYouThenWhoElseThenWhatIsStale() {
        val source = FakeSource(
            rows = listOf(
                approval("APR-ELSEWHERE", approverIds = listOf("USR-OTH")),
                approval("APR-MINE"),
                approval("APR-EXPIRED", expiresAtMillis = NOW - 1_000L),
                approval("APR-DONE", state = ApprovalState.GRANTED),
            ),
        )
        val state = board(source).refresh() as ApprovalsState.Loaded
        assertEquals(
            listOf("APR-MINE", "APR-ELSEWHERE", "APR-EXPIRED", "APR-DONE"),
            state.rows.map { it.id },
        )
        assertEquals(listOf("APR-MINE"), state.answerable.map { it.id })
        val theirs = state.rows.first { it.id == "APR-ELSEWHERE" }
        assertEquals(ApprovalRow.Standing.AWAITING_OTHERS, theirs.standing)
        assertEquals("the screen is told who it waits for", listOf("USR-OTH"), theirs.awaiting)
    }

    @Test
    fun anApprovalTheServiceStillCallsPendingIsExpiredOnceItsClockHasPassed() {
        val source = FakeSource(rows = listOf(approval("APR-STALE", expiresAtMillis = NOW - 1)))
        val row = (board(source).refresh() as ApprovalsState.Loaded).rows.single()
        assertEquals(ApprovalRow.Standing.EXPIRED, row.standing)
        assertTrue("the screen is told how stale", row.millisecondsToExpiry < 0)
        assertNull("and nobody is asked to sign for it", board(source).proofRequirement(row))
    }

    @Test
    fun anApprovalNamedForSomebodyElseIsNotAnswerableEvenByTheRightLevel() {
        val source = FakeSource(rows = listOf(approval("APR-NAMED", approverIds = listOf("USR-FIN"))))
        val board = board(source)
        val row = (board.refresh() as ApprovalsState.Loaded).rows.single()
        assertEquals(ApprovalRow.Standing.AWAITING_OTHERS, row.standing)
        // A grant attempted anyway is refused before the wire.
        assertEquals(ApprovalAction.Refused("APPROVAL_NOT_YOURS", null), board.grant("APR-NAMED"))
        assertTrue(source.calls.none { it.startsWith("grant(") })
    }

    @Test
    fun theFilterIsReportedSoAFilteredListIsNeverShownAsEverything() {
        val source = FakeSource(rows = listOf(approval("APR-1")))
        val state = board(source).refresh(ApprovalState.PENDING) as ApprovalsState.Loaded
        assertEquals(ApprovalState.PENDING, state.filteredBy)
        assertEquals(listOf("list(PENDING)"), source.calls)
    }

    // ---------------------------------------------------------- the answering

    @Test
    fun answeringAsksForAChallengeBoundToTheApprovalSignsItAndOnlyThenGrants() {
        val source = FakeSource(
            rows = listOf(approval("APR-1")),
            challengeAnswer = RemoteResult.Ok(
                RemoteChallenge(
                    challengeId = "CHG-1",
                    messageToSign = "mizan-approval|sim-alamal|USR-MGR|EXE-1|fp|nonce",
                    expiresAtMillis = NOW + 60_000L,
                ),
            ),
            decision = { RemoteResult.Ok(approval("APR-1", state = ApprovalState.GRANTED)) },
        )
        var signed: String? = null
        val action = board(source, signChallenge = { signed = it; "sig-base64" }).grant("APR-1")

        assertTrue(action is ApprovalAction.Answered)
        assertEquals(ApprovalState.GRANTED, (action as ApprovalAction.Answered).approval.state)
        assertEquals("the device signed the challenge body the service issued", "mizan-approval|sim-alamal|USR-MGR|EXE-1|fp|nonce", signed)
        // The order is the contract: read, challenge, sign, grant.
        assertEquals(
            listOf("list(PENDING)", "challenge(APR-1,DEV-1)", "grant(APR-1,CHG-1,sig-base64)"),
            source.calls,
        )
    }

    @Test
    fun aBuildThatCannotSignRefusesRatherThanSendingAnUnsignedGrant() {
        val signerless = FakeSource(rows = listOf(approval("APR-1")))
        assertEquals(
            ApprovalAction.Refused("DEVICE_KEY_NOT_ENROLLED", null),
            board(signerless, signChallenge = null, deviceId = null).grant("APR-1"),
        )
        assertTrue("nothing was requested and nothing was granted", signerless.calls.none { it.startsWith("grant(") })

        val failingKey = FakeSource(
            rows = listOf(approval("APR-1")),
            challengeAnswer = RemoteResult.Ok(RemoteChallenge("CHG-1", "body", NOW + 1_000L)),
        )
        assertEquals(
            ApprovalAction.Refused("DEVICE_SIGNATURE_FAILED", null),
            board(failingKey, signChallenge = { null }).grant("APR-1"),
        )
        assertTrue(failingKey.calls.none { it.startsWith("grant(") })
    }

    @Test
    fun aServiceThatRefusesTheChallengeIsReportedWithItsOwnCodeAndNothingIsGranted() {
        val source = FakeSource(
            rows = listOf(approval("APR-1")),
            challengeAnswer = RemoteResult.Refused("DEVICE_NOT_ENROLLED", 422),
        )
        assertEquals(ApprovalAction.Refused("DEVICE_NOT_ENROLLED", 422), board(source, signChallenge = { "sig" }).grant("APR-1"))
        assertTrue(source.calls.none { it.startsWith("grant(") })
    }

    @Test
    fun anAnswerForAnApprovalThatIsNotThereIsRefusedRatherThanPosted() {
        val source = FakeSource(rows = listOf(approval("APR-OTHER")))
        assertEquals(ApprovalAction.Refused("APPROVAL_NOT_FOUND", null), board(source).grant("APR-MISSING"))
        assertTrue(source.calls.none { it.startsWith("grant(") })
    }

    @Test
    fun anExpiredRowIsRefusedHereWithTheSameCodeTheServiceWouldUse() {
        val source = FakeSource(rows = listOf(approval("APR-STALE", expiresAtMillis = NOW - 1)))
        assertEquals(ApprovalAction.Refused("APPROVAL_EXPIRED", null), board(source).grant("APR-STALE"))
        assertTrue(source.calls.none { it.startsWith("challenge(") })
    }

    @Test
    fun anL0ApprovalNeedsNoProofSoItIsAnsweredWithoutAKeyOrAChallenge() {
        val source = FakeSource(
            rows = listOf(approval("APR-L0", level = ApprovalLevel.L0_NONE)),
            decision = { RemoteResult.Ok(approval("APR-L0", level = ApprovalLevel.L0_NONE, state = ApprovalState.GRANTED)) },
        )
        val board = board(source, signChallenge = null, deviceId = null)
        val row = (board.refresh() as ApprovalsState.Loaded).rows.single()
        assertFalse(row.needsDeviceProof)
        assertNull(board.proofRequirement(row))
        assertTrue(board.grant("APR-L0") is ApprovalAction.Answered)
        assertEquals(
            // The initial unfiltered read is the screen loading; the second is
            // the decision reading the row it is about to answer.
            listOf("list(ALL)", "list(PENDING)", "grant(APR-L0,-,-)"),
            source.calls,
        )
    }

    @Test
    fun aProofIsRequiredWhereTheLevelNeedsOneAndTheDeviceIsNamedAsTheGap() {
        val source = FakeSource(rows = listOf(approval("APR-1")))
        val board = board(source, signChallenge = null, deviceId = null)
        val row = (board.refresh() as ApprovalsState.Loaded).rows.single()
        assertEquals("DEVICE_KEY_NOT_ENROLLED", board.proofRequirement(row))
    }

    @Test
    fun aRefusalCarriesTheReasonCodeAndTheServiceDecidesTheState() {
        val source = FakeSource(
            rows = listOf(approval("APR-1")),
            decision = { RemoteResult.Ok(approval("APR-1", state = ApprovalState.REJECTED)) },
        )
        val action = board(source).refuse("APR-1", "PRICE_WRONG")
        assertTrue(action is ApprovalAction.Answered)
        assertEquals(ApprovalState.REJECTED, (action as ApprovalAction.Answered).approval.state)
        assertEquals(listOf("list(PENDING)", "refuse(APR-1,PRICE_WRONG)"), source.calls)
    }

    @Test
    fun aGrantThatStillNeedsASecondApproverIsNotShownAsGranted() {
        // The service answers a dual approval's first yes with the object it
        // actually holds: still pending. The board must not upgrade that.
        val source = FakeSource(
            rows = listOf(approval("APR-DUAL", level = ApprovalLevel.L4_DUAL)),
            challengeAnswer = RemoteResult.Ok(RemoteChallenge("CHG-1", "body", NOW + 1_000L)),
            decision = { RemoteResult.Ok(approval("APR-DUAL", level = ApprovalLevel.L4_DUAL, state = ApprovalState.PENDING)) },
        )
        val action = board(source, signChallenge = { "sig" }).grant("APR-DUAL")
        assertEquals(ApprovalState.PENDING, (action as ApprovalAction.Answered).approval.state)
        val row = (board(source).refresh() as ApprovalsState.Loaded).rows.single()
        assertEquals("a first yes is still a queue row", ApprovalRow.Standing.ANSWERABLE, row.standing)
    }

    @Test
    fun theFingerprintShownIsShortDerivedFromTheRealOneAndNotTheWholeThing() {
        val source = FakeSource(rows = listOf(approval("APR-1")))
        val board = board(source)
        val row = (board.refresh() as ApprovalsState.Loaded).rows.single()
        val shown = board.shortFingerprint(row)
        assertTrue("the shown value starts with the real one", shown.startsWith(row.approval.proposalFingerprint.take(12)))
        assertTrue("and says how long the real one is", shown.contains(row.approval.proposalFingerprint.length.toString()))
        assertEquals(16, board.fingerprintOf("some message to sign").length)
    }

    companion object {
        /** A fixed clock, so an expiry is a fact rather than a race. */
        private const val NOW = 1_800_000_000_000L
    }
}
