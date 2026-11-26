package app.openhikes.feature.places

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.TrailPlaceAnchor
import app.openhikes.core.model.TrailPlaceAroundEntry
import app.openhikes.core.model.TrailPlaceAroundListing
import app.openhikes.core.model.TrailPlaceFact
import app.openhikes.core.model.TrailPlaceOsm
import app.openhikes.core.model.TrailPlaceReach
import app.openhikes.core.model.TrailPlaceRow
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.TrailPoint
import app.openhikes.core.model.WikidataPlace
import app.openhikes.core.model.WikipediaArticle
import com.android.tools.screenshot.PreviewTest

private fun sampleOsmPlaceRow(): TrailPlaceRow = TrailPlaceRow(
    place = TrailPoint(
        id = "preview-summit",
        hikeId = HikeId("preview-hike"),
        coordinate = GeoCoordinate(latitude = 47.7215, longitude = 12.841363),
        name = "Thumsee Kopf",
        symbol = TrailPlaceSymbol.SUMMIT.rawValue,
        osm = TrailPlaceOsm(
            elementType = "node",
            elementId = 9_100_004L,
            facts = listOf(
                TrailPlaceFact(TrailPlaceFact.Kind.ELEVATION, "712"),
                TrailPlaceFact(TrailPlaceFact.Kind.DESCRIPTION, "Wooded summit overlooking the lake")
            ),
            wikidata = "Q4115189"
        )
    ),
    anchor = TrailPlaceAnchor(distanceAlongRouteMeters = 1_120.0, offRouteMeters = 420.0)
)

private fun sampleWikidataArticleOnly(): WikidataPlace = WikidataPlace(
    item = "Q4115189",
    article = WikipediaArticle(
        title = "Thumsee Kopf",
        extract = "Thumsee Kopf is a wooded summit above the Thumsee, a small lake west of Bad Reichenhall.",
        url = "https://en.wikipedia.org/wiki/Thumsee_Kopf",
        language = "en"
    ),
    photo = null
)

private fun samplePlacesAroundState(): PlacesAroundUiState = PlacesAroundUiState(
    phase = PlacesAroundPhase.FOUND,
    reach = TrailPlaceReach.NEARBY,
    listing = TrailPlaceAroundListing(
        onTrail = listOf(
            TrailPlaceAroundEntry(
                row = TrailPlaceRow(
                    place = TrailPoint(
                        id = "preview-hut",
                        hikeId = HikeId("preview-hike"),
                        coordinate = GeoCoordinate(47.724008, 12.829098),
                        name = "Lakeshore Hut",
                        symbol = TrailPlaceSymbol.SHELTER.rawValue
                    ),
                    anchor = TrailPlaceAnchor(distanceAlongRouteMeters = 840.0, offRouteMeters = 14.0)
                ),
                isAdded = false
            )
        ),
        nearby = listOf(
            TrailPlaceAroundEntry(
                row = sampleOsmPlaceRow(),
                isAdded = true
            )
        )
    )
)

@PreviewTest
@Preview(name = "Place Detail OSM Light", showBackground = true, widthDp = 411, heightDp = 760)
@Composable
fun PlaceDetailOsmLightPreview() {
    OpenHikesTheme(darkTheme = false, dynamicColor = false) {
        Surface {
            HikePlaceDetailScreen(
                uiState = HikePlaceDetailUiState(
                    row = sampleOsmPlaceRow(),
                    wikidataPlace = sampleWikidataArticleOnly(),
                    isLoaded = true
                ),
                onBack = {},
                onShowOnMap = {},
                onShare = {},
                onStartEdit = {},
                onEditNameChange = {},
                onEditSymbolChange = {},
                onEditNoteChange = {},
                onSaveEdit = {},
                onCancelEdit = {},
                onRequestRemove = {},
                onConfirmRemove = {},
                onCancelRemove = {},
                onDismissSaveRefusal = {},
                onOpenUrl = {}
            )
        }
    }
}

@PreviewTest
@Preview(name = "Place Detail OSM Dark", showBackground = true, widthDp = 411, heightDp = 760)
@Composable
fun PlaceDetailOsmDarkPreview() {
    OpenHikesTheme(darkTheme = true, dynamicColor = false) {
        Surface {
            HikePlaceDetailScreen(
                uiState = HikePlaceDetailUiState(
                    row = sampleOsmPlaceRow(),
                    wikidataPlace = sampleWikidataArticleOnly(),
                    isLoaded = true
                ),
                onBack = {},
                onShowOnMap = {},
                onShare = {},
                onStartEdit = {},
                onEditNameChange = {},
                onEditSymbolChange = {},
                onEditNoteChange = {},
                onSaveEdit = {},
                onCancelEdit = {},
                onRequestRemove = {},
                onConfirmRemove = {},
                onCancelRemove = {},
                onDismissSaveRefusal = {},
                onOpenUrl = {}
            )
        }
    }
}

@PreviewTest
@Preview(
    name = "Place Detail Large Font 200%",
    showBackground = true,
    widthDp = 411,
    heightDp = 891,
    fontScale = 2.0f
)
@Composable
fun PlaceDetailLargeFontPreview() {
    OpenHikesTheme(darkTheme = false, dynamicColor = false) {
        Surface {
            HikePlaceDetailScreen(
                uiState = HikePlaceDetailUiState(
                    row = sampleOsmPlaceRow(),
                    wikidataPlace = sampleWikidataArticleOnly(),
                    isLoaded = true
                ),
                onBack = {},
                onShowOnMap = {},
                onShare = {},
                onStartEdit = {},
                onEditNameChange = {},
                onEditSymbolChange = {},
                onEditNoteChange = {},
                onSaveEdit = {},
                onCancelEdit = {},
                onRequestRemove = {},
                onConfirmRemove = {},
                onCancelRemove = {},
                onDismissSaveRefusal = {},
                onOpenUrl = {}
            )
        }
    }
}

@PreviewTest
@Preview(name = "Add Place Light", showBackground = true, widthDp = 411, heightDp = 760)
@Composable
fun AddHikePlaceLightPreview() {
    OpenHikesTheme(darkTheme = false, dynamicColor = false) {
        Surface {
            AddHikePlaceScreen(
                uiState = AddHikePlaceUiState(
                    name = "Lake Overlook",
                    symbol = TrailPlaceSymbol.VIEWPOINT,
                    note = "Quiet wooden bench overlooking the water",
                    coordinate = GeoCoordinate(47.719219, 12.830877)
                ),
                onNameChange = {},
                onSymbolChange = {},
                onNoteChange = {},
                onAdd = {},
                onCancel = {},
                onDismissSaveRefusal = {}
            )
        }
    }
}

@PreviewTest
@Preview(name = "Places Around Trail Light", showBackground = true, widthDp = 411, heightDp = 760)
@Composable
fun PlacesAroundLightPreview() {
    OpenHikesTheme(darkTheme = false, dynamicColor = false) {
        Surface {
            PlacesAroundScreen(
                uiState = samplePlacesAroundState(),
                onBack = {},
                onSelectReach = {},
                onToggleSymbol = { _, _ -> },
                onRetrySearch = {},
                onOpenPlace = {},
                onAddPlace = {},
                onCloseCard = {},
                onShareCard = {},
                onOpenUrl = {},
                onDismissSaveRefusal = {}
            )
        }
    }
}
