package app.openhikes.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.MapStyleId
import app.openhikes.core.model.UserSettings
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenHikesPreferencesDataSourceTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun emitsDefaultsOnEmptyDataStoreIncludingDefaultTrueFlags() = runTest {
        val dataStore = createDataStore(backgroundScope, tempFolder.newFile("prefs_defaults.pb"))
        val dataSource = OpenHikesPreferencesDataSource(dataStore)

        val settings = dataSource.userSettings.first()

        assertThat(settings).isEqualTo(UserSettings())
        assertThat(settings.showsHikingRoutes).isTrue()
        assertThat(settings.autoPauseEnabled).isTrue()
        assertThat(settings.hikeRemindersEnabled).isTrue()
        assertThat(settings.liveUpdatesEnabled).isTrue()
        assertThat(settings.keepScreenAwake).isFalse()
        assertThat(settings.autoSaveTilesEnabled).isFalse()
        assertThat(settings.savePhotosToLibrary).isFalse()
        assertThat(settings.distanceUnit).isEqualTo(DistanceUnit.METRIC)
        assertThat(settings.mapStyleId).isEqualTo(MapStyleId.OPENHIKES)
    }

    @Test
    fun distinguishesUnsetFromExplicitFalseOnDefaultTrueSettings() = runTest {
        val file = tempFolder.newFile("prefs_false.pb")
        val writerScope = CoroutineScope(coroutineContext + Job())
        val firstStore = createDataStore(writerScope, file)
        val firstSource = OpenHikesPreferencesDataSource(firstStore)

        firstSource.setShowsHikingRoutes(false)
        firstSource.setAutoPauseEnabled(false)
        firstSource.setHikeRemindersEnabled(false)
        firstSource.setLiveUpdatesEnabled(false)
        firstSource.setKeepScreenAwake(true)
        firstSource.setAutoSaveTilesEnabled(true)
        firstSource.setSavePhotosToLibrary(true)
        firstSource.setDistanceUnit(DistanceUnit.IMPERIAL)
        firstSource.setMapStyleId("custom-topo")
        writerScope.cancel()

        val readerStore = createDataStore(backgroundScope, file)
        val readerSource = OpenHikesPreferencesDataSource(readerStore)
        val reloaded = readerSource.userSettings.first()

        assertThat(reloaded.showsHikingRoutes).isFalse()
        assertThat(reloaded.autoPauseEnabled).isFalse()
        assertThat(reloaded.hikeRemindersEnabled).isFalse()
        assertThat(reloaded.liveUpdatesEnabled).isFalse()
        assertThat(reloaded.keepScreenAwake).isTrue()
        assertThat(reloaded.autoSaveTilesEnabled).isTrue()
        assertThat(reloaded.savePhotosToLibrary).isTrue()
        assertThat(reloaded.distanceUnit).isEqualTo(DistanceUnit.IMPERIAL)
        assertThat(reloaded.mapStyleId).isEqualTo("custom-topo")
    }

    @Test(expected = CorruptionException::class)
    fun serializerThrowsCorruptionExceptionOnMalformedBytes() = runTest {
        UserPreferencesSerializer.readFrom(ByteArrayInputStream(byteArrayOf(0x00, 0x7F, 0xFF.toByte())))
    }

    private fun createDataStore(scope: CoroutineScope, file: File): DataStore<UserPreferences> =
        DataStoreFactory.create(
            serializer = UserPreferencesSerializer,
            scope = scope,
            produceFile = { file }
        )
}
