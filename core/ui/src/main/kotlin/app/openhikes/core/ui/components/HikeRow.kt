package app.openhikes.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.ui.R
import app.openhikes.core.ui.format.HikeMetricsFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags
import app.openhikes.core.ui.theme.parseHexColorOrDefault
import app.openhikes.core.ui.theme.toOpaque

private val RowCornerRadius = 20.dp
private val RowMinHeight = 64.dp
private val RowPaddingHorizontal = 12.dp
private val RowPaddingVertical = 10.dp
private val RowItemGap = 12.dp
private val BadgeSize = 40.dp
private val BadgeIconSize = 20.dp
private val TouchTargetMinSize = 48.dp
private val SelectedBorderWidth = 1.5.dp
private const val SELECTED_CONTAINER_ALPHA = 0.14f

/**
 * Reusable hike list item with colored trail glyph, merged metadata semantics,
 * and an accessible delete action button.
 */
@Composable
fun HikeRow(
    hike: HikeSummary,
    distanceUnit: DistanceUnit,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val routeTint = parseHexColorOrDefault(hike.tintHex, MaterialTheme.colorScheme.primary).toOpaque()
    val containerColor = if (isSelected) {
        routeTint.copy(alpha = SELECTED_CONTAINER_ALPHA)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val border = if (isSelected) BorderStroke(SelectedBorderWidth, routeTint) else null
    val subtitle = HikeMetricsFormatter.formatHikeSubtitle(
        distanceMeters = hike.distanceMeters,
        dateEpochMillis = hike.dateEpochMillis,
        unit = distanceUnit
    )

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(RowCornerRadius),
        border = border,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = RowMinHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HikeRowSummaryButton(
                hike = hike,
                subtitle = subtitle,
                routeTint = routeTint,
                isSelected = isSelected,
                onClick = onClick,
                modifier = Modifier.weight(1f)
            )
            HikeRowDeleteButton(title = hike.displayTitle, hikeId = hike.id.value, onClick = onDeleteClick)
        }
    }
}

@Composable
private fun HikeRowSummaryButton(
    hike: HikeSummary,
    subtitle: String,
    routeTint: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = TouchTargetMinSize)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { selected = isSelected }
            .padding(
                start = RowPaddingHorizontal,
                top = RowPaddingVertical,
                bottom = RowPaddingVertical
            )
            .testTag(OpenHikesTestTags.hikeRow(hike.id.value)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RowItemGap)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(BadgeSize)
                .clip(CircleShape)
                .background(routeTint)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Hiking),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(BadgeIconSize)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = hike.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HikeRowDeleteButton(title: String, hikeId: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .padding(end = 4.dp)
            .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
            .testTag(OpenHikesTestTags.hikeRowDelete(hikeId))
    ) {
        Icon(
            painter = painterResource(OpenHikesIcons.Delete),
            contentDescription = stringResource(R.string.hike_row_delete_description, title),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
