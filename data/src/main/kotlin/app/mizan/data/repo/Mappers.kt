package app.mizan.data.repo

import app.mizan.data.local.AuditEventEntity
import app.mizan.data.local.CustomerEntity
import app.mizan.data.local.ExecutionEntity
import app.mizan.data.local.OrderEntity
import app.mizan.data.local.ReceiptEntity
import app.mizan.data.local.ReconciliationEntity
import app.mizan.data.local.StockEntity
import app.mizan.data.local.SyncEntity
import app.mizan.domain.audit.AuditEvent
import app.mizan.domain.audit.IntegrityClass
import app.mizan.domain.error.DispatchState
import app.mizan.domain.execution.ExecutionPhase
import app.mizan.domain.model.ActorId
import app.mizan.domain.model.ApprovalLevel
import app.mizan.domain.model.CachedCustomer
import app.mizan.domain.model.CachedOrder
import app.mizan.domain.model.CachedStock
import app.mizan.domain.model.EvidenceOrigin
import app.mizan.domain.model.ExecutionId
import app.mizan.domain.model.ExecutionRecord
import app.mizan.domain.model.IdempotencyKey
import app.mizan.domain.model.Money
import app.mizan.domain.model.ProposalId
import app.mizan.domain.model.ReceiptId
import app.mizan.domain.model.ReconciliationCase
import app.mizan.domain.model.ReconciliationStatus
import app.mizan.domain.model.RiskTier
import app.mizan.domain.model.SyncSnapshot
import app.mizan.domain.model.SyncState
import app.mizan.domain.model.TenantId
import app.mizan.domain.model.ToolName
import app.mizan.domain.model.TraceId
import app.mizan.domain.model.TrustReceipt
import app.mizan.domain.model.VerificationKind
import java.time.Instant

internal fun ExecutionEntity.toDomain(): ExecutionRecord = ExecutionRecord(
    id = ExecutionId(executionId),
    traceId = TraceId(traceId),
    proposalId = ProposalId(proposalId),
    tenantId = TenantId(tenantId),
    initiatorId = ActorId(initiatorId),
    tool = ToolName.fromWire(toolName) ?: ToolName.UNKNOWN,
    toolVersion = toolVersion,
    intent = intent,
    phase = runCatching { ExecutionPhase.valueOf(phase) }.getOrDefault(ExecutionPhase.RECONCILIATION_REQUIRED),
    idempotencyKey = IdempotencyKey(idempotencyKey),
    canonicalArgs = canonicalArgs,
    amount = if (amountMinor != null && currency != null) Money(amountMinor, currency) else null,
    approval = runCatching { ApprovalLevel.valueOf(approval) }.getOrDefault(ApprovalLevel.L5_MULTI_PARTY),
    riskTier = runCatching { RiskTier.valueOf(riskTier) }.getOrDefault(RiskTier.R4_CRITICAL),
    policyRuleId = policyRuleId,
    approverIds = approverIds.split(',').filter { it.isNotBlank() }.map(::ActorId),
    erpRecordId = erpRecordId,
    erpModel = erpModel,
    dispatch = runCatching { DispatchState.valueOf(dispatch) }.getOrDefault(DispatchState.UNKNOWN),
    leaseExpiresAt = leaseExpiresAt?.let(Instant::ofEpochMilli),
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    errorCode = errorCode,
    origin = runCatching { EvidenceOrigin.valueOf(origin) }.getOrDefault(EvidenceOrigin.LEGACY_LOCAL),
)

internal fun ExecutionRecord.toEntity(): ExecutionEntity = ExecutionEntity(
    executionId = id.value,
    traceId = traceId.value,
    proposalId = proposalId.value,
    tenantId = tenantId.value,
    initiatorId = initiatorId.value,
    toolName = tool.wire,
    toolVersion = toolVersion,
    intent = intent,
    phase = phase.name,
    idempotencyKey = idempotencyKey.value,
    canonicalArgs = canonicalArgs,
    amountMinor = amount?.minorUnits,
    currency = amount?.currency,
    approval = approval.name,
    riskTier = riskTier.name,
    policyRuleId = policyRuleId,
    approverIds = approverIds.joinToString(",") { it.value },
    erpRecordId = erpRecordId,
    erpModel = erpModel,
    dispatch = dispatch.name,
    leaseExpiresAt = leaseExpiresAt?.toEpochMilli(),
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    errorCode = errorCode,
    origin = origin.name,
)

internal fun ReceiptEntity.toDomain(): TrustReceipt = TrustReceipt(
    id = ReceiptId(receiptId),
    traceId = TraceId(traceId),
    executionId = ExecutionId(executionId),
    tenantId = TenantId(tenantId),
    tenantLabel = tenantLabel,
    initiatorId = ActorId(initiatorId),
    initiatorLabel = initiatorLabel,
    approverIds = approverIds.split(',').filter { it.isNotBlank() }.map(::ActorId),
    approverLabels = approverLabels.split(',').filter { it.isNotBlank() },
    tool = ToolName.fromWire(toolName) ?: ToolName.UNKNOWN,
    toolVersion = toolVersion,
    policyRuleId = policyRuleId,
    approval = runCatching { ApprovalLevel.valueOf(approval) }.getOrDefault(ApprovalLevel.L5_MULTI_PARTY),
    riskTier = runCatching { RiskTier.valueOf(riskTier) }.getOrDefault(RiskTier.R4_CRITICAL),
    canonicalArgs = canonicalArgs,
    idempotencyKey = IdempotencyKey(idempotencyKey),
    erpRecordId = erpRecordId,
    erpModel = erpModel,
    verification = runCatching { VerificationKind.valueOf(verification) }.getOrDefault(VerificationKind.NOT_VERIFIED),
    integrityClass = runCatching { IntegrityClass.valueOf(integrityClass) }.getOrDefault(IntegrityClass.LOCAL_ONLY),
    origin = runCatching { EvidenceOrigin.valueOf(origin) }.getOrDefault(EvidenceOrigin.LEGACY_LOCAL),
    createdAt = Instant.ofEpochMilli(createdAt),
    auditChainIndex = auditChainIndex,
)

