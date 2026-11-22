package app.openhikes.feature.hikes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.RouteTint
import app.openhikes.core.ui.testing.OpenHikesTestTags
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RouteStyleScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectsColorBorderPatternAndShowsErrorBanner() {
        var uiState by mutableStateOf(
            RouteStyleUiState(
                hikeId = HikeId("h1"),
                title = "Thumsee",
                isLoaded = true
            )
        )
        var backClicked = false

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                RouteStyleScreen(
                    uiState = uiState,
                    onBack = { backClicked = true },
                    onTintSelected = { uiState = uiState.copy(tintHex = it) },
                    onBorderSelected = { uiState = uiState.copy(borderHex = it) },
                    onWidthChanged = { uiState = uiState.copy(widthDp = it) },
                    onPatternSelected = { uiState = uiState.copy(linePattern = it) },
                    onDismissError = { uiState = uiState.copy(hasSaveError = false) }
                )
            }
        }

        val secondColor = RouteTint.palette[1]
        composeRule.onNodeWithTag(OpenHikesTestTags.routeColorSwatch(secondColor)).performClick()
        assertThat(uiState.tintHex).isEqualTo(secondColor)

        composeRule.onNodeWithTag(OpenHikesTestTags.routeBorderSwatch(RouteBorder.WHITE_HEX)).performClick()
        assertThat(uiState.borderHex).isEqualTo(RouteBorder.WHITE_HEX)

        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STYLE_SCREEN).performScrollToKey("line")
        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_WIDTH_SLIDER).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.routePattern(RouteLinePattern.DASHED.id))
            .performScrollTo()
            .performClick()
        assertThat(uiState.linePattern).isEqualTo(RouteLinePattern.DASHED)
        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_PATTERN_CAPTION).assertTextEquals("Dashed")

        uiState = uiState.copy(hasSaveError = true)
        composeRule.onNodeWithTag(OpenHikesTestTags.HIKE_ACTION_ERROR).assertIsDisplayed()

        composeRule.onNodeWithTag(OpenHikesTestTags.ROUTE_STYLE_BACK).performClick()
        assertThat(backClicked).isTrue()
    }
}
