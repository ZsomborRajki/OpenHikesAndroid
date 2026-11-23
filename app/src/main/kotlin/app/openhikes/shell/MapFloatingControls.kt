package app.openhikes.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalFloatingToolbar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.openhikes.R
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.map.MapOverlayState
import app.openhikes.core.model.MapAttribution
import app.openhikes.core.model.MapAttributionCredit
import app.openhikes.core.ui.testing.OpenHikesTestTags
import kotlin.math.roundToInt

private val ControlsEdgePadding = 12.dp
private val TouchTargetMinSize = 48.dp
private val AttributionMinHeight = 48.dp
private val AttributionInnerPaddingHorizontal = 14.dp
private val AttributionInnerPaddingVertical = 8.dp
private val AttributionClearanceAboveSheet = 56.dp
private val AttributionMinTopClearance = 88.dp
private val DialogRowMinHeight = 48.dp
private val DialogIconSize = 18.dp
private val DialogSpacing = 8.dp
private val PillElevation = 3.dp

/**
 * Floating map controls (Material 3 Expressive [VerticalFloatingToolbar]) and clickable
 * data attribution pill + dialog, positioned cleanly around either the bottom sheet or side panel.
 */
@Composable
fun MapFloatingControls(
    shellLayout: ShellLayout,
    sheetDetent: SheetDetent,
    sheetTopOffsetProvider: () -> Float,
    sidePanelEdge: Dp,
    overlayState: MapOverlayState,
    showAttributionDialog: Boolean,
    onToggleHikingRoutes: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetNorth: () -> Unit,
    onOpenAttributionDialog: () -> Unit,
    onDismissAttributionDialog: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    isRecordingActive: Boolean = false,
    onOpenRecording: () -> Unit = {},
    onOpenOfflineMaps: () -> Unit = {}
) {
    val safePadding = WindowInsets.safeDrawing.asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current

    Box(modifier = modifier.fillMaxSize()) {
        MapActionToolbar(
            showsHikingRoutes = overlayState.showsHikingRoutes,
            isRecordingActive = isRecordingActive,
            onOpenRecording = onOpenRecording,
            onOpenOfflineMaps = onOpenOfflineMaps,
            onToggleHikingRoutes = onToggleHikingRoutes,
            onZoomIn = onZoomIn,
            onZoomOut = onZoomOut,
            onResetNorth = onResetNorth,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(safePadding)
                .padding(ControlsEdgePadding)
        )

        if (overlayState.attribution.credits.isNotEmpty() &&
            !(shellLayout == ShellLayout.BOTTOM_SHEET && sheetDetent == SheetDetent.EXPANDED)
        ) {
            AttributionBadge(
                shellLayout = shellLayout,
                sheetTopOffsetProvider = sheetTopOffsetProvider,
                sidePanelOffset = sidePanelEdge + ControlsEdgePadding,
                safeStart = safePadding.calculateStartPadding(layoutDirection),
                safeBottom = safePadding.calculateBottomPadding(),
                compactText = overlayState.attribution.compactText,
                onClick = onOpenAttributionDialog
            )
        }
    }

    if (showAttributionDialog) {
        MapAttributionDialog(
            attribution = overlayState.attribution,
            onDismiss = onDismissAttributionDialog,
            onOpenUrl = onOpenUrl
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MapActionToolbar(
    showsHikingRoutes: Boolean,
    isRecordingActive: Boolean,
    onOpenRecording: () -> Unit,
    onOpenOfflineMaps: () -> Unit,
    onToggleHikingRoutes: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetNorth: () -> Unit,
    modifier: Modifier = Modifier
) {
    val routesDescription = if (showsHikingRoutes) {
        stringResource(R.string.map_hiking_routes_hide_description)
    } else {
        stringResource(R.string.map_hiking_routes_show_description)
    }

    VerticalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
        modifier = modifier
    ) {
        RecordActionButton(isRecordingActive = isRecordingActive, onOpenRecording = onOpenRecording)
        MapToolbarIconButton(
            iconRes = OpenHikesIcons.Map,
            contentDescription = stringResource(R.string.action_offline_maps),
            testTag = OpenHikesTestTags.MAP_OFFLINE_MAPS_BUTTON,
            onClick = onOpenOfflineMaps
        )
        IconToggleButton(
            checked = showsHikingRoutes,
            onCheckedChange = { onToggleHikingRoutes() },
            modifier = Modifier
                .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
                .testTag(OpenHikesTestTags.HIKING_ROUTES_TOGGLE)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Layers),
                contentDescription = routesDescription
            )
        }
        MapToolbarIconButton(
            iconRes = OpenHikesIcons.Add,
            contentDescription = stringResource(R.string.map_zoom_in_description),
            testTag = OpenHikesTestTags.MAP_ZOOM_IN_BUTTON,
            onClick = onZoomIn
        )
        MapToolbarIconButton(
            iconRes = OpenHikesIcons.Remove,
            contentDescription = stringResource(R.string.map_zoom_out_description),
            testTag = OpenHikesTestTags.MAP_ZOOM_OUT_BUTTON,
            onClick = onZoomOut
        )
        MapToolbarIconButton(
            iconRes = OpenHikesIcons.Explore,
            contentDescription = stringResource(R.string.map_reset_north_description),
            testTag = OpenHikesTestTags.MAP_RESET_NORTH_BUTTON,
            onClick = onResetNorth
        )
    }
}

