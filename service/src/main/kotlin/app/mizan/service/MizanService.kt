package app.mizan.service

import app.mizan.domain.model.ConnectorCapabilities
import app.mizan.domain.policy.PolicyCatalog
import app.mizan.domain.policy.PolicyEvaluator
import app.mizan.service.authority.ServiceAuthority
import app.mizan.service.erp.InMemoryErp
import app.mizan.service.json.Json
import app.mizan.service.json.JsonValue
import app.mizan.service.json.asObject
import app.mizan.service.json.text
import app.mizan.service.ledger.AuditLedger
import app.mizan.service.ledger.ExecutionLedger
import app.mizan.service.protocol.ExecutionOutcome
import app.mizan.service.protocol.ExecutionRequest
import app.mizan.service.protocol.MizanContract
import app.mizan.service.security.ServiceUser
import app.mizan.service.security.SessionRegistry
import app.mizan.service.security.UserDirectory
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors

data class ServiceConfig(
    val host: String = "127.0.0.1",
    val port: Int = 8080,
    /** Null means the labeled demo accounts. A deployment always supplies its own. */
    val users: List<ServiceUser>? = null,
    val catalog: PolicyCatalog = PolicyCatalog.demo,
    val capabilities: ConnectorCapabilities = ServiceAuthority.referenceCapabilities(),
    val sessionTtlMillis: Long = SessionRegistry.DEFAULT_TTL_MILLIS,
    /** When false the X-Mizan-Simulate header is ignored, as it must be outside tests. */
    val allowSimulationHeader: Boolean = true,
    val workerThreads: Int = 4,
)

/**
 * The reference MIZAN service.
 *
 * It listens on plain HTTP and is expected to sit behind a TLS terminator:
 * the Android client refuses to send a write to anything but an HTTPS URL,
 * and that refusal is a security property, not a convenience.
 *
 * Routes:
 *
 * ```text
 * POST /v1/sessions            issue a short-lived token
 * POST /v1/executions          decide and, when allowed, perform an ERP write
 * GET  /v1/health              liveness and counts
 * GET  /v1/audit?tenant=...    the service-side audit chain of one tenant
 * GET  /v1/erp?tenant=...      read-only view of the in-memory ERP state
 * ```
 */
