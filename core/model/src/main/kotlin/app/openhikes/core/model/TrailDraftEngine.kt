package app.openhikes.core.model

import kotlin.math.max

/**
 * Pure-JVM state engine owning all Trail Maker route geometry, stop-slot ordering, leg memoization,
 * alternative selection, travel-time estimation, and pin drag invariants.
 */
@Suppress("TooManyFunctions")
class TrailDraftEngine {
    private var _waypoints: List<TrailWaypoint> = emptyList()
    private var _legs: List<TrailLeg> = emptyList()
    private var _snapsToPaths: Boolean = true
    private var _travelMode: TrailTravelMode = TrailTravelMode.HIKING
    private var _startIsOpen: Boolean = false
    private var _distancesAlongLine: List<Double> = emptyList()
    private var _slots: List<TrailStopSlot> = TrailStopSlot.rows(emptyList(), startIsOpen = false)
    private var _drag: TrailWaypointDrag? = null
    private val memo = TrailLegMemo()
    private val choices = LinkedHashMap<TrailLegEnds, TrailLegChoice>()

    val waypoints: List<TrailWaypoint>
        get() = _waypoints
    val legs: List<TrailLeg>
        get() = _legs
    val snapsToPaths: Boolean
        get() = _snapsToPaths
    val travelMode: TrailTravelMode
        get() = _travelMode
    val startIsOpen: Boolean
        get() = _startIsOpen
    val distancesAlongLine: List<Double>
        get() = _distancesAlongLine
    val slots: List<TrailStopSlot>
        get() = _slots
    val drag: TrailWaypointDrag?
        get() = _drag

    val chosenLegs: List<TrailLegChoice>
        get() = _legs.mapNotNull { choices[it.ends] }

    val distanceMeters: Double
        get() = _distancesAlongLine.lastOrNull() ?: 0.0

    val canBeSaved: Boolean
        get() = _slots.none { it.waypointIndex == null }

    val isEmpty: Boolean
        get() = _waypoints.isEmpty()

    val isRouting: Boolean
        get() = _legs.any { it.snap.isRouting }

    val hasRetryableLegs: Boolean
        get() = _legs.any { it.snap.isRetryable }

    val worstLegSnap: TrailLegSnap?
        get() = _legs.firstOrNull { it.snap.isRetryable }?.snap
            ?: _legs.firstOrNull { it.snap.isDegraded }?.snap
            ?: _legs.firstOrNull { it.snap.isRouting }?.snap

    val routeCoordinates: List<RouteCoordinate>
        get() = if (_legs.isEmpty()) _waypoints.map { it.routeCoordinate } else TrailLeg.flattened(_legs)

    val coordinates: List<GeoCoordinate>
        get() = _waypoints.map { it.coordinate }

    val travelTimeSeconds: Double
        get() = _legs.sumOf { travelTimeSecondsOf(it.path) }

    fun travelTimeSeconds(climb: RouteClimb?): Double = travelTimeSeconds * climbFactor(climb)

    fun climbFactor(climb: RouteClimb?): Double {
        val flat = travelTimeSeconds
        return if (_travelMode != TrailTravelMode.HIKING || climb == null || flat <= 0.0) {
            1.0
        } else {
            val climbed = WalkingTimeEstimate.seconds(
                distanceMeters = distanceMeters,
                ascentMeters = climb.gainMeters,
                descentMeters = climb.lossMeters
            )
            max(1.0, climbed / flat)
        }
    }

    fun travelTimeSecondsOf(path: TrailLegPath): Double =
        path.travelTimeSeconds ?: (path.distanceMeters / _travelMode.paceMetersPerSecond)

    fun roleOfWaypointAt(index: Int): TrailStopRole = if (_waypoints.size == 1 && _startIsOpen) {
        TrailStopRole.End
    } else {
        TrailStopRole.of(index, _waypoints.size)
    }

    fun distanceAlongLineToWaypointAt(index: Int): Double = _distancesAlongLine.getOrElse(index) { 0.0 }

