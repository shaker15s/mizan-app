package app.mizan.service.security

/**
 * How many calls of one kind are allowed in one window.
 *
 * A budget is a data class rather than a constant so a deployment can size it:
 * a single-tenant pilot and a thousand-tenant instance do not deserve the same
 * allowance, and a test that wants to prove a refusal must be able to ask for a
 * small one without sleeping for a minute.
 */
data class Budget(val limit: Int, val windowMillis: Long) {
    init {
        require(limit > 0) { "a budget must allow at least one call" }
        require(windowMillis > 0) { "a budget needs a window" }
    }

    /** A tighter version of the same budget, for a tenant with no trust yet. */
    fun stricter(factor: Int = 3): Budget = copy(limit = (limit / factor).coerceAtLeast(1))
}

/**
 * Where a limit applies.
 *
 * The surfaces are deliberately few. Every one of them is something an
 * attacker can turn into load: guessing passwords, scanning the catalogue,
 * asking for proposals, executing writes, resolving cases, enrolling devices.
 */
enum class LimitSurface(val defaultBudget: Budget) {
    /** Failed sign-in attempts only. A correct password never costs budget. */
    SIGN_IN(Budget(limit = 30, windowMillis = 60_000)),
    SEARCH(Budget(limit = 600, windowMillis = 60_000)),
    PROPOSAL(Budget(limit = 300, windowMillis = 60_000)),
    EXECUTION(Budget(limit = 240, windowMillis = 60_000)),
    RECONCILIATION(Budget(limit = 120, windowMillis = 60_000)),
    DEVICE(Budget(limit = 30, windowMillis = 60_000)),
    ;

    /**
     * A budget for a proof, not for a person: used by tests that must see the
     * refusal, and by a deployment that has just opened a tenant.
     */
    fun strictBudget(): Budget = when (this) {
        SIGN_IN -> Budget(3, 60_000)
        SEARCH -> Budget(6, 60_000)
        PROPOSAL -> Budget(4, 60_000)
        EXECUTION -> Budget(3, 60_000)
        RECONCILIATION -> Budget(4, 60_000)
        DEVICE -> Budget(3, 60_000)
    }

    companion object {
        /** The budgets a real deployment starts from. */
        val defaultBudgets: Map<LimitSurface, Budget> = entries.associateWith { it.defaultBudget }

        /** Budgets low enough to observe a refusal in a test. */
        val strictBudgets: Map<LimitSurface, Budget> = entries.associateWith { it.strictBudget() }
    }
}

data class RateDecision(
    val allowed: Boolean,
    val remaining: Int,
    val retryAfterMillis: Long,
    val reasonCode: String,
) {
    /** The response header a client should honour. */
    val retryAfterSeconds: Long get() = if (retryAfterMillis <= 0L) 0L else (retryAfterMillis + 999L) / 1000L

    companion object {
        val unlimited = RateDecision(true, Int.MAX_VALUE, 0, "RATE_OK")
    }
}

/**
 * Token buckets, one per key, with a budget per surface.
 *
 * A sign-in attempt, a search and an ERP write do not deserve the same
 * allowance. The plan asks for limits on login, proposals, executions and
 * search, sized per actor, per tenant and per address, and for the refusals to
 * be counted so credential stuffing and tool flooding show up as a pattern
 * rather than as noise.
 *
 * Two entry points matter:
 *
 * * [peek] answers "would this be allowed" without spending anything, which is
 *   what a sign-in needs: the budget is spent by a *failed* attempt, never by
 *   a person who typed the right password.
 * * [consume] spends one call and is what every other surface uses.
 *
 * The limiter is deliberately simple: a bucket per key, refilled at a fixed
 * rate. It is not a quota service, and a deployment with more than one
 * instance puts this in front of a shared store.
 */
class RateLimiter(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val enabled: Boolean = true,
    private val budgets: Map<LimitSurface, Budget> = LimitSurface.defaultBudgets,
) {

    private class Bucket(var tokens: Double, var updatedAtMillis: Long)

    private val buckets = LinkedHashMap<String, Bucket>()
    private var refusals = 0L

    /** Would this call be allowed? Nothing is spent. */
    @Synchronized
    fun peek(surface: LimitSurface, vararg keys: String): RateDecision {
        if (!enabled) return RateDecision.unlimited
        val now = clock()
        var worst: RateDecision = RateDecision.unlimited
        for (key in keys.filter { it.isNotBlank() }) {
            val decision = refill(surface, key, now, spend = false)
            if (!decision.allowed) return decision
            if (decision.remaining < worst.remaining) worst = decision
        }
        return worst
    }

    /** Spends one call against every key. All of them must have budget. */
    @Synchronized
    fun consume(surface: LimitSurface, vararg keys: String): RateDecision {
        if (!enabled) return RateDecision.unlimited
        val now = clock()
        val effective = keys.filter { it.isNotBlank() }
        // Check every key before spending on any of them, so a refusal on the
        // second key does not silently eat the first key's budget.
        for (key in effective) {
            val decision = refill(surface, key, now, spend = false)
            if (!decision.allowed) return decision
        }
        var worst: RateDecision = RateDecision.unlimited
        for (key in effective) {
            val decision = refill(surface, key, now, spend = true)
            if (decision.remaining < worst.remaining) worst = decision
        }
        return worst
    }

    /**
     * Gives a spent call back. Used when work that was debited turned out to
     * succeed because the caller was legitimate.
     */
    @Synchronized
    fun refund(surface: LimitSurface, vararg keys: String) {
        if (!enabled) return
        val budget = budgets[surface] ?: surface.defaultBudget
        for (key in keys.filter { it.isNotBlank() }) {
            val bucket = buckets[bucketKey(surface, key)] ?: continue
            bucket.tokens = (bucket.tokens + 1.0).coerceAtMost(budget.limit.toDouble())
        }
    }

    @Synchronized
    fun refusalCount(): Long = refusals

    /** Remaining allowance per bucket. Reported so an operator can see the shape. */
    @Synchronized
    fun snapshot(): Map<String, Int> =
        buckets.entries.associate { (key, bucket) -> key to bucket.tokens.toInt() }

    /** Forgets every bucket. A limiter with no clock reset is unusable in a test. */
    @Synchronized
    fun reset() {
        buckets.clear()
        refusals = 0L
    }

    private fun refill(surface: LimitSurface, key: String, now: Long, spend: Boolean): RateDecision {
        val budget = budgets[surface] ?: surface.defaultBudget
        val token = bucketKey(surface, key)
        val bucket = buckets.getOrPut(token) { Bucket(budget.limit.toDouble(), now) }
        val elapsed = (now - bucket.updatedAtMillis).coerceAtLeast(0L)
        val refill = budget.limit.toDouble() * elapsed / budget.windowMillis.toDouble()
        bucket.tokens = (bucket.tokens + refill).coerceAtMost(budget.limit.toDouble())
        bucket.updatedAtMillis = now
        if (bucket.tokens < 1.0) {
            refusals++
            val needed = (1.0 - bucket.tokens) * budget.windowMillis / budget.limit
            return RateDecision(
                allowed = false,
                remaining = 0,
                retryAfterMillis = needed.toLong().coerceAtLeast(1_000L),
                reasonCode = "RATE_LIMITED_" + surface.name,
            )
        }
        if (spend) bucket.tokens -= 1.0
        return RateDecision(true, bucket.tokens.toInt(), 0, "RATE_OK")
    }

    private fun bucketKey(surface: LimitSurface, key: String) = surface.name + "::" + key
}
