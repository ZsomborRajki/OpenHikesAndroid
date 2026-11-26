package app.openhikes.feature.places

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.common.DefaultDispatcher
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.data.WikidataPlaceLookup
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
import app.openhikes.core.model.GeoBoundingBox
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.PlaceSearchOutage
import app.openhikes.core.model.TrailPlaceAroundListing
import app.openhikes.core.model.TrailPlaceCorridorSearch
import app.openhikes.core.model.TrailPlaceHolding
import app.openhikes.core.model.TrailPlaceOrder
import app.openhikes.core.model.TrailPlaceReach
import app.openhikes.core.model.TrailPlaceRow
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.TrailPoint
import app.openhikes.core.model.TrailPointQuery
import app.openhikes.core.model.TrailPointSearchResult
import app.openhikes.core.model.TrailPointSource
import app.openhikes.core.model.UserSettings
import app.openhikes.core.model.WikidataPlace
import app.openhikes.core.ui.navigation.PlacesAroundRoute
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PlacesAroundPhase {
    SEARCHING,
    FOUND,
    FAILED
}

@Immutable
data class SelectedAroundCard(val row: TrailPlaceRow, val isOnHike: Boolean, val wikidataPlace: WikidataPlace? = null)

@Immutable
data class PlacesAroundUiState(
    val phase: PlacesAroundPhase = PlacesAroundPhase.SEARCHING,
    val outage: PlaceSearchOutage? = null,
    val reach: TrailPlaceReach = TrailPlaceReach.AROUND,
    val shownSymbols: Set<TrailPlaceSymbol> = TrailPointQuery.searchableSymbols.toSet(),
    val distanceUnit: DistanceUnit = DistanceUnit.METRIC,
    val listing: TrailPlaceAroundListing = TrailPlaceAroundListing(),
    val selectedCard: SelectedAroundCard? = null,
    val showSaveRefusal: Boolean = false,
    val pendingLongPressCoordinate: GeoCoordinate? = null
)

