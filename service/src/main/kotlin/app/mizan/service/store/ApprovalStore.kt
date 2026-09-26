package app.mizan.service.store

import app.mizan.domain.approval.ApprovalDecision
import app.mizan.domain.approval.ApprovalRequest
import app.mizan.domain.approval.ApprovalState
import app.mizan.domain.model.Actor
import app.mizan.domain.model.ActorId
import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.Role
import app.mizan.domain.model.TenantId
import app.mizan.domain.policy.ApprovalRecord
import app.mizan.service.json.Json
import app.mizan.service.json.JsonValue
import app.mizan.service.json.asObject
import app.mizan.service.json.field
import app.mizan.service.json.text

/**
 * Approval requests that survive a restart.
 *
 * An approval that only exists in the memory of one process is not an
 * authorisation: a restart would silently erase the record of who approved
 * what, and the fingerprint that binds an approval to a proposal has to
 * outlive the process that checked it.
 */
class ApprovalStore(private val log: RecordLog) {

    private val byId = LinkedHashMap<String, ApprovalRequest>()
    private val byProposal = LinkedHashMap<String, String>()

    init {
        for (line in log.records()) {
            val parsed = Json.parseOrNull(line)?.asObject() ?: continue
            val request = parse(parsed) ?: continue
            index(request)
        }
    }

    @Synchronized
    fun save(request: ApprovalRequest): ApprovalRequest {
        log.append(write(request))
        index(request)
        return request
    }

    @Synchronized
    fun get(id: String): ApprovalRequest? = byId[id]

    @Synchronized
    fun forProposal(proposalId: String): ApprovalRequest? = byProposal[proposalId]?.let { byId[it] }

    @Synchronized
    fun pending(tenantId: String): List<ApprovalRequest> = byId.values.filter {
        it.tenantId.value == tenantId && it.state == ApprovalState.PENDING
    }

    @Synchronized
    fun forTenant(tenantId: String): List<ApprovalRequest> =
        byId.values.filter { it.tenantId.value == tenantId }.sortedByDescending { it.createdAtMillis }

    @Synchronized
    fun size(): Int = byId.size

    private fun index(request: ApprovalRequest) {
        byId[request.id] = request
        byProposal[request.proposalId] = request.id
    }

    private fun write(request: ApprovalRequest): String = Json.write(
        Json.obj(
            "id" to Json.str(request.id),
            "proposalId" to Json.str(request.proposalId),
            "tenantId" to Json.str(request.tenantId.value),
            "initiatorId" to Json.str(request.initiatorId.value),
            "proposalRevision" to Json.num(request.proposalRevision),
            "proposalFingerprint" to Json.str(request.proposalFingerprint),
            "requiredLevel" to Json.str(request.requiredLevel.name),
            "policyVersionId" to Json.str(request.policyVersionId),
            "policyHash" to Json.str(request.policyHash),
            "state" to Json.str(request.state.name),
            "createdAtMillis" to Json.num(request.createdAtMillis),
            "expiresAtMillis" to Json.num(request.expiresAtMillis),
            "approvals" to Json.arr(
                request.approvals.map { record ->
                    Json.obj(
                        "actorId" to Json.str(record.approver.id.value),
                        "displayName" to Json.str(record.approver.displayName),
                        "role" to Json.str(record.approver.role.name),
                        "tenantId" to Json.str(record.approver.tenantId.value),
                        "atEpochMillis" to Json.num(record.atEpochMillis),
                    )
                },
            ),
            "decisions" to Json.arr(
                request.decisions.map { decision ->
                    Json.obj(
                        "approverId" to Json.str(decision.approverId.value),
                        "approverLabel" to Json.str(decision.approverLabel),
                        "state" to Json.str(decision.state.name),
                        "atEpochMillis" to Json.num(decision.atEpochMillis),
                        "reasonCode" to Json.str(decision.reasonCode),
                    )
                },
            ),
        ),
    )

    private fun parse(record: JsonValue.Obj): ApprovalRequest? {
        val id = record.text("id") ?: return null
        val tenantId = record.text("tenantId") ?: return null
        val initiatorId = record.text("initiatorId") ?: return null
        val approvals = ((record.field("approvals") as? JsonValue.Arr)?.items ?: emptyList())
            .mapNotNull { item ->
                val row = item.asObject() ?: return@mapNotNull null
                val actorId = row.text("actorId") ?: return@mapNotNull null
                ApprovalRecord(
                    approver = Actor(
                        id = ActorId(actorId),
                        displayName = row.text("displayName") ?: "",
                        role = row.text("role")?.let { runCatching { Role.valueOf(it) }.getOrNull() }
                            ?: Role.OPERATOR,
                        tenantId = TenantId(row.text("tenantId") ?: tenantId),
                    ),
                    atEpochMillis = row.long("atEpochMillis") ?: 0L,
                )
            }
        val decisions = ((record.field("decisions") as? JsonValue.Arr)?.items ?: emptyList())
            .mapNotNull { item ->
                val row = item.asObject() ?: return@mapNotNull null
                ApprovalDecision(
                    approverId = ActorId(row.text("approverId") ?: return@mapNotNull null),
                    approverLabel = row.text("approverLabel") ?: "",
                    state = row.text("state")?.let { runCatching { ApprovalState.valueOf(it) }.getOrNull() }
                        ?: ApprovalState.PENDING,
                    atEpochMillis = row.long("atEpochMillis") ?: 0L,
                    reasonCode = row.text("reasonCode"),
                )
            }
        return ApprovalRequest(
            id = id,
            proposalId = record.text("proposalId") ?: "",
            tenantId = TenantId(tenantId),
            initiatorId = ActorId(initiatorId),
            proposalRevision = record.long("proposalRevision")?.toInt() ?: 1,
            proposalFingerprint = record.text("proposalFingerprint") ?: "",
            requiredLevel = record.text("requiredLevel")
                ?.let { runCatching { ApprovalLevel.valueOf(it) }.getOrNull() } ?: ApprovalLevel.L0_NONE,
            policyVersionId = record.text("policyVersionId") ?: "",
            policyHash = record.text("policyHash") ?: "",
            state = record.text("state")?.let { runCatching { ApprovalState.valueOf(it) }.getOrNull() }
                ?: ApprovalState.PENDING,
            createdAtMillis = record.long("createdAtMillis") ?: 0L,
            expiresAtMillis = record.long("expiresAtMillis") ?: 0L,
            approvals = approvals,
            decisions = decisions,
        )
    }
}

private fun JsonValue.Obj.long(name: String): Long? = field(name)?.let { value ->
    when (value) {
        is JsonValue.Num -> value.raw.toLongOrNull()
        is JsonValue.Str -> value.value.toLongOrNull()
        else -> null
    }
}
