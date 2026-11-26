package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HikeWalkPhotoTimelineTest {
    private val hikeId = HikeId("hike-walk-1")
    private val revision = "rev-abc"
    private val t0 = 1_700_000_000_000L

    @Test
    fun forwardWalkDistributesTimeAcrossCoveredGapsAndChecksSlack() {
        val walk = HikeWalk(
            id = "w-forward",
            hikeId = hikeId,
            startedAtEpochMillis = t0,
            endedAtEpochMillis = t0 + 1_000_000L,
            distanceMeters = 1_000.0,
            durationSeconds = 1_000.0,
            // Covered [0..400] and [600..1200] => 1000m covered total across a 200m uncovered gap
            coveredIntervals = listOf(0.0, 400.0, 600.0, 1200.0),
            routeDistanceMeters = 1_200.0,
            routeRevision = revision
        )
        val timeline = HikeWalkPhotoTimeline.from(walk, revision)!!

        assertThat(timeline.walkedTowardsStart).isFalse()
        assertThat(timeline.distanceAlongRoute(t0 + 200_000L)).isWithin(0.1).of(200.0)
        assertThat(timeline.distanceAlongRoute(t0 + 500_000L)).isWithin(0.1).of(700.0)
        assertThat(timeline.covers(495.0)).isTrue()
        assertThat(timeline.covers(500.0)).isTrue()
        assertThat(timeline.covers(1350.0)).isFalse()
    }

    @Test
    fun reversedWalkProgressesDownwardFromTopOfCoverage() {
        val walk = HikeWalk(
            id = "w-reverse",
            hikeId = hikeId,
            startedAtEpochMillis = t0,
            endedAtEpochMillis = t0 + 1_000_000L,
            distanceMeters = 1_000.0,
            durationSeconds = 1_000.0,
            coveredIntervals = listOf(1000.0, 0.0),
            routeDistanceMeters = 1_000.0,
            routeRevision = revision
        )
        val timeline = HikeWalkPhotoTimeline.from(walk, revision)!!

        assertThat(timeline.walkedTowardsStart).isTrue()
        assertThat(timeline.distanceAlongRoute(t0 + 250_000L)).isWithin(0.1).of(750.0)
        assertThat(timeline.distanceAlongRoute(t0 + 1_000_000L)).isWithin(0.1).of(0.0)
    }

    @Test
    fun dropsMismatchedRouteRevisionsAndOrdersOldestFirst() {
        val stale = HikeWalk(
            id = "w-stale",
            hikeId = hikeId,
            startedAtEpochMillis = t0 - 10_000L,
            endedAtEpochMillis = t0,
            distanceMeters = 500.0,
            durationSeconds = 100.0,
            coveredIntervals = listOf(0.0, 500.0),
            routeRevision = "old-rev"
        )
        val newer = HikeWalk(
            id = "w-2",
            hikeId = hikeId,
            startedAtEpochMillis = t0 + 50_000L,
            endedAtEpochMillis = t0 + 100_000L,
            distanceMeters = 500.0,
            durationSeconds = 50.0,
            coveredIntervals = listOf(0.0, 500.0),
            routeRevision = revision
        )
        val older = HikeWalk(
            id = "w-1",
            hikeId = hikeId,
            startedAtEpochMillis = t0,
            endedAtEpochMillis = t0 + 40_000L,
            distanceMeters = 500.0,
            durationSeconds = 40.0,
            coveredIntervals = listOf(0.0, 500.0),
            routeRevision = revision
        )

        val timelines = HikeWalkPhotoTimeline.allMatching(listOf(newer, stale, older), revision)
        assertThat(timelines.map { it.walkId }).containsExactly("w-1", "w-2").inOrder()
    }
}
