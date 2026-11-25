package app.openhikes

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.testing.TestFixtures
import app.openhikes.core.ui.testing.OpenHikesTestTags
import app.openhikes.launch.LaunchConfigReader
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class WalkInstrumentedTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Before
    fun setUp() {
        hiltRule.inject()
        composeRule.enableAccessibilityChecks()
    }

    @Test
    fun seededWalkHistorySummaryAndManualFollowControlsPassAccessibilityChecks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(LaunchConfigReader.EXTRA_UI_TESTING, "true")
            putExtra(LaunchConfigReader.EXTRA_EXPANDED_SHEET, "true")
            putExtra(LaunchConfigReader.EXTRA_IN_MEMORY_DB, "true")
            putExtra(LaunchConfigReader.EXTRA_ISOLATED_DATASTORE, "true")
            putExtra(LaunchConfigReader.EXTRA_OFFLINE, "true")
            putExtra(LaunchConfigReader.EXTRA_ENABLE_LOCATION, "true")
            putExtra(LaunchConfigReader.EXTRA_DISABLE_AUTO_RECOVERY, "true")
            putExtra(LaunchConfigReader.EXTRA_IMPORT_GPX, TestFixtures.THUMSEE_LOOP_FAST_NAME)
            putExtra(LaunchConfigReader.EXTRA_SEED_WALKS, TestFixtures.SEED_WALK_HALF_LOOP)
        }

        ActivityScenario.launch<MainActivity>(intent).use {
            composeRule.waitUntilAtLeastOneExists(hasText("Thumsee Loop", substring = true), WAIT_TIMEOUT_MILLIS)
            composeRule.onNode(hasText("Thumsee Loop", substring = true)).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.HIKE_DETAIL_SCREEN), WAIT_TIMEOUT_MILLIS)
            composeRule.onRoot().tryPerformAccessibilityChecks()

            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_TOGGLE).performClick()
            composeRule.waitUntilExactlyOneExists(hasTestTag(OpenHikesTestTags.WALK_CONTROLS), WAIT_TIMEOUT_MILLIS)
            composeRule.onRoot().tryPerformAccessibilityChecks()

            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_SEGMENT_HISTORY).performClick()
            val rowMatcher = SemanticsMatcher("walk row") { node ->
                node.config.getOrNull(SemanticsProperties.TestTag)
                    ?.startsWith("${OpenHikesTestTags.WALK_ROW}-") == true
            }
            composeRule.waitUntilExactlyOneExists(rowMatcher, WAIT_TIMEOUT_MILLIS)
            composeRule.onNode(rowMatcher).performClick()

            composeRule.waitUntilExactlyOneExists(
                hasTestTag(OpenHikesTestTags.WALK_SUMMARY_SCREEN),
                WAIT_TIMEOUT_MILLIS
            )
            composeRule.onNodeWithTag(OpenHikesTestTags.WALK_COMPLETION)
                .assertIsDisplayed()
                .assertContentDescriptionContains("50 percent complete", substring = true)
            composeRule.onRoot().tryPerformAccessibilityChecks()
        }
    }

    private companion object {
        const val WAIT_TIMEOUT_MILLIS = 5_000L
    }
}
