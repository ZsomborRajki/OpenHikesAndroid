package app.openhikes.core.testing.android

import app.openhikes.core.location.LocationFeed
import app.openhikes.core.location.LocationPermissionStatus
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.LocationFix
import app.openhikes.core.model.RecordingEnergyProfile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Deterministic [LocationFeed] fake for unit, journey, and UI tests.
 */
@Singleton
class FakeLocationFeed @Inject constructor() : LocationFeed {
    private val permissionFlow = MutableStateFlow(LocationPermissionStatus.GRANTED_FINE)
    private val fixesFlow = MutableSharedFlow<LocationFix>(extraBufferCapacity = FIX_BUFFER_CAPACITY)
    private val activeProfileFlow = MutableStateFlow<RecordingEnergyProfile?>(null)

    override val permissionStatus: StateFlow<LocationPermissionStatus> = permissionFlow.asStateFlow()
    override val fixes: Flow<LocationFix> = fixesFlow.asSharedFlow()
    override val activeProfile: StateFlow<RecordingEnergyProfile?> = activeProfileFlow.asStateFlow()

    var startCount: Int = 0
        private set

    var updateProfileCount: Int = 0
        private set

    var stopCount: Int = 0
        private set

    var lastKnownCoordinateOverride: GeoCoordinate? = null

    override fun lastKnownCoordinate(): GeoCoordinate? = lastKnownCoordinateOverride

    fun setPermission(status: LocationPermissionStatus) {
        permissionFlow.value = status
    }

    suspend fun emitFix(fix: LocationFix) {
        fixesFlow.emit(fix)
    }

    fun tryEmitFix(fix: LocationFix): Boolean = fixesFlow.tryEmit(fix)

    override fun refreshPermissionStatus() = Unit

    override fun startUpdates(profile: RecordingEnergyProfile) {
        startCount += 1
        activeProfileFlow.value = profile
    }

    override fun updateProfile(profile: RecordingEnergyProfile) {
        updateProfileCount += 1
        activeProfileFlow.value = profile
    }

    override fun stopUpdates() {
        stopCount += 1
        activeProfileFlow.value = null
    }

    companion object {
        private const val FIX_BUFFER_CAPACITY = 64
    }
}
