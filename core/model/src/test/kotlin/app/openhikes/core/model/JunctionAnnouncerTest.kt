package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JunctionAnnouncerTest {
    @Test
    fun emitsAheadThenImminentAndRearmsAfterPassingFarAway() {
        val junctions = listOf(
            TrailJunction(
                distanceMeters = 500.0,
                towardsEnd = JunctionManeuver.KEEP_LEFT,
                towardsStart = JunctionManeuver.TURN_RIGHT
            )
        )
        val announcer = JunctionAnnouncer(junctions)

        // First fix sets anchor only.
        assertThat(announcer.advance(340.0, isOnTrail = true)).isNull()
        // Move +20 m -> direction becomes TOWARDS_END, 140 m ahead -> ahead cue.
        val firstCue = announcer.advance(360.0, isOnTrail = true)
        assertThat(firstCue).isNotNull()
        assertThat(firstCue!!.maneuver).isEqualTo(JunctionManeuver.KEEP_LEFT)
        assertThat(firstCue.isImminent).isFalse()
        assertThat(firstCue.roundedAheadValue(DistanceUnit.METRIC)).isWithin(0.01).of(140.0)

        // Duplicate within ahead window does not repeat.
        assertThat(announcer.advance(400.0, isOnTrail = true)).isNull()

        // Within 30 m -> imminent cue.
        val imminentCue = announcer.advance(475.0, isOnTrail = true)
        assertThat(imminentCue).isNotNull()
        assertThat(imminentCue!!.isImminent).isTrue()
        assertThat(imminentCue.maneuver).isEqualTo(JunctionManeuver.KEEP_LEFT)

        // Off-trail produces nothing.
        assertThat(announcer.advance(490.0, isOnTrail = false)).isNull()

        // Walk > 250 m past junction, then reverse with two fixes >= 30 m back to re-approach.
        assertThat(announcer.advance(800.0, isOnTrail = true)).isNull()
        assertThat(announcer.advance(760.0, isOnTrail = true)).isNull()
        assertThat(announcer.advance(720.0, isOnTrail = true)).isNull()
        assertThat(announcer.direction).isEqualTo(TrailWalkDirection.TOWARDS_START)

        val returnCue = announcer.advance(640.0, isOnTrail = true)
        assertThat(returnCue).isNotNull()
        assertThat(returnCue!!.maneuver).isEqualTo(JunctionManeuver.TURN_RIGHT)
    }

    @Test
    fun holdsNextJunctionUntilFirstImminentJunctionIsReached() {
        val junctions = listOf(
            TrailJunction(distanceMeters = 500.0, towardsEnd = JunctionManeuver.TURN_LEFT, towardsStart = null),
            TrailJunction(distanceMeters = 580.0, towardsEnd = JunctionManeuver.TURN_RIGHT, towardsStart = null)
        )
        val announcer = JunctionAnnouncer(junctions, initialDirection = TrailWalkDirection.TOWARDS_END)
        val firstImminent = announcer.advance(480.0, isOnTrail = true)
        assertThat(firstImminent?.junctionIndex).isEqualTo(0)
        assertThat(firstImminent?.isImminent).isTrue()

        // Still 10 m before junction 0 -> must not jump ahead to junction 1.
        assertThat(announcer.advance(490.0, isOnTrail = true)).isNull()

        // Past junction 0 (80 m before junction 1) -> junction 1 ahead cue now fires.
        val secondAhead = announcer.advance(502.0, isOnTrail = true)
        assertThat(secondAhead?.junctionIndex).isEqualTo(1)
        assertThat(secondAhead?.isImminent).isFalse()
    }

    @Test
    fun turnDetectorIdentifiesNinetyDegreeCorner() {
        val route = listOf(
            RouteCoordinate(47.7000, 12.8000),
            RouteCoordinate(47.7005, 12.8000),
            RouteCoordinate(47.7010, 12.8000),
            RouteCoordinate(47.7010, 12.8007),
            RouteCoordinate(47.7010, 12.8014)
        )
        val junctions = RouteTurnJunctionDetector.detect(RouteProfile.from(route))
        assertThat(junctions).hasSize(1)
        assertThat(junctions.first().towardsEnd).isEqualTo(JunctionManeuver.TURN_RIGHT)
        assertThat(junctions.first().towardsStart).isEqualTo(JunctionManeuver.TURN_LEFT)
    }
}
