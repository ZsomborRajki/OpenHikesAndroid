package app.openhikes.feature.offlinemaps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.InstalledCountryRecord
import app.openhikes.core.model.OfflineCountryDownload
import app.openhikes.core.model.OfflineCountryEntry
import app.openhikes.core.model.OfflineCountryExtra
import app.openhikes.core.model.OfflineCountryOffer
import app.openhikes.core.model.OfflineDownloadFailure
import app.openhikes.core.model.OfflineDownloadPhase
import app.openhikes.core.ui.format.StorageSizeFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags
import kotlin.math.roundToInt

private val RowMinHeight = 60.dp
private val RowCornerRadius = 18.dp
private val RowHorizontalPadding = 16.dp
private val RowContentPadding = PaddingValues(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)
private val TouchTargetMinSize = 48.dp
private val ProgressSize = 32.dp
private val ProgressStrokeWidth = 3.dp
private val StopIconSize = 14.dp
private val CheckIconSize = 20.dp
private val OfferIconSize = 20.dp
private const val OFFER_DIVIDER_ALPHA = 0.5f
private const val PERCENT_MULTIPLIER = 100

/**
 * Interactive row for a single offline country or split-country region, showing size/progress/status,
 * accessible 48dp trailing controls, and per-country Add/Remove offers when *Extra Detail* or
 * *Elevation Data* differs from what is stored on disk.
 */
@Composable
internal fun OfflineCountryRow(
    entry: OfflineCountryEntry,
    namesCountry: Boolean,
    installedRecord: InstalledCountryRecord?,
    download: OfflineCountryDownload?,
    needsRepair: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onRepair: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    additionOffer: OfflineCountryOffer? = null,
    removalOffer: OfflineCountryOffer? = null,
    onExtend: () -> Unit = {},
    onTrim: () -> Unit = {},
    rowTestTag: String = OpenHikesTestTags.offlineCountry(entry.country.id)
) {
    val label = if (namesCountry) entry.fullName else entry.displayName
    val hasUpdate = installedRecord != null && entry.country.build > installedRecord.country.build
    val status = resolveRowStatus(entry, installedRecord, download, needsRepair, hasUpdate)
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    Surface(color = containerColor, shape = RoundedCornerShape(RowCornerRadius), modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = RowMinHeight)
                    .padding(RowContentPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CountryRowTextBlock(
                    label = label,
                    status = status,
                    isSelected = isSelected,
                    onSelect = onSelect,
                    rowTestTag = rowTestTag,
                    modifier = Modifier.weight(1f)
                )
                CountryRowTrailingActions(
                    countryId = entry.country.id,
                    countryLabel = label,
                    installedRecord = installedRecord,
                    download = download,
                    needsRepair = needsRepair,
                    hasUpdate = hasUpdate,
                    onDownload = onDownload,
                    onRetry = onRetry,
                    onRepair = onRepair,
                    onCancel = onCancel,
                    onDelete = onDelete
                )
            }
            if (additionOffer != null || removalOffer != null) {
                CountryRowOffers(entry.country.id, additionOffer, removalOffer, onExtend, onTrim)
            }
        }
    }
}

@Composable
private fun CountryRowOffers(
    countryId: String,
    additionOffer: OfflineCountryOffer?,
    removalOffer: OfflineCountryOffer?,
    onExtend: () -> Unit,
    onTrim: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = OFFER_DIVIDER_ALPHA),
            modifier = Modifier.padding(horizontal = RowHorizontalPadding)
        )
        if (additionOffer != null) {
            CountryRowOfferButton(
                title = additionOfferTitle(additionOffer),
                iconRes = OpenHikesIcons.Download,
                tint = MaterialTheme.colorScheme.primary,
                testTag = OpenHikesTestTags.offlineCountryAddOffer(countryId),
                onClick = onExtend
            )
        }
        if (removalOffer != null) {
            CountryRowOfferButton(
                title = removalOfferTitle(removalOffer),
                iconRes = OpenHikesIcons.Remove,
                tint = MaterialTheme.colorScheme.error,
                testTag = OpenHikesTestTags.offlineCountryRemoveOffer(countryId),
                onClick = onTrim
            )
        }
    }
}

@Composable
private fun CountryRowOfferButton(title: String, iconRes: Int, tint: Color, testTag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = TouchTargetMinSize)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = RowHorizontalPadding, vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(OfferIconSize)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = tint,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun additionOfferTitle(offer: OfflineCountryOffer): String {
    val sizeText = StorageSizeFormatter.formatBytes(offer.byteCount)
    val hasDetail = OfflineCountryExtra.DETAIL in offer.extras
    val hasElevation = OfflineCountryExtra.ELEVATION in offer.extras
    return when {
        hasDetail && hasElevation -> stringResource(R.string.offline_maps_offer_add_both, sizeText)
        hasElevation -> stringResource(R.string.offline_maps_offer_add_elevation, sizeText)
        else -> stringResource(R.string.offline_maps_offer_add_detail, sizeText)
    }
}

