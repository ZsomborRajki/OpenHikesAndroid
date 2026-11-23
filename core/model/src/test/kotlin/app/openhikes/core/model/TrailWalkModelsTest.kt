package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrailWalkModelsTest {
    @Test
    fun coverageMergesOverlappingIntervalsAndRespectsGapBound() {
        var coverage = TrailWalkCoverage()
        coverage = coverage.withRecordedMatch(0.0)
        coverage = coverage.withRecordedMatch(200.0)
        coverage = coverage.withRecordedMatch(450.0)
        // Jump of 600 m exceeds GAP_BOUND_METERS (500 m) and must not bridge 450..1050.
        coverage = coverage.withRecordedMatch(1050.0)
        coverage = coverage.withRecordedMatch(1200.0)

        assertThat(coverage.coveredMeters).isWithin(0.01).of(600.0)
        assertThat(coverage.furthestDistanceMeters).isWithin(0.01).of(1200.0)
        assertThat(coverage.ranges).hasSize(2)
        assertThat(coverage.meetsMinimum).isTrue()
    }

    @Test
    fun coverageSeparatesDirectionsAndIgnoresShortWobbleTurns() {
        var coverage = TrailWalkCoverage()
        coverage = coverage.withRecordedMatch(0.0, accuracyMeters = 10.0)
        coverage = coverage.withRecordedMatch(300.0, accuracyMeters = 10.0)
        // Small 20 m backward jitter inside a 300 m forward stretch must not count as a turn.
        coverage = coverage.withRecordedMatch(280.0, accuracyMeters = 10.0)
        coverage = coverage.withRecordedMatch(400.0, accuracyMeters = 10.0)

        assertThat(coverage.direction).isEqualTo(TrailWalkDirection.TOWARDS_END)
        assertThat(coverage.walkedTowardsStart).isEmpty()
        assertThat(coverage.walkedMeters).isWithin(0.01).of(400.0)
        assertThat(coverage.coveredMeters).isWithin(0.01).of(400.0)

        // Genuine 150 m turnaround reverses direction and counts walked meters both ways while ground counts once.
        coverage = coverage.withRecordedMatch(250.0, accuracyMeters = 10.0)
        assertThat(coverage.direction).isEqualTo(TrailWalkDirection.TOWARDS_START)
        assertThat(coverage.walkedTowardsStart).hasSize(1)
        assertThat(coverage.walkedMeters).isWithin(0.01).of(550.0)
        assertThat(coverage.coveredMeters).isWithin(0.01).of(400.0)
    }

    @Test
    fun accuracyWeightsPreventStandingStillWobbleFromReversingDirection() {
        var coverage = TrailWalkCoverage()
        coverage = coverage.withRecordedMatch(0.0, accuracyMeters = 10.0)
        coverage = coverage.withRecordedMatch(200.0, accuracyMeters = 60.0)
        assertThat(coverage.direction).isEqualTo(TrailWalkDirection.TOWARDS_END)

        // 90 m backwards is > 75 m minimum turn, but < (60 + 60 = 120 m) combined fix accuracy.
        coverage = coverage.withRecordedMatch(110.0, accuracyMeters = 60.0)
        assertThat(coverage.direction).isEqualTo(TrailWalkDirection.TOWARDS_END)
    }

    @Test
    fun breakContinuityPreventsBridgingAfterPauseOrOffRoute() {
        val coverage = TrailWalkCoverage()
            .withRecordedMatch(0.0)
            .withRecordedMatch(150.0)
            .withBrokenContinuity()
            .withRecordedMatch(350.0)
            .withRecordedMatch(450.0)

        assertThat(coverage.coveredMeters).isWithin(0.01).of(250.0)
        assertThat(coverage.ranges).hasSize(2)
    }

    @Test
    fun recordPauseResumeActiveSecondsAndAbandonRules() {
        val t0 = 1_000_000L
        var record = TrailWalkRecord(
            hikeId = "h1",
            startedAtEpochMillis = t0,
            routeDistanceMeters = 1_000.0
        )
        assertThat(record.activeSeconds(t0 + 60_000L)).isWithin(0.01).of(60.0)

        record = record.pausedAt(t0 + 60_000L)
        assertThat(record.phase).isEqualTo(TrailWalkPhase.PAUSED)
        assertThat(record.activeSeconds(t0 + 300_000L)).isWithin(0.01).of(60.0)
        // Paused walks are never abandoned during active session (stale-at-launch handles >24h).
        assertThat(record.isAbandoned(t0 + TrailWalkPolicy.ABANDON_AFTER_MILLIS + 60_000L)).isFalse()
        assertThat(record.isStaleAtLaunch(t0 + TrailWalkPolicy.STALE_AT_LAUNCH_AFTER_MILLIS + 1L)).isTrue()

        record = record.resumedAt(t0 + 300_000L)
        assertThat(record.phase).isEqualTo(TrailWalkPhase.FOLLOWING)
        assertThat(record.activeSeconds(t0 + 330_000L)).isWithin(0.01).of(90.0)
    }

    @Test
    fun reachesEndWorksInBothDirections() {
        val coverage = TrailWalkCoverage()
            .withRecordedMatch(0.0)
            .withRecordedMatch(480.0)
            .withRecordedMatch(960.0)
        val record = TrailWalkRecord(
            hikeId = "h1",
            startedAtEpochMillis = 0L,
            coverage = coverage,
            routeDistanceMeters = 1_000.0
        )
        assertThat(record.reachesEnd(970.0)).isTrue()
        assertThat(record.reachesEnd(20.0)).isTrue()
        assertThat(record.reachesEnd(500.0)).isFalse()
    }

    @Test
    fun pendingWalkStartConfirmsAfterTwoOutwardStepsPastMinimum() {
        val t0 = 10_000L
        var pending = PendingWalkStart.start(
            hikeId = "h1",
            routeDistanceMeters = 2_000.0,
            endsApartMeters = 1_500.0,
            routeRevision = "rev1",
            distanceMeters = 0.0,
            atEpochMillis = t0
        )
        assertThat(pending.isConfirmed).isFalse()

        // Single jump past 100 m is only 1 step -> not confirmed yet.
        pending = pending.withRecordedMatch(110.0, t0 + 5_000L)
        assertThat(pending.isConfirmed).isFalse()

        // Second step further out confirms.
        pending = pending.withRecordedMatch(140.0, t0 + 10_000L)
        assertThat(pending.isConfirmed).isTrue()
        assertThat(pending.confirmedRecord.startedAtEpochMillis).isEqualTo(t0)
    }

    @Test
    fun routeRevisionIgnoresElevationChangesButDetectsPauseAndCoordinateEdits() {
        val base = listOf(
            RouteCoordinate(47.7000, 12.8000, elevationMeters = 500.0),
            RouteCoordinate(47.7010, 12.8000, elevationMeters = 520.0),
            RouteCoordinate(47.7020, 12.8000, elevationMeters = 540.0)
        )
        val differentElev = base.map { it.copy(elevationMeters = (it.elevationMeters ?: 0.0) + 10.0) }
        val withPause = listOf(base[0], base[1].copy(boundary = RouteBoundary.PAUSED), base[2])
        val reversed = base.reversed()

        assertThat(TrailWalkRecord.routeRevision(base)).isEqualTo(TrailWalkRecord.routeRevision(differentElev))
        assertThat(TrailWalkRecord.routeRevision(base)).isNotEqualTo(TrailWalkRecord.routeRevision(withPause))
        assertThat(TrailWalkRecord.routeRevision(base)).isNotEqualTo(TrailWalkRecord.routeRevision(reversed))
    }
}
