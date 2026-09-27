package app.mizan.service.store

import app.mizan.domain.org.MemberStatus
import app.mizan.domain.org.Membership
import app.mizan.domain.org.OrgPlan
import app.mizan.domain.org.OrgResult
import app.mizan.domain.org.OrgStatus
import app.mizan.domain.org.Organization
import app.mizan.domain.org.OrganizationBook
import app.mizan.domain.org.Permission
import app.mizan.domain.org.RoleDefinition
import app.mizan.service.json.Json
import app.mizan.service.json.JsonValue
import app.mizan.service.json.asObject
import app.mizan.service.json.field
import app.mizan.service.json.text

/**
 * Organizations that survive a restart.
 *
 * The model holds the rules and this holds the rows: every mutation is
 * appended, and replay rebuilds the same book. An organization that only
 * exists in one process's memory is a directory that empties when the process
 * restarts, and a grant that disappears is a permission change nobody
 * approved -- the exact thing an enterprise deployment is audited on.
 *
 * A membership is rewritten in full on every change. That keeps the log a
 * sequence of states rather than a sequence of deltas, so a replay cannot
 * half-apply an update: the last record for an actor is the truth.
 */
class OrganizationStore(
    private val log: RecordLog,
    clock: () -> Long = { System.currentTimeMillis() },
) {

    val book = OrganizationBook(now = clock)

    private val roleDefinitions = LinkedHashMap<String, RoleDefinition>()

    init {
        // Replay in two passes: organizations first, then their members. A
        // membership is meaningless before the organization it belongs to,
        // and a log can interleave the two kinds of record freely.
        val orgs = LinkedHashMap<String, Organization>()
        val memberLists = LinkedHashMap<String, MutableList<Membership>>()
        val removed = LinkedHashSet<String>()
        for (line in log.records()) {
            val record = Json.parseOrNull(line)?.asObject() ?: continue
            when (record.text("kind")) {
                "organization" -> parseOrganization(record)?.let { orgs[it.id] = it }
                "membership" -> parseMembership(record)?.let { member ->
                    val list = memberLists.getOrPut(member.orgId) { ArrayList() }
                    list.removeAll { it.actorId == member.actorId }
                    list.add(member)
                }
                "membership-removed" -> {
                    val orgId = record.text("orgId") ?: continue
                    val actorId = record.text("actorId") ?: continue
                    memberLists[orgId]?.removeAll { it.actorId == actorId }
                    removed.add("$orgId::$actorId")
                }
                "role" -> parseRole(record)?.let { roleDefinitions[it.name] = it }
            }
        }
        orgs.values.forEach { org -> book.restore(org, memberLists[org.id].orEmpty()) }
        // A member removed after being invited stays removed: replay is
        // chronological, so the removal must win over the earlier invite.
        removed.forEach { key ->
            val orgId = key.substringBefore("::")
            val actorId = key.substringAfter("::")
            if (book.member(orgId, actorId) != null) book.remove(orgId, actorId)
        }
        roleDefinitions.values.filter { !it.builtIn }.forEach { role ->
            book.defineRole(role.name, role.permissions)
        }
    }

    fun create(
        id: String,
        label: String,
        plan: OrgPlan,
        ownerActorId: String,
        ownerEmail: String,
        ownerName: String,
    ): OrgResult<Organization> {
        val result = book.create(
            id = id,
            label = label,
            plan = plan,
            owner = Membership(
                orgId = id,
                actorId = ownerActorId,
                email = ownerEmail,
                displayName = ownerName,
                roleNames = listOf("ORG_ADMIN"),
                status = MemberStatus.ACTIVE,
                addedAtMillis = 0L,
                addedBy = ownerActorId,
            ),
        )
        if (result is OrgResult.Ok) {
            append(
                Json.obj(
                    "kind" to Json.str("organization"),
                    "id" to Json.str(result.value.id),
                    "label" to Json.str(result.value.label),
                    "plan" to Json.str(result.value.plan.name),
                    "status" to Json.str(result.value.status.name),
                    "createdAtMillis" to Json.num(result.value.createdAtMillis),
                    "ssoDomains" to Json.arr(result.value.ssoDomains.map { Json.str(it) }),
                ),
            )
            saveMembership(id, ownerActorId)
        }
        return result
    }

    fun invite(member: Membership, invitedBy: String): OrgResult<Membership> {
        val result = book.invite(member, invitedBy)
        if (result is OrgResult.Ok) saveMembership(member.orgId, member.actorId)
        return result
    }

    fun activate(orgId: String, actorId: String): OrgResult<Membership> {
        val result = book.activate(orgId, actorId)
        if (result is OrgResult.Ok) saveMembership(orgId, actorId)
        return result
    }

    fun touch(orgId: String, actorId: String): OrgResult<Membership> {
        val result = book.touch(orgId, actorId)
        if (result is OrgResult.Ok) saveMembership(orgId, actorId)
        return result
    }

    fun suspend(orgId: String, actorId: String): OrgResult<Membership> {
        val result = book.suspend(orgId, actorId)
        if (result is OrgResult.Ok) saveMembership(orgId, actorId)
        return result
    }

    fun remove(orgId: String, actorId: String): OrgResult<String> {
        val result = book.remove(orgId, actorId)
        if (result is OrgResult.Ok) {
            append(
                Json.obj(
                    "kind" to Json.str("membership-removed"),
                    "orgId" to Json.str(orgId),
                    "actorId" to Json.str(actorId),
                ),
            )
        }
        return result
    }

    fun setRoles(orgId: String, actorId: String, roleNames: List<String>): OrgResult<Membership> {
        val result = book.setRoles(orgId, actorId, roleNames)
        if (result is OrgResult.Ok) saveMembership(orgId, actorId)
        return result
    }

    fun setSsoDomains(orgId: String, domains: List<String>): OrgResult<Organization> {
        val result = book.setSsoDomains(orgId, domains)
        if (result is OrgResult.Ok) {
            val org = result.value
            append(
                Json.obj(
                    "kind" to Json.str("organization"),
                    "id" to Json.str(org.id),
                    "label" to Json.str(org.label),
                    "plan" to Json.str(org.plan.name),
                    "status" to Json.str(org.status.name),
                    "createdAtMillis" to Json.num(org.createdAtMillis),
                    "ssoDomains" to Json.arr(org.ssoDomains.map { Json.str(it) }),
                ),
            )
        }
        return result
    }

    fun suspendOrganization(orgId: String) = saveOrganization(book.suspendOrganization(orgId))

    fun resumeOrganization(orgId: String) = saveOrganization(book.resumeOrganization(orgId))

    fun defineRole(name: String, permissions: Set<Permission>): OrgResult<RoleDefinition> {
        val result = book.defineRole(name, permissions)
        if (result is OrgResult.Ok) appendRole(result.value)
        return result
    }

    fun updateRole(name: String, permissions: Set<Permission>): OrgResult<RoleDefinition> {
        val result = book.updateRole(name, permissions)
        if (result is OrgResult.Ok) appendRole(result.value)
        return result
    }

    fun removeRole(name: String): OrgResult<String> {
        val result = book.removeRole(name)
        if (result is OrgResult.Ok) {
            append(
                Json.obj(
                    "kind" to Json.str("role-removed"),
                    "name" to Json.str(name.uppercase()),
                ),
            )
        }
        return result
    }

    private fun saveOrganization(result: OrgResult<Organization>): OrgResult<Organization> {
        if (result is OrgResult.Ok) {
            val org = result.value
            append(
                Json.obj(
                    "kind" to Json.str("organization"),
                    "id" to Json.str(org.id),
                    "label" to Json.str(org.label),
                    "plan" to Json.str(org.plan.name),
                    "status" to Json.str(org.status.name),
                    "createdAtMillis" to Json.num(org.createdAtMillis),
                    "ssoDomains" to Json.arr(org.ssoDomains.map { Json.str(it) }),
                ),
            )
        }
        return result
    }

    private fun appendRole(role: RoleDefinition) = append(
        Json.obj(
            "kind" to Json.str("role"),
            "name" to Json.str(role.name),
            "permissions" to Json.arr(role.permissions.map { Json.str(it.name) }),
        ),
    )

    private fun saveMembership(orgId: String, actorId: String) {
        val member = book.member(orgId, actorId) ?: return
        append(
            Json.obj(
                "kind" to Json.str("membership"),
                "orgId" to Json.str(member.orgId),
                "actorId" to Json.str(member.actorId),
                "email" to Json.str(member.email),
                "displayName" to Json.str(member.displayName),
                "roleNames" to Json.arr(member.roleNames.map { Json.str(it) }),
                "status" to Json.str(member.status.name),
                "addedAtMillis" to Json.num(member.addedAtMillis),
                "addedBy" to Json.str(member.addedBy),
                "lastSeenAtMillis" to Json.num(member.lastSeenAtMillis),
            ),
        )
    }

    private fun append(record: JsonValue.Obj) = log.append(Json.write(record))

    private fun parseOrganization(record: JsonValue.Obj): Organization? {
        val id = record.text("id") ?: return null
        return Organization(
            id = id,
            label = record.text("label") ?: id,
            plan = record.text("plan")?.let { runCatching { OrgPlan.valueOf(it) }.getOrNull() }
                ?: OrgPlan.STANDARD,
            status = record.text("status")?.let { runCatching { OrgStatus.valueOf(it) }.getOrNull() }
                ?: OrgStatus.ACTIVE,
            createdAtMillis = record.longOrNull("createdAtMillis") ?: 0L,
            ssoDomains = ((record.field("ssoDomains") as? JsonValue.Arr)?.items ?: emptyList())
                .mapNotNull { (it as? JsonValue.Str)?.value },
        )
    }

    private fun parseMembership(record: JsonValue.Obj): Membership? {
        val orgId = record.text("orgId") ?: return null
        val actorId = record.text("actorId") ?: return null
        return Membership(
            orgId = orgId,
            actorId = actorId,
            email = record.text("email") ?: return null,
            displayName = record.text("displayName") ?: actorId,
            roleNames = ((record.field("roleNames") as? JsonValue.Arr)?.items ?: emptyList())
                .mapNotNull { (it as? JsonValue.Str)?.value?.uppercase() },
            status = record.text("status")?.let { runCatching { MemberStatus.valueOf(it) }.getOrNull() }
                ?: MemberStatus.INVITED,
            addedAtMillis = record.longOrNull("addedAtMillis") ?: 0L,
            addedBy = record.text("addedBy") ?: "",
            lastSeenAtMillis = record.longOrNull("lastSeenAtMillis"),
        )
    }

    private fun parseRole(record: JsonValue.Obj): RoleDefinition? {
        val name = record.text("name") ?: return null
        val permissions = ((record.field("permissions") as? JsonValue.Arr)?.items ?: emptyList())
            .mapNotNull { (it as? JsonValue.Str)?.value }
            .mapNotNull { raw -> runCatching { Permission.valueOf(raw) }.getOrNull() }
            .toSet()
        return RoleDefinition(name = name.uppercase(), permissions = permissions, builtIn = false)
    }
}

internal fun JsonValue.Obj.longOrNull(name: String): Long? = field(name)?.let { value ->
    when (value) {
        is JsonValue.Num -> value.raw.toLongOrNull()
        is JsonValue.Str -> value.value.toLongOrNull()
        else -> null
    }
}
