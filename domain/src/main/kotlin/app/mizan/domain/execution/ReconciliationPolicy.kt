package app.mizan.domain.execution

/**
 * A person can record a match or close the question.
 * Neither result is a machine read-back, and neither may become [ExecutionPhase.VERIFIED].
 */
enum class HumanMatch {
    LINK,
    CLOSE_WITHOUT_LINK,
}

sealed interface MatchDecision {
    data class Accepted(
        val phase: ExecutionPhase,
        val event: ExecutionEvent,
    ) : MatchDecision

    data class Rejected(val reasonCode: String) : MatchDecision
}

object ReconciliationPolicy {
    fun decide(action: HumanMatch, note: String?, candidateId: String?): MatchDecision {
        return when (action) {
            HumanMatch.LINK -> when {
                note.isNullOrBlank() -> MatchDecision.Rejected("NOTE_REQUIRED")
                candidateId.isNullOrBlank() -> MatchDecision.Rejected("CANDIDATE_REQUIRED")
                else -> MatchDecision.Accepted(
                    ExecutionPhase.LINKED_UNVERIFIED,
                    ExecutionEvent.LinkUnverified,
                )
            }
            HumanMatch.CLOSE_WITHOUT_LINK -> MatchDecision.Accepted(
                ExecutionPhase.CLOSED_UNVERIFIED,
                ExecutionEvent.CloseUnverified,
            )
        }
    }
}
