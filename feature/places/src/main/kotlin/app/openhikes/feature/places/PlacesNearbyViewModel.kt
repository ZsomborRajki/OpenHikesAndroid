package app.openhikes.feature.places

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.data.WikidataPlaceLookup
import app.openhikes.core.location.RecordingController
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapCameraTracker
import app.openhikes.core.map.MapClaim
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.MapPlacePin
import app.openhikes.core.map.MapSearchAreaPillState
import app.openhikes.core.map.MapTrailPlacesOverlay
import app.openhikes.core.map.TrailPlacesClaim
import app.openhikes.core.map.TrailPlacesMapInteractionHandler
import app.openhikes.core.map.TrailPlacesOverlayState
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.PlaceSearchArea
import app.openhikes.core.model.PlaceSearchOutage
import app.openhikes.core.model.PlacesNearbyEntry
import app.openhikes.core.model.PlacesNearbyFrame
import app.openhikes.core.model.TrailPlaceHolding
import app.openhikes.core.model.TrailPlaceRow
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.TrailPoint
import app.openhikes.core.model.TrailPointQuery
import app.openhikes.core.model.TrailPointSearchResult
import app.openhikes.core.model.TrailPointSource
import app.openhikes.core.model.UserSettings
import app.openhikes.core.model.WikidataPlace
import app.openhikes.core.ui.navigation.PlacesNearbyRoute
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class PlacesNearbyUiState(
    val isSearching: Boolean = false,
    val outage: PlaceSearchOutage? = null,
    val hasAnsweredEmpty: Boolean = false,
    val isZoomedOutTooFar: Boolean = false,
    val shownSymbols: Set<TrailPlaceSymbol> = TrailPointQuery.searchableSymbols.toSet(),
    val distanceUnit: DistanceUnit = DistanceUnit.METRIC,
    val entries: List<PlacesNearbyEntry> = emptyList(),
    val selectedCard: SelectedAroundCard? = null,
    val showSaveRefusal: Boolean = false,
    val pendingLongPressCoordinate: GeoCoordinate? = null
)

