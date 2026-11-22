package app.openhikes.feature.hikes

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.map.MapClaim
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.RouteStyleUpdate
import app.openhikes.core.model.RouteTint
import app.openhikes.core.ui.navigation.RouteStyleRoute
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Immutable
data class RouteStyleUiState(
    val hikeId: HikeId,
    val title: String = "",
    val tintHex: String = RouteTint.DEFAULT_HEX,
    val borderHex: String = RouteBorder.NONE_HEX,
    val widthDp: Double = HikeSummary.DEFAULT_ROUTE_WIDTH_DP,
    val linePattern: RouteLinePattern = RouteLinePattern.Default,
    val isLoaded: Boolean = false,
    val hasSaveError: Boolean = false
)

/**
 * Coordinates live route tint, border, line width, and pattern editing for [RouteStyleRoute].
 */
@HiltViewModel(assistedFactory = RouteStyleViewModel.Factory::class)
class RouteStyleViewModel @AssistedInject constructor(
    @Assisted rawHikeId: String,
    private val hikeRepository: HikeRepository,
    private val mapClaimCoordinator: MapClaimCoordinator
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(rawHikeId: String): RouteStyleViewModel
    }

    val hikeId: HikeId = HikeId(rawHikeId)
    private val routeKey = RouteStyleRoute(hikeId)
    private val saveErrorState = MutableStateFlow(false)
    private val pendingStyleState = MutableStateFlow<RouteStyleUpdate?>(null)
    private val styleMutex = Mutex()

    val uiState: StateFlow<RouteStyleUiState> = combine(
        hikeRepository.observeHike(hikeId),
        pendingStyleState,
        saveErrorState
    ) { hike, pendingStyle, hasError ->
        if (hike == null) {
            RouteStyleUiState(hikeId = hikeId, isLoaded = true, hasSaveError = hasError)
        } else {
            val activeTint = pendingStyle?.tintHex ?: hike.tintHex
            val activeBorder = pendingStyle?.routeBorderHex ?: hike.routeBorderHex
            val activeWidth = pendingStyle?.clampedWidthDp ?: hike.routeWidthDp
            val activePattern = pendingStyle?.linePattern ?: hike.linePattern
            RouteStyleUiState(
                hikeId = hikeId,
                title = hike.displayTitle,
                tintHex = activeTint,
                borderHex = activeBorder,
                widthDp = activeWidth,
                linePattern = activePattern,
                isLoaded = true,
                hasSaveError = hasError
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = RouteStyleUiState(hikeId = hikeId)
    )

    init {
        mapClaimCoordinator.registerClaim(
            MapClaim(
                routeKey = routeKey,
                depth = INITIAL_CLAIM_DEPTH,
                selectedHikeId = hikeId
            )
        )
    }

    override fun onCleared() {
        mapClaimCoordinator.releaseClaim(routeKey)
    }

    fun onTintSelected(tintHex: String) {
        persistStyle { current -> current.copy(tintHex = tintHex) }
    }

    fun onBorderSelected(borderHex: String) {
        persistStyle { current -> current.copy(routeBorderHex = borderHex) }
    }

    fun onWidthChanged(widthDp: Double) {
        persistStyle { current -> current.copy(routeWidthDp = widthDp) }
    }

    fun onPatternSelected(pattern: RouteLinePattern) {
        persistStyle { current -> current.copy(linePattern = pattern) }
    }

    fun onDismissError() {
        saveErrorState.value = false
    }

    private fun persistStyle(transform: (RouteStyleUpdate) -> RouteStyleUpdate) {
        val state = uiState.value
        val base = pendingStyleState.value ?: RouteStyleUpdate(
            tintHex = state.tintHex,
            routeBorderHex = state.borderHex,
            routeWidthDp = state.widthDp,
            linePattern = state.linePattern
        )
        val updated = transform(base)
        pendingStyleState.value = updated
        viewModelScope.launch {
            styleMutex.withLock {
                val target = pendingStyleState.value ?: return@withLock
                val result = hikeRepository.updateRouteStyle(hikeId, target)
                saveErrorState.value = result.isFailure
                if (result.isFailure || pendingStyleState.value == target) {
                    pendingStyleState.value = null
                }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val INITIAL_CLAIM_DEPTH = 2
    }
}
