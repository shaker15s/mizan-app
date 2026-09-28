package app.mizan.service.concurrency

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Mutual exclusion keyed by identity, for the critical sections that span more
 * than one store call.
 *
 * Every store in this service is synchronized per method, which is the right
 * granularity for a map and the wrong granularity for a decision. An approval
 * is read, judged and written across three calls; so is an idempotency key; so
 * is the consumption of an approval by an execution. Two requests that
 * interleave inside one of those sequences both read the same "before" state
 * and both write their own "after" state, and the service that results is not
 * slow or confused -- it is *wrong in a way that looks like success*: two
 * grants where one was possible, two draft orders where the person asked once.
 *
 * The alternative to a lock is a compare-and-set at the store level, which for
 * an append-only log means a version field on every record and a retry loop in
 * every caller. That is the better design for a distributed deployment, and
 * this service is one process (or several replicas behind a database that
 * already serialises writes); what it needs here is a lock that does not
 * serialise unrelated tenants, and this is that lock.
 *
 * Two properties matter beyond mutual exclusion:
 *
 *  - **nothing leaks.** A lock is held only while its key is in use and removed
 *    when the last holder leaves, so a service that has served a million
 *    executions does not carry a million locks. The reference count is what
 *    makes the removal safe: the map entry is dropped only when no thread holds
 *    it and none is waiting.
 *  - **the key is always the same string** for the same protected resource, so
 *    two callers cannot lock two different objects for one resource. [keys]
 *    builds the names in one place for that reason.
 */
class KeyedLocks {

    private class Entry {
        val lock = ReentrantLock()
        var users = 0
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    /** How many locks are currently in the table. For tests, not for callers. */
    val liveCount: Int get() = entries.size

    fun <T> withLock(key: String, block: () -> T): T {
        val entry = entries.compute(key) { _, existing ->
            val held = existing ?: Entry()
            held.users += 1
            held
        }!!
        entry.lock.lock()
        try {
            return block()
        } finally {
            entry.lock.unlock()
            entries.computeIfPresent(key) { _, held ->
                held.users -= 1
                if (held.users <= 0) null else held
            }
        }
    }

    /**
     * Acquires several keys in a fixed order and runs the block inside all of
     * them.
     *
     * Order is not a detail: two callers that need the same pair of locks and
     * take them in different orders are a deadlock waiting for a busy day. The
     * keys are sorted here, once, so no caller has to remember the order.
     */
    fun <T> withLocks(keys: List<String>, block: () -> T): T {
        val ordered = keys.distinct().sorted()
        return acquire(ordered, 0, block)
    }

    private fun <T> acquire(keys: List<String>, index: Int, block: () -> T): T {
        if (index == keys.size) return block()
        return withLock(keys[index]) { acquire(keys, index + 1, block) }
    }

    companion object {
        /** A section that decides whether an approval may be answered. */
        fun approval(approvalId: String): String = "approval\u0000$approvalId"

        /** A section that decides whether an execution may run. */
        fun execution(tenantId: String, executionId: String): String = "execution\u0000$tenantId\u0000$executionId"

        /** A section that decides what an idempotency key already means. */
        fun idempotency(tenantId: String, key: String): String = "idempotency\u0000$tenantId\u0000$key"
    }
}
