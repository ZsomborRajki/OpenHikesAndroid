package app.openhikes.core.common

import java.time.Instant

/**
 * Injectable time source so domain and recording rules never read wall clocks directly.
 */
interface OpenHikesClock {
    fun now(): Instant
}

/**
 * Production clock reading the system UTC instant.
 */
class SystemOpenHikesClock : OpenHikesClock {
    override fun now(): Instant = Instant.now()
}
