package app.mizan.domain.org

import app.mizan.domain.model.Role

/**
 * Organizations, members and grants.
 *
 * The reference deployment ships four accounts hardcoded in one tenant, which
 * is exactly what an enterprise product cannot do: a company onboards its own
 * people, gives them roles, suspends them when they leave, and does all of it
 * without a code change. This is the model underneath that.
 *
 * Three rules are enforced here rather than by the caller, because every
 * caller eventually forgets one:
 *
 * 1. An organization always has at least one active member who can administer
 *    it. The last administrator cannot be suspended, removed or stripped.
 * 2. A member's permissions come from their roles, and a role that does not
 *    exist grants nothing. There is no "unknown role means everything".
 * 3. A member of one organization is invisible to another. Every read takes
 *    the organization, so a cross-tenant read cannot be expressed.
 */
enum class OrgPlan { TRIAL, STANDARD, ENTERPRISE }

enum class OrgStatus { ACTIVE, SUSPENDED }

enum class MemberStatus {
    /** Invited, has not accepted. Cannot sign in yet. */
    INVITED,
    ACTIVE,
    /** Kept for the audit trail, refused at sign-in. */
    SUSPENDED,
}

data class Organization(
    val id: String,
    val label: String,
    val plan: OrgPlan,
    val status: OrgStatus,
    val createdAtMillis: Long,
    /** Domains that may self-enrol into this organization on SSO. */
    val ssoDomains: List<String> = emptyList(),
) {
    val canSignIn: Boolean get() = status == OrgStatus.ACTIVE
}

data class Membership(
    val orgId: String,
    val actorId: String,
    val email: String,
    val displayName: String,
    val roleNames: List<String>,
    val status: MemberStatus,
    val addedAtMillis: Long,
    val addedBy: String,
    val lastSeenAtMillis: Long? = null,
) {
    /** The domain part of the email, which is what an SSO rule matches. */
    val emailDomain: String get() = email.substringAfter('@', missingDelimiterValue = "").lowercase()
}

/** What an organization action can come back as. Refusals carry a code, not prose. */
sealed interface OrgResult<out T> {
    data class Ok<T>(val value: T) : OrgResult<T>
    data class Refused(val code: String) : OrgResult<Nothing>
}

/**
 * The organizations this deployment knows about.
 *
 * In-memory and deterministic: the durable store in the service replays its
 * log into this book, so the rules live in one place whether the rows came
 * from a file, a database, or a test.
 */
