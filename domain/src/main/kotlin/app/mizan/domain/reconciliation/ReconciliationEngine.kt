package app.mizan.domain.reconciliation

import app.mizan.domain.model.Money
import app.mizan.domain.model.TenantId
import app.mizan.domain.execution.HumanMatch
import app.mizan.domain.execution.MatchDecision
import app.mizan.domain.execution.ReconciliationPolicy
import app.mizan.domain.model.ReconciliationCase
import app.mizan.domain.model.ReconciliationStatus

/**
 * Reconciliation, not retry.
 *
 * When a write is sent and the answer is lost, the system does not know
 * whether the ERP holds the record. Retrying is the one action that can
 * create a duplicate; doing nothing leaves a person without an answer. So the
 * engine looks for what might exist, compares fingerprints, and hands a ranked
 * list to a person who decides.
 *
 * The engine never resolves a case on its own. Its best output is a
 * recommendation, and its most common output is a question.
 */
data class ExpectedRecord(
    val model: String,
    val customerName: String?,
    val amount: Money?,
    val sentAtMillis: Long?,
    val stateHint: String? = null,
)

data class ErpCandidateRecord(
    val recordId: String,
    val model: String,
    val customerName: String?,
    val amountMinor: Long?,
    val currency: String?,
    val createdAtMillis: Long?,
    val state: String? = null,
)

data class CandidateMatch(
    val record: ErpCandidateRecord,
    val score: Int,
    val matched: List<String>,
    val differed: List<String>,
) {
    /** Every compared field agreed. Still a claim to be confirmed by a person. */
    val exact: Boolean get() = differed.isEmpty() && score >= 70
}

sealed interface ReconciliationAssessment {
    /** Nothing resembling the request exists in the window. */
    data class NothingFound(val reasonCode: String) : ReconciliationAssessment

    /**
     * One candidate agrees on every comparable field. The service reports it
     * as a recommendation and still requires a human to link it.
     */
    data class SingleExactCandidate(val match: CandidateMatch) : ReconciliationAssessment

    /** Several candidates are plausible. A person chooses. */
    data class SeveralPlausible(val matches: List<CandidateMatch>) : ReconciliationAssessment

    /** Candidates exist but none agrees. A person investigates. */
    data class Inconclusive(val matches: List<CandidateMatch>) : ReconciliationAssessment
}

class ReconciliationEngine(
    private val windowMillis: Long = 15 * 60 * 1000L,
) {

    fun rank(expected: ExpectedRecord, candidates: List<ErpCandidateRecord>): List<CandidateMatch> =
        candidates
            .filter { it.model == expected.model }
            .map { score(expected, it) }
            .filter { it.score > 0 }
            .sortedWith(compareByDescending<CandidateMatch> { it.score }.thenBy { it.record.createdAtMillis ?: 0L })

    fun assess(expected: ExpectedRecord, candidates: List<ErpCandidateRecord>): ReconciliationAssessment {
        val ranked = rank(expected, candidates)
        if (ranked.isEmpty()) return ReconciliationAssessment.NothingFound("NO_CANDIDATE_IN_WINDOW")
        val exact = ranked.filter { it.exact }
        if (exact.size == 1 && ranked.size == 1) {
            return ReconciliationAssessment.SingleExactCandidate(exact.first())
        }
        return if (ranked.size == 1) {
            ReconciliationAssessment.SeveralPlausible(ranked)
        } else {
            ReconciliationAssessment.SeveralPlausible(ranked)
        }
    }

    private fun score(expected: ExpectedRecord, candidate: ErpCandidateRecord): CandidateMatch {
        val matched = mutableListOf<String>()
        val differed = mutableListOf<String>()
        var score = 0

        if (expected.customerName != null && candidate.customerName != null) {
            if (normalize(expected.customerName) == normalize(candidate.customerName)) {
                matched += "customerName"
                score += 30
            } else {
                differed += "customerName"
                score -= 5
            }
        }

        val expectedAmount = expected.amount
        if (expectedAmount != null && candidate.amountMinor != null && candidate.currency != null) {
            if (candidate.currency.equals(expectedAmount.currency, ignoreCase = true)) {
                if (candidate.amountMinor == expectedAmount.minorUnits) {
                    matched += "amountMinor"
                    score += 50
                } else {
                    differed += "amountMinor"
                    score -= 10
                }
            } else {
                // Different currencies are not comparable amounts. This is a
                // different record, not a mismatch to weigh.
                differed += "currency"
                score -= 60
            }
        }

        val sentAt = expected.sentAtMillis
        val createdAt = candidate.createdAtMillis
        if (sentAt != null && createdAt != null) {
            val delta = createdAt - sentAt
            when {
                delta < -60_000 -> {
                    differed += "createdAt"
                    score -= 40
                }
                delta in 0..windowMillis -> {
                    matched += "createdAt"
                    score += 20
                }
                else -> {
                    differed += "createdAt"
                    score -= 20
                }
            }
        }
        return CandidateMatch(candidate, score, matched, differed)
    }

    private fun normalize(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    /** The only two moves available to a person, and what each one means. */
    fun humanResolution(
        action: HumanMatch,
        note: String?,
        candidateId: String?,
        currentStatus: ReconciliationStatus,
    ): MatchDecision {
        if (currentStatus != ReconciliationStatus.OPEN) {
            return MatchDecision.Rejected("RECONCILIATION_ALREADY_CLOSED")
        }
        return ReconciliationPolicy.decide(action, note, candidateId)
    }
}

/** A reconciliation case carries who opened it and what was expected. */
data class ReconciliationContext(
    val tenantId: TenantId,
    val case: ReconciliationCase,
    val expected: ExpectedRecord,
    val assessedAtMillis: Long,
)
