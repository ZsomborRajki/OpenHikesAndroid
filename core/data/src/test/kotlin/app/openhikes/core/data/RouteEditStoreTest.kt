package app.openhikes.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.database.OpenHikesDatabase
import app.openhikes.core.database.OpenHikesLocalDatabase
import app.openhikes.core.model.EditedLine
import app.openhikes.core.model.GpxImportResult
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.PreparedRecording
import app.openhikes.core.model.RouteCarriedEnds
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteEditOutcome
import app.openhikes.core.model.RouteEditRefusal
import app.openhikes.core.model.RouteGeometry
import app.openhikes.core.model.RouteTrim
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
class RouteEditStoreTest {
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
    fun editSavesOriginalUpdatesRecordedWalkLeavesOtherWalksAloneAndRevertRestoresAll() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = createRepository(LaunchConfig(), dispatcher)
        val hikeId = HikeId("recorded-drive")
        val route = sampleWalkThenDriveRoute(startMillis = 1_780_000_000_000L)
        val originalLength = RouteGeometry.routeLengthMeters(route)
        val otherWalk = seedRecordedHikeWithExtraWalk(repo, hikeId, route, originalLength, dispatcher)

        val distances = RouteTrim.distances(route)
        val edited = EditedLine.build(
            whole = route,
            distances = distances,
            cuts = EditedLine.Cuts(range = 0.0..distances[5]),
            replacing = route
        )!!

        val outcome = repo.applyRouteEdit(hikeId, expectedCurrentRoute = route, edited = edited)
        assertThat(outcome).isEqualTo(RouteEditOutcome.Saved(edited.route))

        val updatedHike = repo.observeHike(hikeId).first()!!
        assertThat(updatedHike.route).isEqualTo(edited.route)
        assertThat(updatedHike.distanceMeters).isWithin(1.0).of(edited.lengthMeters)

        val walksAfterEdit = repo.observeWalks(hikeId).first()
        val recordedAfterEdit = walksAfterEdit.first { it.endReason == TrailWalkEndReason.RECORDED }
        assertThat(recordedAfterEdit.routeDistanceMeters).isWithin(1.0).of(edited.lengthMeters)
        assertThat(recordedAfterEdit.routeRevision).isEqualTo(edited.revision)
        assertThat(walksAfterEdit.first { it.id == otherWalk.id }).isEqualTo(otherWalk)

        val sources = repo.loadRouteEditSources(hikeId)!!
        assertThat(sources.saved).isEqualTo(route)
        assertThat(sources.current).isEqualTo(edited.route)
        assertThat(sources.edit?.original).isNotNull()

        val reverted = repo.revertRouteEdit(hikeId, expectedCurrentRoute = edited.route)
        assertThat(reverted).isEqualTo(RouteEditOutcome.Saved(route))

