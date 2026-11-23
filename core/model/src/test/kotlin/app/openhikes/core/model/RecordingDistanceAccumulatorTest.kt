package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecordingDistanceAccumulatorTest {
    private val baseMillis = 1_767_225_600_000L

    @Test
    fun accumulatesDistanceAndRetractsStationaryGpsWanderWindow() {
        val acc = RecordingDistanceAccumulator()
        val p0 = RecordingPoint(47.71800, 12.83100, baseMillis, 5.0, elevationMeters = 500.0)
        val p1 = RecordingPoint(47.71850, 12.83100, baseMillis + 15_000L, 5.0, elevationMeters = 508.0)
        val p2 = RecordingPoint(47.71803, 12.83102, baseMillis + 35_000L, 5.0, elevationMeters = 510.0)

        acc.append(p0)
        val afterWalk = acc.append(p1)
        assertThat(afterWalk).isGreaterThan(50.0)
        assertThat(acc.isStationary).isFalse()

        // Return within < 15 m after >= 30 s: stationary window retracts wander distance, keeps elevation
        val afterReturn = acc.append(p2)
        assertThat(acc.isStationary).isTrue()
        assertThat(afterReturn).isWithin(0.01).of(0.0)
        assertThat(acc.elevationGainMeters).isWithin(0.01).of(10.0)
        assertThat(acc.recordedDurationSeconds).isWithin(0.01).of(35.0)
    }

    @Test
    fun respectsPauseResumesCarriedGroundAndRecentSpeedClamping() {
        val acc = RecordingDistanceAccumulator()
        acc.append(RecordingPoint(47.71800, 12.83100, baseMillis, 5.0, elevationMeters = 500.0))
        acc.append(RecordingPoint(47.71900, 12.83100, baseMillis + 60_000L, 5.0, elevationMeters = 520.0))
        val distBeforePause = acc.distanceMeters
        assertThat(acc.recentSpeedMps).isNotNull()

        // Resuming 5 km away after an hour pause adds zero distance, duration, or climb across the gap
        val resumed = RecordingPoint(
            latitude = 47.76000,
            longitude = 12.89000,
            timestampEpochMillis = baseMillis + 3_660_000L,
            horizontalAccuracyMeters = 5.0,
            elevationMeters = 900.0,
            flags = RecordingPointFlags.RESUMED
        )
        acc.append(resumed)
        assertThat(acc.distanceMeters).isWithin(0.01).of(distBeforePause)
        assertThat(acc.recordedDurationSeconds).isWithin(0.01).of(60.0)
        assertThat(acc.elevationGainMeters).isWithin(0.01).of(20.0)
        assertThat(acc.recentSpeedMps).isNull()

        // Next point on resumed leg grows second segment and elevation from new anchor
        acc.append(RecordingPoint(47.76090, 12.89000, baseMillis + 3_730_000L, 5.0, elevationMeters = 910.0))
        assertThat(acc.elevationGainMeters).isWithin(0.01).of(30.0)
        assertThat(acc.segments).hasSize(2)
        assertThat(acc.segments.first().stopSeconds).isWithin(0.01).of(3_600.0)

        // Carried flag forces leg inclusion and resets stillness window
        val carried = RecordingPoint(
            latitude = 47.76180,
            longitude = 12.89000,
            timestampEpochMillis = baseMillis + 3_740_000L,
            horizontalAccuracyMeters = 5.0,
            flags = RecordingPointFlags.CARRIED
        )
        val beforeCarried = acc.distanceMeters
        acc.append(carried)
        assertThat(acc.distanceMeters).isGreaterThan(beforeCarried + 90.0)
    }
}
