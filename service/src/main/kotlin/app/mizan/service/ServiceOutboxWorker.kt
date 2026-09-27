package app.mizan.service

import app.mizan.domain.model.ConnectorCapabilities
import app.mizan.service.authority.ServiceAuthority
import app.mizan.service.json.Json
import app.mizan.service.json.JsonValue
import app.mizan.service.outbox.Outbox
import app.mizan.service.outbox.OutboxWorker
import app.mizan.service.protocol.ExecutionOutcome
import app.mizan.service.protocol.ExecutionRequest
import app.mizan.service.security.ServiceUser
import app.mizan.service.store.ServiceStores

/**
 * Retries the reads that the ERP could not answer.
 *
 * The service forms the intention (an outbox entry, written to the same
 * durable log as the journal) and this class is the only thing that acts on
 * it. Three rules shape the implementation:
 *
 *  - only reads are ever re-dispatched. An entry naming a mutating tool is
 *    stopped rather than run, because re-dispatching a write is how a duplicate
 *    order is created by a scheduler;
 *  - a retry re-enters the authority with the same identity and the same
 *    idempotency key, so the second attempt goes through policy, approval, the
 *    journal and the read back exactly like the first one did;
 *  - a refusal that would not change later (policy, contract, a missing
 *    record) stops the entry. Retrying it would only produce the same answer
 *    and hide the fact that a person has to look at it.
 */
class ServiceOutboxWorker(
    private val stores: ServiceStores,
    private val authority: ServiceAuthority,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val capabilities: ConnectorCapabilities? = null,
) {

    private val worker = OutboxWorker(stores.outbox, clock)

    /** Puts back whatever a dead worker left in flight. Call before [runOnce]. */
    fun recover(): Int = worker.recoverInFlight()

    /** Runs one pass and returns what happened, so a caller can log or assert it. */
    fun runOnce(limit: Int = 20, users: List<ServiceUser>): OutboxWorker.Pass =
        worker.runOnce(limit) { entry -> dispatch(entry, users) }

    private fun dispatch(entry: Outbox.Entry, users: List<ServiceUser>): OutboxWorker.Dispatch {
        if (!entry.tool.readOnly) {
            // Never re-dispatch a write. It would be a second ERP record with a
            // scheduler's blessing.
            return OutboxWorker.Dispatch.Stop("OUTBOX_WRITE_NOT_RETRYABLE")
        }
        if (capabilities != null && !capabilities.supports(entry.tool)) {
            return OutboxWorker.Dispatch.Stop("TOOL_NOT_SUPPORTED_BY_ERP")
        }
        val user = users.firstOrNull { it.tenantId == entry.tenantId && it.actorId == entry.actorId }
            ?: return OutboxWorker.Dispatch.Stop("OUTBOX_ACTOR_UNKNOWN")
        return when (val outcome = authority.decide(requestOf(entry), user, simulateAmbiguous = false)) {
            is ExecutionOutcome.Accepted, is ExecutionOutcome.Verified -> OutboxWorker.Dispatch.Done
            is ExecutionOutcome.Failed -> when (outcome.messageCode) {
                // Still unreachable, still rate limited, or the port has no
                // binding yet: ask again later.
                "ERP_UNAVAILABLE", "ERP_RATE_LIMITED", "ERP_UNKNOWN_ANSWER", "ERP_BINDING_MISSING" ->
                    OutboxWorker.Dispatch.Retry(outcome.messageCode)
                // The ERP answered, and asking again will not change the
                // answer: a person has to look at this.
                else -> OutboxWorker.Dispatch.Stop(outcome.messageCode)
            }
            is ExecutionOutcome.Ambiguous -> OutboxWorker.Dispatch.Stop("OUTBOX_AMBIGUOUS")
            is ExecutionOutcome.Rejected -> when (outcome.httpStatus) {
                429 -> OutboxWorker.Dispatch.Retry(
                    outcome.messageCode,
                    outcome.retryAfterSeconds.coerceAtLeast(0L) * 1000L,
                )
                503 -> OutboxWorker.Dispatch.Retry(outcome.messageCode)
                // Policy, tenant and contract refusals do not become true later.
                else -> OutboxWorker.Dispatch.Stop(outcome.messageCode)
            }
        }
    }

    private fun requestOf(entry: Outbox.Entry): ExecutionRequest = ExecutionRequest(
        executionId = entry.executionId,
        traceId = "outbox-" + entry.id,
        tenantId = entry.tenantId,
        toolWire = entry.tool.wire,
        toolVersion = entry.tool.version,
        proposalId = null,
        arguments = (Json.parseOrNull(entry.arguments) as? JsonValue.Obj) ?: Json.obj(),
        approverId = null,
        idempotencyKey = entry.idempotencyKey.takeIf { it.isNotBlank() },
    )

    /** What is waiting, for an operator surface. */
    fun pending(): List<Outbox.Entry> = stores.outbox.pending()

    fun deadLettered(): List<Outbox.Entry> = stores.outbox.deadLettered()
}
