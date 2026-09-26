package app.mizan.domain.policy

import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.CanonicalJson
import app.mizan.domain.model.CanonicalValue
import app.mizan.domain.model.Digests
import app.mizan.domain.model.Money
import app.mizan.domain.model.ToolName
import java.time.Instant

/**
 * A policy is versioned configuration, not a constant in a source file.
 *
 * Every decision names the version that produced it, and every proposal
 * stores a snapshot of that version. If the policy moves while a proposal is
 * waiting for its approver, the decision is re-evaluated instead of silently
 * applying yesterday's thresholds to today's amounts.
 */
data class PolicyVersion(
    val version: Int,
    val effectiveFrom: Instant,
    val label: String,
) {
    val id: String get() = "v$version"

    companion object {
        val UNVERSIONED = PolicyVersion(0, Instant.EPOCH, "unversioned")
    }
}

/**
 * One threshold ladder for one tool in one currency. `actorLimitMinor` is the
 * ceiling a single actor may commit without escalation; the plan calls this
 * the scope limit.
 */
data class ToolPolicyRule(
    val toolWire: String,
    val currency: String?,
    val l1MaxMinor: Long,
    val l2MaxMinor: Long,
    val l3MaxMinor: Long,
    val actorLimitMinor: Long? = null,
    val requiresSeparationOfDuties: Boolean = true,
) {
    init {
        require(l1MaxMinor < l2MaxMinor && l2MaxMinor < l3MaxMinor) {
            "a rule's thresholds must be strictly increasing"
        }
    }

    fun appliesTo(tool: ToolName, currencyCode: String?): Boolean {
        if (tool.wire != toolWire) return false
        if (currency == null) return true
        return currency.equals(currencyCode, ignoreCase = true)
    }
}

/**
 * The immutable identity of the policy a decision was made under. Stored on
 * the proposal, on the approval, and on the receipt, so a reader can tell
 * which rules governed a historical action without trusting this build.
 */
data class PolicySnapshot(
    val versionId: String,
    val effectiveFromMillis: Long,
    val rulesHash: String,
) {
    companion object {
        fun of(version: PolicyVersion, rules: List<ToolPolicyRule>): PolicySnapshot = PolicySnapshot(
            versionId = version.id,
            effectiveFromMillis = version.effectiveFrom.toEpochMilli(),
            rulesHash = hashOf(version, rules),
        )

        fun hashOf(version: PolicyVersion, rules: List<ToolPolicyRule>): String {
            val encoded = CanonicalValue.Obj(
                listOf(
                    "version" to CanonicalValue.Str(version.id),
                    "effectiveFrom" to CanonicalValue.Num(version.effectiveFrom.toEpochMilli().toString()),
                    "rules" to CanonicalValue.Arr(
                        rules.sortedBy { it.toolWire + (it.currency ?: "") }.map { rule ->
                            CanonicalValue.Obj(
                                listOf(
                                    "actorLimitMinor" to CanonicalValue.Num((rule.actorLimitMinor ?: -1).toString()),
                                    "currency" to CanonicalValue.Str(rule.currency ?: "*"),
                                    "l1MaxMinor" to CanonicalValue.Num(rule.l1MaxMinor.toString()),
                                    "l2MaxMinor" to CanonicalValue.Num(rule.l2MaxMinor.toString()),
                                    "l3MaxMinor" to CanonicalValue.Num(rule.l3MaxMinor.toString()),
                                    "sod" to CanonicalValue.Bool(rule.requiresSeparationOfDuties),
                                    "tool" to CanonicalValue.Str(rule.toolWire),
                                ),
                            )
                        },
                    ),
                ),
            )
            return Digests.sha256(CanonicalJson.write(encoded))
        }
    }
}

/**
 * The catalogue this build ships, plus the rules a workspace can override.
 * The demo workspace is labelled as such; a customer's policy arrives from the
 * server and replaces it wholesale.
 */
data class VersionedPolicy(
    val version: PolicyVersion,
    val ladders: Map<String, ThresholdLadder>,
    val rules: List<ToolPolicyRule>,
) {
    init {
        val duplicates = rules.groupBy { it.toolWire + (it.currency ?: "*") }.filterValues { it.size > 1 }
        require(duplicates.isEmpty()) { "duplicate tool rules: ${duplicates.keys}" }
    }

    val snapshot: PolicySnapshot get() = PolicySnapshot.of(version, rules)

    fun catalog(): PolicyCatalog = PolicyCatalog(ladders)

    fun ruleFor(tool: ToolName, currency: String?): ToolPolicyRule? =
        rules.firstOrNull { it.appliesTo(tool, currency) }

    companion object {
        /**
         * Demo thresholds. The plan's example is a workspace where an order up
         * to 10,000 is one approval, up to 50,000 needs a manager, and above
         * that needs two people. Those numbers belong to the customer, not to
         * this repository -- a deployment replaces this object with policy
         * fetched from the server.
         */
        val demoV12: VersionedPolicy = VersionedPolicy(
            version = PolicyVersion(
                version = 12,
                effectiveFrom = Instant.parse("2026-09-01T00:00:00Z"),
                label = "demo-default",
            ),
            ladders = PolicyCatalog.demo.ladders,
            rules = listOf(
                ToolPolicyRule(
                    toolWire = ToolName.CREATE_DRAFT_ORDER.wire,
                    currency = "EGP",
                    l1MaxMinor = 1_000_000,
                    l2MaxMinor = 5_000_000,
                    l3MaxMinor = 10_000_000,
                    actorLimitMinor = 5_000_000,
                ),
                ToolPolicyRule(
                    toolWire = ToolName.CREATE_DRAFT_ORDER.wire,
                    currency = "USD",
                    l1MaxMinor = 100_000,
                    l2MaxMinor = 1_000_000,
                    l3MaxMinor = 2_500_000,
                    actorLimitMinor = 1_000_000,
                ),
                ToolPolicyRule(
                    toolWire = ToolName.REGISTER_PAYMENT.wire,
                    currency = null,
                    l1MaxMinor = 100_000,
                    l2MaxMinor = 500_000,
                    l3MaxMinor = 2_000_000,
                    actorLimitMinor = 500_000,
                ),
            ),
        )

        val unversioned: VersionedPolicy = VersionedPolicy(
            version = PolicyVersion.UNVERSIONED,
            ladders = PolicyCatalog.demo.ladders,
            rules = emptyList(),
        )
    }
}

/**
 * A proposal is judged against a concrete amount and currency. This is the
 * input the ladder needs, kept separate from the request so the same
 * evaluation can be replayed from a stored proposal.
 */
data class PolicyBasis(
    val amount: Money?,
    val currency: String?,
    val tool: ToolName,
)
