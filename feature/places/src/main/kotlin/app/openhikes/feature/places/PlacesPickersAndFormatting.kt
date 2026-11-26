package app.openhikes.feature.places

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.PlaceSearchOutage
import app.openhikes.core.model.TrailPlaceFact
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.TrailPointQuery
import app.openhikes.core.ui.components.TrailPlaceCoordinates
import app.openhikes.core.ui.components.chipLabelRes
import app.openhikes.core.ui.components.iconRes
import app.openhikes.core.ui.components.labelRes
import app.openhikes.core.ui.components.tintColor
import app.openhikes.core.ui.format.HikeMetricsFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags

@Composable
internal fun TrailPlaceKindChipsRow(
    shownSymbols: Set<TrailPlaceSymbol>,
    onToggleSymbol: (TrailPlaceSymbol, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        TrailPointQuery.searchableSymbols.forEach { symbol ->
            val selected = symbol in shownSymbols
            FilterChip(
                selected = selected,
                onClick = { onToggleSymbol(symbol, !selected) },
                label = { Text(text = stringResource(symbol.chipLabelRes())) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(symbol.iconRes()),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = symbol.tintColor(),
                    selectedLabelColor = Color.White,
                    selectedLeadingIconColor = Color.White
                ),
                modifier = Modifier
                    .defaultMinSize(minHeight = PlaceRowMinHeight)
                    .testTag(OpenHikesTestTags.trailPlaceKindChip(symbol.rawValue))
            )
        }
    }
}

@Composable
internal fun TrailPlaceKindPickerRow(
    selected: TrailPlaceSymbol?,
    onSelect: (TrailPlaceSymbol?) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPickerDialog by remember { mutableStateOf(false) }
    val selectedLabel = if (selected != null) {
        stringResource(selected.labelRes())
    } else {
        stringResource(app.openhikes.core.ui.R.string.place_kind_none)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = PlaceRowMinHeight)
            .clickable(role = Role.Button) { showPickerDialog = true }
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {}
            .testTag(OpenHikesTestTags.TRAIL_PLACE_KIND_PICKER)
    ) {
        Text(
            text = stringResource(R.string.places_field_kind),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                painter = painterResource(selected.iconRes()),
                contentDescription = null,
                tint = selected.tintColor(),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = selectedLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }

    if (showPickerDialog) {
        TrailPlaceKindPickerDialog(
            selected = selected,
            onSelect = { kind ->
                showPickerDialog = false
                onSelect(kind)
            },
            onDismiss = { showPickerDialog = false }
        )
    }
}

@Composable
private fun TrailPlaceKindPickerDialog(
    selected: TrailPlaceSymbol?,
    onSelect: (TrailPlaceSymbol?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.places_field_kind)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                KindOptionRow(
                    label = stringResource(app.openhikes.core.ui.R.string.place_kind_none),
                    symbol = null,
                    isSelected = selected == null,
                    onClick = { onSelect(null) }
                )
                TrailPlaceSymbol.entries.forEach { kind ->
                    KindOptionRow(
                        label = stringResource(kind.labelRes()),
                        symbol = kind,
                        isSelected = selected == kind,
                        onClick = { onSelect(kind) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = PlaceRowMinHeight)
            ) {
                Text(text = stringResource(R.string.places_action_cancel))
            }
        }
    )
}

@Composable
private fun KindOptionRow(label: String, symbol: TrailPlaceSymbol?, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = PlaceRowMinHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp)
            .semantics(mergeDescendants = true) {}
    ) {
        Icon(
            painter = painterResource(symbol.iconRes()),
            contentDescription = null,
            tint = symbol.tintColor(),
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (isSelected) {
            Icon(
                painter = painterResource(OpenHikesIcons.Check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
internal fun PlaceSaveRefusalDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.places_save_refused_title)) },
        text = { Text(text = stringResource(R.string.places_save_refused_message)) },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = PlaceRowMinHeight)
            ) {
                Text(text = stringResource(R.string.places_action_close))
            }
        }
    )
}

@ReadOnlyComposable
@Composable
internal fun outageMessage(outage: PlaceSearchOutage): String = when (outage) {
    PlaceSearchOutage.OFFLINE -> stringResource(R.string.places_outage_offline)
    PlaceSearchOutage.RATE_LIMITED -> stringResource(R.string.places_outage_rate_limited)
    PlaceSearchOutage.UNAVAILABLE -> stringResource(R.string.places_outage_unavailable)
}

@ReadOnlyComposable
@Composable
internal fun factLabel(kind: TrailPlaceFact.Kind): String = when (kind) {
    TrailPlaceFact.Kind.ELEVATION -> stringResource(R.string.places_fact_elevation)
    TrailPlaceFact.Kind.DESCRIPTION -> stringResource(R.string.places_fact_description)
    TrailPlaceFact.Kind.DRINKING_WATER -> stringResource(R.string.places_fact_drinking_water)
    TrailPlaceFact.Kind.OPENING_HOURS -> stringResource(R.string.places_fact_opening_hours)
    TrailPlaceFact.Kind.FEE -> stringResource(R.string.places_fact_fee)
    TrailPlaceFact.Kind.ACCESS -> stringResource(R.string.places_fact_access)
    TrailPlaceFact.Kind.CAPACITY -> stringResource(R.string.places_fact_capacity)
    TrailPlaceFact.Kind.OPERATOR_NAME -> stringResource(R.string.places_fact_operator)
    TrailPlaceFact.Kind.PHONE -> stringResource(R.string.places_fact_phone)
    TrailPlaceFact.Kind.WEBSITE -> stringResource(R.string.places_fact_website)
}

@ReadOnlyComposable
@Composable
internal fun formatFactValue(fact: TrailPlaceFact, distanceUnit: DistanceUnit): String = when (fact.kind) {
    TrailPlaceFact.Kind.ELEVATION -> {
        val numeric = fact.value.replace(',', '.').removeSuffix("m").trim().toDoubleOrNull()
        if (numeric != null) {
            HikeMetricsFormatter.formatElevation(numeric, distanceUnit)
        } else {
            fact.value
        }
    }
    TrailPlaceFact.Kind.DRINKING_WATER,
    TrailPlaceFact.Kind.FEE -> when (fact.value.lowercase()) {
        "yes" -> stringResource(R.string.places_fact_yes)
        "no" -> stringResource(R.string.places_fact_no)
        else -> fact.value
    }
    else -> fact.value
}

internal fun sharePlaceIntent(context: Context, title: String, coordinate: GeoCoordinate, osmUrl: String?) {
    val coords = TrailPlaceCoordinates.format(coordinate)
    val link = osmUrl ?: TrailPlaceCoordinates.geoUri(coordinate, title)
    val text = "$title\n$coords\n$link"
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching {
        context.startActivity(Intent.createChooser(sendIntent, title))
    }
}
