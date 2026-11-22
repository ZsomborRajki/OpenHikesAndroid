package app.openhikes.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.openhikes.core.ui.testing.OpenHikesTestTags

private val ErrorBannerCornerRadius = 24.dp
private val ErrorBannerHorizontalPadding = 16.dp
private val ErrorBannerVerticalPadding = 12.dp
private val ErrorBannerTouchTargetMinSize = 48.dp

@Composable
internal fun SettingsSaveErrorBanner(onRetry: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(ErrorBannerCornerRadius),
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(OpenHikesTestTags.SETTINGS_SAVE_ERROR)
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = ErrorBannerHorizontalPadding,
                vertical = ErrorBannerVerticalPadding
            )
        ) {
            Text(
                text = stringResource(R.string.settings_save_error_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.defaultMinSize(minHeight = ErrorBannerTouchTargetMinSize)
                ) {
                    Text(
                        text = stringResource(R.string.settings_save_error_dismiss),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier
                        .defaultMinSize(minHeight = ErrorBannerTouchTargetMinSize)
                        .testTag(OpenHikesTestTags.SETTINGS_RETRY_SAVE)
                ) {
                    Text(
                        text = stringResource(R.string.settings_save_error_retry),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}