    fun legArrivingAtWaypointAt(index: Int): TrailLeg? = if (index > 0) _legs.getOrNull(index - 1) else null

    fun toStoredDraft(): StoredTrailDraft = StoredTrailDraft(
        waypoints = _waypoints,
        snapsToPaths = _snapsToPaths,
        travelMode = _travelMode,
        startIsOpen = _startIsOpen,
        chosenLegs = chosenLegs
    )

    fun replace(stored: StoredTrailDraft) {
        _drag = null
        _waypoints = stored.waypoints
        _travelMode = stored.travelMode
        _startIsOpen = stored.waypoints.size == 1 && stored.startIsOpen
        memo.clear()
        choices.clear()
        for (choice in stored.chosenLegs) choices[choice.ends] = choice
        _legs = emptyList()
        _snapsToPaths = stored.snapsToPaths
        rebuildLegs()
    }

    fun clear() {
        _drag = null
        memo.clear()
        choices.clear()
        if (isEmpty) return
        _waypoints = emptyList()
        _startIsOpen = false
        rebuildLegs()
    }

    fun append(coordinate: GeoCoordinate, name: String = "") {
        _waypoints = _waypoints + TrailWaypoint.at(coordinate, name = name)
        _startIsOpen = false
        rebuildLegs()
    }

    fun fillOpenField(coordinate: GeoCoordinate, name: String = ""): Boolean {
        val openSlot = _slots.firstOrNull { it is TrailStopSlot.Open } as? TrailStopSlot.Open ?: return false
        fill(openSlot.role, coordinate, name)
        return true
    }

    fun fill(role: TrailStopRole, coordinate: GeoCoordinate, name: String = "") {
        if (!_slots.contains(TrailStopSlot.Open(role))) return
        val point = TrailWaypoint.at(coordinate, name = name)
        _waypoints = if (role == TrailStopRole.Start) {
            listOf(point) + _waypoints
        } else {
            _waypoints + point
        }
        _startIsOpen = _waypoints.size == 1 && role == TrailStopRole.End
        rebuildLegs()
    }

    fun addStop(coordinate: GeoCoordinate, name: String = "", preferredLegEnds: TrailLegEnds? = null) {
        if (fillOpenField(coordinate, name)) return
        val preferredIndex = preferredLegEnds?.let { ends -> _legs.indexOfFirst { it.ends == ends } }
            ?.takeIf { it >= 0 }
        val targetLeg = preferredIndex ?: nearestLegIndex(coordinate) ?: return
        insert(coordinate, intoLegAt = targetLeg, name = name)
    }

    fun insert(coordinate: GeoCoordinate, intoLegAt: Int, name: String = "") {
        if (intoLegAt !in _legs.indices) return
        val updated = _waypoints.toMutableList()
        updated.add(intoLegAt + 1, TrailWaypoint.at(coordinate, name = name))
        _waypoints = updated
        rebuildLegs()
    }

    fun place(waypointId: String, coordinate: GeoCoordinate, name: String): Boolean {
        val index = _waypoints.indexOfFirst { it.id == waypointId }
        if (index < 0) return false
        return placeAt(index, coordinate, name)
    }

    fun move(waypointAt: Int, to: GeoCoordinate): Boolean = placeAt(waypointAt, to, name = "")

    fun placeAt(index: Int, coordinate: GeoCoordinate, name: String): Boolean {
        val current = _waypoints.getOrNull(index)
        val moved = current?.copy(latitude = coordinate.latitude, longitude = coordinate.longitude, name = name)
        val changed = moved != null && moved != current
        if (changed) {
            val updated = _waypoints.toMutableList()
            updated[index] = moved
            _waypoints = updated
            rebuildLegs()
        }
        return changed
    }

    fun describe(waypointId: String, expectedCoordinate: GeoCoordinate, name: String): Boolean {
        val index = if (name.isEmpty()) -1 else _waypoints.indexOfFirst { it.id == waypointId }
        val existing = _waypoints.getOrNull(index)
        val canDescribe = existing != null && existing.name.isEmpty() && existing.coordinate == expectedCoordinate
        if (canDescribe) {
            val updated = _waypoints.toMutableList()
            updated[index] = existing.copy(name = name)
            _waypoints = updated
        }
        return canDescribe
    }

