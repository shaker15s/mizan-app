package app.mizan.service.security

import app.mizan.domain.model.Digests
import app.mizan.domain.model.Role
import java.security.SecureRandom
import java.util.Base64

data class ServiceUser(
    val email: String,
    val passwordHash: String,
    val actorId: String,
    val displayName: String,
    val role: Role,
    val tenantId: String,
    val tenantLabel: String,
) {
    companion object {
        /** Builds a user from a plain password and stores only the derived key. */
        fun of(
            email: String,
            password: String,
            actorId: String,
            displayName: String,
            role: Role,
            tenantId: String,
            tenantLabel: String,
        ): ServiceUser = ServiceUser(
            email = email.trim().lowercase(),
            passwordHash = PasswordHash.hash(password),
            actorId = actorId,
            displayName = displayName,
            role = role,
            tenantId = tenantId,
            tenantLabel = tenantLabel,
        )
    }
}

data class ServiceSession(
    val tokenFingerprint: String,
    val user: ServiceUser,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)

/**
 * Issues and resolves short-lived session tokens.
 *
 * Only the SHA-256 of a token is kept, so a memory dump of the service does
 * not hand over a usable credential. Expired sessions are evicted on lookup
 * and on issue, and an expired token is never silently extended.
 */
class SessionRegistry(
    private val ttlMillis: Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val random = SecureRandom()
    private val sessions = HashMap<String, ServiceSession>()

    @Synchronized
    fun issue(user: ServiceUser): Pair<String, ServiceSession> {
        evictExpired(clock())
        val token = newToken()
        val now = clock()
        val session = ServiceSession(
            tokenFingerprint = Digests.sha256(token).take(16),
            user = user,
            issuedAtEpochMillis = now,
            expiresAtEpochMillis = now + ttlMillis,
        )
        sessions[Digests.sha256(token)] = session
        return token to session
    }

    @Synchronized
    fun resolve(token: String?): ServiceSession? {
        if (token.isNullOrBlank()) return null
        val now = clock()
        evictExpired(now)
        val session = sessions[Digests.sha256(token)] ?: return null
        if (session.expiresAtEpochMillis <= now) {
            sessions.remove(Digests.sha256(token))
            return null
        }
        return session
    }

    @Synchronized
    fun revoke(token: String) {
        sessions.remove(Digests.sha256(token))
    }

    @Synchronized
    fun activeCount(): Int {
        evictExpired(clock())
        return sessions.size
    }

    private fun evictExpired(now: Long) {
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.expiresAtEpochMillis <= now) iterator.remove()
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val DEFAULT_TTL_MILLIS = 15L * 60L * 1000L
    }
}

/**
 * Counts failed sign-in attempts per account. This is a reference service:
 * the counter lives in memory and a restart clears it. A real deployment
 * keeps it next to the identity store, not in this class.
 */
class LoginThrottle(
    private val maxAttempts: Int = 5,
    private val lockoutMillis: Long = 5L * 60L * 1000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val failures = HashMap<String, Attempt>()

    @Synchronized
    fun isLocked(email: String): Boolean {
        val attempt = failures[email.trim().lowercase()] ?: return false
        if (attempt.unlockAtEpochMillis <= clock()) {
            failures.remove(email.trim().lowercase())
            return false
        }
        return attempt.count >= maxAttempts
    }

    @Synchronized
    fun recordFailure(email: String) {
        val key = email.trim().lowercase()
        val previous = failures[key]
        val count = (previous?.count ?: 0) + 1
        failures[key] = Attempt(count, clock() + lockoutMillis)
    }

    @Synchronized
    fun recordSuccess(email: String) {
        failures.remove(email.trim().lowercase())
    }

    private data class Attempt(val count: Int, val unlockAtEpochMillis: Long)
}
