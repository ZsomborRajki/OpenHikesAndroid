package app.openhikes.feature.hikes

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.LibraryRecordCandidate
import app.openhikes.core.model.LibraryTotals
import app.openhikes.core.ui.format.HikeMetricsFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags

private val TotalsHorizontalPadding = 16.dp
private val TotalsSectionSpacing = 24.dp
private val TotalsHeadingSpacing = 12.dp
private val TotalsCardCornerRadius = 20.dp
private val TotalsCardPadding = 16.dp
private val TotalsRowPaddingVertical = 12.dp
private val TotalsTouchTargetMinSize = 48.dp
private val TotalsTileGap = 10.dp
private val TotalsErrorIconSize = 32.dp
private val TotalsFootnoteSpacing = 8.dp
private val TotalsListBottomPadding = 24.dp

/**
 * Stateful Library Totals destination backed by [LibraryTotalsViewModel].
 */
@Composable
fun LibraryTotalsRoute(
    onBack: () -> Unit,
    onOpenHike: (HikeId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryTotalsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LibraryTotalsScreen(
        uiState = uiState,
        onBack = onBack,
        onRetry = viewModel::onRetry,
        onOpenHike = { hikeId -> viewModel.onOpenRecordHike(hikeId, onOpenHike) },
        modifier = modifier
    )
}

/**
 * Stateless Library Totals screen shared across runtime, tests, and screenshot suites.
 */
@Composable
fun LibraryTotalsScreen(
    uiState: LibraryTotalsUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenHike: (HikeId) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        LibraryTotalsTopBar(onBack = onBack)
        when (val state = uiState.loadState) {
            LibraryTotalsLoadState.Loading -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    CircularProgressIndicator()
                }
            }
            LibraryTotalsLoadState.Error -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = TotalsHorizontalPadding, vertical = TotalsSectionSpacing)
                ) {
                    LibraryTotalsErrorCard(onRetry = onRetry)
                }
            }
            is LibraryTotalsLoadState.Loaded -> {
                LibraryTotalsContentList(
                    totals = state.totals,
                    distanceUnit = uiState.distanceUnit,
                    onOpenHike = onOpenHike,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun LibraryTotalsTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .defaultMinSize(minWidth = TotalsTouchTargetMinSize, minHeight = TotalsTouchTargetMinSize)
                .testTag(OpenHikesTestTags.LIBRARY_TOTALS_BACK)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.ArrowBack),
                contentDescription = stringResource(R.string.library_totals_back_description)
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.library_totals_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }
        )
    }
}

@Composable
private fun LibraryTotalsErrorCard(onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(TotalsCardCornerRadius),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Warning),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(TotalsErrorIconSize)
            )
            Text(
                text = stringResource(R.string.library_totals_error_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.library_totals_error_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(
                onClick = onRetry,
                modifier = Modifier
                    .defaultMinSize(minHeight = TotalsTouchTargetMinSize)
                    .testTag(OpenHikesTestTags.LIBRARY_TOTALS_RETRY)
            ) {
                Text(text = stringResource(R.string.library_totals_retry))
            }
        }
    }
}

@Composable
private fun LibraryTotalsContentList(
    totals: LibraryTotals,
    distanceUnit: DistanceUnit,
    onOpenHike: (HikeId) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag(OpenHikesTestTags.LIBRARY_TOTALS_SCREEN),
        contentPadding = PaddingValues(
            start = TotalsHorizontalPadding,
            end = TotalsHorizontalPadding,
            top = 4.dp,
            bottom = TotalsListBottomPadding
        ),
        verticalArrangement = Arrangement.spacedBy(TotalsSectionSpacing)
    ) {
        item(key = "this_year") {
            TotalsSection(title = totals.year.toString()) {
                TotalsFiguresBlock(
                    figures = totals.thisYear,
                    comparedWith = totals.lastYear,
                    distanceUnit = distanceUnit
                )
            }
        }
        item(key = "month_chart") {
            LibraryMonthChart(totals = totals, distanceUnit = distanceUnit)
        }
        item(key = "all_time") {
            TotalsSection(title = stringResource(R.string.library_totals_all_time)) {
                TotalsFiguresBlock(
                    figures = totals.allTime,
                    comparedWith = null,
                    distanceUnit = distanceUnit
                )
            }
        }
        if (totals.longest != null) {
            item(key = "records") {
                TotalsSection(title = stringResource(R.string.library_totals_records)) {
                    TotalsRecordsCard(
                        totals = totals,
                        distanceUnit = distanceUnit,
                        onOpenHike = onOpenHike
                    )
                }
            }
        }
        item(key = "footnotes") {
            TotalsFootnotes(showRedrawnNote = totals.allTime.outingsWithoutClimb > 0)
        }
    }
}

@Composable
private fun TotalsSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .semantics { heading() }
        )
        Spacer(modifier = Modifier.height(TotalsHeadingSpacing))
        content()
    }
}