internal fun TrustReceipt.toEntity(): ReceiptEntity = ReceiptEntity(
    receiptId = id.value,
    traceId = traceId.value,
    executionId = executionId.value,
    tenantId = tenantId.value,
    tenantLabel = tenantLabel,
    initiatorId = initiatorId.value,
    initiatorLabel = initiatorLabel,
    approverIds = approverIds.joinToString(",") { it.value },
    approverLabels = approverLabels.joinToString(","),
    toolName = tool.wire,
    toolVersion = toolVersion,
    policyRuleId = policyRuleId,
    approval = approval.name,
    riskTier = riskTier.name,
    canonicalArgs = canonicalArgs,
    idempotencyKey = idempotencyKey.value,
    erpRecordId = erpRecordId,
    erpModel = erpModel,
    verification = verification.name,
    integrityClass = integrityClass.name,
    origin = origin.name,
    createdAt = createdAt.toEpochMilli(),
    auditChainIndex = auditChainIndex,
)

internal fun AuditEventEntity.toDomain(): AuditEvent = AuditEvent(
    chainIndex = chainIndex,
    timestampMillis = timestampMillis,
    traceId = traceId,
    tenantId = tenantId,
    actorId = actorId,
    action = action,
    stateBefore = stateBefore,
    stateAfter = stateAfter,
    details = details,
    previousHash = previousHash,
    currentHash = currentHash,
    integrityClass = runCatching { IntegrityClass.valueOf(integrityClass) }.getOrDefault(IntegrityClass.LOCAL_ONLY),
)

internal fun ReconciliationEntity.toDomain(): ReconciliationCase = ReconciliationCase(
    id = caseId,
    executionId = ExecutionId(executionId),
    traceId = TraceId(traceId),
    tenantId = TenantId(tenantId),
    tool = ToolName.fromWire(toolName) ?: ToolName.UNKNOWN,
    intent = intent,
    idempotencyKey = IdempotencyKey(idempotencyKey),
    candidateRecordIds = candidateRecordIds.split(',').filter { it.isNotBlank() },
    status = runCatching { ReconciliationStatus.valueOf(status) }.getOrDefault(ReconciliationStatus.OPEN),
    notes = notes,
    openedAt = Instant.ofEpochMilli(openedAt),
)

internal fun ReconciliationCase.toEntity(): ReconciliationEntity = ReconciliationEntity(
    caseId = id,
    executionId = executionId.value,
    traceId = traceId.value,
    tenantId = tenantId.value,
    toolName = tool.wire,
    intent = intent,
    idempotencyKey = idempotencyKey.value,
    candidateRecordIds = candidateRecordIds.joinToString(","),
    status = status.name,
    notes = notes,
    openedAt = openedAt.toEpochMilli(),
)

internal fun OrderEntity.toDomain(): CachedOrder = CachedOrder(
    id = orderId,
    tenantId = TenantId(tenantId),
    customerName = customerName,
    amount = Money(amountMinor, currency),
    status = status,
    summary = summary,
    origin = runCatching { EvidenceOrigin.valueOf(origin) }.getOrDefault(EvidenceOrigin.LEGACY_LOCAL),
    updatedAt = Instant.ofEpochMilli(updatedAt),
)

internal fun CustomerEntity.toDomain(): CachedCustomer = CachedCustomer(
    id = customerId,
    tenantId = TenantId(tenantId),
    name = name,
    creditLimit = if (creditMinor != null && currency != null) Money(creditMinor, currency) else null,
    balance = if (balanceMinor != null && currency != null) Money(balanceMinor, currency) else null,
    status = status,
    origin = runCatching { EvidenceOrigin.valueOf(origin) }.getOrDefault(EvidenceOrigin.LEGACY_LOCAL),
)

internal fun StockEntity.toDomain(): CachedStock = CachedStock(
    sku = sku,
    tenantId = TenantId(tenantId),
    name = name,
    availableQty = availableQty,
    reservedQty = reservedQty,
    unitPrice = if (priceMinor != null && currency != null) Money(priceMinor, currency) else null,
    location = location,
    origin = runCatching { EvidenceOrigin.valueOf(origin) }.getOrDefault(EvidenceOrigin.LEGACY_LOCAL),
)

internal fun SyncEntity.toDomain(): SyncSnapshot = SyncSnapshot(
    tenantId = TenantId(tenantId),
    lastSuccessfulSync = lastSuccessfulSync?.let(Instant::ofEpochMilli),
    lastAttempt = lastAttempt?.let(Instant::ofEpochMilli),
    state = runCatching { SyncState.valueOf(state) }.getOrDefault(SyncState.NEVER),
    pendingChanges = pendingChanges,
    failedChanges = failedChanges,
    conflicts = conflicts,
    serverCursor = serverCursor,
)
