package app.openhikes.feature.offlinemaps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.OfflineCountryEntry
import app.openhikes.core.model.OfflineCountrySort
import app.openhikes.core.model.OfflineDirectoryItem
import app.openhikes.core.model.OfflineDirectorySection
import app.openhikes.core.model.OfflineDirectorySectionKind
import app.openhikes.core.model.OfflineListPhase
import app.openhikes.core.ui.testing.OpenHikesTestTags

private val HorizontalPadding = 16.dp
private val ItemSpacing = 10.dp
private val ListBottomPadding = 24.dp

/**
 * Stateful Offline Maps route scoped to [overMap] via assisted Hilt ViewModel injection.
 */
@Composable
fun OfflineMapsRoute(
    overMap: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OfflineMapsViewModel = hiltViewModel<OfflineMapsViewModel, OfflineMapsViewModel.Factory>(
        key = "offline-maps-$overMap"
    ) { factory ->
        factory.create(overMap)
    }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    OfflineMapsScreen(
        uiState = uiState,
        onBack = onBack,
        onSearchQueryChange = viewModel::onSearchQueryChange,
        onClearSearch = { viewModel.onSearchQueryChange("") },
        onSortChange = viewModel::onSortChange,
        onToggleGroup = viewModel::onToggleGroup,
        onSelectCountry = { countryId ->
            focusManager.clearFocus(force = true)
            viewModel.onSelectCountry(countryId)
        },
        onSetIncludesDetail = viewModel::onSetIncludesDetail,
        onSetIncludesElevation = viewModel::onSetIncludesElevation,
        onUseOpenHikesMap = viewModel::onUseOpenHikesMap,
        onDownload = viewModel::onDownload,
        onRetry = viewModel::onRetry,
        onRepair = viewModel::onRepair,
        onCancel = viewModel::onCancel,
        onDelete = viewModel::onDelete,
        onExtend = viewModel::onExtend,
        onTrim = viewModel::onTrim,
        onRefresh = viewModel::onRefresh,
        modifier = modifier
    )
}

/**
 * Stateless Material 3 Expressive Offline Maps screen used by runtime, previews, and tests.
 */
