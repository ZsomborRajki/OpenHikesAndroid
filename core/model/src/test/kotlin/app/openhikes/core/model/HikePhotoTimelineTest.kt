package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HikePhotoTimelineTest {
    private val t0 = 1_700_000_000_000L

    @Test
    fun interpolatesWithinSegmentAndHonorsFiveMinuteGraceAtEndsAndGaps() {
        val route = listOf(
            RouteCoordinate(47.7000, 12.8000, timestampEpochMillis = t0),
            RouteCoordinate(47.7020, 12.8000, timestampEpochMillis = t0 + 120_000L),
            // 12-minute GPS gap within same continuous segment
            RouteCoordinate(47.7040, 12.8000, timestampEpochMillis = t0 + 840_000L)
        )
        val timeline = HikePhotoTimeline.from(route)!!

        // 4 min before start -> snapped to start
        val before = timeline.positionAt(t0 - 240_000L)
        assertThat(before?.coordinate).isEqualTo(GeoCoordinate(47.7000, 12.8000))
        assertThat(before?.secondsFromFix).isWithin(0.01).of(240.0)

        // 6 min before start -> outside grace
        assertThat(timeline.positionAt(t0 - 360_000L)).isNull()

        // Midpoint of first 2-min leg -> interpolated
        val mid = timeline.positionAt(t0 + 60_000L)!!
        assertThat(mid.coordinate.latitude).isWithin(1e-5).of(47.7010)
        assertThat(mid.secondsFromFix).isWithin(0.01).of(60.0)

        // Center of 12-min GPS gap (6 min from either fix) -> rejected
        assertThat(timeline.positionAt(t0 + 480_000L)).isNull()
    }

    @Test
    fun stationaryPauseAllowsUnlimitedTimeWhileTravelPauseEnforcesGraceAndNeverInterpolates() {
        // Stationary pause (~10m apart) lasting 1 hour
        val stationaryRoute = listOf(
            RouteCoordinate(47.7000, 12.8000, timestampEpochMillis = t0),
            RouteCoordinate(47.70005, 12.8000, timestampEpochMillis = t0 + 3_600_000L, boundary = RouteBoundary.PAUSED)
        )
        val stationaryTimeline = HikePhotoTimeline.from(stationaryRoute)!!
        val lunchPhoto = stationaryTimeline.positionAt(t0 + 1_800_000L)
        assertThat(lunchPhoto?.coordinate).isEqualTo(GeoCoordinate(47.7000, 12.8000))

        // Travel pause (> 1 km apart) lasting 20 minutes
        val travelRoute = listOf(
            RouteCoordinate(47.7000, 12.8000, timestampEpochMillis = t0),
            RouteCoordinate(47.7200, 12.8000, timestampEpochMillis = t0 + 1_200_000L, boundary = RouteBoundary.PAUSED)
        )
        val travelTimeline = HikePhotoTimeline.from(travelRoute)!!
        // Middle of travel pause -> rejected (never interpolated across pause)
        assertThat(travelTimeline.positionAt(t0 + 600_000L)).isNull()
        // Within 5 min of resume -> snaps to resume endpoint, never interpolates
        val nearResume = travelTimeline.positionAt(t0 + 1_000_000L)
        assertThat(nearResume?.coordinate).isEqualTo(GeoCoordinate(47.7200, 12.8000))
    }

    @Test
    fun pauseMarkedOnUntimestampedPointStillSeparatesSurroundingTimedLegs() {
        val route = listOf(
            RouteCoordinate(47.7000, 12.8000, timestampEpochMillis = t0),
            RouteCoordinate(47.7100, 12.8000, timestampEpochMillis = null, boundary = RouteBoundary.PAUSED),
            RouteCoordinate(47.7200, 12.8000, timestampEpochMillis = t0 + 60_000L)
        )
        val timeline = HikePhotoTimeline.from(route)!!
        // Because the untimestamped pause split segments, midpoint snaps to earlier endpoint rather than interpolating.
        val mid = timeline.positionAt(t0 + 20_000L)
        assertThat(mid?.coordinate).isEqualTo(GeoCoordinate(47.7000, 12.8000))
    }

    @Test
    fun recordedRouteLegsExcludesPausedAndInferredSpansWhileKeepingEndpoints() {
        val route = listOf(
            RouteCoordinate(47.7000, 12.8000),
            RouteCoordinate(47.7010, 12.8000),
            RouteCoordinate(47.7200, 12.8000, provenance = RouteProvenance.INFERRED),
            RouteCoordinate(47.7210, 12.8000)
        )
        val legs = RecordedRouteLegs.from(route)
        val midGap = GeoCoordinate(47.7105, 12.8000)

        val projection = legs.nearestPoint(midGap)!!
        // Must project to one of the real leg endpoints (> 900m away), never onto the inferred bridge.
        assertThat(projection.offRouteMeters).isGreaterThan(900.0)
    }
}
