package app.openhikes.feature.settings

import app.cash.turbine.test
import app.openhikes.core.model.AppBuildInfo
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.UserSettings
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.android.FakeUserSettingsRepository
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun emitsInitialSettingsAndPersistsEveryPreferenceUpdate() = runTest {
        val repository = FakeUserSettingsRepository()
        val viewModel = SettingsViewModel(
            userSettingsRepository = repository,
            appBuildInfo = AppBuildInfo(versionName = "0.1.0", versionCode = 1)
        )

        viewModel.uiState.test {
            val loading = awaitItem()
            assertThat(loading.appVersion).isEqualTo("0.1.0 (1)")
            assertThat(loading.isLoaded).isFalse()

            val initial = awaitItem()
            assertThat(initial.appVersion).isEqualTo("0.1.0 (1)")
            assertThat(initial.isLoaded).isTrue()
            assertThat(initial.settings.autoPauseEnabled).isTrue()
            assertThat(initial.settings.savePhotosToLibrary).isFalse()

            viewModel.onDistanceUnitChange(DistanceUnit.IMPERIAL)
            assertThat(awaitItem().settings.distanceUnit).isEqualTo(DistanceUnit.IMPERIAL)

            viewModel.onAutoPauseChange(false)
            assertThat(awaitItem().settings.autoPauseEnabled).isFalse()

            viewModel.onHikeRemindersChange(false)
            assertThat(awaitItem().settings.hikeRemindersEnabled).isFalse()

            viewModel.onLiveUpdatesChange(false)
            assertThat(awaitItem().settings.liveUpdatesEnabled).isFalse()

            viewModel.onKeepScreenAwakeChange(true)
            assertThat(awaitItem().settings.keepScreenAwake).isTrue()

            viewModel.onAutoSaveTilesChange(true)
            assertThat(awaitItem().settings.autoSaveTilesEnabled).isTrue()

            viewModel.onSavePhotosToLibraryChange(true)
            assertThat(awaitItem().settings.savePhotosToLibrary).isTrue()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun surfacesWriteIoExceptionAndRetriesFailedPreferenceSave() = runTest {
        val repository = FakeUserSettingsRepository()
        repository.nextWriteFailure = IOException("Disk full")
        val viewModel = SettingsViewModel(
            userSettingsRepository = repository,
            appBuildInfo = AppBuildInfo(versionName = "0.1.0", versionCode = 1)
        )

        viewModel.uiState.test {
            awaitItem() // loading
            val loaded = awaitItem()
            assertThat(loaded.saveError).isNull()
            assertThat(loaded.settings.savePhotosToLibrary).isFalse()

            viewModel.onSavePhotosToLibraryChange(true)
            val errored = awaitItem()
            assertThat(errored.saveError).isNotNull()
            assertThat(errored.settings.savePhotosToLibrary).isFalse()

            viewModel.onRetrySave()
            val updated = awaitItem()
            assertThat(updated.settings.savePhotosToLibrary).isTrue()
            val cleared = if (updated.saveError == null) updated else awaitItem()
            assertThat(cleared.saveError).isNull()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun fallsBackToDefaultSettingsWhenReadStreamThrowsIoException() = runTest {
        val failingRepository = FakeUserSettingsRepository(
            settingsFlowOverride = flow { throw IOException("Unreadable preferences file") }
        )
        val viewModel = SettingsViewModel(
            userSettingsRepository = failingRepository,
            appBuildInfo = AppBuildInfo(versionName = "0.1.0", versionCode = 1)
        )

        viewModel.uiState.test {
            awaitItem() // loading
            val recovered = awaitItem()
            assertThat(recovered.isLoaded).isTrue()
            assertThat(recovered.settings).isEqualTo(UserSettings())
            assertThat(recovered.saveError).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
