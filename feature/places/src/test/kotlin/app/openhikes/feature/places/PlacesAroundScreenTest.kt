package app.openhikes.feature.places

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.TrailPlaceAnchor
import app.openhikes.core.model.TrailPlaceAroundEntry
import app.openhikes.core.model.TrailPlaceAroundListing
import app.openhikes.core.model.TrailPlaceReach
import app.openhikes.core.model.TrailPlaceRow
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.TrailPoint
import app.openhikes.core.ui.testing.OpenHikesTestTags
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlacesAroundScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersOnTrailAndNearbySectionsAndDispatchesReachChipAndAddActions() {
        var selectedReach: TrailPlaceReach? = null
        var addedPlaceId: String? = null
        var toggledSymbol: Pair<TrailPlaceSymbol, Boolean>? = null

        val listing = TrailPlaceAroundListing(
            onTrail = listOf(sampleEntry("hut-1", "Lakeshore Hut", TrailPlaceSymbol.SHELTER, 800.0, 12.0, false)),
            nearby = listOf(sampleEntry("summit-1", "Thumsee Kopf", TrailPlaceSymbol.SUMMIT, 1100.0, 420.0, true))
        )

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                PlacesAroundScreen(
                    uiState = PlacesAroundUiState(
                        phase = PlacesAroundPhase.FOUND,
                        reach = TrailPlaceReach.NEARBY,
                        listing = listing
                    ),
                    onBack = {},
                    onSelectReach = { selectedReach = it },
                    onToggleSymbol = { symbol, visible -> toggledSymbol = symbol to visible },
                    onRetrySearch = {},
                    onOpenPlace = {},
                    onAddPlace = { addedPlaceId = it },
                    onCloseCard = {},
                    onShareCard = {},
                    onOpenUrl = {},
                    onDismissSaveRefusal = {}
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.PLACES_AROUND_ON_TRAIL).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.PLACES_AROUND_NEARBY).assertIsDisplayed()
        composeRule.onNodeWithText("Lakeshore Hut").assertIsDisplayed()
        composeRule.onNodeWithText("Thumsee Kopf").assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.PLACES_AROUND_ADDED).assertIsDisplayed()

        composeRule.onNodeWithTag(OpenHikesTestTags.PLACES_AROUND_ADD).performClick()
        assertThat(addedPlaceId).isEqualTo("hut-1")

        composeRule.onNodeWithTag(
            OpenHikesTestTags.placesAroundReachOption(TrailPlaceReach.ON_TRAIL.name.lowercase())
        ).performClick()
        assertThat(selectedReach).isEqualTo(TrailPlaceReach.ON_TRAIL)

        composeRule.onNodeWithTag(OpenHikesTestTags.trailPlaceKindChip(TrailPlaceSymbol.WATER.rawValue)).performClick()
        assertThat(toggledSymbol).isEqualTo(TrailPlaceSymbol.WATER to false)
    }

    private fun sampleEntry(
        id: String,
        name: String,
        symbol: TrailPlaceSymbol,
        along: Double,
        offRoute: Double,
        isAdded: Boolean
    ): TrailPlaceAroundEntry = TrailPlaceAroundEntry(
        row = TrailPlaceRow(
            place = TrailPoint(
                id = id,
                hikeId = HikeId("h1"),
                coordinate = GeoCoordinate(47.7215, 12.841363),
                name = name,
                symbol = symbol.rawValue
            ),
            anchor = TrailPlaceAnchor(distanceAlongRouteMeters = along, offRouteMeters = offRoute)
        ),
        isAdded = isAdded
    )
}
