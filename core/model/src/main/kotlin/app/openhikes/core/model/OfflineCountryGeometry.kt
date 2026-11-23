package app.openhikes.core.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Geometric helpers for offline country outlines: distance to nearest polygon border and
 * padded map camera framing bounds.
 */
object OfflineCountryGeometry {
    private const val KM_PER_DEGREE_LAT = 111.32
    private const val FULL_CIRCLE_DEGREES = 360.0
    private const val HALF_CIRCLE_DEGREES = 180.0
    private const val RADIANS_PER_DEGREE = PI / 180.0
    private const val FRAME_PADDING_FACTOR = 1.2
    private const val HALF_FACTOR = 0.5
    private const val MAX_LATITUDE = 85.05112878
    private const val MAX_LONGITUDE = 180.0

    /**
     * Approximate shortest distance in kilometers from [from] to [country]'s polygon outline
     * (`0.0` when [from] lies inside the outline).
     */
    fun distanceKm(country: OfflineCountry, from: GeoCoordinate): Double {
        if (country.contains(longitude = from.longitude, latitude = from.latitude)) return 0.0
        val cosLat = cos(from.latitude * RADIANS_PER_DEGREE).coerceAtLeast(0.0)
        var bestKm = Double.POSITIVE_INFINITY
        for (ring in country.outline) {
            if (ring.isEmpty()) continue
            var previous = ring.last()
            for (vertex in ring) {
                if (vertex.size >= 2 && previous.size >= 2) {
                    val dist = pointToSegmentDistanceKm(
                        point = LonLat(lon = from.longitude, lat = from.latitude),
                        start = LonLat(lon = previous[0], lat = previous[1]),
                        end = LonLat(lon = vertex[0], lat = vertex[1]),
                        cosLat = cosLat
                    )
                    if (dist < bestKm) bestKm = dist
                }
                previous = vertex
            }
        }
        return if (bestKm.isFinite()) bestKm else fallbackBoundsDistanceKm(country.bounds, from, cosLat)
    }

    /**
     * Padded bounding box suitable for framing [country] on the map camera (`1.2x` span padding).
     */
    fun framingBoundingBox(country: OfflineCountry): GeoBoundingBox {
        val raw = outlineBoundingBox(country) ?: country.bounds.toGeoBoundingBox()
        val latSpan = (raw.northLat - raw.southLat).coerceAtLeast(0.05)
        val lonSpan = (raw.eastLon - raw.westLon).coerceAtLeast(0.05)
        val extraLat = latSpan * (FRAME_PADDING_FACTOR - 1.0) * HALF_FACTOR
        val extraLon = lonSpan * (FRAME_PADDING_FACTOR - 1.0) * HALF_FACTOR
        return GeoBoundingBox(
            southLat = (raw.southLat - extraLat).coerceIn(-MAX_LATITUDE, MAX_LATITUDE),
            westLon = (raw.westLon - extraLon).coerceIn(-MAX_LONGITUDE, MAX_LONGITUDE),
            northLat = (raw.northLat + extraLat).coerceIn(-MAX_LATITUDE, MAX_LATITUDE),
            eastLon = (raw.eastLon + extraLon).coerceIn(-MAX_LONGITUDE, MAX_LONGITUDE)
        )
    }

    private fun outlineBoundingBox(country: OfflineCountry): GeoBoundingBox? {
        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minLon = Double.POSITIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY
        var found = false
        for (ring in country.outline) {
            for (vertex in ring) {
                if (vertex.size < 2) continue
                val lon = vertex[0]
                val lat = vertex[1]
                if (lat < minLat) minLat = lat
                if (lat > maxLat) maxLat = lat
                if (lon < minLon) minLon = lon
                if (lon > maxLon) maxLon = lon
                found = true
            }
        }
        if (!found) return null
        return GeoBoundingBox(
            southLat = minLat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE),
            westLon = minLon.coerceIn(-MAX_LONGITUDE, MAX_LONGITUDE),
            northLat = maxLat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE),
            eastLon = maxLon.coerceIn(-MAX_LONGITUDE, MAX_LONGITUDE)
        )
    }

    private fun fallbackBoundsDistanceKm(bounds: OfflineBounds, from: GeoCoordinate, cosLat: Double): Double {
        val clampedLat = from.latitude.coerceIn(bounds.south, bounds.north)
        val clampedLon = from.longitude.coerceIn(bounds.west, bounds.east)
        val dy = (from.latitude - clampedLat) * KM_PER_DEGREE_LAT
        val dx = wrapDeltaLongitude(from.longitude - clampedLon) * cosLat * KM_PER_DEGREE_LAT
        return hypot(dx, dy)
    }

    private data class LonLat(val lon: Double, val lat: Double)

    private fun pointToSegmentDistanceKm(point: LonLat, start: LonLat, end: LonLat, cosLat: Double): Double {
        val axRel = wrapDeltaLongitude(start.lon - point.lon) * cosLat * KM_PER_DEGREE_LAT
        val ayRel = (start.lat - point.lat) * KM_PER_DEGREE_LAT
        val bxRel = wrapDeltaLongitude(end.lon - point.lon) * cosLat * KM_PER_DEGREE_LAT
        val byRel = (end.lat - point.lat) * KM_PER_DEGREE_LAT
        val dx = bxRel - axRel
        val dy = byRel - ayRel
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq > 0.0) (-(axRel * dx + ayRel * dy) / lenSq).coerceIn(0.0, 1.0) else 0.0
        return hypot(axRel + t * dx, ayRel + t * dy)
    }

    private fun wrapDeltaLongitude(delta: Double): Double {
        var wrapped = ((delta + HALF_CIRCLE_DEGREES) % FULL_CIRCLE_DEGREES)
        if (wrapped < 0.0) wrapped += FULL_CIRCLE_DEGREES
        return wrapped - HALF_CIRCLE_DEGREES
    }
}
