package app.openhikes.feature.places

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.TrailPlaceFact
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.WikidataPhoto
import app.openhikes.core.model.WikidataPlace
import app.openhikes.core.ui.components.TrailPlaceBadge
import app.openhikes.core.ui.components.TrailPlaceCoordinates
import app.openhikes.core.ui.testing.OpenHikesTestTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val PlaceScreenPadding = 16.dp
internal val PlaceSectionSpacing = 16.dp
internal val PlaceCardCornerRadius = 18.dp
internal val PlaceRowMinHeight = 48.dp
private val WikidataPhotoHeight = 180.dp
private val WikidataPhotoCornerRadius = 12.dp
private val CreditPillInset = 8.dp
private const val CREDIT_SCRIM_ALPHA = 0.72f
private const val EXTRACT_MAX_LINES = 10

@Composable
internal fun PlaceHeaderRow(
    symbol: TrailPlaceSymbol?,
    title: String,
    subtitle: String?,
    titleTestTag: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        TrailPlaceBadge(symbol = symbol)
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .semantics { heading() }
                    .testTag(titleTestTag)
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun PlaceTopBar(
    title: String,
    backTestTag: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .defaultMinSize(minWidth = PlaceRowMinHeight, minHeight = PlaceRowMinHeight)
                .testTag(backTestTag)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.ArrowBack),
                contentDescription = stringResource(R.string.places_nav_back)
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        trailing()
    }
}

@Composable
internal fun TrailPlaceFactsAndLocationSection(
    facts: List<TrailPlaceFact>,
    coordinate: GeoCoordinate,
    distanceUnit: DistanceUnit,
    openStreetMapUrl: String?,
    wikidataPlace: WikidataPlace?,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(PlaceSectionSpacing),
        modifier = modifier.fillMaxWidth()
    ) {
        if (wikidataPlace != null) {
            WikidataPlaceSection(place = wikidataPlace, onOpenUrl = onOpenUrl)
        }
        if (facts.isNotEmpty()) {
            PlaceDetailsCard(facts = facts, distanceUnit = distanceUnit, onOpenUrl = onOpenUrl)
        }
        PlaceLocationCard(
            coordinate = coordinate,
            openStreetMapUrl = openStreetMapUrl,
            onOpenUrl = onOpenUrl
        )
    }
}

@Composable
private fun PlaceDetailsCard(facts: List<TrailPlaceFact>, distanceUnit: DistanceUnit, onOpenUrl: (String) -> Unit) {
    PlaceSectionCard(title = stringResource(R.string.places_section_details)) {
        facts.forEachIndexed { index, fact ->
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            TrailPlaceFactRow(fact = fact, distanceUnit = distanceUnit, onOpenUrl = onOpenUrl)
        }
    }
}

@Composable
private fun PlaceLocationCard(coordinate: GeoCoordinate, openStreetMapUrl: String?, onOpenUrl: (String) -> Unit) {
    PlaceSectionCard(title = stringResource(R.string.places_section_location)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = PlaceRowMinHeight)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = stringResource(R.string.places_row_coordinates),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SelectionContainer {
                Text(
                    text = TrailPlaceCoordinates.format(coordinate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag(OpenHikesTestTags.TRAIL_PLACE_COORDINATES)
                )
            }
        }
        if (openStreetMapUrl != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PlaceLinkActionRow(
                label = stringResource(R.string.places_view_on_osm),
                testTag = OpenHikesTestTags.TRAIL_PLACE_OSM_LINK,
                onClick = { onOpenUrl(openStreetMapUrl) }
            )
        }
    }
}