@Composable
private fun TotalsFiguresBlock(
    figures: LibraryTotals.Figures,
    comparedWith: LibraryTotals.Figures?,
    distanceUnit: DistanceUnit
) {
    Column(verticalArrangement = Arrangement.spacedBy(TotalsTileGap)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TotalsTileGap)
        ) {
            TotalsHeadlineTile(
                label = stringResource(R.string.library_totals_stat_distance),
                value = HikeMetricsFormatter.formatDistance(figures.distanceMeters, distanceUnit),
                modifier = Modifier.weight(1f)
            )
            TotalsHeadlineTile(
                label = stringResource(R.string.library_totals_stat_climb),
                value = HikeMetricsFormatter.formatElevation(figures.climbMeters, distanceUnit),
                modifier = Modifier.weight(1f)
            )
        }
        TotalsHeadlineTile(
            label = stringResource(R.string.library_totals_stat_moving_time),
            value = HikeMetricsFormatter.formatDuration(figures.movingSeconds),
            modifier = Modifier.fillMaxWidth()
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(TotalsCardCornerRadius),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = TotalsCardPadding, vertical = 4.dp)) {
                TotalsKeyValueRow(
                    label = stringResource(R.string.library_totals_stat_activities),
                    value = HikeMetricsFormatter.formatCount(figures.outings)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TotalsKeyValueRow(
                    label = stringResource(R.string.library_totals_stat_trails),
                    value = HikeMetricsFormatter.formatCount(figures.trails)
                )
                if (comparedWith != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TotalsKeyValueRow(
                        label = stringResource(R.string.library_totals_stat_last_year),
                        value = HikeMetricsFormatter.formatDistance(comparedWith.distanceMeters, distanceUnit)
                    )
                }
            }
        }
    }
}

@Composable
private fun TotalsHeadlineTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(TotalsCardCornerRadius),
        modifier = modifier.semantics(mergeDescendants = true) {}
    ) {
        Column(modifier = Modifier.padding(TotalsCardPadding)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TotalsKeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(vertical = TotalsRowPaddingVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun TotalsRecordsCard(totals: LibraryTotals, distanceUnit: DistanceUnit, onOpenHike: (HikeId) -> Unit) {
    val rows = buildRecordRowItems(totals = totals, distanceUnit = distanceUnit)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(TotalsCardCornerRadius),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = TotalsCardPadding, vertical = 4.dp)) {
            rows.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                TotalsRecordRow(
                    item = item,
                    onClick = { onOpenHike(item.candidate.hikeId) }
                )
            }
        }
    }
}

@Composable
private fun buildRecordRowItems(totals: LibraryTotals, distanceUnit: DistanceUnit): List<RecordRowItem> = buildList {
    totals.longest?.let { candidate ->
        add(
            RecordRowItem(
                label = stringResource(R.string.library_totals_record_longest),
                value = stringResource(
                    R.string.library_totals_record_value,
                    candidate.title,
                    HikeMetricsFormatter.formatDistance(candidate.distanceMeters, distanceUnit)
                ),
                tag = OpenHikesTestTags.LIBRARY_RECORD_LONGEST,
                candidate = candidate
            )
        )
    }
    totals.highest?.let { candidate ->
        add(
            RecordRowItem(
                label = stringResource(R.string.library_totals_record_highest),
                value = stringResource(
                    R.string.library_totals_record_value,
                    candidate.title,
                    HikeMetricsFormatter.formatElevation(candidate.highestMeters ?: 0.0, distanceUnit)
                ),
                tag = OpenHikesTestTags.LIBRARY_RECORD_HIGHEST,
                candidate = candidate
            )
        )
    }
    totals.steepest?.let { candidate ->
        val climb = HikeMetricsFormatter.formatElevation(candidate.climbMeters ?: 0.0, distanceUnit)
        val climbUp = stringResource(R.string.library_totals_record_steepest_up, climb)
        add(
            RecordRowItem(
                label = stringResource(R.string.library_totals_record_steepest),
                value = stringResource(R.string.library_totals_record_value, candidate.title, climbUp),
                tag = OpenHikesTestTags.LIBRARY_RECORD_STEEPEST,
                candidate = candidate
            )
        )
    }
}

@Composable
private fun TotalsRecordRow(item: RecordRowItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = TotalsTouchTargetMinSize)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(vertical = TotalsRowPaddingVertical)
            .testTag(item.tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = item.value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            painter = painterResource(OpenHikesIcons.ChevronRight),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TotalsFootnotes(showRedrawnNote: Boolean) {
    Column(
        verticalArrangement = Arrangement.spacedBy(TotalsFootnoteSpacing),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.library_totals_footnote_counting),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (showRedrawnNote) {
            Text(
                text = stringResource(R.string.library_totals_footnote_redrawn),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private data class RecordRowItem(
    val label: String,
    val value: String,
    val tag: String,
    val candidate: LibraryRecordCandidate
)
