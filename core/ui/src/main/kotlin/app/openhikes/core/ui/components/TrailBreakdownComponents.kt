package app.openhikes.core.ui.components

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.RouteShade
import app.openhikes.core.model.TrailBreakdown
import app.openhikes.core.model.TrailCategory
import app.openhikes.core.model.TrailDifficulty
import app.openhikes.core.model.TrailDifficultyBreakdown
import app.openhikes.core.model.TrailSurface
import app.openhikes.core.model.TrailSurfaceBreakdown
import app.openhikes.core.ui.R
import app.openhikes.core.ui.format.HikeMetricsFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags
import app.openhikes.core.ui.theme.parseHexColorOrDefault
import kotlin.math.min
import kotlin.math.round

private val BreakdownCardCorner = 24.dp
private val BreakdownCardPadding = 16.dp
private val BreakdownBarHeight = 14.dp
private val SwatchDiameter = 10.dp
private val LegendRowSpacing = 10.dp
private const val UNMAPPED_ALPHA = 0.30f

private val SurfacePavedColor = Color(0xFF2563EB)
private val SurfaceGravelColor = Color(0xFFEA580C)
private val SurfaceGroundColor = Color(0xFF8D5524)
private val SurfaceRockColor = Color(0xFF7C3AED)
private val NeutralUnknownColor = Color(0xFF757575)

/**
 * Shared visual presentation for a [TrailCategory] on breakdown bars and legend rows.
 */
data class TrailCategoryPresentation(val displayName: String, val summary: String, val color: Color)

/**
 * Material 3 Expressive Surface breakdown card displaying the stacked capsule bar, legend rows,
 * and OpenStreetMap coverage footnote.
 */
@Composable
fun TrailSurfaceSection(breakdown: TrailSurfaceBreakdown, distanceUnit: DistanceUnit, modifier: Modifier = Modifier) {
    TrailBreakdownSection(
        breakdown = breakdown,
        title = stringResource(R.string.trail_surface_section_title),
        barContentDescription = stringResource(R.string.trail_surface_bar_description),
        source = stringResource(R.string.trail_surface_source),
        barTestTag = OpenHikesTestTags.SURFACE_BAR,
        distanceUnit = distanceUnit,
        presentationOf = { surface -> surfacePresentation(surface) },
        modifier = modifier
    )
}

/**
 * Material 3 Expressive SAC Difficulty breakdown card displaying the stacked capsule bar colored
 * on the shared [RouteShade] scale, legend rows, and OpenStreetMap coverage footnote.
 */
@Composable
fun TrailDifficultySection(
    breakdown: TrailDifficultyBreakdown,
    distanceUnit: DistanceUnit,
    modifier: Modifier = Modifier
) {
    TrailBreakdownSection(
        breakdown = breakdown,
        title = stringResource(R.string.trail_difficulty_section_title),
        barContentDescription = stringResource(R.string.trail_difficulty_bar_description),
        source = stringResource(R.string.trail_difficulty_source),
        barTestTag = OpenHikesTestTags.DIFFICULTY_BAR,
        distanceUnit = distanceUnit,
        presentationOf = { difficulty -> difficultyPresentation(difficulty) },
        modifier = modifier
    )
}

