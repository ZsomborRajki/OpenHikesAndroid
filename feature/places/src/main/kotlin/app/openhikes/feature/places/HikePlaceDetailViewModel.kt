package app.openhikes.feature.places

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.common.DefaultDispatcher
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.data.WikidataPlaceLookup
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapClaim
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.MapPlacePin
import app.openhikes.core.map.MapTrailPlacesOverlay
import app.openhikes.core.map.TrailPlacesClaim
import app.openhikes.core.map.TrailPlacesMapInteractionHandler
import app.openhikes.core.map.TrailPlacesOverlayState
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.TrailPlaceOrder
import app.openhikes.core.model.TrailPlaceRow
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.UserSettings
import app.openhikes.core.model.WikidataPlace
import app.openhikes.core.ui.navigation.HikePlaceDetailRoute
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class HikePlaceDetailUiState(
    val row: TrailPlaceRow? = null,
    val distanceUnit: DistanceUnit = DistanceUnit.METRIC,
    val wikidataPlace: WikidataPlace? = null,
    val isLoaded: Boolean = false,
    val isEditing: Boolean = false,
    val editName: String = "",
    val editSymbol: TrailPlaceSymbol? = null,
    val editNote: String = "",
    val showRemoveDialog: Boolean = false,
    val showSaveRefusal: Boolean = false,
    val pendingOpenPlaceId: String? = null
)

