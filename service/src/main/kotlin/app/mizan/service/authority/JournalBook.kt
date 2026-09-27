package app.mizan.service.authority

import app.mizan.domain.execution.ExecutionJournal
import app.mizan.domain.execution.JournalAdvance
import app.mizan.domain.execution.JournalEvent
import app.mizan.domain.execution.JournalStateMachine
import app.mizan.service.store.ServiceStores
import java.time.Instant

/**
 * Writes the journal, and nothing else.
 *
 * The stage machine decides what may happen next; this class is the only place
 * that persists the result. It exists because two callers need it -- the
 * authority that authorises and the pipeline that executes -- and because an
 * illegal transition has to be recorded by one piece of code rather than
 * remembered by two.
 */
internal class JournalBook(
    private val stores: ServiceStores?,
    private val clock: () -> Long,
) {

    private val machine = JournalStateMachine()

    fun now(): Instant = Instant.ofEpochMilli(clock())

    fun advance(
    journal: ExecutionJournal,
    event: JournalEvent,
    at: Instant = now(),
): ExecutionJournal {
    return when (val stepped = journal.advance(event, at, machine)) {
        is app.mizan.domain.execution.JournalAdvance.Moved -> {
            stores?.journals?.save(stepped.journal)
            stepped.journal
        }
        is app.mizan.domain.execution.JournalAdvance.Illegal -> {
            // An illegal transition is a bug in the caller, and it is
            // recorded rather than swallowed: the journal keeps the last
            // legal stage and the error code names the attempt.
            val flagged = journal.copy(
                errorCode = "ILLEGAL_TRANSITION_${stepped.transition.event}",
                updatedAt = at,
                revision = journal.revision + 1,
            )
            stores?.journals?.save(flagged)
            flagged
        }
    }
}

    fun withProof(journal: ExecutionJournal, proof: String?): ExecutionJournal {
    if (proof.isNullOrBlank()) return journal
    val stamped = journal.copy(proofReference = proof)
    stores?.journals?.save(stamped)
    return stamped
}
}
