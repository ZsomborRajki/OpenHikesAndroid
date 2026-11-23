package app.openhikes.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.LegalLinks
import app.openhikes.core.model.UserSettings
import app.openhikes.core.ui.format.StorageSizeFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags

private val ScreenHorizontalPadding = 16.dp
private val SectionSpacing = 20.dp
private val SectionHeaderBottomPadding = 8.dp
private val CardCornerRadius = 24.dp
private val RowMinHeight = 56.dp
private val LinkMinHeight = 52.dp
private val RowHorizontalPadding = 16.dp
private val RowVerticalPadding = 12.dp
private val IconSize = 20.dp
private val TouchTargetMinSize = 48.dp

/**
 * Stateful route composable collecting [SettingsViewModel] state for the settings screen.
 */
@Composable
fun SettingsRoute(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenOfflineMaps: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    SettingsScreen(
        uiState = uiState,
        onClose = onClose,
        onDistanceUnitChange = viewModel::onDistanceUnitChange,
        onAutoPauseChange = viewModel::onAutoPauseChange,
        onHikeRemindersChange = viewModel::onHikeRemindersChange,
        onLiveUpdatesChange = viewModel::onLiveUpdatesChange,
        onKeepScreenAwakeChange = viewModel::onKeepScreenAwakeChange,
        onAutoSaveTilesChange = viewModel::onAutoSaveTilesChange,
        onSavePhotosToLibraryChange = viewModel::onSavePhotosToLibraryChange,
        onRetrySave = viewModel::onRetrySave,
        onDismissSaveError = viewModel::onDismissSaveError,
        onOpenOfflineMaps = onOpenOfflineMaps,
        onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
        modifier = modifier
    )
}

