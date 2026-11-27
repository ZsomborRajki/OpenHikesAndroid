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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Computes yearly, prior-year, monthly, and all-time hiking totals off-main when opened and
 * refreshes automatically whenever saved hikes, route chunks, or walks change (for example,
 * trimming a leading pause across New Year, renaming, or deleting a hike), while surfacing
 * explicit retry on read failures.
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

    private val loadState = MutableStateFlow<LibraryTotalsLoadState>(LibraryTotalsLoadState.Loading)
    private val retryTrigger = MutableStateFlow(0)

    private val settingsFlow = userSettingsRepository.userSettings.catch { exception ->
        if (exception is IOException) {
            emit(UserSettings())
        } else {
            throw exception
        }
    }

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
            combine(hikeRepository.totalsInvalidation, retryTrigger) { revision, attempt ->
                revision to attempt
            }.collectLatest {
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
