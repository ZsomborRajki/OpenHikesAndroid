package app.openhikes.core.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.ui.components.iconRes
import app.openhikes.core.ui.components.tintColor
import app.openhikes.core.ui.testing.OpenHikesTestTags
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import org.maplibre.compose.map.MapState
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.spatialk.geojson.Position

private val PlacePinTouchTargetSize = 48.dp
private val PlacePinVisualDiameter = 30.dp
private val PlacePinIconSize = 18.dp
private val PlacementPinVisualDiameter = 36.dp
private val PlacementPinIconSize = 20.dp
private val PlacePinBorderWidth = 2.dp
private val PlacePinShadowElevation = 3.dp
private const val CANDIDATE_PIN_ALPHA = 0.62f

@Composable
internal fun MapOverlayScope.TrailPlacesInteractiveOverlay(
    mapState: MapState,
    viewportPadding: PaddingValues,
    trailPlacesOverlayState: StateFlow<MapTrailPlacesOverlay?>,
    interactionHandlerProvider: () -> TrailPlacesMapInteractionHandler?
) {
    val overlay by trailPlacesOverlayState.collectAsStateWithLifecycle()
    val current = overlay?.takeIf { it.hasVisibleContent } ?: return
    val currentHandlerProvider by rememberUpdatedState(interactionHandlerProvider)
    val density = LocalDensity.current

    if (current.allowLongPressDrop) {
        PlaceLongPressDropSurface(
            screenToCoordinate = { offset -> placeScreenToCoordinate(mapState, density, offset) },
            handlerProvider = { currentHandlerProvider() }
        )
    }

    current.candidates.forEach { candidate ->
        MapPlacePinMarker(
            pin = candidate,
            alpha = CANDIDATE_PIN_ALPHA,
            testTag = OpenHikesTestTags.HIKE_PLACE_CANDIDATE_PIN,
            onClick = { currentHandlerProvider()?.onTapCandidatePin(candidate.id) }
        )
    }

    current.pins.forEach { pin ->
        MapPlacePinMarker(
            pin = pin,
            alpha = 1f,
            testTag = OpenHikesTestTags.HIKE_PLACE_PIN,
            onClick = { currentHandlerProvider()?.onTapPlacePin(pin.id) }
        )
    }

    if (current.showCenterPlacementPin) {
        CenterPlacementPinOverlay(
            mapState = mapState,
            viewportPadding = viewportPadding,
            symbol = current.placementSymbol,
            handlerProvider = { currentHandlerProvider() }
        )
    }
}

@Composable
private fun PlaceLongPressDropSurface(
    screenToCoordinate: (Offset) -> GeoCoordinate?,
    handlerProvider: () -> TrailPlacesMapInteractionHandler?
) {
    val currentScreenToCoord by rememberUpdatedState(screenToCoordinate)
    val currentHandlerProvider by rememberUpdatedState(handlerProvider)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val outcome = awaitDraftHoldOutcome(
                        pointerId = down.id,
                        origin = down.position,
                        touchSlop = touchSlop,
                        timeoutMs = TRAIL_DRAFT_DROP_PRESS_TIMEOUT_MS
                    )
                    if (outcome == DraftHoldOutcome.HELD) {
                        val coordinate = currentScreenToCoord(down.position)
                        if (coordinate != null) {
                            currentHandlerProvider()?.onLongPressMap(coordinate)
                        }
                    }
                }
            }
    )
}

@Composable
private fun MapOverlayScope.MapPlacePinMarker(pin: MapPlacePin, alpha: Float, testTag: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .placedAt(
                position = Position(longitude = pin.coordinate.longitude, latitude = pin.coordinate.latitude),
                alignment = Alignment.Center
            )
            .size(PlacePinTouchTargetSize)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = pin.title
                role = Role.Button
            }
            .testTag(testTag)
    ) {
        Surface(
            color = pin.symbol.tintColor().copy(alpha = alpha),
            contentColor = Color.White,
            shape = CircleShape,
            border = BorderStroke(PlacePinBorderWidth, Color.White.copy(alpha = alpha)),
            shadowElevation = PlacePinShadowElevation,
            modifier = Modifier.size(PlacePinVisualDiameter)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(pin.symbol.iconRes()),
                    contentDescription = null,
                    modifier = Modifier.size(PlacePinIconSize)
                )
            }
        }
    }
}

@Composable
private fun CenterPlacementPinOverlay(
    mapState: MapState,
    viewportPadding: PaddingValues,
    symbol: TrailPlaceSymbol?,
    handlerProvider: () -> TrailPlacesMapInteractionHandler?
) {
    val description = stringResource(R.string.trail_place_placeholder_description)
    val layoutDirection = LocalLayoutDirection.current
    val currentHandlerProvider by rememberUpdatedState(handlerProvider)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val reticleOffset = unobstructedViewportCenter(
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            viewportPadding = viewportPadding,
            layoutDirection = layoutDirection
        )
        val currentReticleOffset by rememberUpdatedState(reticleOffset)

        LaunchedEffect(mapState) {
            snapshotFlow { mapState.cameraPosition to currentReticleOffset }
                .distinctUntilChanged()
                .drop(1)
                .collect { (_, screenCenter) ->
                    val projected = placeDpToCoordinate(mapState, screenCenter) ?: return@collect
                    currentHandlerProvider()?.onPlacementCoordinateChanged(projected)
                }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(viewportPadding)
        ) {
            Surface(
                color = symbol.tintColor(),
                contentColor = Color.White,
                shape = CircleShape,
                border = BorderStroke(PlacePinBorderWidth, Color.White),
                shadowElevation = PlacePinShadowElevation,
                modifier = Modifier
                    .size(PlacementPinVisualDiameter)
                    .semantics { contentDescription = description }
                    .testTag(OpenHikesTestTags.HIKE_PLACE_PLACEHOLDER_PIN)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(symbol.iconRes()),
                        contentDescription = null,
                        modifier = Modifier.size(PlacementPinIconSize)
                    )
                }
            }
        }
    }
}

internal fun unobstructedViewportCenter(
    maxWidth: Dp,
    maxHeight: Dp,
    viewportPadding: PaddingValues,
    layoutDirection: LayoutDirection
): DpOffset {
    val left = if (layoutDirection == LayoutDirection.Ltr) {
        viewportPadding.calculateStartPadding(layoutDirection)
    } else {
        viewportPadding.calculateEndPadding(layoutDirection)
    }
    val right = if (layoutDirection == LayoutDirection.Ltr) {
        viewportPadding.calculateEndPadding(layoutDirection)
    } else {
        viewportPadding.calculateStartPadding(layoutDirection)
    }
    val top = viewportPadding.calculateTopPadding()
    val bottom = viewportPadding.calculateBottomPadding()
    val usableWidth = (maxWidth - left - right).coerceAtLeast(0.dp)
    val usableHeight = (maxHeight - top - bottom).coerceAtLeast(0.dp)
    return DpOffset(x = left + usableWidth / 2f, y = top + usableHeight / 2f)
}

private fun placeScreenToCoordinate(mapState: MapState, density: Density, offset: Offset): GeoCoordinate? {
    val dpOffset = with(density) { DpOffset(offset.x.toDp(), offset.y.toDp()) }
    return placeDpToCoordinate(mapState, dpOffset)
}

private fun placeDpToCoordinate(mapState: MapState, dpOffset: DpOffset): GeoCoordinate? =
    mapState.positionFromScreenLocation(dpOffset)
        ?.takeIf { it.latitude.isFinite() && it.longitude.isFinite() }
        ?.let { GeoCoordinate(latitude = it.latitude, longitude = it.longitude) }
