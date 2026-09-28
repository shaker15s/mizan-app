package app.mizan.service

import app.mizan.service.concurrency.KeyedLocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The lock the service uses for its read-judge-write sections.
 *
 * A lock is the kind of code that passes every test it is not asked about: it
 * is only wrong when two callers overlap, or when nobody calls it and it leaks,
 * or when two callers take two locks in two orders. Each of those is checked
 * here, because the alternative is finding out in production with a hung
 * request or a lost approval.
 */
class KeyedLocksTest {

    @Test
    fun theSameKeyNeverRunsTwiceAtOnce() {
        val locks = KeyedLocks()
        val inside = AtomicInteger(0)
        val overlapped = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(32)
        repeat(32) {
            pool.execute {
                start.await()
                locks.withLock("approval\u0000APR-1") {
                    if (inside.incrementAndGet() > 1) overlapped.incrementAndGet()
                    Thread.sleep(2)
                    inside.decrementAndGet()
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("every holder must finish", done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals("two holders of one key were inside the section together", 0, overlapped.get())
    }

    @Test
    fun differentKeysDoNotWaitForEachOther() {
        // The point of keying the lock: a slow approval in one tenant must not
        // hold up an approval in another. Both sections are entered and held
        // at the same time, which a single global lock could not do.
        val locks = KeyedLocks()
        val held = CountDownLatch(2)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        repeat(2) { index ->
            pool.execute {
                locks.withLock("approval\u0000APR-$index") {
                    held.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                finished.countDown()
            }
        }
        assertTrue("both keys must be held at the same time", held.await(5, TimeUnit.SECONDS))
        release.countDown()
        assertTrue("both callers finish", finished.await(5, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals("both sections completed", 0, finished.count)
        assertEquals("and left nothing behind", 0, locks.liveCount)
    }

    @Test
    fun aLockIsReleasedAndNotKept() {
        val locks = KeyedLocks()
        repeat(500) { index -> locks.withLock("execution\u0000t\u0000EXE-$index") { index } }
        assertEquals("a lock per request would leak for the life of the process", 0, locks.liveCount)
    }

    @Test
    fun twoLocksInEitherOrderDoNotDeadlock() {
        // `withLocks` sorts the keys, so a caller that asks for (a, b) and a
        // caller that asks for (b, a) acquire them in the same order. Without
        // that, this test does not fail -- it hangs.
        val locks = KeyedLocks()
        val done = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        pool.execute {
            locks.withLocks(listOf("a", "b")) { Thread.sleep(20) }
            done.countDown()
        }
        pool.execute {
            locks.withLocks(listOf("b", "a")) { Thread.sleep(20) }
            done.countDown()
        }
        assertTrue("a pair of locks taken in two orders must not deadlock", done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
    }

    @Test
    fun aFailureInsideTheSectionStillReleasesTheLock() {
        val locks = KeyedLocks()
        runCatching { locks.withLock("approval\u0000APR-1") { error("the decision threw") } }
        assertEquals("a thrown decision must not keep the key", 0, locks.liveCount)
        val ran = locks.withLock("approval\u0000APR-1") { true }
        assertTrue("the key is usable again", ran)
    }
}
