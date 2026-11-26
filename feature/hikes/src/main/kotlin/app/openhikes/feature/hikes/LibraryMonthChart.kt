package app.openhikes.feature.hikes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.LibraryTotals
import app.openhikes.core.ui.format.HikeMetricsFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

private val ChartCardCornerRadius = 20.dp
private val ChartCardPadding = 16.dp
private val ChartPlotHeight = 160.dp
private val LegendDotSize = 10.dp
private val BarTopCornerRadius = 4.dp
private val ChartGridStrokeWidth = 1.dp
private const val LAST_YEAR_BAR_ALPHA = 0.45f
private const val GRIDLINE_ALPHA = 0.18f
private const val GROUP_INNER_PADDING_FRACTION = 0.12f
private const val PAIR_GAP_FRACTION = 0.08f
private const val MIN_BAR_HEIGHT_PX = 2f
private const val DEFAULT_CEILING = 10.0
private const val NICE_STEP_SMALL = 2.0
private const val NICE_STEP_MID = 5.0
private const val NICE_STEP_LARGE = 10.0
private const val BASE_TEN = 10.0
private const val HALF = 0.5f

/**
 * Grouped monthly distance bar chart comparing [LibraryTotals.year] against
 * [LibraryTotals.previousYear] with legend, unit axis label, and spoken accessibility summary.
 */
@Composable
internal fun LibraryMonthChart(totals: LibraryTotals, distanceUnit: DistanceUnit, modifier: Modifier = Modifier) {
    val configuration = LocalConfiguration.current
    val locale = remember(configuration) {
        configuration.locales.get(0) ?: Locale.getDefault()
    }
    val chartData = remember(totals, distanceUnit, locale) {
        buildMonthChartData(totals = totals, distanceUnit = distanceUnit, locale = locale)
    }
    val chartDescription = stringResource(
        R.string.library_totals_chart_description,
        totals.year.toString(),
        totals.previousYear.toString()
    ) + ": ${totals.year} ${chartData.thisYearSummary}, ${totals.previousYear} ${chartData.lastYearSummary}."

    val thisYearColor = MaterialTheme.colorScheme.primary
    val lastYearColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = LAST_YEAR_BAR_ALPHA)
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = GRIDLINE_ALPHA)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(ChartCardCornerRadius),
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = chartDescription }
            .testTag(OpenHikesTestTags.LIBRARY_MONTH_CHART)
    ) {
        Column(modifier = Modifier.padding(ChartCardPadding)) {
            MonthChartLegendRow(
                previousYear = totals.previousYear,
                currentYear = totals.year,
                previousYearColor = lastYearColor,
                currentYearColor = thisYearColor,
                ceilingLabel = "${chartData.ceiling.roundToInt()} ${chartData.unitLabel}"
            )
            Spacer(modifier = Modifier.height(12.dp))
            MonthBarsCanvas(
                values = chartData.values,
                ceiling = chartData.ceiling,
                previousYearColor = lastYearColor,
                currentYearColor = thisYearColor,
                gridColor = gridColor
            )
            Spacer(modifier = Modifier.height(6.dp))
            MonthAxisLabelsRow(values = chartData.values)
        }
    }
}

