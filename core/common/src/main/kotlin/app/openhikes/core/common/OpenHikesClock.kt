package app.openhikes.core.common

import java.time.Instant

/**
 * Injectable time source so domain and recording rules never read wall clocks directly.
 */
interface OpenHikesClock {
    fun now(): Instant

    fun nowEpochMillis(): Long = now().toEpochMilli()

    fun monotonicMillis(): Long = now().toEpochMilli()
}

/**
 * Production clock reading the system UTC instant and monotonic nano-time.
 */
class SystemOpenHikesClock(private val monotonicMillisProvider: () -> Long = { System.nanoTime() / NANOS_PER_MILLI }) :
    OpenHikesClock {
    override fun now(): Instant = Instant.now()

    override fun monotonicMillis(): Long = monotonicMillisProvider()

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
