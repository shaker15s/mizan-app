package app.mizan.service.authority

import app.mizan.domain.execution.ExecutionJournal
import app.mizan.domain.execution.JournalStage
import app.mizan.service.ledger.ExecutionLedger
import app.mizan.service.ledger.LedgerEntry
import app.mizan.service.protocol.ExecutionOutcome
import app.mizan.service.protocol.ExecutionRequest
import app.mizan.service.protocol.MizanContract
import app.mizan.service.protocol.executionIdOf
import app.mizan.service.protocol.messageCodeOf
import app.mizan.service.protocol.statusOf
import app.mizan.service.store.ServiceStores

/**
 * What a key already did.
 *
 * A key is the identity of a request, so this answers three questions and no
 * more: has this exact request already been answered, has this key been used
 * for a different request (a client bug or an attack, refused rather than
 * treated as a retry), and what was the answer. The answer is replayed whole,
 * receipt included: a replay that dropped the proof would be a weaker answer
 * to the very same question.
 */
internal class IdempotencyView(
    private val ledger: ExecutionLedger,
    private val stores: ServiceStores?,
    private val clock: () -> Long,
) {

    sealed interface Prior {
        data object None : Prior

        data class Replay(val entry: Entry) : Prior

        data class Conflict(val code: String, val httpStatus: Int) : Prior
    }

    /** The remembered answer, in the shape the journal stores it. */
    data class Entry(
        val canonicalArguments: String,
        val executionId: String,
        val status: String,
        val stage: JournalStage,
        val erpRecordId: String?,
        val erpModel: String?,
        val messageCode: String,
        val candidateRecordIds: List<String>,
        val summary: String?,
        /** The receipt this key already produced, when it produced one. */
        val receiptId: String?,
    )

    fun priorFor(tenantId: String, key: String?, canonicalArguments: String): Prior {
        if (key.isNullOrBlank()) return Prior.None

        val durable = stores?.idempotency?.find(tenantId, key)
        if (durable != null) {
            if (durable.canonicalArguments != canonicalArguments) {
                return Prior.Conflict("IDEMPOTENCY_KEY_REUSE", 409)
            }
            return when (durable.stage) {
                JournalStage.VERIFIED,
                JournalStage.ACCEPTED,
                JournalStage.VERIFYING,
                -> Prior.Replay(
                    Entry(
                        canonicalArguments = durable.canonicalArguments,
                        executionId = durable.executionId,
                        status = durable.status,
                        stage = durable.stage,
                        erpRecordId = durable.erpRecordId,
                        erpModel = durable.erpModel,
                        messageCode = durable.messageCode,
                        candidateRecordIds = durable.candidateRecordIds,
                        summary = durable.summary,
                        receiptId = stores?.receipts?.forExecution(durable.executionId)?.claims?.receiptId,
                    ),
                )

                JournalStage.REJECTED, JournalStage.CANCELLED, JournalStage.EXPIRED, JournalStage.FAILED ->
                    if (durable.stage == JournalStage.FAILED) {
                        Prior.Conflict("EXECUTION_ALREADY_FAILED", 409)
                    } else {
                        Prior.None
                    }

                JournalStage.AMBIGUOUS, JournalStage.RECONCILIATION_REQUIRED ->
                    Prior.Conflict("EXECUTION_ALREADY_AMBIGUOUS", 409)

                else -> Prior.Conflict("EXECUTION_IN_PROGRESS", 409)
            }
        }

        val existing = ledger.find(tenantId, key) ?: return Prior.None
        if (existing.canonicalArguments != canonicalArguments) return Prior.Conflict("IDEMPOTENCY_KEY_REUSE", 409)
        return Prior.Replay(
            Entry(
                canonicalArguments = existing.canonicalArguments,
                executionId = existing.executionId,
                status = existing.status,
                stage = stageFor(existing.status),
                erpRecordId = existing.erpRecordId,
                erpModel = existing.erpModel,
                messageCode = existing.messageCode,
                candidateRecordIds = existing.candidateRecordIds,
                summary = existing.summary,
                receiptId = stores?.receipts?.forExecution(existing.executionId)?.claims?.receiptId,
            ),
        )
    }

    fun stageFor(status: String): JournalStage = when (status) {
        MizanContract.Status.VERIFIED -> JournalStage.VERIFIED
        MizanContract.Status.ACCEPTED -> JournalStage.ACCEPTED
        MizanContract.Status.AMBIGUOUS -> JournalStage.AMBIGUOUS
        MizanContract.Status.REJECTED -> JournalStage.REJECTED
        else -> JournalStage.FAILED
    }

    fun remember(
        request: ExecutionRequest,
        canonicalArguments: String,
        outcome: ExecutionOutcome,
        journal: ExecutionJournal?,
    ) {
        val key = request.idempotencyKey ?: return
        val candidates = if (outcome is ExecutionOutcome.Ambiguous) outcome.candidateRecordIds else emptyList()
        val recordId = when (outcome) {
            is ExecutionOutcome.Verified -> outcome.erpRecordId
            is ExecutionOutcome.Accepted -> outcome.erpRecordId
            is ExecutionOutcome.Ambiguous -> outcome.possibleRecordId
            else -> null
        }
        val model = when (outcome) {
            is ExecutionOutcome.Verified -> outcome.erpModel
            is ExecutionOutcome.Accepted -> outcome.erpModel
            is ExecutionOutcome.Ambiguous -> outcome.possibleModel
            else -> null
        }
        val summary = when (outcome) {
            is ExecutionOutcome.Verified -> outcome.summary
            is ExecutionOutcome.Accepted -> outcome.summary
            else -> null
        }
        ledger.record(
            LedgerEntry(
                tenantId = request.tenantId,
                key = key,
                canonicalArguments = canonicalArguments,
                executionId = outcome.executionIdOf(),
                traceId = request.traceId,
                status = outcome.statusOf(),
                erpRecordId = recordId,
                erpModel = model,
                messageCode = outcome.messageCodeOf(),
                candidateRecordIds = candidates,
                summary = summary,
            ),
        )
        stores?.idempotency?.record(
            app.mizan.service.store.IdempotencyStore.Entry(
                tenantId = request.tenantId,
                key = key,
                canonicalArguments = canonicalArguments,
                executionId = outcome.executionIdOf(),
                status = outcome.statusOf(),
                stage = stageFor(outcome.statusOf()),
                erpRecordId = recordId,
                erpModel = model,
                messageCode = outcome.messageCodeOf(),
                candidateRecordIds = candidates,
                summary = summary,
                recordedAtMillis = clock(),
            ),
        )
        stores?.idempotency?.maybeCompact()
        journal?.let { stores?.journals?.save(it) }
    }

    fun replay(entry: Entry): ExecutionOutcome = when (entry.status) {
        MizanContract.Status.VERIFIED -> {
            val receipt = stores?.receipts?.forExecution(entry.executionId)
            ExecutionOutcome.Verified(
                executionId = entry.executionId,
                erpRecordId = entry.erpRecordId ?: "",
                erpModel = entry.erpModel ?: "",
                summary = entry.summary ?: "",
                receiptId = receipt?.claims?.receiptId ?: entry.receiptId,
                receiptSignature = receipt?.signature,
                receiptKeyId = receipt?.keyId,
                verifiedFields = receipt?.claims?.verifiedFields ?: emptyList(),
            )
        }
        MizanContract.Status.ACCEPTED -> ExecutionOutcome.Accepted(
            executionId = entry.executionId,
            messageCode = entry.messageCode,
            erpRecordId = entry.erpRecordId,
            erpModel = entry.erpModel,
            summary = entry.summary,
        )
        MizanContract.Status.AMBIGUOUS -> ExecutionOutcome.Ambiguous(
            executionId = entry.executionId,
            candidateRecordIds = entry.candidateRecordIds,
            reasonCode = entry.messageCode,
            possibleRecordId = entry.erpRecordId,
            possibleModel = entry.erpModel,
        )
        MizanContract.Status.REJECTED -> ExecutionOutcome.Rejected(
            executionId = entry.executionId,
            messageCode = entry.messageCode,
            httpStatus = 422,
        )
        else -> ExecutionOutcome.Failed(entry.executionId, entry.messageCode)
    }
}
