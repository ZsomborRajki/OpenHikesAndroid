package app.openhikes.core.ui.testing

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenHikesTestTagsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun attachesAndResolvesSharedSemanticTags() {
        composeRule.setContent {
            OpenHikesTheme(dynamicColor = false) {
                Text(
                    text = "Austria",
                    modifier = Modifier.testTag(OpenHikesTestTags.offlineCountry("AT"))
                )
            }
        }

        assertThat(OpenHikesTestTags.offlineCountry("AT")).isEqualTo("offline-country-AT")
        composeRule.onNodeWithTag("offline-country-AT").assertIsDisplayed()
    }
}
