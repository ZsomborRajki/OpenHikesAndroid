package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutoPauseWatchTest {
    private val baseMillis = 1_767_225_600_000L

    @Test
    fun detectsStopAfterContainmentAndDepartsFromFirstOutboundFix() {
        val policy = AutoPausePolicy.Standard.copy(minimumStopSeconds = 120.0)
        val watch = AutoPauseWatch(policy)

        val anchor = AutoPauseWatch.Fix(47.71850, 12.83170, baseMillis, 5.0)
        assertThat(watch.observe(anchor)).isNull()
        assertThat(watch.decisionEpochMillis).isEqualTo(baseMillis + 120_000L)

        // Wander inside 30 m stop radius for 120 s
        val wander = AutoPauseWatch.Fix(47.71855, 12.83175, baseMillis + 60_000L, 5.0)
        assertThat(watch.observe(wander)).isNull()

        val stopConfirm = AutoPauseWatch.Fix(47.71852, 12.83172, baseMillis + 120_000L, 5.0)
        val stopped = watch.observe(stopConfirm)
        assertThat(stopped).isEqualTo(AutoPauseWatch.Event.Stopped(sinceEpochMillis = baseMillis))
        assertThat(watch.isStopped).isTrue()

        // Stepping outward: first outbound fix inside circle, next outside circle departs from first outbound fix
        val firstOutbound = AutoPauseWatch.Fix(47.71868, 12.83170, baseMillis + 150_000L, 5.0)
        assertThat(watch.observe(firstOutbound)).isNull()
        assertThat(watch.isStopped).isTrue()

        val outside = AutoPauseWatch.Fix(47.71910, 12.83170, baseMillis + 160_000L, 5.0)
        val departed = watch.observe(outside)
        assertThat(departed).isEqualTo(AutoPauseWatch.Event.Departed(sinceEpochMillis = baseMillis + 150_000L))
        assertThat(watch.isStopped).isFalse()
    }

    @Test
    fun silentStopTriggersOnTickOnlyWhenSettledOrStationaryWithoutMotionMovement() {
        val watch = AutoPauseWatch(AutoPausePolicy.Standard)
        val anchor = AutoPauseWatch.Fix(47.71850, 12.83170, baseMillis, 5.0)
        watch.observe(anchor)
        // Settled fix at 35s within 15m marks candidate as settled
        watch.observe(AutoPauseWatch.Fix(47.71852, 12.83170, baseMillis + 35_000L, 5.0))

        val premature = watch.tick(baseMillis + 299_000L, AutoPauseWatch.Motion.STATIONARY)
        assertThat(premature).isNull()

        val stopped = watch.tick(baseMillis + 300_000L, AutoPauseWatch.Motion.STATIONARY)
        assertThat(stopped).isEqualTo(AutoPauseWatch.Event.Stopped(sinceEpochMillis = baseMillis))

        // Walking silence (motion ON_FOOT during silence) refuses tick stop
        val tunnelWatch = AutoPauseWatch(AutoPausePolicy.Standard)
        tunnelWatch.observe(anchor)
        tunnelWatch.observe(AutoPauseWatch.Fix(47.71852, 12.83170, baseMillis + 35_000L, 5.0))
        assertThat(tunnelWatch.tick(baseMillis + 100_000L, AutoPauseWatch.Motion.ON_FOOT)).isNull()
        assertThat(tunnelWatch.tick(baseMillis + 310_000L, AutoPauseWatch.Motion.STATIONARY)).isNull()
    }

    @Test
    fun climbBreaksContainmentAndCarriedMovesStopPlaceWithoutDeparting() {
        val watch = AutoPauseWatch(AutoPausePolicy.Standard.copy(minimumStopSeconds = 60.0))
        watch.observe(AutoPauseWatch.Fix(47.71850, 12.83170, baseMillis, 5.0, elevationMeters = 500.0))
        // Climbing > 10 m vertically resets candidate anchor even if horizontal coords stay close
        watch.observe(AutoPauseWatch.Fix(47.71851, 12.83170, baseMillis + 65_000L, 5.0, elevationMeters = 515.0))
        assertThat(watch.isStopped).isFalse()

        val stoppedWatch = AutoPauseWatch(AutoPausePolicy.Standard.copy(minimumStopSeconds = 10.0))
        assertThat(
            stoppedWatch.resumeStop(AutoPauseWatch.Fix(47.71850, 12.83170, baseMillis, 5.0))
        ).isTrue()
        // Carried in a car 200 m north moves stop place rather than resuming on foot
        val busMove = AutoPauseWatch.Fix(
            latitude = 47.72050,
            longitude = 12.83170,
            timestampEpochMillis = baseMillis + 20_000L,
            horizontalAccuracyMeters = 5.0,
            speedMps = 15.0,
            motion = AutoPauseWatch.Motion.CARRIED
        )
        assertThat(stoppedWatch.observe(busMove)).isNull()
        assertThat(stoppedWatch.isStopped).isTrue()

        // Walking 50 m away from new bus drop-off resumes
        val walkOff = AutoPauseWatch.Fix(
            latitude = 47.72120,
            longitude = 12.83170,
            timestampEpochMillis = baseMillis + 35_000L,
            horizontalAccuracyMeters = 5.0,
            speedMps = 1.4,
            motion = AutoPauseWatch.Motion.ON_FOOT
        )
        assertThat(stoppedWatch.observe(walkOff))
            .isEqualTo(AutoPauseWatch.Event.Departed(sinceEpochMillis = baseMillis + 35_000L))
    }
}