@Composable
fun OfflineMapsScreen(
    uiState: OfflineMapsUiState,
    onBack: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSortChange: (OfflineCountrySort) -> Unit,
    onToggleGroup: (String) -> Unit,
    onSelectCountry: (String) -> Unit,
    onSetIncludesDetail: (Boolean) -> Unit,
    onSetIncludesElevation: (Boolean) -> Unit,
    onUseOpenHikesMap: () -> Unit,
    onDownload: (String) -> Unit,
    onRetry: (String) -> Unit,
    onRepair: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDelete: (String) -> Unit,
    onExtend: (String) -> Unit,
    onTrim: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    onSearchFocusChanged: (Boolean) -> Unit = {}
) {
    Column(modifier = modifier.fillMaxSize()) {
        OfflineMapsTopBar(onBack = onBack)

        if (uiState.isAvailable && (uiState.hasAnyEntries || uiState.isSearching)) {
            OfflineMapsSearchAndSortBar(
                searchQuery = uiState.searchQuery,
                sort = uiState.sort,
                availableSorts = uiState.availableSorts,
                onSearchQueryChange = onSearchQueryChange,
                onClearSearch = onClearSearch,
                onSearchFocusChanged = onSearchFocusChanged,
                onSortChange = onSortChange,
                modifier = Modifier.padding(horizontal = HorizontalPadding, vertical = 4.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        OfflineMapsBody(
            uiState = uiState,
            onToggleGroup = onToggleGroup,
            onSelectCountry = onSelectCountry,
            onSetIncludesDetail = onSetIncludesDetail,
            onSetIncludesElevation = onSetIncludesElevation,
            onUseOpenHikesMap = onUseOpenHikesMap,
            onDownload = onDownload,
            onRetry = onRetry,
            onRepair = onRepair,
            onCancel = onCancel,
            onDelete = onDelete,
            onExtend = onExtend,
            onTrim = onTrim,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun OfflineMapsBody(
    uiState: OfflineMapsUiState,
    onToggleGroup: (String) -> Unit,
    onSelectCountry: (String) -> Unit,
    onSetIncludesDetail: (Boolean) -> Unit,
    onSetIncludesElevation: (Boolean) -> Unit,
    onUseOpenHikesMap: () -> Unit,
    onDownload: (String) -> Unit,
    onRetry: (String) -> Unit,
    onRepair: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDelete: (String) -> Unit,
    onExtend: (String) -> Unit,
    onTrim: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        !uiState.isAvailable -> Box(
            modifier = modifier
                .fillMaxSize()
                .padding(HorizontalPadding)
        ) {
            OfflineMapsStatusCard(
                iconRes = OpenHikesIcons.Map,
                title = stringResource(R.string.offline_maps_unavailable_title),
                body = stringResource(R.string.offline_maps_unavailable_body),
                testTag = OpenHikesTestTags.OFFLINE_MAPS_UNAVAILABLE
            )
        }

        !uiState.hasAnyEntries && uiState.listPhase == OfflineListPhase.FAILED -> Box(
            modifier = modifier
                .fillMaxSize()
                .padding(HorizontalPadding)
        ) {
            OfflineMapsStatusCard(
                iconRes = OpenHikesIcons.WifiOff,
                title = stringResource(R.string.offline_maps_error_title),
                body = stringResource(R.string.offline_maps_error_body),
                testTag = OpenHikesTestTags.OFFLINE_MAPS_LOAD_ERROR,
                actionLabel = stringResource(R.string.offline_maps_try_again),
                actionTag = OpenHikesTestTags.OFFLINE_MAPS_TRY_AGAIN,
                onAction = onRefresh
            )
        }

        !uiState.isPrepared || (!uiState.hasAnyEntries && uiState.listPhase != OfflineListPhase.LOADED) -> {
            val loadingDescription = stringResource(R.string.offline_maps_loading_description)
            Box(
                contentAlignment = Alignment.Center,
                modifier = modifier.fillMaxSize()
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = loadingDescription }
                )
            }
        }

        else -> OfflineCountriesLazyList(
            uiState = uiState,
            onToggleGroup = onToggleGroup,
            onSelectCountry = onSelectCountry,
            onSetIncludesDetail = onSetIncludesDetail,
            onSetIncludesElevation = onSetIncludesElevation,
            onUseOpenHikesMap = onUseOpenHikesMap,
            onDownload = onDownload,
            onRetry = onRetry,
            onRepair = onRepair,
            onCancel = onCancel,
            onDelete = onDelete,
            onExtend = onExtend,
            onTrim = onTrim,
            modifier = modifier
        )
    }
}

@Composable
private fun OfflineCountriesLazyList(
    uiState: OfflineMapsUiState,
    onToggleGroup: (String) -> Unit,
    onSelectCountry: (String) -> Unit,
    onSetIncludesDetail: (Boolean) -> Unit,
    onSetIncludesElevation: (Boolean) -> Unit,
    onUseOpenHikesMap: () -> Unit,
    onDownload: (String) -> Unit,
    onRetry: (String) -> Unit,
    onRepair: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDelete: (String) -> Unit,
    onExtend: (String) -> Unit,
    onTrim: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val actions = CountryRowCallbacks(
        onSelect = onSelectCountry,
        onDownload = onDownload,
        onRetry = onRetry,
        onRepair = onRepair,
        onCancel = onCancel,
        onDelete = onDelete,
        onExtend = onExtend,
        onTrim = onTrim
    )

    LaunchedEffect(uiState.sort) {
        listState.scrollToItem(0)
    }
    LaunchedEffect(uiState.scrollRevealTick) {
        val targetIndex = uiState.scrollRevealCountryId?.let { findRevealIndex(uiState, it) }
        if (targetIndex != null) {
            listState.animateScrollToItem(targetIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .testTag(OpenHikesTestTags.OFFLINE_MAPS_SCREEN),
        contentPadding = PaddingValues(
            start = HorizontalPadding,
            end = HorizontalPadding,
            bottom = ListBottomPadding
        ),
        verticalArrangement = Arrangement.spacedBy(ItemSpacing)
    ) {
        if (uiState.isSearching) {
            addSearchItems(uiState = uiState, actions = actions)
        } else {
            addBrowseContent(
                uiState = uiState,
                onToggleGroup = onToggleGroup,
                onSetIncludesDetail = onSetIncludesDetail,
                onSetIncludesElevation = onSetIncludesElevation,
                onUseOpenHikesMap = onUseOpenHikesMap,
                actions = actions
            )
        }
    }
}

private fun LazyListScope.addBrowseContent(
    uiState: OfflineMapsUiState,
    onToggleGroup: (String) -> Unit,
    onSetIncludesDetail: (Boolean) -> Unit,
    onSetIncludesElevation: (Boolean) -> Unit,
    onUseOpenHikesMap: () -> Unit,
    actions: CountryRowCallbacks
) {
    item(key = "head") {
        OfflineMapsHeadCard(
            includesDetail = uiState.includesDetail,
            includesElevation = uiState.includesElevation,
            showUseOpenHikesBanner = uiState.showUseOpenHikesBanner,
            activeMapStyleId = uiState.activeMapStyleId,
            onSetIncludesDetail = onSetIncludesDetail,
            onSetIncludesElevation = onSetIncludesElevation,
            onUseOpenHikesMap = onUseOpenHikesMap
        )
    }
    addActionSections(uiState = uiState, actions = actions)
    uiState.sections.forEach { section ->
        addBrowseSection(
            section = section,
            uiState = uiState,
            onToggleGroup = onToggleGroup,
            actions = actions
        )
    }
    item(key = "footer") {
        Text(
            text = stringResource(R.string.offline_maps_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )
    }
}

private data class CountryRowCallbacks(
    val onSelect: (String) -> Unit,
    val onDownload: (String) -> Unit,
    val onRetry: (String) -> Unit,
    val onRepair: (String) -> Unit,
    val onCancel: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onExtend: (String) -> Unit,
    val onTrim: (String) -> Unit
)

private fun LazyListScope.addSearchItems(uiState: OfflineMapsUiState, actions: CountryRowCallbacks) {
    if (uiState.searchResults.isEmpty()) {
        item(key = "search-empty") {
            OfflineMapsStatusCard(
                iconRes = OpenHikesIcons.Search,
                title = stringResource(R.string.offline_maps_search_empty_title, uiState.searchQuery.trim()),
                body = stringResource(R.string.offline_maps_search_empty_body),
                testTag = OpenHikesTestTags.OFFLINE_MAPS_EMPTY_SEARCH
            )
        }
        return
    }
    items(items = uiState.searchResults, key = { "search-${it.country.id}" }) { entry ->
        BindCountryRow(entry = entry, namesCountry = true, uiState = uiState, actions = actions)
    }
}

private fun LazyListScope.addActionSections(uiState: OfflineMapsUiState, actions: CountryRowCallbacks) {
    if (uiState.unfinished.isNotEmpty()) {
        item(key = "header-unfinished") {
            SectionHeading(title = stringResource(R.string.offline_maps_section_unfinished))
        }
        items(items = uiState.unfinished, key = { "unfinished-${it.country.id}" }) { entry ->
            BindCountryRow(entry = entry, namesCountry = true, uiState = uiState, actions = actions)
        }
    }
    if (uiState.downloaded.isNotEmpty()) {
        item(key = "header-downloaded") {
            SectionHeading(title = stringResource(R.string.offline_maps_section_downloaded))
        }
        items(items = uiState.downloaded, key = { "downloaded-${it.country.id}" }) { entry ->
            BindCountryRow(entry = entry, namesCountry = true, uiState = uiState, actions = actions)
        }
    }
}

private fun LazyListScope.addBrowseSection(
    section: OfflineDirectorySection,
    uiState: OfflineMapsUiState,
    onToggleGroup: (String) -> Unit,
    actions: CountryRowCallbacks
) {
    item(key = "section-${section.id}") {
        val headerTitle = when (section.kind) {
            OfflineDirectorySectionKind.NEARBY -> stringResource(R.string.offline_maps_section_nearby)
            OfflineDirectorySectionKind.ALL_COUNTRIES -> stringResource(R.string.offline_maps_section_all)
            OfflineDirectorySectionKind.LETTER -> section.title
        }
        SectionHeading(title = headerTitle)
    }
    section.items.forEach { item ->
        when (item) {
            is OfflineDirectoryItem.CountryItem -> item(key = item.stableKey) {
                BindCountryRow(
                    entry = item.entry,
                    namesCountry = item.namesCountry,
                    uiState = uiState,
                    actions = actions
                )
            }

            is OfflineDirectoryItem.GroupItem -> {
                val isExpanded = item.groupId in uiState.expandedGroups
                item(key = item.stableKey) {
                    OfflineCountryGroupHeader(
                        group = item,
                        isExpanded = isExpanded,
                        onToggle = { onToggleGroup(item.groupId) }
                    )
                }
                if (isExpanded) {
                    items(items = item.regions, key = { "group-${item.groupId}-${it.country.id}" }) { region ->
                        BindCountryRow(
                            entry = region,
                            namesCountry = false,
                            uiState = uiState,
                            actions = actions,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BindCountryRow(
    entry: OfflineCountryEntry,
    namesCountry: Boolean,
    uiState: OfflineMapsUiState,
    actions: CountryRowCallbacks,
    modifier: Modifier = Modifier
) {
    val id = entry.country.id
    val installedRecord = uiState.installed[id]
    val download = uiState.downloads[id]
    val needsRepair = installedRecord != null && uiState.unopenable[id].orEmpty().isNotEmpty()
    OfflineCountryRow(
        entry = entry,
        namesCountry = namesCountry,
        installedRecord = installedRecord,
        download = download,
        needsRepair = needsRepair,
        isSelected = uiState.selectedCountryId == id,
        onSelect = { actions.onSelect(id) },
        onDownload = { actions.onDownload(id) },
        onRetry = { actions.onRetry(id) },
        onRepair = { actions.onRepair(id) },
        onCancel = { actions.onCancel(id) },
        onDelete = { actions.onDelete(id) },
        additionOffer = uiState.additions[id],
        removalOffer = uiState.removals[id],
        onExtend = { actions.onExtend(id) },
        onTrim = { actions.onTrim(id) },
        modifier = modifier
    )
}

@Composable
private fun SectionHeading(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 4.dp, top = 6.dp)
            .semantics { heading() }
    )
}

private fun findRevealIndex(uiState: OfflineMapsUiState, targetId: String): Int? {
    val itemCountryIds = if (uiState.isSearching) {
        uiState.searchResults.map { it.country.id }
    } else {
        flattenBrowseCountryIds(uiState)
    }
    return itemCountryIds.indexOf(targetId).takeIf { it >= 0 }
}

private fun flattenBrowseCountryIds(uiState: OfflineMapsUiState): List<String?> = buildList {
    add(null) // head
    if (uiState.unfinished.isNotEmpty()) {
        add(null)
        uiState.unfinished.forEach { add(it.country.id) }
    }
    if (uiState.downloaded.isNotEmpty()) {
        add(null)
        uiState.downloaded.forEach { add(it.country.id) }
    }
    uiState.sections.forEach { section ->
        add(null)
        appendSectionCountryIds(section, uiState.expandedGroups, this)
    }
}

private fun appendSectionCountryIds(
    section: OfflineDirectorySection,
    expandedGroups: Set<String>,
    out: MutableList<String?>
) {
    section.items.forEach { item ->
        when (item) {
            is OfflineDirectoryItem.CountryItem -> out.add(item.entry.country.id)
            is OfflineDirectoryItem.GroupItem -> {
                out.add(null)
                if (item.groupId in expandedGroups) {
                    item.regions.forEach { out.add(it.country.id) }
                }
            }
        }
    }
}
