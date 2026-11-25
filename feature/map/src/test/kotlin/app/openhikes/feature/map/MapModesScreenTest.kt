package app.openhikes.feature.map

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.Density
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.MapStyleId
import app.openhikes.core.ui.testing.OpenHikesTestTags
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class MapModesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun displaysCardsSummaryHikingRoutesOfflineRowAndAttributionActions() {
        var selectedStyle: String? = null
        var hikingRoutesChoice: Boolean? = null
        var openedOfflineMaps = false
        var closed = false
        val openedUrls = mutableListOf<String>()

        val state = MapModesUiState(
            installedCountryNames = listOf("Austria", "Germany"),
            installedByteCount = 2_410_000_000L
        )

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                MapModesScreen(
                    uiState = state,
                    onClose = { closed = true },
                    onSelectStyle = { selectedStyle = it },
                    onSetShowsHikingRoutes = { hikingRoutesChoice = it },
                    onOpenOfflineMaps = { openedOfflineMaps = true },
                    onOpenUrl = { openedUrls.add(it) }
                )
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_TITLE).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.mapStyleCard(MapStyleId.OPENHIKES)).assertIsSelected()
        composeRule.onNodeWithTag(OpenHikesTestTags.mapStyleCard(MapStyleId.OPENFREEMAP))
            .assertIsNotSelected()
            .performClick()
        assertThat(selectedStyle).isEqualTo(MapStyleId.OPENFREEMAP)

        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_STYLE_ROW).performScrollToKey(MapStyleId.STADIA_OUTDOORS)
        composeRule.onNodeWithTag(OpenHikesTestTags.mapStyleCard(MapStyleId.STADIA_OUTDOORS)).assertIsNotEnabled()

        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_SCREEN).performScrollToKey("hiking-routes")
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKING_ROUTES_ON).assertIsSelected()
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKING_ROUTES_OFF)
            .assertIsNotSelected()
            .performClick()
        assertThat(hikingRoutesChoice).isFalse()

        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_SCREEN).performScrollToKey("offline-maps")
        composeRule.onNodeWithText("Austria and Germany · 2.41 GB").assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_OFFLINE_MAPS).performClick()
        assertThat(openedOfflineMaps).isTrue()

        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_SCREEN).performScrollToKey("footer")
        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_API_KEY_NOTE).assertIsDisplayed()
        val attributionNode = composeRule.onNodeWithTag(OpenHikesTestTags.TILE_ATTRIBUTION).fetchSemanticsNode()
        val customActions = attributionNode.config[SemanticsActions.CustomActions]
        assertThat(customActions.map { it.label }).contains("Open OpenStreetMap contributors")
        customActions.first { it.label == "Open OpenStreetMap contributors" }.action()
        assertThat(openedUrls).containsExactly("https://www.openstreetmap.org/copyright")

        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_CLOSE).performClick()
        assertThat(closed).isTrue()
    }

    @Test
    fun largeFontScale200PercentKeepsAllControlsDisplayed() {
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale = 2.0f)) {
                OpenHikesTheme(dynamicColor = false) {
                    MapModesScreen(
                        uiState = MapModesUiState(),
                        onClose = {},
                        onSelectStyle = {},
                        onSetShowsHikingRoutes = {},
                        onOpenOfflineMaps = {},
                        onOpenUrl = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_TITLE).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_SCREEN).performScrollToKey("hiking-routes")
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKING_ROUTES_OFF).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKING_ROUTES_ON).assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_SCREEN).performScrollToKey("offline-maps")
        composeRule.onNodeWithTag(OpenHikesTestTags.MAP_MODES_OFFLINE_MAPS).assertIsDisplayed()
    }
}
