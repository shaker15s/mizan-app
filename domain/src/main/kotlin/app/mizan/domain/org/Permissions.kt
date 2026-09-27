package app.mizan.domain.org

import app.mizan.domain.model.Role

/**
 * What a person is allowed to do, named after the thing they do.
 *
 * The service has always asked a coarser question -- "is this person an
 * auditor?" -- and answered it in the place the question was asked. That is
 * fine for four roles and fatal for an enterprise: a company does not want
 * "manager", it wants "this person may approve up to their limit, may read
 * their own branch's operations, and may not touch policy".
 *
 * Permissions are the vocabulary; roles are named bundles of them; grants are
 * who holds which bundle in which organization. Every check in the admin
 * surface is a permission check, so adding a role is a data change and adding
 * a permission is a code change that the compiler can see.
 */
enum class Permission {
    /** Read the organization's own operations, journal and reconciliation. */
    OPERATIONS_READ,
    /** Read the audit trail. Separate from reading operations: auditors. */
    AUDIT_READ,
    /** Answer an approval that is waiting for this person. */
    APPROVAL_DECIDE,
    /** Read the effective policy and its version history. */
    POLICY_READ,
    /** Publish a new policy version. The most dangerous permission here. */
    POLICY_WRITE,
    /** Read the enrolled devices of the organization. */
    DEVICE_READ,
    /** Enrol, name or retire a device. */
    DEVICE_WRITE,
    /** Read members, roles and grants. */
    MEMBERS_READ,
    /** Invite, suspend or remove a member, and change their roles. */
    MEMBERS_WRITE,
    /** Read whether the ERP integration is connected, and its capabilities. */
    ERP_READ,
    /** Point the organization at an ERP, or change its credentials. */
    ERP_WRITE,
}

/**
 * The bundles a new organization starts with.
 *
 * They mirror the four roles the demo shipped with, so an existing deployment
 * keeps working unchanged -- but they are named grants now, which is what lets
 * a fifth role exist without editing a `when` in the authority.
 */
object RoleCatalog {

    val SALES_REP: Set<Permission> = setOf(Permission.OPERATIONS_READ)
    val SALES_MANAGER: Set<Permission> = SALES_REP + Permission.APPROVAL_DECIDE
    val FINANCE_APPROVER: Set<Permission> = SALES_MANAGER + Permission.POLICY_READ
    val AUDITOR: Set<Permission> = setOf(Permission.AUDIT_READ, Permission.OPERATIONS_READ, Permission.POLICY_READ)
    val OPERATOR: Set<Permission> = setOf(
        Permission.OPERATIONS_READ,
        Permission.DEVICE_READ,
        Permission.DEVICE_WRITE,
        Permission.ERP_READ,
        Permission.MEMBERS_READ,
    )
    val ORG_ADMIN: Set<Permission> = Permission.entries.toSet()
    /** An administrator who may look and read, and deliberately may not write. */
    val ORG_AUDITOR: Set<Permission> = setOf(
        Permission.AUDIT_READ,
        Permission.OPERATIONS_READ,
        Permission.POLICY_READ,
        Permission.DEVICE_READ,
        Permission.MEMBERS_READ,
        Permission.ERP_READ,
    )

    /** The default bundle for a built-in role. */
    fun of(role: Role): Set<Permission> = when (role) {
        Role.SALES_REP -> SALES_REP
        Role.SALES_MANAGER -> SALES_MANAGER
        Role.FINANCE_APPROVER -> FINANCE_APPROVER
        Role.AUDITOR -> AUDITOR
        Role.OPERATOR -> OPERATOR
    }

    fun named(name: String): Set<Permission>? = when (name.trim().uppercase()) {
        "SALES_REP" -> SALES_REP
        "SALES_MANAGER" -> SALES_MANAGER
        "FINANCE_APPROVER" -> FINANCE_APPROVER
        "AUDITOR" -> AUDITOR
        "OPERATOR" -> OPERATOR
        "ORG_ADMIN" -> ORG_ADMIN
        "ORG_AUDITOR" -> ORG_AUDITOR
        else -> null
    }

    val names: List<String> = listOf(
        "OPERATOR",
        "SALES_REP",
        "SALES_MANAGER",
        "FINANCE_APPROVER",
        "AUDITOR",
        "ORG_AUDITOR",
        "ORG_ADMIN",
    )
}

/** One role as an organization defines it: a name and a set of permissions. */
data class RoleDefinition(
    val name: String,
    val permissions: Set<Permission>,
    /** Built-in roles cannot be edited or deleted through the admin surface. */
    val builtIn: Boolean = false,
) {
    fun allows(permission: Permission): Boolean = permission in permissions
}

/**
 * The roles of one organization: the built-ins plus whatever it defined.
 *
 * A custom role starts as a copy of a built-in one, which is the only way to
 * make a role without inventing a permission vocabulary in a web form.
 */
class RoleBook(roles: List<RoleDefinition> = RoleCatalog.names.map { name ->
    RoleDefinition(name = name, permissions = RoleCatalog.named(name) ?: emptySet(), builtIn = true)
}) {

    private val byName = LinkedHashMap<String, RoleDefinition>()

    init {
        roles.forEach { byName[it.name.uppercase()] = it }
    }

    fun define(name: String, permissions: Set<Permission>): RoleDefinition? {
        val key = name.trim().uppercase()
        if (key.isEmpty() || byName[key]?.builtIn == true) return null
        val created = RoleDefinition(name = key, permissions = permissions, builtIn = false)
        byName[key] = created
        return created
    }

    fun replace(name: String, permissions: Set<Permission>): RoleDefinition? {
        val key = name.trim().uppercase()
        val existing = byName[key] ?: return null
        if (existing.builtIn) return null
        val updated = existing.copy(permissions = permissions)
        byName[key] = updated
        return updated
    }

    fun remove(name: String): Boolean {
        val key = name.trim().uppercase()
        val existing = byName[key] ?: return false
        if (existing.builtIn) return false
        byName.remove(key)
        return true
    }

    fun get(name: String): RoleDefinition? = byName[name.trim().uppercase()]

    fun roles(): List<RoleDefinition> = byName.values.toList()

    /** Every permission any of the given roles grants, which is what a check asks. */
    fun permissionsFor(names: Collection<String>): Set<Permission> =
        names.flatMapTo(LinkedHashSet()) { byName[it.trim().uppercase()]?.permissions ?: emptySet() }
}