@Composable
internal fun WikidataPlaceSection(place: WikidataPlace, onOpenUrl: (String) -> Unit, modifier: Modifier = Modifier) {
    val headingText = if (place.hasWikipediaArticle) {
        stringResource(R.string.places_wikidata_heading_wikipedia)
    } else {
        stringResource(R.string.places_wikidata_heading_commons)
    }
    PlaceSectionCard(title = headingText, modifier = modifier) {
        place.photo?.let { photo ->
            WikidataPhotoBlock(
                photo = photo,
                subjectTitle = place.article?.title,
                onOpenUrl = onOpenUrl
            )
        }
        place.article?.let { article ->
            if (place.photo != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Text(
                text = article.extract,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = EXTRACT_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag(OpenHikesTestTags.WIKIDATA_PLACE_EXTRACT)
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PlaceLinkActionRow(
                label = stringResource(R.string.places_wikidata_read_on_wikipedia),
                testTag = OpenHikesTestTags.WIKIDATA_PLACE_ARTICLE_LINK,
                onClick = { onOpenUrl(article.url) }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PlaceKeyValueLinkRow(
                label = stringResource(R.string.places_wikidata_text_license),
                value = WikidataPlace.TEXT_LICENSE_NAME,
                testTag = OpenHikesTestTags.WIKIDATA_PLACE_TEXT_LICENSE,
                onClick = { onOpenUrl(WikidataPlace.TEXT_LICENSE_URL) }
            )
        }
    }
}

@Suppress("InjectDispatcher")
@Composable
private fun WikidataPhotoBlock(photo: WikidataPhoto, subjectTitle: String?, onOpenUrl: (String) -> Unit) {
    var showCreditDialog by remember { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = photo.credit.license.sourceUrl) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                BitmapFactory.decodeByteArray(photo.imageBytes, 0, photo.imageBytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val photoDescription = if (!subjectTitle.isNullOrBlank()) {
        stringResource(R.string.places_wikidata_photo_description, subjectTitle)
    } else {
        stringResource(R.string.places_wikidata_photo_fallback_description)
    }
    val creditLabel = listOfNotNull(
        photo.credit.author?.takeIf { it.isNotBlank() },
        photo.credit.license.name
    ).joinToString(" · ")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .height(WikidataPhotoHeight)
            .clip(RoundedCornerShape(WikidataPhotoCornerRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = photoDescription }
                .testTag(OpenHikesTestTags.WIKIDATA_PLACE_PHOTO)
        ) {
            bitmap?.let { decoded ->
                Image(
                    bitmap = decoded,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        WikidataPhotoCreditPill(
            creditLabel = creditLabel,
            onClick = { showCreditDialog = true },
            modifier = Modifier.align(Alignment.BottomStart)
        )
    }

    if (showCreditDialog) {
        WikidataCreditDialog(photo = photo, onOpenUrl = onOpenUrl, onDismiss = { showCreditDialog = false })
    }
}

@Composable
private fun WikidataPhotoCreditPill(creditLabel: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Black.copy(alpha = CREDIT_SCRIM_ALPHA),
        contentColor = Color.White,
        modifier = modifier
            .padding(CreditPillInset)
            .defaultMinSize(minHeight = PlaceRowMinHeight)
            .testTag(OpenHikesTestTags.WIKIDATA_PLACE_CREDIT)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = creditLabel,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun WikidataCreditDialog(photo: WikidataPhoto, onOpenUrl: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.places_wikidata_credit_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                photo.credit.author?.let { author ->
                    Text(
                        text = "${stringResource(R.string.places_wikidata_credit_author)}: $author",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = "${stringResource(R.string.places_wikidata_credit_license)}: ${photo.credit.license.name}",
                    style = MaterialTheme.typography.bodyMedium
                )
                photo.credit.license.url?.let { licenseUrl ->
                    TextButton(
                        onClick = { onOpenUrl(licenseUrl) },
                        modifier = Modifier.defaultMinSize(minHeight = PlaceRowMinHeight)
                    ) {
                        Text(text = photo.credit.license.name)
                    }
                }
                TextButton(
                    onClick = { onOpenUrl(photo.credit.license.sourceUrl) },
                    modifier = Modifier.defaultMinSize(minHeight = PlaceRowMinHeight)
                ) {
                    Text(text = stringResource(R.string.places_wikidata_credit_open_file))
                }
            }
        },
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

@Composable
internal fun PlaceSectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() }
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(PlaceCardCornerRadius),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

@Composable
private fun TrailPlaceFactRow(fact: TrailPlaceFact, distanceUnit: DistanceUnit, onOpenUrl: (String) -> Unit) {
    val label = factLabel(fact.kind)
    val displayValue = formatFactValue(fact, distanceUnit)
    when (fact.kind) {
        TrailPlaceFact.Kind.WEBSITE -> {
            val isHttp = fact.value.startsWith("https://") || fact.value.startsWith("http://")
            if (isHttp) {
                PlaceKeyValueLinkRow(label = label, value = fact.value, onClick = { onOpenUrl(fact.value) })
            } else {
                PlaceKeyValueRow(label = label, value = displayValue)
            }
        }
        TrailPlaceFact.Kind.PHONE -> {
            val dialDigits = fact.value.filter { it.isDigit() || it == '+' }
            if (dialDigits.isNotEmpty()) {
                PlaceKeyValueLinkRow(label = label, value = fact.value, onClick = { onOpenUrl("tel:$dialDigits") })
            } else {
                PlaceKeyValueRow(label = label, value = displayValue)
            }
        }
        else -> PlaceKeyValueRow(label = label, value = displayValue)
    }
}

@Composable
private fun PlaceKeyValueRow(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = PlaceRowMinHeight)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {}
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Composable
private fun PlaceKeyValueLinkRow(label: String, value: String, onClick: () -> Unit, testTag: String? = null) {
    val baseModifier = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = PlaceRowMinHeight)
        .clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp)
        .semantics(mergeDescendants = true) {}
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = if (testTag != null) baseModifier.testTag(testTag) else baseModifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Composable
private fun PlaceLinkActionRow(label: String, testTag: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = PlaceRowMinHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {}
            .testTag(testTag)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Icon(
            painter = painterResource(OpenHikesIcons.OpenInNew),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
    }
}
