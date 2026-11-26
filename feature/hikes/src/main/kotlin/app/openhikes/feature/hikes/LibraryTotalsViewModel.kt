package app.openhikes.feature.hikes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.UserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Computes yearly, prior-year, monthly, and all-time hiking totals off-main when opened and
 * refreshes automatically whenever saved hikes or walks change (for example, after opening a
 * record and editing, renaming, or deleting it), while surfacing explicit retry on read failures.
 */
@HiltViewModel
class LibraryTotalsViewModel internal constructor(
    private val hikeRepository: HikeRepository,
    userSettingsRepository: UserSettingsRepository,
    private val clock: OpenHikesClock,
    private val zoneIdProvider: () -> ZoneId
) : ViewModel() {
    @Inject
    constructor(
        hikeRepository: HikeRepository,
        userSettingsRepository: UserSettingsRepository,
        clock: OpenHikesClock
    ) : this(hikeRepository, userSettingsRepository, clock, ZoneId::systemDefault)

    private data class HikeLibrarySignature(
        val id: HikeId,
        val displayTitle: String,
        val distanceMeters: Double,
        val walkCount: Int,
        val climbMeters: Double?
    )

    private val loadState = MutableStateFlow<LibraryTotalsLoadState>(LibraryTotalsLoadState.Loading)
    private val retryTrigger = MutableStateFlow(0)

    private val settingsFlow = userSettingsRepository.userSettings.catch { exception ->
        if (exception is IOException) {
            emit(UserSettings())
        } else {
            throw exception
        }
    }

    private val librarySignatureFlow = hikeRepository.hikes
        .map { hikes ->
            hikes.map { hike ->
                HikeLibrarySignature(
                    id = hike.id,
                    displayTitle = hike.displayTitle,
                    distanceMeters = hike.distanceMeters,
                    walkCount = hike.walkCount,
                    climbMeters = hike.climbMeters
                )
            }
        }
        .distinctUntilChanged()

    val uiState: StateFlow<LibraryTotalsUiState> = combine(
        loadState,
        settingsFlow
    ) { state, settings ->
        LibraryTotalsUiState(
            loadState = state,
            distanceUnit = settings.distanceUnit
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = LibraryTotalsUiState()
    )

    init {
        observeTotalsRefresh()
    }

    fun onRetry() {
        loadState.value = LibraryTotalsLoadState.Loading
        retryTrigger.value += 1
    }

    fun onOpenRecordHike(hikeId: HikeId, onOpenHike: (HikeId) -> Unit) {
        viewModelScope.launch {
            if (hikeRepository.observeHike(hikeId).first() != null) {
                onOpenHike(hikeId)
            }
        }
    }

    private fun observeTotalsRefresh() {
        viewModelScope.launch {
            combine(librarySignatureFlow, retryTrigger) { _, attempt -> attempt }
                .collectLatest {
                    val result = hikeRepository.computeLibraryTotals(
                        asOfEpochMillis = clock.nowEpochMillis(),
                        zoneId = zoneIdProvider()
                    )
                    loadState.value = result.fold(
                        onSuccess = { totals -> LibraryTotalsLoadState.Loaded(totals) },
                        onFailure = { LibraryTotalsLoadState.Error }
                    )
                }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
