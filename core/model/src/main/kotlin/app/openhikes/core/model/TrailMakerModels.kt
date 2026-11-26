package app.openhikes.core.model

import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * Travel mode selected in Trail Maker, governing pace estimation and routing behavior.
 */
@Serializable
enum class TrailTravelMode(val id: String, val paceKmPerHour: Double) {
    HIKING(id = "hiking", paceKmPerHour = 4.0),
    WALKING(id = "walking", paceKmPerHour = 5.0),
    CYCLING(id = "cycling", paceKmPerHour = 15.0),
    DRIVING(id = "driving", paceKmPerHour = 50.0);

    val paceMetersPerSecond: Double
        get() = paceKmPerHour * METERS_PER_KILOMETER / SECONDS_PER_HOUR

    companion object {
        val ordered: List<TrailTravelMode> = listOf(HIKING, WALKING, CYCLING, DRIVING)
        private const val METERS_PER_KILOMETER = 1_000.0
        private const val SECONDS_PER_HOUR = 3_600.0
    }
}

/**
 * One stop placed by the hiker in Trail Maker.
 */
@Serializable
data class TrailWaypoint(
    val id: String = UUID.randomUUID().toString(),
    val latitude: Double,
    val longitude: Double,
    val name: String = ""
) {
    val coordinate: GeoCoordinate
        get() = GeoCoordinate(latitude = latitude, longitude = longitude)

    val routeCoordinate: RouteCoordinate
        get() = RouteCoordinate(latitude = latitude, longitude = longitude)

    companion object {
        fun at(coordinate: GeoCoordinate, name: String = "", id: String = UUID.randomUUID().toString()): TrailWaypoint =
            TrailWaypoint(
                id = id,
                latitude = coordinate.latitude,
                longitude = coordinate.longitude,
                name = name
            )
    }
}

/**
 * Derived positional role of a stop along a route (never persisted separately from list order).
 */
sealed interface TrailStopRole {
    data object Start : TrailStopRole

    data class Stop(val number: Int) : TrailStopRole

    data object End : TrailStopRole

    companion object {
        fun of(waypointIndex: Int, count: Int): TrailStopRole = when {
            waypointIndex == 0 -> Start
            waypointIndex == count - 1 -> End
            else -> Stop(number = waypointIndex)
        }
    }
}

/**
 * Row model for the Trail Maker stop list: either an empty open field (Start/Destination) or a placed stop.
 */
sealed interface TrailStopSlot {
    val id: String
    val waypointIndex: Int?
    val stopId: String?

    data class Open(val role: TrailStopRole) : TrailStopSlot {
        override val id: String = if (role == TrailStopRole.Start) OPEN_START_ID else OPEN_END_ID
        override val waypointIndex: Int? = null
        override val stopId: String? = null
    }

    data class Point(override val waypointIndex: Int, override val stopId: String) : TrailStopSlot {
        override val id: String = stopId
    }

    companion object {
        const val OPEN_START_ID = "open-start"
        const val OPEN_END_ID = "open-end"

        fun rows(waypoints: List<TrailWaypoint>, startIsOpen: Boolean): List<TrailStopSlot> {
            val points = waypoints.mapIndexed { index, wp -> Point(index, wp.id) }
            return when (waypoints.size) {
                0 -> listOf(Open(TrailStopRole.Start), Open(TrailStopRole.End))
                1 -> if (startIsOpen) {
                    listOf(Open(TrailStopRole.Start)) + points
                } else {
                    points + listOf(Open(TrailStopRole.End))
                }
                else -> points
            }
        }

        fun rearranged(ids: List<String>, moving: List<String>, before: String?): List<String> {
            val movingSet = moving.toHashSet()
            val staying = ids.filterNot { movingSet.contains(it) }.toMutableList()
            val moved = ids.filter { movingSet.contains(it) }
            val targetIdx = before?.let { staying.indexOf(it).takeIf { idx -> idx >= 0 } } ?: staying.size
            staying.addAll(targetIdx, moved)
            return staying
        }
    }
}

/**
 * Geometric endpoints identifying one leg between two consecutive stops.
 */
@Serializable
data class TrailLegEnds(val start: GeoCoordinate, val end: GeoCoordinate) {
    val straightCoordinates: List<GeoCoordinate>
        get() = listOf(start, end)

    val straightDistanceMeters: Double
        get() = RouteGeometry.distanceMeters(start, end)

    companion object {
        fun between(from: TrailWaypoint, to: TrailWaypoint): TrailLegEnds =
            TrailLegEnds(start = from.coordinate, end = to.coordinate)
    }
}

/**
 * Settled reason why a leg could not follow a mapped path.
 */
enum class TrailLegGap {
    NO_PATH_BETWEEN,
    TOO_FAR_APART,
    NO_DIRECTIONS,
    ENDPOINT_OFF_NETWORK
}

/**
 * Transient provider refusal that can be retried via *Try Again*.
 */
enum class TrailLegOutage {
    OFFLINE,
    RATE_LIMITED,
    UNAVAILABLE
}

/**
 * Resolution status of a single leg in a drawn trail.
 */
