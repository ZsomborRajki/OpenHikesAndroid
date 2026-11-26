package app.openhikes.core.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import app.openhikes.core.common.HikeTitle
import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.model.GeoBoundingBox
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.RouteGeometry
import app.openhikes.core.model.TrailStopSearchResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Seam for Trail Maker stop search and reverse geocoding of tapped stops & dropped pins.
 */
interface StopPlaceSearchSource {
    suspend fun search(query: String, biasBounds: GeoBoundingBox? = null): Result<List<TrailStopSearchResult>>

    suspend fun reverseName(coordinate: GeoCoordinate): String?

    suspend fun reverseAddress(coordinate: GeoCoordinate): String?
}

@Singleton
internal class AndroidStopPlaceSearchSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val launchConfigHolder: LaunchConfigHolder,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : StopPlaceSearchSource {
    override suspend fun search(query: String, biasBounds: GeoBoundingBox?): Result<List<TrailStopSearchResult>> {
        val trimmed = query.trim()
        val canQuery = trimmed.isNotEmpty() && !launchConfigHolder.current.value.isUiTesting && Geocoder.isPresent()
        return if (!canQuery) {
            Result.success(emptyList())
        } else {
            withContext(ioDispatcher) {
                runCatching {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    val addresses = queryForward(geocoder, trimmed, biasBounds)
                    addresses.mapIndexedNotNull { index, address -> address.toSearchResultOrNull(index) }
                }
            }
        }
    }

    override suspend fun reverseName(coordinate: GeoCoordinate): String? {
        if (launchConfigHolder.current.value.isUiTesting || !Geocoder.isPresent()) return null
        return withContext(ioDispatcher) {
            val address = runCatching { queryReverse(Geocoder(context, Locale.getDefault()), coordinate) }
                .getOrNull()
                ?: return@withContext null
            formatStopName(address, coordinate)
        }
    }

    override suspend fun reverseAddress(coordinate: GeoCoordinate): String? {
        if (launchConfigHolder.current.value.isUiTesting || !Geocoder.isPresent()) return null
        return withContext(ioDispatcher) {
            val address = runCatching { queryReverse(Geocoder(context, Locale.getDefault()), coordinate) }
                .getOrNull()
                ?: return@withContext null
            formatFullAddress(address)
        }
    }

    private suspend fun queryForward(geocoder: Geocoder, query: String, biasBounds: GeoBoundingBox?): List<Address> =
        suspendCancellableCoroutine { cont ->
            val listener = object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    if (cont.isActive) cont.resume(addresses)
                }

                override fun onError(errorMessage: String?) {
                    if (cont.isActive) cont.resume(emptyList())
                }
            }
            if (biasBounds != null && !biasBounds.isSinglePoint) {
                geocoder.getFromLocationName(
                    query,
                    MAX_SEARCH_RESULTS,
                    biasBounds.southLat,
                    biasBounds.westLon,
                    biasBounds.northLat,
                    biasBounds.eastLon,
                    listener
                )
            } else {
                geocoder.getFromLocationName(query, MAX_SEARCH_RESULTS, listener)
            }
        }

    private suspend fun queryReverse(geocoder: Geocoder, coordinate: GeoCoordinate): Address? =
        suspendCancellableCoroutine { cont ->
            geocoder.getFromLocation(
                coordinate.latitude,
                coordinate.longitude,
                1,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (cont.isActive) cont.resume(addresses.firstOrNull())
                    }

                    override fun onError(errorMessage: String?) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        }

    private fun Address.toSearchResultOrNull(index: Int): TrailStopSearchResult? {
        val primaryName = if (hasLatitude() && hasLongitude()) {
            HikeTitle.bounded(featureName)
                ?: HikeTitle.bounded(thoroughfare)
                ?: HikeTitle.bounded(locality)
                ?: HikeTitle.bounded(getAddressLine(0))
        } else {
            null
        }
        return primaryName?.let { name ->
            val coord = GeoCoordinate(latitude = latitude, longitude = longitude)
            val fullLine = formatFullAddress(this).orEmpty()
            TrailStopSearchResult(
                id = "geo-$index-${coord.latitude}-${coord.longitude}",
                name = name,
                subtitle = if (fullLine == name) "" else fullLine,
                coordinate = coord
            )
        }
    }

    private fun formatStopName(address: Address, requested: GeoCoordinate): String? {
        if (address.hasLatitude() && address.hasLongitude()) {
            val resolved = GeoCoordinate(address.latitude, address.longitude)
            if (RouteGeometry.distanceMeters(requested, resolved) > FARTHEST_STOP_NAME_METERS) return null
        }
        val street = address.thoroughfare?.takeIf { it.isNotBlank() }
        val feature = address.featureName?.takeIf { it.isNotBlank() && it != address.postalCode }
        val locality = address.locality?.takeIf { it.isNotBlank() }
        val raw = when {
            feature != null && street != null && feature != street ->
                joinWithLocality("$feature $street", locality)
            street != null -> joinWithLocality(street, locality)
            feature != null && feature != locality && feature != address.adminArea ->
                joinWithLocality(feature, locality)
            else -> null
        }
        return HikeTitle.bounded(raw)
    }

    private fun joinWithLocality(place: String, locality: String?): String =
        if (!locality.isNullOrBlank() && locality != place) "$place, $locality" else place

    private fun formatFullAddress(address: Address): String? = HikeTitle.bounded(address.getAddressLine(0))
        ?: HikeTitle.bounded(
            listOfNotNull(
                address.thoroughfare?.takeIf { it.isNotBlank() },
                address.locality?.takeIf { it.isNotBlank() },
                address.countryName?.takeIf { it.isNotBlank() }
            ).joinToString(", ")
        )

    private companion object {
        const val MAX_SEARCH_RESULTS = 8
        const val FARTHEST_STOP_NAME_METERS = 1_000.0
    }
}
