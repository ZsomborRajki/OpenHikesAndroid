package app.openhikes.core.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared stationary and on-foot speed thresholds aligning live recording and saved hike statistics.
 */
object MovementThresholds {
    const val STATIONARY_INTERVAL_SECONDS = 30.0
    const val STATIONARY_NET_DISPLACEMENT_METERS = 15.0
    const val MAXIMUM_ON_FOOT_SPEED_MPS = 8.0
}

/**
 * Tangent-plane projection of a coordinate onto a single line segment.
 */
data class SegmentProjection(val fraction: Double, val offRouteMeters: Double, val dx: Double, val dy: Double)

/**
 * Geodesic and local tangent-plane geometry utilities shared across GPX import, route profiles,
 * statistics, and matching.
 */
object RouteGeometry {
    private const val EARTH_RADIUS_METERS = 6_371_008.8
    private const val DEGREES_IN_HALF_TURN = 180.0
    private const val DEGREES_IN_FULL_TURN = 360.0
    private const val HALF_FACTOR = 2.0
    const val METERS_PER_DEGREE_LATITUDE = EARTH_RADIUS_METERS * PI / DEGREES_IN_HALF_TURN

    fun distanceMeters(from: GeoCoordinate, to: GeoCoordinate): Double {
        val startLat = from.latitude * PI / DEGREES_IN_HALF_TURN
        val endLat = to.latitude * PI / DEGREES_IN_HALF_TURN
        val deltaLat = (to.latitude - from.latitude) * PI / DEGREES_IN_HALF_TURN
        val deltaLon = normalizedLongitudeDelta(to.longitude - from.longitude) * PI / DEGREES_IN_HALF_TURN
        val sinLat = sin(deltaLat / HALF_FACTOR)
        val sinLon = sin(deltaLon / HALF_FACTOR)
        val haversine = (sinLat * sinLat + cos(startLat) * cos(endLat) * sinLon * sinLon)
            .coerceIn(0.0, 1.0)
        return HALF_FACTOR * EARTH_RADIUS_METERS * atan2(sqrt(haversine), sqrt(1.0 - haversine))
    }

    fun lengthMeters(coordinates: List<GeoCoordinate>): Double {
        if (coordinates.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until coordinates.size) {
            total += distanceMeters(coordinates[i - 1], coordinates[i])
        }
        return total
    }

    fun legMeters(previous: RouteCoordinate, current: RouteCoordinate): Double = if (current.isPauseBoundary) {
        0.0
    } else {
        distanceMeters(previous.coordinate, current.coordinate)
    }

    fun routeLengthMeters(route: List<RouteCoordinate>): Double {
        if (route.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until route.size) {
            total += legMeters(route[i - 1], route[i])
        }
        return total
    }

    fun localOffset(from: GeoCoordinate, to: GeoCoordinate): Pair<Double, Double> {
        val originLatRad = from.latitude * PI / DEGREES_IN_HALF_TURN
        val deltaLon = normalizedLongitudeDelta(to.longitude - from.longitude)
        val x = deltaLon * PI / DEGREES_IN_HALF_TURN * EARTH_RADIUS_METERS * cos(originLatRad)
        val y = (to.latitude - from.latitude) * PI / DEGREES_IN_HALF_TURN * EARTH_RADIUS_METERS
        return x to y
    }

    fun project(coordinate: GeoCoordinate, start: GeoCoordinate, end: GeoCoordinate): SegmentProjection {
        val (startX, startY) = localOffset(coordinate, start)
        val (endX, endY) = localOffset(coordinate, end)
        var dx = endX - startX
        val halfTurn = PI * EARTH_RADIUS_METERS * cos(coordinate.latitude * PI / DEGREES_IN_HALF_TURN)
        if (dx > halfTurn) {
            dx -= HALF_FACTOR * halfTurn
        } else if (dx < -halfTurn) {
            dx += HALF_FACTOR * halfTurn
        }
        val dy = endY - startY
        val lengthSq = dx * dx + dy * dy
        val fraction = if (lengthSq > 0.0) {
            (-(startX * dx + startY * dy) / lengthSq).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        return SegmentProjection(
            fraction = fraction,
            offRouteMeters = hypot(startX + fraction * dx, startY + fraction * dy),
            dx = dx,
            dy = dy
        )
    }

    fun interpolate(start: GeoCoordinate, end: GeoCoordinate, fraction: Double): GeoCoordinate {
        val bounded = min(max(fraction, 0.0), 1.0)
        val lon = start.longitude + normalizedLongitudeDelta(end.longitude - start.longitude) * bounded
        return GeoCoordinate(
            latitude = start.latitude + (end.latitude - start.latitude) * bounded,
            longitude = normalizedLongitude(lon)
        )
    }

    fun normalizedLongitudeDelta(delta: Double): Double {
        var normalized = delta % DEGREES_IN_FULL_TURN
        if (normalized > DEGREES_IN_HALF_TURN) normalized -= DEGREES_IN_FULL_TURN
        if (normalized < -DEGREES_IN_HALF_TURN) normalized += DEGREES_IN_FULL_TURN
        return normalized
    }

    fun normalizedLongitude(longitude: Double): Double {
        var normalized = longitude % DEGREES_IN_FULL_TURN
        if (normalized > DEGREES_IN_HALF_TURN) normalized -= DEGREES_IN_FULL_TURN
        if (normalized < -DEGREES_IN_HALF_TURN) normalized += DEGREES_IN_FULL_TURN
        return normalized
    }

    fun bearingDifference(first: Double, second: Double): Double {
        val delta = abs(first - second) % DEGREES_IN_FULL_TURN
        return if (delta > DEGREES_IN_HALF_TURN) DEGREES_IN_FULL_TURN - delta else delta
    }
}
