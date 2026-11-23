package app.openhikes.core.data

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.database.OpenHikesDatabase
import app.openhikes.core.database.OpenHikesLocalDatabase
import app.openhikes.core.database.dao.HikeLocalStateDao
import app.openhikes.core.database.entity.HikeLocalStateEntity
import app.openhikes.core.model.GpxImportFailure
import app.openhikes.core.model.GpxImportResult
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.PreparedRecording
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.RouteStyleUpdate
import app.openhikes.core.testing.TestClock
import app.openhikes.core.testing.TestFixtures
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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

    @Test
    fun importsLarge80000PointGpxAndReadsMapRoutesAndDetailWithoutCursorWindowFailure() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = createRepository(LaunchConfigHolder(LaunchConfig()), LinkedHashMap(), dispatcher)
        val gpxBytes = buildLargeGpxBytes(pointCount = 80_000)

        val result = repo.importGpxBytes(gpxBytes, "long-alpine.gpx") as GpxImportResult.Success
        val importedSummary = result.importedHikes.single()
        assertThat(importedSummary.routePointCount).isEqualTo(80_000)

        val mapRoute = repo.mapRoutes.first().single()
        assertThat(mapRoute.hikeId).isEqualTo(importedSummary.id)
        assertThat(mapRoute.route).hasSize(80_000)

        val detailHike = repo.observeHike(importedSummary.id).first()!!
        assertThat(detailHike.route).hasSize(80_000)
        assertThat(detailHike.route.first().elevationMeters).isNotNull()
        assertThat(detailHike.route.first().timestampEpochMillis).isNotNull()
    }

    @Test
    fun keepsPortableImportAuthoritativeAndRepairsMissingLocalStateAfterLocalUpsertFailure() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val failingLocalDao = FailingUpsertLocalStateDao(localDb.hikeLocalStateDao())
        val repo = createRepository(
            launchConfigHolder = LaunchConfigHolder(LaunchConfig()),
            writtenUris = LinkedHashMap(),
            dispatcher = dispatcher,
            localStateDao = failingLocalDao
        )

        failingLocalDao.failUpsert = true
        val gpxBytes = TestFixtures.thumseeLoopFastGpxBytes()
        val firstResult = repo.importGpxBytes(gpxBytes, "thumsee.gpx") as GpxImportResult.Success
        val firstSummary = firstResult.importedHikes.single()
        val expectedClimb = firstSummary.climbMeters
        val expectedDescent = firstSummary.descentMeters
        assertThat(expectedClimb).isGreaterThan(0.0)

        // Even while local DB upserts are failing, library query does not crash and repairs in-memory view.
        val summaryWhileLocalFailing = repo.hikes.first().single()
        assertThat(summaryWhileLocalFailing.climbMeters).isWithin(0.01).of(expectedClimb!!)
        assertThat(summaryWhileLocalFailing.descentMeters).isWithin(0.01).of(expectedDescent!!)
        assertThat(localDb.hikeLocalStateDao().getByHikeId(firstSummary.id.value)).isNull()

        // Once local storage recovers, library observation repairs and persists missing local state.
        failingLocalDao.failUpsert = false
        val repairedSummary = repo.hikes.first().single()
        assertThat(repairedSummary.climbMeters).isWithin(0.01).of(expectedClimb)
        val persistedLocal = localDb.hikeLocalStateDao().getByHikeId(firstSummary.id.value)
        assertThat(persistedLocal).isNotNull()
        assertThat(persistedLocal!!.climbMeters).isWithin(0.01).of(expectedClimb)

        // Direct detail local-state observation also repairs a wiped local row cleanly.
        localDb.hikeLocalStateDao().deleteByHikeId(firstSummary.id.value)
        assertThat(localDb.hikeLocalStateDao().getByHikeId(firstSummary.id.value)).isNull()
        val observedRepaired = repo.observeLocalState(firstSummary.id).first()!!
        assertThat(observedRepaired.climbMeters).isWithin(0.01).of(expectedClimb)
        assertThat(localDb.hikeLocalStateDao().getByHikeId(firstSummary.id.value)).isNotNull()
    }

    @Test
    fun managesRecordingDraftsEnforcesPositiveOwnershipAndPreservesDraftOnFailedFinalize() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val launchConfigHolder = LaunchConfigHolder(LaunchConfig(failsFirstSave = true))
        val repo = createRepository(launchConfigHolder, LinkedHashMap(), dispatcher)

        val ownedDraftId = HikeId("draft-owned")
        val foreignDraftId = HikeId("draft-foreign")

        // Create owned draft & unowned foreign draft (e.g. synced without local ownership)
        assertThat(repo.createOrClaimRecordingDraft(ownedDraftId, 10_000L, "Morning Hike").isSuccess).isTrue()
        assertThat(repo.createOrClaimRecordingDraft(foreignDraftId, 11_000L, "Foreign Draft").isSuccess).isTrue()
        localDb.hikeLocalStateDao().deleteByHikeId(foreignDraftId.value)

        // Drafts remain hidden from library listing and map route overlays
        assertThat(repo.hikes.first()).isEmpty()
        assertThat(repo.mapRoutes.first()).isEmpty()

        // Sweep deletes only owned orphan and leaves foreign unowned draft untouched
        val activeId = HikeId("draft-active")
        assertThat(repo.createOrClaimRecordingDraft(activeId, 12_000L, "Active Draft").isSuccess).isTrue()
        val swept = repo.deleteOwnedOrphanedRecordingDrafts(exceptHikeId = activeId).getOrThrow()
        assertThat(swept).isEqualTo(1)
        assertThat(repo.observeHike(ownedDraftId).first()).isNull()
        assertThat(repo.observeHike(foreignDraftId).first()?.isRecording).isTrue()

        // Failed finalize preserves active recording draft & inserts no walks
        val prepared = PreparedRecording(
            route = listOf(
                RouteCoordinate(47.7180, 12.8310, elevationMeters = 520.0, timestampEpochMillis = 12_000L),
                RouteCoordinate(47.7190, 12.8310, elevationMeters = 535.0, timestampEpochMillis = 22_000L)
            ),
            distanceMeters = 111.0,
            routeLengthMeters = 111.0,
            recordedSeconds = 10.0,
            elevationGainMeters = 15.0,
            elevationLossMeters = 0.0,
            startedAtEpochMillis = 12_000L
        )
        assertThat(
            repo.finalizeRecordingDraft(activeId, "Morning Hike", "Summit Day", 12_000L, 22_000L, prepared).isFailure
        ).isTrue()
        assertThat(repo.observeHike(activeId).first()?.isRecording).isTrue()
        assertThat(portableDb.hikeWalkDao().getByHikeId(activeId.value)).isEmpty()

        // Retry succeeds, publishes to library + map, clears local draft ownership, and persists HikeWalk
        val finalized = repo.finalizeRecordingDraft(
            activeId,
            "Morning Hike",
            "Summit Day",
            12_000L,
            22_000L,
            prepared
        ).getOrThrow()
        assertThat(finalized.isRecording).isFalse()
        assertThat(finalized.displayTitle).isEqualTo("Summit Day")
        assertThat(repo.hikes.first().map { it.id }).containsExactly(activeId)
        assertThat(repo.mapRoutes.first().map { it.hikeId }).containsExactly(activeId)
        assertThat(repo.observeLocalState(activeId).first()?.ownsRecordingDraft).isFalse()
        assertThat(portableDb.hikeWalkDao().getByHikeId(activeId.value)).hasSize(1)
    }

    @Test
    fun roomConstraintFailureOnWalkInsertReturnsFailureAndLeavesDraftRetryable() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = createRepository(LaunchConfigHolder(LaunchConfig.Production), LinkedHashMap(), dispatcher)
        val activeId = HikeId("draft-walk-constraint")
        assertThat(repo.createOrClaimRecordingDraft(activeId, 12_000L, "Ridge Trial").isSuccess).isTrue()

        val prepared = PreparedRecording(
            route = listOf(
                RouteCoordinate(47.71842, 12.83177, 533.0, 12_000L),
                RouteCoordinate(47.71910, 12.83080, 548.0, 22_000L)
            ),
            distanceMeters = 95.0,
            routeLengthMeters = 95.0,
            recordedSeconds = 10.0,
            elevationGainMeters = 15.0,
            elevationLossMeters = null,
            startedAtEpochMillis = 12_000L
        )

        portableDb.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER block_walk_insert BEFORE INSERT ON hike_walks " +
                "BEGIN SELECT RAISE(ABORT, 'forced walk failure'); END;"
        )
        val failed = repo.finalizeRecordingDraft(activeId, "Ridge Trial", null, 12_000L, 22_000L, prepared)
        assertThat(failed.isFailure).isTrue()
        assertThat(failed.exceptionOrNull()).isInstanceOf(SQLiteConstraintException::class.java)
        assertThat(repo.observeHike(activeId).first()?.isRecording).isTrue()
        assertThat(repo.hikes.first()).isEmpty()

        portableDb.openHelper.writableDatabase.execSQL("DROP TRIGGER block_walk_insert")
        val retried = repo.finalizeRecordingDraft(activeId, "Ridge Trial", null, 12_000L, 22_000L, prepared)
        assertThat(retried.isSuccess).isTrue()
        assertThat(repo.hikes.first().map { it.id }).containsExactly(activeId)
        assertThat(portableDb.hikeWalkDao().getByHikeId(activeId.value)).hasSize(1)
    }

    private fun createRepository(
        launchConfigHolder: LaunchConfigHolder,
        writtenUris: MutableMap<String, String>,
        dispatcher: CoroutineDispatcher,
        localStateDao: HikeLocalStateDao = localDb.hikeLocalStateDao()
    ): OfflineFirstHikeRepository = OfflineFirstHikeRepository(
        database = portableDb,
        hikeDao = portableDb.hikeDao(),
        trailPointDao = portableDb.trailPointDao(),
        hikeWalkDao = portableDb.hikeWalkDao(),
        hikeLocalStateDao = localStateDao,
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

    private fun buildLargeGpxBytes(pointCount: Int): ByteArray {
        val sb = StringBuilder(pointCount * 96 + 256)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk><name>Long Alpine</name><trkseg>\n")
        for (i in 0 until pointCount) {
            val lat = 47.0 + i * 0.00001
            val ele = 500.0 + (i % 300) * 0.5
            val totalSeconds = i
            val sec = totalSeconds % 60
            val min = (totalSeconds / 60) % 60
            val hour = (totalSeconds / 3600) % 24
            val day = 1 + (totalSeconds / 86400)
            sb.append(
                String.format(
                    Locale.US,
                    "    <trkpt lat=\"%.6f\" lon=\"12.800000\"><ele>%.1f</ele>" +
                        "<time>2026-01-%02dT%02d:%02d:%02dZ</time></trkpt>\n",
                    lat,
                    ele,
                    day,
                    hour,
                    min,
                    sec
                )
            )
        }
        sb.append("  </trkseg></trk>\n</gpx>\n")
        return sb.toString().encodeToByteArray()
    }

    private class FailingUpsertLocalStateDao(private val delegate: HikeLocalStateDao) : HikeLocalStateDao {
        var failUpsert: Boolean = false

        override fun observeAll(): Flow<List<HikeLocalStateEntity>> = delegate.observeAll()

        override fun observeByHikeId(hikeId: String): Flow<HikeLocalStateEntity?> = delegate.observeByHikeId(hikeId)

        override suspend fun getByHikeId(hikeId: String): HikeLocalStateEntity? = delegate.getByHikeId(hikeId)

        override suspend fun getOwnedRecordingDraftIds(): List<String> = delegate.getOwnedRecordingDraftIds()

        override suspend fun getWithWalkInProgress(): List<HikeLocalStateEntity> = delegate.getWithWalkInProgress()

        override suspend fun upsert(entity: HikeLocalStateEntity) {
            if (failUpsert) {
                throw SQLiteException("Simulated local DB write failure")
            }
            delegate.upsert(entity)
        }

        override suspend fun deleteByHikeId(hikeId: String): Int = delegate.deleteByHikeId(hikeId)
    }
}
