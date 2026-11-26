package app.openhikes.feature.places

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.NearbyPlaceSuggestions
import app.openhikes.core.model.PlaceSearchOutage
import app.openhikes.core.model.RouteGeometry
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.TrailPoint
import app.openhikes.core.model.TrailPointQuery
import app.openhikes.core.model.TrailPointSearchResult
import app.openhikes.core.model.TrailPointSource
import app.openhikes.core.model.UserSettings
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class RecordingPlaceSuggestionItem(val place: TrailPoint, val distanceMeters: Double)

@Immutable
data class RecordingAddPlaceUiState(
    val coordinate: GeoCoordinate? = null,
    val canFindNearby: Boolean = false,
    val canSearchMappedHere: Boolean = false,
    val isSearching: Boolean = false,
    val suggestions: List<RecordingPlaceSuggestionItem> = emptyList(),
    val outage: PlaceSearchOutage? = null,
    val distanceUnit: DistanceUnit = DistanceUnit.METRIC,
    val showSaveRefusal: Boolean = false,
    val openedPlaceId: String? = null
)

@HiltViewModel(assistedFactory = RecordingAddPlaceViewModel.Factory::class)
class RecordingAddPlaceViewModel @AssistedInject constructor(
    @Assisted("hikeId") rawHikeId: String,
    @Assisted("lat") latitude: Double?,
    @Assisted("lon") longitude: Double?,
    private val hikeRepository: HikeRepository,
    userSettingsRepository: UserSettingsRepository,
    private val trailPointSource: TrailPointSource
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("hikeId") rawHikeId: String,
            @Assisted("lat") latitude: Double?,
            @Assisted("lon") longitude: Double?
        ): RecordingAddPlaceViewModel
    }

    val hikeId: HikeId = HikeId(rawHikeId)
    val coordinate: GeoCoordinate? = if (latitude != null && longitude != null) {
        GeoCoordinate(latitude, longitude)
    } else {
        null
    }

    private val localState = MutableStateFlow(
        RecordingAddPlaceUiState(
            coordinate = coordinate,
            canFindNearby = trailPointSource.isAvailable,
            canSearchMappedHere = trailPointSource.isAvailable && coordinate != null
        )
    )

    val uiState: StateFlow<RecordingAddPlaceUiState> = combine(
        localState,
        userSettingsRepository.userSettings.catch { ex ->
            if (ex is IOException) emit(UserSettings()) else throw ex
        }
    ) { state, settings ->
        state.copy(distanceUnit = settings.distanceUnit)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = localState.value
    )

    init {
        loadSuggestions()
    }

    fun onPickSuggestion(place: TrailPoint) {
        viewModelScope.launch {
            val result = hikeRepository.addPlace(hikeId, place)
            if (result.isFailure) {
                localState.update { it.copy(showSaveRefusal = true) }
                return@launch
            }
            val hike = hikeRepository.observeHike(hikeId).first()
            val targetId = hike?.places?.firstOrNull { held ->
                val osm = place.osm
                val other = held.osm
                held.id == place.id || (osm != null && other != null && osm.isSameElement(other))
            }?.id
            if (targetId != null) {
                localState.update { it.copy(openedPlaceId = targetId) }
            }
        }
    }

    fun onDismissSaveRefusal() {
        localState.update { it.copy(showSaveRefusal = false) }
    }

    fun consumeOpenedPlace() {
        localState.update { it.copy(openedPlaceId = null) }
    }

    private fun loadSuggestions() {
        val here = coordinate ?: return
        if (!trailPointSource.isAvailable) return
        val area = NearbyPlaceSuggestions.area(here)
        localState.update { it.copy(isSearching = true, outage = null) }
        viewModelScope.launch {
            val held = hikeRepository.observeHike(hikeId).first()?.places.orEmpty()
            val cached = trailPointSource.cachedPlaces(area, TrailPointQuery.MAXIMUM_STORED_RESULTS)
            val cachedSuggestions = NearbyPlaceSuggestions.suggestions(cached, here, held).map { place ->
                RecordingPlaceSuggestionItem(place, RouteGeometry.distanceMeters(here, place.coordinate))
            }
            localState.update { it.copy(suggestions = cachedSuggestions) }
            when (val live = trailPointSource.places(area, TrailPlaceSymbol.entries.toSet())) {
                is TrailPointSearchResult.Places -> {
                    val liveSuggestions = NearbyPlaceSuggestions.suggestions(live.places, here, held).map { place ->
                        RecordingPlaceSuggestionItem(place, RouteGeometry.distanceMeters(here, place.coordinate))
                    }
                    localState.update { it.copy(isSearching = false, suggestions = liveSuggestions, outage = null) }
                }
                is TrailPointSearchResult.Failed -> {
                    localState.update { it.copy(isSearching = false, outage = live.outage) }
                }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