    fun removeById(waypointId: String): Boolean {
        val index = _waypoints.indexOfFirst { it.id == waypointId }
        if (index < 0) return false
        return removeAt(index)
    }

    fun removeAt(index: Int): Boolean {
        if (index !in _waypoints.indices) return false
        val kept = _waypoints.mapIndexedNotNull { idx, wp -> if (idx == index) null else idx to wp }
        val wasDestination = kept.size == 1 && roleOfWaypointAt(kept[0].first) == TrailStopRole.End
        _waypoints = kept.map { it.second }
        _startIsOpen = wasDestination
        rebuildLegs()
        return true
    }

    fun arrangeRows(orderIds: List<String>): Boolean {
        val byId = _waypoints.associateBy { it.id }
        val uniqueOrder = LinkedHashSet(orderIds)
        val reordered = uniqueOrder.mapNotNull { byId[it] }
        val loneIsDestination = reordered.size == 1 && orderIds.firstOrNull() != reordered[0].id
        val changed = reordered.size == _waypoints.size &&
            (reordered != _waypoints || loneIsDestination != _startIsOpen)
        if (changed) {
            _waypoints = reordered
            _startIsOpen = loneIsDestination
            rebuildLegs()
        }
        return changed
    }

    fun moveWaypoint(fromIndex: Int, toIndex: Int): Boolean {
        if (fromIndex !in _waypoints.indices || toIndex !in _waypoints.indices || fromIndex == toIndex) return false
        val updated = _waypoints.toMutableList()
        val item = updated.removeAt(fromIndex)
        updated.add(toIndex, item)
        _waypoints = updated
        rebuildLegs()
        return true
    }

    fun setSnapsToPaths(snapping: Boolean): Boolean {
        if (_snapsToPaths == snapping) return false
        _snapsToPaths = snapping
        if (!snapping) straightenLegs()
        return true
    }

    fun setTravelMode(mode: TrailTravelMode): Boolean {
        if (_travelMode == mode) return false
        _drag = null
        _travelMode = mode
        memo.clear()
        choices.clear()
        straightenLegs()
        return true
    }

    fun chooseAlternative(legIndex: Int, alternativeIndex: Int): Boolean {
        val chosen = _legs.getOrNull(legIndex)?.choosing(alternativeIndex)
        if (chosen != null) {
            choices[chosen.ends] = TrailLegChoice.fromLeg(chosen)
            val updated = _legs.toMutableList()
            updated[legIndex] = chosen
            publish(updated)
        }
        return chosen != null
    }

    fun legsAwaitingRoutes(retryingRefusals: Boolean): List<TrailLegEnds> {
        if (!_snapsToPaths) return emptyList()
        return _legs.mapNotNull { leg ->
            when (leg.snap) {
                TrailLegSnap.Freehand -> leg.ends
                is TrailLegSnap.Refused -> if (retryingRefusals) leg.ends else null
                TrailLegSnap.Routing, TrailLegSnap.Snapped, is TrailLegSnap.Unmapped -> null
            }
        }
    }

    fun beginRouting(pending: List<TrailLegEnds>) {
        if (pending.isEmpty()) return
        val wanted = pending.toHashSet()
        publish(
            _legs.map { leg ->
                if (wanted.contains(leg.ends)) leg.copy(snap = TrailLegSnap.Routing) else leg
            }
        )
    }

    fun applyRoute(ends: TrailLegEnds, route: TrailLegRoute) {
        val chosen = if (route.snap.offersWays) choices[ends] else null
        publish(
            _legs.map { leg ->
                if (leg.ends != ends || !leg.snap.isRouting) {
                    leg
                } else {
                    val base = leg.copy(
                        coordinates = route.coordinates,
                        distanceMeters = route.distanceMeters,
                        snap = route.snap,
                        travelTimeSeconds = route.travelTimeSeconds,
                        alternatives = route.alternatives
                    )
                    if (chosen != null) {
                        val applied = base.taking(chosen)
                        choices[ends] = TrailLegChoice.fromLeg(applied)
                        applied
                    } else {
                        base
                    }
                }
            }
        )
    }

