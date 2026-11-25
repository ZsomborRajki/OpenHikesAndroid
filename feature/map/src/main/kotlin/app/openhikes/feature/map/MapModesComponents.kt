package app.openhikes.feature.map

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.MapStyleId
import app.openhikes.core.ui.format.mapStyleDisplayName
import app.openhikes.core.ui.testing.OpenHikesTestTags

private val HorizontalMargin = 16.dp
private val TopBarVerticalPadding = 6.dp
private val TouchTargetMinSize = 48.dp
private val ThumbnailBaseSide = 84.dp
private val ThumbnailMaxSide = 120.dp
private val ThumbnailCornerRadius = 14.dp
private val RingStrokeWidth = 3.dp
private val RingGap = 2.dp
private val HairlineWidth = 1.dp
private val CardSpacing = 10.dp
private val HikingRoutesCardSpacing = 24.dp
private val LabelTopSpacing = 6.dp
private val SectionInnerSpacing = 10.dp
private const val HAIRLINE_ALPHA = 0.14f
private const val DISABLED_CARD_ALPHA = 0.55f
private const val MAX_FONT_SCALE_FOR_THUMBNAIL = 1.45f
private const val LABEL_MAX_LINES = 2
private const val SUMMARY_MIN_LINES = 2

@Composable
internal fun MapModesTopBar(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HorizontalMargin, vertical = TopBarVerticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.size(TouchTargetMinSize))
        Text(
            text = stringResource(R.string.map_modes_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() }
                .testTag(OpenHikesTestTags.MAP_MODES_TITLE)
        )
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
                .testTag(OpenHikesTestTags.MAP_MODES_CLOSE)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Close),
                contentDescription = stringResource(R.string.map_modes_close_description)
            )
        }
    }
}

@Composable
internal fun MapStylePickerSection(
    styles: List<MapStyleCardUiModel>,
    selectedStyleId: String,
    selectedIndex: Int,
    previews: Map<String, ImageBitmap>,
    onSelectStyle: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        if (selectedIndex > 0) {
            listState.scrollToItem(selectedIndex)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SectionInnerSpacing)
    ) {
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = HorizontalMargin),
            horizontalArrangement = Arrangement.spacedBy(CardSpacing),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(OpenHikesTestTags.MAP_STYLE_ROW)
        ) {
            items(items = styles, key = { it.style.id }) { card ->
                MapStyleCard(
                    card = card,
                    preview = previews[card.style.id],
                    onSelect = { onSelectStyle(card.style.id) }
                )
            }
        }
        Text(
            text = mapStyleSummaryText(selectedStyleId),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            minLines = SUMMARY_MIN_LINES,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HorizontalMargin)
                .testTag(OpenHikesTestTags.MAP_STYLE_SUMMARY)
        )
    }
}

