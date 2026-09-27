package app.mizan.service.outbox

import app.mizan.service.json.Json
import app.mizan.service.json.asLong
import app.mizan.service.json.asObject
import app.mizan.service.json.field
import app.mizan.service.json.text
import app.mizan.service.store.RecordLog

/**
 * One sweeper per deployment, not one per process.
 *
 * The outbox is re-dispatched by whichever worker gets there first, and the
 * worker is a timer inside the service. Run two replicas against one durable
 * store -- which is the whole point of a replica -- and both timers fire, both
 * read the same due entries, and the same read is dispatched twice. The entries
 * are idempotent by key, so the second dispatch may be harmless; "may be" is
 * not a property to build a queue on.
 *
 * So the sweep is claimed, durably, by exactly one process at a time:
 *
 *  - a process writes a claim (owner, when it asked) to the shared log;
 *  - it then reads every claim everyone has written in the current round;
 *  - the winner is the earliest live claim, ties broken by owner id, which is
 *    a rule every process can compute from the same records and agree on.
 *
 * The ordering does not need a clock it can trust to the millisecond: two
 * processes that disagree by a second still agree on which claim is earlier,
 * and two claims with identical timestamps are separated by their owner id.
 * A claim expires after [ttlMillis], so a process that dies mid-sweep does not
 * lock its peers out forever.
 *
 * A single-process deployment can turn this off ([ttlMillis] <= 0) and keep
 * the old behaviour: a lease that nobody contends is overhead.
 */
class SweepLease(
    private val log: RecordLog,
    private val ownerId: String,
    private val ttlMillis: Long,
) {

    data class Claim(val ownerId: String, val askedAtMillis: Long, val round: Long)

    private val claims = ArrayList<Claim>()
    private var latestRound: Long = 0L

    init {
        // Start from what the previous process wrote: a restarted replica
        // must not believe it is alone just because its memory is empty.
        for (record in log.records()) {
            parse(record)?.let { claim ->
                claims.add(claim)
                if (claim.round > latestRound) latestRound = claim.round
            }
        }
        claims.removeAll { it.ownerId == ownerId }
    }

    /** True when this process is the one that should run the pass. */
    @Synchronized
    fun claim(nowMillis: Long): Boolean {
        if (ttlMillis <= 0L) return true
        val round = latestRound + 1L
        val mine = Claim(ownerId = ownerId, askedAtMillis = nowMillis, round = round)
        log.append(serialize(mine))
        latestRound = round
        // Read back everything, including other processes' claims for this
        // round, which is what makes two racing replicas agree on one winner.
        for (record in log.records()) {
            val claim = parse(record) ?: continue
            if (claim.round < round) continue
            if (claims.none { it.ownerId == claim.ownerId && it.round == claim.round }) claims.add(claim)
        }
        expire(nowMillis)
        val winner = claims
            .filter { it.round == round }
            .minWithOrNull(compareBy({ it.askedAtMillis }, { it.ownerId }))
        return winner?.ownerId == ownerId
    }

    /** How many processes were seen asking for this round. For a health line. */
    @Synchronized
    fun contenders(): Int = claims.map { it.ownerId }.distinct().size

    @Synchronized
    fun isLeaseOwner(nowMillis: Long): Boolean {
        if (ttlMillis <= 0L) return true
        expire(nowMillis)
        val winner = claims.minWithOrNull(compareBy({ it.askedAtMillis }, { it.ownerId }))
        return winner == null || winner.ownerId == ownerId
    }

    private fun expire(nowMillis: Long) {
        val cutoff = nowMillis - ttlMillis
        claims.removeAll { it.askedAtMillis < cutoff }
        // The log is append-only and every pass appends; dropping what has
        // expired is what keeps it proportional to the number of live claims
        // rather than to how long the service has been up.
        if (log.records().size > CLAIMS_BEFORE_COMPACTION) log.compact(claims.map(::serialize))
    }

    private fun serialize(claim: Claim): String = Json.write(
        Json.obj(
            "ownerId" to Json.str(claim.ownerId),
            "askedAtMillis" to Json.num(claim.askedAtMillis),
            "round" to Json.num(claim.round),
        ),
    )

    private fun parse(record: String): Claim? {
        val obj = Json.parseOrNull(record)?.asObject() ?: return null
        val owner = obj.text("ownerId") ?: return null
        val askedAt = obj.field("askedAtMillis")?.asLong() ?: return null
        val round = obj.field("round")?.asLong() ?: 0L
        return Claim(owner, askedAt, round)
    }

    /** The owner id this instance was created with, for a health line. */
    val owner: String get() = ownerId

    private companion object {
        /** Rewrite the claim stream once it has this many rows. */
        const val CLAIMS_BEFORE_COMPACTION = 64
    }
}

/** The owner id a service uses when the deployment did not name one. */
fun defaultOwnerId(prefix: String = "svc"): String =
    prefix + "-" + java.util.UUID.randomUUID().toString().take(8)
