package app.openhikes.feature.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.MapAttribution
import app.openhikes.core.ui.testing.OpenHikesTestTags

private val HorizontalMargin = 16.dp
private val CardCornerRadius = 20.dp
private val CardInnerPadding = 14.dp
private val RowVerticalPadding = 12.dp
private val RowItemSpacing = 12.dp
private val TitleSummarySpacing = 2.dp
private val BannerEndPadding = 6.dp
private val BannerVerticalPadding = 8.dp
private val BannerItemSpacing = 8.dp
private val TouchTargetMinSize = 48.dp
private val OfflineRowMinHeight = 60.dp
private val LeadingIconSize = 24.dp
private val ChevronIconSize = 20.dp
private val FooterSpacing = 8.dp

@Composable
internal fun MapModesOfflineMapsRow(
    installedCountryNames: List<String>,
    installedByteCount: Long,
    onOpenOfflineMaps: () -> Unit,
    modifier: Modifier = Modifier
) {
    val emptySummary = stringResource(R.string.map_modes_offline_maps_empty_summary)
    val installedFormat = stringResource(R.string.map_modes_offline_maps_installed_summary)
    val locale = LocalLocale.current.platformLocale
    val summary = formatInstalledCountriesSummary(
        names = installedCountryNames,
        installedByteCount = installedByteCount,
        emptySummary = emptySummary,
        installedTemplate = { joined, size -> String.format(locale, installedFormat, joined, size) },
        locale = locale
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(CardCornerRadius),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HorizontalMargin)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = OfflineRowMinHeight)
                .clickable(role = Role.Button, onClick = onOpenOfflineMaps)
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = CardInnerPadding, vertical = RowVerticalPadding)
                .testTag(OpenHikesTestTags.MAP_MODES_OFFLINE_MAPS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RowItemSpacing)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.Download),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(LeadingIconSize)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(TitleSummarySpacing)
            ) {
                Text(
                    text = stringResource(R.string.map_modes_offline_maps_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                painter = painterResource(OpenHikesIcons.ChevronRight),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ChevronIconSize)
            )
        }
    }
}

@Composable
internal fun MapModesSaveErrorBanner(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(CardCornerRadius),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HorizontalMargin)
            .testTag(OpenHikesTestTags.MAP_MODES_SAVE_ERROR)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = CardInnerPadding,
                    end = BannerEndPadding,
                    top = BannerVerticalPadding,
                    bottom = BannerVerticalPadding
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BannerItemSpacing)
        ) {
            Text(
                text = stringResource(R.string.map_modes_save_error_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = TouchTargetMinSize)
            ) {
                Text(text = stringResource(R.string.map_modes_save_error_dismiss))
            }
        }
    }
}

@Composable
internal fun MapModesFooter(
    hasMissingApiKeyStyles: Boolean,
    attribution: MapAttribution,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HorizontalMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FooterSpacing)
    ) {
        if (hasMissingApiKeyStyles) {
            Text(
                text = stringResource(R.string.map_modes_missing_api_key_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(OpenHikesTestTags.MAP_MODES_API_KEY_NOTE)
            )
        }
        TileAttributionFooter(
            attribution = attribution,
            onOpenUrl = onOpenUrl
        )
    }
}

@Composable
internal fun TileAttributionFooter(
    attribution: MapAttribution,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val openCreditFormat = stringResource(R.string.map_modes_open_credit_action)
    val annotated = buildAnnotatedString {
        attribution.credits.forEachIndexed { index, credit ->
            if (index > 0) append(", ")
            append(credit.prefix)
            append(' ')
            val link = LinkAnnotation.Url(
                url = credit.url,
                styles = TextLinkStyles(
                    style = SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline
                    )
                ),
                linkInteractionListener = { onOpenUrl(credit.url) }
            )
            withLink(link) {
                append(credit.title)
            }
        }
    }
    val locale = LocalLocale.current.platformLocale
    val accessibilityActions = attribution.credits.map { credit ->
        CustomAccessibilityAction(label = String.format(locale, openCreditFormat, credit.title)) {
            onOpenUrl(credit.url)
            true
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = TouchTargetMinSize)
            .semantics(mergeDescendants = true) {
                contentDescription = attribution.plainText
                customActions = accessibilityActions
            }
            .testTag(OpenHikesTestTags.TILE_ATTRIBUTION)
    ) {
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
