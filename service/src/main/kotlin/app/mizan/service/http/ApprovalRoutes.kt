package app.mizan.service.http

import app.mizan.domain.approval.ApprovalState
import app.mizan.service.approvals.ApprovalDesk
import app.mizan.service.json.Json
import app.mizan.service.json.asObject
import app.mizan.service.json.field
import app.mizan.service.json.text
import app.mizan.service.protocol.ExecutionRequest
import app.mizan.service.protocol.MizanContract
import app.mizan.service.security.ServiceSession
import app.mizan.service.store.ServiceStores
import com.sun.net.httpserver.HttpExchange

/**
 * The approval routes.
 *
 * They live in their own file for the same reason the desk does: approving is
 * a governed act with its own rules, and those rules are easier to read when
 * they are not in the middle of the file that also serves the ERP view and the
 * health endpoint.
 */
class ApprovalRoutes(
    private val approvals: ApprovalDesk,
    private val governance: GovernanceApi,
    private val stores: ServiceStores?,
    private val authenticate: (HttpExchange) -> ServiceSession?,
    /** Enrolments and challenges. Null on a deployment with no durable store. */
    private val deviceBinding: app.mizan.domain.security.DeviceBindingService? = null,
) {

/**
 * The approval surface.
 *
 * `POST /v1/approvals` opens an approval for a request the service itself
 * judges; `POST /v1/approvals/{id}/grant` and `/refuse` are where a person
 * answers; the reads are what the app's approval screen renders. Nothing
 * here performs a governed write: approving is not executing.
 */
fun handle(exchange: HttpExchange) = Http.serve(exchange) {
    val session = authenticate(exchange) ?: return@serve
    val user = session.user
    val path = exchange.requestURI.path.removePrefix(MizanContract.PATH_APPROVALS).trim('/')
    val method = exchange.requestMethod

    if (path.isEmpty() && method == "GET") {
        val state = Http.query(exchange, "state")?.let { raw ->
            runCatching { app.mizan.domain.approval.ApprovalState.valueOf(raw.uppercase()) }.getOrNull()
        }
        val listed = approvals.list(user, state)
        Http.respond(
            exchange,
            200,
            Json.obj(
                "durable" to Json.bool(stores != null),
                "approvals" to Json.arr(listed.map { governance.approvalJson(it) }),
            ),
        )
        return@serve
    }

    if (path.isEmpty() && method == "POST") {
        val body = readJsonBody(exchange) ?: return@serve
        val request = ExecutionRequest.parse(
            body = body,
            fallbackExecutionId = "APR-PREVIEW",
            traceHeader = exchange.requestHeaders.getFirst(MizanContract.HEADER_TRACE_ID),
            idempotencyHeader = exchange.requestHeaders.getFirst(MizanContract.HEADER_IDEMPOTENCY_KEY),
        )
        if (request == null) {
            Http.respond(exchange, 422, Json.obj("messageCode" to Json.str("REQUEST_UNREADABLE")))
            return@serve
        }
        val proposalId = body.text("proposalId") ?: "PROP-" + request.executionId.take(12)
        val revision = (body.field("proposalRevision") as? app.mizan.service.json.JsonValue.Num)
            ?.raw?.toIntOrNull() ?: 1
        when (val result = approvals.create(request, user, proposalId, revision)) {
            is ApprovalDesk.Result.Created -> Http.respond(
                exchange,
                201,
                governance.approvalJson(result.approval),
            )
            is ApprovalDesk.Result.Decided -> Http.respond(
                exchange,
                200,
                governance.approvalJson(result.approval),
            )
            is ApprovalDesk.Result.Refused -> Http.respond(
                exchange,
                result.httpStatus,
                Json.obj("messageCode" to Json.str(result.code)),
            )
        }
        return@serve
    }

    if (path.isNotEmpty() && method == "GET") {
        val approval = approvals.get(path, user)
        if (approval == null) {
            Http.respond(exchange, 404, Json.obj("messageCode" to Json.str("APPROVAL_UNKNOWN")))
            return@serve
        }
        Http.respond(exchange, 200, governance.approvalJson(approval))
        return@serve
    }

    /**
     * A challenge to answer *this* approval with.
     *
     * The device challenge used to be reachable only from `/v1/devices`, which
     * meant a client had to remember which execution an approval was about
     * before it could ask for one. A queue of approvals is exactly the screen
     * that cannot do that, so the binding is the approval's own: the challenge
     * is issued against the approval's fingerprint and the execution it
     * carries, for the device the caller names.
     */
    if (path.endsWith("/challenge") && method == "POST") {
        val id = path.removeSuffix("/challenge").trim('/')
        val approval = approvals.get(id, user)
        if (approval == null) {
            Http.respond(exchange, 404, Json.obj("messageCode" to Json.str("APPROVAL_UNKNOWN")))
            return@serve
        }
        if (approval.state != ApprovalState.PENDING) {
            Http.respond(exchange, 409, Json.obj("messageCode" to Json.str("EXECUTION_ALREADY_RESOLVED")))
            return@serve
        }
        if (stores == null || deviceBinding == null) {
            Http.respond(exchange, 503, Json.obj("messageCode" to Json.str("DEVICE_STORE_UNAVAILABLE")))
            return@serve
        }
        val body = readJsonBody(exchange) ?: return@serve
        val deviceId = body.text("deviceId")
        if (deviceId.isNullOrBlank()) {
            Http.respond(exchange, 422, Json.obj("messageCode" to Json.str("DEVICE_ID_REQUIRED")))
            return@serve
        }
        val issued = deviceBinding.issueChallenge(
            challengeId = "CHG-" + java.util.UUID.randomUUID().toString().replace("-", "").take(12),
            nonce = java.util.UUID.randomUUID().toString().replace("-", ""),
            deviceId = deviceId,
            executionId = app.mizan.domain.model.ExecutionId(approval.executionId),
            tenantId = approval.tenantId,
            actorId = app.mizan.domain.model.ActorId(user.actorId),
            proposalFingerprint = approval.proposalFingerprint,
        )
        if (issued == null) {
            // The device is unknown, revoked, or belongs to someone else.
            // None of those is a signature problem, so they are not reported
            // as one: the caller has to fix the enrolment first.
            Http.respond(exchange, 422, Json.obj("messageCode" to Json.str("DEVICE_NOT_ENROLLED")))
            return@serve
        }
        Http.respond(
            exchange,
            201,
            Json.obj(
                "challengeId" to Json.str(issued.challengeId),
                "deviceId" to Json.str(issued.deviceId),
                "executionId" to Json.str(issued.executionId.value),
                "proposalFingerprint" to Json.str(issued.proposalFingerprint),
                "expiresAtMillis" to Json.num(issued.expiresAtMillis),
                "messageToSign" to Json.str(String(deviceBinding.message(issued), Charsets.UTF_8)),
            ),
        )
        return@serve
    }

    if (path.endsWith("/grant") || path.endsWith("/refuse")) {
        if (method != "POST") {
            Http.respond(exchange, 405, Json.obj("messageCode" to Json.str("METHOD_NOT_ALLOWED")))
            return@serve
        }
        val id = path.removeSuffix("/grant").removeSuffix("/refuse").trim('/')
        val body = readJsonBody(exchange) ?: return@serve
        val granted = path.endsWith("/grant")
        val reasonCode = body.text("reasonCode")
        val challengeId = body.text("deviceChallengeId")
        val signature = body.text("deviceSignature")
        when (
            val result = approvals.decide(
                approvalId = id,
                user = user,
                granted = granted,
                reasonCode = reasonCode,
                deviceChallengeId = challengeId,
                deviceSignature = signature,
            )
        ) {
            is ApprovalDesk.Result.Decided -> {
                val json = governance.approvalJson(result.approval)
                Http.respond(
                    exchange,
                    200,
                    if (result.complete) {
                        json
                    } else {
                        Json.obj(
                            "approval" to json,
                            "messageCode" to Json.str("APPROVAL_NEEDS_SECOND_APPROVER"),
                        )
                    },
                )
            }
            is ApprovalDesk.Result.Created -> Http.respond(exchange, 200, governance.approvalJson(result.approval))
            is ApprovalDesk.Result.Refused -> Http.respond(
                exchange,
                result.httpStatus,
                Json.obj("messageCode" to Json.str(result.code)),
            )
        }
        return@serve
    }

    Http.respond(exchange, 404, Json.obj("messageCode" to Json.str("APPROVAL_ROUTE_UNKNOWN")))
}

/**
 * The body of a request that carries one, read exactly the way executions
 * are read: a body that cannot be understood is refused, never guessed at.
 */
private fun readJsonBody(exchange: HttpExchange): app.mizan.service.json.JsonValue.Obj? {
    val read = Http.readBody(exchange)
    if (read.tooLarge) {
        Http.respond(exchange, 413, Json.obj("messageCode" to Json.str("REQUEST_TOO_LARGE")))
        return null
    }
    if (read.value == null) {
        Http.respond(exchange, 422, Json.obj("messageCode" to Json.str("REQUEST_UNREADABLE")))
        return null
    }
    return read.value
}
}
