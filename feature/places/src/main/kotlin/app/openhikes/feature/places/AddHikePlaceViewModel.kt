package app.openhikes.feature.places

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.common.BoundedText
import app.openhikes.core.common.TextBound
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapCameraTracker
import app.openhikes.core.map.MapClaim
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.MapPlacePin
import app.openhikes.core.map.MapTrailPlacesOverlay
import app.openhikes.core.map.TrailPlacesClaim
import app.openhikes.core.map.TrailPlacesOverlayState
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.TrailPoint
import app.openhikes.core.ui.navigation.AddHikePlaceRoute
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class AddHikePlaceUiState(
    val name: String = TrailPlaceSymbol.VIEWPOINT.rawValue,
    val symbol: TrailPlaceSymbol? = TrailPlaceSymbol.VIEWPOINT,
    val note: String = "",
    val coordinate: GeoCoordinate,
    val isSaving: Boolean = false,
    val showSaveRefusal: Boolean = false,
    val addedPlaceId: String? = null
) {
    fun displayName(unnamedFallback: String, symbolLabelProvider: (TrailPlaceSymbol) -> String): String {
        val typed = name.trim()
        return typed.ifEmpty { symbol?.let(symbolLabelProvider) ?: unnamedFallback }
    }
}

/**
 * ViewModel for [AddHikePlaceRoute] holding the new place draft, fixed center placement pin,
 * and live coordinate tracking as the hiker pans the map under the pin.
 */
@Suppress("LongParameterList")
@HiltViewModel(assistedFactory = AddHikePlaceViewModel.Factory::class)
class AddHikePlaceViewModel @AssistedInject constructor(
    @Assisted("hikeId") rawHikeId: String,
    @Assisted("lat") initialLatitude: Double,
    @Assisted("lon") initialLongitude: Double,
    private val hikeRepository: HikeRepository,
    private val mapClaimCoordinator: MapClaimCoordinator,
    private val mapCameraTracker: MapCameraTracker,
    private val trailPlacesOverlayState: TrailPlacesOverlayState
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("hikeId") rawHikeId: String,
            @Assisted("lat") initialLatitude: Double,
            @Assisted("lon") initialLongitude: Double
        ): AddHikePlaceViewModel
    }

    val hikeId: HikeId = HikeId(rawHikeId)
    private val initialCoordinate = GeoCoordinate(latitude = initialLatitude, longitude = initialLongitude)
    private val routeKey = AddHikePlaceRoute(
        hikeId = hikeId,
        initialLatitude = initialLatitude,
        initialLongitude = initialLongitude
    )
    private val placeId = UUID.randomUUID().toString()

    private val uiStateFlow = MutableStateFlow(AddHikePlaceUiState(coordinate = initialCoordinate))
    val uiState: StateFlow<AddHikePlaceUiState> = uiStateFlow.asStateFlow()

    @Volatile
    private var latestHikePins: List<MapPlacePin> = emptyList()

    init {
        mapClaimCoordinator.registerClaim(
            MapClaim(routeKey = routeKey, depth = INITIAL_CLAIM_DEPTH, selectedHikeId = hikeId)
        )
        mapClaimCoordinator.requestCameraCommand(
            routeKey = routeKey,
            command = MapCameraCommand.MoveTo(
                id = mapClaimCoordinator.nextCommandId(),
                center = initialCoordinate,
                zoom = PLACEMENT_ZOOM
            )
        )
        publishPlacementOverlay()
        observeHikePins()
        observeMapCenter()
    }

    override fun onCleared() {
        trailPlacesOverlayState.clearClaim(routeKey)
        mapClaimCoordinator.releaseClaim(routeKey)
    }

    fun onNameChange(name: String) {
        uiStateFlow.update { it.copy(name = name) }
    }

    /**
     * Updates the selected kind and carries the default name along while it still matches the
     * previous kind's label, preserving any custom name typed by the hiker.
     */
    fun onSymbolChange(next: TrailPlaceSymbol?, previousKindLabel: String, nextKindLabel: String) {
        uiStateFlow.update { current ->
            val updatedName = if (current.name.trim() == previousKindLabel) {
                nextKindLabel
            } else {
                current.name
            }
            current.copy(name = updatedName, symbol = next)
        }
        publishPlacementOverlay()
    }

    fun onNoteChange(note: String) {
        uiStateFlow.update { it.copy(note = note) }
    }

    fun onConfirmAdd(currentKindLabel: String) {
        val current = uiStateFlow.value
        if (current.isSaving) return
        val boundedName = BoundedText.boundedOrEmpty(current.name, TextBound.TITLE)
        val storedName = if (boundedName == currentKindLabel) "" else boundedName
        val newPlace = TrailPoint(
            id = placeId,
            hikeId = hikeId,
            coordinate = current.coordinate,
            name = storedName,
            symbol = current.symbol?.rawValue,
            note = BoundedText.boundedOrEmpty(current.note, TextBound.NOTES)
        )
        uiStateFlow.update { it.copy(isSaving = true, showSaveRefusal = false) }
        viewModelScope.launch {
            val result = hikeRepository.addPlace(hikeId, newPlace)
            if (result.isSuccess) {
                uiStateFlow.update { it.copy(isSaving = false, addedPlaceId = newPlace.id) }
            } else {
                uiStateFlow.update { it.copy(isSaving = false, showSaveRefusal = true) }
            }
        }
    }

    fun onDismissSaveRefusal() {
        uiStateFlow.update { it.copy(showSaveRefusal = false) }
    }

    fun consumeAddedNavigation() {
        uiStateFlow.update { it.copy(addedPlaceId = null) }
    }

    private fun observeHikePins() {
        viewModelScope.launch {
            hikeRepository.observeHike(hikeId).collect { hike ->
                latestHikePins = hike?.places.orEmpty().map { place ->
                    MapPlacePin(
                        id = place.id,
                        coordinate = place.coordinate,
                        symbol = place.symbolKind,
                        title = place.name.ifBlank { place.symbol ?: DEFAULT_PIN_TITLE }
                    )
                }
                publishPlacementOverlay()
            }
        }
    }

    private fun observeMapCenter() {
        viewModelScope.launch {
            mapCameraTracker.cameraState.drop(1).collect { camera ->
                uiStateFlow.update { it.copy(coordinate = camera.center) }
            }
        }
    }

    private fun publishPlacementOverlay() {
        trailPlacesOverlayState.setClaim(
            TrailPlacesClaim(
                routeKey = routeKey,
                overlay = MapTrailPlacesOverlay(
                    pins = latestHikePins,
                    showCenterPlacementPin = true,
                    placementSymbol = uiStateFlow.value.symbol
                )
            )
        )
    }

    private companion object {
        const val INITIAL_CLAIM_DEPTH = 3
        const val PLACEMENT_ZOOM = 16.0
        const val DEFAULT_PIN_TITLE = "Place"
    }
}
