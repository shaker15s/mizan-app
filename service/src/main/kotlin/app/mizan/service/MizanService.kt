package app.mizan.service

import app.mizan.domain.model.ConnectorCapabilities
import app.mizan.domain.policy.PolicyCatalog
import app.mizan.domain.policy.PolicyEvaluator
import app.mizan.domain.policy.VersionedPolicy
import app.mizan.domain.audit.AuditSealer
import app.mizan.domain.receipt.ReceiptSigner
import app.mizan.domain.security.DeviceBindingService
import app.mizan.service.authority.ReferenceDeployment
import app.mizan.service.authority.ServiceAuthority
import app.mizan.service.erp.ErpConnector
import app.mizan.service.erp.InMemoryErp
import app.mizan.service.erp.InMemoryErpConnector
import app.mizan.service.http.GovernanceApi
import app.mizan.service.http.Http
import app.mizan.service.json.Json
import app.mizan.service.ledger.AuditLedger
import app.mizan.service.ledger.ExecutionLedger
import app.mizan.service.protocol.ExecutionOutcome
import app.mizan.service.protocol.ExecutionRequest
import app.mizan.service.approvals.ApprovalDesk
import app.mizan.service.http.ApprovalRoutes
import app.mizan.service.json.asObject
import app.mizan.service.json.field
import app.mizan.service.json.text
import app.mizan.service.protocol.MizanContract
import app.mizan.service.security.LimitSurface
import app.mizan.service.security.LoginThrottle
import app.mizan.service.security.RateLimiter
import app.mizan.service.security.ServiceUser
import app.mizan.service.security.SessionRegistry
import app.mizan.service.security.UserDirectory
import app.mizan.service.store.ServiceStores
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Everything a deployment may configure. The defaults describe the reference
 * service: in-memory, unsigned, with the labeled demo accounts.
 */
data class ServiceConfig(
    val host: String = "127.0.0.1",
    val port: Int = 8080,
    /** Null means the labeled demo accounts. A deployment always supplies its own. */
    val users: List<ServiceUser>? = null,
    val catalog: PolicyCatalog = PolicyCatalog.demo,
    val capabilities: ConnectorCapabilities = ReferenceDeployment.referenceCapabilities(),
    val sessionTtlMillis: Long = SessionRegistry.DEFAULT_TTL_MILLIS,
    /** When false the X-Mizan-Simulate header is ignored, as it must be outside tests. */
    val allowSimulationHeader: Boolean = true,
    val workerThreads: Int = 4,
    /**
     * When set, the journal, the idempotency index, sessions, receipts,
     * devices, approvals and reconciliation cases are written to this
     * directory and survive a restart.
     */
    val storeDirectory: Path? = null,
    /**
     * When set, the same durable state is kept in PostgreSQL instead of a
     * directory. A deployment with more than one service process needs this:
     * two processes writing ten files on two machines are two services.
     */
    val database: app.mizan.service.store.sql.DatabaseConfig? = null,
    /**
     * Bring your own durability. Set by tests and by a deployment that keeps
     * its records somewhere this file has never heard of.
     */
    val logProvider: app.mizan.service.store.LogProvider? = null,
    /**
     * When set, a verified write is signed with this key ring's active key.
     *
     * An HMAC secret keeps the receipt verifiable by the service and by an
     * auditor who holds the secret. It cannot be verified by the device, and
     * [receiptKeyPair] exists for that reason: a phone that can verify with a
     * public key, and cannot mint with it, is the difference between a proof
     * and a log line.
     */
    val signingSecret: String? = null,
    /**
     * The authority's Ed25519 key pair. When set, it signs receipts and its
     * public half is published on `GET /v1/capabilities` so a client build can
     * pin it. Takes precedence over [signingSecret].
     */
    val receiptKeyPair: app.mizan.domain.receipt.AuthorityKeyPair? = null,
    /** When true, a tool that demands a fresh proof refuses without a device signature. */
    val requireDeviceProof: Boolean = false,
    /** The versioned policy decisions are stamped with. */
    val versionedPolicy: VersionedPolicy = VersionedPolicy.unversioned,
    /** The connector the authority writes through. Defaults to the reference adapter. */
    val connector: ErpConnector? = null,
    val rateLimiting: Boolean = true,
    /**
     * How often the retry sweeper runs, in milliseconds. Zero disables it, and
     * a deployment that runs the sweeper as a separate process sets it to zero
     * here rather than having two workers race for the same entries.
     */
    val outboxSweepMillis: Long = 15_000L,
    /** Per-surface budgets. A test that must observe a refusal passes strict ones. */
    val budgets: Map<app.mizan.service.security.LimitSurface, app.mizan.service.security.Budget> =
        app.mizan.service.security.LimitSurface.defaultBudgets,
)

