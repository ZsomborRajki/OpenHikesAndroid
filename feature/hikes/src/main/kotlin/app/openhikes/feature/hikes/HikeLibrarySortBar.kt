package app.openhikes.feature.hikes

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.HikeListSort
import app.openhikes.core.ui.testing.OpenHikesTestTags

private val SortBarTouchMinHeight = 48.dp
private val SortBarGap = 8.dp

/**
 * Material 3 Expressive control bar below the library header offering the active sort menu
 * (or Done button while reordering) alongside the Totals entry point.
 */
@Composable
internal fun HikeLibrarySortBar(
    sort: HikeListSort,
    isCustomOrder: Boolean,
    isReordering: Boolean,
    canReorder: Boolean,
    onSelectSort: (HikeListSort) -> Unit,
    onEnterReorderMode: () -> Unit,
    onExitReorderMode: () -> Unit,
    onOpenTotals: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (isReordering) {
            Button(
                onClick = onExitReorderMode,
                modifier = Modifier
                    .defaultMinSize(minHeight = SortBarTouchMinHeight)
                    .testTag(OpenHikesTestTags.HIKE_ORDER_DONE_BUTTON)
            ) {
                Icon(
                    painter = painterResource(OpenHikesIcons.Check),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                Text(text = stringResource(R.string.hikes_reorder_done))
            }
            Spacer(modifier = Modifier.width(SortBarGap))
            Text(
                text = stringResource(R.string.hikes_reorder_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        } else {
            SortOrderMenuButton(
                sort = sort,
                isCustomOrder = isCustomOrder,
                canReorder = canReorder,
                onSelectSort = onSelectSort,
                onEnterReorderMode = onEnterReorderMode
            )
            Spacer(modifier = Modifier.width(SortBarGap))
            FilledTonalButton(
                onClick = onOpenTotals,
                modifier = Modifier
                    .defaultMinSize(minHeight = SortBarTouchMinHeight)
                    .testTag(OpenHikesTestTags.LIBRARY_TOTALS_BUTTON)
            ) {
                Icon(
                    painter = painterResource(OpenHikesIcons.BarChart),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                Text(
                    text = stringResource(R.string.hikes_totals_button),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SortOrderMenuButton(
    sort: HikeListSort,
    isCustomOrder: Boolean,
    canReorder: Boolean,
    onSelectSort: (HikeListSort) -> Unit,
    onEnterReorderMode: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = if (isCustomOrder) {
        stringResource(R.string.hikes_sort_custom)
    } else {
        stringResource(sortLabelRes(sort))
    }
    val sortDescription = stringResource(R.string.hikes_sort_content_description)

    Box {
        FilledTonalButton(
            onClick = { expanded = true },
            modifier = Modifier
                .defaultMinSize(minHeight = SortBarTouchMinHeight)
                .semantics {
                    contentDescription = sortDescription
                    stateDescription = currentLabel
                }
                .testTag(OpenHikesTestTags.HIKE_ORDER_BUTTON)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Sort),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize)
            )
            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
            Text(
                text = currentLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        SortOrderDropdownMenu(
            expanded = expanded,
            sort = sort,
            isCustomOrder = isCustomOrder,
            canReorder = canReorder,
            onDismiss = { expanded = false },
            onSelectSort = { option ->
                expanded = false
                onSelectSort(option)
            },
            onEnterReorderMode = {
                expanded = false
                onEnterReorderMode()
            }
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun SortOrderDropdownMenu(
    expanded: Boolean,
    sort: HikeListSort,
    isCustomOrder: Boolean,
    canReorder: Boolean,
    onDismiss: () -> Unit,
    onSelectSort: (HikeListSort) -> Unit,
    onEnterReorderMode: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        HikeListSort.menuOrder.forEach { option ->
            val isSelected = !isCustomOrder && option == sort
            DropdownMenuItem(
                text = { Text(text = stringResource(sortLabelRes(option))) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(sortIconRes(option)),
                        contentDescription = null
                    )
                },
                trailingIcon = if (isSelected) {
                    {
                        Icon(
                            painter = painterResource(OpenHikesIcons.Check),
                            contentDescription = null
                        )
                    }
                } else {
                    null
                },
                onClick = { onSelectSort(option) },
                modifier = Modifier.testTag(OpenHikesTestTags.hikeSortOption(option.rawValue))
            )
        }
        if (canReorder) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.hikes_reorder_action)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(OpenHikesIcons.SwapVert),
                        contentDescription = null
                    )
                },
                onClick = onEnterReorderMode,
                modifier = Modifier.testTag(OpenHikesTestTags.HIKE_ORDER_REORDER_ACTION)
            )
        }
    }
}

@StringRes
internal fun sortLabelRes(sort: HikeListSort): Int = when (sort) {
    HikeListSort.NEWEST -> R.string.hikes_sort_newest
    HikeListSort.ALPHABETICAL -> R.string.hikes_sort_alphabetical
    HikeListSort.LONGEST -> R.string.hikes_sort_longest
    HikeListSort.MOST_WALKED -> R.string.hikes_sort_most_walked
    HikeListSort.HILLIEST -> R.string.hikes_sort_hilliest
}

@DrawableRes
private fun sortIconRes(sort: HikeListSort): Int = when (sort) {
    HikeListSort.NEWEST -> OpenHikesIcons.CalendarToday
    HikeListSort.ALPHABETICAL -> OpenHikesIcons.SortByAlpha
    HikeListSort.LONGEST -> OpenHikesIcons.Straighten
    HikeListSort.MOST_WALKED -> OpenHikesIcons.Hiking
    HikeListSort.HILLIEST -> OpenHikesIcons.Landscape
}
