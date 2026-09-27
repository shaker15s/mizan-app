package app.mizan.domain.agent

import app.mizan.domain.model.TenantId

/**
 * Finding the entity a person meant, and refusing to guess when more than one
 * thing fits.
 *
 * "اعمل طلب للنور" is not an instruction until "النور" names one customer. The
 * resolver returns either one confident record or the list of candidates that
 * a person must choose between. It never picks the first row.
 */
data class CustomerCandidate(
    val recordId: String,
    val name: String,
    val tenantId: TenantId,
    val status: String = "active",
    val balanceMinor: Long? = null,
    val currency: String? = null,
)

data class ScoredCandidate(
    val candidate: CustomerCandidate,
    val score: Int,
    val reasonCode: String,
)

sealed interface EntityResolution {
    /** One customer, by a rule that cannot be fooled by a substring. */
    data class Resolved(val candidate: CustomerCandidate, val reasonCode: String) : EntityResolution

    /** More than one customer answers to this text. A person chooses. */
    data class Ambiguous(
        val query: String,
        val candidates: List<ScoredCandidate>,
        val reasonCode: String = "AMBIGUOUS_CUSTOMER",
    ) : EntityResolution

    /** Nothing matched. The caller must not invent a customer. */
    data class NotFound(val query: String, val reasonCode: String = "CUSTOMER_NOT_FOUND") : EntityResolution

    /** The text itself is not usable as a name. */
    data class Rejected(val reasonCode: String) : EntityResolution
}

class CustomerResolver(private val maxCandidates: Int = 5) {

    fun resolve(query: String, candidates: List<CustomerCandidate>): EntityResolution {
        val cleaned = query.trim()
        if (cleaned.length < 2) return EntityResolution.Rejected("CUSTOMER_QUERY_TOO_SHORT")
        val key = ArabicText.entityKey(cleaned)
        if (key.isBlank()) return EntityResolution.Rejected("CUSTOMER_QUERY_EMPTY")

        val scoped = candidates.distinctBy { it.recordId }

        val exact = scoped.filter { ArabicText.entityKey(it.name) == key }
        if (exact.size == 1) {
            return EntityResolution.Resolved(exact.first(), "EXACT_ENTITY_MATCH")
        }
        if (exact.size > 1) {
            // Two customers genuinely share a name. That is a data question,
            // not something to resolve by ordering.
            return EntityResolution.Ambiguous(
                cleaned,
                exact.map { ScoredCandidate(it, 100, "DUPLICATE_NAME") },
                "DUPLICATE_CUSTOMER_NAME",
            )
        }

        val partial = scoped
            .mapNotNull { candidate ->
                val candidateKey = ArabicText.entityKey(candidate.name)
                when {
                    ArabicText.containsWord(candidateKey, key) ->
                        ScoredCandidate(candidate, 70, "NAME_CONTAINS_QUERY")

                    key.split(' ').all { word -> candidateKey.contains(word) } ->
                        ScoredCandidate(candidate, 50, "ALL_WORDS_PRESENT")

                    else -> null
                }
            }
            .sortedByDescending { it.score }
            .take(maxCandidates)

        return when {
            partial.isEmpty() -> EntityResolution.NotFound(cleaned)
            partial.size == 1 && partial.first().score >= 70 ->
                // One candidate, and the query appears in its name as a word.
                EntityResolution.Resolved(partial.first().candidate, partial.first().reasonCode)
            else -> EntityResolution.Ambiguous(cleaned, partial)
        }
    }
}
