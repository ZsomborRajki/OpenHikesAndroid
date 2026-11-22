package app.openhikes.core.model

import kotlinx.serialization.Serializable

/**
 * WGS-84 geographic coordinate pair used across OpenHikes routes and places.
 */
@Serializable
data class GeoCoordinate(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in MIN_LATITUDE..MAX_LATITUDE) {
            "Latitude must be finite and within [$MIN_LATITUDE, $MAX_LATITUDE], was $latitude"
        }
        require(longitude.isFinite() && longitude in MIN_LONGITUDE..MAX_LONGITUDE) {
            "Longitude must be finite and within [$MIN_LONGITUDE, $MAX_LONGITUDE], was $longitude"
        }
    }

    private companion object {
        const val MIN_LATITUDE = -90.0
        const val MAX_LATITUDE = 90.0
        const val MIN_LONGITUDE = -180.0
        const val MAX_LONGITUDE = 180.0
    }
}
