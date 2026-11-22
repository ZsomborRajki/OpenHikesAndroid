package app.openhikes.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openhikes.R
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.ui.testing.OpenHikesTestTags

private val HorizontalPadding = 16.dp
private val SearchChromeGap = 10.dp
private val ContentSpacing = 16.dp
private val CardCornerRadius = 24.dp
private val CardPadding = 20.dp
private val EmptyIconSize = 28.dp
private val TouchTargetMinSize = 48.dp

/**
 * Root map-sheet / side-panel screen providing trail search, settings entry, and library status.
 */
@Composable
fun MapHomeScreen(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = HorizontalPadding)
    ) {
        SearchAndSettingsRow(
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            onClearSearch = onClearSearch,
            onSearchFocusChanged = onSearchFocusChanged,
            onOpenSettings = onOpenSettings
        )
        Spacer(modifier = Modifier.height(ContentSpacing))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(ContentSpacing)
        ) {
            if (searchQuery.isNotBlank()) {
                SearchEmptyResultsCard(query = searchQuery, onClearSearch = onClearSearch)
            } else {
                EmptyLibraryCard()
            }
        }
    }
}

@Composable
private fun SearchAndSettingsRow(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SearchChromeGap)
    ) {
        SearchPillField(
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            onClearSearch = onClearSearch,
            onSearchFocusChanged = onSearchFocusChanged,
            modifier = Modifier.weight(1f)
        )
        FilledTonalIconButton(
            onClick = onOpenSettings,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            modifier = Modifier
                .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
                .testTag(OpenHikesTestTags.SETTINGS_BUTTON)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Settings),
                contentDescription = stringResource(R.string.map_settings_content_description)
            )
        }
    }
}

@Composable
private fun SearchPillField(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSearchFocusChanged: (Boolean) -> Unit,
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
                    text = stringResource(R.string.map_search_placeholder),
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
                    IconButton(
                        onClick = onClearSearch,
                        modifier = Modifier
                            .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
                            .testTag(OpenHikesTestTags.CLEAR_SEARCH_BUTTON)
                    ) {
                        Icon(
                            painter = painterResource(OpenHikesIcons.Close),
                            contentDescription = stringResource(R.string.map_search_clear_content_description)
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
                .onFocusChanged { focusState ->
                    onSearchFocusChanged(focusState.isFocused)
                }
                .testTag(OpenHikesTestTags.MAP_SEARCH)
        )
    }
}

@Composable
private fun EmptyLibraryCard() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(CardCornerRadius),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(SearchChromeGap)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Landscape),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(EmptyIconSize)
            )
            Text(
                text = stringResource(R.string.map_empty_library_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.map_empty_library_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SearchEmptyResultsCard(query: String, onClearSearch: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(CardCornerRadius),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(SearchChromeGap)
        ) {
            Text(
                text = stringResource(R.string.map_search_no_results_title, query.trim()),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.map_search_no_results_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(
                onClick = onClearSearch,
                modifier = Modifier.defaultMinSize(minHeight = TouchTargetMinSize)
            ) {
                Text(text = stringResource(R.string.map_search_clear_button))
            }
        }
    }
}
