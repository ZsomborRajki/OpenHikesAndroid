package app.openhikes.shell

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import app.openhikes.core.ui.testing.OpenHikesTestTags

private val PanelOuterPadding = 12.dp
private val PanelInnerTopPadding = 16.dp
private val PanelMaxWidth = 360.dp
private val PanelCornerRadius = 28.dp
private val PanelElevation = 6.dp
private const val PANEL_MAX_WIDTH_FRACTION = 0.42f

/**
 * Computes the docked side panel's target width while guaranteeing the live map always keeps
 * at least half of the window.
 */
fun calculateSidePanelWidth(availableWidth: Dp): Dp = min(PanelMaxWidth, availableWidth * PANEL_MAX_WIDTH_FRACTION)

/**
 * Docked leading-edge panel hosting the shared Navigation 3 content beside the live map
 * in landscape phone, unfolded foldable, and tablet windows.
 */
@Composable
fun MapSidePanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(PanelOuterPadding),
        contentAlignment = Alignment.TopStart
    ) {
        val panelWidth = calculateSidePanelWidth(maxWidth)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(PanelCornerRadius),
            shadowElevation = PanelElevation,
            modifier = Modifier
                .width(panelWidth)
                .fillMaxHeight()
                .testTag(OpenHikesTestTags.MAP_SIDE_PANEL)
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = PanelInnerTopPadding)
            ) {
                content()
            }
        }
    }
}