sealed interface TrailLegSnap {
    data object Freehand : TrailLegSnap

    data object Routing : TrailLegSnap

    data object Snapped : TrailLegSnap

    data class Unmapped(val gap: TrailLegGap) : TrailLegSnap

    data class Refused(val outage: TrailLegOutage) : TrailLegSnap

    val isRouting: Boolean
        get() = this is Routing

    val isRetryable: Boolean
        get() = this is Refused

    val isDegraded: Boolean
        get() = this is Unmapped || this is Refused

    val isWarning: Boolean
        get() = this is Refused || (this is Unmapped && gap == TrailLegGap.ENDPOINT_OFF_NETWORK)

    val offersWays: Boolean
        get() = this is Snapped || (this is Unmapped && gap == TrailLegGap.ENDPOINT_OFF_NETWORK)
}

/**
 * One candidate geometry for a leg (either the active path or a selectable grey alternative).
 */
data class TrailLegPath(
    val coordinates: List<GeoCoordinate>,
    val distanceMeters: Double,
    val travelTimeSeconds: Double? = null,
    val snap: TrailLegSnap? = null
)

/**
 * Persisted hiker preference for an alternative path between two leg endpoints.
 */
@Serializable
data class TrailLegChoice(
    val start: GeoCoordinate,
    val end: GeoCoordinate,
    val coordinates: List<GeoCoordinate>,
    val distanceMeters: Double,
    val travelTimeSeconds: Double? = null,
    val hasUncheckedAccess: Boolean = false
) {
    val ends: TrailLegEnds
        get() = TrailLegEnds(start, end)

    val way: TrailLegPath
        get() = TrailLegPath(coordinates, distanceMeters, travelTimeSeconds)

    companion object {
        fun fromLeg(leg: TrailLeg): TrailLegChoice = TrailLegChoice(
            start = leg.ends.start,
            end = leg.ends.end,
            coordinates = leg.coordinates,
            distanceMeters = leg.distanceMeters,
            travelTimeSeconds = leg.travelTimeSeconds,
            hasUncheckedAccess = leg.snap == TrailLegSnap.Unmapped(TrailLegGap.ENDPOINT_OFF_NETWORK)
        )
    }
}

/**
 * Resolved stretch running between two consecutive stops in [TrailDraftEngine].
 */
data class TrailLeg(
    val id: String,
    val ends: TrailLegEnds,
    val coordinates: List<GeoCoordinate>,
    val distanceMeters: Double,
    val snap: TrailLegSnap,
    val travelTimeSeconds: Double? = null,
    val alternatives: List<TrailLegPath> = emptyList()
) {
    val path: TrailLegPath
        get() = TrailLegPath(coordinates, distanceMeters, travelTimeSeconds)

    fun choosing(alternativeIndex: Int): TrailLeg? {
        if (alternativeIndex !in alternatives.indices) return null
        val taken = alternatives[alternativeIndex]
        val displaced = path.copy(snap = if (taken.snap != null) snap else null)
        val updatedAlts = alternatives.toMutableList()
        updatedAlts[alternativeIndex] = displaced
        return copy(
            coordinates = taken.coordinates,
            distanceMeters = taken.distanceMeters,
            travelTimeSeconds = taken.travelTimeSeconds,
            snap = taken.snap ?: snap,
            alternatives = updatedAlts
        )
    }

    fun taking(choice: TrailLegChoice): TrailLeg {
        val way = choice.way
        val matchIdx = alternatives.indexOfFirst { it.coordinates == way.coordinates }
        return when {
            way.coordinates == coordinates -> this
            matchIdx >= 0 -> choosing(matchIdx) ?: this
            else -> {
                val offered = (listOf(path) + alternatives).map { it.copy(snap = it.snap ?: snap) }
                copy(
                    coordinates = way.coordinates,
                    distanceMeters = way.distanceMeters,
                    travelTimeSeconds = way.travelTimeSeconds,
                    snap = if (choice.hasUncheckedAccess) {
                        TrailLegSnap.Unmapped(TrailLegGap.ENDPOINT_OFF_NETWORK)
                    } else {
                        TrailLegSnap.Snapped
                    },
                    alternatives = offered
                )
            }
        }
    }

    companion object {
        fun straight(arrivingAtId: String, along: TrailLegEnds): TrailLeg = TrailLeg(
            id = arrivingAtId,
            ends = along,
            coordinates = along.straightCoordinates,
            distanceMeters = along.straightDistanceMeters,
            snap = TrailLegSnap.Freehand
        )

        fun flattened(legs: List<TrailLeg>): List<RouteCoordinate> {
            if (legs.isEmpty()) return emptyList()
            val out = ArrayList<RouteCoordinate>()
            legs.forEachIndexed { index, leg ->
                val slice = if (index == 0) leg.coordinates else leg.coordinates.drop(1)
                for (coord in slice) {
                    out.add(RouteCoordinate(latitude = coord.latitude, longitude = coord.longitude))
                }
            }
            return out
        }
    }
}

/**
 * Router answer for a single [TrailLegEnds].
 */
