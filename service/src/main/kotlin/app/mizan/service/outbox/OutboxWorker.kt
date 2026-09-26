package app.mizan.service.outbox

/**
 * The dispatcher for retryable work.
 *
 * It is deliberately synchronous and single-threaded per pass: the caller
 * decides when to run a pass (a scheduler, a test, an operator's button), and
 * every pass is a short, bounded, observable unit of work. A background thread
 * that nobody can run in a test is a background thread nobody can prove.
 *
 * Three properties are enforced here rather than hoped for:
 *
 *  - an entry is marked in-flight before the work is attempted, so a crash
 *    leaves it claimable rather than lost;
 *  - a failure schedules the next attempt with a growing delay, and after
 *    [Outbox.MAX_ATTEMPTS] the entry is dead-lettered so a person is asked
 *    instead of the ERP being hammered;
 *  - a success is recorded before the next entry is touched, so a crash after
 *    a successful dispatch does not re-dispatch it if the work is idempotent.
 */
class OutboxWorker(
    private val outbox: Outbox,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxAttempts: Int = Outbox.MAX_ATTEMPTS,
) {

    /** What a single dispatch attempt produced. */
    sealed interface Dispatch {

        /** The work happened. */
        data object Done : Dispatch

        /**
         * The work could not happen and may be attempted again. The reason is
         * an error-taxonomy code, never a sentence.
         */
        data class Retry(val errorCode: String, val delayMillis: Long = 0L) : Dispatch

        /**
         * The work must not be attempted again: it is no longer meaningful, or
         * it must be decided by a person.
         */
        data class Stop(val errorCode: String) : Dispatch
    }

    data class Pass(
        val attempted: Int,
        val done: Int,
        val retried: Int,
        val stopped: Int,
        val deadLettered: Int,
    ) {
        val ran: Boolean get() = attempted > 0
    }

    /**
     * Runs one pass over the due entries.
     *
     * [dispatch] receives the entry and returns what happened. It must be
     * idempotent for its own key: this class cannot make a duplicate ERP write
     * safe, it can only avoid asking twice.
     */
    fun runOnce(
        limit: Int = 20,
        dispatch: (Outbox.Entry) -> Dispatch,
    ): Pass {
        val due = outbox.due(clock(), limit)
        var done = 0
        var retried = 0
        var stopped = 0
        var dead = 0
        for (entry in due) {
            outbox.markInFlight(entry.id)
            val outcome = try {
                dispatch(entry)
            } catch (_: Throwable) {
                // A dispatcher that throws must not strand the entry in flight.
                Dispatch.Retry("OUTBOX_DISPATCH_ERROR")
            }
            val now = clock()
            when (outcome) {
                is Dispatch.Done -> {
                    outbox.markDone(entry.id)
                    done++
                }
                is Dispatch.Retry -> {
                    val updated = outbox.markFailed(
                        id = entry.id,
                        errorCode = outcome.errorCode,
                        nowMillis = now + outcome.delayMillis,
                        maxAttempts = maxAttempts,
                    )
                    if (updated?.state == Outbox.State.DEAD_LETTERED) dead++ else retried++
                }
                is Dispatch.Stop -> {
                    outbox.markFailed(
                        id = entry.id,
                        errorCode = outcome.errorCode,
                        nowMillis = now,
                        // One attempt, and it is over: an entry that will never
                        // succeed must not consume the retry budget of one
                        // that might.
                        maxAttempts = entry.attempts + 1,
                    )
                    stopped++
                }
            }
        }
        return Pass(due.size, done, retried, stopped, dead)
    }

    /**
     * Puts back every entry that a dead worker left in flight.
     *
     * Called at startup, before the first pass: an entry is only in flight
     * because a process was working on it, and no process survives a restart.
     */
    fun recoverInFlight(): Int {
        var released = 0
        for (entry in outbox.all().filter { it.state == Outbox.State.IN_FLIGHT }) {
            outbox.release(entry.id, clock())
            released++
        }
        return released
    }
}
