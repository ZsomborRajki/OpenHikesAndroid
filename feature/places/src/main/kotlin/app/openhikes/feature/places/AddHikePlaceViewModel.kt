package app.openhikes.feature.places

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.common.BoundedText
import app.openhikes.core.common.TextBound
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapCameraTracker
import app.openhikes.core.map.MapClaim
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.MapPlacePin
import app.openhikes.core.map.MapTrailPlacesOverlay
import app.openhikes.core.map.PhotoCaptureController
import app.openhikes.core.map.PhotoCaptureSubject
import app.openhikes.core.map.TrailPlacesClaim
import app.openhikes.core.map.TrailPlacesMapInteractionHandler
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
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class AddHikePlaceUiState(
    val name: String = TrailPlaceSymbol.VIEWPOINT.rawValue,
    val symbol: TrailPlaceSymbol? = TrailPlaceSymbol.VIEWPOINT,
    val note: String = "",
    val coordinate: GeoCoordinate,
    val stagedPhotos: List<StagedPlacePhoto> = emptyList(),
    val isStagingPhoto: Boolean = false,
    val isSaving: Boolean = false,
    val showSaveRefusal: Boolean = false,
    val photoAttachFailureCount: Int = 0,
    val addedPlaceId: String? = null
) {
    val canConfirmAdd: Boolean
        get() = !isSaving && !isStagingPhoto

    fun displayName(unnamedFallback: String, symbolLabelProvider: (TrailPlaceSymbol) -> String): String {
        val typed = name.trim()
        return typed.ifEmpty { symbol?.let(symbolLabelProvider) ?: unnamedFallback }
    }
}

/**
 * ViewModel for [AddHikePlaceRoute] holding the new place draft, fixed center placement pin,
 * in-memory staged photos, and live coordinate tracking as the hiker pans the map under the pin.
 */
