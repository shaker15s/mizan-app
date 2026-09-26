package app.mizan.service.http

import app.mizan.domain.approval.ApprovalPolicy
import app.mizan.domain.execution.ExecutionJournal
import app.mizan.domain.model.ActorId
import app.mizan.domain.model.ConnectorCapabilities
import app.mizan.domain.model.ReconciliationCase
import app.mizan.domain.model.ReconciliationStatus
import app.mizan.domain.model.TenantId
import app.mizan.domain.policy.PolicyEvaluator
import app.mizan.domain.policy.VersionedPolicy
import app.mizan.domain.receipt.ReceiptSigner
import app.mizan.domain.receipt.SignedReceipt
import app.mizan.domain.security.DeviceBindingService
import app.mizan.domain.security.DeviceKeyAlgorithm
import app.mizan.domain.security.DevicePublicKey
import app.mizan.domain.tool.ToolCatalog
import app.mizan.service.ServiceConfig
import app.mizan.service.erp.ErpConnector
import app.mizan.service.json.Json
import app.mizan.service.json.JsonValue
import app.mizan.service.json.asObject
import app.mizan.service.json.text
import app.mizan.service.ledger.AuditLedger
import app.mizan.service.protocol.MizanContract
import app.mizan.service.security.ServiceUser
import app.mizan.service.store.ServiceStores
import com.sun.net.httpserver.HttpExchange
import java.security.SecureRandom
import java.time.Instant

/**
 * The routes that are not about performing a governed write.
 *
 * Everything here is either a read a client needs to render honest state (which
 * capabilities, which tools, which policy, which journal, what the ERP holds) or
 * a governance act a person performs (enrol a device, resolve a reconciliation
 * case, verify a receipt).
 *
 * Two rules are enforced on every route:
 *
 * 1. A tenant can only ever see its own rows. The tenant is taken from the
 *    session, never trusted from the query string.
 * 2. Nothing here decides anything about money. There is no route that
 *    approves an execution: an approval is an object with its own identity,
 *    policy version and fingerprint, and it is validated by the authority.
 */
