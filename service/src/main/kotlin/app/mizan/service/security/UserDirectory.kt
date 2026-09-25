package app.mizan.service.security

/** Read-only view of the accounts the service will authenticate. */
class UserDirectory(
    users: List<ServiceUser>,
    private val throttle: LoginThrottle = LoginThrottle(),
) {
    private val byEmail = LinkedHashMap<String, ServiceUser>()
    private val byActor = LinkedHashMap<String, ServiceUser>()

    init {
        users.forEach { user ->
            byEmail[user.email] = user
            byActor[index(user.tenantId, user.actorId)] = user
        }
    }

    /** Returns null for a wrong password, an unknown account, or a locked account. */
    @Synchronized
    fun signIn(email: String, password: String): ServiceUser? {
        val key = email.trim().lowercase()
        if (throttle.isLocked(key)) return null
        val user = byEmail[key] ?: return null
        if (!PasswordHash.verify(password, user.passwordHash)) {
            throttle.recordFailure(key)
            return null
        }
        throttle.recordSuccess(key)
        return user
    }

    fun find(tenantId: String, actorId: String): ServiceUser? = byActor[index(tenantId, actorId)]

    fun all(): List<ServiceUser> = byEmail.values.toList()

    fun isLocked(email: String): Boolean = throttle.isLocked(email.trim().lowercase())

    private fun index(tenantId: String, actorId: String) = "$tenantId::$actorId"
}
