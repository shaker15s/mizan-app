package app.mizan

import app.mizan.domain.model.Actor
import app.mizan.domain.model.TenantContext
import app.mizan.domain.model.TenantId
import app.mizan.graph.AppGraph

/**
 * The seam that keeps the simulator out of a real build.
 *
 * Only `src/demo` implements this with content. Staging and production
 * implementations are empty, so no screen can reach a simulated actor,
 * tenant, or ledger row in a build that talks to the service. A screen that
 * forgets to check [isAvailable] simply shows nothing instead of inventing
 * an ERP state.
 */
interface SimulationDirectory {

    /** False outside the demo flavor, always. */
    val isAvailable: Boolean

    fun tenants(): List<TenantContext>

    fun actors(tenantId: TenantId): List<Actor>

    /** The actor and workspace a "enter simulation" button opens. */
    fun entry(): Pair<Actor, TenantContext>?

    /** Writes the labeled local ledger once, and only in the demo flavor. */
    suspend fun seed(graph: AppGraph, tenantId: TenantId)
}

object NoSimulationDirectory : SimulationDirectory {
    override val isAvailable: Boolean = false
    override fun tenants(): List<TenantContext> = emptyList()
    override fun actors(tenantId: TenantId): List<Actor> = emptyList()
    override fun entry(): Pair<Actor, TenantContext>? = null
    override suspend fun seed(graph: AppGraph, tenantId: TenantId) = Unit
}
