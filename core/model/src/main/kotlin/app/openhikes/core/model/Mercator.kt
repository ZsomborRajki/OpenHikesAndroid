package app.openhikes.core.model

/**
 * Web Mercator bounds checking shared at GPX/data boundaries.
 */
object Mercator {
    const val LATITUDE_LIMIT = 85.0511287798066
    private const val MIN_LONGITUDE = -180.0
    private const val MAX_LONGITUDE = 180.0

    fun isRepresentable(latitude: Double, longitude: Double): Boolean = latitude.isFinite() &&
        longitude.isFinite() &&
        latitude in -LATITUDE_LIMIT..LATITUDE_LIMIT &&
        longitude in MIN_LONGITUDE..MAX_LONGITUDE
}
