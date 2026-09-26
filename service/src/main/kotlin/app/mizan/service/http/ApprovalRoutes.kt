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
