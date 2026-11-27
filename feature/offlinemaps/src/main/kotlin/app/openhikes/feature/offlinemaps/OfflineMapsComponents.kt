package app.openhikes.feature.offlinemaps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.OfflineCountrySort
import app.openhikes.core.model.OfflineDirectoryItem
import app.openhikes.core.ui.format.mapStyleDisplayName
import app.openhikes.core.ui.testing.OpenHikesTestTags

private val CardCornerRadius = 22.dp
private val TouchTargetMinSize = 48.dp
private val CardPadding = 16.dp
private val IconSize = 22.dp
private val GroupRowMinHeight = 56.dp

@Composable
internal fun OfflineMapsTopBar(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
                .testTag(OpenHikesTestTags.OFFLINE_MAPS_BACK)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.ArrowBack),
                contentDescription = stringResource(R.string.offline_maps_back_description)
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.offline_maps_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }
        )
    }
}

@Composable
internal fun OfflineMapsSearchAndSortBar(
    searchQuery: String,
    sort: OfflineCountrySort,
    availableSorts: List<OfflineCountrySort>,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit,
    onSortChange: (OfflineCountrySort) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchFocused by remember { mutableStateOf(false) }
    val showSort = !searchFocused && searchQuery.isEmpty()

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OfflineMapsSearchField(
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            onClearSearch = onClearSearch,
            onFocusChanged = { focused ->
                searchFocused = focused
                onSearchFocusChanged(focused)
            },
            modifier = Modifier.weight(1f)
        )
        if (showSort) {
            SortMenuCapsule(
                selected = sort,
                options = availableSorts,
                onSelect = onSortChange
            )
        }
    }
}

@Composable
private fun OfflineMapsSearchField(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = CircleShape,
        modifier = modifier
    ) {
        TextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            singleLine = true,
            placeholder = {
                Text(
                    text = stringResource(R.string.offline_maps_search_placeholder),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(OpenHikesIcons.Search),
                    contentDescription = null
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    val focusManager = LocalFocusManager.current
                    IconButton(
                        onClick = {
                            focusManager.clearFocus(force = true)
                            onClearSearch()
                        },
                        modifier = Modifier
                            .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
                            .testTag(OpenHikesTestTags.OFFLINE_MAPS_CLEAR_SEARCH)
                    ) {
                        Icon(
                            painter = painterResource(OpenHikesIcons.Close),
                            contentDescription = stringResource(R.string.offline_maps_clear_search_description)
                        )
                    }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onFocusChanged(it.isFocused) }
                .testTag(OpenHikesTestTags.OFFLINE_MAPS_SEARCH)
        )
    }
}

