package app.openhikes.feature.trailmaker

import app.openhikes.core.map.MapTrailDraftAlternative
import app.openhikes.core.map.MapTrailDraftLeg
import app.openhikes.core.map.MapTrailDraftOverlay
import app.openhikes.core.map.MapTrailDraftPin
import app.openhikes.core.map.MapTrailDraftTimeBubble
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.RouteGeometry
import app.openhikes.core.model.TrailDraftDroppedPin
import app.openhikes.core.model.TrailDraftEngine
import app.openhikes.core.model.TrailLeg
import app.openhikes.core.model.TrailStopRole
import app.openhikes.core.ui.format.HikeMetricsFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Pure helper mapping [TrailDraftEngine] geometry and active drag state into [MapTrailDraftOverlay]
 * and formatted coordinates for the place card.
 */
internal object TrailMakerOverlayBuilder {
    private const val COORDINATE_DECIMALS = 5
    private const val HALF = 2.0

    fun build(engine: TrailDraftEngine, droppedPin: TrailDraftDroppedPin?): MapTrailDraftOverlay {
        val legs = engine.legs
        val tripTimeSeconds = engine.travelTimeSeconds
        val mapLegs = buildMapLegs(engine, legs, tripTimeSeconds)
        val pins = buildMapPins(engine)
        val routeBubble = if (legs.isNotEmpty() && !engine.isRouting) {
            routeTimeAnchor(legs)?.let { anchor ->
                MapTrailDraftTimeBubble(
                    coordinate = anchor,
                    label = HikeMetricsFormatter.formatDuration(tripTimeSeconds),
                    spokenDescription = HikeMetricsFormatter.formatSpokenDuration(tripTimeSeconds)
                )
            }
        } else {
            null
        }
        return MapTrailDraftOverlay(
            legs = mapLegs,
            pins = pins,
            droppedPin = droppedPin,
            routeTimeBubble = routeBubble,
            snapsToPaths = engine.snapsToPaths,
            draggedPinIndex = engine.drag?.index,
            draggedCoordinate = engine.drag?.coordinate
        )
    }

    private fun buildMapLegs(
        engine: TrailDraftEngine,
        legs: List<TrailLeg>,
        tripTimeSeconds: Double
    ): List<MapTrailDraftLeg> {
        val legTimes = legs.map { engine.travelTimeSecondsOf(it.path) }
        return legs.mapIndexed { legIndex, leg ->
            val alternatives = if (leg.snap.isRouting) {
                emptyList()
            } else {
                leg.alternatives.mapIndexed { altIndex, alt ->
                    val altTripSeconds = tripTimeSeconds - legTimes[legIndex] + engine.travelTimeSecondsOf(alt)
                    val spokenTime = HikeMetricsFormatter.formatSpokenDuration(altTripSeconds)
                    MapTrailDraftAlternative(
                        legIndex = legIndex,
                        alternativeIndex = altIndex,
                        coordinates = alt.coordinates,
                        midpoint = midpoint(alt.coordinates),
                        formattedTime = HikeMetricsFormatter.formatDuration(altTripSeconds),
                        spokenDescription = "Alternative route, $spokenTime"
                    )
                }
            }
            MapTrailDraftLeg(
                id = leg.id,
                ends = leg.ends,
                coordinates = leg.coordinates,
                snap = leg.snap,
                alternatives = alternatives
            )
        }
    }

    private fun buildMapPins(engine: TrailDraftEngine): List<MapTrailDraftPin> =
        engine.waypoints.mapIndexed { index, wp ->
            val role = engine.roleOfWaypointAt(index)
            val roleLabel = roleFallbackLabel(role)
            MapTrailDraftPin(
                id = wp.id,
                index = index,
                coordinate = wp.coordinate,
                role = role,
                label = wp.name.ifBlank { roleLabel },
                badgeText = when (role) {
                    TrailStopRole.Start, TrailStopRole.End -> ""
                    is TrailStopRole.Stop -> role.number.toString()
                }
            )
        }

    fun formatCoordinates(coordinate: GeoCoordinate, locale: Locale = Locale.getDefault()): String {
        val northSouth = if (coordinate.latitude >= 0.0) "N" else "S"
        val eastWest = if (coordinate.longitude >= 0.0) "E" else "W"
        val lat = String.format(locale, "%.${COORDINATE_DECIMALS}f", abs(coordinate.latitude))
        val lon = String.format(locale, "%.${COORDINATE_DECIMALS}f", abs(coordinate.longitude))
        return "$lat° $northSouth, $lon° $eastWest"
    }

    fun roleFallbackLabel(role: TrailStopRole): String = when (role) {
        TrailStopRole.Start -> "Start"
        TrailStopRole.End -> "Destination"
        is TrailStopRole.Stop -> "Stop ${role.number}"
    }

    internal fun routeTimeAnchor(legs: List<TrailLeg>): GeoCoordinate? {
        val longest = legs.maxByOrNull { it.distanceMeters } ?: return null
        return midpoint(longest.coordinates)
    }

    internal fun midpoint(shape: List<GeoCoordinate>): GeoCoordinate = when (shape.size) {
        0 -> GeoCoordinate(0.0, 0.0)
        1 -> shape.first()
        else -> interpolateMidpoint(shape)
    }

    private fun interpolateMidpoint(shape: List<GeoCoordinate>): GeoCoordinate {
        val lengths = DoubleArray(shape.lastIndex) { i ->
            RouteGeometry.distanceMeters(shape[i], shape[i + 1])
        }
        var remaining = lengths.sum() / HALF
        var result = shape.last()
        var index = 0
        var found = false
        while (index < lengths.size && !found) {
            val segLen = lengths[index]
            if (remaining <= segLen) {
                val fraction = if (segLen > 0.0) remaining / segLen else 0.0
                result = RouteGeometry.interpolate(shape[index], shape[index + 1], fraction)
                found = true
            } else {
                remaining -= segLen
                index += 1
            }
        }
        return result
    }
}
