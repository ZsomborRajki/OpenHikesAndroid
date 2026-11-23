package app.openhikes.feature.hikes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.OfflineDownloadFailure
import app.openhikes.core.model.OfflineDownloadPhase
import app.openhikes.core.model.OfflineRouteSaving
import app.openhikes.core.ui.format.StorageSizeFormatter
import app.openhikes.core.ui.testing.OpenHikesTestTags
import kotlin.math.roundToInt

private val TouchTargetMinSize = 48.dp
private val SpinnerSize = 18.dp
private val SpinnerStrokeWidth = 2.dp
private const val PERCENT_MULTIPLIER = 100

/**
 * Hike Detail action button offering offline country downloads for the countries crossed by this trail.
 */
@Composable
internal fun HikeOfflineCountryButton(
    coverage: HikeOfflineCoverageState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeDownload = coverage.activeDownload
    val label = resolveButtonLabel(coverage)
    val stateText = resolveStateDescription(coverage)
    val accessibilityLabel = stringResource(R.string.hike_detail_offline_content_description)
    val isEnabled = coverage.crossed.isNotEmpty() &&
        (coverage.canOpenDialog || coverage.saving == OfflineRouteSaving.WHOLE)

    OutlinedButton(
        onClick = { if (coverage.canOpenDialog) onClick() },
        enabled = isEnabled,
        modifier = modifier
            .defaultMinSize(minHeight = TouchTargetMinSize)
            .semantics {
                contentDescription = accessibilityLabel
                stateDescription = stateText
            }
            .testTag(OpenHikesTestTags.OFFLINE_COUNTRY_BUTTON)
    ) {
        if (activeDownload != null && activeDownload.phase != OfflineDownloadPhase.FAILED) {
            CircularProgressIndicator(
                strokeWidth = SpinnerStrokeWidth,
                modifier = Modifier.size(SpinnerSize)
            )
        } else {
            val iconRes = when {
                activeDownload?.phase == OfflineDownloadPhase.FAILED -> OpenHikesIcons.Warning
                coverage.saving == OfflineRouteSaving.WHOLE -> OpenHikesIcons.CheckCircle
                else -> OpenHikesIcons.Download
            }
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = when {
                    activeDownload?.phase == OfflineDownloadPhase.FAILED -> MaterialTheme.colorScheme.error
                    coverage.saving == OfflineRouteSaving.WHOLE -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.size(ButtonDefaults.IconSize)
            )
        }
        Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
        Text(text = label, maxLines = 1)
    }
}

/**
 * Dialog offering downloads for every missing country crossed by the hike route, or retry/cancel
 * controls when a crossed country is already downloading or failed.
 */
@Composable
internal fun HikeOfflineCountryDialog(
    coverage: HikeOfflineCoverageState,
    onDownloadCountry: (String) -> Unit,
    onRetryCountry: (String) -> Unit,
    onCancelCountry: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val active = coverage.activeDownload
    val message = if (active?.phase == OfflineDownloadPhase.FAILED) {
        failureCopy(active.failure)
    } else {
        stringResource(R.string.hike_detail_offline_dialog_body)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(OpenHikesTestTags.OFFLINE_COUNTRY_DIALOG),
        title = { Text(text = stringResource(R.string.hike_detail_offline_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
                if (active != null) {
                    ActiveDownloadDialogActions(
                        countryId = active.country.id,
                        isFailed = active.phase == OfflineDownloadPhase.FAILED,
                        onRetryCountry = onRetryCountry,
                        onCancelCountry = onCancelCountry,
                        onDismiss = onDismiss
                    )
                } else {
                    MissingCountriesDialogActions(
                        coverage = coverage,
                        onDownloadCountry = onDownloadCountry,
                        onDismiss = onDismiss
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = TouchTargetMinSize)
            ) {
                Text(text = stringResource(R.string.hike_detail_offline_close))
            }
        }
    )
}

@Composable
private fun ActiveDownloadDialogActions(
    countryId: String,
    isFailed: Boolean,
    onRetryCountry: (String) -> Unit,
    onCancelCountry: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (isFailed) {
        FilledTonalButton(
            onClick = {
                onRetryCountry(countryId)
                onDismiss()
            },
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = TouchTargetMinSize)
        ) {
            Text(text = stringResource(R.string.hike_detail_offline_retry))
        }
    }
    OutlinedButton(
        onClick = {
            onCancelCountry(countryId)
            onDismiss()
        },
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = TouchTargetMinSize)
    ) {
        Text(
            text = stringResource(R.string.hike_detail_offline_cancel_download),
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun MissingCountriesDialogActions(
    coverage: HikeOfflineCoverageState,
    onDownloadCountry: (String) -> Unit,
    onDismiss: () -> Unit
) {
    coverage.missing.forEach { country ->
        val sizeText = StorageSizeFormatter.formatBytes(country.byteCount(coverage.includesDetail))
        FilledTonalButton(
            onClick = {
                onDownloadCountry(country.id)
                onDismiss()
            },
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = TouchTargetMinSize)
        ) {
            Text(
                text = stringResource(
                    R.string.hike_detail_offline_download_country,
                    country.fullName(),
                    sizeText
                )
            )
        }
    }
}

@Composable
private fun resolveButtonLabel(coverage: HikeOfflineCoverageState): String {
    val active = coverage.activeDownload
    return when {
        active != null && active.phase != OfflineDownloadPhase.FAILED ->
            "${(active.progressFraction * PERCENT_MULTIPLIER).roundToInt()}%"

        coverage.saving == OfflineRouteSaving.WHOLE -> stringResource(R.string.hike_detail_offline_saved)
        active?.phase == OfflineDownloadPhase.FAILED -> stringResource(R.string.hike_detail_offline_action_label)
        coverage.saving == OfflineRouteSaving.PART && coverage.missing.isEmpty() ->
            stringResource(R.string.hike_detail_offline_partly_saved)

        else -> stringResource(R.string.hike_detail_offline_action_label)
    }
}

@Composable
private fun resolveStateDescription(coverage: HikeOfflineCoverageState): String {
    val active = coverage.activeDownload
    return when {
        active != null && active.phase != OfflineDownloadPhase.FAILED ->
            "${(active.progressFraction * PERCENT_MULTIPLIER).roundToInt()}%"

        active?.phase == OfflineDownloadPhase.FAILED -> failureCopy(active.failure)
        coverage.saving == OfflineRouteSaving.WHOLE -> stringResource(R.string.hike_detail_offline_saved)
        coverage.saving == OfflineRouteSaving.PART -> stringResource(R.string.hike_detail_offline_partly_saved)
        else -> stringResource(R.string.hike_detail_offline_not_saved)
    }
}

@Composable
private fun failureCopy(failure: OfflineDownloadFailure?): String = when (failure) {
    OfflineDownloadFailure.SPACE -> stringResource(R.string.hike_detail_offline_error_space)
    OfflineDownloadFailure.DAMAGED -> stringResource(R.string.hike_detail_offline_error_damaged)
    OfflineDownloadFailure.CONSERVING -> stringResource(R.string.hike_detail_offline_error_conserving)
    OfflineDownloadFailure.CONNECTION, null -> stringResource(R.string.hike_detail_offline_error_connection)
}
