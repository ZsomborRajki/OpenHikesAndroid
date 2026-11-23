package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RouteSegmentsTest {
    @Test
    fun splitsMultiSegmentRoutesSkipsLoneFixesAndScalesToWalkDistance() {
        val baseMillis = 1_767_225_600_000L
        val route = listOf(
            RouteCoordinate(47.71800, 12.83100, elevationMeters = 500.0, timestampEpochMillis = baseMillis),
            RouteCoordinate(47.71900, 12.83100, elevationMeters = 520.0, timestampEpochMillis = baseMillis + 60_000L),
            // Lone point bounded by pauses on both sides: excluded from segments, merges into stop duration
            RouteCoordinate(
                latitude = 47.71910,
                longitude = 12.83100,
                elevationMeters = 521.0,
                timestampEpochMillis = baseMillis + 120_000L,
                boundary = RouteBoundary.PAUSED
            ),
            RouteCoordinate(
                latitude = 47.72000,
                longitude = 12.83100,
                elevationMeters = 530.0,
                timestampEpochMillis = baseMillis + 240_000L,
                boundary = RouteBoundary.PAUSED
            ),
            RouteCoordinate(47.72100, 12.83100, elevationMeters = 540.0, timestampEpochMillis = baseMillis + 300_000L)
        )

        val segments = RouteSegments.segments(route, distanceMeters = 200.0)
        assertThat(segments).hasSize(2)
        assertThat(segments[0].distanceMeters + segments[1].distanceMeters).isWithin(0.01).of(200.0)
        assertThat(segments[0].stopSeconds).isWithin(0.01).of(180.0)
        assertThat(segments[0].elevationGainMeters).isWithin(0.01).of(20.0)
        assertThat(segments[1].elevationGainMeters).isWithin(0.01).of(10.0)
        assertThat(segments[1].stopSeconds).isNull()
    }

    @Test
    fun returnsEmptyWhenSinglePieceOrExceedingMaximumCount() {
        val singleSegment = listOf(
            RouteCoordinate(47.71800, 12.83100),
            RouteCoordinate(47.71900, 12.83100)
        )
        assertThat(RouteSegments.segments(singleSegment, distanceMeters = 100.0)).isEmpty()

        val tooMany = buildList {
            repeat(RouteSegments.MAXIMUM_COUNT + 1) { index ->
                val boundary = if (index > 0) RouteBoundary.PAUSED else null
                add(RouteCoordinate(47.70 + index * 0.001, 12.83, boundary = boundary))
                add(RouteCoordinate(47.7005 + index * 0.001, 12.83))
            }
        }
        assertThat(RouteSegments.segments(tooMany, distanceMeters = 5_000.0)).isEmpty()
    }
}
