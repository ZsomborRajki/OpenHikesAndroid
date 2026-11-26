package app.openhikes.core.location

import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.LocationFix
import app.openhikes.core.model.RecordingEnergyProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Current runtime location permission level visible to the recording engine.
 */
enum class LocationPermissionStatus {
    GRANTED_FINE,
    GRANTED_COARSE,
    DENIED
}

/**
 * Injectable location source contract separating Android LocationManager / FusedLocation delivery
 * from recording policy and deterministic test playback.
 */
interface LocationFeed {
    val permissionStatus: StateFlow<LocationPermissionStatus>
    val fixes: Flow<LocationFix>
    val activeProfile: StateFlow<RecordingEnergyProfile?>

    fun refreshPermissionStatus()

    fun startUpdates(profile: RecordingEnergyProfile)

    fun updateProfile(profile: RecordingEnergyProfile)

    fun stopUpdates()

    /**
     * One-shot best-effort last known hiker coordinate for sorting nearby offline countries
     * without starting continuous GPS updates.
     */
    fun lastKnownCoordinate(): GeoCoordinate? = null

    /**
     * Permission-aware one-shot lookup returning a cached or freshly requested hiker coordinate
     * without starting continuous recording updates, or `null` when permission is denied,
     * no provider is available, or no fix can be obtained.
     */
    suspend fun requestCurrentCoordinate(): GeoCoordinate? = lastKnownCoordinate()
}
