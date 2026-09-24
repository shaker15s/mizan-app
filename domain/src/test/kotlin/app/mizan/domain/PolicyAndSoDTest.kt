package app.mizan.domain

import app.mizan.domain.model.Actor
import app.mizan.domain.model.ActorId
import app.mizan.domain.model.Money
import app.mizan.domain.model.Role
import app.mizan.domain.model.TenantId
import app.mizan.domain.model.ToolName
import app.mizan.domain.policy.ApprovalRecord
import app.mizan.domain.policy.PolicyCatalog
import app.mizan.domain.policy.PolicyEvaluator
import app.mizan.domain.policy.PolicyRequest
import app.mizan.domain.policy.SeparationOfDuties
import app.mizan.domain.policy.SodCode
import app.mizan.domain.model.ApprovalLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyAndSoDTest {
    private val tenant = TenantId("tenant-a")
    private val rep = Actor(ActorId("USR-01"), "Amr Kamel", Role.SALES_REP, tenant)
    private val manager = Actor(ActorId("MGR-01"), "Tarek", Role.SALES_MANAGER, tenant)
    private val finance = Actor(ActorId("FIN-01"), "Noha", Role.FINANCE_APPROVER, tenant)
    private val auditor = Actor(ActorId("AUD-01"), "Hisham", Role.AUDITOR, tenant)
    private val policy = PolicyEvaluator(PolicyCatalog.demo)
    private val sod = SeparationOfDuties()

    @Test
    fun readOnlyIsL0() {
        val result = policy.evaluate(request(rep, ToolName.STOCK_AVAILABILITY, null))
        assertTrue(result.allowed)
        assertEquals(ApprovalLevel.L0_NONE, result.approval)
        assertEquals("SAFE_READ", result.reasonCode)
    }

    @Test
    fun auditorCannotWrite() {
        val result = policy.evaluate(request(auditor, ToolName.CREATE_DRAFT_ORDER, Money(50_000, "USD")))
        assertFalse(result.allowed)
        assertEquals("AUDITOR_READONLY", result.reasonCode)
    }

    @Test
    fun usdLadderMatchesHistoricalThresholds() {
        assertEquals(ApprovalLevel.L1_USER_CONFIRMATION, approval(Money(80_000, "USD")))
        assertEquals(ApprovalLevel.L2_PRIVILEGED, approval(Money(450_000, "USD")))
        assertEquals(ApprovalLevel.L3_MANAGER, approval(Money(1_500_000, "USD")))
        assertEquals(ApprovalLevel.L4_DUAL, approval(Money(3_000_000, "USD")))
    }

    @Test
    fun egpIsNotJudgedAsDollars() {
        val smallEgp = policy.evaluate(request(rep, ToolName.CREATE_DRAFT_ORDER, Money(150_000, "EGP")))
        assertEquals("THRESHOLD_L1", smallEgp.reasonCode)
        val unconfigured = PolicyEvaluator(PolicyCatalog.empty)
            .evaluate(request(rep, ToolName.CREATE_DRAFT_ORDER, Money(150_000, "EGP")))
        assertEquals("CURRENCY_LADDER_MISSING", unconfigured.reasonCode)
        assertEquals(ApprovalLevel.L3_MANAGER, unconfigured.approval)
    }

    @Test
    fun destructiveRequiresManagerAndSod() {
        val result = policy.evaluate(request(rep, ToolName.CANCEL_ORDER, null, destructive = true))
        assertEquals(ApprovalLevel.L3_MANAGER, result.approval)
        assertTrue(result.requiresSeparationOfDuties)
    }

    @Test
    fun l1AllowsSelfConfirmation() {
        val code = sod.check(rep.id, tenant, ApprovalLevel.L1_USER_CONFIRMATION, listOf(ApprovalRecord(rep, 0)))
        assertEquals(SodCode.SATISFIED, code)
    }

    @Test
    fun initiatorCannotApproveL3() {
        val code = sod.check(manager.id, tenant, ApprovalLevel.L3_MANAGER, listOf(ApprovalRecord(manager, 0)))
        assertEquals(SodCode.SAME_ACTOR, code)
    }

    @Test
    fun managerApprovingRepSatisfiesL3() {
        val code = sod.check(rep.id, tenant, ApprovalLevel.L3_MANAGER, listOf(ApprovalRecord(manager, 0)))
        assertEquals(SodCode.SATISFIED, code)
    }

    @Test
    fun l4RequiresTwoApproversNeitherInitiator() {
        val one = sod.check(rep.id, tenant, ApprovalLevel.L4_DUAL, listOf(ApprovalRecord(manager, 0)))
        assertEquals(SodCode.NEED_SECOND_APPROVER, one)
        val two = sod.check(
            rep.id,
            tenant,
            ApprovalLevel.L4_DUAL,
            listOf(ApprovalRecord(manager, 0), ApprovalRecord(finance, 1)),
        )
        assertEquals(SodCode.SATISFIED, two)
    }

    @Test
    fun otherTenantApproverIsRejected() {
        val outsider = manager.copy(tenantId = TenantId("other"))
        val code = sod.check(rep.id, tenant, ApprovalLevel.L3_MANAGER, listOf(ApprovalRecord(outsider, 0)))
        assertEquals(SodCode.TENANT_MISMATCH, code)
    }

    private fun approval(amount: Money) =
        policy.evaluate(request(rep, ToolName.CREATE_DRAFT_ORDER, amount)).approval

    private fun request(
        actor: Actor,
        tool: ToolName,
        amount: Money?,
        destructive: Boolean = tool.destructive,
    ) = PolicyRequest(actor, tool, amount, destructive, customerBlocked = false, exceedsCredit = false)
}
