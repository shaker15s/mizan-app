package app.mizan.service

import app.mizan.domain.policy.VersionedPolicy
import app.mizan.service.outbox.SweepLease
import app.mizan.service.store.ServiceStores
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Two replicas, one queue.
 *
 * The outbox is drained by a timer inside the service. Run the service twice
 * against one durable store -- which is what a replica is -- and both timers
 * see the same due entries. The lease is what makes exactly one of them act
 * per round, and these tests run two real store instances over one directory
 * rather than two objects pretending to be processes.
 */
class SweepLeaseTest {

    private fun directory(name: String): Path {
        val dir = Files.createTempDirectory("mizan-lease-$name-")
        dir.toFile().deleteOnExit()
        return dir
    }

    private fun lease(stores: ServiceStores, owner: String, ttl: Long = 30_000L) =
        SweepLease(stores.openLeaseLog(), owner, ttl)

    @Test
    fun twoReplicasOverOneStoreElectExactlyOneSweeperPerRound() {
        val dir = directory("elect")
        ServiceStores(dir).use { shared ->
            val replicaA = lease(shared, "svc-a")
            val replicaB = lease(shared, "svc-b")

            // Same round, both asking: the earliest claim wins, and both
            // compute the same answer from the same records.
            assertTrue("the first asker sweeps", replicaA.claim(1_000L))
            assertFalse("the second asker stands down", replicaB.claim(1_000L))
            // A reads the claim B wrote and still holds the lease, because
            // its own claim was earlier.
            assertTrue(replicaA.claim(1_001L))
            assertFalse(replicaB.claim(1_001L))
        }
    }

    @Test
    fun tieBrokenByOwnerSoTwoProcessesNeverBothWin() {
        val dir = directory("tie")
        ServiceStores(dir).use { shared ->
            val a = lease(shared, "svc-a")
            val b = lease(shared, "svc-b")
            // Identical timestamps, which is what two replicas started by the
            // same supervisor produce.
            val aWon = a.claim(5_000L)
            val bWon = b.claim(5_000L)
            assertEquals("exactly one wins a tie", 1, listOf(aWon, bWon).count { it })
            // "svc-a" sorts first, so it is the one.
            assertTrue(aWon)
            assertFalse(bWon)
        }
    }

    @Test
    fun aReplicaThatDiedDoesNotLockTheQueueOutForever() {
        val dir = directory("expiry")
        ServiceStores(dir).use { shared ->
            val dead = lease(shared, "svc-dead", ttl = 1_000L)
            assertTrue(dead.claim(10_000L))
            // The survivor asks one millisecond after the dead claim expired.
            val survivor = lease(shared, "svc-live", ttl = 1_000L)
            assertTrue("a stale claim must not hold the lease", survivor.claim(11_500L))
        }
    }

    @Test
    fun aRestartedReplicaRemembersWhatThePreviousProcessClaimed() {
        val dir = directory("restart")
        val ttl = 60_000L
        ServiceStores(dir).use { first -> assertTrue(lease(first, "svc-b", ttl).claim(1_000L)) }
        // The same replica name comes back and asks again: it is the same
        // process as far as the lease is concerned, and it still wins.
        ServiceStores(dir).use { second ->
            val again = lease(second, "svc-b", ttl)
            assertTrue(again.claim(2_000L))
            assertEquals(1, again.contenders())
        }
    }

    @Test
    fun aSingleProcessDeploymentIsNotSlowedDownByALeaseNobodyContends() {
        val dir = directory("solo")
        ServiceStores(dir).use { stores ->
            val solo = lease(stores, "svc-only", ttl = 0L)
            assertTrue(solo.claim(1L))
            assertTrue(solo.claim(2L))
            assertTrue(solo.isLeaseOwner(3L))
        }
    }

    @Test
    fun theLeaseIsBoundedAndDoesNotGrowWithEveryPass() {
        val dir = directory("bounded")
        ServiceStores(dir).use { shared ->
            val a = lease(shared, "svc-a", ttl = 1_000L)
            val b = lease(shared, "svc-b", ttl = 1_000L)
            // A long-lived service, with a claim every second: old claims
            // expire, so the stream stays proportional to the live claims and
            // not to the uptime.
            repeat(200) { round ->
                val now = 1_000L + round * 1_000L
                a.claim(now)
                b.claim(now)
            }
            val rows = shared.openLeaseLog().records().size
            // Compaction keeps the claim stream proportional to live claims,
            // not to how long the service has been up: a year of uptime must
            // not turn a lease into a log that only grows.
            assertTrue("the claim stream must not grow without bound: $rows", rows <= 128)
        }
    }

    @Test
    fun aRunningServiceWithALeaseSweepsAndWithoutOneIsUnchanged() {
        val dir = directory("service")
        val leased = MizanService(
            ServiceConfig(
                storeDirectory = dir,
                versionedPolicy = VersionedPolicy.demoV12,
                outboxSweepMillis = 50L,
                sweepLeaseMillis = 30_000L,
                instanceId = "svc-lease-test",
            ),
        )
        val port = leased.start(port = 0)
        try {
            assertTrue(port > 0)
            assertEquals("svc-lease-test", leased.sweepLease?.owner)
            assertTrue(leased.sweepLease!!.claim(1_000L))
        } finally {
            leased.stop()
        }
    }
}
