package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PhotoTrailAnchorTest {
    private val start = GeoCoordinate(47.7000, 12.8000)
    private val mid = GeoCoordinate(47.7020, 12.8000)
    private val afterPause = GeoCoordinate(47.7100, 12.8000)
    private val end = GeoCoordinate(47.7120, 12.8000)

    private val profile = RouteProfile.from(
        listOf(
            RouteCoordinate(start.latitude, start.longitude),
            RouteCoordinate(mid.latitude, mid.longitude),
            RouteCoordinate(afterPause.latitude, afterPause.longitude, boundary = RouteBoundary.PAUSED),
            RouteCoordinate(end.latitude, end.longitude)
        )
    )

    @Test
    fun liveAnchorWinsAtTrailheadZeroAndAcrossPauseWithoutReprojection() {
        val trailheadLive = LiveTrailAnchor(distanceAlongRouteMeters = 0.0, coordinate = start)
        assertThat(PhotoTrailAnchor.coordinate(profile, live = trailheadLive, scrubbedMeters = 100.0))
            .isEqualTo(start)

        // Immediately after a pause boundary, distanceAlongRoute matches the pre-pause distance,
        // so returning live.coordinate directly prevents jumping back across the pause.
        val pauseDistance = RouteGeometry.distanceMeters(start, mid)
        val postPauseLive = LiveTrailAnchor(distanceAlongRouteMeters = pauseDistance, coordinate = afterPause)
        assertThat(PhotoTrailAnchor.coordinate(profile, live = postPauseLive, scrubbedMeters = null))
            .isEqualTo(afterPause)
    }

    @Test
    fun scrubbedZeroLeavesPhotoUnanchoredWhilePlaceFallsBackToTrailhead() {
        assertThat(PhotoTrailAnchor.coordinate(profile, live = null, scrubbedMeters = 0.0)).isNull()
        assertThat(PhotoTrailAnchor.coordinate(profile, live = null, scrubbedMeters = null)).isNull()
        assertThat(PhotoTrailAnchor.placeCoordinate(profile, live = null, scrubbedMeters = 0.0)).isEqualTo(start)
        assertThat(PhotoTrailAnchor.placeCoordinate(profile, live = null, scrubbedMeters = null)).isEqualTo(start)

        val scrubbed = PhotoTrailAnchor.coordinate(profile, live = null, scrubbedMeters = 100.0)
        assertThat(scrubbed).isNotNull()
        assertThat(scrubbed!!.latitude).isGreaterThan(start.latitude)
    }

    @Test
    fun recordingCoordinateInterpolatesShutterTimeAndRejectsEvictedWindow() {
        val baseMillis = 1_700_000_000_000L
        val p1 = RecordingPoint(47.7000, 12.8000, timestampEpochMillis = baseMillis, horizontalAccuracyMeters = 5.0)
        val p2 = RecordingPoint(
            47.7020,
            12.8000,
            timestampEpochMillis = baseMillis + 60_000L,
            horizontalAccuracyMeters = 5.0
        )

        val halfway = PhotoTrailAnchor.recordingCoordinate(
            shutterEpochMillis = baseMillis + 30_000L,
            recent = listOf(p1, p2),
            latest = p2
        )
        assertThat(halfway).isNotNull()
        assertThat(halfway!!.latitude).isWithin(1e-5).of(47.7010)

        // Shutter at or after latest fix uses latest fix directly.
        assertThat(
            PhotoTrailAnchor.recordingCoordinate(
                shutterEpochMillis = baseMillis + 70_000L,
                recent = listOf(p1, p2),
                latest = p2
            )
        ).isEqualTo(p2.coordinate)

        // Shutter older than the 30-minute recent fix window returns null instead of pinning to oldest fix.
        assertThat(
            PhotoTrailAnchor.recordingCoordinate(
                shutterEpochMillis = baseMillis + 60_000L - PhotoTrailAnchor.RECENT_RECORDING_WINDOW_MILLIS - 1L,
                recent = listOf(p1, p2),
                latest = p2
            )
        ).isNull()
    }
}
