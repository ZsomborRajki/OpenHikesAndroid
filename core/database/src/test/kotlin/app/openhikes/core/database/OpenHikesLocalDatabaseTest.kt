package app.openhikes.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.openhikes.core.database.entity.HikeLocalStateEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenHikesLocalDatabaseTest {
    private lateinit var localDb: OpenHikesLocalDatabase

    @Before
    fun setUp() {
        localDb = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OpenHikesLocalDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        localDb.close()
    }

    @Test
    fun upsertsObservesAndDeletesDeviceLocalHikeState() = runTest {
        val dao = localDb.hikeLocalStateDao()
        val entity = HikeLocalStateEntity(
            hikeId = "local-1",
            listOrder = 1,
            climbMeters = 140.0,
            descentMeters = 135.0,
            elevationRouteRevision = "rev-1",
            ownsRecordingDraft = false,
            autoSavedTileKeysJson = "[\"t1\"]"
        )
        dao.upsert(entity)

        dao.observeByHikeId("local-1").test {
            assertThat(awaitItem()).isEqualTo(entity)
            dao.deleteByHikeId("local-1")
            assertThat(awaitItem()).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
