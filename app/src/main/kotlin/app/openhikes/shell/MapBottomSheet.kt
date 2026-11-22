package app.openhikes.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.openhikes.R
import app.openhikes.core.ui.testing.OpenHikesTestTags
import kotlin.math.roundToInt

private val SheetPeekContentHeight = 112.dp
private val SheetTopSafeMargin = 16.dp
private val SheetCornerRadius = 28.dp
private val SheetElevation = 8.dp
private val TouchTargetMinSize = 48.dp
private const val HALF_SCREEN_TOP_FRACTION = 0.48f
private const val DETENT_GAP_DP = 40
private const val DRAG_POSITIONAL_THRESHOLD = 0.4f

/**
 * Persistent Material 3 Expressive 3-detent bottom sheet (`PEEK`, `HALF`, `EXPANDED`)
 * hosting the shared Navigation 3 content over the full-bleed map.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MapBottomSheet(
    detent: SheetDetent,
    onDetentChange: (SheetDetent) -> Unit,
    onSheetTopOffsetChange: (() -> Float) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val bottomPadding = WindowInsets.navigationBars.union(WindowInsets.ime).asPaddingValues().calculateBottomPadding()
    val currentOnDetentChange by rememberUpdatedState(onDetentChange)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val fullHeightPx = with(density) { maxHeight.toPx() }
        val bottomInsetPx = with(density) { bottomPadding.toPx() }
        val anchors = rememberSheetAnchors(fullHeightPx = fullHeightPx, bottomInsetPx = bottomInsetPx)
        val draggableState = rememberSyncedSheetState(
            detent = detent,
            anchors = anchors,
            onDetentChange = currentOnDetentChange
        )

        val currentTopPx: () -> Float = remember(draggableState, anchors, detent) {
            {
                val offset = draggableState.offset
                if (offset.isNaN()) anchors.positionOf(detent) else offset
            }
        }
        LaunchedEffect(currentTopPx) { onSheetTopOffsetChange(currentTopPx) }

        val expandedTopPx = with(density) { SheetTopSafeMargin.toPx() }
        val sheetHeightDp = with(density) { (fullHeightPx - expandedTopPx).coerceAtLeast(0f).toDp() }

        MapBottomSheetSurface(
            detent = detent,
            draggableState = draggableState,
            sheetHeight = sheetHeightDp,
            fullHeightPx = fullHeightPx,
            bottomPadding = bottomPadding,
            offsetProvider = currentTopPx,
            onDetentChange = onDetentChange,
            content = content
        )
    }
}

@Composable
private fun rememberSheetAnchors(fullHeightPx: Float, bottomInsetPx: Float): DraggableAnchors<SheetDetent> {
    val density = LocalDensity.current
    val peekHeightPx = with(density) { SheetPeekContentHeight.toPx() } + bottomInsetPx
    val expandedTopPx = with(density) { SheetTopSafeMargin.toPx() }
    val minGapPx = with(density) { DETENT_GAP_DP.dp.toPx() }
    return remember(fullHeightPx, peekHeightPx, expandedTopPx, minGapPx) {
        val peekTopPx = (fullHeightPx - peekHeightPx).coerceAtLeast(expandedTopPx + minGapPx * 2f)
        val halfTopPx = (fullHeightPx * HALF_SCREEN_TOP_FRACTION)
            .coerceIn(expandedTopPx + minGapPx, peekTopPx - minGapPx)
        DraggableAnchors {
            SheetDetent.EXPANDED at expandedTopPx
            SheetDetent.HALF at halfTopPx
            SheetDetent.PEEK at peekTopPx
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun rememberSyncedSheetState(
    detent: SheetDetent,
    anchors: DraggableAnchors<SheetDetent>,
    onDetentChange: (SheetDetent) -> Unit
): AnchoredDraggableState<SheetDetent> {
    val motionScheme = MaterialTheme.motionScheme
    val draggableState = remember { AnchoredDraggableState(initialValue = detent) }

    LaunchedEffect(anchors) {
        draggableState.updateAnchors(anchors, newTarget = detent)
    }
    LaunchedEffect(detent) {
        if (draggableState.targetValue != detent && draggableState.anchors.hasPositionFor(detent)) {
            draggableState.animateTo(detent, motionScheme.defaultSpatialSpec())
        }
    }
    LaunchedEffect(draggableState.settledValue) {
        onDetentChange(draggableState.settledValue)
    }
    return draggableState
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MapBottomSheetSurface(
    detent: SheetDetent,
    draggableState: AnchoredDraggableState<SheetDetent>,
    sheetHeight: Dp,
    fullHeightPx: Float,
    bottomPadding: Dp,
    offsetProvider: () -> Float,
    onDetentChange: (SheetDetent) -> Unit,
    content: @Composable () -> Unit
) {
    val flingBehavior = AnchoredDraggableDefaults.flingBehavior(
        state = draggableState,
        positionalThreshold = { distance -> distance * DRAG_POSITIONAL_THRESHOLD },
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = SheetCornerRadius, topEnd = SheetCornerRadius),
        shadowElevation = SheetElevation,
        modifier = Modifier
            .fillMaxWidth()
            .height(sheetHeight)
            .offset { IntOffset(0, offsetProvider().roundToInt()) }
            .anchoredDraggable(
                state = draggableState,
                orientation = Orientation.Vertical,
                flingBehavior = flingBehavior
            )
            .testTag(OpenHikesTestTags.MAP_SHEET)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopStart
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .visibleSheetHeight(fullHeightPx = fullHeightPx, offsetProvider = offsetProvider)
                    .padding(bottom = bottomPadding)
            ) {
                SheetDragHandle(
                    detent = detent,
                    onCycleDetent = {
                        val next = when (detent) {
                            SheetDetent.PEEK -> SheetDetent.HALF
                            SheetDetent.HALF -> SheetDetent.EXPANDED
                            SheetDetent.EXPANDED -> SheetDetent.PEEK
                        }
                        onDetentChange(next)
                    },
                    onExpand = { onDetentChange(SheetDetent.EXPANDED) },
                    onCollapse = { onDetentChange(SheetDetent.PEEK) }
                )
                Box(modifier = Modifier.weight(1f)) { content() }
            }
        }
    }
}

private fun Modifier.visibleSheetHeight(fullHeightPx: Float, offsetProvider: () -> Float): Modifier =
    layout { measurable, constraints ->
        val visibleHeight = (fullHeightPx - offsetProvider())
            .roundToInt()
            .coerceIn(0, constraints.maxHeight)
        val placeable = measurable.measure(
            constraints.copy(minHeight = visibleHeight, maxHeight = visibleHeight)
        )
        layout(placeable.width, placeable.height) {
            placeable.place(0, 0)
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetDragHandle(
    detent: SheetDetent,
    onCycleDetent: () -> Unit,
    onExpand: () -> Unit,
    onCollapse: () -> Unit
) {
    val expandLabel = stringResource(R.string.map_sheet_expand_action)
    val collapseLabel = stringResource(R.string.map_sheet_collapse_action)
    val actions = buildList {
        if (detent != SheetDetent.EXPANDED) {
            add(
                CustomAccessibilityAction(expandLabel) {
                    onExpand()
                    true
                }
            )
        }
        if (detent != SheetDetent.PEEK) {
            add(
                CustomAccessibilityAction(collapseLabel) {
                    onCollapse()
                    true
                }
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = TouchTargetMinSize)
            .clickable(onClick = onCycleDetent)
            .semantics { customActions = actions }
            .testTag(OpenHikesTestTags.SHEET_DRAG_HANDLE),
        contentAlignment = Alignment.Center
    ) {
        BottomSheetDefaults.DragHandle()
    }
}
