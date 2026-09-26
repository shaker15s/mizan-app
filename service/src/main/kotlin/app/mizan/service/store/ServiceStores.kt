package app.mizan.service.store

import app.mizan.domain.error.DispatchState
import app.mizan.domain.execution.ExecutionJournal
import app.mizan.domain.execution.JournalStage
import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.ActorId
import app.mizan.domain.model.ExecutionId
import app.mizan.domain.model.IdempotencyKey
import app.mizan.domain.model.ProposalId
import app.mizan.domain.model.ReconciliationCase
import app.mizan.domain.model.ReconciliationStatus
import app.mizan.domain.model.RiskTier
import app.mizan.domain.model.TenantId
import app.mizan.domain.model.ToolName
import app.mizan.domain.receipt.SignedReceipt
import app.mizan.domain.security.ApprovalChallenge
import app.mizan.domain.security.DeviceKeyAlgorithm
import app.mizan.domain.security.DevicePublicKey
import app.mizan.domain.security.DeviceRepository
import app.mizan.service.json.Json
import app.mizan.service.json.JsonValue
import app.mizan.service.json.asObject
import app.mizan.service.json.field
import app.mizan.service.json.text
import java.nio.file.Path
import java.time.Instant
import java.util.Base64

/**
 * Every durable thing the service remembers, on top of [DurableLog].
 *
 * Each store writes a JSON record per change and rebuilds its index by
 * replaying the log at start-up. The service therefore survives a restart the
 * way it must: a session stays valid, an idempotency key still replays the
 * original answer, an execution journal still knows which stage it reached,
 * and a receipt is still there to be verified.
 *
 * Nothing here decides anything. These are the boxes the authority writes its
 * decisions into.
 */
