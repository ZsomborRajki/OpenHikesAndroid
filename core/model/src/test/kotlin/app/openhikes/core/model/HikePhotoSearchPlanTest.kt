package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HikePhotoSearchPlanTest {
    private val hikeId = HikeId("hike-plan")
    private val t0 = 1_700_000_000_000L
    private val timedRoute = listOf(
        RouteCoordinate(47.7000, 12.8000, elevationMeters = 500.0, timestampEpochMillis = t0),
        RouteCoordinate(47.7050, 12.8000, elevationMeters = 600.0, timestampEpochMillis = t0 + 300_000L)
    )
    private val untimedRoute = listOf(
        RouteCoordinate(47.7000, 12.8000),
        RouteCoordinate(47.7050, 12.8000)
    )

    @Test
    fun recordedRouteMatchesTimeTimeAndPlaceAndPlaceWhileRejectingOffTrailCamera() {
        val plan = HikePhotoSearchPlan.build(route = timedRoute, walks = emptyList())
        val midTime = t0 + 150_000L
        val midCoord = GeoCoordinate(47.7025, 12.8000)

        val timeOnly = PhotoLibraryAsset("a-time", midTime, coordinate = null)
        val both = PhotoLibraryAsset("a-both", midTime, coordinate = GeoCoordinate(47.7026, 12.8001))
        val placeSnap = PhotoLibraryAsset("a-place", midTime, coordinate = GeoCoordinate(47.7002, 12.8000))
        val farOff = PhotoLibraryAsset("a-far", midTime, coordinate = GeoCoordinate(47.8000, 12.9000))

        val matches = plan.matches(listOf(timeOnly, both, placeSnap, farOff), alreadyImported = emptySet())

        assertThat(matches.map { it.id to it.evidence }).containsExactly(
            "a-both" to PhotoMatchEvidence.TIME_AND_PLACE,
            "a-place" to PhotoMatchEvidence.PLACE,
            "a-time" to PhotoMatchEvidence.TIME
        ).inOrder()
        // When both agree, the walk's clock position wins over the camera reading.
        val bothMatch = matches.first { it.id == "a-both" }
        assertThat(bothMatch.coordinate.latitude).isWithin(1e-4).of(midCoord.latitude)
    }

    @Test
    fun recordedRouteRefusalIsNeverOverruledByOverlappingWalk() {
        val revision = TrailWalkRecord.routeRevision(timedRoute)
        val overlappingWalk = HikeWalk(
            id = "w-overlap",
            hikeId = hikeId,
            startedAtEpochMillis = t0,
            endedAtEpochMillis = t0 + 300_000L,
            distanceMeters = 550.0,
            durationSeconds = 300.0,
            coveredIntervals = listOf(0.0, 550.0),
            routeRevision = revision
        )
        val plan = HikePhotoSearchPlan.build(route = timedRoute, walks = listOf(overlappingWalk))
        val farOffDuringRecording = PhotoLibraryAsset("off", t0 + 150_000L, GeoCoordinate(47.8500, 12.9500))

        assertThat(plan.matches(listOf(farOffDuringRecording), emptySet())).isEmpty()
    }

    @Test
    fun untimedRouteMatchesViaWalkAndKeepsDisjointWindowsSeparate() {
        val revision = TrailWalkRecord.routeRevision(untimedRoute)
        val walk1 = HikeWalk(
            id = "w-1",
            hikeId = hikeId,
            startedAtEpochMillis = t0,
            endedAtEpochMillis = t0 + 600_000L,
            distanceMeters = 550.0,
            durationSeconds = 600.0,
            coveredIntervals = listOf(0.0, 550.0),
            routeRevision = revision
        )
        val walk2 = HikeWalk(
            id = "w-2",
            hikeId = hikeId,
            startedAtEpochMillis = t0 + 86_400_000L,
            endedAtEpochMillis = t0 + 87_000_000L,
            distanceMeters = 550.0,
            durationSeconds = 600.0,
            coveredIntervals = listOf(0.0, 550.0),
            routeRevision = revision
        )
        val plan = HikePhotoSearchPlan.build(route = untimedRoute, walks = listOf(walk1, walk2))

        assertThat(plan.searchWindows).hasSize(2)
        val walkOnlyAsset = PhotoLibraryAsset("walk-clock", t0 + 300_000L, coordinate = null)
        val gpsAsset = PhotoLibraryAsset("walk-gps", t0 + 86_700_000L, coordinate = GeoCoordinate(47.7025, 12.8001))
        val matches = plan.matches(listOf(walkOnlyAsset, gpsAsset), alreadyImported = emptySet())

        assertThat(matches.map { it.id to it.evidence }).containsExactly(
            "walk-clock" to PhotoMatchEvidence.WALK,
            "walk-gps" to PhotoMatchEvidence.PLACE
        ).inOrder()
    }

    @Test
    fun fingerprintIgnoresElevationAndMotionButReactsToGeometryChange() {
        val elevationChanged = timedRoute.map { it.copy(elevationMeters = (it.elevationMeters ?: 0.0) + 50.0) }
        val moved = listOf(timedRoute[0], timedRoute[1].copy(latitude = 47.7060))

        val fpOriginal = HikePhotoSearchPlan.fingerprint(timedRoute, emptyList())
        val fpElevation = HikePhotoSearchPlan.fingerprint(elevationChanged, emptyList())
        val fpMoved = HikePhotoSearchPlan.fingerprint(moved, emptyList())

        assertThat(fpElevation).isEqualTo(fpOriginal)
        assertThat(fpMoved).isNotEqualTo(fpOriginal)
    }

    @Test
    fun pickedPhotoPlacementBypassesPlanForPlacesAndSnapsByPositionOtherwise() {
        val plan = HikePhotoSearchPlan.build(route = untimedRoute, walks = emptyList(), profileRoute = true)
        val fallback = GeoCoordinate(47.7000, 12.8000)
        val nearTrail = GeoCoordinate(47.7030, 12.8002)

        // Filing under a place passes plan = null -> uses place coordinate directly.
        val forPlace = PickedPhotoPlacement.resolve(
            metadata = PickedPhotoMetadata(capturedAtEpochMillis = t0, coordinate = nearTrail),
            plan = null,
            fallbackCoordinate = fallback,
            nowEpochMillis = t0 + 10_000L
        )
        assertThat(forPlace.coordinate).isEqualTo(fallback)
        assertThat(forPlace.matchEvidence).isNull()

        // Undated or other-day photo on an untimed trail still snaps via position-only tier.
        val snapped = PickedPhotoPlacement.resolve(
            metadata = PickedPhotoMetadata(capturedAtEpochMillis = null, coordinate = nearTrail),
            plan = plan,
            fallbackCoordinate = fallback,
            nowEpochMillis = t0 + 10_000L
        )
        assertThat(snapped.matchEvidence).isEqualTo(PhotoMatchEvidence.PLACE)
        assertThat(snapped.coordinate?.latitude).isWithin(1e-4).of(47.7030)
        assertThat(snapped.capturedAtEpochMillis).isEqualTo(t0 + 10_000L)
    }
}
