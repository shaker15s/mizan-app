package app.mizan

import app.mizan.domain.model.Actor
import app.mizan.domain.model.TenantContext
import app.mizan.domain.model.TenantId
import app.mizan.graph.AppGraph

@Suppress("UNUSED_PARAMETER")
fun simulationActors(tenantId: TenantId): List<Actor> = emptyList()

@Suppress("UNUSED_PARAMETER")
fun simulationEntry(): Pair<Actor, TenantContext>? = null

@Suppress("UNUSED_PARAMETER")
suspend fun onSimulationEntered(graph: AppGraph, tenantId: TenantId) = Unit
