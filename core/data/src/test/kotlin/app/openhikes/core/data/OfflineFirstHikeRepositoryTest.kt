package app.openhikes.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.database.OpenHikesDatabase
import app.openhikes.core.database.OpenHikesLocalDatabase
import app.openhikes.core.model.GpxImportFailure
import app.openhikes.core.model.GpxImportResult
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.RouteStyleUpdate
import app.openhikes.core.testing.TestClock
import app.openhikes.core.testing.TestFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OfflineFirstHikeRepositoryTest {
    private lateinit var portableDb: OpenHikesDatabase
    private lateinit var localDb: OpenHikesLocalDatabase

    @Before
    fun setUp() {
        portableDb = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OpenHikesDatabase::class.java
        ).allowMainThreadQueries().build()
        localDb = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OpenHikesLocalDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        portableDb.close()
        localDb.close()
    }

    @Test
    fun importsExportsRenamesStylesAndDeletesHikesAndHonorsLaunchOverrides() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val launchConfigHolder = LaunchConfigHolder(
            LaunchConfig(importedGpxFixtureName = TestFixtures.THUMSEE_LOOP_FAST_NAME)
        )
        val writtenUris = LinkedHashMap<String, String>()
        val repo = createRepository(launchConfigHolder, writtenUris, dispatcher)

        val seededId = repo.seedFixtureIfConfigured()!!
        assertThat(repo.seedFixtureIfConfigured()).isNull() // idempotent once seeded
        val summary = repo.hikes.first().single()
        assertThat(summary.id).isEqualTo(seededId)
        assertThat(summary.displayTitle).isEqualTo(TestFixtures.THUMSEE_LOOP_FAST_TITLE)
        assertThat(repo.observeLocalState(seededId).first()).isNotNull()

        repo.renameHike(seededId, "Morning Thumsee")
        assertThat(repo.observeHike(seededId).first()?.displayTitle).isEqualTo("Morning Thumsee")

        repo.updateRouteStyle(
            seededId,
            RouteStyleUpdate("#D85A17", RouteBorder.WHITE_HEX, 5.5, RouteLinePattern.DASHED)
        )
        val updatedMapRoute = repo.mapRoutes.first().single()
        assertThat(updatedMapRoute.tintHex).isEqualTo("#D85A17")
        assertThat(updatedMapRoute.linePattern).isEqualTo(RouteLinePattern.DASHED)

        val exportResult = repo.exportHikeToUri(seededId, "content://exports/thumsee.gpx")
        assertThat(exportResult.isSuccess).isTrue()
        assertThat(writtenUris["content://exports/thumsee.gpx"]).contains("Morning Thumsee")

        launchConfigHolder.mergeWith { it.copy(refusesHikeDeletion = true) }
        assertThat(repo.deleteHike(seededId).isFailure).isTrue()
        assertThat(repo.hikes.first()).hasSize(1)

        launchConfigHolder.mergeWith { it.copy(refusesHikeDeletion = false) }
        assertThat(repo.deleteHike(seededId).isSuccess).isTrue()
        assertThat(repo.hikes.first()).isEmpty()
        assertThat(repo.observeLocalState(seededId).first()).isNull()
    }

    @Test
    fun simulatesFirstSaveFailureOnceAndHandlesUriImportErrors() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val launchConfigHolder = LaunchConfigHolder(LaunchConfig(failsFirstSave = true))
        val repo = createRepository(launchConfigHolder, LinkedHashMap(), dispatcher)

        val imported = (repo.importGpxUri("content://missing") as GpxImportResult.Failure)
        assertThat(imported.reason).isEqualTo(GpxImportFailure.UNREADABLE)

        val paused = (
            repo.importGpxBytes(TestFixtures.samplePausedGpxXml().encodeToByteArray(), "paused.gpx")
                as GpxImportResult.Success
            ).importedHikes.single()

        assertThat(repo.renameHike(paused.id, "First Fail").isFailure).isTrue()
        assertThat(repo.renameHike(paused.id, "Second Succeeds").isSuccess).isTrue()
        assertThat(repo.observeHike(paused.id).first()?.displayTitle).isEqualTo("Second Succeeds")
    }

    private fun createRepository(
        launchConfigHolder: LaunchConfigHolder,
        writtenUris: MutableMap<String, String>,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher
    ): OfflineFirstHikeRepository = OfflineFirstHikeRepository(
        database = portableDb,
        hikeDao = portableDb.hikeDao(),
        trailPointDao = portableDb.trailPointDao(),
        hikeLocalStateDao = localDb.hikeLocalStateDao(),
        gpxFileSource = object : GpxFileSource {
            override suspend fun read(uriString: String): GpxFilePayload? = null
        },
        gpxFileSink = object : GpxFileSink {
            override suspend fun write(uriString: String, xml: String): Result<Unit> {
                writtenUris[uriString] = xml
                return Result.success(Unit)
            }
        },
        bundledGpxFixtureSource = object : BundledGpxFixtureSource {
            override suspend fun loadFixture(fixtureName: String): Pair<String, ByteArray>? =
                if (fixtureName == TestFixtures.THUMSEE_LOOP_FAST_NAME) {
                    "${TestFixtures.THUMSEE_LOOP_FAST_NAME}.gpx" to TestFixtures.thumseeLoopFastGpxBytes()
                } else {
                    null
                }
        },
        launchConfigHolder = launchConfigHolder,
        clock = TestClock(),
        ioDispatcher = dispatcher,
        defaultDispatcher = dispatcher
    )
}