/**
 * The reference Wakeel service.
 *
 * It listens on plain HTTP and is expected to sit behind a TLS terminator: the
 * Android client refuses to send a write to anything but an HTTPS URL, and
 * that refusal is a security property, not a convenience.
 *
 * Routes:
 *
 * ```text
 * POST /v1/sessions                    issue a short-lived token
 * DELETE /v1/sessions/current          revoke the caller's token
 * POST /v1/executions                  decide and, when allowed, perform a governed write
 * GET  /v1/executions/{id}             one execution's journal entry
 * GET  /v1/journal?tenant=...          the journal of a tenant
 * GET  /v1/health                      liveness and counters
 * GET  /v1/audit?tenant=...            the service-side audit chain of one tenant
 * GET  /v1/erp?tenant=...              read-only view of the configured ERP state
 * GET  /v1/capabilities                what this deployment can do, for the client
 * GET  /v1/tools                       the tool contracts, for capability-driven UI
 * GET  /v1/policy                      the versioned policy a decision is stamped with
 * GET  /v1/reconciliation?tenant=...   uncertain writes waiting for a person
 * POST /v1/reconciliation/{id}/resolve link or close a case
 * GET  /v1/receipts/{id}               a signed receipt and its verification
 * POST /v1/devices                     enroll a device public key
 * GET  /v1/devices?tenant=...          the tenant's enrolled devices
 * POST /v1/devices/challenge           a challenge for a sensitive approval
 * ```
 */
