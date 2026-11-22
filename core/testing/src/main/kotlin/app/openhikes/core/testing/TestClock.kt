package app.openhikes.core.testing

import app.openhikes.core.common.OpenHikesClock
import java.time.Duration
import java.time.Instant

/**
 * Deterministic [OpenHikesClock] for unit and scenario tests.
 */
class TestClock(initialInstant: Instant = DEFAULT_START_INSTANT) : OpenHikesClock {
    private var currentInstant: Instant = initialInstant

    override fun now(): Instant = currentInstant

    fun set(instant: Instant) {
        currentInstant = instant
    }

    fun advanceBy(duration: Duration) {
        currentInstant = currentInstant.plus(duration)
    }

    companion object {
        val DEFAULT_START_INSTANT: Instant = Instant.parse("2026-06-15T08:00:00Z")
    }
}