@Composable
private fun <C : TrailCategory> TrailBreakdownSection(
    breakdown: TrailBreakdown<C>,
    title: String,
    barContentDescription: String,
    source: String,
    barTestTag: String,
    distanceUnit: DistanceUnit,
    presentationOf: @Composable (C) -> TrailCategoryPresentation,
    modifier: Modifier = Modifier
) {
    if (breakdown.isEmpty) return
    val presentedShares = breakdown.shares.map { share -> share to presentationOf(share.category) }
    val spokenShares = presentedShares.map { (share, presentation) ->
        stringResource(
            R.string.trail_breakdown_share_spoken,
            HikeMetricsFormatter.formatPercent(share.fraction),
            presentation.displayName
        )
    }
    val spokenSummary = formatSpokenList(spokenShares)
    val footnote = breakdownFootnote(source = source, surveyedFraction = breakdown.surveyedFraction)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(horizontal = 4.dp, vertical = 6.dp)
                .semantics { heading() }
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(BreakdownCardCorner),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(BreakdownCardPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TrailBreakdownBar(
                    shares = presentedShares,
                    barContentDescription = barContentDescription,
                    spokenSummary = spokenSummary,
                    barTestTag = barTestTag
                )
                Column(verticalArrangement = Arrangement.spacedBy(LegendRowSpacing)) {
                    presentedShares.forEach { (share, presentation) ->
                        TrailBreakdownLegendRow(
                            share = share,
                            presentation = presentation,
                            distanceUnit = distanceUnit
                        )
                    }
                }
                Text(
                    text = footnote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun breakdownFootnote(source: String, surveyedFraction: Double): String =
    if (surveyedFraction >= TrailBreakdown.FULL_COVERAGE_THRESHOLD) {
        stringResource(R.string.trail_breakdown_footnote_full, source)
    } else {
        stringResource(
            R.string.trail_breakdown_footnote_partial,
            source,
            HikeMetricsFormatter.formatPercent(surveyedFraction)
        )
    }

@Composable
private fun <C : TrailCategory> TrailBreakdownBar(
    shares: List<Pair<TrailBreakdown.Share<C>, TrailCategoryPresentation>>,
    barContentDescription: String,
    spokenSummary: String,
    barTestTag: String
) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(BreakdownBarHeight)
            .clip(CircleShape)
            .background(trackColor)
            .testTag(barTestTag)
            .clearAndSetSemantics {
                contentDescription = barContentDescription
                stateDescription = spokenSummary
            }
    ) {
        val totalWidth = size.width.coerceAtLeast(0f)
        var cursor = 0f
        var remaining = totalWidth
        shares.forEachIndexed { index, (share, presentation) ->
            val width = if (index == shares.lastIndex) {
                remaining
            } else {
                min(round(totalWidth * share.fraction.toFloat()), remaining).coerceAtLeast(0f)
            }
            if (width > 0f) {
                drawRect(
                    color = presentation.color,
                    topLeft = Offset(x = cursor, y = 0f),
                    size = Size(width = width, height = size.height)
                )
            }
            cursor += width
            remaining = (remaining - width).coerceAtLeast(0f)
        }
    }
}

@Composable
private fun <C : TrailCategory> TrailBreakdownLegendRow(
    share: TrailBreakdown.Share<C>,
    presentation: TrailCategoryPresentation,
    distanceUnit: DistanceUnit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(SwatchDiameter)
                .clip(CircleShape)
                .background(presentation.color)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = presentation.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = presentation.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = HikeMetricsFormatter.formatPercent(share.fraction),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum"
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = HikeMetricsFormatter.formatDistance(share.meters, distanceUnit),
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

fun routeShadeColor(shade: RouteShade): Color = parseHexColorOrDefault(shade.hexColor, NeutralUnknownColor)

@Composable
private fun surfacePresentation(surface: TrailSurface): TrailCategoryPresentation = when (surface) {
    TrailSurface.PAVED -> TrailCategoryPresentation(
        displayName = stringResource(R.string.trail_surface_paved),
        summary = stringResource(R.string.trail_surface_paved_summary),
        color = SurfacePavedColor
    )
    TrailSurface.GRAVEL -> TrailCategoryPresentation(
        displayName = stringResource(R.string.trail_surface_gravel),
        summary = stringResource(R.string.trail_surface_gravel_summary),
        color = SurfaceGravelColor
    )
    TrailSurface.GROUND -> TrailCategoryPresentation(
        displayName = stringResource(R.string.trail_surface_ground),
        summary = stringResource(R.string.trail_surface_ground_summary),
        color = SurfaceGroundColor
    )
    TrailSurface.ROCK -> TrailCategoryPresentation(
        displayName = stringResource(R.string.trail_surface_rock),
        summary = stringResource(R.string.trail_surface_rock_summary),
        color = SurfaceRockColor
    )
    TrailSurface.UNKNOWN -> TrailCategoryPresentation(
        displayName = stringResource(R.string.trail_surface_unknown),
        summary = stringResource(R.string.trail_surface_unknown_summary),
        color = NeutralUnknownColor
    )
    TrailSurface.UNMAPPED -> TrailCategoryPresentation(
        displayName = stringResource(R.string.trail_surface_unmapped),
        summary = stringResource(R.string.trail_surface_unmapped_summary),
        color = NeutralUnknownColor.copy(alpha = UNMAPPED_ALPHA)
    )
}

@Composable
private fun difficultyPresentation(difficulty: TrailDifficulty): TrailCategoryPresentation {
    val color = difficulty.shade?.let { routeShadeColor(it) } ?: if (difficulty == TrailDifficulty.UNMAPPED) {
        NeutralUnknownColor.copy(alpha = UNMAPPED_ALPHA)
    } else {
        NeutralUnknownColor
    }
    return when (difficulty) {
        TrailDifficulty.HIKING -> TrailCategoryPresentation(
            displayName = stringResource(R.string.trail_difficulty_hiking),
            summary = stringResource(R.string.trail_difficulty_hiking_summary),
            color = color
        )
        TrailDifficulty.MOUNTAIN_HIKING -> TrailCategoryPresentation(
            displayName = stringResource(R.string.trail_difficulty_mountain_hiking),
            summary = stringResource(R.string.trail_difficulty_mountain_hiking_summary),
            color = color
        )
        TrailDifficulty.DEMANDING_MOUNTAIN_HIKING -> TrailCategoryPresentation(
            displayName = stringResource(R.string.trail_difficulty_demanding_mountain_hiking),
            summary = stringResource(R.string.trail_difficulty_demanding_mountain_hiking_summary),
            color = color
        )
        TrailDifficulty.ALPINE_HIKING -> TrailCategoryPresentation(
            displayName = stringResource(R.string.trail_difficulty_alpine_hiking),
            summary = stringResource(R.string.trail_difficulty_alpine_hiking_summary),
            color = color
        )
        TrailDifficulty.DEMANDING_ALPINE_HIKING -> TrailCategoryPresentation(
            displayName = stringResource(R.string.trail_difficulty_demanding_alpine_hiking),
            summary = stringResource(R.string.trail_difficulty_demanding_alpine_hiking_summary),
            color = color
        )
        TrailDifficulty.DIFFICULT_ALPINE_HIKING -> TrailCategoryPresentation(
            displayName = stringResource(R.string.trail_difficulty_difficult_alpine_hiking),
            summary = stringResource(R.string.trail_difficulty_difficult_alpine_hiking_summary),
            color = color
        )
        TrailDifficulty.UNKNOWN -> TrailCategoryPresentation(
            displayName = stringResource(R.string.trail_difficulty_unknown),
            summary = stringResource(R.string.trail_difficulty_unknown_summary),
            color = color
        )
        TrailDifficulty.UNMAPPED -> TrailCategoryPresentation(
            displayName = stringResource(R.string.trail_difficulty_unmapped),
            summary = stringResource(R.string.trail_difficulty_unmapped_summary),
            color = color
        )
    }
}

private fun formatSpokenList(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    2 -> "${items[0]} and ${items[1]}"
    else -> "${items.dropLast(1).joinToString(", ")}, and ${items.last()}"
}
