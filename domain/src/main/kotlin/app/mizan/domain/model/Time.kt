package app.mizan.domain.model

import java.time.Instant

fun interface TimeSource {
    fun now(): Instant

    companion object {
        val system: TimeSource = TimeSource { Instant.now() }
    }
}
