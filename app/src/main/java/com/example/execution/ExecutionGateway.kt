package com.example.execution

import com.example.connectors.ErpConnector
import com.example.connectors.ErpExecutionResult
import com.example.control.PolicyEngine
import com.example.data.local.ExecutionRecordEntity
import com.example.data.local.MizanDao
import com.example.data.local.ReconciliationItemEntity
import com.example.data.local.TrustReceiptEntity
import com.example.decision.DecisionService
import com.example.evidence.AuditChainManager
import com.example.model.ApprovalLevel
import com.example.model.BoundedProposal
import com.example.model.CryptoUtil
import com.example.model.DecisionSignals
import com.example.model.ExecutionLease
import com.example.model.ExecutionState
import com.example.model.IdentityPrincipal
import com.example.model.RiskTier
import com.example.model.TrustReceipt
import java.util.UUID

sealed class GatewayExecutionOutcome {
    data class RequiresApproval(
        val proposal: BoundedProposal,
        val decisionSignals: DecisionSignals,
        val requiredLevel: ApprovalLevel,
        val policyReasonEn: String,
        val policyReasonAr: String
    ) : GatewayExecutionOutcome()

    data class VerifiedSuccess(
        val executionId: String,
        val state: ExecutionState,
        val erpRecordId: String,
        val receipt: TrustReceipt
    ) : GatewayExecutionOutcome()

    data class AmbiguousState(
        val executionId: String,
        val candidateErpIds: List<String>,
        val messageEn: String,
        val messageAr: String
    ) : GatewayExecutionOutcome()

    data class Rejected(
        val reasonEn: String,
        val reasonAr: String
    ) : GatewayExecutionOutcome()
}