        val restoredHike = repo.observeHike(hikeId).first()!!
        assertThat(restoredHike.route).isEqualTo(route)
        assertThat(restoredHike.distanceMeters).isWithin(0.1).of(originalLength)
        val recordedAfterRevert = repo.observeWalks(hikeId).first()
            .first { it.endReason == TrailWalkEndReason.RECORDED }
        assertThat(recordedAfterRevert.routeDistanceMeters).isWithin(0.1).of(originalLength)
        assertThat(recordedAfterRevert.routeRevision).isEqualTo(TrailWalkRecord.routeRevision(route))
        assertThat(repo.loadRouteEditSources(hikeId)!!.edit?.original).isNull()
    }

    private fun sampleWalkThenDriveRoute(startMillis: Long): List<RouteCoordinate> = List(12) { index ->
        RouteCoordinate(
            latitude = 47.71842 + index * 0.0009,
            longitude = 12.83177 + if (index > 5) (index - 5) * 0.004 else 0.0,
            elevationMeters = 500.0 + index * 10.0,
            timestampEpochMillis = startMillis + index * if (index <= 5) 75_000L else 20_000L
        )
    }

    private suspend fun seedRecordedHikeWithExtraWalk(
        repo: OfflineFirstHikeRepository,
        hikeId: HikeId,
        route: List<RouteCoordinate>,
        originalLength: Double,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher
    ): app.openhikes.core.model.HikeWalk {
        val startMillis = route.first().timestampEpochMillis!!
        val prepared = PreparedRecording(
            route = route,
            distanceMeters = originalLength,
            routeLengthMeters = originalLength,
            recordedSeconds = RouteTrim.recordedSeconds(route),
            elevationGainMeters = 110.0,
            elevationLossMeters = 0.0,
            startedAtEpochMillis = startMillis
        )
        repo.createOrClaimRecordingDraft(hikeId, startMillis, "Morning Walk").getOrThrow()
        repo.finalizeRecordingDraft(
            hikeId = hikeId,
            title = "Morning Walk",
            customName = null,
            startedAtEpochMillis = startMillis,
            endedAtEpochMillis = route.last().timestampEpochMillis!!,
            prepared = prepared
        ).getOrThrow()
        val otherWalk = TestFixtures.halfLoopWalk(hikeId, originalLength, route)
        val walkStore = WalkPersistenceStore(
            database = portableDb,
            hikeDao = portableDb.hikeDao(),
            hikeWalkDao = portableDb.hikeWalkDao(),
            hikeLocalStateDao = localDb.hikeLocalStateDao(),
            shouldFailFirstSave = { false },
            ioDispatcher = dispatcher,
            defaultDispatcher = dispatcher
        )
        walkStore.seedWalk(otherWalk)
        return otherWalk
    }

    @Test
    fun refusesStaleExpectedRouteAndSimulatedSaveFailureWithoutMutatingHike() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = createRepository(LaunchConfig(failsFirstSave = true), dispatcher)
        val imported = repo.importGpxBytes(
            TestFixtures.thumseeWalkThenDriveGpxBytes(),
            "${TestFixtures.THUMSEE_WALK_THEN_DRIVE_NAME}.gpx"
        ) as GpxImportResult.Success
        val hikeId = imported.importedHikes.first().id
        val originalHike = repo.observeHike(hikeId).first()!!
        val distances = RouteTrim.distances(originalHike.route)
        val ends = RouteCarriedEnds.found(originalHike.route, distances)
        val edited = EditedLine.build(
            whole = originalHike.route,
            distances = distances,
            cuts = EditedLine.Cuts(range = 0.0..ends.trailing!!.startMeters),
            replacing = originalHike.route
        )!!

        // First save fails due to LaunchConfig(failsFirstSave = true).
        val refusedSave = repo.applyRouteEdit(hikeId, expectedCurrentRoute = originalHike.route, edited = edited)
        assertThat(refusedSave).isEqualTo(RouteEditOutcome.Refused(RouteEditRefusal.NOT_SAVED))
        assertThat(repo.observeHike(hikeId).first()!!.route).isEqualTo(originalHike.route)

        // Mismatched expected route refuses with LINE_CHANGED.
        val refusedChanged = repo.applyRouteEdit(
            hikeId = hikeId,
            expectedCurrentRoute = originalHike.route.drop(1),
            edited = edited
        )
        assertThat(refusedChanged).isEqualTo(RouteEditOutcome.Refused(RouteEditRefusal.LINE_CHANGED))

        // Unreadable route_edit_json is flagged on load and refused on write.
        val entity = portableDb.hikeDao().getHikeById(hikeId.value)!!
        portableDb.hikeDao().updateHike(entity.copy(routeEditJson = "{not-valid-json"))
        assertThat(repo.loadRouteEditSources(hikeId)!!.isUnreadable).isTrue()
        val refusedUnreadable = repo.applyRouteEdit(hikeId, expectedCurrentRoute = originalHike.route, edited = edited)
        assertThat(refusedUnreadable).isEqualTo(RouteEditOutcome.Refused(RouteEditRefusal.NOT_HERE_YET))
    }

    private fun createRepository(
        config: LaunchConfig,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher
    ): OfflineFirstHikeRepository = OfflineFirstHikeRepository(
        database = portableDb,
        hikeDao = portableDb.hikeDao(),
        trailPointDao = portableDb.trailPointDao(),
        hikeWalkDao = portableDb.hikeWalkDao(),
        hikeLocalStateDao = localDb.hikeLocalStateDao(),
        gpxFileSource = object : GpxFileSource {
            override suspend fun read(uriString: String): GpxFilePayload? = null
        },
        gpxFileSink = object : GpxFileSink {
            override suspend fun write(uriString: String, xml: String): Result<Unit> = Result.success(Unit)
        },
        bundledGpxFixtureSource = object : BundledGpxFixtureSource {
            override suspend fun loadFixture(fixtureName: String): Pair<String, ByteArray>? = null
        },
        launchConfigHolder = LaunchConfigHolder(config),
        clock = TestClock(),
        ioDispatcher = dispatcher,
        defaultDispatcher = dispatcher
    )
}