class ServiceStores(
    private val directory: Path,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AutoCloseable {

    private val journalLog = DurableLog(directory.resolve("execution-journal.log"))
    private val idempotencyLog = DurableLog(directory.resolve("idempotency.log"))
    private val sessionLog = DurableLog(directory.resolve("sessions.log"))
    private val receiptLog = DurableLog(directory.resolve("receipts.log"))
    private val deviceLog = DurableLog(directory.resolve("devices.log"))
    private val challengeLog = DurableLog(directory.resolve("challenges.log"))
    private val reconciliationLog = DurableLog(directory.resolve("reconciliation.log"))
    private val auditLog = DurableLog(directory.resolve("audit.log"))
    private val approvalLog = DurableLog(directory.resolve("approvals.log"))
    private val outboxLog = DurableLog(directory.resolve("outbox.log"))

    val journals = JournalStore(journalLog)
    val idempotency = IdempotencyStore(idempotencyLog)
    val sessions = SessionStore(sessionLog)
    val receipts = ReceiptStore(receiptLog)
    val devices = DurableDeviceRepository(deviceLog)
    val challenges = DurableChallengeRepository(challengeLog)
    val reconciliations = ReconciliationStore(reconciliationLog)
    val audit = AuditStore(auditLog)
    val approvals = ApprovalStore(approvalLog)
    val outbox = app.mizan.service.outbox.Outbox(outboxLog)

    override fun close() {
        listOf(
            journalLog,
            idempotencyLog,
            sessionLog,
            receiptLog,
            deviceLog,
            challengeLog,
            reconciliationLog,
            auditLog,
            approvalLog,
            outboxLog,
        ).forEach { it.close() }
    }
}

private fun obj(vararg fields: Pair<String, JsonValue?>): JsonValue.Obj = Json.obj(*fields)

private fun JsonValue.Obj.long(name: String): Long? = field(name)?.let { value ->
    when (value) {
        is JsonValue.Num -> value.raw.toLongOrNull()
        is JsonValue.Str -> value.value.toLongOrNull()
        else -> null
    }
}

private fun JsonValue.Obj.flag(name: String): Boolean = field(name)?.let { value ->
    (value as? JsonValue.Bool)?.value ?: (value as? JsonValue.Str)?.value?.toBooleanStrictOrNull()
} ?: false

private fun JsonValue.Obj.strings(name: String): List<String> =
    (field(name) as? JsonValue.Arr)?.items?.mapNotNull { it.text() } ?: emptyList()

private fun JsonValue.text(): String? = (this as? JsonValue.Str)?.value

private fun instantOf(millis: Long?): Instant? = millis?.let { Instant.ofEpochMilli(it) }

/**
 * The execution journal. Keyed by execution id, indexed by idempotency key so
 * a repeat can find the original without scanning every tenant's history.
 */
class JournalStore(private val log: DurableLog) {

    private val byExecution = LinkedHashMap<String, ExecutionJournal>()
    private val byKey = LinkedHashMap<String, String>()
    private val byTenant = LinkedHashMap<String, MutableList<String>>()
    /** How many records this store has written. Not the journal's revision. */
    private var appends = 0L

    init {
        for (record in log.records()) {
            val parsed = Json.parseOrNull(record)?.asObject() ?: continue
            val journal = parse(parsed) ?: continue
            index(journal)
        }
    }

    /**
     * Persists the entry exactly as the authority produced it.
     *
     * The revision belongs to the journal, not to this store: an approval
     * binds to the revision it was granted for, so a store that renumbered
     * entries as it wrote them would break the very binding it exists to keep.
     */
    @Synchronized
    fun save(journal: ExecutionJournal): ExecutionJournal {
        log.append(write(journal))
        index(journal)
        appends++
        return journal
    }

    /** How many records have been appended since this store was opened. */
    @Synchronized
    fun appends(): Long = appends

    @Synchronized
    fun get(executionId: String): ExecutionJournal? = byExecution[executionId]

    @Synchronized
    fun byIdempotency(tenantId: String, key: String): ExecutionJournal? =
        byKey["$tenantId::$key"]?.let { byExecution[it] }

    @Synchronized
    fun forTenant(tenantId: String, limit: Int = 100): List<ExecutionJournal> {
        val ids = byTenant[tenantId] ?: return emptyList()
        return ids.asReversed().take(limit).mapNotNull { byExecution[it] }
    }

    @Synchronized
    fun requiringAttention(tenantId: String): List<ExecutionJournal> = forTenant(tenantId).filter {
        it.stage == JournalStage.AMBIGUOUS || it.stage == JournalStage.RECONCILIATION_REQUIRED ||
            it.stage == JournalStage.WAITING_APPROVAL
    }

    @Synchronized
    fun count(): Int = byExecution.size

    @Synchronized
    fun compacted(): Int {
        val state = byExecution.values.map { write(it) }
        log.compact(state)
        return state.size
    }

    private fun index(journal: ExecutionJournal) {
        val id = journal.executionId.value
        byExecution[id] = journal
        byKey["${journal.tenantId.value}::${journal.idempotencyKey.value}"] = id
        val list = byTenant.getOrPut(journal.tenantId.value) { ArrayList() }
        if (!list.contains(id)) list.add(id)
    }

    private fun write(journal: ExecutionJournal): String = Json.write(
        obj(
            "executionId" to Json.str(journal.executionId.value),
            "tenantId" to Json.str(journal.tenantId.value),
            "actorId" to Json.str(journal.actorId.value),
            "proposalId" to Json.str(journal.proposalId?.value),
            "proposalFingerprint" to Json.str(journal.proposalFingerprint),
            "tool" to Json.str(journal.tool.wire),
            "toolVersion" to Json.str(journal.toolVersion),
            "schemaVersion" to Json.str(journal.schemaVersion),
            "catalogVersion" to Json.str(journal.catalogVersion),
            "inputHash" to Json.str(journal.canonicalInputHash),
            "idempotencyKey" to Json.str(journal.idempotencyKey.value),
            "policyVersionId" to Json.str(journal.policyVersionId),
            "policyHash" to Json.str(journal.policyHash),
            "approvalId" to Json.str(journal.approvalId),
            "approvalFingerprint" to Json.str(journal.approvalFingerprint),
            "proofReference" to Json.str(journal.proofReference),
            "stage" to Json.str(journal.stage.name),
            "riskTier" to Json.str(journal.riskTier.name),
            "approvalLevel" to Json.str(journal.approvalLevel.name),
            "dispatch" to Json.str(journal.dispatch.name),
            "dispatchStartedAt" to Json.num(journal.dispatchStartedAt?.toEpochMilli()),
            "dispatchFinishedAt" to Json.num(journal.dispatchFinishedAt?.toEpochMilli()),
            "responseReceivedAt" to Json.num(journal.responseReceivedAt?.toEpochMilli()),
            "verificationStartedAt" to Json.num(journal.verificationStartedAt?.toEpochMilli()),
            "verificationFinishedAt" to Json.num(journal.verificationFinishedAt?.toEpochMilli()),
            "erpModel" to Json.str(journal.erpModel),
            "erpRecordId" to Json.str(journal.erpRecordId),
            "candidateIds" to Json.arr(journal.candidateIds.map { Json.str(it) }),
            "errorCode" to Json.str(journal.errorCode),
            "traceId" to Json.str(journal.traceId),
            "revision" to Json.num(journal.revision),
            "createdAt" to Json.num(journal.createdAt.toEpochMilli()),
            "updatedAt" to Json.num(journal.updatedAt.toEpochMilli()),
            "leaseExpiresAt" to Json.num(journal.leaseExpiresAt?.toEpochMilli()),
        ),
    )

    private fun parse(record: JsonValue.Obj): ExecutionJournal? {
        val executionId = record.text("executionId") ?: return null
        val tenantId = record.text("tenantId") ?: return null
        val actorId = record.text("actorId") ?: return null
        val tool = record.text("tool")?.let { ToolName.fromWire(it) } ?: return null
        val stage = record.text("stage")?.let { runCatching { JournalStage.valueOf(it) }.getOrNull() } ?: return null
        return ExecutionJournal(
            executionId = ExecutionId(executionId),
            tenantId = TenantId(tenantId),
            actorId = ActorId(actorId),
            proposalId = record.text("proposalId")?.let { ProposalId(it) },
            proposalFingerprint = record.text("proposalFingerprint") ?: "",
            tool = tool,
            toolVersion = record.text("toolVersion") ?: tool.version,
            schemaVersion = record.text("schemaVersion") ?: "",
            catalogVersion = record.text("catalogVersion") ?: "",
            canonicalInputHash = record.text("inputHash") ?: "",
            idempotencyKey = IdempotencyKey(record.text("idempotencyKey") ?: executionId),
            policyVersionId = record.text("policyVersionId") ?: "",
            policyHash = record.text("policyHash") ?: "",
            approvalId = record.text("approvalId"),
            approvalFingerprint = record.text("approvalFingerprint"),
            proofReference = record.text("proofReference"),
            stage = stage,
            riskTier = record.text("riskTier")?.let { runCatching { RiskTier.valueOf(it) }.getOrNull() }
                ?: RiskTier.R0_READ,
            approvalLevel = record.text("approvalLevel")?.let { runCatching { ApprovalLevel.valueOf(it) }.getOrNull() }
                ?: ApprovalLevel.L0_NONE,
            dispatch = record.text("dispatch")?.let { runCatching { DispatchState.valueOf(it) }.getOrNull() }
                ?: DispatchState.NOT_SENT,
            dispatchStartedAt = instantOf(record.long("dispatchStartedAt")),
            dispatchFinishedAt = instantOf(record.long("dispatchFinishedAt")),
            responseReceivedAt = instantOf(record.long("responseReceivedAt")),
            verificationStartedAt = instantOf(record.long("verificationStartedAt")),
            verificationFinishedAt = instantOf(record.long("verificationFinishedAt")),
            erpModel = record.text("erpModel"),
            erpRecordId = record.text("erpRecordId"),
            candidateIds = record.strings("candidateIds"),
            errorCode = record.text("errorCode"),
            traceId = record.text("traceId") ?: "",
            revision = record.long("revision") ?: 0L,
            createdAt = instantOf(record.long("createdAt")) ?: Instant.EPOCH,
            updatedAt = instantOf(record.long("updatedAt")) ?: Instant.EPOCH,
            leaseExpiresAt = instantOf(record.long("leaseExpiresAt")),
        )
    }
}

/**
 * The idempotency index. (tenant, key) is the identity of a request, and the
 * stored value is what that request already produced. A key reused with
 * different arguments is a refusal, and the check happens here, before the ERP
 * is touched.
 */
class IdempotencyStore(private val log: DurableLog) {

    data class Entry(
        val tenantId: String,
        val key: String,
        val canonicalArguments: String,
        val executionId: String,
        val status: String,
        val stage: JournalStage,
        val erpRecordId: String?,
        val erpModel: String?,
        val messageCode: String,
        val candidateRecordIds: List<String>,
        val summary: String?,
        val recordedAtMillis: Long,
    )

    private val entries = LinkedHashMap<String, Entry>()
    private var compactions = 0

    init {
        for (record in log.records()) {
            val parsed = Json.parseOrNull(record)?.asObject() ?: continue
            val entry = parse(parsed) ?: continue
            entries[index(entry.tenantId, entry.key)] = entry
        }
    }

    @Synchronized
    fun find(tenantId: String, key: String): Entry? = entries[index(tenantId, key)]

    @Synchronized
    fun record(entry: Entry): Entry {
        entries[index(entry.tenantId, entry.key)] = entry
        log.append(write(entry))
        return entry
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun all(): List<Entry> = entries.values.toList()

    /** Compaction keeps the file from growing without bound. */
    @Synchronized
    fun maybeCompact(maxRecords: Int = 5_000) {
        if (size() <= maxRecords) return
        log.compact(entries.values.map { write(it) })
        compactions++
    }

    private fun index(tenantId: String, key: String) = "$tenantId::$key"

    private fun write(entry: Entry): String = Json.write(
        obj(
            "tenantId" to Json.str(entry.tenantId),
            "key" to Json.str(entry.key),
            "canonicalArguments" to Json.str(entry.canonicalArguments),
            "executionId" to Json.str(entry.executionId),
            "status" to Json.str(entry.status),
            "stage" to Json.str(entry.stage.name),
            "erpRecordId" to Json.str(entry.erpRecordId),
            "erpModel" to Json.str(entry.erpModel),
            "messageCode" to Json.str(entry.messageCode),
            "candidateRecordIds" to Json.arr(entry.candidateRecordIds.map { Json.str(it) }),
            "summary" to Json.str(entry.summary),
            "recordedAtMillis" to Json.num(entry.recordedAtMillis),
        ),
    )

    private fun parse(record: JsonValue.Obj): Entry? {
        val tenantId = record.text("tenantId") ?: return null
        val key = record.text("key") ?: return null
        return Entry(
            tenantId = tenantId,
            key = key,
            canonicalArguments = record.text("canonicalArguments") ?: "",
            executionId = record.text("executionId") ?: "",
            status = record.text("status") ?: "",
            stage = record.text("stage")?.let { runCatching { JournalStage.valueOf(it) }.getOrNull() }
                ?: JournalStage.DRAFT,
            erpRecordId = record.text("erpRecordId"),
            erpModel = record.text("erpModel"),
            messageCode = record.text("messageCode") ?: "",
            candidateRecordIds = record.strings("candidateRecordIds"),
            summary = record.text("summary"),
            recordedAtMillis = record.long("recordedAtMillis") ?: 0L,
        )
    }
}

/**
 * Sessions that survive a restart. Without this, restarting the service logs
 * every operator out mid-shift, which in practice is how a governance layer
 * gets bypassed.
 */
class SessionStore(private val log: DurableLog) {

    data class Record(
        val tokenFingerprint: String,
        val actorId: String,
        val tenantId: String,
        val issuedAtMillis: Long,
        val expiresAtMillis: Long,
        val revokedAtMillis: Long? = null,
    ) {
        fun active(nowMillis: Long): Boolean = revokedAtMillis == null && nowMillis < expiresAtMillis
    }

    private val byFingerprint = LinkedHashMap<String, Record>()

    init {
        for (line in log.records()) {
            val parsed = Json.parseOrNull(line)?.asObject() ?: continue
            val fingerprint = parsed.text("tokenFingerprint") ?: continue
            byFingerprint[fingerprint] = Record(
                tokenFingerprint = fingerprint,
                actorId = parsed.text("actorId") ?: "",
                tenantId = parsed.text("tenantId") ?: "",
                issuedAtMillis = parsed.long("issuedAtMillis") ?: 0L,
                expiresAtMillis = parsed.long("expiresAtMillis") ?: 0L,
                revokedAtMillis = parsed.long("revokedAtMillis"),
            )
        }
    }

    @Synchronized
    fun save(record: Record): Record {
        byFingerprint[record.tokenFingerprint] = record
        log.append(
            Json.write(
                obj(
                    "tokenFingerprint" to Json.str(record.tokenFingerprint),
                    "actorId" to Json.str(record.actorId),
                    "tenantId" to Json.str(record.tenantId),
                    "issuedAtMillis" to Json.num(record.issuedAtMillis),
                    "expiresAtMillis" to Json.num(record.expiresAtMillis),
                    "revokedAtMillis" to Json.num(record.revokedAtMillis),
                ),
            ),
        )
        return record
    }

    @Synchronized
    fun find(tokenFingerprint: String): Record? = byFingerprint[tokenFingerprint]

    @Synchronized
    fun revoke(tokenFingerprint: String, atMillis: Long): Record? {
        val record = byFingerprint[tokenFingerprint] ?: return null
        return save(record.copy(revokedAtMillis = atMillis))
    }

    @Synchronized
    fun revokeAllFor(actorId: String, atMillis: Long): Int {
        val targets = byFingerprint.values.filter { it.actorId == actorId && it.revokedAtMillis == null }
        targets.forEach { save(it.copy(revokedAtMillis = atMillis)) }
        return targets.size
    }

    @Synchronized
    fun activeCount(nowMillis: Long): Int = byFingerprint.values.count { it.active(nowMillis) }

    @Synchronized
    fun size(): Int = byFingerprint.size
}

/** Signed receipts, kept so an execution can be proved after the fact. */
class ReceiptStore(private val log: DurableLog) {

    private val byId = LinkedHashMap<String, SignedReceipt>()

    init {
        for (line in log.records()) {
            val parsed = Json.parseOrNull(line)?.asObject() ?: continue
            val receipt = parse(parsed) ?: continue
            byId[receipt.claims.receiptId] = receipt
        }
    }

    @Synchronized
    fun save(receipt: SignedReceipt): SignedReceipt {
        byId[receipt.claims.receiptId] = receipt
        log.append(write(receipt))
        return receipt
    }

    @Synchronized
    fun get(receiptId: String): SignedReceipt? = byId[receiptId]

    @Synchronized
    fun forExecution(executionId: String): SignedReceipt? =
        byId.values.lastOrNull { it.claims.executionId == executionId }

    @Synchronized
    fun size(): Int = byId.size

    private fun write(receipt: SignedReceipt): String = Json.write(
        obj(
            "claims" to claimsOf(receipt),
            "algorithm" to Json.str(receipt.algorithm),
            "keyId" to Json.str(receipt.keyId),
            "signature" to Json.str(receipt.signature),
        ),
    )

    private fun claimsOf(receipt: SignedReceipt): JsonValue {
        val claims = receipt.claims
        return obj(
            "receiptId" to Json.str(claims.receiptId),
            "executionId" to Json.str(claims.executionId),
            "tenantId" to Json.str(claims.tenantId),
            "actorId" to Json.str(claims.actorId),
            "approverIds" to Json.arr(claims.approverIds.map { Json.str(it) }),
            "proposalFingerprint" to Json.str(claims.proposalFingerprint),
            "tool" to Json.str(claims.tool),
            "toolVersion" to Json.str(claims.toolVersion),
            "catalogVersion" to Json.str(claims.catalogVersion),
            "policyVersionId" to Json.str(claims.policyVersionId),
            "policyHash" to Json.str(claims.policyHash),
            "approvalLevel" to Json.str(claims.approvalLevel.name),
            "inputHash" to Json.str(claims.inputHash),
            "erpModel" to Json.str(claims.erpModel),
            "erpRecordId" to Json.str(claims.erpRecordId),
            "verificationHash" to Json.str(claims.verificationHash),
            "verifiedFields" to Json.arr(claims.verifiedFields.map { Json.str(it) }),
            "issuedAtMillis" to Json.num(claims.issuedAtMillis),
            "traceId" to Json.str(claims.traceId),
        )
    }

    private fun parse(record: JsonValue.Obj): SignedReceipt? {
        val claims = record.field("claims")?.asObject() ?: return null
        val receiptId = claims.text("receiptId") ?: return null
        return SignedReceipt(
            claims = app.mizan.domain.receipt.ReceiptClaims(
                receiptId = receiptId,
                executionId = claims.text("executionId") ?: "",
                tenantId = claims.text("tenantId") ?: "",
                actorId = claims.text("actorId") ?: "",
                approverIds = claims.strings("approverIds"),
                proposalFingerprint = claims.text("proposalFingerprint") ?: "",
                tool = claims.text("tool") ?: "",
                toolVersion = claims.text("toolVersion") ?: "",
                catalogVersion = claims.text("catalogVersion") ?: "",
                policyVersionId = claims.text("policyVersionId") ?: "",
                policyHash = claims.text("policyHash") ?: "",
                approvalLevel = claims.text("approvalLevel")
                    ?.let { runCatching { ApprovalLevel.valueOf(it) }.getOrNull() } ?: ApprovalLevel.L0_NONE,
                inputHash = claims.text("inputHash") ?: "",
                erpModel = claims.text("erpModel") ?: "",
                erpRecordId = claims.text("erpRecordId") ?: "",
                verificationHash = claims.text("verificationHash") ?: "",
                verifiedFields = claims.strings("verifiedFields"),
                issuedAtMillis = claims.long("issuedAtMillis") ?: 0L,
                traceId = claims.text("traceId") ?: "",
            ),
            algorithm = record.text("algorithm") ?: "",
            keyId = record.text("keyId") ?: "",
            signature = record.text("signature") ?: "",
        )
    }
}

/** Devices that may sign approvals, and their revocation state. */
class DurableDeviceRepository(private val log: DurableLog) : DeviceRepository {

    private val byId = LinkedHashMap<String, DevicePublicKey>()

    init {
        for (line in log.records()) {
            val parsed = Json.parseOrNull(line)?.asObject() ?: continue
            val id = parsed.text("deviceId") ?: continue
            val tenantId = parsed.text("tenantId") ?: continue
            val actorId = parsed.text("actorId") ?: continue
            byId[id] = DevicePublicKey(
                deviceId = id,
                tenantId = TenantId(tenantId),
                actorId = ActorId(actorId),
                algorithm = parsed.text("algorithm")
                    ?.let { runCatching { DeviceKeyAlgorithm.valueOf(it) }.getOrNull() }
                    ?: DeviceKeyAlgorithm.ED25519,
                publicKeyBase64 = parsed.text("publicKey") ?: "",
                label = parsed.text("label") ?: "",
                enrolledAtMillis = parsed.long("enrolledAtMillis") ?: 0L,
                revokedAtMillis = parsed.long("revokedAtMillis"),
                lastSeenAtMillis = parsed.long("lastSeenAtMillis"),
            )
        }
    }

    override fun find(deviceId: String): DevicePublicKey? = byId[deviceId]

    override fun save(device: DevicePublicKey) {
        byId[device.deviceId] = device
        log.append(
            Json.write(
                obj(
                    "deviceId" to Json.str(device.deviceId),
                    "tenantId" to Json.str(device.tenantId.value),
                    "actorId" to Json.str(device.actorId.value),
                    "algorithm" to Json.str(device.algorithm.name),
                    "publicKey" to Json.str(device.publicKeyBase64),
                    "label" to Json.str(device.label),
                    "enrolledAtMillis" to Json.num(device.enrolledAtMillis),
                    "revokedAtMillis" to Json.num(device.revokedAtMillis),
                    "lastSeenAtMillis" to Json.num(device.lastSeenAtMillis),
                ),
            ),
        )
    }

    override fun byTenant(tenantId: TenantId): List<DevicePublicKey> =
        byId.values.filter { it.tenantId == tenantId }
}

/** Approval challenges. A nonce must not survive as reusable after a restart. */
class DurableChallengeRepository(private val log: DurableLog) : app.mizan.domain.security.ChallengeRepository {

    private val byId = LinkedHashMap<String, ApprovalChallenge>()

    init {
        for (line in log.records()) {
            val parsed = Json.parseOrNull(line)?.asObject() ?: continue
            val id = parsed.text("challengeId") ?: continue
            val challengeTenant = parsed.text("tenantId") ?: continue
            val challengeActor = parsed.text("actorId") ?: continue
            val challengeExecution = parsed.text("executionId") ?: continue
            byId[id] = ApprovalChallenge(
                challengeId = id,
                nonce = parsed.text("nonce") ?: "",
                deviceId = parsed.text("deviceId") ?: "",
                tenantId = TenantId(challengeTenant),
                actorId = ActorId(challengeActor),
                executionId = ExecutionId(challengeExecution),
                proposalFingerprint = parsed.text("proposalFingerprint") ?: "",
                issuedAtMillis = parsed.long("issuedAtMillis") ?: 0L,
                expiresAtMillis = parsed.long("expiresAtMillis") ?: 0L,
                consumedAtMillis = parsed.long("consumedAtMillis"),
            )
        }
    }

    override fun find(challengeId: String): ApprovalChallenge? = byId[challengeId]

    override fun save(challenge: ApprovalChallenge) {
        byId[challenge.challengeId] = challenge
        log.append(
            Json.write(
                obj(
                    "challengeId" to Json.str(challenge.challengeId),
                    "nonce" to Json.str(challenge.nonce),
                    "deviceId" to Json.str(challenge.deviceId),
                    "tenantId" to Json.str(challenge.tenantId.value),
                    "actorId" to Json.str(challenge.actorId.value),
                    "executionId" to Json.str(challenge.executionId.value),
                    "proposalFingerprint" to Json.str(challenge.proposalFingerprint),
                    "issuedAtMillis" to Json.num(challenge.issuedAtMillis),
                    "expiresAtMillis" to Json.num(challenge.expiresAtMillis),
                    "consumedAtMillis" to Json.num(challenge.consumedAtMillis),
                ),
            ),
        )
    }
}

/** Reconciliation cases, so an uncertain write is still a question after a restart. */
class ReconciliationStore(private val log: DurableLog) {

    private val byId = LinkedHashMap<String, ReconciliationCase>()

    init {
        for (line in log.records()) {
            val parsed = Json.parseOrNull(line)?.asObject() ?: continue
            val id = parsed.text("id") ?: continue
            val caseExecution = parsed.text("executionId") ?: continue
            val caseTrace = parsed.text("traceId") ?: continue
            val caseTenant = parsed.text("tenantId") ?: continue
            val caseKey = parsed.text("idempotencyKey") ?: continue
            byId[id] = ReconciliationCase(
                id = id,
                executionId = ExecutionId(caseExecution),
                traceId = app.mizan.domain.model.TraceId(caseTrace),
                tenantId = TenantId(caseTenant),
                tool = parsed.text("tool")?.let { ToolName.fromWire(it) } ?: ToolName.UNKNOWN,
                intent = parsed.text("intent") ?: "",
                idempotencyKey = IdempotencyKey(caseKey),
                candidateRecordIds = parsed.strings("candidateRecordIds"),
                status = parsed.text("status")
                    ?.let { runCatching { ReconciliationStatus.valueOf(it) }.getOrNull() } ?: ReconciliationStatus.OPEN,
                notes = parsed.text("notes"),
                openedAt = instantOf(parsed.long("openedAt")) ?: Instant.EPOCH,
                reasonCode = parsed.text("reasonCode"),
                resolvedRecordId = parsed.text("resolvedRecordId"),
                resolvedByActorId = parsed.text("resolvedByActorId"),
                resolvedAtMillis = parsed.long("resolvedAtMillis"),
                resolutionLabelKey = parsed.text("resolutionLabelKey"),
                updatedAt = instantOf(parsed.long("updatedAt")) ?: Instant.EPOCH,
            )
        }
    }

    fun get(id: String): ReconciliationCase? = byId[id]

    fun get(tenantId: String, id: String): ReconciliationCase? =
        byId[id]?.takeIf { it.tenantId.value == tenantId }

    fun open(tenantId: String): List<ReconciliationCase> = byId.values.filter {
        it.tenantId.value == tenantId && it.status == ReconciliationStatus.OPEN
    }

    fun forTenant(tenantId: String): List<ReconciliationCase> =
        byId.values.filter { it.tenantId.value == tenantId }.sortedByDescending { it.openedAt }

    fun all(tenantId: String): List<ReconciliationCase> = forTenant(tenantId)

    fun upsert(case: ReconciliationCase): ReconciliationCase {
        byId[case.id] = case
        log.append(
            Json.write(
                obj(
                    "id" to Json.str(case.id),
                    "executionId" to Json.str(case.executionId.value),
                    "traceId" to Json.str(case.traceId.value),
                    "tenantId" to Json.str(case.tenantId.value),
                    "tool" to Json.str(case.tool.wire),
                    "intent" to Json.str(case.intent),
                    "idempotencyKey" to Json.str(case.idempotencyKey.value),
                    "candidateRecordIds" to Json.arr(case.candidateRecordIds.map { Json.str(it) }),
                    "status" to Json.str(case.status.name),
                    "notes" to Json.str(case.notes),
                    "openedAt" to Json.num(case.openedAt.toEpochMilli()),
                    "reasonCode" to Json.str(case.reasonCode),
                    "resolvedRecordId" to Json.str(case.resolvedRecordId),
                    "resolvedByActorId" to Json.str(case.resolvedByActorId),
                    "resolvedAtMillis" to Json.num(case.resolvedAtMillis),
                    "resolutionLabelKey" to Json.str(case.resolutionLabelKey),
                    "updatedAt" to Json.num(case.updatedAt.toEpochMilli()),
                ),
            ),
        )
        return case
    }

    fun size(): Int = byId.size
}

/** The service's own audit chain, written to disk as it is appended. */
class AuditStore(private val log: DurableLog) {

    private val events = ArrayList<app.mizan.domain.audit.AuditEvent>()

    init {
        for (line in log.records()) {
            val parsed = Json.parseOrNull(line)?.asObject() ?: continue
            events += app.mizan.domain.audit.AuditEvent(
                chainIndex = parsed.long("chainIndex") ?: 0L,
                timestampMillis = parsed.long("timestampMillis") ?: 0L,
                traceId = parsed.text("traceId") ?: "",
                tenantId = parsed.text("tenantId") ?: "",
                actorId = parsed.text("actorId") ?: "",
                action = parsed.text("action") ?: "",
                stateBefore = parsed.text("stateBefore") ?: "",
                stateAfter = parsed.text("stateAfter") ?: "",
                details = parsed.text("details") ?: "",
                previousHash = parsed.text("previousHash") ?: "",
                currentHash = parsed.text("currentHash") ?: "",
                integrityClass = app.mizan.domain.audit.IntegrityClass.SERVER_AUTHORED,
            )
        }
    }

    fun append(event: app.mizan.domain.audit.AuditEvent): app.mizan.domain.audit.AuditEvent {
        events += event
        log.append(
            Json.write(
                obj(
                    "chainIndex" to Json.num(event.chainIndex),
                    "timestampMillis" to Json.num(event.timestampMillis),
                    "traceId" to Json.str(event.traceId),
                    "tenantId" to Json.str(event.tenantId),
                    "actorId" to Json.str(event.actorId),
                    "action" to Json.str(event.action),
                    "stateBefore" to Json.str(event.stateBefore),
                    "stateAfter" to Json.str(event.stateAfter),
                    "details" to Json.str(event.details),
                    "previousHash" to Json.str(event.previousHash),
                    "currentHash" to Json.str(event.currentHash),
                ),
            ),
        )
        return event
    }

    fun forTenant(tenantId: String): List<app.mizan.domain.audit.AuditEvent> =
        events.filter { it.tenantId == tenantId }

    fun lastFor(tenantId: String): app.mizan.domain.audit.AuditEvent? =
        events.lastOrNull { it.tenantId == tenantId }

    fun size(): Int = events.size
}

/** Base64 helpers shared by the stores. */
internal object Base64Text {
    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
}
