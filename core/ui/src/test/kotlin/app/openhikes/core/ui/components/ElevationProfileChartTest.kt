package app.openhikes.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteProfile
import app.openhikes.core.ui.testing.OpenHikesTestTags
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ElevationProfileChartTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tapDragAndAccessibilityActionsUpdateAndRetainScrubPosition() {
        val route = (0..10).map { idx ->
            RouteCoordinate(
                latitude = 47.70 + idx * 0.001,
                longitude = 12.80,
                elevationMeters = 500.0 + idx * 10.0
            )
        }
        val profile = RouteProfile.from(route)
        var latestDistance = 0.0

        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                var scrubDistance by remember { mutableDoubleStateOf(0.0) }
                Box(modifier = Modifier.width(360.dp)) {
                    ElevationProfileChart(
                        profile = profile,
                        distanceUnit = DistanceUnit.METRIC,
                        tintHex = "#1B7F3B",
                        scrubDistanceProvider = { scrubDistance },
                        onScrubDistance = {
                            scrubDistance = it
                            latestDistance = it
                        }
                    )
                }
            }
        }

        val chart = composeRule.onNodeWithTag(OpenHikesTestTags.ELEVATION_CHART)
        chart.assertIsDisplayed()
        composeRule.onNodeWithTag(OpenHikesTestTags.ELEVATION_READOUT).assertIsDisplayed()

        // Tap at 50% width updates scrub position.
        chart.performTouchInput { click(Offset(width * 0.5f, centerY)) }
        composeRule.waitForIdle()
        assertThat(latestDistance).isWithin(profile.totalDistanceMeters * 0.1)
            .of(profile.totalDistanceMeters * 0.5)

        // Drag to 80% and release: scrub position must remain retained after up().
        chart.performTouchInput {
            down(Offset(width * 0.2f, centerY))
            moveTo(Offset(width * 0.8f, centerY))
            up()
        }
        composeRule.waitForIdle()
        assertThat(latestDistance).isWithin(profile.totalDistanceMeters * 0.1)
            .of(profile.totalDistanceMeters * 0.8)

        // Accessibility forward/backward actions step cleanly along the profile.
        val beforeStep = latestDistance
        val node = chart.fetchSemanticsNode()
        val actions = node.config[SemanticsActions.CustomActions]
        assertThat(actions.map { it.label }).containsAtLeast(
            "Step forward along trail",
            "Step backward along trail"
        )
        assertThat(node.config[SemanticsProperties.StateDescription]).contains("along the trail")

        actions.first { it.label == "Step backward along trail" }.action()
        composeRule.waitForIdle()
        assertThat(latestDistance).isLessThan(beforeStep)
    }
}
