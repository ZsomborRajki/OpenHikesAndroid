package app.openhikes.feature.hikes

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.map.MapClaim
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.model.Hike
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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    private val styleEditState = MutableStateFlow(StyleEditState())
    private val observedStyle = AtomicReference<RouteStyleUpdate?>(null)
    private val styleMutex = Mutex()

    val uiState: StateFlow<RouteStyleUiState> = combine(
        hikeRepository.observeHike(hikeId).onEach { hike ->
            val observed = hike?.toStyleUpdate()
            observedStyle.set(observed)
            if (observed != null) {
                styleEditState.update { current ->
                    if (current.queuedStyle == null && current.lastSavedStyle == observed) {
                        current.copy(lastSavedStyle = null)
                    } else {
                        current
                    }
                }
            }
        },
        styleEditState,
        saveErrorState
    ) { hike, edits, hasError ->
        if (hike == null) {
            RouteStyleUiState(hikeId = hikeId, isLoaded = true, hasSaveError = hasError)
        } else {
            val active = edits.queuedStyle ?: edits.lastSavedStyle ?: hike.toStyleUpdate()
            RouteStyleUiState(
                hikeId = hikeId,
                title = hike.displayTitle,
                tintHex = active.tintHex,
                borderHex = active.routeBorderHex,
                widthDp = active.clampedWidthDp,
                linePattern = active.linePattern,
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
        styleEditState.update { current ->
            val base = current.queuedStyle
                ?: current.lastSavedStyle
                ?: observedStyle.get()
                ?: RouteStyleUpdate(
                    tintHex = state.tintHex,
                    routeBorderHex = state.borderHex,
                    routeWidthDp = state.widthDp,
                    linePattern = state.linePattern
                ).normalized()
            current.copy(
                queuedStyle = transform(base).normalized(),
                queuedRevision = current.queuedRevision + 1L
            )
        }
        viewModelScope.launch {
            flushQueuedEdits()
        }
    }

    private suspend fun flushQueuedEdits() {
        styleMutex.withLock {
            while (true) {
                val snapshot = styleEditState.value
                val target = snapshot.queuedStyle ?: return@withLock
                val targetRevision = snapshot.queuedRevision
                val result = hikeRepository.updateRouteStyle(hikeId, target)
                saveErrorState.value = result.isFailure
                val shouldContinue = if (result.isSuccess) {
                    applyWriteSuccess(target, targetRevision)
                } else {
                    applyWriteFailure(targetRevision)
                }
                if (!shouldContinue) return@withLock
            }
        }
    }

    private fun applyWriteSuccess(savedStyle: RouteStyleUpdate, targetRevision: Long): Boolean {
        var hasMoreWork = false
        styleEditState.update { current ->
            if (current.queuedRevision == targetRevision) {
                hasMoreWork = false
                current.copy(
                    queuedStyle = null,
                    lastSavedStyle = if (observedStyle.get() == savedStyle) null else savedStyle
                )
            } else {
                hasMoreWork = true
                current.copy(lastSavedStyle = savedStyle)
            }
        }
        return hasMoreWork
    }

    private fun applyWriteFailure(targetRevision: Long): Boolean {
        var hasMoreWork = false
        styleEditState.update { current ->
            if (current.queuedRevision == targetRevision) {
                hasMoreWork = false
                current.copy(queuedStyle = null)
            } else {
                hasMoreWork = true
                current
            }
        }
        return hasMoreWork
    }

    private data class StyleEditState(
        val queuedStyle: RouteStyleUpdate? = null,
        val lastSavedStyle: RouteStyleUpdate? = null,
        val queuedRevision: Long = 0L
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val INITIAL_CLAIM_DEPTH = 2
    }
}

private fun RouteStyleUpdate.normalized(): RouteStyleUpdate = copy(routeWidthDp = clampedWidthDp)

private fun Hike.toStyleUpdate(): RouteStyleUpdate = RouteStyleUpdate(
    tintHex = tintHex,
    routeBorderHex = routeBorderHex,
    routeWidthDp = routeWidthDp,
    linePattern = linePattern
).normalized()
