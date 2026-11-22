package app.openhikes.core.data

import androidx.datastore.core.DataStoreFactory
import app.cash.turbine.test
import app.openhikes.core.datastore.OpenHikesPreferencesDataSource
import app.openhikes.core.datastore.UserPreferencesSerializer
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.UserSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineFirstUserSettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun emitsUpdatedSettingsAcrossMainSafeMutations() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val dataStore = DataStoreFactory.create(
            serializer = UserPreferencesSerializer,
            scope = backgroundScope,
            produceFile = { tempFolder.newFile("repo_prefs.pb") }
        )
        val repository: UserSettingsRepository = OfflineFirstUserSettingsRepository(
            preferencesDataSource = OpenHikesPreferencesDataSource(dataStore),
            ioDispatcher = dispatcher
        )

        repository.userSettings.test {
            assertThat(awaitItem()).isEqualTo(UserSettings())

            repository.setDistanceUnit(DistanceUnit.IMPERIAL)
            assertThat(awaitItem().distanceUnit).isEqualTo(DistanceUnit.IMPERIAL)

            repository.setShowsHikingRoutes(false)
            assertThat(awaitItem().showsHikingRoutes).isFalse()

            repository.setAutoSaveTilesEnabled(true)
            assertThat(awaitItem().autoSaveTilesEnabled).isTrue()

            cancelAndIgnoreRemainingEvents()
        }
    }
}
