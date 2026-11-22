package app.openhikes.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.openhikes.core.designsystem.theme.OpenHikesDarkColorScheme
import app.openhikes.core.designsystem.theme.OpenHikesLightColorScheme
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenHikesThemeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lightThemeAppliesFallbackPalette() {
        var resolvedPrimary = androidx.compose.ui.graphics.Color.Unspecified
        composeRule.setContent {
            OpenHikesTheme(darkTheme = false, dynamicColor = false) {
                resolvedPrimary = MaterialTheme.colorScheme.primary
                Text(text = "Light Trail")
            }
        }

        composeRule.onNodeWithText("Light Trail").assertIsDisplayed()
        assertThat(resolvedPrimary).isEqualTo(OpenHikesLightColorScheme.primary)
    }

    @Test
    fun darkThemeAppliesFallbackPalette() {
        var resolvedPrimary = androidx.compose.ui.graphics.Color.Unspecified
        composeRule.setContent {
            OpenHikesTheme(darkTheme = true, dynamicColor = false) {
                resolvedPrimary = MaterialTheme.colorScheme.primary
                Text(text = "Dark Trail")
            }
        }

        composeRule.onNodeWithText("Dark Trail").assertIsDisplayed()
        assertThat(resolvedPrimary).isEqualTo(OpenHikesDarkColorScheme.primary)
    }
}
