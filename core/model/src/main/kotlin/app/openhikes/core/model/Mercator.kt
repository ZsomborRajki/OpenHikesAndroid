package app.openhikes.core.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * Web Mercator bounds checking and unit-square projection shared across GPX and tile preview math.
 */
object Mercator {
    const val LATITUDE_LIMIT = 85.0511287798066
    private const val MIN_LONGITUDE = -180.0
    private const val MAX_LONGITUDE = 180.0
    private const val FULL_CIRCLE_DEGREES = 360.0
    private const val HALF_CIRCLE_DEGREES = 180.0
    private const val DEGREES_TO_RADIANS = PI / HALF_CIRCLE_DEGREES
    private const val HALF = 0.5

    fun isRepresentable(latitude: Double, longitude: Double): Boolean = latitude.isFinite() &&
        longitude.isFinite() &&
        latitude in -LATITUDE_LIMIT..LATITUDE_LIMIT &&
        longitude in MIN_LONGITUDE..MAX_LONGITUDE

    /**
     * Projects [longitude] in degrees onto the Web Mercator unit X interval `[0.0, 1.0]`.
     */
    fun unitX(longitude: Double): Double = (longitude + HALF_CIRCLE_DEGREES) / FULL_CIRCLE_DEGREES

    /**
     * Wraps a unit X coordinate into `[0.0, 1.0)` across the antimeridian.
     */
    fun wrappedUnitX(unitX: Double): Double {
        val remainder = unitX % 1.0
        return if (remainder < 0.0) remainder + 1.0 else remainder
    }

    /**
     * Projects [latitude] in degrees (clamped to Web Mercator bounds) onto unit Y `[0.0, 1.0]`.
     */
    fun unitY(latitude: Double): Double {
        val clamped = latitude.coerceIn(-LATITUDE_LIMIT, LATITUDE_LIMIT)
        val radians = clamped * DEGREES_TO_RADIANS
        val y = (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) * HALF
        return y.coerceIn(0.0, 1.0)
    }
}
