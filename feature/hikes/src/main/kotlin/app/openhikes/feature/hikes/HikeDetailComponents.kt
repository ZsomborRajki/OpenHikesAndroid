package app.openhikes.feature.hikes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeRouteStatistics
import app.openhikes.core.ui.components.RoutePatternSwatch
import app.openhikes.core.ui.format.HikeMetricsFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags
import app.openhikes.core.ui.theme.parseHexColorOrDefault
import app.openhikes.core.ui.theme.toOpaque

private val SectionPaddingHorizontal = 16.dp
private val CardCornerRadius = 24.dp
private val RowMinHeight = 52.dp
private val TouchTargetMinSize = 48.dp
private val HeaderBadgeSize = 44.dp
private val HeaderBadgeIconSize = 22.dp
private val CardSpacing = 12.dp
private val RowHorizontalPadding = 16.dp
private val RowVerticalPadding = 12.dp
private const val ACCESSIBILITY_FONT_SCALE_THRESHOLD = 1.5f

@Composable
internal fun HikeDetailTopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
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
                .testTag(OpenHikesTestTags.HIKE_DETAIL_BACK)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.ArrowBack),
                contentDescription = stringResource(R.string.hike_detail_back_description)
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() }
                .testTag(OpenHikesTestTags.HIKE_DETAIL_TITLE)
        )
    }
}

@Composable
internal fun HikeDetailHeader(
    hike: Hike,
    isRenaming: Boolean,
    titleDraft: String,
    onTitleDraftChange: (String) -> Unit,
    onCommitRename: () -> Unit,
    onCancelRename: () -> Unit
) {
    val routeTint = parseHexColorOrDefault(hike.tintHex, MaterialTheme.colorScheme.primary).toOpaque()
    Column(verticalArrangement = Arrangement.spacedBy(CardSpacing)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CardSpacing),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(HeaderBadgeSize)
                    .clip(CircleShape)
                    .background(routeTint)
            ) {
                Icon(
                    painter = painterResource(OpenHikesIcons.Hiking),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(HeaderBadgeIconSize)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hike.displayTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = HikeMetricsFormatter.formatDate(hike.dateEpochMillis),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (isRenaming) {
            RenameTitleEditor(
                draft = titleDraft,
                onDraftChange = onTitleDraftChange,
                onCommit = onCommitRename,
                onCancel = onCancelRename
            )
        }
    }
}

@Composable
private fun RenameTitleEditor(
    draft: String,
    onDraftChange: (String) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(CardCornerRadius),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(RowHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                singleLine = true,
                label = { Text(stringResource(R.string.hike_detail_rename_label)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onCommit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(OpenHikesTestTags.HIKE_TITLE_FIELD)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .defaultMinSize(minHeight = TouchTargetMinSize)
                        .testTag(OpenHikesTestTags.HIKE_RENAME_CANCEL_BUTTON)
                ) {
                    Text(stringResource(R.string.hike_detail_rename_cancel))
                }
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = onCommit,
                    modifier = Modifier
                        .defaultMinSize(minHeight = TouchTargetMinSize)
                        .testTag(OpenHikesTestTags.HIKE_RENAME_SAVE_BUTTON)
                ) {
                    Text(stringResource(R.string.hike_detail_rename_save))
                }
            }
        }
    }
}