class MizanService(
    private val config: ServiceConfig = ServiceConfig(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    init {
        // Checked before anything is opened: a configuration that names two
        // homes for the same state must be refused, not resolved by whichever
        // initialiser happens to run first.
        require(
            !(config.database != null && config.storeDirectory != null && config.logProvider == null),
        ) { "a deployment keeps its records in one place: a directory or a database, not both" }
    }

    val erp: InMemoryErp = InMemoryErp()
    val connector: ErpConnector = config.connector ?: InMemoryErpConnector(erp, clock)
    val audit = AuditLedger(clock, AuditSealer.of(config.receiptKeyPair))
    val executions = ExecutionLedger()
    val directory = UserDirectory(config.users ?: ReferenceDeployment.demoUsers())
    val sessions = SessionRegistry(config.sessionTtlMillis, clock)
    val policy = PolicyEvaluator(config.catalog, config.versionedPolicy.version, config.versionedPolicy.rules, clock)
    val rateLimiter = RateLimiter(clock, config.rateLimiting, config.budgets)
    val loginThrottle = LoginThrottle(clock = clock)
    val stores: ServiceStores? = when {
        config.logProvider != null -> ServiceStores(config.logProvider, clock)
        config.database != null -> ServiceStores(
            app.mizan.service.store.sql.SqlLogProvider(
                app.mizan.service.store.sql.JdbcDatabase(
                    url = config.database.url,
                    user = config.database.user,
                    password = config.database.password,
                    driverClass = config.database.driverClass,
                ),
                config.database.table,
            ),
            clock,
        )
        config.storeDirectory != null -> ServiceStores(config.storeDirectory, clock)
        else -> null
    }

    val signer: ReceiptSigner? = when {
        config.receiptKeyPair != null -> ReceiptSigner(listOf(config.receiptKeyPair))
        config.signingSecret != null -> ReceiptSigner(listOf(ReceiptSigner.demoKey(config.signingSecret)))
        else -> null
    }
    val devices: DeviceBindingService? = stores?.let {
        DeviceBindingService(it.devices, it.challenges, clock)
    }
    val governance = GovernanceApi(
        config = config,
        connector = connector,
        policy = policy,
        versionedPolicy = config.versionedPolicy,
        stores = stores,
        signer = signer,
        devices = devices,
        directory = directory,
        audit = audit,
        clock = clock,
    )
    val authority = ServiceAuthority(
        connector = connector,
        ledger = executions,
        audit = audit,
        directory = directory,
        capabilities = config.capabilities,
        policy = policy,
        clock = clock,
        stores = stores,
        signer = signer,
        deviceBinding = devices,
        requireDeviceProof = config.requireDeviceProof && devices != null,
        versionedPolicy = config.versionedPolicy,
        rateLimiter = rateLimiter,
    )

    val approvals = ApprovalDesk(
        authority = authority,
        stores = stores,
        devices = devices,
        requireDeviceProof = config.requireDeviceProof,
        clock = clock,
    )

    private val approvalRoutes = ApprovalRoutes(
        approvals = approvals,
        governance = governance,
        stores = stores,
        authenticate = ::authenticate,
    )

    /**
     * Retries the reads the ERP could not answer. Present only when the
     * deployment has a durable store: an in-memory retry queue that dies with
     * the process would be a promise the service cannot keep.
     */
    val outbox: ServiceOutboxWorker? = stores?.let {
        ServiceOutboxWorker(
            stores = it,
            authority = authority,
            clock = clock,
            capabilities = config.capabilities,
        )
    }

    private var server: HttpServer? = null
    private var sweeper: Thread? = null
    private var pool = Executors.newFixedThreadPool(config.workerThreads)

    /**
     * Starts the server and returns the port it is listening on. Port 0 asks
     * the operating system for a free port, which is what tests use.
     */
    @Synchronized
    fun start(port: Int = config.port, host: String = config.host): Int {
        check(server == null) { "service already started" }
        val http = HttpServer.create(InetSocketAddress(host, port), 0)
        http.createContext(MizanContract.PATH_SESSIONS, this::handleSessions)
        http.createContext(MizanContract.PATH_EXECUTIONS, this::handleExecutions)
        http.createContext(MizanContract.PATH_JOURNAL, this::handleJournal)
        http.createContext(MizanContract.PATH_HEALTH, this::handleHealth)
        http.createContext(MizanContract.PATH_AUDIT, this::handleAudit)
        http.createContext(MizanContract.PATH_ERP, this::handleErp)
        http.createContext(MizanContract.PATH_CAPABILITIES, governance::capabilities)
        http.createContext(MizanContract.PATH_TOOLS, governance::tools)
        http.createContext(MizanContract.PATH_POLICY, governance::policy)
        http.createContext(MizanContract.PATH_RECONCILIATION, governance::reconciliation)
        http.createContext(MizanContract.PATH_RECEIPTS, governance::receipts)
        http.createContext(MizanContract.PATH_DEVICES, this::handleDevices)
        http.createContext(MizanContract.PATH_APPROVALS, approvalRoutes::handle)
        pool = Executors.newFixedThreadPool(config.workerThreads)
        http.executor = pool
        http.start()
        server = http
        // Anything a previous process left in flight is put back before the
        // first pass, so a restart does not quietly drop the work.
        outbox?.recover()
        startOutboxSweeper()
        return http.address.port
    }

    @Synchronized
    fun stop() {
        sweeper?.interrupt()
        sweeper = null
        server?.stop(0)
        server = null
        pool.shutdownNow()
        stores?.close()
    }

    /**
     * Drains the retry queue on a timer.
     *
     * A queue that nobody drains is a log file with extra steps, so the
     * service that writes the entries is also the one that takes them back
     * out. The thread is a daemon and does exactly one short pass per
     * interval: no pass may hold a lock a request needs, and a failure during
     * a pass is counted, never fatal.
     */
    private fun startOutboxSweeper() {
        val worker = outbox ?: return
        if (config.outboxSweepMillis <= 0L) return
        val thread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(config.outboxSweepMillis)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                runCatching { worker.runOnce(users = directory.all()) }
            }
        }, "mizan-outbox-sweeper")
        thread.isDaemon = true
        thread.start()
        sweeper = thread
    }

    val isRunning: Boolean
        get() = server != null

    /** The port the listener is actually bound to. Zero when it is not running. */
    val boundPort: Int
        get() = server?.address?.port ?: 0

    // ------------------------------------------------------------------ routes

    private fun handleSessions(exchange: HttpExchange) = Http.serve(exchange) {
        val path = exchange.requestURI.path.removePrefix(MizanContract.PATH_SESSIONS)
        if (path.startsWith("/current")) {
            revokeCurrent(exchange)
            return@serve
        }
        if (exchange.requestMethod != "POST") {
            Http.respond(exchange, 405, Json.obj("messageCode" to Json.str("METHOD_NOT_ALLOWED")))
            return@serve
        }
        val body = Http.readJsonObject(exchange)
        val email = body?.text("email")
        val password = body?.text("password")
        if (email.isNullOrBlank() || password.isNullOrBlank()) {
            Http.respond(exchange, 400, Json.obj("messageCode" to Json.str("SIGN_IN_BODY")))
            return@serve
        }
        val address = exchange.remoteAddress?.address?.hostAddress ?: "unknown"
        // A correct password never costs budget: the limiter bounds *failures*,
        // which is what credential stuffing is made of.
        val decision = rateLimiter.peek(LimitSurface.SIGN_IN, email, address)
        if (!decision.allowed) {
            Http.respond(
                exchange,
                429,
                Json.obj("messageCode" to Json.str(decision.reasonCode)),
                mapOf("Retry-After" to decision.retryAfterSeconds.toString()),
            )
            return@serve
        }
        if (loginThrottle.isLocked(email)) {
            // One answer for an unknown account, a wrong password and a lockout.
            Http.respond(exchange, 401, Json.obj("messageCode" to Json.str("SESSION_DENIED")))
            return@serve
        }
        val user = directory.signIn(email, password)
        if (user == null) {
            loginThrottle.recordFailure(email)
            rateLimiter.consume(LimitSurface.SIGN_IN, email, address)
            Http.respond(exchange, 401, Json.obj("messageCode" to Json.str("SESSION_DENIED")))
            return@serve
        }
        loginThrottle.recordSuccess(email)
        val issued = sessions.issue(user)
        val token = issued.first
        val session = issued.second
        stores?.sessions?.save(
            app.mizan.service.store.SessionStore.Record(
                tokenFingerprint = session.tokenFingerprint,
                actorId = user.actorId,
                tenantId = user.tenantId,
                issuedAtMillis = session.issuedAtEpochMillis,
                expiresAtMillis = session.expiresAtEpochMillis,
            ),
        )
        audit.append(
            tenantId = user.tenantId,
            traceId = "session",
            actorId = user.actorId,
            action = "SESSION_ISSUED",
            stateBefore = "ANONYMOUS",
            stateAfter = "AUTHENTICATED",
            details = "fingerprint=${session.tokenFingerprint}",
        )
        Http.respond(
            exchange,
            200,
            Json.obj(
                "token" to Json.str(token),
                "actorId" to Json.str(user.actorId),
                "displayName" to Json.str(user.displayName),
                "role" to Json.str(user.role.name),
                "tenantId" to Json.str(user.tenantId),
                "tenantLabel" to Json.str(user.tenantLabel),
                "expiresAtEpochMillis" to Json.num(session.expiresAtEpochMillis),
            ),
        )
    }

    private fun revokeCurrent(exchange: HttpExchange) {
        if (exchange.requestMethod != "DELETE" && exchange.requestMethod != "POST") {
            Http.respond(exchange, 405, Json.obj("messageCode" to Json.str("METHOD_NOT_ALLOWED")))
            return
        }
        val token = Http.bearer(exchange.requestHeaders.getFirst(MizanContract.HEADER_AUTHORIZATION))
        val session = sessions.resolve(token)
        if (session == null || token == null) {
            Http.respond(exchange, 401, Json.obj("messageCode" to Json.str("SESSION_EXPIRED")))
            return
        }
        sessions.revoke(token)
        stores?.sessions?.revoke(session.tokenFingerprint, clock())
        audit.append(
            tenantId = session.user.tenantId,
            traceId = "session",
            actorId = session.user.actorId,
            action = "SESSION_REVOKED",
            stateBefore = "AUTHENTICATED",
            stateAfter = "ANONYMOUS",
            details = "fingerprint=${session.tokenFingerprint}",
        )
        Http.respond(exchange, 200, Json.obj("messageCode" to Json.str("SESSION_REVOKED")))
    }

    private fun handleExecutions(exchange: HttpExchange) = Http.serve(exchange) {
        val session = authenticate(exchange) ?: return@serve
        val path = exchange.requestURI.path.removePrefix(MizanContract.PATH_EXECUTIONS).trim('/')
        if (path.isNotEmpty() && exchange.requestMethod == "GET") {
            val journal = stores?.journals?.get(path)
            if (journal == null) {
                Http.respond(exchange, 404, Json.obj("messageCode" to Json.str("EXECUTION_NOT_FOUND")))
                return@serve
            }
            if (journal.tenantId.value != session.user.tenantId) {
                Http.respond(exchange, 403, Json.obj("messageCode" to Json.str("TENANT_MISMATCH")))
                return@serve
            }
            Http.respond(exchange, 200, governance.journalJson(journal))
            return@serve
        }
        if (exchange.requestMethod != "POST") {
            Http.respond(exchange, 405, Json.obj("messageCode" to Json.str("METHOD_NOT_ALLOWED")))
            return@serve
        }
        val headers = exchange.requestHeaders
        val read = Http.readBody(exchange)
        if (read.tooLarge) {
            Http.respond(
                exchange,
                413,
                Json.obj(
                    "status" to Json.str(MizanContract.Status.REJECTED),
                    "messageCode" to Json.str("REQUEST_TOO_LARGE"),
                ),
            )
            return@serve
        }
        val body = read.value
        if (body == null) {
            Http.respond(
                exchange,
                400,
                Json.obj(
                    "status" to Json.str(MizanContract.Status.REJECTED),
                    "messageCode" to Json.str("REQUEST_BODY"),
                ),
            )
            return@serve
        }
        val request = ExecutionRequest.parse(
            body = body,
            fallbackExecutionId = "EXE-" + UUID.randomUUID().toString().take(8).uppercase(),
            traceHeader = headers.getFirst(MizanContract.HEADER_TRACE_ID),
            idempotencyHeader = headers.getFirst(MizanContract.HEADER_IDEMPOTENCY_KEY),
        )
        if (request == null) {
            Http.respond(
                exchange,
                400,
                Json.obj(
                    "status" to Json.str(MizanContract.Status.REJECTED),
                    "messageCode" to Json.str("REQUEST_BODY"),
                ),
            )
            return@serve
        }
        val simulate = config.allowSimulationHeader &&
            headers.getFirst(MizanContract.HEADER_SIMULATE) == MizanContract.SIMULATE_AMBIGUOUS
        respondOutcome(exchange, authority.decide(request, session.user, simulate))
    }

    private fun respondOutcome(exchange: HttpExchange, outcome: ExecutionOutcome) {
        when (outcome) {
            is ExecutionOutcome.Verified -> Http.respond(
                exchange,
                200,
                Json.obj(
                    "status" to Json.str(MizanContract.Status.VERIFIED),
                    "executionId" to Json.str(outcome.executionId),
                    "erpRecordId" to Json.str(outcome.erpRecordId),
                    "erpModel" to Json.str(outcome.erpModel),
                    "verification" to Json.str("READ_BACK"),
                    "verifiedFields" to Json.arr(outcome.verifiedFields.map { Json.str(it) }),
                    "receiptId" to Json.str(outcome.receiptId),
                    "receiptSignature" to Json.str(outcome.receiptSignature),
                    "receiptKeyId" to Json.str(outcome.receiptKeyId),
                    "receiptAlgorithm" to Json.str(outcome.receiptAlgorithm),
                    "summary" to Json.str(outcome.summary),
                ),
            )
            is ExecutionOutcome.Accepted -> Http.respond(
                exchange,
                200,
                Json.obj(
                    "status" to Json.str(MizanContract.Status.ACCEPTED),
                    "executionId" to Json.str(outcome.executionId),
                    "messageCode" to Json.str(outcome.messageCode),
                    "erpRecordId" to Json.str(outcome.erpRecordId),
                    "erpModel" to Json.str(outcome.erpModel),
                    "summary" to Json.str(outcome.summary),
                ),
            )
            is ExecutionOutcome.Ambiguous -> Http.respond(
                exchange,
                200,
                Json.obj(
                    "status" to Json.str(MizanContract.Status.AMBIGUOUS),
                    "executionId" to Json.str(outcome.executionId),
                    "messageCode" to Json.str(outcome.reasonCode ?: "SERVICE_AMBIGUOUS"),
                    "reconciliationId" to Json.str(outcome.executionId.replace("EXE-", "REC-")),
                    "possibleRecordId" to Json.str(outcome.possibleRecordId),
                    "possibleModel" to Json.str(outcome.possibleModel),
                    "candidates" to Json.str(outcome.candidateRecordIds.joinToString(",")),
                ),
            )
            is ExecutionOutcome.Rejected -> Http.respond(
                exchange,
                outcome.httpStatus,
                Json.obj(
                    "status" to Json.str(MizanContract.Status.REJECTED),
                    "executionId" to Json.str(outcome.executionId),
                    "messageCode" to Json.str(outcome.messageCode),
                ),
                if (outcome.retryAfterSeconds > 0L) {
                    mapOf("Retry-After" to outcome.retryAfterSeconds.toString())
                } else {
                    emptyMap()
                },
            )
            is ExecutionOutcome.Failed -> Http.respond(
                exchange,
                200,
                Json.obj(
                    "status" to Json.str(MizanContract.Status.FAILED),
                    "executionId" to Json.str(outcome.executionId),
                    "messageCode" to Json.str(outcome.messageCode),
                ),
            )
        }
    }

    private fun handleJournal(exchange: HttpExchange) = Http.serve(exchange) {
        val session = authenticate(exchange) ?: return@serve
        val store = stores?.journals
        if (store == null) {
            Http.respond(exchange, 200, Json.obj("durable" to Json.bool(false), "entries" to Json.arr(emptyList())))
            return@serve
        }
        val requested = Http.query(exchange, "tenant") ?: session.user.tenantId
        if (requested != session.user.tenantId) {
            Http.respond(exchange, 403, Json.obj("messageCode" to Json.str("TENANT_MISMATCH")))
            return@serve
        }
        val limit = Http.query(exchange, "limit")?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        val entries = store.forTenant(requested, limit).map { governance.journalJson(it) }
        Http.respond(exchange, 200, Json.obj("durable" to Json.bool(true), "entries" to Json.arr(entries)))
    }

    private fun handleHealth(exchange: HttpExchange) = Http.serve(exchange) {
        Http.respond(
            exchange,
            200,
            Json.obj(
                "service" to Json.str("wakeel-reference"),
                "status" to Json.str("ok"),
                "erp" to Json.str(connector.id),
                "durable" to Json.bool(stores != null),
                "signed" to Json.bool(signer != null),
                "policyVersion" to Json.str(config.versionedPolicy.version.id),
                "tools" to Json.num(app.mizan.domain.tool.ToolCatalog.definitions.size),
                "executions" to Json.num(executions.size()),
                "journalEntries" to Json.num(stores?.journals?.count() ?: 0),
                "tenants" to Json.num(audit.tenants().size),
                "rateLimited" to Json.num(rateLimiter.refusalCount()),
                "outbox" to Json.obj(
                    "durable" to Json.bool(stores != null),
                    "sweeping" to Json.bool(sweeper != null),
                    "pending" to Json.num(stores?.outbox?.pending()?.size ?: 0),
                    "deadLettered" to Json.num(stores?.outbox?.deadLettered()?.size ?: 0),
                ),
                "nowEpochMillis" to Json.num(clock()),
            ),
        )
    }

    private fun handleAudit(exchange: HttpExchange) = Http.serve(exchange) {
        val session = authenticate(exchange) ?: return@serve
        val tenant = Http.query(exchange, "tenant") ?: session.user.tenantId
        if (tenant != session.user.tenantId) {
            Http.respond(exchange, 403, Json.obj("messageCode" to Json.str("TENANT_MISMATCH")))
            return@serve
        }
        val durableEvents = stores?.audit?.forTenant(tenant)
        val events = if (durableEvents.isNullOrEmpty()) audit.events(tenant) else durableEvents
        // The verifier is built with the deployment's sealer, so a row someone
        // added by hand is reported as unsealed instead of passing because the
        // checker never looked.
        val report = app.mizan.domain.audit.ChainVerifier(AuditSealer.of(config.receiptKeyPair)).verify(events)
        Http.respond(
            exchange,
            200,
            Json.obj(
                "tenant" to Json.str(tenant),
                "chainIntact" to Json.bool(report.intact),
                "messageCode" to Json.str(report.messageCode),
                "records" to Json.num(report.records),
                "sealed" to Json.bool(report.fullySealed),
                "sealedRecords" to Json.num(report.sealedRecords),
                "verifiedSeals" to Json.num(report.verifiedSeals),
                "sealKeyId" to Json.str(config.receiptKeyPair?.keyId),
                "integrityClass" to Json.str(app.mizan.domain.audit.IntegrityClass.SERVER_AUTHORED.name),
                "events" to Json.arr(
                    events.map { event ->
                        Json.obj(
                            "chainIndex" to Json.num(event.chainIndex),
                            "action" to Json.str(event.action),
                            "actorId" to Json.str(event.actorId),
                            "details" to Json.str(event.details),
                            "integrityClass" to Json.str(event.integrityClass.name),
                            "timestampMillis" to Json.num(event.timestampMillis),
                            "sealKeyId" to Json.str(event.seal?.keyId),
                            "sealAlgorithm" to Json.str(event.seal?.algorithm),
                        )
                    },
                ),
            ),
        )
    }

    private fun handleErp(exchange: HttpExchange) = Http.serve(exchange) {
        val session = authenticate(exchange) ?: return@serve
        val tenant = Http.query(exchange, "tenant") ?: session.user.tenantId
        if (tenant != session.user.tenantId) {
            Http.respond(exchange, 403, Json.obj("messageCode" to Json.str("TENANT_MISMATCH")))
            return@serve
        }
        val snapshot = erp.snapshot(tenant)
        Http.respond(
            exchange,
            200,
            Json.obj(
                "tenant" to Json.str(tenant),
                "erp" to Json.str(connector.id),
                "orders" to Json.arr(
                    snapshot.orders.map { (id, state) ->
                        Json.obj("id" to Json.str(id), "state" to Json.str(state))
                    },
                ),
                "invoices" to Json.arr(
                    snapshot.invoices.map { (id, currency) ->
                        Json.obj("id" to Json.str(id), "currency" to Json.str(currency))
                    },
                ),
                "payments" to Json.arr(
                    snapshot.payments.map { (id, currency) ->
                        Json.obj("id" to Json.str(id), "currency" to Json.str(currency))
                    },
                ),
            ),
        )
    }

    private fun handleDevices(exchange: HttpExchange) = Http.serve(exchange) {
        val session = authenticate(exchange) ?: return@serve
        val binding = devices
        if (binding == null) {
            Http.respond(exchange, 503, Json.obj("messageCode" to Json.str("DEVICE_STORE_UNAVAILABLE")))
            return@serve
        }
        governance.devices(exchange, session.user.actorId, session.user.tenantId)
    }

    // --------------------------------------------------------------- utilities

    private fun authenticate(exchange: HttpExchange): app.mizan.service.security.ServiceSession? {
        val token = Http.bearer(exchange.requestHeaders.getFirst(MizanContract.HEADER_AUTHORIZATION))
        val session = sessions.resolve(token)
        if (session != null) return session
        // A restart must not log everybody out: the durable session record is
        // consulted before the request is refused.
        val durable = token?.let { stores?.sessions?.find(app.mizan.domain.model.Digests.sha256(it).take(16)) }
        if (durable != null && durable.active(clock())) {
            val user = directory.find(durable.tenantId, durable.actorId) ?: return null
            return app.mizan.service.security.ServiceSession(
                tokenFingerprint = durable.tokenFingerprint,
                user = user,
                issuedAtEpochMillis = durable.issuedAtMillis,
                expiresAtEpochMillis = durable.expiresAtMillis,
            )
        }
        Http.respond(
            exchange,
            401,
            Json.obj(
                "status" to Json.str(MizanContract.Status.REJECTED),
                "messageCode" to Json.str("SESSION_EXPIRED"),
            ),
        )
        return null
    }
}