    fun abandonRouting(ends: TrailLegEnds) {
        publish(
            _legs.map { leg ->
                if (leg.ends == ends && leg.snap.isRouting) leg.copy(snap = TrailLegSnap.Freehand) else leg
            }
        )
    }

    fun stopRouting() {
        publish(
            _legs.map { leg ->
                if (leg.snap.isRouting) leg.copy(snap = TrailLegSnap.Freehand) else leg
            }
        )
    }

    fun beginDrag(waypointIndex: Int): Boolean {
        val wp = _waypoints.getOrNull(waypointIndex) ?: return false
        _drag = TrailWaypointDrag(index = waypointIndex, coordinate = wp.coordinate)
        return true
    }

    fun moveDrag(to: GeoCoordinate): Boolean {
        val current = _drag
        val moved = current != null && current.coordinate != to
        if (moved) {
            _drag = current.copy(coordinate = to)
        }
        return moved
    }

    fun endDrag(): Boolean {
        val finished = _drag
        _drag = null
        val before = finished?.let { _waypoints.getOrNull(it.index) }
        val shouldMove = finished != null && before != null && before.coordinate != finished.coordinate
        if (shouldMove) {
            move(finished.index, finished.coordinate)
        }
        return shouldMove
    }

    fun cancelDrag(): Boolean {
        if (_drag == null) return false
        _drag = null
        return true
    }

    fun nearestLegIndex(coordinate: GeoCoordinate): Int? {
        var bestIndex: Int? = null
        var bestDistance = Double.POSITIVE_INFINITY
        _legs.forEachIndexed { index, leg ->
            val shape = if (leg.coordinates.size > 1) leg.coordinates else leg.ends.straightCoordinates
            for (i in 0 until shape.lastIndex) {
                val projection = RouteGeometry.project(coordinate, shape[i], shape[i + 1])
                if (projection.offRouteMeters < bestDistance) {
                    bestDistance = projection.offRouteMeters
                    bestIndex = index
                }
            }
        }
        return bestIndex
    }

    private fun rebuildLegs() {
        val resolved = _legs.associateBy { it.ends }
        val rebuilt = ArrayList<TrailLeg>(max(0, _waypoints.size - 1))
        for (i in 0 until _waypoints.lastIndex) {
            val prev = _waypoints[i]
            val next = _waypoints[i + 1]
            val ends = TrailLegEnds.between(prev, next)
            val existing = resolved[ends]
            val remembered = if (_snapsToPaths) memo.leg(ends, arrivingAtId = next.id) else null
            rebuilt.add(
                when {
                    existing != null -> existing.copy(id = next.id)
                    remembered != null -> remembered
                    else -> TrailLeg.straight(arrivingAtId = next.id, along = ends)
                }
            )
        }
        _slots = TrailStopSlot.rows(_waypoints, _startIsOpen)
        publish(rebuilt)
    }

    private fun straightenLegs() {
        publish(
            _legs.map { leg ->
                leg.copy(
                    coordinates = leg.ends.straightCoordinates,
                    distanceMeters = leg.ends.straightDistanceMeters,
                    snap = TrailLegSnap.Freehand,
                    travelTimeSeconds = null,
                    alternatives = emptyList()
                )
            }
        )
    }

    private fun publish(rebuilt: List<TrailLeg>) {
        memo.rememberAll(rebuilt)
        _legs = rebuilt
        _distancesAlongLine = computeDistances(rebuilt, _waypoints.size)
    }

    private fun computeDistances(legs: List<TrailLeg>, pointCount: Int): List<Double> {
        if (pointCount <= 0) return emptyList()
        val totals = ArrayList<Double>(pointCount)
        var running = 0.0
        totals.add(0.0)
        for (leg in legs) {
            running += leg.distanceMeters
            totals.add(running)
        }
        return totals
    }
}
