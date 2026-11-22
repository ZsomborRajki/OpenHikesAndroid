package app.openhikes.shell

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapCameraState
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.MapOverlayState
import app.openhikes.core.model.MapAttribution
import app.openhikes.core.model.MapStyleId
import app.openhikes.core.model.UserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Coarse UI state for map overlays and attribution chrome, deliberately excluding high-frequency
 * camera/scroll ticks so root shell composables do not recompose during pan/zoom gestures.
 */
@Immutable
data class MapChromeUiState(
    val overlayState: MapOverlayState = MapOverlayState(),
    val showAttributionDialog: Boolean = false
)

/**
 * Root ViewModel coordinating map chrome, search query, and route-owned map claims.
 */
@HiltViewModel
class MapShellViewModel @Inject constructor(
    private val userSettingsRepository: UserSettingsRepository,
    private val mapClaimCoordinator: MapClaimCoordinator,
    launchConfigHolder: LaunchConfigHolder
) : ViewModel() {
    private val commandIdGenerator = AtomicLong(0L)
    private val showAttributionDialog = MutableStateFlow(false)
    private val searchQueryState = MutableStateFlow("")
    private val pendingCameraCommandState = MutableStateFlow<MapCameraCommand?>(null)
    private val cameraStateFlow = MutableStateFlow(
        MapCameraState(center = launchConfigHolder.current.value.mapStart ?: MapCameraState.DEFAULT_CENTER)
    )

    val initialCameraState: MapCameraState = cameraStateFlow.value
    val latestCameraState: StateFlow<MapCameraState> = cameraStateFlow.asStateFlow()
    val searchQuery: StateFlow<String> = searchQueryState.asStateFlow()
    val pendingCameraCommand: StateFlow<MapCameraCommand?> = pendingCameraCommandState.asStateFlow()

    val chromeUiState: StateFlow<MapChromeUiState> = combine(
        userSettingsRepository.userSettings.catch { exception ->
            if (exception is IOException) {
                emit(UserSettings())
            } else {
                throw exception
            }
        },
        mapClaimCoordinator.activeClaim,
        showAttributionDialog
    ) { settings, activeClaim, attributionDialogOpen ->
        val defaultOverlay = MapOverlayState(
            mapStyleId = settings.mapStyleId.ifBlank { MapStyleId.OPENHIKES },
            showsHikingRoutes = settings.showsHikingRoutes,
            attribution = MapAttribution.forOpenFreeMap(settings.showsHikingRoutes)
        )
        MapChromeUiState(
            overlayState = activeClaim?.overlayOverride ?: defaultOverlay,
            showAttributionDialog = attributionDialogOpen
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = MapChromeUiState()
    )

    fun syncBackStack(keysInOrder: List<Any>) {
        mapClaimCoordinator.syncBackStack(keysInOrder)
    }

    fun onToggleHikingRoutes() {
        val nextValue = !chromeUiState.value.overlayState.showsHikingRoutes
        viewModelScope.launch {
            try {
                userSettingsRepository.setShowsHikingRoutes(nextValue)
            } catch (_: IOException) {
                // Keep existing state intact cleanly when storage writes fail.
            }
        }
    }

    fun onZoomIn() {
        pendingCameraCommandState.value = MapCameraCommand.ZoomIn(
            id = commandIdGenerator.incrementAndGet()
        )
    }

    fun onZoomOut() {
        pendingCameraCommandState.value = MapCameraCommand.ZoomOut(
            id = commandIdGenerator.incrementAndGet()
        )
    }

    fun onResetNorth() {
        pendingCameraCommandState.value = MapCameraCommand.ResetNorth(
            id = commandIdGenerator.incrementAndGet()
        )
    }

    fun onCameraCommandHandled(commandId: Long) {
        if (pendingCameraCommandState.value?.id == commandId) {
            pendingCameraCommandState.value = null
        }
    }

    fun onCameraChanged(cameraState: MapCameraState) {
        cameraStateFlow.value = cameraState
    }

    fun onSearchQueryChange(query: String) {
        searchQueryState.value = query
    }

    fun onClearSearch() {
        searchQueryState.value = ""
    }

    fun onOpenAttributionDialog() {
        showAttributionDialog.value = true
    }

    fun onDismissAttributionDialog() {
        showAttributionDialog.value = false
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
