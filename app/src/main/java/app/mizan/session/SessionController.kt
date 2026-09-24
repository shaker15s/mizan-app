package app.mizan.session

import app.mizan.domain.model.Actor
import app.mizan.domain.model.ActorId
import app.mizan.domain.model.Role
import app.mizan.domain.model.SessionMode
import app.mizan.domain.model.TenantContext
import app.mizan.domain.model.TenantId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

data class WorkspaceSession(
    val actor: Actor,
    val tenant: TenantContext,
    val mode: SessionMode,
    val expiresAt: Instant?,
)

class SessionController {
    private val _session = MutableStateFlow<WorkspaceSession?>(null)
    val session: StateFlow<WorkspaceSession?> = _session.asStateFlow()

    fun open(session: WorkspaceSession) {
        _session.value = session
    }

    fun updateActor(actor: Actor) {
        val current = _session.value ?: return
        _session.value = current.copy(actor = actor, tenant = current.tenant.copy(id = actor.tenantId))
    }

    fun updateTenant(tenant: TenantContext, actor: Actor) {
        _session.value = _session.value?.copy(tenant = tenant, actor = actor)
    }

    fun clear() {
        _session.value = null
    }

    /** True when a known expiry has passed and the session was cleared. */
    fun expireIfNeeded(now: Instant): Boolean {
        val expiry = _session.value?.expiresAt ?: return false
        if (expiry.isAfter(now)) return false
        _session.value = null
        return true
    }

    companion object {
        fun roleOf(wire: String): Role = runCatching { Role.valueOf(wire) }.getOrDefault(Role.OPERATOR)

        fun actor(id: String, name: String, role: Role, tenant: String) =
            Actor(ActorId(id), name, role, TenantId(tenant))
    }
}
