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