@Suppress("LongParameterList", "TooManyFunctions")
@HiltViewModel(assistedFactory = PlacesAroundViewModel.Factory::class)
class PlacesAroundViewModel @AssistedInject constructor(
    @Assisted rawHikeId: String,
    private val hikeRepository: HikeRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val trailPointSource: TrailPointSource,
    private val wikidataPlaceLookup: WikidataPlaceLookup,
    private val mapClaimCoordinator: MapClaimCoordinator,
    private val mapCameraTracker: MapCameraTracker,
    private val trailPlacesOverlayState: TrailPlacesOverlayState,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(rawHikeId: String): PlacesAroundViewModel
    }

    val hikeId: HikeId = HikeId(rawHikeId)
    private val routeKey = PlacesAroundRoute(hikeId)

    private val searchState = MutableStateFlow(SearchAccumulator())
    private val selectedIdState = MutableStateFlow<String?>(null)
    private val wikidataState = MutableStateFlow<WikidataPlace?>(null)
    private val showSaveRefusalState = MutableStateFlow(false)
    private val pendingLongPressState = MutableStateFlow<GeoCoordinate?>(null)

    private var searchedReachMeters: Double? = null
    private var searchedSymbols: Set<TrailPlaceSymbol> = emptySet()
    private var askingReachMeters: Double? = null
    private var askingSymbols: Set<TrailPlaceSymbol> = emptySet()
    private var searchJob: Job? = null
    private var hasAutoFitted = false
    private var latestHike: Hike? = null
    private var latestShownSymbols: Set<TrailPlaceSymbol> = TrailPointQuery.searchableSymbols.toSet()

    private val settingsFlow = userSettingsRepository.userSettings.catch { ex ->
        if (ex is IOException) emit(UserSettings()) else throw ex
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val heldPlacesFlow = hikeRepository.observeHike(hikeId)
        .map { hike -> hike?.let { it.places to it.route } }
        .distinctUntilChanged()
        .mapLatest { pair ->
            if (pair == null) {
                HeldPlacesSnapshot()
            } else {
                HeldPlacesSnapshot(
                    places = pair.first,
                    rows = TrailPlaceOrder.ordered(pair.first, pair.second)
                )
            }
        }
        .flowOn(defaultDispatcher)

    private val selectionBundleFlow = combine(
        selectedIdState,
        wikidataState,
        showSaveRefusalState,
        pendingLongPressState
    ) { selectedId, wikidata, refusal, longPress ->
        SelectionBundle(selectedId, wikidata, refusal, longPress)
    }

    val uiState: StateFlow<PlacesAroundUiState> = combine(
        heldPlacesFlow,
        settingsFlow,
        searchState,
        selectionBundleFlow,
        mapCameraTracker.canSearchVisibleArea
    ) { heldSnapshot, settings, search, sel, canSearchArea ->
        val heldRows = heldSnapshot.rows
        val candidates = computeCandidates(
            found = search.found,
            askedAbout = search.askedAbout,
            reach = search.reach,
            shownSymbols = settings.shownSearchPlaceSymbols,
            held = heldSnapshot.places
        )
        val listing = TrailPlaceAroundListing.from(held = heldRows, candidates = candidates)
        val selectedCard = resolveSelectedCard(sel.selectedId, heldRows, search.found, sel.wikidata)
        publishMapClaim(
            heldRows = heldRows,
            candidates = candidates,
            isSearching = search.phase == PlacesAroundPhase.SEARCHING || search.isSearchingArea,
            shownSymbols = settings.shownSearchPlaceSymbols,
            canSearchArea = canSearchArea
        )
        PlacesAroundUiState(
            phase = search.phase,
            outage = search.outage,
            reach = search.reach,
            shownSymbols = settings.shownSearchPlaceSymbols,
            distanceUnit = settings.distanceUnit,
            listing = listing,
            selectedCard = selectedCard,
            showSaveRefusal = sel.showSaveRefusal,
            pendingLongPressCoordinate = sel.longPressCoordinate
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = PlacesAroundUiState()
    )

    init {
        mapClaimCoordinator.registerClaim(
            MapClaim(routeKey = routeKey, depth = INITIAL_CLAIM_DEPTH, selectedHikeId = hikeId)
        )
        observeSearchTriggers()
    }

    override fun onCleared() {
        searchJob?.cancel()
        trailPlacesOverlayState.clearClaim(routeKey)
        mapClaimCoordinator.releaseClaim(routeKey)
    }

    fun onSelectReach(reach: TrailPlaceReach) {
        if (searchState.value.reach == reach) return
        searchState.update { it.copy(reach = reach) }
        searchIfNeeded()
    }

    fun onToggleSymbol(symbol: TrailPlaceSymbol, visible: Boolean) {
        viewModelScope.launch {
            userSettingsRepository.setPlaceSymbolVisible(symbol, visible)
        }
    }

    fun onRetrySearch() {
        val hike = latestHike ?: return
        runCorridorSearch(hike, latestShownSymbols)
    }

    fun onSearchVisibleArea() {
        val area = mapCameraTracker.currentSearchableArea() ?: return
        val symbols = latestShownSymbols
        if (!trailPointSource.isAvailable || symbols.isEmpty()) return
        searchState.update { it.copy(isSearchingArea = true) }
        viewModelScope.launch {
            val result = trailPointSource.places(area, symbols)
            val route = latestHike?.route.orEmpty()
            when (result) {
                is TrailPointSearchResult.Places -> {
                    val ordered = withContext(defaultDispatcher) {
                        TrailPlaceOrder.ordered(result.places, route)
                    }
                    mergeFoundRows(ordered, markAsAskedAbout = true, outage = null)
                }
                is TrailPointSearchResult.Failed -> {
                    val cached = trailPointSource.cachedPlaces(area, TrailPointQuery.MAXIMUM_STORED_RESULTS)
                    val ordered = withContext(defaultDispatcher) {
                        TrailPlaceOrder.ordered(cached, route)
                    }
                    mergeFoundRows(ordered, markAsAskedAbout = true, outage = result.outage)
                }
            }
            searchState.update { it.copy(isSearchingArea = false) }
        }
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
        val row = latestHike?.places?.firstOrNull { it.id == placeId }
            ?: searchState.value.found.firstOrNull { it.id == placeId }?.place
        val wikidataItem = row?.osm?.wikidata
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

    private fun observeSearchTriggers() {
        viewModelScope.launch {
            combine(
                hikeRepository.observeHike(hikeId),
                settingsFlow.map { it.shownSearchPlaceSymbols }.distinctUntilChanged()
            ) { hike, shown -> hike to shown }.collect { (hike, shown) ->
                latestHike = hike
                latestShownSymbols = shown
                if (hike != null && !hasAutoFitted) {
                    hasAutoFitted = true
                    GeoBoundingBox.from(hike.route.map { it.coordinate })?.let { bounds ->
                        mapClaimCoordinator.requestCameraCommand(
                            routeKey = routeKey,
                            command = MapCameraCommand.FitBounds(
                                id = mapClaimCoordinator.nextCommandId(),
                                bounds = bounds
                            )
                        )
                    }
                }
                searchIfNeeded()
            }
        }
    }

    private fun searchIfNeeded() {
        val hike = latestHike ?: return
        val symbols = latestShownSymbols
        val cannotSearch = hike.route.size <= 1 || symbols.isEmpty() || !trailPointSource.isAvailable
        if (cannotSearch) {
            settleSkippedSearch()
            return
        }
        val desiredReach = searchState.value.reach.meters
        val alreadyAsking = askingReachMeters?.let { desiredReach <= it && symbols.all(askingSymbols::contains) }
        val alreadySearched = searchedReachMeters?.let { desiredReach <= it && symbols.all(searchedSymbols::contains) }
        if (alreadyAsking != true && alreadySearched != true) {
            runCorridorSearch(hike, symbols)
        }
    }

    private fun settleSkippedSearch() {
        searchJob?.cancel()
        askingReachMeters = null
        askingSymbols = emptySet()
        searchState.update { current ->
            if (current.phase == PlacesAroundPhase.SEARCHING) {
                current.copy(phase = PlacesAroundPhase.FOUND, outage = null)
            } else {
                current
            }
        }
    }

    private fun runCorridorSearch(hike: Hike, symbols: Set<TrailPlaceSymbol>) {
        searchJob?.cancel()
        val askedReach = maxOf(searchState.value.reach.meters, searchedReachMeters ?: 0.0)
        askingReachMeters = askedReach
        askingSymbols = symbols
        searchState.update { it.copy(phase = PlacesAroundPhase.SEARCHING) }
        searchJob = viewModelScope.launch {
            val outcome = withContext(defaultDispatcher) {
                TrailPlaceCorridorSearch.search(
                    route = hike.route,
                    excluding = emptyList(),
                    source = trailPointSource,
                    symbols = symbols,
                    reaching = askedReach
                )
            }
            askingReachMeters = null
            askingSymbols = emptySet()
            mergeFoundRows(outcome.rows, markAsAskedAbout = false, outage = outcome.outage)
            if (outcome.outage == null) {
                searchedReachMeters = askedReach
                searchedSymbols = symbols
            }
        }
    }

    private fun mergeFoundRows(incoming: List<TrailPlaceRow>, markAsAskedAbout: Boolean, outage: PlaceSearchOutage?) {
        searchState.update { current ->
            val merged = ArrayList(current.found)
            val existingById = current.found.associateBy { it.id }.toMutableMap()
            val existingByOsm = current.found.mapNotNull { row ->
                row.place.osm?.elementKey?.let { it to row.id }
            }.toMap().toMutableMap()
            val askedIds = current.askedAbout.toMutableSet()

            for (row in incoming) {
                val osmKey = row.place.osm?.elementKey
                val matchedId = existingById[row.id]?.id ?: osmKey?.let { existingByOsm[it] }
                if (matchedId == null) {
                    merged.add(row)
                    existingById[row.id] = row
                    if (osmKey != null) existingByOsm[osmKey] = row.id
                    if (markAsAskedAbout) askedIds.add(row.id)
                } else if (markAsAskedAbout) {
                    askedIds.add(matchedId)
                }
            }
            val nextPhase = if (outage != null && merged.isEmpty()) {
                PlacesAroundPhase.FAILED
            } else {
                PlacesAroundPhase.FOUND
            }
            current.copy(
                phase = nextPhase,
                outage = outage,
                found = merged,
                askedAbout = askedIds
            )
        }
    }

    private fun computeCandidates(
        found: List<TrailPlaceRow>,
        askedAbout: Set<String>,
        reach: TrailPlaceReach,
        shownSymbols: Set<TrailPlaceSymbol>,
        held: List<TrailPoint>
    ): List<TrailPlaceRow> {
        val within = found.filter { row ->
            val kindOk = row.place.symbolKind?.let { it in shownSymbols } ?: true
            val off = row.offRouteMeters
            val nearOk = off != null && off <= reach.meters
            kindOk && (nearOk || row.id in askedAbout)
        }
        val unheldIds = TrailPlaceHolding.unheld(within.map { it.place }, held).map { it.id }.toSet()
        return within.filter { it.id in unheldIds }
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
        val pins = heldRows.map { row ->
            MapPlacePin(
                id = row.id,
                coordinate = row.place.coordinate,
                symbol = row.place.symbolKind,
                title = row.place.name.ifBlank { row.place.symbol ?: DEFAULT_PIN_TITLE }
            )
        }
        val candidatePins = candidates.map { row ->
            MapPlacePin(
                id = row.id,
                coordinate = row.place.coordinate,
                symbol = row.place.symbolKind,
                title = row.place.name.ifBlank { row.place.symbol ?: DEFAULT_PIN_TITLE },
                isCandidate = true
            )
        }
        trailPlacesOverlayState.setClaim(
            TrailPlacesClaim(
                routeKey = routeKey,
                overlay = MapTrailPlacesOverlay(
                    pins = pins,
                    candidates = candidatePins,
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

    private data class HeldPlacesSnapshot(
        val places: List<TrailPoint> = emptyList(),
        val rows: List<TrailPlaceRow> = emptyList()
    )

    private data class SearchAccumulator(
        val phase: PlacesAroundPhase = PlacesAroundPhase.SEARCHING,
        val outage: PlaceSearchOutage? = null,
        val reach: TrailPlaceReach = TrailPlaceReach.AROUND,
        val found: List<TrailPlaceRow> = emptyList(),
        val askedAbout: Set<String> = emptySet(),
        val isSearchingArea: Boolean = false
    )

    private data class SelectionBundle(
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
