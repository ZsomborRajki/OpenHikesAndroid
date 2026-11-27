package app.openhikes.feature.trailmaker

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteColoring
import app.openhikes.core.ui.R as CoreUiR
import app.openhikes.core.ui.components.RouteColoringPicker
import app.openhikes.core.ui.testing.OpenHikesTestTags

private val ScreenHorizontalPadding = 16.dp
private val SectionSpacing = 12.dp
private val TopBarPaddingVertical = 6.dp
private val BottomContentPadding = 24.dp
private val MinTouchTarget = 48.dp
private val ColoringCardCornerRadius = 20.dp
private val ColoringCardPadding = 16.dp
private val ColoringCardSpacing = 8.dp

/**
 * Stateful Navigation 3 entry composable for Trail Maker.
 */
@Composable
fun TrailMakerRoute(
    onBack: () -> Unit,
    onSaved: (HikeId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TrailMakerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val chooserTitle = stringResource(R.string.trail_place_share_chooser)

    TrailMakerScreen(
        uiState = uiState,
        onBack = onBack,
        onCloseRequested = { viewModel.onCloseRequested(onBack) },
        onDiscardTrail = { viewModel.onDiscardTrail(onBack) },
        onKeepForLater = { viewModel.onKeepForLater(onBack) },
        onConfirmSave = { viewModel.onConfirmSave(onSaved) },
        onSharePlace = { title, body ->
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, body)
            }
            runCatching { context.startActivity(Intent.createChooser(sendIntent, chooserTitle)) }
        },
        onAction = viewModel::onAction,
        modifier = modifier
    )
}

/**
 * Stateless Material 3 Expressive Trail Maker screen hosted inside the adaptive sheet/side panel.
 */
@Composable
fun TrailMakerScreen(
    uiState: TrailMakerUiState,
    onBack: () -> Unit,
    onCloseRequested: () -> Unit,
    onDiscardTrail: () -> Unit,
    onKeepForLater: () -> Unit,
    onConfirmSave: () -> Unit,
    onSharePlace: (String, String) -> Unit,
    onAction: (TrailMakerAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TrailMakerTopBar(
                canBeSaved = uiState.canBeSaved && uiState.isEditingEnabled,
                canClose = uiState.isEditingEnabled,
                onBack = onBack,
                onSaveClick = { onAction(TrailMakerAction.OpenSaveDialog) },
                onCloseClick = onCloseRequested
            )

            TrailMakerContentList(
                uiState = uiState,
                onAction = onAction
            )
        }

        TrailMakerOverlays(
            uiState = uiState,
            onDiscardTrail = onDiscardTrail,
            onKeepForLater = onKeepForLater,
            onConfirmSave = onConfirmSave,
            onSharePlace = onSharePlace,
            onAction = onAction
        )
    }
}

@Composable
private fun TrailMakerContentList(uiState: TrailMakerUiState, onAction: (TrailMakerAction) -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(OpenHikesTestTags.TRAIL_MAKER_SCREEN),
        contentPadding = PaddingValues(
            start = ScreenHorizontalPadding,
            end = ScreenHorizontalPadding,
            top = TopBarPaddingVertical,
            bottom = BottomContentPadding
        ),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing)
    ) {
        item(key = "travel_mode") {
            TrailTravelModeBar(
                selectedMode = uiState.travelMode,
                enabled = uiState.isEditingEnabled,
                onSelectMode = { onAction(TrailMakerAction.SelectTravelMode(it)) }
            )
        }

        item(key = "route_card") {
            TrailRouteCard(
                uiState = uiState,
                onAction = onAction
            )
        }

        if (uiState.canSnapToPaths) {
            item(key = "follow_paths") {
                FollowPathsCard(
                    snapsToPaths = uiState.snapsToPaths,
                    travelMode = uiState.travelMode,
                    enabled = uiState.isEditingEnabled,
                    onToggle = { onAction(TrailMakerAction.ToggleSnapToPaths(it)) }
                )
            }
        }

        if (uiState.showsColoringSection) {
            item(key = "on_map") {
                TrailMapColoringCard(
                    routeColoring = uiState.routeColoring,
                    isElevationAvailable = uiState.isElevationColoringAvailable,
                    onSelectColoring = { onAction(TrailMakerAction.SelectRouteColoring(it)) }
                )
            }
        }
    }
}

@Composable
private fun TrailMapColoringCard(
    routeColoring: RouteColoring,
    isElevationAvailable: Boolean,
    onSelectColoring: (RouteColoring) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(ColoringCardCornerRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(ColoringCardPadding),
            verticalArrangement = Arrangement.spacedBy(ColoringCardSpacing)
        ) {
            Text(
                text = stringResource(R.string.trail_maker_on_map_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .semantics { heading() }
                    .testTag(OpenHikesTestTags.TRAIL_DRAFT_COLORING)
            )
            RouteColoringPicker(
                selectedColoring = routeColoring,
                onSelectColoring = onSelectColoring,
                elevationUnavailableNote = if (isElevationAvailable) {
                    null
                } else {
                    stringResource(CoreUiR.string.route_coloring_trail_maker_elevation_note)
                }
            )
        }
    }
}

@Composable
private fun TrailMakerOverlays(
    uiState: TrailMakerUiState,
    onDiscardTrail: () -> Unit,
    onKeepForLater: () -> Unit,
    onConfirmSave: () -> Unit,
    onSharePlace: (String, String) -> Unit,
    onAction: (TrailMakerAction) -> Unit
) {
    TrailMakerDialogs(
        uiState = uiState,
        onDiscardTrail = onDiscardTrail,
        onKeepForLater = onKeepForLater,
        onConfirmSave = onConfirmSave,
        onAction = onAction
    )

    uiState.stopSearch?.let { searchState ->
        TrailStopSearchOverlay(
            state = searchState,
            onAction = onAction
        )
    }

    uiState.placeCard?.let { card ->
        TrailPlaceCardDialog(
            card = card,
            distanceUnit = uiState.distanceUnit,
            onShare = onSharePlace,
            onAction = onAction
        )
    }
}

@Composable
private fun TrailMakerTopBar(
    canBeSaved: Boolean,
    canClose: Boolean,
    onBack: () -> Unit,
    onSaveClick: () -> Unit,
    onCloseClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TopBarPaddingVertical, vertical = TopBarPaddingVertical),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
                .testTag(OpenHikesTestTags.TRAIL_MAKER_BACK)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.ArrowBack),
                contentDescription = stringResource(R.string.trail_maker_back_description)
            )
        }
        Text(
            text = stringResource(R.string.trail_maker_title),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = TopBarPaddingVertical)
                .semantics { heading() }
        )
        IconButton(
            onClick = onSaveClick,
            enabled = canBeSaved,
            modifier = Modifier
                .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
                .testTag(OpenHikesTestTags.TRAIL_DRAFT_SAVE)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Check),
                contentDescription = stringResource(R.string.trail_maker_save_description)
            )
        }
        IconButton(
            onClick = onCloseClick,
            enabled = canClose,
            modifier = Modifier
                .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
                .testTag(OpenHikesTestTags.TRAIL_DRAFT_CLOSE)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Close),
                contentDescription = stringResource(R.string.trail_maker_close_description)
            )
        }
    }
}