@Suppress("LongParameterList", "TooManyFunctions")
@HiltViewModel(assistedFactory = AddHikePlaceViewModel.Factory::class)
class AddHikePlaceViewModel @AssistedInject constructor(
    @Assisted("hikeId") rawHikeId: String,
    @Assisted("lat") initialLatitude: Double,
    @Assisted("lon") initialLongitude: Double,
    private val hikeRepository: HikeRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val mapClaimCoordinator: MapClaimCoordinator,
    private val mapCameraTracker: MapCameraTracker,
    private val trailPlacesOverlayState: TrailPlacesOverlayState,
    private val photoCaptureController: PhotoCaptureController
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
    private var createdPlace: TrailPoint? = null

    private val uiStateFlow = MutableStateFlow(AddHikePlaceUiState(coordinate = initialCoordinate))
    val uiState: StateFlow<AddHikePlaceUiState> = uiStateFlow.asStateFlow()

    @Volatile
    private var latestHikePins: List<MapPlacePin> = emptyList()

    @Volatile
    private var hasProjectedCoordinate = false

    private val placementInteractionHandler = object : TrailPlacesMapInteractionHandler {
        override fun onPlacementCoordinateChanged(coordinate: GeoCoordinate) {
            hasProjectedCoordinate = true
            uiStateFlow.update { it.copy(coordinate = coordinate) }
        }
    }

    init {
        mapClaimCoordinator.registerClaim(
            MapClaim(routeKey = routeKey, depth = INITIAL_CLAIM_DEPTH, selectedHikeId = hikeId)
        )
        photoCaptureController.registerSubject(
            PhotoCaptureSubject(
                routeKey = routeKey,
                depth = INITIAL_CLAIM_DEPTH,
                hikeId = hikeId,
                placeId = null,
                showFloatingMapButtons = false
            )
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
        photoCaptureController.releaseSubject(routeKey)
        mapClaimCoordinator.releaseClaim(routeKey)
    }

    fun onSetStagingPhoto(busy: Boolean) {
        uiStateFlow.update { it.copy(isStagingPhoto = busy) }
    }

    fun onStageCameraPhoto(bytes: ByteArray, capturedAtEpochMillis: Long?) {
        val staged = StagedPlacePhoto(
            id = UUID.randomUUID().toString(),
            bytes = bytes,
            capturedAtEpochMillis = capturedAtEpochMillis,
            fromCamera = true
        )
        uiStateFlow.update { it.copy(stagedPhotos = it.stagedPhotos + staged, isStagingPhoto = false) }
    }

    fun onStageLibraryPhotos(bytesList: List<ByteArray>) {
        val additions = bytesList.map { bytes ->
            StagedPlacePhoto(
                id = UUID.randomUUID().toString(),
                bytes = bytes,
                capturedAtEpochMillis = null,
                fromCamera = false
            )
        }
        uiStateFlow.update { it.copy(stagedPhotos = it.stagedPhotos + additions, isStagingPhoto = false) }
    }

    fun onRemoveStagedPhoto(stagedId: String) {
        uiStateFlow.update { current ->
            current.copy(stagedPhotos = current.stagedPhotos.filterNot { it.id == stagedId })
        }
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
        if (!current.canConfirmAdd) return
        val existingCreated = createdPlace
        val targetPlace = existingCreated ?: buildDraftPlace(current, currentKindLabel)
        val stagedSnapshot = current.stagedPhotos
        uiStateFlow.update { it.copy(isSaving = true, showSaveRefusal = false, photoAttachFailureCount = 0) }
        viewModelScope.launch {
            val placePersisted = if (existingCreated != null) {
                true
            } else {
                val addResult = hikeRepository.addPlace(hikeId, targetPlace)
                if (addResult.isSuccess) {
                    createdPlace = targetPlace
                    true
                } else {
                    false
                }
            }
            if (!placePersisted) {
                uiStateFlow.update { it.copy(isSaving = false, showSaveRefusal = true) }
                return@launch
            }
            val failed = commitStagedPhotos(targetPlace, stagedSnapshot)
            if (failed.isEmpty()) {
                uiStateFlow.update {
                    it.copy(
                        isSaving = false,
                        stagedPhotos = emptyList(),
                        photoAttachFailureCount = 0,
                        addedPlaceId = targetPlace.id
                    )
                }
            } else {
                uiStateFlow.update {
                    it.copy(
                        isSaving = false,
                        stagedPhotos = failed,
                        photoAttachFailureCount = failed.size
                    )
                }
            }
        }
    }

    fun onContinueWithoutFailedPhotos() {
        val savedId = createdPlace?.id ?: return
        uiStateFlow.update {
            it.copy(
                stagedPhotos = emptyList(),
                photoAttachFailureCount = 0,
                addedPlaceId = savedId
            )
        }
    }

    fun onDismissPhotoAttachError() {
        uiStateFlow.update { it.copy(photoAttachFailureCount = 0) }
    }

    private fun buildDraftPlace(current: AddHikePlaceUiState, currentKindLabel: String): TrailPoint {
        val boundedName = BoundedText.boundedOrEmpty(current.name, TextBound.TITLE)
        val storedName = if (boundedName == currentKindLabel) "" else boundedName
        return TrailPoint(
            id = placeId,
            hikeId = hikeId,
            coordinate = current.coordinate,
            name = storedName,
            symbol = current.symbol?.rawValue,
            note = BoundedText.boundedOrEmpty(current.note, TextBound.NOTES)
        )
    }

    private suspend fun commitStagedPhotos(place: TrailPoint, staged: List<StagedPlacePhoto>): List<StagedPlacePhoto> {
        if (staged.isEmpty()) return emptyList()
        val saveToLibrary = try {
            userSettingsRepository.userSettings.first().savePhotosToLibrary
        } catch (_: IOException) {
            false
        }
        val failed = ArrayList<StagedPlacePhoto>()
        for (item in staged) {
            val result = if (item.fromCamera) {
                hikeRepository.addCameraPhoto(
                    hikeId = hikeId,
                    rawBytes = item.bytes,
                    capturedAtEpochMillis = item.capturedAtEpochMillis,
                    coordinate = place.coordinate,
                    placeId = place.id,
                    saveToSystemLibrary = saveToLibrary
                )
            } else {
                hikeRepository.addPickedPhoto(
                    hikeId = hikeId,
                    bytes = item.bytes,
                    assetLocalIdentifier = null,
                    fallbackCoordinate = place.coordinate,
                    placeId = place.id
                )
            }
            if (result.isFailure) {
                failed.add(item)
            }
        }
        return failed
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
                if (!hasProjectedCoordinate) {
                    uiStateFlow.update { it.copy(coordinate = camera.center) }
                }
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
                ),
                handler = placementInteractionHandler
            )
        )
    }

    private companion object {
        const val INITIAL_CLAIM_DEPTH = 3
        const val PLACEMENT_ZOOM = 16.0
        const val DEFAULT_PIN_TITLE = "Place"
    }
}
