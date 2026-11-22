package app.openhikes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.openhikes.core.ui.testing.OpenHikesTestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class MainActivityInstrumentedTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
        composeRule.enableAccessibilityChecks()
    }

    @Test
    fun launchesWithAccessibleRootShell() {
        composeRule.onNodeWithTag(OpenHikesTestTags.APP_ROOT).assertExists()
        composeRule.onNodeWithText("OpenHikes").assertIsDisplayed()
    }
}