class GovernanceApi(
    private val config: ServiceConfig,
    private val connector: ErpConnector,
    private val policy: PolicyEvaluator,
    private val versionedPolicy: VersionedPolicy,
    private val stores: ServiceStores?,
    private val signer: ReceiptSigner?,
    private val devices: DeviceBindingService?,
    private val directory: app.mizan.service.security.UserDirectory,
    private val audit: AuditLedger,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val random = SecureRandom()

    // ------------------------------------------------------------------- tools

    fun capabilities(exchange: HttpExchange) = Http.serve(exchange) {
        val caps: ConnectorCapabilities = config.capabilities
        Http.respond(
            exchange,
            200,
            Json.obj(
                "connector" to Json.str(connector.id),
                "toolsCatalogVersion" to Json.str(ToolCatalog.VERSION),
                "policyVersionId" to Json.str(versionedPolicy.version.id),
                "policyHash" to Json.str(versionedPolicy.snapshot.rulesHash),
                "erp" to Json.obj(
                    "draftOrders" to Json.bool(caps.supportsDraftOrders),
                    "cancelOrders" to Json.bool(caps.supportsOrderCancel),
                    "invoices" to Json.bool(caps.supportsInvoiceCreation),
                    "payments" to Json.bool(caps.supportsPayment),
                    "verification" to Json.bool(caps.supportsVerification),
                    "batchRead" to Json.bool(caps.supportsBatchRead),
                    "json2" to Json.bool(caps.supportsJson2),
                    "legacyRpc" to Json.bool(caps.supportsLegacyRpc),
                ),
                // What this deployment cannot do, named. A client that shows a
                // payment button against a connector with no payment support is
                // lying to the user, so the list is explicit.
                "unavailable" to Json.arr(unavailableCapabilities(caps).map { Json.str(it) }),
                "durable" to Json.bool(stores != null),
                "signed" to Json.bool(signer != null),
                "deviceProofRequired" to Json.bool(config.requireDeviceProof),
                "rateLimiting" to Json.bool(config.rateLimiting),
            ),
        )
    }

    fun tools(exchange: HttpExchange) = Http.serve(exchange) {
        val caps = config.capabilities
        Http.respond(
            exchange,
            200,
            Json.obj(
                "catalogVersion" to Json.str(ToolCatalog.VERSION),
                "definitions" to Json.arr(
                    ToolCatalog.definitions.map { definition ->
                        Json.obj(
                            "tool" to Json.str(definition.tool.wire),
                            "version" to Json.str(definition.tool.version),
                            "schemaVersion" to Json.str(definition.schemaVersion),
                            "riskClass" to Json.str(definition.riskClass.name),
                            "descriptionKey" to Json.str(definition.descriptionKey),
                            "mutating" to Json.bool(!definition.tool.readOnly),
                            "destructive" to Json.bool(definition.tool.destructive),
                            "requiresApproval" to Json.bool(definition.requiresApproval),
                            "requiresFreshProof" to Json.bool(definition.requiresFreshProof),
                            "requiredPermission" to Json.str(definition.requiredPermission),
                            "verification" to Json.str(definition.verification.name),
                            "idempotency" to Json.str(definition.idempotency.name),
                            "capabilities" to Json.arr(definition.requiresCapabilities.map { Json.str(it.wire) }),
                            "sideEffects" to Json.arr(definition.sideEffects.map { Json.str(it.code) }),
                            "arguments" to Json.arr(
                                definition.arguments.map { spec ->
                                    Json.obj(
                                        "name" to Json.str(spec.name),
                                        "type" to Json.str(spec.type.name),
                                        "required" to Json.bool(spec.required),
                                        "maxLength" to Json.num(spec.maxLength),
                                        "minMinor" to Json.num(spec.minMinor),
                                        "maxMinor" to Json.num(spec.maxMinor),
                                        "minQuantity" to Json.num(spec.minQuantity),
                                        "maxQuantity" to Json.num(spec.maxQuantity),
                                        "note" to Json.str(spec.note),
                                    )
                                },
                            ),
                        )
                    },
                ),
                "supported" to Json.arr(
                    ToolCatalog.definitions
                        .filter { definition -> caps.supports(definition.tool) }
                        .map { Json.str(it.tool.wire) },
                ),
            ),
        )
    }

    fun policy(exchange: HttpExchange) = Http.serve(exchange) {
        Http.respond(
            exchange,
            200,
            Json.obj(
                "version" to Json.num(versionedPolicy.version.version),
                "versionId" to Json.str(versionedPolicy.version.id),
                "effectiveFrom" to Json.str(versionedPolicy.version.effectiveFrom.toString()),
                "label" to Json.str(versionedPolicy.version.label),
                "rulesHash" to Json.str(versionedPolicy.snapshot.rulesHash),
                "rules" to Json.arr(
                    versionedPolicy.rules.map { rule ->
                        Json.obj(
                            "tool" to Json.str(rule.toolWire),
                            "currency" to Json.str(rule.currency),
                            "l1MaxMinor" to Json.num(rule.l1MaxMinor),
                            "l2MaxMinor" to Json.num(rule.l2MaxMinor),
                            "l3MaxMinor" to Json.num(rule.l3MaxMinor),
                            "actorLimitMinor" to Json.num(rule.actorLimitMinor),
                            "requiresSeparationOfDuties" to Json.bool(rule.requiresSeparationOfDuties),
                        )
                    },
                ),
                "ladders" to Json.arr(
                    versionedPolicy.ladders.entries.map { (currency, ladder) ->
                        Json.obj(
                            "currency" to Json.str(currency),
                            "l1Minor" to Json.num(ladder.l1MaxMinor),
                            "l2Minor" to Json.num(ladder.l2MaxMinor),
                            "l3Minor" to Json.num(ladder.l3MaxMinor),
                        )
                    },
                ),
            ),
        )
    }

    // ------------------------------------------------------------ reconciliations

    fun reconciliation(exchange: HttpExchange) = Http.serve(exchange) {
        val session = sessionOf(exchange) ?: return@serve
        val store = stores?.reconciliations
        if (store == null) {
            Http.respond(exchange, 503, Json.obj("messageCode" to Json.str("STORE_UNAVAILABLE")))
            return@serve
        }
        val segments = Http.segments(exchange, MizanContract.PATH_RECONCILIATION)
        if (segments.size >= 2 && segments[1] == "resolve") {
            resolveCase(exchange, session, segments[0])
            return@serve
        }
        if (exchange.requestMethod != "GET") {
            Http.respond(exchange, 405, Json.obj("messageCode" to Json.str("METHOD_NOT_ALLOWED")))
            return@serve
        }
        val requested = Http.query(exchange, "tenant") ?: session.tenantId
        if (requested != session.tenantId) {
            Http.respond(exchange, 403, Json.obj("messageCode" to Json.str("TENANT_MISMATCH")))
            return@serve
        }
        val cases = store.forTenant(requested)
        Http.respond(
            exchange,
            200,
            Json.obj(
                "tenant" to Json.str(requested),
                "open" to Json.num(cases.count { it.open }),
                "oldestOpenAgeMillis" to Json.num(
                    cases.filter { it.open }.minOfOrNull { it.ageMillis(clock()) } ?: 0L,
                ),
                "cases" to Json.arr(cases.map { caseJson(it) }),
            ),
        )
    }

    private fun resolveCase(exchange: HttpExchange, session: ServiceUser, caseId: String) {
        val store = stores?.reconciliations
            ?: return Http.respond(exchange, 503, Json.obj("messageCode" to Json.str("STORE_UNAVAILABLE")))
        if (exchange.requestMethod != "POST") {
            Http.respond(exchange, 405, Json.obj("messageCode" to Json.str("METHOD_NOT_ALLOWED")))
            return
        }
        val body = Http.readJsonObject(exchange)
        val record = store.get(caseId)
            ?: return Http.respond(exchange, 404, Json.obj("messageCode" to Json.str("RECONCILIATION_UNKNOWN")))
        if (record.tenantId.value != session.tenantId) {
            return Http.respond(exchange, 403, Json.obj("messageCode" to Json.str("TENANT_MISMATCH")))
        }
        if (!record.open) {
            return Http.respond(exchange, 409, Json.obj("messageCode" to Json.str("RECONCILIATION_ALREADY_RESOLVED")))
        }
        val resolution = body?.text("resolution")?.uppercase()
            ?: return Http.respond(exchange, 422, Json.obj("messageCode" to Json.str("RECONCILIATION_RESOLUTION_REQUIRED")))
        val recordId = body.text("erpRecordId")
        val note = body.text("note") ?: ""
        val resolved = when (resolution) {
            "LINKED", "CONFIRMED" -> {
                if (recordId.isNullOrBlank()) {
                    return Http.respond(
                        exchange,
                        422,
                        Json.obj("messageCode" to Json.str("RECONCILIATION_RECORD_REQUIRED")),
                    )
                }
                record.resolve(
                    status = ReconciliationStatus.LINKED,
                    actorId = session.actorId,
                    atMillis = clock(),
                    recordId = recordId,
                    labelKey = "reconciliation_linked",
                    note = note,
                )
            }
            "NOT_PERFORMED", "CANCELLED" -> record.resolve(
                status = ReconciliationStatus.CLOSED_WITHOUT_LINK,
                actorId = session.actorId,
                atMillis = clock(),
                labelKey = "reconciliation_not_performed",
                note = note,
            )
            else -> return Http.respond(
                exchange,
                422,
                Json.obj("messageCode" to Json.str("RECONCILIATION_RESOLUTION_UNKNOWN")),
            )
        }
        store.upsert(resolved)
        audit.append(
            tenantId = session.tenantId,
            traceId = record.traceId.value,
            actorId = session.actorId,
            action = "RECONCILIATION_${resolved.status.name}",
            stateBefore = record.status.name,
            stateAfter = resolved.status.name,
            details = "case=${record.id} record=${resolved.resolvedRecordId ?: "-"} note=$note",
        )
        Http.respond(exchange, 200, caseJson(resolved))
    }

    fun caseJson(record: ReconciliationCase): JsonValue = Json.obj(
        "id" to Json.str(record.id),
        "executionId" to Json.str(record.executionId.value),
        "tenantId" to Json.str(record.tenantId.value),
        "tool" to Json.str(record.tool.wire),
        "intent" to Json.str(record.intent),
        "status" to Json.str(record.status.name),
        "open" to Json.bool(record.open),
        "reasonCode" to Json.str(record.reasonCode),
        "candidateRecordIds" to Json.arr(record.candidateRecordIds.map { Json.str(it) }),
        "resolvedRecordId" to Json.str(record.resolvedRecordId),
        "resolvedByActorId" to Json.str(record.resolvedByActorId),
        "resolvedAtMillis" to Json.num(record.resolvedAtMillis),
        "resolutionLabelKey" to Json.str(record.resolutionLabelKey),
        "openedAtMillis" to Json.num(record.openedAt.toEpochMilli()),
        "ageMillis" to Json.num(record.ageMillis(clock())),
        "traceId" to Json.str(record.traceId.value),
    )

    // ---------------------------------------------------------------- receipts

    fun receipts(exchange: HttpExchange) = Http.serve(exchange) {
        val session = sessionOf(exchange)
        val store = stores?.receipts
        val id = Http.segments(exchange, MizanContract.PATH_RECEIPTS).firstOrNull()
        if (session == null) {
            // sessionOf already answered.
        } else if (store == null) {
            Http.respond(exchange, 503, Json.obj("messageCode" to Json.str("STORE_UNAVAILABLE")))
        } else if (id == null) {
            Http.respond(exchange, 400, Json.obj("messageCode" to Json.str("RECEIPT_ID_REQUIRED")))
        } else {
            val receipt = store.get(id)
            if (receipt == null) {
                Http.respond(exchange, 404, Json.obj("messageCode" to Json.str("RECEIPT_UNKNOWN")))
            } else if (receipt.claims.tenantId != session.tenantId) {
                Http.respond(exchange, 403, Json.obj("messageCode" to Json.str("TENANT_MISMATCH")))
            } else {
                val verification = signer?.verify(receipt)
                Http.respond(
                    exchange,
                    200,
                    Json.obj(
                        "receiptId" to Json.str(receipt.claims.receiptId),
                        "executionId" to Json.str(receipt.claims.executionId),
                        "tool" to Json.str(receipt.claims.tool),
                        "toolVersion" to Json.str(receipt.claims.toolVersion),
                        "catalogVersion" to Json.str(receipt.claims.catalogVersion),
                        "policyVersionId" to Json.str(receipt.claims.policyVersionId),
                        "policyHash" to Json.str(receipt.claims.policyHash),
                        "actorId" to Json.str(receipt.claims.actorId),
                        "approverIds" to Json.arr(receipt.claims.approverIds.map { Json.str(it) }),
                        "proposalFingerprint" to Json.str(receipt.claims.proposalFingerprint),
                        "inputHash" to Json.str(receipt.claims.inputHash),
                        "erpModel" to Json.str(receipt.claims.erpModel),
                        "erpRecordId" to Json.str(receipt.claims.erpRecordId),
                        "verifiedFields" to Json.arr(receipt.claims.verifiedFields.map { Json.str(it) }),
                        "issuedAtMillis" to Json.num(receipt.claims.issuedAtMillis),
                        "signature" to Json.str(receipt.signature),
                        "algorithm" to Json.str(receipt.algorithm),
                        "keyId" to Json.str(receipt.keyId),
                        "verdict" to Json.str(verification?.verdict?.name),
                        "messageCode" to Json.str(verification?.reasonCode ?: "RECEIPT_UNSIGNED"),
                        "verified" to Json.bool(verification?.valid == true),
                    ),
                )
            }
        }
    }

    // ----------------------------------------------------------------- devices

    fun devices(exchange: HttpExchange, actorId: String, tenantId: String) {
        val binding = devices
            ?: return Http.respond(exchange, 503, Json.obj("messageCode" to Json.str("DEVICE_STORE_UNAVAILABLE")))
        val segments = Http.segments(exchange, MizanContract.PATH_DEVICES)
        when {
            exchange.requestMethod == "POST" && segments.firstOrNull() == "challenge" -> issueChallenge(
                exchange = exchange,
                actorId = actorId,
                tenantId = tenantId,
            )
            exchange.requestMethod == "POST" -> enroll(exchange, actorId, tenantId)
            exchange.requestMethod == "GET" -> {
                val enrolled = binding.byTenant(TenantId(tenantId)).map { deviceJson(it) }
                Http.respond(
                    exchange,
                    200,
                    Json.obj("tenant" to Json.str(tenantId), "devices" to Json.arr(enrolled)),
                )
            }
            else -> Http.respond(exchange, 405, Json.obj("messageCode" to Json.str("METHOD_NOT_ALLOWED")))
        }
    }

    private fun enroll(exchange: HttpExchange, actorId: String, tenantId: String) {
        val binding = devices ?: return
        val body = Http.readJsonObject(exchange)
        val deviceId = body?.text("deviceId")
            ?: return Http.respond(exchange, 422, Json.obj("messageCode" to Json.str("DEVICE_ID_REQUIRED")))
        val publicKey = body.text("publicKey")
            ?: return Http.respond(exchange, 422, Json.obj("messageCode" to Json.str("DEVICE_KEY_REQUIRED")))
        val algorithm = body.text("algorithm")?.let { DeviceKeyAlgorithm.fromWire(it.uppercase()) }
            ?: DeviceKeyAlgorithm.ED25519
        val label = body.text("label") ?: "device"
        val enrolled = try {
            binding.enroll(
                deviceId = deviceId,
                tenantId = TenantId(tenantId),
                actorId = ActorId(actorId),
                algorithm = algorithm,
                publicKeyBase64 = publicKey,
                label = label,
            )
        } catch (_: IllegalArgumentException) {
            return Http.respond(exchange, 422, Json.obj("messageCode" to Json.str("DEVICE_KEY_INVALID")))
        }
        audit.append(
            tenantId = tenantId,
            traceId = "device",
            actorId = actorId,
            action = "DEVICE_ENROLLED",
            stateBefore = "NONE",
            stateAfter = "ENROLLED",
            details = "device=$deviceId algorithm=${algorithm.name}",
        )
        Http.respond(exchange, 201, deviceJson(enrolled))
    }

    private fun issueChallenge(exchange: HttpExchange, actorId: String, tenantId: String) {
        val binding = devices ?: return
        val body = Http.readJsonObject(exchange)
        val deviceId = body?.text("deviceId")
            ?: return Http.respond(exchange, 422, Json.obj("messageCode" to Json.str("DEVICE_ID_REQUIRED")))
        val executionId = body.text("executionId")
            ?: return Http.respond(exchange, 422, Json.obj("messageCode" to Json.str("EXECUTION_ID_REQUIRED")))
        val fingerprint = body.text("proposalFingerprint")
            ?: return Http.respond(exchange, 422, Json.obj("messageCode" to Json.str("FINGERPRINT_REQUIRED")))
        val challengeId = "CHG-" + randomToken(8)
        val nonce = randomToken(32)
        val challenge = binding.issueChallenge(
            challengeId = challengeId,
            nonce = nonce,
            deviceId = deviceId,
            executionId = app.mizan.domain.model.ExecutionId(executionId),
            tenantId = TenantId(tenantId),
            actorId = ActorId(actorId),
            proposalFingerprint = fingerprint,
        ) ?: return Http.respond(exchange, 422, Json.obj("messageCode" to Json.str("DEVICE_NOT_ENROLLED")))
        Http.respond(
            exchange,
            201,
            Json.obj(
                "challengeId" to Json.str(challenge.challengeId),
                "nonce" to Json.str(challenge.nonce),
                "deviceId" to Json.str(challenge.deviceId),
                "executionId" to Json.str(challenge.executionId.value),
                "proposalFingerprint" to Json.str(challenge.proposalFingerprint),
                "issuedAtMillis" to Json.num(challenge.issuedAtMillis),
                "expiresAtMillis" to Json.num(challenge.expiresAtMillis),
                // The exact bytes the device signs, so the two sides cannot
                // disagree about what was approved.
                "messageToSign" to Json.str(String(binding.message(challenge), Charsets.UTF_8)),
            ),
        )
    }

    private fun deviceJson(device: DevicePublicKey): JsonValue = Json.obj(
        "deviceId" to Json.str(device.deviceId),
        "actorId" to Json.str(device.actorId.value),
        "algorithm" to Json.str(device.algorithm.name),
        "label" to Json.str(device.label),
        "enrolledAtMillis" to Json.num(device.enrolledAtMillis),
        "lastSeenAtMillis" to Json.num(device.lastSeenAtMillis),
        "revokedAtMillis" to Json.num(device.revokedAtMillis),
        "revoked" to Json.bool(device.revoked),
        // Never the key itself beyond the enrolled material: a public key is
        // public, but the response should not become a key directory.
        "publicKeyFingerprint" to Json.str(app.mizan.domain.model.Digests.sha256(device.publicKeyBase64).take(16)),
    )

    // ----------------------------------------------------------------- journal

    fun journalJson(journal: ExecutionJournal): JsonValue = Json.obj(
        "executionId" to Json.str(journal.executionId.value),
        "tenantId" to Json.str(journal.tenantId.value),
        "actorId" to Json.str(journal.actorId.value),
        "proposalId" to Json.str(journal.proposalId?.value),
        "proposalFingerprint" to Json.str(journal.proposalFingerprint),
        "tool" to Json.str(journal.tool.wire),
        "toolVersion" to Json.str(journal.toolVersion),
        "schemaVersion" to Json.str(journal.schemaVersion),
        "catalogVersion" to Json.str(journal.catalogVersion),
        "canonicalInputHash" to Json.str(journal.canonicalInputHash),
        "idempotencyKey" to Json.str(journal.idempotencyKey.value),
        "policyVersionId" to Json.str(journal.policyVersionId),
        "policyHash" to Json.str(journal.policyHash),
        "approvalId" to Json.str(journal.approvalId),
        "approvalFingerprint" to Json.str(journal.approvalFingerprint),
        "proofReference" to Json.str(journal.proofReference),
        "stage" to Json.str(journal.stage.name),
        "terminal" to Json.bool(journal.stage.terminal),
        "riskTier" to Json.str(journal.riskTier.name),
        "approvalLevel" to Json.str(journal.approvalLevel.name),
        "dispatch" to Json.str(journal.dispatch.name),
        "dispatchStartedAtMillis" to Json.num(journal.dispatchStartedAt?.toEpochMilli()),
        "dispatchFinishedAtMillis" to Json.num(journal.dispatchFinishedAt?.toEpochMilli()),
        "responseReceivedAtMillis" to Json.num(journal.responseReceivedAt?.toEpochMilli()),
        "verificationStartedAtMillis" to Json.num(journal.verificationStartedAt?.toEpochMilli()),
        "verificationFinishedAtMillis" to Json.num(journal.verificationFinishedAt?.toEpochMilli()),
        "erpModel" to Json.str(journal.erpModel),
        "erpRecordId" to Json.str(journal.erpRecordId),
        "candidateIds" to Json.arr(journal.candidateIds.map { Json.str(it) }),
        "errorCode" to Json.str(journal.errorCode),
        "traceId" to Json.str(journal.traceId),
        "revision" to Json.num(journal.revision),
        "createdAtMillis" to Json.num(journal.createdAt.toEpochMilli()),
        "updatedAtMillis" to Json.num(journal.updatedAt.toEpochMilli()),
    )

    // --------------------------------------------------------------- utilities

    /** Resolves the caller from the durable session store, without trusting a header alone. */
    private fun sessionOf(exchange: HttpExchange): ServiceUser? {
        val token = Http.bearer(exchange.requestHeaders.getFirst(MizanContract.HEADER_AUTHORIZATION))
        if (token == null) {
            Http.respond(exchange, 401, Json.obj("messageCode" to Json.str("SESSION_EXPIRED")))
            return null
        }
        val fingerprint = app.mizan.domain.model.Digests.sha256(token).take(16)
        val record = stores?.sessions?.find(fingerprint)
        if (record == null || !record.active(clock())) {
            Http.respond(exchange, 401, Json.obj("messageCode" to Json.str("SESSION_EXPIRED")))
            return null
        }
        val user = directory.find(record.tenantId, record.actorId)
        if (user == null) {
            Http.respond(exchange, 401, Json.obj("messageCode" to Json.str("SESSION_DENIED")))
            return null
        }
        return user
    }

    private fun randomToken(bytes: Int): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    private fun unavailableCapabilities(caps: ConnectorCapabilities): List<String> = buildList {
        if (!caps.supportsDraftOrders) add("draftOrders")
        if (!caps.supportsOrderCancel) add("cancelOrders")
        if (!caps.supportsInvoiceCreation) add("invoices")
        if (!caps.supportsPayment) add("payments")
        if (!caps.supportsVerification) add("verification")
        if (!caps.supportsBatchRead) add("batchRead")
        if (!caps.supportsJson2) add("json2")
        if (!caps.supportsLegacyRpc) add("legacyRpc")
    }

    /** Kept so a caller can quote the instant a response was produced. */
    fun now(): Instant = Instant.ofEpochMilli(clock())

    /** Not used on any route; exists so the approval policy type stays referenced. */
    fun approvalPolicy(): ApprovalPolicy = ApprovalPolicy()

    /** Reported by `/v1/health` so an operator can see the tenant count. */
    fun tenants(): List<String> = audit.tenants()

    /** A receipt store lookup that does not leak across tenants. */
    fun receiptFor(tenantId: String, receiptId: String): SignedReceipt? =
        stores?.receipts?.get(receiptId)?.takeIf { it.claims.tenantId == tenantId }
}
