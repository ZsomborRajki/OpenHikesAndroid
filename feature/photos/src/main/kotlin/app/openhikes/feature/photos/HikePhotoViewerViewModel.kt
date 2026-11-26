package app.openhikes.feature.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.common.DefaultDispatcher
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapClaim
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.MapPhotoPinItem
import app.openhikes.core.map.MapRouteHighlight
import app.openhikes.core.map.MapTrailPhotosOverlay
import app.openhikes.core.map.RouteHighlightState
import app.openhikes.core.map.TrailPhotosClaim
import app.openhikes.core.map.TrailPhotosMapInteractionHandler
import app.openhikes.core.map.TrailPhotosOverlayState
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikePhoto
import app.openhikes.core.model.OrderedHikePhotos
import app.openhikes.core.model.PhotoMapPin
import app.openhikes.core.model.PhotoUnavailableReason
import app.openhikes.core.ui.components.PhotoPresentationFormatter
import app.openhikes.core.ui.navigation.HikePhotoViewerRoute
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordinates the full-screen photograph viewer: chronological ordering, map-pin synchronization,
 * Show-on-map / re-expand transitions, share preparation, and confirmed deletion.
 */
@Suppress("LongParameterList", "TooManyFunctions")
@HiltViewModel(assistedFactory = HikePhotoViewerViewModel.Factory::class)
class HikePhotoViewerViewModel @AssistedInject constructor(
    @Assisted("hikeId") rawHikeId: String,
    @Assisted("startPhotoId") startPhotoId: String,
    private val hikeRepository: HikeRepository,
    private val mapClaimCoordinator: MapClaimCoordinator,
    private val routeHighlightState: RouteHighlightState,
    private val trailPhotosOverlayState: TrailPhotosOverlayState,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("hikeId") rawHikeId: String,
            @Assisted("startPhotoId") startPhotoId: String
        ): HikePhotoViewerViewModel
    }

    val hikeId: HikeId = HikeId(rawHikeId)

    private val routeKey = HikePhotoViewerRoute(hikeId = hikeId, startPhotoId = startPhotoId)
    private val actionTokens = AtomicLong(0L)
    private val stateFlow = MutableStateFlow(HikePhotoViewerUiState(currentPhotoId = startPhotoId))
    private var orderedPhotos: OrderedHikePhotos = OrderedHikePhotos.Empty
    private var lastTintHex: String = DEFAULT_TINT_HEX
    private var hasReceivedFirstHike = false

    val uiState: StateFlow<HikePhotoViewerUiState> = stateFlow.asStateFlow()

    private val mapHandler = object : TrailPhotosMapInteractionHandler {
        override fun onTapPhotoPin(pinId: String) {
            trailPhotosOverlayState.selectPhoto(routeKey, pinId)
        }

        override fun onTapPhotoCallout(photoId: String) {
            onOpenFromMapPin(photoId)
        }

        override fun onDismissPhotoCallout() {
            trailPhotosOverlayState.selectPhoto(routeKey, null)
        }

        override suspend fun loadThumbnailBytes(photo: HikePhoto): ByteArray? =
            hikeRepository.readPhotoThumbnailBytes(photo)
    }

    init {
        mapClaimCoordinator.registerClaim(
            MapClaim(
                routeKey = routeKey,
                depth = VIEWER_CLAIM_DEPTH,
                selectedHikeId = hikeId
            )
        )
        viewModelScope.launch {
            hikeRepository.observeHike(hikeId).collect { hike ->
                applyHikeSnapshot(hike)
            }
        }
    }

    fun onAction(action: HikePhotoViewerAction) {
        when (action) {
            is HikePhotoViewerAction.SelectPhoto -> selectPhoto(action.photoId)
            HikePhotoViewerAction.StepPrevious -> stepBy(-1)
            HikePhotoViewerAction.StepNext -> stepBy(1)
            HikePhotoViewerAction.ShowOnMap -> showCurrentOnMap()
            HikePhotoViewerAction.TapPhotoSurface -> handlePhotoSurfaceTap()
            HikePhotoViewerAction.ShareCurrent -> shareCurrentPhoto()
            HikePhotoViewerAction.RequestDelete -> stateFlow.update { it.copy(showDeleteDialog = true) }
            HikePhotoViewerAction.ConfirmDelete -> deleteCurrentPhoto()
            HikePhotoViewerAction.DismissDelete -> stateFlow.update { it.copy(showDeleteDialog = false) }
            HikePhotoViewerAction.DismissError -> stateFlow.update { it.copy(errorMessage = null) }
            HikePhotoViewerAction.ConsumeRevealMap -> stateFlow.update { it.copy(pendingRevealMapToken = 0L) }
            HikePhotoViewerAction.ConsumeExpandFullHeight ->
                stateFlow.update { it.copy(pendingExpandFullHeightToken = 0L) }
            HikePhotoViewerAction.ConsumeShareFile -> stateFlow.update { it.copy(pendingShareFile = null) }
        }
    }

    suspend fun loadPageContent(photo: HikePhoto): PhotoPageContent {
        if (photo.recordsPlaceOnly) {
            return PhotoPageContent.Unavailable(PhotoUnavailableReason.PLACE_ONLY)
        }
        val exists = hikeRepository.hasPhotoFile(photo)
        return if (!exists) {
            stateFlow.update { it.copy(shareablePhotoIds = it.shareablePhotoIds - photo.id) }
            val reason = if (photo.license != null) {
                PhotoUnavailableReason.NOT_DOWNLOADED
            } else {
                PhotoUnavailableReason.NOT_ON_THIS_DEVICE
            }
            PhotoPageContent.Unavailable(reason)
        } else {
            stateFlow.update { it.copy(shareablePhotoIds = it.shareablePhotoIds + photo.id) }
            val bytes = hikeRepository.readPhotoBytes(photo)
            if (bytes != null && bytes.isNotEmpty()) {
                PhotoPageContent.Ready(bytes)
            } else {
                PhotoPageContent.Unavailable(PhotoUnavailableReason.UNREADABLE)
            }
        }
    }

    override fun onCleared() {
        routeHighlightState.clear()
        trailPhotosOverlayState.clearClaim(routeKey)
        mapClaimCoordinator.releaseClaim(routeKey)
    }

    private suspend fun applyHikeSnapshot(hike: Hike?) {
        if (hike == null) {
            if (hasReceivedFirstHike) {
                stateFlow.update { it.copy(photos = emptyList(), shouldClose = true) }
            }
            return
        }
        hasReceivedFirstHike = true
        lastTintHex = hike.tintHex
        orderedPhotos = withContext(defaultDispatcher) { OrderedHikePhotos.from(hike.photos) }
        val sorted = orderedPhotos.photos
        if (sorted.isEmpty()) {
            trailPhotosOverlayState.clearClaim(routeKey)
            stateFlow.update { it.copy(hikeTitle = hike.displayTitle, photos = emptyList(), shouldClose = true) }
            return
        }
        val currentId = stateFlow.value.currentPhotoId?.takeIf { orderedPhotos.indexOf(it) != null }
            ?: sorted.first().id
        val shareable = resolveShareableIds(sorted)
        stateFlow.update {
            it.copy(
                hikeTitle = hike.displayTitle,
                photos = sorted,
                currentPhotoId = currentId,
                shareablePhotoIds = shareable,
                shouldClose = false
            )
        }
        publishPhotoPinsOverlay(sorted, selectedPhotoId = currentId.takeIf { stateFlow.value.isMapRevealed })
    }

    private suspend fun resolveShareableIds(photos: List<HikePhoto>): Set<String> {
        val result = HashSet<String>(photos.size)
        for (photo in photos) {
            if (!photo.recordsPlaceOnly && hikeRepository.hasPhotoFile(photo)) {
                result.add(photo.id)
            }
        }
        return result
    }

    private fun selectPhoto(photoId: String) {
        if (orderedPhotos.indexOf(photoId) == null || stateFlow.value.currentPhotoId == photoId) return
        stateFlow.update { it.copy(currentPhotoId = photoId) }
        if (stateFlow.value.isMapRevealed) {
            followPhotoOnMap(photoId)
        }
    }

    private fun stepBy(delta: Int) {
        val state = stateFlow.value
        val nextIndex = state.currentIndex + delta
        if (nextIndex in state.photos.indices) {
            selectPhoto(state.photos[nextIndex].id)
        }
    }

    private fun showCurrentOnMap() {
        val current = stateFlow.value.currentPhoto ?: return
        val coordinate = current.coordinate ?: return
        routeHighlightState.move(
            MapRouteHighlight(
                hikeId = hikeId,
                coordinate = coordinate,
                tintHex = lastTintHex
            )
        )
        mapClaimCoordinator.requestCameraCommand(
            routeKey = routeKey,
            command = MapCameraCommand.FitBounds(
                id = mapClaimCoordinator.nextCommandId(),
                bounds = TrailPhotosOverlayState.photoSpotBounds(coordinate)
            )
        )
        trailPhotosOverlayState.selectPhoto(routeKey, current.id)
        stateFlow.update {
            it.copy(
                isMapRevealed = true,
                pendingRevealMapToken = actionTokens.incrementAndGet()
            )
        }
    }

    private fun followPhotoOnMap(photoId: String) {
        val photo = orderedPhotos.photo(photoId)
        val coordinate = photo?.coordinate
        if (coordinate != null) {
            routeHighlightState.move(
                MapRouteHighlight(
                    hikeId = hikeId,
                    coordinate = coordinate,
                    tintHex = lastTintHex
                )
            )
            trailPhotosOverlayState.selectPhoto(routeKey, photoId)
            mapClaimCoordinator.requestCameraCommand(
                routeKey = routeKey,
                command = MapCameraCommand.MoveTo(
                    id = mapClaimCoordinator.nextCommandId(),
                    center = coordinate,
                    zoom = null
                )
            )
        } else {
            routeHighlightState.clear()
            trailPhotosOverlayState.selectPhoto(routeKey, null)
        }
    }

    private fun handlePhotoSurfaceTap() {
        if (!stateFlow.value.isMapRevealed) return
        trailPhotosOverlayState.selectPhoto(routeKey, null)
        stateFlow.update {
            it.copy(
                isMapRevealed = false,
                pendingExpandFullHeightToken = actionTokens.incrementAndGet()
            )
        }
    }

    private fun onOpenFromMapPin(photoId: String) {
        trailPhotosOverlayState.selectPhoto(routeKey, null)
        stateFlow.update {
            it.copy(
                currentPhotoId = photoId,
                isMapRevealed = false,
                pendingExpandFullHeightToken = actionTokens.incrementAndGet()
            )
        }
    }

    private fun shareCurrentPhoto() {
        val state = stateFlow.value
        val current = state.currentPhoto ?: return
        val positionOneBased = state.currentIndex + 1
        viewModelScope.launch {
            hikeRepository.prepareSharePhotoFile(
                hikeTitle = state.hikeTitle,
                positionOneBased = positionOneBased,
                photo = current
            ).fold(
                onSuccess = { file -> stateFlow.update { currentState -> currentState.copy(pendingShareFile = file) } },
                onFailure = {
                    stateFlow.update { currentState ->
                        currentState.copy(errorMessage = PhotoViewerError.SHARE_FAILED)
                    }
                }
            )
        }
    }

    private fun deleteCurrentPhoto() {
        val current = stateFlow.value.currentPhoto ?: return
        val nextId = orderedPhotos.nextIdAfterDeleting(current.id)
        stateFlow.update { it.copy(showDeleteDialog = false, currentPhotoId = nextId) }
        viewModelScope.launch {
            hikeRepository.removePhoto(hikeId = hikeId, photoId = current.id).onFailure {
                stateFlow.update { currentState ->
                    currentState.copy(
                        currentPhotoId = current.id,
                        errorMessage = PhotoViewerError.DELETE_FAILED
                    )
                }
            }
        }
    }

    private fun publishPhotoPinsOverlay(photos: List<HikePhoto>, selectedPhotoId: String?) {
        val pins = PhotoMapPin.pinsFor(photos).map { pin ->
            MapPhotoPinItem(
                pin = pin,
                tintHex = lastTintHex,
                spokenLabel = PhotoPresentationFormatter.pinSpokenLabel(pin),
                subtitle = PhotoPresentationFormatter.pinSubtitle(pin),
                previewAccessibilityLabel = PhotoPresentationFormatter.pinPreviewLabel(pin)
            )
        }
        val selectedPinId = selectedPhotoId?.let { id -> pins.firstOrNull { item -> item.contains(id) }?.id }
        trailPhotosOverlayState.setClaim(
            TrailPhotosClaim(
                routeKey = routeKey,
                overlay = MapTrailPhotosOverlay(
                    pins = pins,
                    selectedPinId = selectedPinId
                ),
                handler = mapHandler
            )
        )
    }

    private companion object {
        const val VIEWER_CLAIM_DEPTH = 3
        const val DEFAULT_TINT_HEX = "#2E7D32"
    }
}
