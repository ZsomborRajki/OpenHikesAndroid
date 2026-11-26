package app.openhikes.feature.settings

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.UserSettings
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "Settings Light", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
fun SettingsScreenLightPreview() {
    SettingsPreviewSurface(darkTheme = false)
}

@PreviewTest
@Preview(name = "Settings Dark", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
fun SettingsScreenDarkPreview() {
    SettingsPreviewSurface(darkTheme = true)
}

@PreviewTest
@Preview(name = "Settings Large Font 200%", showBackground = true, widthDp = 411, heightDp = 891, fontScale = 2.0f)
@Composable
fun SettingsScreenLargeFontPreview() {
    SettingsPreviewSurface(darkTheme = false)
}

@Composable
private fun SettingsPreviewSurface(darkTheme: Boolean) {
    OpenHikesTheme(darkTheme = darkTheme, dynamicColor = false) {
        Surface {
            SettingsScreen(
                uiState = SettingsUiState(
                    settings = UserSettings(
                        distanceUnit = DistanceUnit.METRIC,
                        autoPauseEnabled = true,
                        hikeRemindersEnabled = true,
                        liveUpdatesEnabled = true,
                        keepScreenAwake = false,
                        autoSaveTilesEnabled = true,
                        savePhotosToLibrary = false
                    ),
                    appVersion = "0.1.0 (1)",
                    photoStorageBytes = 0L,
                    isLoaded = true
                ),
                onClose = {},
                onDistanceUnitChange = {},
                onAutoPauseChange = {},
                onHikeRemindersChange = {},
                onLiveUpdatesChange = {},
                onKeepScreenAwakeChange = {},
                onAutoSaveTilesChange = {},
                onSavePhotosToLibraryChange = {},
                onOpenUrl = {}
            )
        }
    }
}