class ExecutionGateway(
    private val dao: MizanDao,
    private val policyEngine: PolicyEngine,
    private val decisionService: DecisionService,
    private val auditChainManager: AuditChainManager,
    private val odooConnector: ErpConnector,
    private val erpNextConnector: ErpConnector
) {
    // Active in-memory leases (executionId -> lease)
    private val activeLeases = mutableMapOf<String, ExecutionLease>()

    /**
     * Resolves appropriate connector for tenant
     */
    private fun getConnectorForTenant(tenantId: String): ErpConnector {
        return if (tenantId == "tenant-b") erpNextConnector else odooConnector
    }

    /**
     * Step 1 to 4: Propose an action from human intent and assess through Decision & Policy planes
     */
    suspend fun proposeExecution(
        initiator: IdentityPrincipal,
        rawIntent: String,
        targetTool: String,
        toolVersion: String = "2.1.0",
        rawArgs: Map<String, String>,
        estimatedAmount: Double
    ): GatewayExecutionOutcome {
        val traceId = "TRC-${UUID.randomUUID().toString().take(8).uppercase()}"
        val proposalId = "PRP-${UUID.randomUUID().toString().take(8).uppercase()}"
        val executionId = "EXE-${UUID.randomUUID().toString().take(8).uppercase()}"

        // Deterministic Policy Evaluation
        val policyEval = policyEngine.evaluate(
            actor = initiator,
            toolName = targetTool,
            financialAmount = estimatedAmount,
            isDestructive = targetTool == "sales.order.cancel"
        )

        if (!policyEval.allowed) {
            auditChainManager.appendAuditRecord(
                traceId = traceId,
                tenantId = initiator.tenantId,
                actorId = initiator.userId,
                action = "PROPOSAL_REJECTED",
                stateBefore = "INTENT_SUBMITTED",
                stateAfter = ExecutionState.CANCELLED.name,
                detailsJson = """{"tool":"$targetTool","reason":"${policyEval.reasonEn}"}"""
            )
            return GatewayExecutionOutcome.Rejected(
                reasonEn = policyEval.reasonEn,
                reasonAr = policyEval.reasonAr
            )
        }

        // Secondary Decision Signals (Jev Protocol)
        val decisionSignals = decisionService.evaluateIntent(
            rawIntent = rawIntent,
            targetTool = targetTool,
            amount = estimatedAmount,
            policyRequiredLevel = policyEval.requiredApprovalLevel
        )

        val argsJson = rawArgs.entries.joinToString(prefix = "{", postfix = "}") {
            """"${it.key}":"${it.value}""""
        }

        val proposal = BoundedProposal(
            proposalId = proposalId,
            traceId = traceId,
            timestamp = System.currentTimeMillis(),
            tenantId = initiator.tenantId,
            initiatorId = initiator.userId,
            rawIntent = rawIntent,
            targetTool = targetTool,
            toolVersion = toolVersion,
            argumentsJson = argsJson,
            argumentsMap = rawArgs,
            estimatedFinancialValue = estimatedAmount,
            requiredApprovalLevel = policyEval.requiredApprovalLevel,
            riskTier = if (decisionSignals.escalationRequired && decisionSignals.semanticRisk.ordinal > policyEval.riskTier.ordinal)
                decisionSignals.semanticRisk else policyEval.riskTier,
            policyRuleId = policyEval.ruleId,
            policyExplanationEn = policyEval.reasonEn,
            policyExplanationAr = policyEval.reasonAr
        )

        val idempotencyKey = CryptoUtil.computeIdempotencyKey(initiator.tenantId, targetTool, argsJson)

        // Store execution initial state in DB
        val execEntity = ExecutionRecordEntity(
            executionId = executionId,
            traceId = traceId,
            timestamp = System.currentTimeMillis(),
            tenantId = initiator.tenantId,
            initiatorId = initiator.userId,
            toolName = targetTool,
            rawIntent = rawIntent,
            currentState = ExecutionState.PROPOSED.name,
            idempotencyKey = idempotencyKey,
            leaseId = null,
            leaseExpiresAt = null,
            approverId = null,
            erpRecordId = null,
            financialAmount = estimatedAmount,
            riskTier = proposal.riskTier.name,
            approvalLevel = proposal.requiredApprovalLevel.name,
            errorMessage = null
        )
        dao.insertExecution(execEntity)

        auditChainManager.appendAuditRecord(
            traceId = traceId,
            tenantId = initiator.tenantId,
            actorId = initiator.userId,
            action = "PROPOSAL_CREATED",
            stateBefore = "INITIAL",
            stateAfter = ExecutionState.PROPOSED.name,
            detailsJson = """{"proposalId":"$proposalId","tool":"$targetTool","risk":"${proposal.riskTier}","level":"${proposal.requiredApprovalLevel}"}"""
        )

        return GatewayExecutionOutcome.RequiresApproval(
            proposal = proposal,
            decisionSignals = decisionSignals,
            requiredLevel = proposal.requiredApprovalLevel,
            policyReasonEn = proposal.policyExplanationEn,
            policyReasonAr = proposal.policyExplanationAr
        )
    }

    /**
     * Step 5 to 12: Execute approved operation with Lease, Connector, Verification, and Trust Receipt
     */
    suspend fun executeApprovedProposal(
        proposal: BoundedProposal,
        approver: IdentityPrincipal,
        forceAmbiguousSimulation: Boolean = false
    ): GatewayExecutionOutcome {
        val executionId = "EXE-${proposal.traceId.takeLast(8)}"
        val connector = getConnectorForTenant(proposal.tenantId)

        // 1. Separation of Duties Check
        val sodValid = policyEngine.validateSeparationOfDuties(
            initiatorId = proposal.initiatorId,
            approver = approver,
            requiredLevel = proposal.requiredApprovalLevel
        )

        if (!sodValid) {
            val errorEn = "Separation of Duties violation: Initiator (${proposal.initiatorId}) cannot approve tier ${proposal.requiredApprovalLevel.code} alone."
            val errorAr = "مخالفة فصل المهام: منشئ الطلب (${proposal.initiatorId}) لا يمكنه اعتماد المستوى ${proposal.requiredApprovalLevel.code} منفردًا."
            return GatewayExecutionOutcome.Rejected(errorEn, errorAr)
        }

        // 2. Acquire Execution Lease (30 seconds duration)
        val leaseId = "LSE-${UUID.randomUUID().toString().take(8).uppercase()}"
        val now = System.currentTimeMillis()
        val leaseExpires = now + 30000L
        val lease = ExecutionLease(leaseId, executionId, approver.userId, now, leaseExpires)
        activeLeases[executionId] = lease

        auditChainManager.appendAuditRecord(
            traceId = proposal.traceId,
            tenantId = proposal.tenantId,
            actorId = approver.userId,
            action = "LEASE_ACQUIRED",
            stateBefore = ExecutionState.AWAITING_APPROVAL.name,
            stateAfter = ExecutionState.LEASE_ACQUIRED.name,
            detailsJson = """{"leaseId":"$leaseId","expiresAt":$leaseExpires}"""
        )

        // 3. Invoke ERP Connector boundary
        val erpResult = connector.executeTool(
            tenantId = proposal.tenantId,
            toolName = proposal.targetTool,
            validatedArgs = proposal.argumentsMap,
            traceId = proposal.traceId,
            forceAmbiguousSimulation = forceAmbiguousSimulation
        )

        when (erpResult) {
            is ErpExecutionResult.Success -> {
                // 4. Deterministic Post-Execution Verification
                val isVerified = connector.verifyRecord(
                    tenantId = proposal.tenantId,
                    erpModel = erpResult.erpModel,
                    recordId = erpResult.erpRecordId
                )

                if (!isVerified) {
                    return GatewayExecutionOutcome.Rejected(
                        reasonEn = "ERP Verification failed: Record ${erpResult.erpRecordId} was not found in read-back check.",
                        reasonAr = "فشل التحقق من ERP: لم يتم العثور على السجل ${erpResult.erpRecordId} أثناء التحقق اللاحق."
                    )
                }

                // 5. Append Evidence & Mint Trust Receipt
                val auditEntity = auditChainManager.appendAuditRecord(
                    traceId = proposal.traceId,
                    tenantId = proposal.tenantId,
                    actorId = approver.userId,
                    action = "ERP_MUTATION_VERIFIED",
                    stateBefore = ExecutionState.EXECUTING.name,
                    stateAfter = ExecutionState.VERIFIED.name,
                    detailsJson = """{"erpId":"${erpResult.erpRecordId}","tx":"${erpResult.transactionReference}","hash":"${erpResult.verificationHash}"}"""
                )

                val receiptId = "RCP-${UUID.randomUUID().toString().take(8).uppercase()}"
                val idempotencyKey = CryptoUtil.computeIdempotencyKey(proposal.tenantId, proposal.targetTool, proposal.argumentsJson)
                val token = CryptoUtil.computeTamperProofToken(receiptId, proposal.traceId, erpResult.erpRecordId, System.currentTimeMillis())

                val trustReceipt = TrustReceipt(
                    receiptId = receiptId,
                    traceId = proposal.traceId,
                    executionId = executionId,
                    timestamp = System.currentTimeMillis(),
                    tenantId = proposal.tenantId,
                    tenantName = if (proposal.tenantId == "tenant-a") "Al-Amal Trading (Odoo 19)" else "Nile Industrial (ERPNext)",
                    initiatorId = proposal.initiatorId,
                    initiatorName = "Amr Kamel (Sales)",
                    initiatorRole = "SALES_REP",
                    intent = proposal.rawIntent,
                    toolName = proposal.targetTool,
                    toolVersion = proposal.toolVersion,
                    policyRuleId = proposal.policyRuleId,
                    approvalLevel = proposal.requiredApprovalLevel.code,
                    riskTier = proposal.riskTier.code,
                    approverId = approver.userId,
                    approverName = approver.fullName,
                    sodProof = if (proposal.requiredApprovalLevel.levelNumber >= 2) "Verified: Initiator (${proposal.initiatorId}) != Approver (${approver.userId})" else "L1 Self-Confirmed",
                    canonicalArgumentsJson = proposal.argumentsJson,
                    idempotencyKey = idempotencyKey,
                    erpRecordId = erpResult.erpRecordId,
                    erpModel = erpResult.erpModel,
                    verificationHash = erpResult.verificationHash,
                    auditChainIndex = auditEntity.chainIndex,
                    tamperProofToken = token
                )

                // Persist receipt
                dao.insertTrustReceipt(
                    TrustReceiptEntity(
                        receiptId = trustReceipt.receiptId,
                        traceId = trustReceipt.traceId,
                        executionId = trustReceipt.executionId,
                        timestamp = trustReceipt.timestamp,
                        tenantId = trustReceipt.tenantId,
                        tenantName = trustReceipt.tenantName,
                        initiatorId = trustReceipt.initiatorId,
                        initiatorName = trustReceipt.initiatorName,
                        initiatorRole = trustReceipt.initiatorRole,
                        intent = trustReceipt.intent,
                        toolName = trustReceipt.toolName,
                        toolVersion = trustReceipt.toolVersion,
                        policyRuleId = trustReceipt.policyRuleId,
                        approvalLevel = trustReceipt.approvalLevel,
                        riskTier = trustReceipt.riskTier,
                        approverId = trustReceipt.approverId,
                        approverName = trustReceipt.approverName,
                        sodProof = trustReceipt.sodProof,
                        canonicalArgumentsJson = trustReceipt.canonicalArgumentsJson,
                        idempotencyKey = trustReceipt.idempotencyKey,
                        erpRecordId = trustReceipt.erpRecordId,
                        erpModel = trustReceipt.erpModel,
                        verificationHash = trustReceipt.verificationHash,
                        auditChainIndex = trustReceipt.auditChainIndex,
                        tamperProofToken = trustReceipt.tamperProofToken
                    )
                )

                // Update execution record
                dao.insertExecution(
                    ExecutionRecordEntity(
                        executionId = executionId,
                        traceId = proposal.traceId,
                        timestamp = System.currentTimeMillis(),
                        tenantId = proposal.tenantId,
                        initiatorId = proposal.initiatorId,
                        toolName = proposal.targetTool,
                        rawIntent = proposal.rawIntent,
                        currentState = ExecutionState.VERIFIED.name,
                        idempotencyKey = idempotencyKey,
                        leaseId = leaseId,
                        leaseExpiresAt = leaseExpires,
                        approverId = approver.userId,
                        erpRecordId = erpResult.erpRecordId,
                        financialAmount = proposal.estimatedFinancialValue,
                        riskTier = proposal.riskTier.name,
                        approvalLevel = proposal.requiredApprovalLevel.name,
                        errorMessage = null
                    )
                )

                return GatewayExecutionOutcome.VerifiedSuccess(
                    executionId = executionId,
                    state = ExecutionState.VERIFIED,
                    erpRecordId = erpResult.erpRecordId,
                    receipt = trustReceipt
                )
            }

            is ErpExecutionResult.Ambiguous -> {
                // Transition to AMBIGUOUS and enter Reconciliation Center
                val reconId = "REC-${UUID.randomUUID().toString().take(8).uppercase()}"
                val idempotencyKey = CryptoUtil.computeIdempotencyKey(proposal.tenantId, proposal.targetTool, proposal.argumentsJson)

                dao.insertReconciliationItem(
                    ReconciliationItemEntity(
                        reconciliationId = reconId,
                        executionId = executionId,
                        traceId = proposal.traceId,
                        tenantId = proposal.tenantId,
                        toolName = proposal.targetTool,
                        timestamp = System.currentTimeMillis(),
                        idempotencyKey = idempotencyKey,
                        intent = proposal.rawIntent,
                        payloadJson = proposal.argumentsJson,
                        candidateErpIds = erpResult.candidateMatches.joinToString(","),
                        resolutionStatus = "PENDING"
                    )
                )

                auditChainManager.appendAuditRecord(
                    traceId = proposal.traceId,
                    tenantId = proposal.tenantId,
                    actorId = approver.userId,
                    action = "EXECUTION_AMBIGUOUS_RECON_REQUIRED",
                    stateBefore = ExecutionState.EXECUTING.name,
                    stateAfter = ExecutionState.RECONCILIATION_REQUIRED.name,
                    detailsJson = """{"candidates":"${erpResult.candidateMatches}","error":"${erpResult.messageEn}"}"""
                )

                return GatewayExecutionOutcome.AmbiguousState(
                    executionId = executionId,
                    candidateErpIds = erpResult.candidateMatches,
                    messageEn = erpResult.messageEn,
                    messageAr = erpResult.messageAr
                )
            }

            is ErpExecutionResult.Failed -> {
                auditChainManager.appendAuditRecord(
                    traceId = proposal.traceId,
                    tenantId = proposal.tenantId,
                    actorId = approver.userId,
                    action = "EXECUTION_FAILED",
                    stateBefore = ExecutionState.EXECUTING.name,
                    stateAfter = ExecutionState.FAILED.name,
                    detailsJson = """{"error":"${erpResult.messageEn}"}"""
                )
                return GatewayExecutionOutcome.Rejected(
                    reasonEn = erpResult.messageEn,
                    reasonAr = erpResult.messageAr
                )
            }
        }
    }
}
