package app.openhikes.feature.walks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeWalk
import app.openhikes.core.model.TrailWalkEndReason
import app.openhikes.core.ui.format.HikeMetricsFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags
import app.openhikes.core.ui.theme.parseHexColorOrDefault
import app.openhikes.core.ui.theme.toOpaque
import java.time.Instant
import java.time.ZoneId

private val HorizontalPadding = 16.dp
private val SectionSpacing = 16.dp
private val CardCornerRadius = 24.dp
private val CardPadding = 16.dp
private val TouchTargetMinSize = 48.dp
private val RowMinHeight = 52.dp
private val ProgressBarHeight = 10.dp
private val NoticeCornerRadius = 20.dp
private const val MINIMUM_UNCOVERED_DISPLAY_METERS = 1.0

/**
 * Stateful hike summary destination scoped to a saved [walkId] on [hikeId].
 */
@Composable
fun WalkSummaryRoute(
    hikeId: HikeId,
    walkId: String,
    onBack: () -> Unit,
    onCollapseSheet: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WalkSummaryViewModel = hiltViewModel<WalkSummaryViewModel, WalkSummaryViewModel.Factory>(
        key = "${hikeId.value}-$walkId"
    ) { factory ->
        factory.create(rawHikeId = hikeId.value, walkId = walkId)
    }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    WalkSummaryScreen(
        uiState = uiState,
        onBack = onBack,
        onShowOnMap = { viewModel.onShowOnMap(onCollapseSheet) },
        modifier = modifier
    )
}

/**
 * Stateless hike summary screen shared across runtime, tests, previews, and screenshot tests.
 */
@Composable
fun WalkSummaryScreen(
    uiState: WalkSummaryUiState,
    onBack: () -> Unit,
    onShowOnMap: () -> Unit,
    modifier: Modifier = Modifier,
    zoneId: ZoneId = ZoneId.systemDefault()
) {
    val walk = uiState.walk

    Column(modifier = modifier.fillMaxSize()) {
        WalkSummaryTopBar(onBack = onBack)
        if (walk == null && uiState.isLoaded) {
            MissingWalkState(modifier = Modifier.weight(1f))
        } else if (walk != null) {
            WalkSummaryList(
                uiState = uiState,
                walk = walk,
                zoneId = zoneId,
                onShowOnMap = onShowOnMap,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun WalkSummaryTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
                .testTag(OpenHikesTestTags.WALK_SUMMARY_BACK)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.ArrowBack),
                contentDescription = stringResource(R.string.walk_summary_back_description)
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.walk_summary_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() }
        )
    }
}

@Composable
private fun WalkSummaryList(
    uiState: WalkSummaryUiState,
    walk: HikeWalk,
    zoneId: ZoneId,
    onShowOnMap: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(OpenHikesTestTags.WALK_SUMMARY_SCREEN),
        contentPadding = PaddingValues(
            start = HorizontalPadding,
            end = HorizontalPadding,
            bottom = SectionSpacing
        ),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing)
    ) {
        item(key = "completion") {
            WalkCompletionHeroCard(
                hikeTitle = uiState.hikeTitle,
                tintHex = uiState.tintHex,
                walk = walk,
                distanceUnit = uiState.distanceUnit
            )
        }
        item(key = "show_on_map") {
            FilledTonalButton(
                onClick = onShowOnMap,
                enabled = uiState.canShowOnMap,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = TouchTargetMinSize)
                    .testTag(OpenHikesTestTags.WALK_SHOW_ON_MAP)
            ) {
                Icon(
                    painter = painterResource(OpenHikesIcons.Map),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                Text(text = stringResource(R.string.walk_summary_show_on_map))
            }
        }
        if (uiState.isRouteChanged) {
            item(key = "trail_changed") {
                TrailChangedNotice()
            }
        }
        item(key = "stats") {
            WalkStatsCard(
                walk = walk,
                trailAscentMeters = uiState.trailAscentMeters,
                distanceUnit = uiState.distanceUnit,
                zoneId = zoneId
            )
        }
    }
}

@Composable
private fun WalkCompletionHeroCard(hikeTitle: String, tintHex: String, walk: HikeWalk, distanceUnit: DistanceUnit) {
    val accent = parseHexColorOrDefault(tintHex, MaterialTheme.colorScheme.primary).toOpaque()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(CardCornerRadius),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = hikeTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                WalkReasonChip(reason = walk.endReason)
            }
            WalkCompletionMetricsBlock(walk = walk, accent = accent, distanceUnit = distanceUnit)
        }
    }
}

