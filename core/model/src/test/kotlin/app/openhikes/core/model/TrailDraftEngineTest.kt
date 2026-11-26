package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrailDraftEngineTest {
    private val pointA = GeoCoordinate(47.7180, 12.8300)
    private val pointB = GeoCoordinate(47.7190, 12.8310)
    private val pointC = GeoCoordinate(47.7200, 12.8320)
    private val pointMid = GeoCoordinate(47.7185, 12.8305)

    @Test
    fun emptyDraftOpensWithStartAndDestinationSlotsAndCannotBeSaved() {
        val engine = TrailDraftEngine()

        assertThat(engine.isEmpty).isTrue()
        assertThat(engine.canBeSaved).isFalse()
        assertThat(engine.slots).containsExactly(
            TrailStopSlot.Open(TrailStopRole.Start),
            TrailStopSlot.Open(TrailStopRole.End)
        ).inOrder()
    }

    @Test
    fun fillingDestinationFirstKeepsStartOpenAndAssignsEndRole() {
        val engine = TrailDraftEngine()
        engine.fill(TrailStopRole.End, pointB, name = "Summit")

        assertThat(engine.startIsOpen).isTrue()
        assertThat(engine.canBeSaved).isFalse()
        assertThat(engine.roleOfWaypointAt(0)).isEqualTo(TrailStopRole.End)
        assertThat(engine.slots.first()).isEqualTo(TrailStopSlot.Open(TrailStopRole.Start))

        engine.fill(TrailStopRole.Start, pointA, name = "Trailhead")
        assertThat(engine.startIsOpen).isFalse()
        assertThat(engine.canBeSaved).isTrue()
        assertThat(engine.waypoints.map { it.name }).containsExactly("Trailhead", "Summit").inOrder()
    }

    @Test
    fun deletingStartOfTwoPointRouteKeepsDestinationAndReopensStart() {
        val engine = TrailDraftEngine()
        engine.append(pointA, name = "Start Spot")
        engine.append(pointB, name = "End Spot")

        engine.removeAt(0)

        assertThat(engine.waypoints).hasSize(1)
        assertThat(engine.waypoints[0].name).isEqualTo("End Spot")
        assertThat(engine.startIsOpen).isTrue()
        assertThat(engine.roleOfWaypointAt(0)).isEqualTo(TrailStopRole.End)
        assertThat(engine.canBeSaved).isFalse()
    }

    @Test
    fun addStopFillsOpenFieldsFirstThenInsertsIntoPreferredOrNearestLeg() {
        val engine = TrailDraftEngine()
        engine.addStop(pointA, name = "A")
        engine.addStop(pointC, name = "C")
        val originalEnds = engine.legs.first().ends

        engine.addStop(pointMid, name = "Mid", preferredLegEnds = originalEnds)

        assertThat(engine.waypoints.map { it.name }).containsExactly("A", "Mid", "C").inOrder()
        assertThat(engine.roleOfWaypointAt(0)).isEqualTo(TrailStopRole.Start)
        assertThat(engine.roleOfWaypointAt(1)).isEqualTo(TrailStopRole.Stop(1))
        assertThat(engine.roleOfWaypointAt(2)).isEqualTo(TrailStopRole.End)
    }

    @Test
    fun memoRestoresSnappedShapeAfterDeleteAndReinsertAndRespectsSnappingToggle() {
        val engine = TrailDraftEngine()
        engine.append(pointA)
        engine.append(pointB)
        val ends = engine.legs.first().ends
        val bent = listOf(pointA, pointMid, pointB)
        val bentDistance = RouteGeometry.lengthMeters(bent)
        engine.beginRouting(listOf(ends))
        engine.applyRoute(
            ends,
            TrailLegRoute(coordinates = bent, distanceMeters = bentDistance, snap = TrailLegSnap.Snapped)
        )
        assertThat(engine.legs.first().snap).isEqualTo(TrailLegSnap.Snapped)

        // Delete B and re-add B -> memo restores snapped geometry immediately.
        engine.removeAt(1)
        engine.append(pointB)
        assertThat(engine.legs.first().snap).isEqualTo(TrailLegSnap.Snapped)
        assertThat(engine.legs.first().coordinates).isEqualTo(bent)

        // Turning snapping off straightens and keeps stops.
        engine.setSnapsToPaths(false)
        assertThat(engine.waypoints).hasSize(2)
        assertThat(engine.legs.first().snap).isEqualTo(TrailLegSnap.Freehand)
        assertThat(engine.legs.first().coordinates).containsExactly(pointA, pointB).inOrder()
    }

    @Test
    fun chosenAlternativeSurvivesPersistRestoreAndReroute() {
        val engine = TrailDraftEngine()
        engine.append(pointA)
        engine.append(pointC)
        val ends = engine.legs.first().ends
        val mainShape = listOf(pointA, GeoCoordinate(47.7190, 12.8300), pointC)
        val altShape = listOf(pointA, GeoCoordinate(47.7190, 12.8325), pointC)
        val route = TrailLegRoute(
            coordinates = mainShape,
            distanceMeters = RouteGeometry.lengthMeters(mainShape),
            snap = TrailLegSnap.Snapped,
            alternatives = listOf(TrailLegPath(altShape, RouteGeometry.lengthMeters(altShape)))
        )

        engine.beginRouting(listOf(ends))
        engine.applyRoute(ends, route)
        assertThat(engine.chooseAlternative(legIndex = 0, alternativeIndex = 0)).isTrue()
        assertThat(engine.legs.first().coordinates).isEqualTo(altShape)

        val stored = engine.toStoredDraft()
        val restored = TrailDraftEngine().apply { replace(stored) }
        restored.beginRouting(listOf(ends))
        restored.applyRoute(ends, route)
        assertThat(restored.legs.first().coordinates).isEqualTo(altShape)
        assertThat(restored.legs.first().alternatives.first().coordinates).isEqualTo(mainShape)
    }

    @Test
    fun travelModeChangeStraightensAndClearsMemoAndChoices() {
        val engine = TrailDraftEngine()
        engine.append(pointA)
        engine.append(pointB)
        val ends = engine.legs.first().ends
        engine.beginRouting(listOf(ends))
        engine.applyRoute(
            ends,
            TrailLegRoute(listOf(pointA, pointMid, pointB), 120.0, TrailLegSnap.Snapped)
        )

        engine.setTravelMode(TrailTravelMode.CYCLING)

        assertThat(engine.travelMode).isEqualTo(TrailTravelMode.CYCLING)
        assertThat(engine.waypoints).hasSize(2)
        assertThat(engine.legs.first().snap).isEqualTo(TrailLegSnap.Freehand)
    }

    @Test
    fun dragWithoutMovementPreservesNameWhileActualMoveClearsName() {
        val engine = TrailDraftEngine()
        engine.append(pointA, name = "Car Park")
        engine.append(pointB, name = "Hut")

        assertThat(engine.beginDrag(0)).isTrue()
        assertThat(engine.endDrag()).isFalse()
        assertThat(engine.waypoints[0].name).isEqualTo("Car Park")

        assertThat(engine.beginDrag(0)).isTrue()
        assertThat(engine.moveDrag(pointMid)).isTrue()
        assertThat(engine.endDrag()).isTrue()
        assertThat(engine.waypoints[0].coordinate).isEqualTo(pointMid)
        assertThat(engine.waypoints[0].name).isEmpty()
    }

    @Test
    fun describeIgnoresAlreadyNamedOrMovedStops() {
        val engine = TrailDraftEngine()
        engine.append(pointA)
        val id = engine.waypoints.first().id

        assertThat(engine.describe(id, pointB, "Stale Name")).isFalse()
        assertThat(engine.describe(id, pointA, "Valley Trailhead")).isTrue()
        assertThat(engine.waypoints.first().name).isEqualTo("Valley Trailhead")
        assertThat(engine.describe(id, pointA, "Overwrite Attempt")).isFalse()
        assertThat(engine.waypoints.first().name).isEqualTo("Valley Trailhead")
    }

    @Test
    fun worstLegSnapPrioritizesRefusedOverDegradedOverRouting() {
        val engine = TrailDraftEngine()
        engine.append(pointA)
        engine.append(pointB)
        engine.append(pointC)
        val firstEnds = engine.legs[0].ends
        val secondEnds = engine.legs[1].ends

        engine.beginRouting(listOf(firstEnds, secondEnds))
        assertThat(engine.worstLegSnap).isEqualTo(TrailLegSnap.Routing)

        engine.applyRoute(
            firstEnds,
            TrailLegRoute.straight(firstEnds, TrailLegSnap.Unmapped(TrailLegGap.NO_PATH_BETWEEN))
        )
        assertThat(engine.worstLegSnap).isEqualTo(TrailLegSnap.Unmapped(TrailLegGap.NO_PATH_BETWEEN))

        engine.applyRoute(secondEnds, TrailLegRoute.straight(secondEnds, TrailLegSnap.Refused(TrailLegOutage.OFFLINE)))
        assertThat(engine.worstLegSnap).isEqualTo(TrailLegSnap.Refused(TrailLegOutage.OFFLINE))
        assertThat(engine.hasRetryableLegs).isTrue()
        assertThat(engine.legsAwaitingRoutes(retryingRefusals = false)).isEmpty()
        assertThat(engine.legsAwaitingRoutes(retryingRefusals = true)).containsExactly(secondEnds)
    }

    @Test
    fun memoEvictsOldestEntriesBeyondCapacity() {
        val memo = TrailLegMemo()
        for (i in 0 until TrailLegMemo.CAPACITY + 5) {
            val ends = TrailLegEnds(
                GeoCoordinate(47.0 + i * 0.001, 12.0),
                GeoCoordinate(47.0 + i * 0.001, 12.001)
            )
            memo.remember(
                TrailLeg(
                    id = "leg-$i",
                    ends = ends,
                    coordinates = ends.straightCoordinates,
                    distanceMeters = ends.straightDistanceMeters,
                    snap = TrailLegSnap.Snapped
                )
            )
        }

        assertThat(memo.size).isEqualTo(TrailLegMemo.CAPACITY)
        val evicted = TrailLegEnds(GeoCoordinate(47.0, 12.0), GeoCoordinate(47.0, 12.001))
        assertThat(memo.leg(evicted, arrivingAtId = "x")).isNull()
    }
}
