package app.mizan.service

import app.mizan.service.outbox.SweepLease
import app.mizan.service.security.ServiceUser

/**
 * The timer that drains the retry queue.
 *
 * A queue nobody drains is a log file with extra steps, so the service that
 * writes the entries is also the one that takes them back out. The thread is
 * a daemon and does exactly one short pass per interval: no pass holds a lock
 * a request needs, and a failure during a pass is counted, never fatal.
 *
 * It exists as a class rather than a method on the service because of the
 * lease. A deployment that runs more than one replica must have exactly one
 * sweeper per round, and the claim that decides which one is shared state;
 * keeping the timer, the claim and the pass in one small object is what makes
 * "who is sweeping" a question with an answer.
 */
class OutboxSweeper(
    private val worker: ServiceOutboxWorker?,
    private val lease: SweepLease?,
    private val users: () -> List<ServiceUser>,
    private val intervalMillis: Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private var thread: Thread? = null

    /** True when a pass would run: a worker, an interval, and a claim to take. */
    val running: Boolean get() = thread != null

    @Synchronized
    fun start() {
        // Captured once: the null checks and the thread body must agree about
        // what is running, and a property read inside a lambda would not.
        val activeWorker = worker ?: return
        val activeLease = lease ?: return
        if (intervalMillis <= 0L) return
        if (thread != null) return
        val created = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(intervalMillis)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                // A replica that loses the claim does nothing this round and
                // asks again next round, which is how one read stops being
                // dispatched twice by two timers.
                if (activeLease.claim(clock())) {
                    runCatching { activeWorker.runOnce(users = users()) }
                }
            }
        }, "mizan-outbox-sweeper")
        created.isDaemon = true
        created.start()
        thread = created
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
    }
}
