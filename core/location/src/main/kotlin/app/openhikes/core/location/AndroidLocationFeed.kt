package app.openhikes.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.LocationFix
import app.openhikes.core.model.RecordingEnergyProfile
import app.openhikes.core.model.RecordingEnergyReason
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Production [LocationFeed] backed by Android's [LocationManager] using GPS/Fused providers
 * with explicit permission gating and full sensor teardown when paused or stopped.
 */
@Singleton
internal class AndroidLocationFeed @Inject constructor(@ApplicationContext private val context: Context) :
    LocationFeed {
    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val permissionStatusState = MutableStateFlow(readPermissionStatus())
    private val fixesFlow = MutableSharedFlow<LocationFix>(extraBufferCapacity = FIX_BUFFER_CAPACITY)
    private val activeProfileState = MutableStateFlow<RecordingEnergyProfile?>(null)
    private var isListening = false

    @Volatile
    private var latestFixCoordinate: GeoCoordinate? = null

    private val listener = LocationListener { location ->
        location.toValidCoordinate()?.let { latestFixCoordinate = it }
        fixesFlow.tryEmit(location.toLocationFix())
    }

    override val permissionStatus: StateFlow<LocationPermissionStatus> = permissionStatusState.asStateFlow()
    override val fixes: Flow<LocationFix> = fixesFlow.asSharedFlow()
    override val activeProfile: StateFlow<RecordingEnergyProfile?> = activeProfileState.asStateFlow()

    override fun refreshPermissionStatus() {
        permissionStatusState.value = readPermissionStatus()
    }

    @SuppressLint("MissingPermission")
    override fun startUpdates(profile: RecordingEnergyProfile) {
        refreshPermissionStatus()
        val manager = locationManager
        val canRequest = permissionStatusState.value == LocationPermissionStatus.GRANTED_FINE && manager != null
        val provider = if (canRequest) resolveProvider(manager) else null
        if (provider != null && manager != null) {
            stopUpdates()
            activeProfileState.value = profile
            val minDistanceMeters = if (
                profile.reason == RecordingEnergyReason.AUTO_PAUSED ||
                profile.reason == RecordingEnergyReason.PAUSED_WATCH
            ) {
                profile.distanceFilterMeters.toFloat()
            } else {
                ACTIVE_MIN_DISTANCE_METERS
            }
            manager.requestLocationUpdates(
                provider,
                profile.updateIntervalMillis,
                minDistanceMeters,
                listener,
                Looper.getMainLooper()
            )
            isListening = true
        }
    }

    override fun updateProfile(profile: RecordingEnergyProfile) {
        if (activeProfileState.value == profile && isListening) return
        if (isListening) {
            startUpdates(profile)
        } else {
            activeProfileState.value = profile
        }
    }

    override fun stopUpdates() {
        if (isListening) {
            runCatching { locationManager?.removeUpdates(listener) }
            isListening = false
        }
        activeProfileState.value = null
    }

    @SuppressLint("MissingPermission")
    override fun lastKnownCoordinate(): GeoCoordinate? {
        refreshPermissionStatus()
        val status = permissionStatusState.value
        val manager = locationManager
        return when {
            status == LocationPermissionStatus.DENIED -> null
            manager == null -> latestFixCoordinate
            else -> lastKnownProvidersFor(status).firstNotNullOfOrNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }
                    .getOrNull()
                    ?.toValidCoordinate()
            } ?: latestFixCoordinate
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun requestCurrentCoordinate(): GeoCoordinate? {
        val cached = lastKnownCoordinate()
        val status = permissionStatusState.value
        val manager = locationManager
        val provider = if (cached == null && status != LocationPermissionStatus.DENIED && manager != null) {
            resolveOneShotProvider(manager, status)
        } else {
            null
        }
        return when {
            cached != null -> cached
            provider == null || manager == null -> null
            else -> fetchSingleFix(manager, provider)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun fetchSingleFix(manager: LocationManager, provider: String): GeoCoordinate? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            val started = runCatching {
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                    val coord = location?.toValidCoordinate()
                    if (coord != null) latestFixCoordinate = coord
                    if (continuation.isActive) continuation.resume(coord)
                }
            }
            if (started.isFailure && continuation.isActive) {
                continuation.resume(null)
            }
        }

    private fun lastKnownProvidersFor(status: LocationPermissionStatus): List<String> = when (status) {
        LocationPermissionStatus.GRANTED_FINE -> listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )
        LocationPermissionStatus.GRANTED_COARSE -> listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )
        LocationPermissionStatus.DENIED -> emptyList()
    }

    private fun resolveOneShotProvider(manager: LocationManager, status: LocationPermissionStatus): String? {
        val candidates = lastKnownProvidersFor(status)
        val available = manager.allProviders
        return candidates.firstOrNull {
            it in available &&
                runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }
            ?: candidates.firstOrNull { it in available }
    }

    private fun readPermissionStatus(): LocationPermissionStatus {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (hasFine) return LocationPermissionStatus.GRANTED_FINE

        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return if (hasCoarse) LocationPermissionStatus.GRANTED_COARSE else LocationPermissionStatus.DENIED
    }

    private fun resolveProvider(manager: LocationManager): String? = when {
        manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        manager.allProviders.contains(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
        manager.allProviders.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        else -> null
    }

    private fun Location.toValidCoordinate(): GeoCoordinate? = takeIf { latitude.isFinite() && longitude.isFinite() }
        ?.let { loc -> runCatching { GeoCoordinate(loc.latitude, loc.longitude) }.getOrNull() }

    private fun Location.toLocationFix(): LocationFix = LocationFix(
        latitude = latitude,
        longitude = longitude,
        timestampEpochMillis = time,
        horizontalAccuracyMeters = if (hasAccuracy()) accuracy.toDouble() else Double.NaN,
        elevationMeters = if (hasAltitude()) altitude else null,
        verticalAccuracyMeters = if (hasVerticalAccuracy()) verticalAccuracyMeters.toDouble() else null,
        courseDegrees = if (hasBearing()) bearing.toDouble() else null,
        courseAccuracyDegrees = if (hasBearingAccuracy()) bearingAccuracyDegrees.toDouble() else null,
        speedMps = if (hasSpeed()) speed.toDouble() else null
    )

    private companion object {
        const val FIX_BUFFER_CAPACITY = 64
        const val ACTIVE_MIN_DISTANCE_METERS = 0f
    }
}