@Composable
private fun MapStyleCard(
    card: MapStyleCardUiModel,
    preview: ImageBitmap?,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val side = scaledThumbnailSide()
    val cardWidth = thumbnailFrameOuterWidth(side)
    val styleName = mapStyleDisplayName(card.style.id)
    val needsKeyLabel = stringResource(R.string.map_modes_needs_api_key)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(cardWidth)
            .alpha(if (card.isUsable) 1f else DISABLED_CARD_ALPHA)
            .selectable(
                selected = card.isSelected,
                enabled = card.isUsable,
                role = Role.RadioButton,
                onClick = onSelect
            )
            .semantics(mergeDescendants = true) {
                if (!card.isUsable) {
                    stateDescription = needsKeyLabel
                }
            }
            .testTag(OpenHikesTestTags.mapStyleCard(card.style.id))
    ) {
        MapModeThumbnailFrame(isSelected = card.isSelected, side = side) {
            if (preview != null) {
                Image(
                    bitmap = preview,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                MapStyleSwatch(styleId = card.style.id)
            }
        }
        Spacer(modifier = Modifier.height(LabelTopSpacing))
        Text(
            text = styleName,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (card.isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = LABEL_MAX_LINES,
            overflow = TextOverflow.Ellipsis
        )
        if (!card.isUsable) {
            Text(
                text = needsKeyLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = LABEL_MAX_LINES,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun HikingRoutesSection(
    showsHikingRoutes: Boolean,
    onSetShowsHikingRoutes: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val side = scaledThumbnailSide()
    val pairWidthNeeded = thumbnailFrameOuterWidth(side) * 2 + HikingRoutesCardSpacing

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HorizontalMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SectionInnerSpacing)
    ) {
        BoxWithConstraints(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            HikingRoutesOptionsLayout(
                horizontal = maxWidth >= pairWidthNeeded,
                showsHikingRoutes = showsHikingRoutes,
                side = side,
                onSetShowsHikingRoutes = onSetShowsHikingRoutes
            )
        }
        Text(
            text = stringResource(R.string.map_modes_hiking_routes_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun HikingRoutesOptionsLayout(
    horizontal: Boolean,
    showsHikingRoutes: Boolean,
    side: Dp,
    onSetShowsHikingRoutes: (Boolean) -> Unit
) {
    val cleanCard: @Composable () -> Unit = {
        HikingRoutesOptionCard(
            title = stringResource(R.string.map_modes_clean_map),
            imageRes = R.drawable.map_modes_clean_map,
            isSelected = !showsHikingRoutes,
            side = side,
            testTag = OpenHikesTestTags.HIKING_ROUTES_OFF,
            onSelect = { onSetShowsHikingRoutes(false) }
        )
    }
    val routesCard: @Composable () -> Unit = {
        HikingRoutesOptionCard(
            title = stringResource(R.string.map_modes_hiking_routes),
            imageRes = R.drawable.map_modes_hiking_routes,
            isSelected = showsHikingRoutes,
            side = side,
            testTag = OpenHikesTestTags.HIKING_ROUTES_ON,
            onSelect = { onSetShowsHikingRoutes(true) }
        )
    }
    if (horizontal) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(HikingRoutesCardSpacing),
            verticalAlignment = Alignment.Top
        ) {
            cleanCard()
            routesCard()
        }
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SectionInnerSpacing)
        ) {
            cleanCard()
            routesCard()
        }
    }
}

@Composable
private fun HikingRoutesOptionCard(
    title: String,
    @DrawableRes imageRes: Int,
    isSelected: Boolean,
    side: Dp,
    testTag: String,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(thumbnailFrameOuterWidth(side))
            .selectable(
                selected = isSelected,
                role = Role.RadioButton,
                onClick = onSelect
            )
            .semantics(mergeDescendants = true) {}
            .testTag(testTag)
    ) {
        MapModeThumbnailFrame(isSelected = isSelected, side = side) {
            Image(
                painter = painterResource(imageRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(LabelTopSpacing))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = LABEL_MAX_LINES,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MapModeThumbnailFrame(
    isSelected: Boolean,
    side: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val ringInset = RingStrokeWidth + RingGap
    val outerShape = RoundedCornerShape(ThumbnailCornerRadius + ringInset)
    val innerShape = RoundedCornerShape(ThumbnailCornerRadius)
    val ringModifier = if (isSelected) {
        Modifier.border(width = RingStrokeWidth, color = MaterialTheme.colorScheme.primary, shape = outerShape)
    } else {
        Modifier
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(thumbnailFrameOuterWidth(side))
            .then(ringModifier)
            .padding(ringInset)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(innerShape)
                .border(
                    width = HairlineWidth,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = HAIRLINE_ALPHA),
                    shape = innerShape
                )
        ) {
            content()
        }
    }
}

@Composable
@ReadOnlyComposable
private fun scaledThumbnailSide(): Dp {
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, MAX_FONT_SCALE_FOR_THUMBNAIL)
    return (ThumbnailBaseSide * fontScale).coerceAtMost(ThumbnailMaxSide)
}

private fun thumbnailFrameOuterWidth(side: Dp): Dp = side + (RingStrokeWidth + RingGap) * 2

@Composable
@ReadOnlyComposable
private fun mapStyleSummaryText(styleId: String): String = when (styleId) {
    MapStyleId.OPENHIKES -> stringResource(R.string.map_modes_style_openhikes_summary)
    MapStyleId.OPENFREEMAP -> stringResource(R.string.map_modes_style_openfreemap_summary)
    MapStyleId.OSM_RASTER -> stringResource(R.string.map_modes_style_osm_raster_summary)
    MapStyleId.STADIA_OUTDOORS -> stringResource(R.string.map_modes_style_stadia_outdoors_summary)
    MapStyleId.STAMEN_TERRAIN -> stringResource(R.string.map_modes_style_stamen_terrain_summary)
    MapStyleId.THUNDERFOREST_OUTDOORS -> stringResource(R.string.map_modes_style_thunderforest_outdoors_summary)
    else -> stringResource(R.string.map_modes_style_openhikes_summary)
}
