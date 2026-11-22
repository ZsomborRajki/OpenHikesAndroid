package app.openhikes

import android.content.pm.ApplicationInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.ui.testing.OpenHikesTestTags
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class MainActivitySmokeTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun launchesRootShellAndRendersAppName() {
        composeRule.onNodeWithTag(OpenHikesTestTags.APP_ROOT).assertExists()
        composeRule.onNodeWithText("OpenHikes").assertIsDisplayed()
    }

    @Test
    fun manifestDisablesAutomaticBackup() {
        val app = ApplicationProvider.getApplicationContext<HiltTestApplication>()
        val allowBackupFlag = app.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP
        assertThat(allowBackupFlag).isEqualTo(0)
    }
}
