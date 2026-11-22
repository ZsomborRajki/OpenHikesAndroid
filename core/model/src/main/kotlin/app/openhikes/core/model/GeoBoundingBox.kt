package app.openhikes.core.model

import kotlinx.serialization.Serializable

/**
 * Axis-aligned geographic bounding box in WGS-84 coordinates.
 */
@Serializable
data class GeoBoundingBox(val southLat: Double, val westLon: Double, val northLat: Double, val eastLon: Double) {
    init {
        require(southLat <= northLat) {
            "southLat ($southLat) must be <= northLat ($northLat)"
        }
    }

    val center: GeoCoordinate
        get() = GeoCoordinate(
            latitude = (southLat + northLat) / MIDPOINT_DIVISOR,
            longitude = (westLon + eastLon) / MIDPOINT_DIVISOR
        )

    val isSinglePoint: Boolean
        get() = southLat == northLat && westLon == eastLon

    companion object {
        private const val MIDPOINT_DIVISOR = 2.0

        fun from(coordinates: List<GeoCoordinate>): GeoBoundingBox? {
            if (coordinates.isEmpty()) return null
            var minLat = Double.POSITIVE_INFINITY
            var maxLat = Double.NEGATIVE_INFINITY
            var minLon = Double.POSITIVE_INFINITY
            var maxLon = Double.NEGATIVE_INFINITY
            for (coord in coordinates) {
                if (coord.latitude < minLat) minLat = coord.latitude
                if (coord.latitude > maxLat) maxLat = coord.latitude
                if (coord.longitude < minLon) minLon = coord.longitude
                if (coord.longitude > maxLon) maxLon = coord.longitude
            }
            return GeoBoundingBox(
                southLat = minLat,
                westLon = minLon,
                northLat = maxLat,
                eastLon = maxLon
            )
        }
    }
}