@Suppress("LongParameterList", "TooManyFunctions")
@HiltViewModel(assistedFactory = HikePlaceDetailViewModel.Factory::class)
class HikePlaceDetailViewModel @AssistedInject constructor(
    @Assisted("hikeId") rawHikeId: String,
    @Assisted("placeId") val placeId: String,
    private val hikeRepository: HikeRepository,
    userSettingsRepository: UserSettingsRepository,
    private val wikidataPlaceLookup: WikidataPlaceLookup,
    private val mapClaimCoordinator: MapClaimCoordinator,
    private val trailPlacesOverlayState: TrailPlacesOverlayState,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("hikeId") rawHikeId: String,
            @Assisted("placeId") placeId: String
        ): HikePlaceDetailViewModel
    }

    val hikeId: HikeId = HikeId(rawHikeId)
    private val routeKey = HikePlaceDetailRoute(hikeId = hikeId, placeId = placeId)

    private val editorState = MutableStateFlow(EditorOverlayState())
    private val wikidataState = MutableStateFlow<WikidataPlace?>(null)
    private val pendingOpenPlaceIdState = MutableStateFlow<String?>(null)
    private var lastLoadedWikidataItem: String? = null

    private val rowsFlow = hikeRepository.observeHike(hikeId)
        .map { hike -> hike?.let { it.places to it.route } }
        .distinctUntilChanged()
        .map { pair ->
            if (pair == null) {
                null
            } else {
                TrailPlaceOrder.ordered(pair.first, pair.second)
            }
        }
        .flowOn(defaultDispatcher)

    private val settingsFlow = userSettingsRepository.userSettings.catch { ex ->
        if (ex is IOException) emit(UserSettings()) else throw ex
    }

    val uiState: StateFlow<HikePlaceDetailUiState> = combine(
        rowsFlow,
        settingsFlow,
        wikidataState,
        editorState,
        pendingOpenPlaceIdState
    ) { rows, settings, wikidata, editor, openPlaceId ->
        val row = rows?.firstOrNull { it.id == placeId }
        val resolvedWikidata = row?.place?.osm?.wikidata?.let { item ->
            wikidata?.takeIf { it.item == item }
        }
        HikePlaceDetailUiState(
            row = row,
            distanceUnit = settings.distanceUnit,
            wikidataPlace = resolvedWikidata,
            isLoaded = true,
            isEditing = editor.isEditing,
            editName = editor.name,
            editSymbol = editor.symbol,
            editNote = editor.note,
            showRemoveDialog = editor.showRemoveDialog,
            showSaveRefusal = editor.showSaveRefusal,
            pendingOpenPlaceId = openPlaceId
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = HikePlaceDetailUiState()
    )

    init {
        mapClaimCoordinator.registerClaim(
            MapClaim(routeKey = routeKey, depth = INITIAL_CLAIM_DEPTH, selectedHikeId = hikeId)
        )
        observePlacePins()
        observeWikidataItem()
    }

    override fun onCleared() {
        trailPlacesOverlayState.clearClaim(routeKey)
        mapClaimCoordinator.releaseClaim(routeKey)
    }

    fun onShowOnMap() {
        val coordinate = uiState.value.row?.place?.coordinate ?: return
        mapClaimCoordinator.requestCameraCommand(
            routeKey = routeKey,
            command = MapCameraCommand.MoveTo(
                id = mapClaimCoordinator.nextCommandId(),
                center = coordinate,
                zoom = SHOW_ON_MAP_ZOOM
            )
        )
    }

    fun onStartEdit() {
        val place = uiState.value.row?.place ?: return
        if (!place.isHikersOwn) return
        editorState.update {
            it.copy(
                isEditing = true,
                name = place.name,
                symbol = place.symbolKind,
                note = place.note,
                showSaveRefusal = false
            )
        }
    }

    fun onEditNameChange(name: String) {
        editorState.update { it.copy(name = name) }
    }

    fun onEditSymbolChange(symbol: TrailPlaceSymbol?) {
        editorState.update { it.copy(symbol = symbol) }
    }

    fun onEditNoteChange(note: String) {
        editorState.update { it.copy(note = note) }
    }

    fun onCancelEdit() {
        editorState.update { it.copy(isEditing = false) }
    }

    fun onSaveEdit() {
        val current = editorState.value
        viewModelScope.launch {
            val result = hikeRepository.editPlace(
                hikeId = hikeId,
                placeId = placeId,
                name = current.name,
                symbol = current.symbol,
                note = current.note
            )
            if (result.isSuccess) {
                editorState.update { it.copy(isEditing = false, showSaveRefusal = false) }
            } else {
                editorState.update { it.copy(showSaveRefusal = true) }
            }
        }
    }

    fun onRequestRemove() {
        editorState.update { it.copy(showRemoveDialog = true) }
    }

    fun onCancelRemove() {
        editorState.update { it.copy(showRemoveDialog = false) }
    }

    fun onConfirmRemove() {
        editorState.update { it.copy(showRemoveDialog = false) }
        viewModelScope.launch {
            val result = hikeRepository.removePlace(hikeId, placeId)
            if (result.isFailure) {
                editorState.update { it.copy(showSaveRefusal = true) }
            }
        }
    }

    fun onDismissSaveRefusal() {
        editorState.update { it.copy(showSaveRefusal = false) }
    }

    fun consumeOpenPlace() {
        pendingOpenPlaceIdState.value = null
    }

    private fun observePlacePins() {
        viewModelScope.launch {
            combine(
                rowsFlow,
                settingsFlow.map { it.showsPlacePins }.distinctUntilChanged()
            ) { rows, showsPins ->
                val pins = if (showsPins && rows != null) {
                    rows.map { row ->
                        MapPlacePin(
                            id = row.id,
                            coordinate = row.place.coordinate,
                            symbol = row.place.symbolKind,
                            title = row.place.name.ifBlank { row.place.symbol ?: DEFAULT_PIN_TITLE }
                        )
                    }
                } else {
                    emptyList()
                }
                pins
            }.collect { pins ->
                trailPlacesOverlayState.setClaim(
                    TrailPlacesClaim(
                        routeKey = routeKey,
                        overlay = MapTrailPlacesOverlay(pins = pins),
                        handler = object : TrailPlacesMapInteractionHandler {
                            override fun onTapPlacePin(placeId: String) {
                                if (placeId != this@HikePlaceDetailViewModel.placeId) {
                                    pendingOpenPlaceIdState.value = placeId
                                }
                            }
                        }
                    )
                )
            }
        }
    }

    private fun observeWikidataItem() {
        viewModelScope.launch {
            rowsFlow
                .map { rows -> rows?.firstOrNull { it.id == placeId }?.place?.osm?.wikidata }
                .distinctUntilChanged()
                .collect { item ->
                    if (item == null) {
                        lastLoadedWikidataItem = null
                        wikidataState.value = null
                    } else if (item != lastLoadedWikidataItem) {
                        lastLoadedWikidataItem = item
                        wikidataState.value = wikidataPlaceLookup.cached(item)
                        wikidataState.value = wikidataPlaceLookup.lookup(item)
                    }
                }
        }
    }

    private data class EditorOverlayState(
        val isEditing: Boolean = false,
        val name: String = "",
        val symbol: TrailPlaceSymbol? = null,
        val note: String = "",
        val showRemoveDialog: Boolean = false,
        val showSaveRefusal: Boolean = false
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val INITIAL_CLAIM_DEPTH = 2
        const val SHOW_ON_MAP_ZOOM = 16.0
        const val DEFAULT_PIN_TITLE = "Place"
    }
}
