package app.openhikes.core.data

import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.database.OpenHikesDatabase
import app.openhikes.core.database.OpenHikesLocalDatabase
import app.openhikes.core.database.dao.HikeLocalStateDao
import app.openhikes.core.database.entity.HikeLocalStateEntity
import app.openhikes.core.model.GpxImportResult
import app.openhikes.core.model.TrailWalkCoverage
import app.openhikes.core.model.TrailWalkEndReason
import app.openhikes.core.model.TrailWalkRecord
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
class WalkPersistenceStoreTest {
    private lateinit var portableDb: OpenHikesDatabase
    private lateinit var localDb: OpenHikesLocalDatabase
    private lateinit var localDao: ControllableLocalStateDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        portableDb = Room.inMemoryDatabaseBuilder(context, OpenHikesDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        localDb = Room.inMemoryDatabaseBuilder(context, OpenHikesLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        localDao = ControllableLocalStateDao(localDb.hikeLocalStateDao())
    }

    @After
    fun tearDown() {
        portableDb.close()
        localDb.close()
    }

    @Test
    fun persistsOpenWalkFinishesIntoHistoryAndRollsBackOnLocalClearFailure() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = createRepository(LaunchConfig(), dispatcher)
        val importResult = repo.importGpxBytes(
            TestFixtures.thumseeLoopFastGpxBytes(),
            "${TestFixtures.THUMSEE_LOOP_FAST_NAME}.gpx"
        ) as GpxImportResult.Success
        val hikeId = importResult.importedHikes.first().id
        val hike = repo.observeHike(hikeId).first()!!
        val revision = TrailWalkRecord.routeRevision(hike.route)

        val record = TrailWalkRecord(
            hikeId = hikeId.value,
            startedAtEpochMillis = 10_000L,
            coverage = TrailWalkCoverage().withRecordedMatch(0.0).withRecordedMatch(320.0),
            routeDistanceMeters = hike.distanceMeters,
            routeRevision = revision
        )
        assertThat(repo.saveWalkInProgress(hikeId, record).isSuccess).isTrue()
        assertThat(repo.getOpenWalksInProgress().getOrThrow()).containsExactly(record)

        // Inject local-state clear refusal -> portable HikeWalk row must be rolled back.
        localDao.failUpsert = true
        val refused = repo.finishWalk(record, endedAtEpochMillis = 70_000L, reason = TrailWalkEndReason.ENDED)
        assertThat(refused.isFailure).isTrue()
        assertThat(repo.observeWalks(hikeId).first()).isEmpty()
        assertThat(repo.getOpenWalksInProgress().getOrThrow()).containsExactly(record)

        // Succeed on retry -> walk row written, open walk cleared, autoFollow toggled cleanly.
        localDao.failUpsert = false
        val saved = repo.finishWalk(record, endedAtEpochMillis = 70_000L, reason = TrailWalkEndReason.ENDED)
            .getOrThrow()!!
        assertThat(saved.endReason).isEqualTo(TrailWalkEndReason.ENDED)
        assertThat(saved.distanceMeters).isWithin(0.1).of(320.0)
        assertThat(saved.routeRevision).isEqualTo(revision)
        assertThat(repo.observeWalk(saved.id).first()).isEqualTo(saved)
        assertThat(repo.getOpenWalksInProgress().getOrThrow()).isEmpty()

        assertThat(repo.setAutoFollowEnabled(hikeId, false).isSuccess).isTrue()
        assertThat(repo.observeHike(hikeId).first()!!.autoFollowEnabled).isFalse()
    }