/**
 * Stateless Material 3 Expressive settings screen used in runtime, previews, and tests.
 */
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onClose: () -> Unit,
    onDistanceUnitChange: (DistanceUnit) -> Unit,
    onAutoPauseChange: (Boolean) -> Unit,
    onHikeRemindersChange: (Boolean) -> Unit,
    onLiveUpdatesChange: (Boolean) -> Unit,
    onKeepScreenAwakeChange: (Boolean) -> Unit,
    onAutoSaveTilesChange: (Boolean) -> Unit,
    onSavePhotosToLibraryChange: (Boolean) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenOfflineMaps: () -> Unit = {},
    onRetrySave: () -> Unit = {},
    onDismissSaveError: () -> Unit = {}
) {
    Column(modifier = modifier.fillMaxSize()) {
        SettingsTopBar(onClose = onClose)
        if (uiState.saveError != null) {
            SettingsSaveErrorBanner(
                onRetry = onRetrySave,
                onDismiss = onDismissSaveError,
                modifier = Modifier.padding(horizontal = ScreenHorizontalPadding, vertical = RowVerticalPadding)
            )
        }
        SettingsPreferenceList(
            uiState = uiState,
            onDistanceUnitChange = onDistanceUnitChange,
            onAutoPauseChange = onAutoPauseChange,
            onHikeRemindersChange = onHikeRemindersChange,
            onLiveUpdatesChange = onLiveUpdatesChange,
            onKeepScreenAwakeChange = onKeepScreenAwakeChange,
            onAutoSaveTilesChange = onAutoSaveTilesChange,
            onSavePhotosToLibraryChange = onSavePhotosToLibraryChange,
            onOpenOfflineMaps = onOpenOfflineMaps,
            onOpenUrl = onOpenUrl,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SettingsPreferenceList(
    uiState: SettingsUiState,
    onDistanceUnitChange: (DistanceUnit) -> Unit,
    onAutoPauseChange: (Boolean) -> Unit,
    onHikeRemindersChange: (Boolean) -> Unit,
    onLiveUpdatesChange: (Boolean) -> Unit,
    onKeepScreenAwakeChange: (Boolean) -> Unit,
    onAutoSaveTilesChange: (Boolean) -> Unit,
    onSavePhotosToLibraryChange: (Boolean) -> Unit,
    onOpenOfflineMaps: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(OpenHikesTestTags.SETTINGS_SCREEN),
        contentPadding = PaddingValues(
            start = ScreenHorizontalPadding,
            end = ScreenHorizontalPadding,
            bottom = SectionSpacing
        ),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing)
    ) {
        item(key = "units") {
            SettingsUnitsSection(
                selectedUnit = uiState.settings.distanceUnit,
                onDistanceUnitChange = onDistanceUnitChange
            )
        }
        item(key = "recording") {
            SettingsRecordingSection(
                settings = uiState.settings,
                onAutoPauseChange = onAutoPauseChange,
                onHikeRemindersChange = onHikeRemindersChange,
                onLiveUpdatesChange = onLiveUpdatesChange,
                onKeepScreenAwakeChange = onKeepScreenAwakeChange
            )
        }
        item(key = "offline_photos") {
            SettingsOfflineAndPhotosSection(
                settings = uiState.settings,
                offlineInstalledBytes = uiState.offlineInstalledBytes,
                onOpenOfflineMaps = onOpenOfflineMaps,
                onAutoSaveTilesChange = onAutoSaveTilesChange,
                onSavePhotosToLibraryChange = onSavePhotosToLibraryChange
            )
        }
        item(key = "about") {
            SettingsAboutSection(
                appVersion = uiState.appVersion,
                onOpenUrl = onOpenUrl
            )
        }
    }
}

@Composable
private fun SettingsTopBar(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenHorizontalPadding, vertical = RowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }
        )
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
                .testTag(OpenHikesTestTags.SETTINGS_CLOSE)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Close),
                contentDescription = stringResource(R.string.settings_close_content_description)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsUnitsSection(selectedUnit: DistanceUnit, onDistanceUnitChange: (DistanceUnit) -> Unit) {
    val metricInteraction = remember { MutableInteractionSource() }
    val imperialInteraction = remember { MutableInteractionSource() }

    SettingsSection(title = stringResource(R.string.settings_section_units)) {
        ButtonGroup(
            overflowIndicator = {},
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
        ) {
            customItem(
                buttonGroupContent = {
                    UnitToggleButton(
                        checked = selectedUnit == DistanceUnit.METRIC,
                        onCheckedChange = { onDistanceUnitChange(DistanceUnit.METRIC) },
                        shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                        interactionSource = metricInteraction,
                        title = stringResource(R.string.settings_unit_metric),
                        detail = stringResource(R.string.settings_unit_metric_detail),
                        modifier = Modifier
                            .weight(1f)
                            .animateWidth(metricInteraction)
                            .testTag(OpenHikesTestTags.DISTANCE_UNIT_METRIC)
                    )
                },
                menuContent = {}
            )
            customItem(
                buttonGroupContent = {
                    UnitToggleButton(
                        checked = selectedUnit == DistanceUnit.IMPERIAL,
                        onCheckedChange = { onDistanceUnitChange(DistanceUnit.IMPERIAL) },
                        shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                        interactionSource = imperialInteraction,
                        title = stringResource(R.string.settings_unit_imperial),
                        detail = stringResource(R.string.settings_unit_imperial_detail),
                        modifier = Modifier
                            .weight(1f)
                            .animateWidth(imperialInteraction)
                            .testTag(OpenHikesTestTags.DISTANCE_UNIT_IMPERIAL)
                    )
                },
                menuContent = {}
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UnitToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shapes: androidx.compose.material3.ToggleButtonShapes,
    interactionSource: MutableInteractionSource,
    title: String,
    detail: String,
    modifier: Modifier = Modifier
) {
    ToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        shapes = shapes,
        interactionSource = interactionSource,
        modifier = modifier.defaultMinSize(minHeight = TouchTargetMinSize)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ToggleButtonDefaults.IconSpacing)
        ) {
            Text(text = title, style = MaterialTheme.typography.labelLarge)
            Text(text = detail, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SettingsRecordingSection(
    settings: UserSettings,
    onAutoPauseChange: (Boolean) -> Unit,
    onHikeRemindersChange: (Boolean) -> Unit,
    onLiveUpdatesChange: (Boolean) -> Unit,
    onKeepScreenAwakeChange: (Boolean) -> Unit
) {
    SettingsSection(title = stringResource(R.string.settings_section_recording)) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_auto_pause_title),
            summary = stringResource(R.string.settings_auto_pause_summary),
            checked = settings.autoPauseEnabled,
            onCheckedChange = onAutoPauseChange,
            testTag = OpenHikesTestTags.AUTO_PAUSE_TOGGLE
        )
        SectionDivider()
        SettingsSwitchRow(
            title = stringResource(R.string.settings_hike_reminders_title),
            summary = stringResource(R.string.settings_hike_reminders_summary),
            checked = settings.hikeRemindersEnabled,
            onCheckedChange = onHikeRemindersChange,
            testTag = OpenHikesTestTags.HIKE_REMINDERS_TOGGLE
        )
        SectionDivider()
        SettingsSwitchRow(
            title = stringResource(R.string.settings_live_updates_title),
            summary = stringResource(R.string.settings_live_updates_summary),
            checked = settings.liveUpdatesEnabled,
            onCheckedChange = onLiveUpdatesChange,
            testTag = OpenHikesTestTags.LIVE_UPDATES_TOGGLE
        )
        SectionDivider()
        SettingsSwitchRow(
            title = stringResource(R.string.settings_keep_screen_awake_title),
            summary = stringResource(R.string.settings_keep_screen_awake_summary),
            checked = settings.keepScreenAwake,
            onCheckedChange = onKeepScreenAwakeChange,
            testTag = OpenHikesTestTags.KEEP_SCREEN_AWAKE_TOGGLE
        )
    }
}

@Composable
private fun SettingsOfflineAndPhotosSection(
    settings: UserSettings,
    offlineInstalledBytes: Long,
    onOpenOfflineMaps: () -> Unit,
    onAutoSaveTilesChange: (Boolean) -> Unit,
    onSavePhotosToLibraryChange: (Boolean) -> Unit
) {
    val storageText = if (offlineInstalledBytes > 0L) {
        StorageSizeFormatter.formatBytes(offlineInstalledBytes)
    } else {
        stringResource(R.string.settings_offline_maps_empty)
    }
    SettingsSection(title = stringResource(R.string.settings_section_offline_photos)) {
        SettingsNavigationRow(
            title = stringResource(R.string.settings_offline_maps_title),
            value = storageText,
            onClick = onOpenOfflineMaps,
            testTag = OpenHikesTestTags.SETTINGS_OFFLINE_MAPS
        )
        SectionDivider()
        SettingsSwitchRow(
            title = stringResource(R.string.settings_auto_save_tiles_title),
            summary = stringResource(R.string.settings_auto_save_tiles_summary),
            checked = settings.autoSaveTilesEnabled,
            onCheckedChange = onAutoSaveTilesChange,
            testTag = OpenHikesTestTags.AUTO_SAVE_TILES_TOGGLE
        )
        SectionDivider()
        SettingsSwitchRow(
            title = stringResource(R.string.settings_save_photos_title),
            summary = stringResource(R.string.settings_save_photos_summary),
            checked = settings.savePhotosToLibrary,
            onCheckedChange = onSavePhotosToLibraryChange,
            testTag = OpenHikesTestTags.SAVE_PHOTOS_TO_LIBRARY_TOGGLE
        )
    }
}

@Composable
private fun SettingsNavigationRow(title: String, value: String, onClick: () -> Unit, testTag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = RowMinHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Icon(
            painter = painterResource(OpenHikesIcons.ChevronRight),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSize)
        )
    }
}