@Composable
private fun SortMenuCapsule(
    selected: OfflineCountrySort,
    options: List<OfflineCountrySort>,
    onSelect: (OfflineCountrySort) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = sortLabel(selected)
    val sortDescription = stringResource(R.string.offline_maps_sort_description)

    Box {
        FilledTonalButton(
            onClick = { expanded = true },
            modifier = Modifier
                .defaultMinSize(minHeight = TouchTargetMinSize)
                .semantics {
                    contentDescription = sortDescription
                    stateDescription = selectedLabel
                }
                .testTag(OpenHikesTestTags.OFFLINE_MAPS_ORDER_BUTTON)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Sort),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize)
            )
            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
            Text(text = selectedLabel, maxLines = 1)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text = sortLabel(option)) },
                    trailingIcon = {
                        if (option == selected) {
                            Icon(
                                painter = painterResource(OpenHikesIcons.Check),
                                contentDescription = null
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}

@Composable
internal fun sortLabel(sort: OfflineCountrySort): String = when (sort) {
    OfflineCountrySort.NEAREST -> stringResource(R.string.offline_maps_sort_nearest)
    OfflineCountrySort.NAME -> stringResource(R.string.offline_maps_sort_name)
    OfflineCountrySort.LARGEST -> stringResource(R.string.offline_maps_sort_largest)
}

@Composable
internal fun OfflineMapsHeadCard(
    includesDetail: Boolean,
    includesElevation: Boolean,
    showUseOpenHikesBanner: Boolean,
    activeMapStyleId: String,
    onSetIncludesDetail: (Boolean) -> Unit,
    onSetIncludesElevation: (Boolean) -> Unit,
    onUseOpenHikesMap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.offline_maps_head_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (showUseOpenHikesBanner) {
            UseOpenHikesMapBanner(
                activeMapStyleId = activeMapStyleId,
                onClick = onUseOpenHikesMap
            )
        }
        OfflineMapsToggleCard(
            title = stringResource(R.string.offline_maps_extra_detail_title),
            summary = stringResource(R.string.offline_maps_extra_detail_summary),
            checked = includesDetail,
            testTag = OpenHikesTestTags.OFFLINE_MAPS_EXTRA_DETAIL,
            onCheckedChange = onSetIncludesDetail
        )
        OfflineMapsToggleCard(
            title = stringResource(R.string.offline_maps_elevation_data_title),
            summary = stringResource(R.string.offline_maps_elevation_data_summary),
            checked = includesElevation,
            testTag = OpenHikesTestTags.OFFLINE_MAPS_ELEVATION_DATA,
            onCheckedChange = onSetIncludesElevation
        )
    }
}

@Composable
private fun OfflineMapsToggleCard(
    title: String,
    summary: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(CardCornerRadius),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = GroupRowMinHeight)
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = onCheckedChange
                )
                .padding(horizontal = CardPadding, vertical = 12.dp)
                .testTag(testTag),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
private fun UseOpenHikesMapBanner(activeMapStyleId: String, onClick: () -> Unit) {
    val styleName = mapStyleDisplayName(activeMapStyleId)
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(CardCornerRadius),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = GroupRowMinHeight)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = CardPadding, vertical = 12.dp)
                .testTag(OpenHikesTestTags.OFFLINE_MAPS_USE_OPENHIKES_MAP),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Warning),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(IconSize)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.offline_maps_using_other_map, styleName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = stringResource(R.string.offline_maps_use_openhikes_map),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
internal fun OfflineCountryGroupHeader(
    group: OfflineDirectoryItem.GroupItem,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val toggleLabel = if (isExpanded) {
        stringResource(R.string.offline_maps_collapse_group_description, group.groupName)
    } else {
        stringResource(R.string.offline_maps_expand_group_description, group.groupName)
    }
    val expandedStateText = if (isExpanded) {
        stringResource(R.string.offline_maps_state_expanded)
    } else {
        stringResource(R.string.offline_maps_state_collapsed)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(CardCornerRadius),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = GroupRowMinHeight)
                .clickable(role = Role.Button, onClickLabel = toggleLabel, onClick = onToggle)
                .semantics(mergeDescendants = true) { stateDescription = expandedStateText }
                .padding(horizontal = CardPadding, vertical = 10.dp)
                .testTag(OpenHikesTestTags.offlineCountryGroup(group.groupId)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.groupName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.offline_maps_region_count,
                        group.regions.size,
                        group.regions.size
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                painter = painterResource(
                    if (isExpanded) OpenHikesIcons.ExpandLess else OpenHikesIcons.ExpandMore
                ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun OfflineMapsStatusCard(
    iconRes: Int,
    title: String,
    body: String,
    testTag: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    actionTag: String? = null,
    onAction: (() -> Unit)? = null
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(CardCornerRadius),
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (actionLabel != null && onAction != null) {
                FilledTonalButton(
                    onClick = onAction,
                    modifier = Modifier
                        .defaultMinSize(minHeight = TouchTargetMinSize)
                        .then(if (actionTag != null) Modifier.testTag(actionTag) else Modifier)
                ) {
                    Text(text = actionLabel)
                }
            }
        }
    }
}