@Composable
private fun WalkCompletionMetricsBlock(
    walk: HikeWalk,
    accent: androidx.compose.ui.graphics.Color,
    distanceUnit: DistanceUnit
) {
    val fraction = walk.coveredFraction.toFloat().coerceIn(0f, 1f)
    val percent = HikeMetricsFormatter.wholePercent(walk.coveredFraction)
    val uncovered = walk.uncoveredMeters
    val showUncovered = uncovered >= MINIMUM_UNCOVERED_DISPLAY_METERS
    val completionDescription = if (showUncovered) {
        pluralStringResource(
            R.plurals.walk_summary_completion_remaining_description,
            percent,
            percent,
            HikeMetricsFormatter.formatSpokenDistance(uncovered, distanceUnit)
        )
    } else {
        pluralStringResource(R.plurals.walk_summary_completion_description, percent, percent)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = completionDescription }
            .testTag(OpenHikesTestTags.WALK_COMPLETION),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = HikeMetricsFormatter.formatPercent(walk.coveredFraction),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        LinearProgressIndicator(
            progress = { fraction },
            color = accent,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier
                .fillMaxWidth()
                .height(ProgressBarHeight)
                .clip(CircleShape)
        )
        if (showUncovered) {
            Text(
                text = stringResource(
                    R.string.walk_summary_uncovered,
                    HikeMetricsFormatter.formatDistance(uncovered, distanceUnit)
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WalkReasonChip(reason: TrailWalkEndReason) {
    val label = when (reason) {
        TrailWalkEndReason.REACHED_END -> stringResource(R.string.walk_summary_reason_reached_end)
        TrailWalkEndReason.ABANDONED -> stringResource(R.string.walk_summary_reason_abandoned)
        TrailWalkEndReason.RECORDED -> stringResource(R.string.walk_summary_reason_recorded)
        TrailWalkEndReason.ROUTE_CHANGED -> stringResource(R.string.walk_summary_reason_route_changed)
        TrailWalkEndReason.ENDED -> stringResource(R.string.walk_summary_reason_ended)
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = CircleShape
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun TrailChangedNotice() {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(NoticeCornerRadius),
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag(OpenHikesTestTags.WALK_TRAIL_CHANGED)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CardPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Warning),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                text = stringResource(R.string.walk_summary_trail_changed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
private fun WalkStatsCard(walk: HikeWalk, trailAscentMeters: Double?, distanceUnit: DistanceUnit, zoneId: ZoneId) {
    val rows = buildList {
        add(
            stringResource(R.string.walk_summary_stat_active_time) to
                HikeMetricsFormatter.formatDuration(walk.activeSeconds)
        )
        add(
            stringResource(R.string.walk_summary_stat_furthest_point) to
                HikeMetricsFormatter.formatDistance(walk.furthestDistanceMeters, distanceUnit)
        )
        add(
            stringResource(R.string.walk_summary_stat_trail_distance) to
                HikeMetricsFormatter.formatDistance(walk.routeDistanceMeters, distanceUnit)
        )
        if (trailAscentMeters != null && trailAscentMeters > 0.0) {
            add(
                stringResource(R.string.walk_summary_stat_trail_ascent) to
                    HikeMetricsFormatter.formatElevation(trailAscentMeters, distanceUnit)
            )
        }
        add(
            stringResource(R.string.walk_summary_stat_started) to
                HikeMetricsFormatter.formatDateTime(walk.startedAtEpochMillis, zoneId = zoneId)
        )
        add(
            stringResource(R.string.walk_summary_stat_ended) to
                formatEndedTimestamp(walk.startedAtEpochMillis, walk.endedAtEpochMillis, zoneId)
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.walk_summary_section_stats),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(horizontal = 4.dp, vertical = 6.dp)
                .semantics { heading() }
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(CardCornerRadius),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                rows.forEachIndexed { index, (label, value) ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(horizontal = HorizontalPadding)
                        )
                    }
                    WalkStatRow(label = label, value = value)
                }
            }
        }
    }
}

@Composable
private fun WalkStatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = RowMinHeight)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = HorizontalPadding, vertical = 12.dp),
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
private fun MissingWalkState(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(HorizontalPadding)
    ) {
        Text(
            text = stringResource(R.string.walk_summary_not_found),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatEndedTimestamp(startedMillis: Long, endedMillis: Long, zoneId: ZoneId): String {
    val startDate = Instant.ofEpochMilli(startedMillis).atZone(zoneId).toLocalDate()
    val endDate = Instant.ofEpochMilli(endedMillis).atZone(zoneId).toLocalDate()
    return if (startDate == endDate) {
        HikeMetricsFormatter.formatTimeOfDay(endedMillis, zoneId = zoneId)
    } else {
        HikeMetricsFormatter.formatDateTime(endedMillis, zoneId = zoneId)
    }
}
