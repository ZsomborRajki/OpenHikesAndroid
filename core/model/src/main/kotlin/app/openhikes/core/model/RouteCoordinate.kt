package app.openhikes.core.model

import kotlinx.serialization.Serializable

/**
 * Marks why a track point does not continue the preceding recorded segment.
 */
@Serializable
enum class RouteBoundary {
    /**
     * Recording was paused or a GPX `<trkseg>` break precedes this point.
     * The leg arriving at this point contributes zero distance and is excluded from trail matching.
     */
    PAUSED
}

/**
 * Origin of a route coordinate's geometry when not directly observed by GPS.
 */
@Serializable
enum class RouteProvenance {
    INFERRED
}

/**
 * Activity classification when a fix was not recorded on foot.
 */
@Serializable
enum class RouteMotion {
    NON_PEDESTRIAN
}

/**
 * Single ordered track point along a hike's route.
 */
@Serializable
data class RouteCoordinate(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double? = null,
    val timestampEpochMillis: Long? = null,
    val motion: RouteMotion? = null,
    val provenance: RouteProvenance? = null,
    val boundary: RouteBoundary? = null
) {
    val coordinate: GeoCoordinate
        get() = GeoCoordinate(latitude = latitude, longitude = longitude)

    val isPauseBoundary: Boolean
        get() = boundary == RouteBoundary.PAUSED

    val isInferred: Boolean
        get() = provenance == RouteProvenance.INFERRED
}
