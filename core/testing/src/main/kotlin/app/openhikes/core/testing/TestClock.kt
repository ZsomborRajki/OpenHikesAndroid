package app.openhikes.core.testing

import app.openhikes.core.common.OpenHikesClock
import java.time.Duration
import java.time.Instant

/**
 * Deterministic [OpenHikesClock] for unit and scenario tests.
 */
class TestClock(initialInstant: Instant = DEFAULT_START_INSTANT) : OpenHikesClock {
    constructor(initialEpochMillis: Long) : this(Instant.ofEpochMilli(initialEpochMillis))

    @Volatile
    private var currentInstant: Instant = initialInstant

    @Volatile
    private var currentMonotonicMillis: Long = initialInstant.toEpochMilli()

    override fun now(): Instant = currentInstant

    override fun monotonicMillis(): Long = currentMonotonicMillis

    fun set(instant: Instant) {
        currentInstant = instant
    }

    fun advanceBy(duration: Duration) {
        currentInstant = currentInstant.plus(duration)
        currentMonotonicMillis += duration.toMillis()
    }

    fun advanceMonotonicBy(duration: Duration) {
        currentMonotonicMillis += duration.toMillis()
    }

    companion object {
        val DEFAULT_START_INSTANT: Instant = Instant.parse("2026-06-15T08:00:00Z")
    }
}
