package app.openhikes.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.database.OpenHikesDatabase
import app.openhikes.core.database.OpenHikesLocalDatabase
import app.openhikes.core.database.RouteBlobCodec
import app.openhikes.core.database.entity.HikeRouteChunkEntity
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteElevationRevision
import app.openhikes.core.model.RouteHeightSamples
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.TrailPoint
import app.openhikes.core.model.TrailWalkCoverage
import app.openhikes.core.model.TrailWalkEndReason
import app.openhikes.core.model.TrailWalkRecord
import app.openhikes.core.testing.TestClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
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
class HikeElevationFillPersistenceTest {
    private lateinit var portableDb: OpenHikesDatabase
    private lateinit var localDb: OpenHikesLocalDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        portableDb = Room.inMemoryDatabaseBuilder(context, OpenHikesDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        localDb = Room.inMemoryDatabaseBuilder(context, OpenHikesLocalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        portableDb.close()
        localDb.close()
    }

    @Test
    fun fillsFlatHikeUpdatesLocalMetricsAndPreservesWalksAndPlacesWithoutCascadeDelete() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = createRepository(LaunchConfigHolder(LaunchConfig.Production), dispatcher)
        val flatRoute = listOf(
            RouteCoordinate(47.7100, 12.8300),
            RouteCoordinate(47.7120, 12.8300),
            RouteCoordinate(47.7140, 12.8300)
        )
        val saved = repo.saveDrawnTrail(
            title = "Ridge Path",
            distanceMeters = 445.0,
            route = flatRoute,
            madeOnEpochMillis = 10_000L
        ).getOrThrow()

        // Attach a walk and a place to verify in-place chunk replacement does not CASCADE-delete children.
        val walkRecord = TrailWalkRecord(
            hikeId = saved.id.value,
            startedAtEpochMillis = 20_000L,
            coverage = TrailWalkCoverage().withRecordedMatch(0.0).withRecordedMatch(445.0),
            routeDistanceMeters = 445.0,
            routeRevision = TrailWalkRecord.routeRevision(flatRoute)
        )
        repo.finishWalk(walkRecord, endedAtEpochMillis = 140_000L, reason = TrailWalkEndReason.ENDED).getOrThrow()
        repo.addPlace(
            hikeId = saved.id,
            place = TrailPoint(
                id = "place-1",
                hikeId = saved.id,
                coordinate = GeoCoordinate(47.7140, 12.8300),
                name = "Summit Bench",
                symbol = TrailPlaceSymbol.VIEWPOINT.rawValue
            )
        ).getOrThrow()
        repo.renameHike(saved.id, "Sunny Ridge").getOrThrow()

        val samples = RouteHeightSamples(
            routePointCount = flatRoute.size,
            indexes = listOf(0, 2),
            coordinates = listOf(flatRoute[0], flatRoute[2]),
            heights = listOf(500.0, 700.0)
        )
        val filled = repo.fillHikeElevation(saved.id, samples).getOrThrow()
        assertThat(filled).isTrue()

        val reloaded = requireNotNull(repo.observeHike(saved.id).first())
        assertThat(reloaded.displayTitle).isEqualTo("Sunny Ridge")
        assertThat(reloaded.route[0].elevationMeters).isWithin(1e-6).of(500.0)
        assertThat(reloaded.route[1].elevationMeters).isWithin(1.0).of(600.0)
        assertThat(reloaded.route[2].elevationMeters).isWithin(1e-6).of(700.0)
        assertThat(repo.observeWalks(saved.id).first()).hasSize(1)
        assertThat(reloaded.places).hasSize(1)

        val localState = requireNotNull(repo.observeLocalState(saved.id).first())
        assertThat(localState.climbMeters).isWithin(1e-6).of(200.0)
        assertThat(localState.descentMeters).isWithin(1e-6).of(0.0)
        assertThat(localState.elevationRouteRevision).isEqualTo(RouteElevationRevision.of(reloaded.route))

        // Second fill attempt is ignored because the hike already has elevation readings.
        assertThat(repo.fillHikeElevation(saved.id, samples).getOrThrow()).isFalse()
    }

    @Test
    fun rejectsStaleSamplesRecordingHikesAndHonorsFailsFirstSave() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val holder = LaunchConfigHolder(LaunchConfig.Production)
        val repo = createRepository(holder, dispatcher)
        val flatRoute = listOf(
            RouteCoordinate(47.7100, 12.8300),
            RouteCoordinate(47.7120, 12.8300)
        )
        val saved = repo.saveDrawnTrail(
            title = "Flat Loop",
            distanceMeters = 222.0,
            route = flatRoute,
            madeOnEpochMillis = 10_000L
        ).getOrThrow()

        holder.mergeWith { it.copy(failsFirstSave = true) }
        val samples = RouteHeightSamples(
            routePointCount = flatRoute.size,
            indexes = listOf(0, 1),
            coordinates = flatRoute,
            heights = listOf(500.0, 560.0)
        )
        assertThat(repo.fillHikeElevation(saved.id, samples).isFailure).isTrue()
        assertThat(repo.observeHike(saved.id).first()?.route?.all { it.elevationMeters == null }).isTrue()

        val staleSamples = samples.copy(
            coordinates = listOf(flatRoute[0], flatRoute[1].copy(latitude = 47.9999))
        )
        assertThat(repo.fillHikeElevation(saved.id, staleSamples).getOrThrow()).isFalse()

        val recordingId = HikeId("recording-1")
        repo.createOrClaimRecordingDraft(
            hikeId = recordingId,
            startedAtEpochMillis = 10_000L,
            title = "Live Recording"
        ).getOrThrow()
        val recordingEntity = requireNotNull(portableDb.hikeDao().getHikeById(recordingId.value))
        val chunks = RouteBlobCodec.encodeChunked(flatRoute).mapIndexed { idx, blob ->
            HikeRouteChunkEntity(hikeId = recordingEntity.id, chunkIndex = idx, chunkBlob = blob)
        }
        portableDb.hikeDao().updateExistingHikeWithRoute(recordingEntity, chunks)
        assertThat(repo.fillHikeElevation(recordingId, samples).getOrThrow()).isFalse()
    }

    private fun createRepository(
        launchConfigHolder: LaunchConfigHolder,
        dispatcher: CoroutineDispatcher
    ): OfflineFirstHikeRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val (_, photoStore) = createTestPhotoStore(dispatcher, context)
        return OfflineFirstHikeRepository(
            context = context,
            database = portableDb,
            hikeDao = portableDb.hikeDao(),
            trailPointDao = portableDb.trailPointDao(),
            hikePhotoDao = portableDb.hikePhotoDao(),
            hikeWalkDao = portableDb.hikeWalkDao(),
            hikeLocalStateDao = localDb.hikeLocalStateDao(),
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
                override suspend fun loadFixture(fixtureName: String): Pair<String, ByteArray>? = null
            },
            launchConfigHolder = launchConfigHolder,
            clock = TestClock(),
            ioDispatcher = dispatcher,
            defaultDispatcher = dispatcher
        )
    }
}
