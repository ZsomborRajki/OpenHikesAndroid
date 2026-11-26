package app.openhikes.feature.places

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.TrailPlaceAnchor
import app.openhikes.core.model.TrailPlaceFact
import app.openhikes.core.model.TrailPlaceOsm
import app.openhikes.core.model.TrailPlaceRow
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.TrailPoint
import app.openhikes.core.testing.FakeTrailPointSource
import app.openhikes.core.testing.FakeWikidataPlaceSource
import app.openhikes.core.ui.testing.OpenHikesTestTags
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HikePlaceDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun ownPlaceShowsEditNoteAndRemoveWhileOmittingOsmLink() {
        var editClicked = false
        var removeClicked = false
        val ownRow = TrailPlaceRow(
            place = TrailPoint(
                id = "own-1",
                hikeId = HikeId("h1"),
                coordinate = GeoCoordinate(47.7185, 12.8318),
                name = "Boathouse",
                symbol = TrailPlaceSymbol.SHELTER.rawValue,
                note = "Rowing boats in summer"
            ),
            anchor = TrailPlaceAnchor(distanceAlongRouteMeters = 250.0, offRouteMeters = 5.0)
        )

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                HikePlaceDetailScreen(
                    uiState = HikePlaceDetailUiState(row = ownRow, isLoaded = true),
                    onBack = {},
                    onShowOnMap = {},
                    onShare = {},
                    onStartEdit = { editClicked = true },
                    onEditNameChange = {},
                    onEditSymbolChange = {},
                    onEditNoteChange = {},
                    onSaveEdit = {},
                    onCancelEdit = {},
                    onRequestRemove = { removeClicked = true },
                    onConfirmRemove = {},
                    onCancelRemove = {},
                    onDismissSaveRefusal = {},
                    onOpenUrl = {}
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText("Boathouse").assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_NOTE).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_PLACE_OSM_LINK).assertDoesNotExist()
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_EDIT).performClick()
        assertThat(editClicked).isTrue()

        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_REMOVE).performScrollTo().performClick()
        assertThat(removeClicked).isTrue()
    }

    @Test
    fun osmPlaceWithWikidataHidesEditShowsOsmLinkWikipediaAndPhotoCredit() {
        val openedUrls = mutableListOf<String>()
        val osmRow = TrailPlaceRow(
            place = TrailPoint(
                id = "osm-1",
                hikeId = HikeId("h1"),
                coordinate = GeoCoordinate(47.7215, 12.841363),
                name = "Thumsee Kopf",
                symbol = TrailPlaceSymbol.SUMMIT.rawValue,
                osm = TrailPlaceOsm(
                    elementType = "node",
                    elementId = 9_100_004L,
                    facts = listOf(TrailPlaceFact(TrailPlaceFact.Kind.ELEVATION, "712")),
                    wikidata = FakeTrailPointSource.SEEDED_WIKIDATA_ITEM
                )
            ),
            anchor = TrailPlaceAnchor(distanceAlongRouteMeters = 900.0, offRouteMeters = 420.0)
        )

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                HikePlaceDetailScreen(
                    uiState = HikePlaceDetailUiState(
                        row = osmRow,
                        wikidataPlace = FakeWikidataPlaceSource.seededThumseeKopf(),
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
                    onOpenUrl = { openedUrls.add(it) }
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_PLACE_EDIT).assertDoesNotExist()
        composeRule.onNodeWithTag(OpenHikesTestTags.WIKIDATA_PLACE_EXTRACT).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.WIKIDATA_PLACE_CREDIT).performScrollTo().performClick()
        composeRule.onNodeWithText("Author: ${FakeWikidataPlaceSource.SEEDED_AUTHOR}").assertIsDisplayed()
        composeRule.onNodeWithText("Close").performClick()

        composeRule.onNodeWithTag(OpenHikesTestTags.WIKIDATA_PLACE_TEXT_LICENSE).performScrollTo().performClick()
        composeRule.onNodeWithTag(OpenHikesTestTags.TRAIL_PLACE_OSM_LINK).performScrollTo().performClick()
        assertThat(openedUrls).contains("https://www.openstreetmap.org/node/9100004")
    }
}
