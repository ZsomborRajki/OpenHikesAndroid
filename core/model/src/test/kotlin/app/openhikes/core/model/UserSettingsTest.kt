package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UserSettingsTest {
    @Test
    fun defaultsMatchOpenHikesPrivacyAndFieldPosture() {
        val defaults = UserSettings()

        assertThat(defaults.distanceUnit).isEqualTo(DistanceUnit.METRIC)
        assertThat(defaults.showsHikingRoutes).isTrue()
        assertThat(defaults.autoPauseEnabled).isTrue()
        assertThat(defaults.hikeRemindersEnabled).isTrue()
        assertThat(defaults.liveUpdatesEnabled).isTrue()
        assertThat(defaults.keepScreenAwake).isFalse()
        assertThat(defaults.autoSaveTilesEnabled).isFalse()
        assertThat(defaults.savePhotosToLibrary).isFalse()
        assertThat(defaults.mapStyleId).isEqualTo(MapStyleId.OPENHIKES)
    }

    @Test
    fun appBuildInfoFormatsDisplayVersion() {
        val info = AppBuildInfo(versionName = "0.1.0", versionCode = 1)
        assertThat(info.displayVersion).isEqualTo("0.1.0 (1)")
    }
}
