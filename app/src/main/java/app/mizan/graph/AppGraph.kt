package app.mizan.graph

import android.content.Context
import app.mizan.BuildConfig
import app.mizan.data.local.MizanDatabase
import app.mizan.data.repo.RoomAuditStore
import app.mizan.data.repo.RoomExecutionStore
import app.mizan.data.repo.RoomReadModelStore
import app.mizan.data.repo.RoomReceiptStore
import app.mizan.data.repo.RoomReconciliationStore
import app.mizan.data.repo.RoomSyncStore
import app.mizan.data.security.SessionTokenStore
import app.mizan.domain.agent.IntentInterpreter
import app.mizan.domain.agent.ProposalService
import app.mizan.domain.attention.AttentionPlanner
import app.mizan.domain.audit.ChainVerifier
import app.mizan.domain.authority.ExecutionAuthority
import app.mizan.domain.execution.ExecutionRecovery
import app.mizan.domain.execution.ExecutionStateMachine
import app.mizan.domain.execution.IdempotencyGuard
import app.mizan.domain.model.TimeSource
import app.mizan.domain.policy.PolicyCatalog
import app.mizan.domain.policy.PolicyEvaluator
import app.mizan.domain.policy.SeparationOfDuties
import app.mizan.domain.risk.RiskEvaluator
import app.mizan.domain.security.ReauthenticationPolicy
import app.mizan.domain.store.AuditStore
import app.mizan.domain.store.ExecutionStore
import app.mizan.domain.store.ReadModelStore
import app.mizan.domain.store.ReceiptStore
import app.mizan.domain.store.ReconciliationStore
import app.mizan.domain.store.SyncStore
import app.mizan.createAuthority
import app.mizan.health.DeviceHealth
import app.mizan.prefs.UserPreferences
import app.mizan.session.SessionController

class AppGraph(context: Context) {
    val preferences = UserPreferences(context)
    val tokens = SessionTokenStore(context)
    val session = SessionController()
    val health = DeviceHealth(context)
    val time = TimeSource.system
    val demoMode = BuildConfig.DEMO_MODE
    val environment = BuildConfig.MIZAN_ENV
    val apiBaseUrl: String = BuildConfig.API_BASE_URL.ifBlank { preferences.serviceUrlOverride }

    val executions: ExecutionStore
    val receipts: ReceiptStore
    val audit: AuditStore
    val cases: ReconciliationStore
    val readModels: ReadModelStore
    val sync: SyncStore
    val policy: PolicyEvaluator
    val proposals: ProposalService
    val authority: ExecutionAuthority
    val reauth: ReauthenticationPolicy
    val sod = SeparationOfDuties()
    val attention = AttentionPlanner()
    val chain = ChainVerifier()
    val recovery = ExecutionRecovery()
    val idempotency = IdempotencyGuard()
    val machine = ExecutionStateMachine()

    init {
        val database = MizanDatabase.create(context)
        val dao = database.dao()
        executions = RoomExecutionStore(dao)
        receipts = RoomReceiptStore(dao)
        audit = RoomAuditStore(dao)
        cases = RoomReconciliationStore(dao)
        readModels = RoomReadModelStore(dao, receipts, executions, cases)
        sync = RoomSyncStore(dao)
        val catalog = if (demoMode) PolicyCatalog.demo else PolicyCatalog.empty
        policy = PolicyEvaluator(catalog)
        proposals = ProposalService(
            interpreter = IntentInterpreter(),
            policy = policy,
            risk = RiskEvaluator(),
            time = time,
            policyIsPreview = !demoMode,
        )
        reauth = ReauthenticationPolicy(time, acceptSimulated = demoMode)
        authority = createAuthority(
            AuthorityDeps(
                executions = executions,
                receipts = receipts,
                cases = cases,
                readModels = readModels,
                audit = audit,
                time = time,
                policy = policy,
                sod = sod,
                reauth = reauth,
                machine = machine,
                idempotency = idempotency,
                apiBaseUrl = apiBaseUrl,
                token = { tokens.read() },
            ),
        )
    }
}

data class AuthorityDeps(
    val executions: ExecutionStore,
    val receipts: ReceiptStore,
    val cases: ReconciliationStore,
    val readModels: ReadModelStore,
    val audit: AuditStore,
    val time: TimeSource,
    val policy: PolicyEvaluator,
    val sod: SeparationOfDuties,
    val reauth: ReauthenticationPolicy,
    val machine: ExecutionStateMachine,
    val idempotency: IdempotencyGuard,
    val apiBaseUrl: String,
    val token: () -> String?,
)