@Composable
private fun MonthAxisLabelsRow(values: List<MonthBarValues>) {
    Row(modifier = Modifier.fillMaxWidth()) {
        values.forEach { entry ->
            Text(
                text = entry.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MonthChartLegendRow(
    previousYear: Int,
    currentYear: Int,
    previousYearColor: Color,
    currentYearColor: Color,
    ceilingLabel: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LegendItem(label = previousYear.toString(), color = previousYearColor)
            LegendItem(label = currentYear.toString(), color = currentYearColor)
        }
        Text(
            text = ceilingLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(LegendDotSize)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MonthBarsCanvas(
    values: List<MonthBarValues>,
    ceiling: Double,
    previousYearColor: Color,
    currentYearColor: Color,
    gridColor: Color
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(ChartPlotHeight)
    ) {
        val strokePx = ChartGridStrokeWidth.toPx()
        val cornerPx = BarTopCornerRadius.toPx()
        val chartHeight = size.height
        val chartWidth = size.width
        val midY = chartHeight * HALF

        drawLine(gridColor, Offset(0f, 0f), Offset(chartWidth, 0f), strokeWidth = strokePx)
        drawLine(gridColor, Offset(0f, midY), Offset(chartWidth, midY), strokeWidth = strokePx)
        drawLine(gridColor, Offset(0f, chartHeight), Offset(chartWidth, chartHeight), strokeWidth = strokePx)

        if (values.isEmpty() || ceiling <= 0.0) return@Canvas
        val slotWidth = chartWidth / values.size
        val innerPadding = slotWidth * GROUP_INNER_PADDING_FRACTION
        val pairGap = slotWidth * PAIR_GAP_FRACTION
        val barWidth = ((slotWidth - innerPadding * 2f - pairGap) / 2f).coerceAtLeast(1f)

        values.forEachIndexed { index, entry ->
            val slotStart = index * slotWidth + innerPadding
            drawTopRoundedBar(
                geometry = BarGeometry(slotStart, barWidth, chartHeight, cornerPx),
                value = entry.lastYear,
                ceiling = ceiling,
                color = previousYearColor
            )
            drawTopRoundedBar(
                geometry = BarGeometry(slotStart + barWidth + pairGap, barWidth, chartHeight, cornerPx),
                value = entry.thisYear,
                ceiling = ceiling,
                color = currentYearColor
            )
        }
    }
}

private fun DrawScope.drawTopRoundedBar(geometry: BarGeometry, value: Double, ceiling: Double, color: Color) {
    if (value <= 0.0) return
    val ratio = (value / ceiling).toFloat().coerceIn(0f, 1f)
    val barHeight = max(MIN_BAR_HEIGHT_PX, geometry.chartHeight * ratio)
    val top = (geometry.chartHeight - barHeight).coerceAtLeast(0f)
    val clampedRadius = geometry.cornerPx.coerceAtMost(barHeight / 2f).coerceAtMost(geometry.width / 2f)
    val corner = CornerRadius(clampedRadius, clampedRadius)
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(offset = Offset(geometry.left, top), size = Size(geometry.width, barHeight)),
                topLeft = corner,
                topRight = corner,
                bottomRight = CornerRadius.Zero,
                bottomLeft = CornerRadius.Zero
            )
        )
    }
    drawPath(path = path, color = color)
}

private fun buildMonthChartData(totals: LibraryTotals, distanceUnit: DistanceUnit, locale: Locale): MonthChartData {
    val values = totals.monthColumns.map { column ->
        MonthBarValues(
            label = Month.of(column.monthNumber).getDisplayName(TextStyle.NARROW, locale),
            lastYear = column.lastYear?.distanceMeters
                ?.let { HikeMetricsFormatter.distanceInMajorUnit(it, distanceUnit) } ?: 0.0,
            thisYear = column.thisYear?.distanceMeters
                ?.let { HikeMetricsFormatter.distanceInMajorUnit(it, distanceUnit) } ?: 0.0
        )
    }
    val peak = values.maxOfOrNull { max(it.thisYear, it.lastYear) } ?: 0.0
    return MonthChartData(
        values = values,
        ceiling = niceChartCeiling(peak),
        unitLabel = HikeMetricsFormatter.majorDistanceUnitLabel(distanceUnit),
        thisYearSummary = HikeMetricsFormatter.formatDistance(totals.thisYear.distanceMeters, distanceUnit, locale),
        lastYearSummary = HikeMetricsFormatter.formatDistance(totals.lastYear.distanceMeters, distanceUnit, locale)
    )
}

internal fun niceChartCeiling(peak: Double): Double {
    if (!peak.isFinite() || peak <= 0.0) return DEFAULT_CEILING
    val magnitude = BASE_TEN.pow(floor(log10(peak)))
    val normalized = peak / magnitude
    val niceFactor = when {
        normalized <= 1.0 -> 1.0
        normalized <= NICE_STEP_SMALL -> NICE_STEP_SMALL
        normalized <= NICE_STEP_MID -> NICE_STEP_MID
        else -> NICE_STEP_LARGE
    }
    return ceil(niceFactor * magnitude)
}

private data class MonthChartData(
    val values: List<MonthBarValues>,
    val ceiling: Double,
    val unitLabel: String,
    val thisYearSummary: String,
    val lastYearSummary: String
)

private data class MonthBarValues(val label: String, val lastYear: Double, val thisYear: Double)

private data class BarGeometry(val left: Float, val width: Float, val chartHeight: Float, val cornerPx: Float)