@Composable
private fun SettingsAboutSection(appVersion: String, onOpenUrl: (String) -> Unit) {
    SettingsSection(
        title = stringResource(R.string.settings_section_about),
        footer = stringResource(R.string.settings_about_footer)
    ) {
        SettingsLinkRow(
            title = stringResource(R.string.settings_privacy_policy),
            onClick = { onOpenUrl(LegalLinks.privacyPolicyUrl()) },
            testTag = OpenHikesTestTags.PRIVACY_POLICY_LINK
        )
        SectionDivider()
        SettingsLinkRow(
            title = stringResource(R.string.settings_terms),
            onClick = { onOpenUrl(LegalLinks.termsUrl()) },
            testTag = OpenHikesTestTags.TERMS_LINK
        )
        SectionDivider()
        SettingsLinkRow(
            title = stringResource(R.string.settings_github),
            onClick = { onOpenUrl(LegalLinks.GITHUB_REPOSITORY_URL) },
            testTag = OpenHikesTestTags.PROJECT_GITHUB_LINK
        )
        SectionDivider()
        SettingsInfoRow(
            label = stringResource(R.string.settings_version_label),
            value = appVersion,
            testTag = OpenHikesTestTags.APP_VERSION_ROW
        )
    }
}

@Composable
private fun SettingsSection(title: String, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = RowHorizontalPadding, bottom = SectionHeaderBottomPadding)
                .semantics { heading() }
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(CardCornerRadius),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(content = content)
        }
        if (footer != null) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = RowHorizontalPadding, vertical = SectionHeaderBottomPadding)
            )
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = RowMinHeight)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding)
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
        Spacer(modifier = Modifier.width(RowHorizontalPadding))
        Switch(
            checked = checked,
            onCheckedChange = null
        )
    }
}

@Composable
private fun SettingsLinkRow(title: String, onClick: () -> Unit, testTag: String) {
    val openDescription = stringResource(R.string.settings_open_external_link)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = LinkMinHeight)
            .clickable(onClickLabel = openDescription, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title, $openDescription"
            }
            .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(
            painter = painterResource(OpenHikesIcons.OpenInNew),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSize)
        )
    }
}

@Composable
private fun SettingsInfoRow(label: String, value: String, testTag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = LinkMinHeight)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(horizontal = RowHorizontalPadding)
    )
}