data class TrailLegRoute(
    val coordinates: List<GeoCoordinate>,
    val distanceMeters: Double,
    val snap: TrailLegSnap,
    val travelTimeSeconds: Double? = null,
    val alternatives: List<TrailLegPath> = emptyList()
) {
    companion object {
        fun straight(along: TrailLegEnds, snap: TrailLegSnap): TrailLegRoute = TrailLegRoute(
            coordinates = along.straightCoordinates,
            distanceMeters = along.straightDistanceMeters,
            snap = snap
        )
    }
}

/**
 * Bounded insertion-order memo remembering settled leg shapes during a Trail Maker session.
 */
class TrailLegMemo {
    private val shapes = LinkedHashMap<TrailLegEnds, TrailLeg>()

    val size: Int
        get() = shapes.size

    fun isEmpty(): Boolean = shapes.isEmpty()

    fun remember(leg: TrailLeg) {
        if (!isWorthRemembering(leg.snap)) return
        shapes[leg.ends] = leg
        evictOldest()
    }

    fun rememberAll(legs: List<TrailLeg>) {
        for (leg in legs) remember(leg)
    }

    fun leg(ends: TrailLegEnds, arrivingAtId: String): TrailLeg? = shapes[ends]?.copy(id = arrivingAtId)

    fun clear() {
        shapes.clear()
    }

    private fun evictOldest() {
        val iterator = shapes.entries.iterator()
        while (shapes.size > CAPACITY && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    companion object {
        const val CAPACITY = 256

        private fun isWorthRemembering(snap: TrailLegSnap): Boolean =
            snap is TrailLegSnap.Snapped || snap is TrailLegSnap.Unmapped
    }
}

/**
 * Bounded oldest-first cache of settled [TrailLegRoute] answers owned by a router across sessions.
 */
class TrailLegAnswerCache {
    private val answers = LinkedHashMap<TrailLegEnds, TrailLegRoute>()

    val size: Int
        get() = answers.size

    operator fun get(ends: TrailLegEnds): TrailLegRoute? = answers[ends]

    fun store(ends: TrailLegEnds, route: TrailLegRoute) {
        if (route.snap.isRetryable) return
        answers[ends] = route
        val iterator = answers.entries.iterator()
        while (answers.size > TrailLegMemo.CAPACITY && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    fun clear() {
        answers.clear()
    }
}

/**
 * Seam for routing a single drawn leg between two stops.
 */
interface TrailLegRouter {
    fun supportsMode(mode: TrailTravelMode): Boolean

    suspend fun route(ends: TrailLegEnds, mode: TrailTravelMode): TrailLegRoute?
}

/**
 * Pin dropped on the map via long-press, carrying the leg it landed on (if any).
 */
data class TrailDraftDroppedPin(val coordinate: GeoCoordinate, val legEnds: TrailLegEnds? = null, val name: String = "")

/**
 * What the Trail Maker place card sheet is currently presenting.
 */
sealed interface TrailDraftSelection {
    data object DroppedPin : TrailDraftSelection

    data class Stop(val waypointId: String) : TrailDraftSelection
}

/**
 * Live coordinate of a stop pin currently dragged on the map.
 */
data class TrailWaypointDrag(val index: Int, val coordinate: GeoCoordinate) {
    val adjacentLegIndices: List<Int>
        get() = listOf(index - 1, index)
}

/**
 * Place recently picked in Trail Maker stop search.
 */
@Serializable
data class TrailStopRecent(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val subtitle: String = "",
    val latitude: Double,
    val longitude: Double
) {
    val coordinate: GeoCoordinate
        get() = GeoCoordinate(latitude = latitude, longitude = longitude)

    fun isSamePlaceAs(other: TrailStopRecent): Boolean =
        name == other.name && RouteGeometry.distanceMeters(coordinate, other.coordinate) <= SAME_PLACE_METERS

    companion object {
        const val LIMIT = 10
        private const val SAME_PLACE_METERS = 50.0
    }
}

/**
 * Search candidate presented in the Trail Maker stop search sheet.
 */
data class TrailStopSearchResult(val id: String, val name: String, val subtitle: String, val coordinate: GeoCoordinate)

/**
 * Persisted device-local snapshot of an in-progress drawn trail.
 */
@Serializable
data class StoredTrailDraft(
    val waypoints: List<TrailWaypoint> = emptyList(),
    val snapsToPaths: Boolean = true,
    val travelMode: TrailTravelMode = TrailTravelMode.HIKING,
    val startIsOpen: Boolean = false,
    val chosenLegs: List<TrailLegChoice> = emptyList()
) {
    val isEmpty: Boolean
        get() = waypoints.isEmpty()

    companion object {
        val Empty = StoredTrailDraft()
    }
}

/**
 * Refusal reason when a drawn trail cannot be saved as a hike.
 */
enum class TrailDraftRefusal {
    TOO_SHORT,
    NOT_SAVED
}

/**
 * Result of saving a drawn trail into the hike library.
 */
sealed interface TrailDraftSaveOutcome {
    data class Saved(val hike: Hike) : TrailDraftSaveOutcome

    data class Refused(val refusal: TrailDraftRefusal) : TrailDraftSaveOutcome
}