@Composable
internal fun removalOfferTitle(offer: OfflineCountryOffer): String {
    val sizeText = StorageSizeFormatter.formatBytes(offer.byteCount)
    val hasDetail = OfflineCountryExtra.DETAIL in offer.extras
    val hasElevation = OfflineCountryExtra.ELEVATION in offer.extras
    return when {
        hasDetail && hasElevation -> stringResource(R.string.offline_maps_offer_remove_both, sizeText)
        hasElevation -> stringResource(R.string.offline_maps_offer_remove_elevation, sizeText)
        else -> stringResource(R.string.offline_maps_offer_remove_detail, sizeText)
    }
}

@Composable
private fun CountryRowTextBlock(
    label: String,
    status: CountryRowStatus,
    isSelected: Boolean,
    onSelect: () -> Unit,
    rowTestTag: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .defaultMinSize(minHeight = TouchTargetMinSize)
            .clickable(role = Role.Button, onClick = onSelect)
            .semantics(mergeDescendants = true) { selected = isSelected }
            .testTag(rowTestTag),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = status.text,
            style = MaterialTheme.typography.bodySmall,
            color = if (status.isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private data class CountryRowStatus(val text: String, val isError: Boolean)

@Composable
private fun resolveRowStatus(
    entry: OfflineCountryEntry,
    installedRecord: InstalledCountryRecord?,
    download: OfflineCountryDownload?,
    needsRepair: Boolean,
    hasUpdate: Boolean
): CountryRowStatus {
    if (download != null) {
        return when (download.phase) {
            OfflineDownloadPhase.WAITING_FOR_WIFI ->
                CountryRowStatus(stringResource(R.string.offline_maps_status_waiting_wifi), isError = false)

            OfflineDownloadPhase.DOWNLOADING -> CountryRowStatus(
                text = stringResource(
                    R.string.offline_maps_status_downloading,
                    StorageSizeFormatter.formatBytes(download.writtenBytes),
                    StorageSizeFormatter.formatBytes(download.totalBytes)
                ),
                isError = false
            )

            OfflineDownloadPhase.CHECKING ->
                CountryRowStatus(stringResource(R.string.offline_maps_status_checking), isError = false)

            OfflineDownloadPhase.FAILED ->
                CountryRowStatus(failureMessage(download.failure), isError = true)
        }
    }
    return when {
        installedRecord != null && hasUpdate -> CountryRowStatus(
            text = stringResource(
                R.string.offline_maps_status_update_available,
                StorageSizeFormatter.formatBytes(entry.byteCount)
            ),
            isError = false
        )

        installedRecord != null && needsRepair ->
            CountryRowStatus(stringResource(R.string.offline_maps_error_damaged), isError = true)

        installedRecord != null -> CountryRowStatus(
            text = stringResource(
                if (installedRecord.hasElevation) {
                    R.string.offline_maps_status_downloaded_with_elevation
                } else {
                    R.string.offline_maps_status_downloaded_without_elevation
                },
                StorageSizeFormatter.formatBytes(installedRecord.byteCount)
            ),
            isError = false
        )

        else -> CountryRowStatus(StorageSizeFormatter.formatBytes(entry.byteCount), isError = false)
    }
}

@Composable
private fun failureMessage(failure: OfflineDownloadFailure?): String = when (failure) {
    OfflineDownloadFailure.SPACE -> stringResource(R.string.offline_maps_error_space)
    OfflineDownloadFailure.DAMAGED -> stringResource(R.string.offline_maps_error_damaged)
    OfflineDownloadFailure.CONSERVING -> stringResource(R.string.offline_maps_error_conserving)
    OfflineDownloadFailure.CONNECTION, null -> stringResource(R.string.offline_maps_error_connection)
}

@Composable
private fun CountryRowTrailingActions(
    countryId: String,
    countryLabel: String,
    installedRecord: InstalledCountryRecord?,
    download: OfflineCountryDownload?,
    needsRepair: Boolean,
    hasUpdate: Boolean,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onRepair: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            download != null -> DownloadPhaseAction(
                countryId = countryId,
                download = download,
                onRetry = onRetry,
                onCancel = onCancel
            )

            installedRecord != null -> InstalledCountryAction(
                countryId = countryId,
                countryLabel = countryLabel,
                hasUpdate = hasUpdate,
                needsRepair = needsRepair,
                onDownload = onDownload,
                onRepair = onRepair
            )

            else -> IconButton(
                onClick = onDownload,
                modifier = Modifier
                    .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
                    .testTag(OpenHikesTestTags.offlineCountryAction(countryId))
            ) {
                Icon(
                    painter = painterResource(OpenHikesIcons.Download),
                    contentDescription = stringResource(R.string.offline_maps_action_download, countryLabel),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        if (installedRecord != null && (download == null || download.phase == OfflineDownloadPhase.FAILED)) {
            DeleteCountryButton(countryId = countryId, countryLabel = countryLabel, onDelete = onDelete)
        }
    }
}

@Composable
private fun InstalledCountryAction(
    countryId: String,
    countryLabel: String,
    hasUpdate: Boolean,
    needsRepair: Boolean,
    onDownload: () -> Unit,
    onRepair: () -> Unit
) {
    when {
        hasUpdate -> {
            val updateDescription = stringResource(R.string.offline_maps_action_update_description, countryLabel)
            FilledTonalButton(
                onClick = onDownload,
                modifier = Modifier
                    .defaultMinSize(minHeight = TouchTargetMinSize)
                    .semantics { contentDescription = updateDescription }
                    .testTag(OpenHikesTestTags.offlineCountryAction(countryId))
            ) {
                Text(text = stringResource(R.string.offline_maps_action_update))
            }
        }

        needsRepair -> IconButton(
            onClick = onRepair,
            modifier = Modifier
                .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
                .testTag(OpenHikesTestTags.offlineCountryAction(countryId))
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Refresh),
                contentDescription = stringResource(R.string.offline_maps_action_retry),
                tint = MaterialTheme.colorScheme.error
            )
        }

        else -> Icon(
            painter = painterResource(OpenHikesIcons.CheckCircle),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .size(CheckIconSize)
        )
    }
}

@Composable
private fun DeleteCountryButton(countryId: String, countryLabel: String, onDelete: () -> Unit) {
    IconButton(
        onClick = onDelete,
        modifier = Modifier
            .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
            .testTag(OpenHikesTestTags.offlineCountryDelete(countryId))
    ) {
        Icon(
            painter = painterResource(OpenHikesIcons.Delete),
            contentDescription = stringResource(R.string.offline_maps_action_delete, countryLabel),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DownloadPhaseAction(
    countryId: String,
    download: OfflineCountryDownload,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    when (download.phase) {
        OfflineDownloadPhase.WAITING_FOR_WIFI,
        OfflineDownloadPhase.DOWNLOADING -> ActiveDownloadCancelButton(
            countryId = countryId,
            download = download,
            onCancel = onCancel
        )

        OfflineDownloadPhase.CHECKING -> Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
        ) {
            CircularProgressIndicator(
                strokeWidth = ProgressStrokeWidth,
                modifier = Modifier.size(ProgressSize)
            )
        }

        OfflineDownloadPhase.FAILED -> FailedDownloadActions(
            countryId = countryId,
            onRetry = onRetry,
            onCancel = onCancel
        )
    }
}

@Composable
private fun ActiveDownloadCancelButton(countryId: String, download: OfflineCountryDownload, onCancel: () -> Unit) {
    val percent = if (download.phase == OfflineDownloadPhase.WAITING_FOR_WIFI) {
        0
    } else {
        (download.progressFraction * PERCENT_MULTIPLIER).roundToInt()
    }
    val cancelLabel = stringResource(R.string.offline_maps_action_cancel)
    IconButton(
        onClick = onCancel,
        modifier = Modifier
            .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
            .semantics {
                contentDescription = cancelLabel
                stateDescription = "$percent%"
            }
            .testTag(OpenHikesTestTags.offlineCountryAction(countryId))
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { download.progressFraction },
                strokeWidth = ProgressStrokeWidth,
                modifier = Modifier.size(ProgressSize)
            )
            Icon(
                painter = painterResource(OpenHikesIcons.Stop),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(StopIconSize)
            )
        }
    }
}

@Composable
private fun FailedDownloadActions(countryId: String, onRetry: () -> Unit, onCancel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onRetry,
            modifier = Modifier
                .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
                .testTag(OpenHikesTestTags.offlineCountryAction(countryId))
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Refresh),
                contentDescription = stringResource(R.string.offline_maps_action_retry),
                tint = MaterialTheme.colorScheme.error
            )
        }
        IconButton(
            onClick = onCancel,
            modifier = Modifier
                .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
                .testTag(OpenHikesTestTags.offlineCountryCancel(countryId))
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Close),
                contentDescription = stringResource(R.string.offline_maps_action_cancel),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