@Composable
private fun MapToolbarIconButton(iconRes: Int, contentDescription: String, testTag: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
            .testTag(testTag)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription
        )
    }
}

@Composable
private fun RecordActionButton(isRecordingActive: Boolean, onOpenRecording: () -> Unit) {
    val description = if (isRecordingActive) {
        stringResource(R.string.action_open_active_recording)
    } else {
        stringResource(R.string.action_record_hike)
    }
    if (isRecordingActive) {
        FilledIconButton(
            onClick = onOpenRecording,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ),
            modifier = Modifier
                .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
                .testTag(OpenHikesTestTags.RECORD_BUTTON)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Record),
                contentDescription = description
            )
        }
    } else {
        IconButton(
            onClick = onOpenRecording,
            modifier = Modifier
                .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
                .testTag(OpenHikesTestTags.RECORD_BUTTON)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Record),
                contentDescription = description
            )
        }
    }
}

@Composable
private fun AttributionBadge(
    shellLayout: ShellLayout,
    sheetTopOffsetProvider: () -> Float,
    sidePanelOffset: Dp,
    safeStart: Dp,
    safeBottom: Dp,
    compactText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val accessibilityText = stringResource(R.string.map_attribution_button_description, compactText)

    val positionedModifier = if (shellLayout == ShellLayout.BOTTOM_SHEET) {
        val clearancePx = with(density) { AttributionClearanceAboveSheet.toPx() }
        val minTopPx = with(density) { AttributionMinTopClearance.toPx() }
        modifier
            .fillMaxSize()
            .padding(start = safeStart + ControlsEdgePadding)
            .offset {
                val y = (sheetTopOffsetProvider() - clearancePx).coerceAtLeast(minTopPx).roundToInt()
                IntOffset(0, y)
            }
    } else {
        modifier
            .fillMaxSize()
            .padding(start = sidePanelOffset, bottom = safeBottom + ControlsEdgePadding)
    }

    val alignment = if (shellLayout == ShellLayout.BOTTOM_SHEET) {
        Alignment.TopStart
    } else {
        Alignment.BottomStart
    }

    Box(modifier = positionedModifier, contentAlignment = alignment) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = PillElevation,
            modifier = Modifier
                .defaultMinSize(minHeight = AttributionMinHeight)
                .semantics { contentDescription = accessibilityText }
                .testTag(OpenHikesTestTags.MAP_ATTRIBUTION)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(
                    horizontal = AttributionInnerPaddingHorizontal,
                    vertical = AttributionInnerPaddingVertical
                )
            ) {
                Text(
                    text = compactText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MapAttributionDialog(attribution: MapAttribution, onDismiss: () -> Unit, onOpenUrl: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(OpenHikesTestTags.MAP_ATTRIBUTION_DIALOG),
        title = { Text(text = stringResource(R.string.map_attribution_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DialogSpacing)) {
                attribution.credits.forEachIndexed { index, credit ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    AttributionCreditRow(credit = credit, onOpenUrl = onOpenUrl)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = TouchTargetMinSize)
            ) {
                Text(text = stringResource(R.string.map_attribution_dialog_close))
            }
        }
    )
}

@Composable
private fun AttributionCreditRow(credit: MapAttributionCredit, onOpenUrl: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = DialogRowMinHeight)
            .clickable(role = Role.Button) { onOpenUrl(credit.url) }
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = credit.plainText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = credit.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            painter = painterResource(OpenHikesIcons.OpenInNew),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(DialogIconSize)
        )
    }
}
