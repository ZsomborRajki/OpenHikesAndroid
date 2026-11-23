package app.openhikes.core.map

import androidx.compose.runtime.Immutable
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.OfflineCountry

/**
 * Country/region polygon outline drawn over the map while the Offline Maps screen is active.
 */
@Immutable
data class MapCountryOutline(
    val countryId: String,
    val polygons: List<List<GeoCoordinate>>,
    val isInstalled: Boolean,
    val isSelected: Boolean
)

/**
 * Converts an [OfflineCountry]'s raw `[longitude, latitude]` polygon rings into a renderable [MapCountryOutline].
 */
fun OfflineCountry.toMapCountryOutline(isInstalled: Boolean, isSelected: Boolean): MapCountryOutline {
    val rings = outline.mapNotNull { rawRing ->
        val coords = rawRing.mapNotNull { pair ->
            if (pair.size >= 2) {
                runCatching {
                    GeoCoordinate(
                        latitude = pair[1].coerceIn(-MAX_LATITUDE, MAX_LATITUDE),
                        longitude = pair[0].coerceIn(-MAX_LONGITUDE, MAX_LONGITUDE)
                    )
                }.getOrNull()
            } else {
                null
            }
        }
        coords.takeIf { it.size >= MIN_RING_POINTS }
    }
    return MapCountryOutline(
        countryId = id,
        polygons = rings,
        isInstalled = isInstalled,
        isSelected = isSelected
    )
}

private const val MAX_LATITUDE = 90.0
private const val MAX_LONGITUDE = 180.0
private const val MIN_RING_POINTS = 3