class MizanService(
    private val config: ServiceConfig = ServiceConfig(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    val erp = InMemoryErp()
    val audit = AuditLedger(clock)
    val executions = ExecutionLedger()
    val directory = UserDirectory(config.users ?: ServiceAuthority.demoUsers())
    val sessions = SessionRegistry(config.sessionTtlMillis, clock)
    val policy = PolicyEvaluator(config.catalog)
    val authority = ServiceAuthority(
        erp = erp,
        ledger = executions,
        audit = audit,
        directory = directory,
        capabilities = config.capabilities,
        policy = policy,
        clock = clock,
    )

    private var server: HttpServer? = null
    private var pool = Executors.newFixedThreadPool(config.workerThreads)

    /**
     * Starts the server and returns the port it is listening on.
     * Port 0 asks the operating system for a free port, which tests use.
     */
    @Synchronized
    fun start(port: Int = config.port, host: String = config.host): Int {
        check(server == null) { "service already started" }
        val http = HttpServer.create(InetSocketAddress(host, port), 0)
        http.createContext(MizanContract.PATH_SESSIONS, this::handleSessions)
        http.createContext(MizanContract.PATH_EXECUTIONS, this::handleExecutions)
        http.createContext(MizanContract.PATH_HEALTH, this::handleHealth)
        http.createContext(MizanContract.PATH_AUDIT, this::handleAudit)
        http.createContext(MizanContract.PATH_ERP, this::handleErp)
        pool = Executors.newFixedThreadPool(config.workerThreads)
        http.executor = pool
        http.start()
        server = http
        return http.address.port
    }

    @Synchronized
    fun stop() {
        server?.stop(0)
        server = null
        pool.shutdownNow()
    }

    val isRunning: Boolean
        get() = server != null

    private fun handleSessions(exchange: HttpExchange) = serve(exchange) {
        if (exchange.requestMethod != "POST") {
            respond(exchange, 405, Json.obj("messageCode" to Json.str("METHOD_NOT_ALLOWED")))
            return@serve
        }
        val body = Json.parseOrNull(exchange.requestBody.readBytes().toString(Charsets.UTF_8))?.asObject()
        val email = body?.text("email")
        val password = body?.text("password")
        if (email.isNullOrBlank() || password.isNullOrBlank()) {
            respond(exchange, 400, Json.obj("messageCode" to Json.str("SIGN_IN_BODY")))
            return@serve
        }
        val user = directory.signIn(email, password)
        if (user == null) {
            // One answer for an unknown account, a wrong password, and a lockout.
            respond(exchange, 401, Json.obj("messageCode" to Json.str("SESSION_DENIED")))
            return@serve
        }
        val issued = sessions.issue(user)
        val token = issued.first
        val session = issued.second
        audit.append(
            tenantId = user.tenantId,
            traceId = "session",
            actorId = user.actorId,
            action = "SESSION_ISSUED",
            stateBefore = "ANONYMOUS",
            stateAfter = "AUTHENTICATED",
            details = "fingerprint=${session.tokenFingerprint}",
        )
        respond(
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

    private fun handleExecutions(exchange: HttpExchange) = serve(exchange) {
        if (exchange.requestMethod != "POST") {
            respond(exchange, 405, Json.obj("messageCode" to Json.str("METHOD_NOT_ALLOWED")))
            return@serve
        }
        val headers = exchange.requestHeaders
        val token = bearer(headers.getFirst(MizanContract.HEADER_AUTHORIZATION))
        val session = sessions.resolve(token)
        if (session == null) {
            respond(
                exchange,
                401,
                Json.obj(
                    "status" to Json.str(MizanContract.Status.REJECTED),
                    "messageCode" to Json.str("SESSION_EXPIRED"),
                ),
            )
            return@serve
        }
        val traceId = headers.getFirst(MizanContract.HEADER_TRACE_ID)
        val body = Json.parseOrNull(exchange.requestBody.readBytes().toString(Charsets.UTF_8))?.asObject()
        val request = body?.let {
            ExecutionRequest.parse(
                body = it,
                fallbackExecutionId = "EXE-" + UUID.randomUUID().toString().take(8).uppercase(),
                traceHeader = traceId,
                idempotencyHeader = headers.getFirst(MizanContract.HEADER_IDEMPOTENCY_KEY),
            )
        }
        if (request == null) {
            respond(
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
            is ExecutionOutcome.Verified -> respond(
                exchange,
                200,
                Json.obj(
                    "status" to Json.str(MizanContract.Status.VERIFIED),
                    "executionId" to Json.str(outcome.executionId),
                    "erpRecordId" to Json.str(outcome.erpRecordId),
                    "erpModel" to Json.str(outcome.erpModel),
                    "verification" to Json.str("READ_BACK"),
                    "summary" to Json.str(outcome.summary),
                ),
            )
            is ExecutionOutcome.Accepted -> respond(
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
            is ExecutionOutcome.Ambiguous -> respond(
                exchange,
                200,
                Json.obj(
                    "status" to Json.str(MizanContract.Status.AMBIGUOUS),
                    "executionId" to Json.str(outcome.executionId),
                    "messageCode" to Json.str("SERVICE_AMBIGUOUS"),
                    "candidates" to Json.str(outcome.candidateRecordIds.joinToString(",")),
                ),
            )
            is ExecutionOutcome.Rejected -> respond(
                exchange,
                outcome.httpStatus,
                Json.obj(
                    "status" to Json.str(MizanContract.Status.REJECTED),
                    "executionId" to Json.str(outcome.executionId),
                    "messageCode" to Json.str(outcome.messageCode),
                ),
            )
            is ExecutionOutcome.Failed -> respond(
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

    private fun handleHealth(exchange: HttpExchange) = serve(exchange) {
        respond(
            exchange,
            200,
            Json.obj(
                "service" to Json.str("mizan-reference"),
                "status" to Json.str("ok"),
                "erp" to Json.str("in-memory-reference"),
                "sessions" to Json.num(sessions.activeCount()),
                "executions" to Json.num(executions.size()),
                "tenants" to Json.num(audit.tenants().size),
                "nowEpochMillis" to Json.num(clock()),
            ),
        )
    }

    private fun handleAudit(exchange: HttpExchange) = serve(exchange) {
        val session = sessions.resolve(bearer(exchange.requestHeaders.getFirst(MizanContract.HEADER_AUTHORIZATION)))
        if (session == null) {
            respond(exchange, 401, Json.obj("messageCode" to Json.str("SESSION_EXPIRED")))
            return@serve
        }
        val tenant = query(exchange, "tenant") ?: session.user.tenantId
        if (tenant != session.user.tenantId) {
            respond(exchange, 403, Json.obj("messageCode" to Json.str("TENANT_MISMATCH")))
            return@serve
        }
        val report = audit.verify(tenant)
        val events = audit.events(tenant).map { event ->
            Json.obj(
                "chainIndex" to Json.num(event.chainIndex),
                "action" to Json.str(event.action),
                "actorId" to Json.str(event.actorId),
                "details" to Json.str(event.details),
                "integrityClass" to Json.str(event.integrityClass.name),
                "timestampMillis" to Json.num(event.timestampMillis),
            )
        }
        respond(
            exchange,
            200,
            Json.obj(
                "tenant" to Json.str(tenant),
                "chainIntact" to Json.bool(report.intact),
                "messageCode" to Json.str(report.messageCode),
                "records" to Json.num(report.records),
                "events" to Json.arr(events),
            ),
        )
    }

    private fun handleErp(exchange: HttpExchange) = serve(exchange) {
        val session = sessions.resolve(bearer(exchange.requestHeaders.getFirst(MizanContract.HEADER_AUTHORIZATION)))
        if (session == null) {
            respond(exchange, 401, Json.obj("messageCode" to Json.str("SESSION_EXPIRED")))
            return@serve
        }
        val tenant = query(exchange, "tenant") ?: session.user.tenantId
        if (tenant != session.user.tenantId) {
            respond(exchange, 403, Json.obj("messageCode" to Json.str("TENANT_MISMATCH")))
            return@serve
        }
        val snapshot = erp.snapshot(tenant)
        respond(
            exchange,
            200,
            Json.obj(
                "tenant" to Json.str(tenant),
                "erp" to Json.str("in-memory-reference"),
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

    private fun bearer(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val trimmed = header.trim()
        if (!trimmed.startsWith("Bearer ", ignoreCase = true)) return null
        return trimmed.substring(7).trim().takeIf { it.isNotEmpty() }
    }

    private fun query(exchange: HttpExchange, name: String): String? {
        val raw = exchange.requestURI.query ?: return null
        return raw.split('&').mapNotNull { part ->
            val pieces = part.split('=', limit = 2)
            if (pieces.size == 2) pieces[0] to pieces[1] else null
        }.firstOrNull { (key, _) -> key == name }?.second
    }

    private fun respond(exchange: HttpExchange, status: Int, body: JsonValue) {
        val bytes = Json.write(body).toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.add("X-Content-Type-Options", "nosniff")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
        exchange.responseBody.flush()
        exchange.close()
    }

    /**
     * Inline so handlers can return early. A handler that throws is answered
     * with a 500 rather than being left hanging.
     */
    private inline fun serve(exchange: HttpExchange, block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            try {
                respond(
                    exchange,
                    500,
                    Json.obj(
                        "status" to Json.str(MizanContract.Status.FAILED),
                        "messageCode" to Json.str("SERVICE_ERROR"),
                    ),
                )
            } catch (_: Throwable) {
                exchange.close()
            }
        }
    }
}
