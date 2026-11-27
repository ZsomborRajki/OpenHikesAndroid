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
import app.openhikes.core.model.RouteBoundary
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteElevationRevision
import app.openhikes.core.model.RouteHeightSamples
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.TrailPoint
import app.openhikes.core.model.TrailWalkCoverage
import app.openhikes.core.model.TrailWalkEndReason
import app.openhikes.core.model.TrailWalkRecord
import app.openhikes.core.model.samplesOf
import app.openhikes.core.testing.FakeRouteElevationSource
import app.openhikes.core.testing.TestClock
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
            heights = listOf(500.0, 700.0),
            routeRevision = TrailWalkRecord.routeRevision(flatRoute)
        )
        assertThat(repo.fillHikeElevation(saved.id, samples).getOrThrow()).isTrue()

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
    fun rejectsStaleUnsampledPointOrPauseChangeRecordingHikesAndHonorsFailsFirstSave() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val holder = LaunchConfigHolder(LaunchConfig.Production)
        val repo = createRepository(holder, dispatcher)
        val longRoute = List(201) { i -> RouteCoordinate(47.7000 + i * 0.0001, 12.8300) }
        val saved = repo.saveDrawnTrail(
            title = "Long Flat Loop",
            distanceMeters = 2_220.0,
            route = longRoute,
            madeOnEpochMillis = 10_000L
        ).getOrThrow()

        val elevationSource = FakeRouteElevationSource(cannedHeights = listOf(500.0, 560.0))
        val samples = requireNotNull(elevationSource.samplesOf(longRoute))
        val unsampledIndex = (0 until longRoute.size).first { it !in samples.indexes }

        // Moving a single unsampled interior point changes the full route revision and blocks the stale fill.
        val movedInterior = longRoute.mapIndexed { idx, pt ->
            if (idx == unsampledIndex) pt.copy(longitude = 12.8399) else pt
        }
        replaceRouteChunks(saved.id, movedInterior)
        assertThat(repo.fillHikeElevation(saved.id, samples).getOrThrow()).isFalse()
        assertThat(repo.observeHike(saved.id).first()?.route?.all { it.elevationMeters == null }).isTrue()

        // Adding a pause boundary on an unsampled interior point also blocks the stale fill.
        val pausedInterior = longRoute.mapIndexed { idx, pt ->
            if (idx == unsampledIndex) pt.copy(boundary = RouteBoundary.PAUSED) else pt
        }
        replaceRouteChunks(saved.id, pausedInterior)
        assertThat(repo.fillHikeElevation(saved.id, samples).getOrThrow()).isFalse()

        // Restoring the original route and enabling failsFirstSave surfaces a failure once without mutating.
        replaceRouteChunks(saved.id, longRoute)
        holder.mergeWith { it.copy(failsFirstSave = true) }
        assertThat(repo.fillHikeElevation(saved.id, samples).isFailure).isTrue()
        assertThat(repo.observeHike(saved.id).first()?.route?.all { it.elevationMeters == null }).isTrue()

        // Active recording hikes are never filled.
        val recordingId = HikeId("recording-1")
        repo.createOrClaimRecordingDraft(recordingId, startedAtEpochMillis = 10_000L, title = "Live Recording")
            .getOrThrow()
        replaceRouteChunks(recordingId, longRoute)
        assertThat(repo.fillHikeElevation(recordingId, samples).getOrThrow()).isFalse()
    }

    @Test
    fun finishesLocalElevationCacheUpdateWhenCallerCancelsRightAfterPortableCommit() = runTest {
        val baseDispatcher = UnconfinedTestDispatcher(testScheduler)
        var fillJob: Job? = null
        val dispatches = AtomicInteger(0)
        // Cancel the caller right at the post-commit defaultDispatcher switch (2nd dispatch onto defaultDispatcher).
        val cancelingDefaultDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (dispatches.incrementAndGet() == 2) {
                    fillJob?.cancel(CancellationException("Simulated Hike Detail collectLatest cancellation"))
                }
                block.run()
            }
        }
        val repo = createRepository(
            launchConfigHolder = LaunchConfigHolder(LaunchConfig.Production),
            ioDispatcher = baseDispatcher,
            defaultDispatcher = cancelingDefaultDispatcher
        )
        val flatRoute = listOf(
            RouteCoordinate(47.7100, 12.8300),
            RouteCoordinate(47.7140, 12.8300)
        )
        val saved = repo.saveDrawnTrail(
            title = "Cancel Race Trail",
            distanceMeters = 445.0,
            route = flatRoute,
            madeOnEpochMillis = 10_000L
        ).getOrThrow()
        val samples = RouteHeightSamples(
            routePointCount = flatRoute.size,
            indexes = listOf(0, 1),
            coordinates = flatRoute,
            heights = listOf(500.0, 700.0),
            routeRevision = TrailWalkRecord.routeRevision(flatRoute)
        )

        dispatches.set(0)
        fillJob = launch { repo.fillHikeElevation(saved.id, samples) }
        fillJob.join()

        val reloaded = requireNotNull(repo.observeHike(saved.id).first())
        val localState = requireNotNull(repo.observeLocalState(saved.id).first())
        assertThat(reloaded.route.map { it.elevationMeters }).containsExactly(500.0, 700.0).inOrder()
        assertThat(localState.climbMeters).isWithin(1e-6).of(200.0)
        assertThat(localState.descentMeters).isWithin(1e-6).of(0.0)
        assertThat(localState.elevationRouteRevision).isEqualTo(RouteElevationRevision.of(reloaded.route))
    }

    @Test
    fun skipsLocalElevationCacheUpdateWhenSubsequentRouteEditChangesRevisionFirst() = runTest {
        val baseDispatcher = UnconfinedTestDispatcher(testScheduler)
        val flatRoute = listOf(
            RouteCoordinate(47.7100, 12.8300),
            RouteCoordinate(47.7140, 12.8300)
        )
        val editedRoute = listOf(
            RouteCoordinate(47.7100, 12.8300, elevationMeters = 900.0),
            RouteCoordinate(47.7140, 12.8300, elevationMeters = 950.0)
        )
        var targetHikeId: HikeId? = null
        val dispatches = AtomicInteger(0)
        val racingDefaultDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (dispatches.incrementAndGet() == 2 && targetHikeId != null) {
                    val id = requireNotNull(targetHikeId)
                    kotlinx.coroutines.runBlocking {
                        replaceRouteChunks(id, editedRoute)
                        localDb.hikeLocalStateDao().upsertElevationMetrics(
                            hikeId = id.value,
                            climbMeters = 50.0,
                            descentMeters = 0.0,
                            elevationRouteRevision = RouteElevationRevision.of(editedRoute)
                        )
                    }
                }
                block.run()
            }
        }
        val repo = createRepository(
            launchConfigHolder = LaunchConfigHolder(LaunchConfig.Production),
            ioDispatcher = baseDispatcher,
            defaultDispatcher = racingDefaultDispatcher
        )
        val saved = repo.saveDrawnTrail(
            title = "Edited Mid-Sync",
            distanceMeters = 445.0,
            route = flatRoute,
            madeOnEpochMillis = 10_000L
        ).getOrThrow()
        targetHikeId = saved.id
        val samples = RouteHeightSamples(
            routePointCount = flatRoute.size,
            indexes = listOf(0, 1),
            coordinates = flatRoute,
            heights = listOf(500.0, 700.0),
            routeRevision = TrailWalkRecord.routeRevision(flatRoute)
        )

        dispatches.set(0)
        assertThat(repo.fillHikeElevation(saved.id, samples).getOrThrow()).isTrue()

        val localState = requireNotNull(repo.observeLocalState(saved.id).first())
        assertThat(localState.climbMeters).isWithin(1e-6).of(50.0)
        assertThat(localState.elevationRouteRevision).isEqualTo(RouteElevationRevision.of(editedRoute))
    }

    private suspend fun replaceRouteChunks(hikeId: HikeId, route: List<RouteCoordinate>) {
        val entity = requireNotNull(portableDb.hikeDao().getHikeById(hikeId.value))
        val chunks = RouteBlobCodec.encodeChunked(route).mapIndexed { idx, blob ->
            HikeRouteChunkEntity(hikeId = entity.id, chunkIndex = idx, chunkBlob = blob)
        }
        portableDb.hikeDao().updateExistingHikeWithRoute(entity, chunks)
    }

    private fun createRepository(
        launchConfigHolder: LaunchConfigHolder,
        ioDispatcher: CoroutineDispatcher,
        defaultDispatcher: CoroutineDispatcher = ioDispatcher
    ): OfflineFirstHikeRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val (_, photoStore) = createTestPhotoStore(ioDispatcher, context)
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
            ioDispatcher = ioDispatcher,
            defaultDispatcher = defaultDispatcher
        )
    }
}
