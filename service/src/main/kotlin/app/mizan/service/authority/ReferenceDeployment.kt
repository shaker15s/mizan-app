package app.mizan.service.authority

import app.mizan.domain.model.ConnectorCapabilities
import app.mizan.domain.policy.PolicyCatalog
import app.mizan.domain.policy.PolicyEvaluator
import app.mizan.domain.tool.ToolCatalog
import app.mizan.domain.model.Role
import app.mizan.service.erp.InMemoryErp
import app.mizan.service.erp.InMemoryErpConnector
import app.mizan.service.ledger.AuditLedger
import app.mizan.service.ledger.ExecutionLedger
import app.mizan.service.security.ServiceUser
import app.mizan.service.security.UserDirectory

/**
 * The reference deployment: the accounts, the ERP and the capability set the
 * demo build ships with.
 *
 * It is a separate file from the authority on purpose. Demo credentials inside
 * the class that decides policy is how a labeled demo value ends up one merge
 * away from being real; here it is obviously an artifact of the build, and a
 * deployment replaces it wholesale.
 *
 * Every account below is a demo account with a published password. Nothing in
 * this file may be used in production.
 */
object ReferenceDeployment {

    /** Accounts the reference service ships with. Demo only, never production. */
    fun demoUsers(): List<ServiceUser> = listOf(
        ServiceUser.of(
            email = "rep@mizan.test",
            password = "rep-demo-password",
            actorId = "USR-REP",
            displayName = "Amr Kamel",
            role = Role.SALES_REP,
            tenantId = "sim-alamal",
            tenantLabel = "Al-Amal Trading",
        ),
        ServiceUser.of(
            email = "manager@mizan.test",
            password = "manager-demo-password",
            actorId = "USR-MGR",
            displayName = "Tarek Fouad",
            role = Role.SALES_MANAGER,
            tenantId = "sim-alamal",
            tenantLabel = "Al-Amal Trading",
        ),
        ServiceUser.of(
            email = "finance@mizan.test",
            password = "finance-demo-password",
            actorId = "USR-FIN",
            displayName = "Noha Adel",
            role = Role.FINANCE_APPROVER,
            tenantId = "sim-alamal",
            tenantLabel = "Al-Amal Trading",
        ),
        ServiceUser.of(
            email = "auditor@mizan.test",
            password = "auditor-demo-password",
            actorId = "USR-AUD",
            displayName = "Hisham Sayed",
            role = Role.AUDITOR,
            tenantId = "sim-alamal",
            tenantLabel = "Al-Amal Trading",
        ),
    )

    /**
     * Gives the demo accounts their memberships, once.
     *
     * The reference deployment ships four accounts and no organization, so
     * the deployment seeds the organization their tenant id names. The
     * alternative -- a permission check that falls back to the account's
     * built-in role -- would mean the demo logins obey a different rule from
     * every account a company onboards, and the rule that is tested would not
     * be the rule that runs. The manager is the administrator: the four
     * accounts have different powers, and a rep who became an administrator
     * because they happened to be first in a list would be a lie.
     *
     * Idempotent on purpose. It runs at start-up, and the store it writes to
     * may already contain the seeded rows.
     */
    fun seedOrganization(
        organizations: app.mizan.service.store.OrganizationStore,
        users: List<ServiceUser>,
    ) {
        val tenantId = users.firstOrNull()?.tenantId ?: return
        val label = users.firstOrNull()?.tenantLabel ?: tenantId
        if (organizations.book.get(tenantId) == null) {
            val owner = users.firstOrNull { it.role == Role.SALES_MANAGER } ?: users.first()
            organizations.create(
                id = tenantId,
                label = label,
                plan = app.mizan.domain.org.OrgPlan.ENTERPRISE,
                ownerActorId = owner.actorId,
                ownerEmail = owner.email,
                ownerName = owner.displayName,
            )
        }
        val owner = organizations.book.administrators(tenantId).firstOrNull() ?: return
        for (user in users) {
            if (organizations.book.member(tenantId, user.actorId) != null) continue
            val roles = when (user.role) {
                Role.AUDITOR -> listOf("AUDITOR", "ORG_AUDITOR")
                Role.FINANCE_APPROVER -> listOf("FINANCE_APPROVER", "ORG_ADMIN")
                Role.SALES_MANAGER -> listOf("SALES_MANAGER", "ORG_ADMIN")
                else -> listOf(user.role.name)
            }
            val invited = organizations.invite(
                app.mizan.domain.org.Membership(
                    orgId = tenantId,
                    actorId = user.actorId,
                    email = user.email,
                    displayName = user.displayName,
                    roleNames = roles,
                    status = app.mizan.domain.org.MemberStatus.INVITED,
                    addedAtMillis = 0L,
                    addedBy = "reference-deployment",
                ),
                invitedBy = owner.email,
            )
            if (invited is app.mizan.domain.org.OrgResult.Ok) {
                organizations.activate(tenantId, user.actorId)
            }
        }
        // An account left INVITED would hold a role and no permissions, which
        // is right for an invitation and wrong for an account the build ships.
        organizations.activate(tenantId, owner.actorId)
    }

    /** What the reference ERP adapter claims to support. */
    fun referenceCapabilities(): ConnectorCapabilities = ConnectorCapabilities(
        connectorId = "reference-in-memory",
        supportsDraftOrders = true,
        supportsOrderCancel = true,
        supportsInvoiceCreation = true,
        supportsPayment = true,
        supportsVerification = true,
        supportsBatchRead = false,
        supportsJson2 = false,
        supportsLegacyRpc = false,
    )

    /** A reference authority over the in-memory adapter, for tests and demos. */
    fun reference(
        ledger: ExecutionLedger,
        audit: AuditLedger,
        directory: UserDirectory,
        policy: PolicyEvaluator,
        clock: () -> Long = { System.currentTimeMillis() },
    ): ServiceAuthority = ServiceAuthority(
        connector = InMemoryErpConnector(InMemoryErp(), clock),
        ledger = ledger,
        audit = audit,
        directory = directory,
        capabilities = referenceCapabilities(),
        policy = policy,
        clock = clock,
    )

    /**
     * The diff a person should see when a proposal moved under an approval.
     *
     * It lives beside the reference wiring because it is a convenience for
     * callers of [reference], not a decision: the authority itself refuses an
     * approval whose proposal moved, and this only explains what moved.
     */
    fun diffOf(before: app.mizan.domain.model.Proposal, after: app.mizan.domain.model.Proposal) =
        app.mizan.domain.approval.ProposalDiff.between(before, after)
}
