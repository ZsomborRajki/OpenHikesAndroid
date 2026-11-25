package app.openhikes.feature.offlinemaps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.common.DefaultDispatcher
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.data.OfflineCountryRepository
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.location.LocationFeed
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapClaim
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.toMapCountryOutline
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.MapProviderKeys
import app.openhikes.core.model.MapStyleCatalog
import app.openhikes.core.model.MapStyleId
import app.openhikes.core.model.OfflineCountriesState
import app.openhikes.core.model.OfflineCountryDirectoryBuilder
import app.openhikes.core.model.OfflineCountryGeometry
import app.openhikes.core.model.OfflineCountrySort
import app.openhikes.core.model.UserSettings
import app.openhikes.core.ui.navigation.OfflineMapsRoute
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Coordinates Offline Maps catalog listing, search, sorting, Extra Detail toggle, map country
 * outlines, map tap selection, and download actions.
 */
@HiltViewModel(assistedFactory = OfflineMapsViewModel.Factory::class)
class OfflineMapsViewModel @AssistedInject constructor(
    @Assisted private val overMap: Boolean,
    private val offlineCountryRepository: OfflineCountryRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val mapClaimCoordinator: MapClaimCoordinator,
    mapProviderKeys: MapProviderKeys,
    locationFeed: LocationFeed,
    launchConfigHolder: LaunchConfigHolder,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(overMap: Boolean): OfflineMapsViewModel
    }

    private val routeKey = OfflineMapsRoute(overMap = overMap)
    private val revealTickCounter = AtomicLong(0L)

    /**
     * Captured once when the screen opens so live GPS drift never reorders rows while browsing.
     */
    private val initialUserLocation: GeoCoordinate? = resolveInitialLocation(locationFeed, launchConfigHolder)
    private val availableSorts: List<OfflineCountrySort> = if (initialUserLocation != null) {
        listOf(OfflineCountrySort.NEAREST, OfflineCountrySort.NAME, OfflineCountrySort.LARGEST)
    } else {
        listOf(OfflineCountrySort.NAME, OfflineCountrySort.LARGEST)
    }

    private val sortState = MutableStateFlow(availableSorts.first())
    private val searchQueryState = MutableStateFlow("")
    private val selectedCountryIdState = MutableStateFlow<String?>(null)
    private val expandedGroupsState = MutableStateFlow<Set<String>>(emptySet())
    private val scrollRevealState = MutableStateFlow<Pair<String?, Long>>(null to 0L)

    private val controlsState = combine(
        sortState,
        searchQueryState,
        selectedCountryIdState,
        expandedGroupsState,
        scrollRevealState
    ) { sort, query, selectedId, expanded, reveal ->
        OfflineControlsSnapshot(
            sort = sort,
            searchQuery = query,
            selectedCountryId = selectedId,
            expandedGroups = expanded,
            scrollRevealCountryId = reveal.first,
            scrollRevealTick = reveal.second
        )
    }

    val uiState: StateFlow<OfflineMapsUiState> = combine(
        offlineCountryRepository.state,
        userSettingsRepository.userSettings.catch { exception ->
            if (exception is IOException) emit(UserSettings()) else throw exception
        },
        controlsState
    ) { repoState, settings, controls ->
        buildOfflineMapsUiState(
            repoState = repoState,
            settings = settings,
            mapProviderKeys = mapProviderKeys,
            controls = controls,
            initialUserLocation = initialUserLocation,
            availableSorts = availableSorts
        )
    }
        .flowOn(defaultDispatcher)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = OfflineMapsUiState(
                sort = availableSorts.first(),
                availableSorts = availableSorts
            )
        )

    init {
        viewModelScope.launch {
            offlineCountryRepository.refreshCatalog()
        }
        if (overMap) {
            startMapCoordination()
        }
    }

    override fun onCleared() {
        if (overMap) {
            mapClaimCoordinator.releaseClaim(routeKey)
        }
    }

    fun onSearchQueryChange(query: String) {
        searchQueryState.value = query
    }

    fun onSortChange(sort: OfflineCountrySort) {
        if (sort in availableSorts) {
            sortState.value = sort
        }
    }

    fun onToggleGroup(groupId: String) {
        expandedGroupsState.update { current ->
            if (groupId in current) current - groupId else current + groupId
        }
    }

    fun onSelectCountry(countryId: String) {
        selectedCountryIdState.value = countryId
        if (!overMap) return
        val country = offlineCountryRepository.state.value.listedCountries
            .firstOrNull { it.id == countryId }
            ?: return
        val bounds = OfflineCountryGeometry.framingBoundingBox(country)
        mapClaimCoordinator.requestCameraCommand(
            routeKey = routeKey,
            command = MapCameraCommand.FitBounds(
                id = mapClaimCoordinator.nextCommandId(),
                bounds = bounds
            )
        )
    }

    fun onSetIncludesDetail(enabled: Boolean) {
        viewModelScope.launch {
            offlineCountryRepository.setIncludesDetail(enabled)
        }
    }

    fun onUseOpenHikesMap() {
        viewModelScope.launch {
            runCatching { userSettingsRepository.setMapStyleId(MapStyleId.OPENHIKES) }
        }
    }

    fun onDownload(countryId: String) {
        viewModelScope.launch {
            offlineCountryRepository.downloadCountry(countryId)
        }
    }

    fun onRetry(countryId: String) {
        viewModelScope.launch {
            offlineCountryRepository.retryDownload(countryId)
        }
    }

    fun onRepair(countryId: String) {
        viewModelScope.launch {
            offlineCountryRepository.repairCountry(countryId)
        }
    }

    fun onCancel(countryId: String) {
        viewModelScope.launch {
            offlineCountryRepository.cancelDownload(countryId)
        }
    }

    fun onDelete(countryId: String) {
        viewModelScope.launch {
            offlineCountryRepository.deleteCountry(countryId)
        }
    }

    fun onRefresh() {
        viewModelScope.launch {
            offlineCountryRepository.refreshCatalog()
        }
    }

    private fun startMapCoordination() {
        viewModelScope.launch {
            combine(offlineCountryRepository.state, selectedCountryIdState) { repoState, selectedId ->
                repoState.listedCountries.map { country ->
                    country.toMapCountryOutline(
                        isInstalled = repoState.isDrawn(country.id),
                        isSelected = country.id == selectedId
                    )
                }
            }.collect { outlines ->
                mapClaimCoordinator.registerClaim(
                    MapClaim(
                        routeKey = routeKey,
                        depth = INITIAL_CLAIM_DEPTH,
                        countryOutlines = outlines
                    )
                )
            }
        }
        viewModelScope.launch {
            mapClaimCoordinator.pendingMapTap.collect { event ->
                val tap = event ?: return@collect
                if (mapClaimCoordinator.activeClaim.value?.routeKey != routeKey) return@collect
                mapClaimCoordinator.consumeMapTap(tap.id)
                val match = offlineCountryRepository.state.value.listedCountries.firstOrNull { country ->
                    country.contains(longitude = tap.coordinate.longitude, latitude = tap.coordinate.latitude)
                } ?: return@collect
                selectedCountryIdState.value = match.id
                val currentQuery = searchQueryState.value
                if (currentQuery.isNotBlank()) {
                    val visibleInSearch = OfflineCountryDirectoryBuilder
                        .search(offlineCountryRepository.state.value, currentQuery)
                        .any { it.country.id == match.id }
                    if (!visibleInSearch) {
                        searchQueryState.value = ""
                    }
                }
                match.group?.let { parent ->
                    expandedGroupsState.update { it + parent }
                }
                scrollRevealState.value = match.id to revealTickCounter.incrementAndGet()
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val INITIAL_CLAIM_DEPTH = 1
    }
}

private data class OfflineControlsSnapshot(
    val sort: OfflineCountrySort,
    val searchQuery: String,
    val selectedCountryId: String?,
    val expandedGroups: Set<String>,
    val scrollRevealCountryId: String?,
    val scrollRevealTick: Long
)

private fun buildOfflineMapsUiState(
    repoState: OfflineCountriesState,
    settings: UserSettings,
    mapProviderKeys: MapProviderKeys,
    controls: OfflineControlsSnapshot,
    initialUserLocation: GeoCoordinate?,
    availableSorts: List<OfflineCountrySort>
): OfflineMapsUiState {
    val directory = OfflineCountryDirectoryBuilder.build(
        state = repoState,
        sort = controls.sort,
        userLocation = initialUserLocation
    )
    val searchResults = if (controls.searchQuery.isNotBlank()) {
        OfflineCountryDirectoryBuilder.search(repoState, controls.searchQuery)
    } else {
        emptyList()
    }
    val activeStyleId = MapStyleCatalog.renderable(settings.mapStyleId, mapProviderKeys).id
    return OfflineMapsUiState(
        isAvailable = repoState.isAvailable,
        isPrepared = repoState.isPrepared,
        listPhase = repoState.listPhase,
        sort = controls.sort,
        availableSorts = availableSorts,
        searchQuery = controls.searchQuery,
        includesDetail = repoState.includesDetail,
        showUseOpenHikesBanner = activeStyleId != MapStyleId.OPENHIKES,
        activeMapStyleId = activeStyleId,
        selectedCountryId = controls.selectedCountryId,
        expandedGroups = controls.expandedGroups,
        unfinished = directory.unfinished,
        downloaded = directory.downloaded,
        sections = directory.sections,
        searchResults = searchResults,
        installed = repoState.installed,
        downloads = repoState.downloads,
        unopenable = repoState.unopenable,
        installedByteCount = repoState.installedByteCount,
        scrollRevealCountryId = controls.scrollRevealCountryId,
        scrollRevealTick = controls.scrollRevealTick
    )
}

private fun resolveInitialLocation(locationFeed: LocationFeed, launchConfigHolder: LaunchConfigHolder): GeoCoordinate? {
    val config = launchConfigHolder.current.value
    return if (config.enableSimulatedLocation && config.mapStart != null) {
        config.mapStart
    } else {
        locationFeed.lastKnownCoordinate()
    }
}