@Composable
internal fun HikeDetailActionsRow(
    onZoomToRoute: () -> Unit,
    onExportGpx: () -> Unit,
    offlineCoverage: HikeOfflineCoverageState = HikeOfflineCoverageState(),
    onOpenOfflineDialog: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilledTonalButton(
                onClick = onZoomToRoute,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = TouchTargetMinSize)
                    .testTag(OpenHikesTestTags.HIKE_ZOOM_BUTTON)
            ) {
                Icon(
                    painter = painterResource(OpenHikesIcons.FitScreen),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                Text(text = stringResource(R.string.hike_detail_zoom_to_route), maxLines = 1)
            }
            OutlinedButton(
                onClick = onExportGpx,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = TouchTargetMinSize)
                    .testTag(OpenHikesTestTags.HIKE_EXPORT_GPX_BUTTON)
            ) {
                Icon(
                    painter = painterResource(OpenHikesIcons.Download),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                Text(text = stringResource(R.string.hike_detail_export_gpx), maxLines = 1)
            }
        }
        if (offlineCoverage.isOpenHikesMap && offlineCoverage.crossed.isNotEmpty()) {
            HikeOfflineCountryButton(
                coverage = offlineCoverage,
                onClick = onOpenOfflineDialog,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
internal fun HikeHeadlineStatsStrip(hike: Hike, statistics: HikeRouteStatistics?, distanceUnit: DistanceUnit) {
    val distanceText = HikeMetricsFormatter.formatDistance(hike.distanceMeters, distanceUnit)
    val gainText = statistics?.elevationGainMeters?.let {
        HikeMetricsFormatter.formatElevation(it, distanceUnit)
    }
    val durationText = (statistics?.durationSeconds ?: hike.durationSeconds)?.let {
        HikeMetricsFormatter.formatDuration(it)
    }

    val isLargeFont = LocalDensity.current.fontScale >= ACCESSIBILITY_FONT_SCALE_THRESHOLD
    val items = buildList {
        add(stringResource(R.string.hike_detail_stat_distance) to distanceText)
        if (gainText != null) add(stringResource(R.string.hike_detail_stat_gain) to gainText)
        if (durationText != null) add(stringResource(R.string.hike_detail_stat_duration) to durationText)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(CardCornerRadius),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (isLargeFont) {
            Column(
                modifier = Modifier.padding(RowHorizontalPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items.forEach { (label, value) -> HeadlineStatItem(label = label, value = value) }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(RowHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items.forEach { (label, value) ->
                    HeadlineStatItem(label = label, value = value, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HeadlineStatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
internal fun HikeStatisticsCard(statistics: HikeRouteStatistics, distanceUnit: DistanceUnit) {
    val rows = buildList {
        statistics.movingDurationSeconds?.let {
            add(stringResource(R.string.hike_detail_stat_moving_time) to HikeMetricsFormatter.formatDuration(it))
        }
        statistics.elevationLossMeters?.let {
            add(
                stringResource(R.string.hike_detail_stat_elevation_loss) to
                    HikeMetricsFormatter.formatElevation(it, distanceUnit)
            )
        }
        statistics.maxElevationMeters?.let {
            add(
                stringResource(R.string.hike_detail_stat_high_point) to
                    HikeMetricsFormatter.formatElevation(it, distanceUnit)
            )
        }
        statistics.minElevationMeters?.let {
            add(
                stringResource(R.string.hike_detail_stat_low_point) to
                    HikeMetricsFormatter.formatElevation(it, distanceUnit)
            )
        }
        statistics.averageSpeedMps?.let {
            add(
                stringResource(R.string.hike_detail_stat_avg_speed) to
                    HikeMetricsFormatter.formatSpeed(it, distanceUnit)
            )
        }
        statistics.movingAverageSpeedMps?.let {
            add(
                stringResource(R.string.hike_detail_stat_moving_speed) to
                    HikeMetricsFormatter.formatSpeed(it, distanceUnit)
            )
        }
        statistics.maxSpeedMps?.let {
            add(
                stringResource(R.string.hike_detail_stat_max_speed) to
                    HikeMetricsFormatter.formatSpeed(it, distanceUnit)
            )
        }
        add(stringResource(R.string.hike_detail_stat_points) to statistics.pointCount.toString())
    }
    if (rows.isEmpty()) return

    HikeSectionCard(title = stringResource(R.string.hike_detail_section_statistics)) {
        rows.forEachIndexed { index, (label, value) ->
            if (index > 0) CardDivider()
            KeyValueRow(label = label, value = value)
        }
    }
}

@Composable
internal fun HikeAboutCard(hike: Hike) {
    val rows = buildList {
        hike.trackDescription?.let { add(stringResource(R.string.hike_detail_about_description) to it) }
        hike.author?.let { add(stringResource(R.string.hike_detail_about_author) to it) }
        hike.keywords?.let { add(stringResource(R.string.hike_detail_about_keywords) to it) }
    }
    if (rows.isEmpty()) return

    HikeSectionCard(title = stringResource(R.string.hike_detail_section_about)) {
        rows.forEachIndexed { index, (label, value) ->
            if (index > 0) CardDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {}
                    .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
internal fun HikeOnMapCard(hike: Hike, onOpenRouteStyle: () -> Unit) {
    HikeSectionCard(title = stringResource(R.string.hike_detail_section_on_map)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = RowMinHeight)
                .clickable(role = Role.Button, onClick = onOpenRouteStyle)
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding)
                .testTag(OpenHikesTestTags.ROUTE_STYLE_ROW),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Palette),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.hike_detail_route_style),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            RoutePatternSwatch(
                pattern = hike.linePattern,
                tintHex = hike.tintHex,
                borderHex = hike.routeBorderHex
            )
            Icon(
                painter = painterResource(OpenHikesIcons.ChevronRight),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun HikeClosingActionsCard(onStartRename: () -> Unit, onRequestDelete: () -> Unit) {
    HikeSectionCard(title = null) {
        ActionMenuRow(
            label = stringResource(R.string.hike_detail_rename_hike),
            iconRes = OpenHikesIcons.Edit,
            tint = MaterialTheme.colorScheme.onSurface,
            onClick = onStartRename,
            testTag = OpenHikesTestTags.HIKE_RENAME_BUTTON
        )
        CardDivider()
        ActionMenuRow(
            label = stringResource(R.string.hike_detail_delete_hike),
            iconRes = OpenHikesIcons.Delete,
            tint = MaterialTheme.colorScheme.error,
            onClick = onRequestDelete,
            testTag = OpenHikesTestTags.HIKE_DELETE_BUTTON
        )
    }
}

@Composable
private fun ActionMenuRow(label: String, iconRes: Int, tint: Color, onClick: () -> Unit, testTag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = RowMinHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = null, tint = tint)
        Text(text = label, style = MaterialTheme.typography.titleMedium, color = tint)
    }
}

@Composable
internal fun HikeSectionCard(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = 4.dp, vertical = 6.dp)
                    .semantics { heading() }
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(CardCornerRadius),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = RowMinHeight)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = RowHorizontalPadding, vertical = RowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CardDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(horizontal = SectionPaddingHorizontal)
    )
}