@Suppress("LongParameterList", "TooManyFunctions")
@HiltViewModel(assistedFactory = PlacesNearbyViewModel.Factory::class)
class PlacesNearbyViewModel @AssistedInject constructor(
    @Assisted("hikeId") rawHikeId: String,
    @Assisted("lat") initialLatitude: Double?,
    @Assisted("lon") initialLongitude: Double?,
    private val hikeRepository: HikeRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val recordingController: RecordingController,
    private val trailPointSource: TrailPointSource,
    private val wikidataPlaceLookup: WikidataPlaceLookup,
    private val mapClaimCoordinator: MapClaimCoordinator,
    private val mapCameraTracker: MapCameraTracker,
    private val trailPlacesOverlayState: TrailPlacesOverlayState
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("hikeId") rawHikeId: String,
            @Assisted("lat") initialLatitude: Double?,
            @Assisted("lon") initialLongitude: Double?
        ): PlacesNearbyViewModel
    }

    val hikeId: HikeId = HikeId(rawHikeId)
    private val routeKey = PlacesNearbyRoute(hikeId, initialLatitude, initialLongitude)

    private val searchState = MutableStateFlow(NearbySearchState())
    private val selectedIdState = MutableStateFlow<String?>(null)
    private val wikidataState = MutableStateFlow<WikidataPlace?>(null)
    private val showSaveRefusalState = MutableStateFlow(false)
    private val pendingLongPressState = MutableStateFlow<GeoCoordinate?>(null)

    private var lastAreaAsked: PlaceSearchArea? = null
    private var lastSymbolsAsked: Set<TrailPlaceSymbol> = emptySet()
    private var originCoordinate: GeoCoordinate? = if (initialLatitude != null && initialLongitude != null) {
        GeoCoordinate(initialLatitude, initialLongitude)
    } else {
        null
    }
    private var latestShownSymbols: Set<TrailPlaceSymbol> = TrailPointQuery.searchableSymbols.toSet()
    private var latestHeldPlaces: List<TrailPoint> = emptyList()
    private var searchJob: Job? = null

    private val settingsFlow = userSettingsRepository.userSettings.catch { ex ->
        if (ex is IOException) emit(UserSettings()) else throw ex
    }

    private val selectionBundleFlow = combine(
        selectedIdState,
        wikidataState,
        showSaveRefusalState,
        pendingLongPressState
    ) { selectedId, wikidata, refusal, longPress ->
        NearbySelectionBundle(selectedId, wikidata, refusal, longPress)
    }

    val uiState: StateFlow<PlacesNearbyUiState> = combine(
        hikeRepository.observeHike(hikeId),
        settingsFlow,
        searchState,
        selectionBundleFlow,
        mapCameraTracker.canSearchVisibleArea
    ) { hike, settings, search, sel, canSearchArea ->
        val heldPlaces = hike?.places.orEmpty()
        latestHeldPlaces = heldPlaces
        val heldRows = heldPlaces.map { TrailPlaceRow(place = it) }
        val filteredCandidates = search.found
            .filter { row -> row.place.symbolKind?.let { it in settings.shownSearchPlaceSymbols } ?: true }
        val unheldIds = TrailPlaceHolding.unheld(
            filteredCandidates.map { it.place },
            hike?.places.orEmpty()
        ).map { it.id }.toSet()
        val candidates = filteredCandidates.filter { it.id in unheldIds }
        val entries = PlacesNearbyEntry.sorted(held = heldRows, candidates = candidates, origin = originCoordinate)
        val selectedCard = resolveSelectedCard(sel.selectedId, heldRows, search.found, sel.wikidata)
        publishMapClaim(
            heldRows = heldRows,
            candidates = candidates,
            isSearching = search.isSearching,
            shownSymbols = settings.shownSearchPlaceSymbols,
            canSearchArea = canSearchArea
        )
        PlacesNearbyUiState(
            isSearching = search.isSearching,
            outage = search.outage,
            hasAnsweredEmpty = search.hasAnswered && entries.isEmpty(),
            isZoomedOutTooFar = !canSearchArea && !search.isSearching,
            shownSymbols = settings.shownSearchPlaceSymbols,
            distanceUnit = settings.distanceUnit,
            entries = entries,
            selectedCard = selectedCard,
            showSaveRefusal = sel.showSaveRefusal,
            pendingLongPressCoordinate = sel.longPressCoordinate
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = PlacesNearbyUiState()
    )

    init {
        mapClaimCoordinator.registerClaim(MapClaim(routeKey = routeKey, depth = INITIAL_CLAIM_DEPTH))
        startInitialSearch()
        observeSymbolWidening()
    }

    override fun onCleared() {
        searchJob?.cancel()
        trailPlacesOverlayState.clearClaim(routeKey)
        mapClaimCoordinator.releaseClaim(routeKey)
    }

    fun onToggleSymbol(symbol: TrailPlaceSymbol, visible: Boolean) {
        viewModelScope.launch {
            userSettingsRepository.setPlaceSymbolVisible(symbol, visible)
        }
    }

    fun onRetry() {
        val area = lastAreaAsked ?: mapCameraTracker.currentSearchableArea() ?: return
        askArea(area, latestShownSymbols)
    }

    fun onSearchVisibleArea() {
        val area = mapCameraTracker.currentSearchableArea() ?: return
        askArea(area, latestShownSymbols)
    }

    fun onAddPlace(placeId: String) {
        val place = searchState.value.found.firstOrNull { it.id == placeId }?.place ?: return
        viewModelScope.launch {
            val result = hikeRepository.addPlace(hikeId, place)
            if (result.isFailure) {
                showSaveRefusalState.value = true
            }
        }
    }

    fun onSelectPlace(placeId: String) {
        selectedIdState.value = placeId
        val place = latestHeldPlaces.firstOrNull { it.id == placeId }
            ?: searchState.value.found.firstOrNull { it.id == placeId }?.place
        val wikidataItem = place?.osm?.wikidata
        if (wikidataItem == null) {
            wikidataState.value = null
            return
        }
        viewModelScope.launch {
            wikidataState.value = wikidataPlaceLookup.cached(wikidataItem)
            wikidataState.value = wikidataPlaceLookup.lookup(wikidataItem)
        }
    }

    fun onCloseCard() {
        selectedIdState.value = null
    }

    fun onDismissSaveRefusal() {
        showSaveRefusalState.value = false
    }

    fun consumeLongPressCoordinate() {
        pendingLongPressState.value = null
    }

    private fun startInitialSearch() {
        val traceCoords = recordingController.trace.value.points.map { it.coordinate }
        val position = originCoordinate ?: traceCoords.lastOrNull()
        val region = PlacesNearbyFrame.region(line = traceCoords, position = position)
        if (region != null) {
            if (originCoordinate == null) originCoordinate = position ?: region.center
            mapClaimCoordinator.requestCameraCommand(
                routeKey = routeKey,
                command = MapCameraCommand.FitBounds(
                    id = mapClaimCoordinator.nextCommandId(),
                    bounds = region
                )
            )
            askArea(PlacesNearbyFrame.areaOf(region), latestShownSymbols)
        } else {
            val fallback = mapCameraTracker.currentSearchableArea()
            if (fallback != null) {
                if (originCoordinate == null) originCoordinate = fallback.center
                askArea(fallback, latestShownSymbols)
            }
        }
    }

    private fun observeSymbolWidening() {
        viewModelScope.launch {
            settingsFlow.map { it.shownSearchPlaceSymbols }.distinctUntilChanged().collect { shown ->
                latestShownSymbols = shown
                val area = lastAreaAsked
                if (area != null && shown.isNotEmpty() && !shown.all(lastSymbolsAsked::contains)) {
                    askArea(area, shown)
                }
            }
        }
    }

    private fun askArea(area: PlaceSearchArea, symbols: Set<TrailPlaceSymbol>) {
        if (!trailPointSource.isAvailable || symbols.isEmpty()) return
        searchJob?.cancel()
        lastAreaAsked = area
        lastSymbolsAsked = symbols
        searchState.update { it.copy(isSearching = true, outage = null) }
        searchJob = viewModelScope.launch {
            when (val result = trailPointSource.places(area, symbols)) {
                is TrailPointSearchResult.Places -> mergeFound(result.places, outage = null)
                is TrailPointSearchResult.Failed -> {
                    val cached = trailPointSource.cachedPlaces(area, TrailPointQuery.MAXIMUM_STORED_RESULTS)
                    mergeFound(cached, outage = result.outage)
                }
            }
        }
    }

    private fun mergeFound(incoming: List<TrailPoint>, outage: PlaceSearchOutage?) {
        searchState.update { current ->
            val merged = ArrayList(current.found)
            val seenOsm = current.found.mapNotNull { it.place.osm?.elementKey }.toMutableSet()
            val seenIds = current.found.map { it.id }.toMutableSet()
            for (place in incoming) {
                val osmKey = place.osm?.elementKey
                val alreadyHave = place.id in seenIds || (osmKey != null && osmKey in seenOsm)
                if (!alreadyHave) {
                    merged.add(TrailPlaceRow(place = place))
                    seenIds.add(place.id)
                    if (osmKey != null) seenOsm.add(osmKey)
                }
            }
            current.copy(
                isSearching = false,
                outage = outage,
                hasAnswered = true,
                found = merged
            )
        }
    }

    private fun resolveSelectedCard(
        selectedId: String?,
        heldRows: List<TrailPlaceRow>,
        foundRows: List<TrailPlaceRow>,
        wikidata: WikidataPlace?
    ): SelectedAroundCard? {
        val pair = selectedId?.let { id ->
            heldRows.firstOrNull { it.id == id }?.let { it to true }
                ?: foundRows.firstOrNull { it.id == id }?.let { candidate ->
                    val heldMatch = heldRows.firstOrNull { held ->
                        TrailPlaceHolding.holds(candidate.place, listOf(held.place))
                    }
                    if (heldMatch != null) heldMatch to true else candidate to false
                }
        }
        return pair?.let { (row, isOnHike) ->
            val item = row.place.osm?.wikidata
            SelectedAroundCard(
                row = row,
                isOnHike = isOnHike,
                wikidataPlace = item?.let { wikidata?.takeIf { loaded -> loaded.item == it } }
            )
        }
    }

    private fun publishMapClaim(
        heldRows: List<TrailPlaceRow>,
        candidates: List<TrailPlaceRow>,
        isSearching: Boolean,
        shownSymbols: Set<TrailPlaceSymbol>,
        canSearchArea: Boolean
    ) {
        trailPlacesOverlayState.setClaim(
            TrailPlacesClaim(
                routeKey = routeKey,
                overlay = MapTrailPlacesOverlay(
                    pins = heldRows.map { row ->
                        MapPlacePin(
                            id = row.id,
                            coordinate = row.place.coordinate,
                            symbol = row.place.symbolKind,
                            title = row.place.name.ifBlank { row.place.symbol ?: DEFAULT_PIN_TITLE }
                        )
                    },
                    candidates = candidates.map { row ->
                        MapPlacePin(
                            id = row.id,
                            coordinate = row.place.coordinate,
                            symbol = row.place.symbolKind,
                            title = row.place.name.ifBlank { row.place.symbol ?: DEFAULT_PIN_TITLE },
                            isCandidate = true
                        )
                    },
                    allowLongPressDrop = true
                ),
                handler = object : TrailPlacesMapInteractionHandler {
                    override fun onTapPlacePin(placeId: String) = onSelectPlace(placeId)
                    override fun onTapCandidatePin(placeId: String) = onSelectPlace(placeId)
                    override fun onLongPressMap(coordinate: GeoCoordinate) {
                        pendingLongPressState.value = coordinate
                    }
                },
                searchAreaPill = MapSearchAreaPillState(
                    isVisible = trailPointSource.isAvailable && shownSymbols.isNotEmpty(),
                    isSearching = isSearching,
                    isEnabled = canSearchArea && !isSearching,
                    onSearchArea = ::onSearchVisibleArea
                )
            )
        )
    }

    private data class NearbySearchState(
        val isSearching: Boolean = false,
        val outage: PlaceSearchOutage? = null,
        val hasAnswered: Boolean = false,
        val found: List<TrailPlaceRow> = emptyList()
    )

    private data class NearbySelectionBundle(
        val selectedId: String?,
        val wikidata: WikidataPlace?,
        val showSaveRefusal: Boolean,
        val longPressCoordinate: GeoCoordinate?
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val INITIAL_CLAIM_DEPTH = 2
        const val DEFAULT_PIN_TITLE = "Place"
    }
}
