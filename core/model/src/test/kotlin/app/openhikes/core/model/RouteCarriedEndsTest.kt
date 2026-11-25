package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RouteCarriedEndsTest {
    @Test
    fun detectsTrailingDriveBySpeedAndPreservesWalkedRange() {
        val startMillis = 1_780_000_000_000L
        val walkPoints = (0..10).map { step ->
            RouteCoordinate(
                latitude = 47.71842 + step * 0.0009,
                longitude = 12.83177,
                timestampEpochMillis = startMillis + step * 75_000L
            )
        }
        val driveOrigin = walkPoints.last()
        val drivePoints = (1..20).map { step ->
            RouteCoordinate(
                latitude = driveOrigin.latitude,
                longitude = driveOrigin.longitude + step * 0.004,
                timestampEpochMillis = driveOrigin.timestampEpochMillis!! + step * 20_000L
            )
        }
        val route = walkPoints + drivePoints
        val distances = RouteTrim.distances(route)

        val ends = RouteCarriedEnds.found(route, distances)

        val trailing = requireNotNull(ends.trailing)
        assertThat(ends.leading).isNull()
        assertThat(trailing.startMeters).isWithin(5.0).of(distances[10])
        assertThat(trailing.endMeters).isWithin(1.0).of(distances.last())
        assertThat(trailing.carriedMeters).isGreaterThan(5_000.0)
    }

    @Test
    fun detectsLeadingNonPedestrianMotionTag() {
        val leadingDrive = (0..6).map { step ->
            RouteCoordinate(
                latitude = 47.7000 + step * 0.001,
                longitude = 12.83177,
                motion = if (step > 0) RouteMotion.NON_PEDESTRIAN else null
            )
        }
        val walk = (1..6).map { step ->
            RouteCoordinate(
                latitude = leadingDrive.last().latitude + step * 0.001,
                longitude = 12.83177
            )
        }
        val route = leadingDrive + walk
        val distances = RouteTrim.distances(route)

        val ends = RouteCarriedEnds.found(route, distances)

        val leading = requireNotNull(ends.leading)
        assertThat(leading.endMeters).isWithin(1.0).of(distances[6])
        assertThat(ends.trailing).isNull()
    }

    @Test
    fun detectsPauseJumpAtEndWhenRecordingResumedMilesAway() {
        val walk = (0..5).map { step ->
            RouteCoordinate(latitude = 47.71842 + step * 0.001, longitude = 12.83177)
        }
        val route = walk + RouteCoordinate(
            latitude = 47.74000,
            longitude = 12.83177,
            boundary = RouteBoundary.PAUSED
        )
        val distances = RouteTrim.distances(route)

        val ends = RouteCarriedEnds.found(route, distances)

        val trailing = requireNotNull(ends.trailing)
        assertThat(trailing.startMeters).isWithin(0.1).of(distances.last())
        assertThat(trailing.carriedMeters).isGreaterThan(1_500.0)
    }

    @Test
    fun returnsEmptyWhenTooLittleWalkRemainsBetweenCarriedEnds() {
        val route = (0..10).map { step ->
            RouteCoordinate(
                latitude = 47.71842 + step * 0.001,
                longitude = 12.83177,
                motion = if (step > 0) RouteMotion.NON_PEDESTRIAN else null
            )
        }

        val ends = RouteCarriedEnds.found(route)

        assertThat(ends.isEmpty).isTrue()
    }
}