    @Test
    fun seedsHalfLoopWalkWhenLaunchConfigRequestsIt() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = createRepository(
            LaunchConfig(
                importedGpxFixtureName = TestFixtures.THUMSEE_LOOP_FAST_NAME,
                seedWalkPresetName = TestFixtures.SEED_WALK_HALF_LOOP
            ),
            dispatcher
        )
        val hikeId = repo.seedFixtureIfConfigured()!!
        val walks = repo.observeWalks(hikeId).first()
        assertThat(walks).hasSize(1)
        assertThat(walks.first().coveredFraction).isWithin(0.01).of(0.5)
    }

    @Test
    fun retryingFinishAfterCommittedPortableInsertReusesSameRow() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = createRepository(LaunchConfig(), dispatcher)
        val importResult = repo.importGpxBytes(
            TestFixtures.thumseeLoopFastGpxBytes(),
            "${TestFixtures.THUMSEE_LOOP_FAST_NAME}.gpx"
        ) as GpxImportResult.Success
        val hikeId = importResult.importedHikes.first().id
        val hike = repo.observeHike(hikeId).first()!!
        val revision = TrailWalkRecord.routeRevision(hike.route)

        val record = TrailWalkRecord(
            hikeId = hikeId.value,
            startedAtEpochMillis = 20_000L,
            coverage = TrailWalkCoverage().withRecordedMatch(0.0).withRecordedMatch(410.0),
            routeDistanceMeters = hike.distanceMeters,
            routeRevision = revision,
            walkId = "durable-walk-1"
        )
        assertThat(repo.saveWalkInProgress(hikeId, record).isSuccess).isTrue()

        // Simulate interruption after portable insert committed but before local open walk was cleared.
        val firstFinish = repo.finishWalk(record, endedAtEpochMillis = 80_000L, reason = TrailWalkEndReason.ENDED)
            .getOrThrow()!!
        assertThat(firstFinish.id).isEqualTo("durable-walk-1")
        assertThat(repo.saveWalkInProgress(hikeId, record).isSuccess).isTrue()

        // Retrying finish on the recovered record replaces the same row instead of duplicating History.
        val retryFinish = repo.finishWalk(record, endedAtEpochMillis = 80_000L, reason = TrailWalkEndReason.ENDED)
            .getOrThrow()!!
        assertThat(retryFinish.id).isEqualTo("durable-walk-1")
        assertThat(repo.observeWalks(hikeId).first()).hasSize(1)
        assertThat(repo.getOpenWalksInProgress().getOrThrow()).isEmpty()
    }

    private fun createRepository(
        config: LaunchConfig,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher
    ): OfflineFirstHikeRepository {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val (_, photoStore) = createTestPhotoStore(dispatcher, context)
        return OfflineFirstHikeRepository(
            context = context,
            database = portableDb,
            hikeDao = portableDb.hikeDao(),
            trailPointDao = portableDb.trailPointDao(),
            hikePhotoDao = portableDb.hikePhotoDao(),
            hikeWalkDao = portableDb.hikeWalkDao(),
            hikeLocalStateDao = localDao,
            photoStore = photoStore,
            photoLibraryMirror = TestPhotoLibraryMirror(),
            photoFileIo = TestPhotoFileIo(),
            gpxFileSource = object : GpxFileSource {
                override suspend fun read(uriString: String): GpxFilePayload? = null
            },
            gpxFileSink = object : GpxFileSink {
                override suspend fun write(uriString: String, xml: String): Result<Unit> = Result.success(Unit)
            },
            bundledGpxFixtureSource = object : BundledGpxFixtureSource {
                override suspend fun loadFixture(fixtureName: String): Pair<String, ByteArray>? =
                    if (fixtureName == TestFixtures.THUMSEE_LOOP_FAST_NAME) {
                        "$fixtureName.gpx" to TestFixtures.thumseeLoopFastGpxBytes()
                    } else {
                        null
                    }
            },
            launchConfigHolder = LaunchConfigHolder(config),
            clock = TestClock(),
            ioDispatcher = dispatcher,
            defaultDispatcher = dispatcher
        )
    }

    private class ControllableLocalStateDao(private val delegate: HikeLocalStateDao) : HikeLocalStateDao by delegate {
        var failUpsert: Boolean = false

        override suspend fun upsert(entity: HikeLocalStateEntity) {
            if (failUpsert) throw SQLiteException("Simulated local DB refusal")
            delegate.upsert(entity)
        }

        override suspend fun upsertAll(entities: List<HikeLocalStateEntity>) {
            if (failUpsert) throw SQLiteException("Simulated local DB refusal")
            delegate.upsertAll(entities)
        }

        override suspend fun insertIgnore(entity: HikeLocalStateEntity): Long {
            if (failUpsert) throw SQLiteException("Simulated local DB refusal")
            return delegate.insertIgnore(entity)
        }

        override suspend fun updateElevationMetrics(
            hikeId: String,
            climbMeters: Double?,
            descentMeters: Double?,
            elevationRouteRevision: String?
        ): Int {
            if (failUpsert) throw SQLiteException("Simulated local DB refusal")
            return delegate.updateElevationMetrics(hikeId, climbMeters, descentMeters, elevationRouteRevision)
        }

        override suspend fun updateListOrder(hikeId: String, listOrder: Int?): Int {
            if (failUpsert) throw SQLiteException("Simulated local DB refusal")
            return delegate.updateListOrder(hikeId, listOrder)
        }
    }
}
