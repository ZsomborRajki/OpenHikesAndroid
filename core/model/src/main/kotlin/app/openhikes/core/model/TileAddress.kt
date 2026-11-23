package app.openhikes.core.model

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * Slippy-map Web Mercator tile address `(z, x, y)` used by PMTiles archives and tile clients.
 */
data class TileAddress(val z: Int, val x: Int, val y: Int) {
    val isValid: Boolean
        get() {
            if (z !in 0..MAX_ZOOM) return false
            val limit = 1L shl z
            return x.toLong() in 0L until limit && y.toLong() in 0L until limit
        }

    /**
     * Returns the ancestor tile at `min(z, maxZoom)` for overzooming deeper map viewports.
     */
    fun overzoomedTo(maxZoom: Int): TileAddress {
        val clampedMax = maxZoom.coerceIn(0, MAX_ZOOM)
        val shift = (z - clampedMax).coerceAtLeast(0)
        return if (shift == 0) {
            this
        } else {
            TileAddress(z = z - shift, x = x ushr shift, y = y ushr shift)
        }
    }

    /**
     * Geographic bounding box covered by this slippy tile in WGS-84 degrees.
     */
    fun toGeoBoundingBox(): GeoBoundingBox {
        val n = (1L shl z).toDouble()
        val west = (x / n) * DEGREES_CIRCLE - HALF_CIRCLE_DEGREES
        val east = ((x + 1) / n) * DEGREES_CIRCLE - HALF_CIRCLE_DEGREES
        val north = tileYToLatitude(y / n)
        val south = tileYToLatitude((y + 1) / n)
        return GeoBoundingBox(
            southLat = south.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE),
            westLon = west.coerceIn(-HALF_CIRCLE_DEGREES, HALF_CIRCLE_DEGREES),
            northLat = north.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE),
            eastLon = east.coerceIn(-HALF_CIRCLE_DEGREES, HALF_CIRCLE_DEGREES)
        )
    }

    fun centerCoordinate(): GeoCoordinate {
        val box = toGeoBoundingBox()
        return GeoCoordinate(
            latitude = (box.southLat + box.northLat) / MIDPOINT_DIVISOR,
            longitude = (box.westLon + box.eastLon) / MIDPOINT_DIVISOR
        )
    }

    companion object {
        const val MAX_ZOOM = 30
        private const val DEGREES_CIRCLE = 360.0
        private const val HALF_CIRCLE_DEGREES = 180.0
        private const val RADIANS_TO_DEGREES = 180.0 / PI
        private const val MAX_MERCATOR_LATITUDE = 85.05112878
        private const val MIDPOINT_DIVISOR = 2.0

        private fun tileYToLatitude(fractionY: Double): Double {
            val radians = atan(sinh(PI * (1.0 - MIDPOINT_DIVISOR * fractionY)))
            return radians * RADIANS_TO_DEGREES
        }
    }
}