class OrganizationBook(
    private val roles: RoleBook = RoleBook(),
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val orgs = LinkedHashMap<String, Organization>()
    private val members = LinkedHashMap<String, MutableList<Membership>>()

    /** The roles of an organization. Custom roles are per-organization. */
    fun rolesOf(orgId: String): List<RoleDefinition> = roles.roles()

    fun create(
        id: String,
        label: String,
        plan: OrgPlan = OrgPlan.STANDARD,
        owner: Membership? = null,
    ): OrgResult<Organization> {
        if (orgs.containsKey(id)) return OrgResult.Refused("ORG_EXISTS")
        if (id.isBlank() || label.isBlank()) return OrgResult.Refused("ORG_INVALID")
        val org = Organization(
            id = id,
            label = label,
            plan = plan,
            status = OrgStatus.ACTIVE,
            createdAtMillis = now(),
        )
        orgs[id] = org
        members[id] = ArrayList()
        if (owner != null) {
            addMember(owner.copy(orgId = id, roleNames = listOf("ORG_ADMIN")))
        }
        return OrgResult.Ok(org)
    }

    fun get(orgId: String): Organization? = orgs[orgId]

    fun all(): List<Organization> = orgs.values.toList()

    fun membersOf(orgId: String): List<Membership> = members[orgId].orEmpty().toList()

    fun member(orgId: String, actorId: String): Membership? =
        members[orgId]?.firstOrNull { it.actorId == actorId }

    fun memberByEmail(email: String): Membership? =
        members.values.flatten().firstOrNull { it.email.equals(email.trim(), ignoreCase = true) }

    /**
     * Adds a member, or refuses.
     *
     * Invited members hold their roles but cannot sign in; that is what makes
     * an invitation an invitation and not a password reset waiting to happen.
     */
    fun invite(member: Membership, invitedBy: String): OrgResult<Membership> {
        val org = orgs[member.orgId] ?: return OrgResult.Refused("ORG_UNKNOWN")
        if (org.status != OrgStatus.ACTIVE) return OrgResult.Refused("ORG_SUSPENDED")
        if (member.roleNames.isEmpty()) return OrgResult.Refused("ROLE_REQUIRED")
        for (name in member.roleNames) {
            if (roles.get(name) == null) return OrgResult.Refused("ROLE_UNKNOWN")
        }
        if (memberByEmail(member.email) != null) return OrgResult.Refused("MEMBER_EXISTS")
        // A new member cannot hand themselves a permission the inviter does
        // not have: privilege escalation by invitation is still escalation.
        val inviter = this.memberByEmail(invitedBy)?.let { it }
            ?: memberByEmailOrActor(invitedBy)
        if (inviter != null) {
            val inviterPermissions = permissionsOf(inviter.orgId, inviter.actorId)
            val granted = roles.permissionsFor(member.roleNames)
            if (!inviterPermissions.containsAll(granted)) return OrgResult.Refused("ROLE_ESCALATION")
        }
        addMember(member.copy(addedBy = invitedBy, status = MemberStatus.INVITED))
        return OrgResult.Ok(member(member.orgId, member.actorId)!!)
    }

    private fun memberByEmailOrActor(id: String): Membership? =
        members.values.flatten().firstOrNull { it.actorId == id }

    private fun addMember(member: Membership) {
        val list = members.getOrPut(member.orgId) { ArrayList() }
        list.removeAll { it.actorId == member.actorId }
        list.add(member)
    }

    fun activate(orgId: String, actorId: String): OrgResult<Membership> =
        update(orgId, actorId) { it.copy(status = MemberStatus.ACTIVE, lastSeenAtMillis = now()) }

    /** Marks a member as seen. Never changes their status. */
    fun touch(orgId: String, actorId: String): OrgResult<Membership> =
        update(orgId, actorId) { it.copy(lastSeenAtMillis = now()) }

    fun suspend(orgId: String, actorId: String): OrgResult<Membership> {
        if (isLastAdministrator(orgId, actorId)) return OrgResult.Refused("LAST_ADMIN")
        return update(orgId, actorId) { it.copy(status = MemberStatus.SUSPENDED) }
    }

    fun remove(orgId: String, actorId: String): OrgResult<String> {
        if (isLastAdministrator(orgId, actorId)) return OrgResult.Refused("LAST_ADMIN")
        val list = members[orgId] ?: return OrgResult.Refused("ORG_UNKNOWN")
        val removed = list.removeAll { it.actorId == actorId }
        return if (removed) OrgResult.Ok(actorId) else OrgResult.Refused("MEMBER_UNKNOWN")
    }

    /** Replaces a member's roles in one act, so no window exists with none. */
    fun setRoles(orgId: String, actorId: String, roleNames: List<String>): OrgResult<Membership> {
        if (roleNames.isEmpty()) return OrgResult.Refused("ROLE_REQUIRED")
        for (name in roleNames) if (roles.get(name) == null) return OrgResult.Refused("ROLE_UNKNOWN")
        val remaining = member(orgId, actorId)?.roleNames.orEmpty().filter { it.uppercase() == "ORG_ADMIN" }
        if (remaining.isNotEmpty() && roleNames.none { it.uppercase() == "ORG_ADMIN" } &&
            administrators(orgId).size <= 1
        ) {
            return OrgResult.Refused("LAST_ADMIN")
        }
        return update(orgId, actorId) { it.copy(roleNames = roleNames.map { name -> name.uppercase() }) }
    }

    fun defineRole(name: String, permissions: Set<Permission>): OrgResult<RoleDefinition> {
        val created = roles.define(name, permissions) ?: return OrgResult.Refused("ROLE_IMMUTABLE")
        return OrgResult.Ok(created)
    }

    fun updateRole(name: String, permissions: Set<Permission>): OrgResult<RoleDefinition> {
        val updated = roles.replace(name, permissions) ?: return OrgResult.Refused("ROLE_IMMUTABLE")
        return OrgResult.Ok(updated)
    }

    fun removeRole(name: String): OrgResult<String> {
        if (!roles.remove(name)) return OrgResult.Refused("ROLE_IMMUTABLE")
        return OrgResult.Ok(name)
    }

    /** Every permission a member holds, through every role they hold. */
    fun permissionsOf(orgId: String, actorId: String): Set<Permission> {
        val member = member(orgId, actorId) ?: return emptySet()
        if (member.status != MemberStatus.ACTIVE) return emptySet()
        if (orgs[orgId]?.status != OrgStatus.ACTIVE) return emptySet()
        return roles.permissionsFor(member.roleNames)
    }

    fun allows(orgId: String, actorId: String, permission: Permission): Boolean =
        permission in permissionsOf(orgId, actorId)

    /**
     * Whether an email may self-enrol into an organization through SSO.
     *
     * The domain is the rule, never a claim in the token: a token that says
     * "I am in this organization" is a request, not a fact.
     */
    fun ssoOrganizationFor(email: String): Organization? {
        val domain = email.substringAfter('@', missingDelimiterValue = "").lowercase()
        if (domain.isEmpty()) return null
        return orgs.values.firstOrNull { org ->
            org.status == OrgStatus.ACTIVE && org.ssoDomains.any { it.lowercase() == domain }
        }
    }

    fun administrators(orgId: String): List<Membership> = membersOf(orgId).filter { member ->
        member.status == MemberStatus.ACTIVE && member.roleNames.any { it.uppercase() == "ORG_ADMIN" }
    }

    fun isLastAdministrator(orgId: String, actorId: String): Boolean {
        val admin = administrators(orgId)
        return admin.size == 1 && admin.first().actorId == actorId
    }

    /** Replaces an organization record, used by the durable store on replay. */
    fun restore(org: Organization, memberList: List<Membership>) {
        orgs[org.id] = org
        members[org.id] = ArrayList(memberList)
    }

    fun suspendOrganization(orgId: String): OrgResult<Organization> {
        val org = orgs[orgId] ?: return OrgResult.Refused("ORG_UNKNOWN")
        val suspended = org.copy(status = OrgStatus.SUSPENDED)
        orgs[orgId] = suspended
        return OrgResult.Ok(suspended)
    }

    fun resumeOrganization(orgId: String): OrgResult<Organization> {
        val org = orgs[orgId] ?: return OrgResult.Refused("ORG_UNKNOWN")
        val resumed = org.copy(status = OrgStatus.ACTIVE)
        orgs[orgId] = resumed
        return OrgResult.Ok(resumed)
    }

    fun setSsoDomains(orgId: String, domains: List<String>): OrgResult<Organization> {
        val org = orgs[orgId] ?: return OrgResult.Refused("ORG_UNKNOWN")
        val cleaned = domains.map { it.trim().lowercase() }.filter { it.contains('.') && !it.contains('@') }
        val updated = org.copy(ssoDomains = cleaned.distinct())
        orgs[orgId] = updated
        return OrgResult.Ok(updated)
    }

    /** The built-in role a service account maps to, for a migration. */
    fun roleFor(role: Role): String = role.name

    private fun update(
        orgId: String,
        actorId: String,
        change: (Membership) -> Membership,
    ): OrgResult<Membership> {
        val list = members[orgId] ?: return OrgResult.Refused("ORG_UNKNOWN")
        val index = list.indexOfFirst { it.actorId == actorId }
        if (index < 0) return OrgResult.Refused("MEMBER_UNKNOWN")
        val updated = change(list[index])
        list[index] = updated
        return OrgResult.Ok(updated)
    }
}
