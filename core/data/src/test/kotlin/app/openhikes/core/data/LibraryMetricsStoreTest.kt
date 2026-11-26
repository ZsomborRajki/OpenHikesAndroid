package app.openhikes.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.database.OpenHikesDatabase
import app.openhikes.core.database.OpenHikesLocalDatabase
import app.openhikes.core.database.entity.HikeLocalStateEntity
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.LibraryLineOutline
import app.openhikes.core.model.PreparedRecording
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteGeometry
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.RouteProfile
import app.openhikes.core.model.RouteStyleUpdate
import app.openhikes.core.model.TrailWalkCoverage
import app.openhikes.core.model.TrailWalkEndReason
import app.openhikes.core.model.TrailWalkRecord
import app.openhikes.core.testing.TestClock
import app.openhikes.core.testing.TestFixtures
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
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
class LibraryMetricsStoreTest {
    private lateinit var portableDb: OpenHikesDatabase
    private lateinit var localDb: OpenHikesLocalDatabase
    private val utc = ZoneOffset.UTC

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
    fun customOrderPreservesUnrelatedLocalStateAndClearRestoresNullPositions() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = createRepository(LaunchConfig.Production, dispatcher)

        val h1 = repo.saveDrawnTrail("Trail A", 3_000.0, climbingRoute(), 1_760_000_000_000L).getOrThrow()
        val h2 = repo.saveDrawnTrail("Trail B", 5_000.0, climbingRoute(), 1_760_100_000_000L).getOrThrow()
        val beforeLocal = localDb.hikeLocalStateDao().getByHikeId(h1.id.value)!!
        localDb.hikeLocalStateDao().upsert(beforeLocal.copy(autoSavedTileKeysJson = "[\"12/1/2\"]"))

        assertThat(repo.saveCustomHikeOrder(listOf(h1.id, h2.id)).isSuccess).isTrue()

        val summaries = repo.hikes.first().associateBy { it.id }
        assertThat(summaries[h1.id]?.listOrder).isEqualTo(0)
        assertThat(summaries[h2.id]?.listOrder).isEqualTo(1)
        val afterLocal = localDb.hikeLocalStateDao().getByHikeId(h1.id.value)!!
        assertThat(afterLocal.climbMeters).isEqualTo(beforeLocal.climbMeters)
        assertThat(afterLocal.autoSavedTileKeysJson).isEqualTo("[\"12/1/2\"]")

        assertThat(repo.clearCustomHikeOrder().isSuccess).isTrue()
        assertThat(repo.hikes.first().all { it.listOrder == null }).isTrue()
    }

    @Test
    fun validateElevationMetricsRepairsStaleFiguresAndClearsObsoleteOnes() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = createRepository(LaunchConfig.Production, dispatcher)

        val flatNoHeightRoute = listOf(
            RouteCoordinate(47.700, 12.800),
            RouteCoordinate(47.710, 12.800)
        )
        val climbed = repo.saveDrawnTrail("Mountain", 4_000.0, climbingRoute(), 1_760_000_000_000L).getOrThrow()
        val flat = repo.saveDrawnTrail("Flat", 1_000.0, flatNoHeightRoute, 1_760_100_000_000L).getOrThrow()

        // Corrupt cached local state to simulate stale cache from an older route.
        localDb.hikeLocalStateDao().upsert(
            HikeLocalStateEntity(
                hikeId = climbed.id.value,
                listOrder = 2,
                climbMeters = 5.0,
                descentMeters = 5.0,
                elevationRouteRevision = "stale-rev",
                ownsRecordingDraft = false,
                autoSavedTileKeysJson = "[]"
            )
        )
        localDb.hikeLocalStateDao().upsert(
            HikeLocalStateEntity(
                hikeId = flat.id.value,
                listOrder = null,
                climbMeters = 999.0,
                descentMeters = 999.0,
                elevationRouteRevision = "stale-rev",
                ownsRecordingDraft = false,
                autoSavedTileKeysJson = "[]"
            )
        )

        val changed = repo.validateElevationMetrics(listOf(climbed.id, flat.id)).getOrThrow()
        assertThat(changed).isTrue()

        val climbedLocal = localDb.hikeLocalStateDao().getByHikeId(climbed.id.value)!!
        assertThat(climbedLocal.climbMeters).isWithin(1.0).of(400.0)
        assertThat(climbedLocal.listOrder).isEqualTo(2)

        val flatLocal = localDb.hikeLocalStateDao().getByHikeId(flat.id.value)!!
        assertThat(flatLocal.climbMeters).isNull()
        assertThat(flatLocal.descentMeters).isNull()

        // Second validation is a no-op once revisions match.
        assertThat(repo.validateElevationMetrics(listOf(climbed.id, flat.id)).getOrThrow()).isFalse()
    }

    @Test
    fun computeLibraryTotalsAppliesDirectedWalkClimbSameGroundOverlapAndRedrawnTrailRules() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = createRepository(LaunchConfig.Production, dispatcher)
        val route = climbingRoute()
        val profile = RouteProfile.from(route)
        val totalDist = profile.totalDistanceMeters
        val halfDist = profile.distances[20]
        val walkStart = ZonedDateTime.of(2026, 5, 10, 10, 0, 0, 0, utc).toInstant().toEpochMilli()
        val currentRevision = TrailWalkRecord.routeRevision(route)

        val drawn = repo.saveDrawnTrail("Ridge", totalDist, route, walkStart - 86_400_000L).getOrThrow()
        // Walk 1: out and back over climbing half -> climbs 400 m once, 4 km walked, 2 km covered.
        finishSampleWalk(
            repo = repo,
            hikeId = drawn.id.value,
            walkId = "walk-1",
            startedAtMillis = walkStart,
            coverage = TrailWalkCoverage.fromStored(listOf(0.0, halfDist, halfDist, 0.0), halfDist),
            routeDistanceMeters = totalDist,
            routeRevision = currentRevision
        )
        // Walk 2: walked downhill only -> 0 climb.
        finishSampleWalk(
            repo = repo,
            hikeId = drawn.id.value,
            walkId = "walk-2",
            startedAtMillis = walkStart + 2 * 86_400_000L,
            coverage = TrailWalkCoverage.fromStored(listOf(totalDist, 0.0), totalDist),
            routeDistanceMeters = totalDist,
            routeRevision = currentRevision
        )
        // Walk 3: stale revision (trail redrawn) -> counts distance, withholds climb.
        finishSampleWalk(
            repo = repo,
            hikeId = drawn.id.value,
            walkId = "walk-3",
            startedAtMillis = walkStart + 4 * 86_400_000L,
            coverage = TrailWalkCoverage.fromStored(listOf(0.0, totalDist), totalDist),
            routeDistanceMeters = totalDist,
            routeRevision = "old-redrawn-revision"
        )
        // Clocked recording over walk1's hours in Zurich (different ground) leaves walk1 whole.
        val recDist = seedZurichRecording(repo, walkStart)

        val totals = repo.computeLibraryTotals(
            asOfEpochMillis = ZonedDateTime.of(2026, 6, 1, 0, 0, 0, 0, utc).toInstant().toEpochMilli(),
            zoneId = utc
        ).getOrThrow()

        assertThat(totals.thisYear.outings).isEqualTo(4)
        assertThat(totals.thisYear.trails).isEqualTo(2)
        assertThat(totals.allTime.outingsWithoutClimb).isEqualTo(1)
        assertThat(totals.thisYear.climbMeters).isWithin(2.0).of(500.0)
        assertThat(totals.thisYear.distanceMeters).isWithin(5.0).of(halfDist + totalDist + totalDist + recDist)
        assertThat(repo.hikes.first().first { it.id == drawn.id }.walkCount).isEqualTo(3)
    }

    @Suppress("LongParameterList")
    private suspend fun finishSampleWalk(
        repo: OfflineFirstHikeRepository,
        hikeId: String,
        walkId: String,
        startedAtMillis: Long,
        coverage: TrailWalkCoverage,
        routeDistanceMeters: Double,
        routeRevision: String
    ) {
        val walk = TrailWalkRecord(
            hikeId = hikeId,
            startedAtEpochMillis = startedAtMillis,
            coverage = coverage,
            bankedActiveSeconds = 3_600.0,
            phaseId = "paused",
            routeDistanceMeters = routeDistanceMeters,
            routeRevision = routeRevision,
            walkId = walkId
        )
        repo.finishWalk(walk, startedAtMillis + 3_600_000L, TrailWalkEndReason.ENDED).getOrThrow()
    }

    private suspend fun seedZurichRecording(repo: OfflineFirstHikeRepository, walkStart: Long): Double {
        val recordingRoute = (0..10).map { step ->
            RouteCoordinate(
                latitude = 47.37 + step * 0.001,
                longitude = 8.54,
                elevationMeters = 410.0 + step * 10.0,
                timestampEpochMillis = walkStart + step * 180_000L
            )
        }
        val recDist = RouteGeometry.routeLengthMeters(recordingRoute)
        val recId = HikeId("rec-zurich")
        repo.createOrClaimRecordingDraft(recId, walkStart, "Zurich Walk").getOrThrow()
        repo.finalizeRecordingDraft(
            hikeId = recId,
            title = "Zurich Walk",
            customName = null,
            startedAtEpochMillis = walkStart,
            endedAtEpochMillis = walkStart + 1_800_000L,
            prepared = PreparedRecording(
                route = recordingRoute,
                distanceMeters = recDist,
                routeLengthMeters = recDist,
                recordedSeconds = 1_800.0,
                elevationGainMeters = 100.0,
                elevationLossMeters = 0.0,
                startedAtEpochMillis = walkStart
            )
        ).getOrThrow()
        return recDist
    }

    @Test
    fun libraryLinesThinsLongRoutesExcludesRecordingDraftsAndUpdatesColor() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = createRepository(
            LaunchConfig(isUiTesting = true, seedHikeCount = 2, seedNearbyTrails = true),
            dispatcher
        )
        repo.seedFixtureIfConfigured()

        val draftId = HikeId("draft-live")
        repo.createOrClaimRecordingDraft(draftId, 1_760_000_000_000L, "Live Draft").getOrThrow()

        val lines = repo.libraryLines.first()
        assertThat(lines).hasSize(5)
        assertThat(lines.map { it.hikeId }).doesNotContain(draftId)
        assertThat(lines.all { line -> line.runs.sumOf { it.size } <= LibraryLineOutline.MAXIMUM_POINTS }).isTrue()

        val firstId = lines.first().hikeId
        repo.updateRouteStyle(firstId, RouteStyleUpdate("#D85A17", RouteBorder.NONE_HEX, 3.0, RouteLinePattern.SOLID))
        val updated = repo.libraryLines.first().first { it.hikeId == firstId }
        assertThat(updated.tintHex).isEqualTo("#D85A17")
    }

    private fun climbingRoute(): List<RouteCoordinate> = (0..40).map { step ->
        RouteCoordinate(
            latitude = 47.0 + step * 0.0009,
            longitude = 11.0,
            elevationMeters = 1_000.0 + minOf(step, 20) * 20.0
        )
    }

    private fun createRepository(config: LaunchConfig, dispatcher: CoroutineDispatcher): OfflineFirstHikeRepository {
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
                override suspend fun loadFixture(fixtureName: String): Pair<String, ByteArray>? =
                    if (fixtureName == TestFixtures.THUMSEE_LOOP_FAST_NAME ||
                        fixtureName == "${TestFixtures.THUMSEE_LOOP_FAST_NAME}.gpx"
                    ) {
                        "${TestFixtures.THUMSEE_LOOP_FAST_NAME}.gpx" to TestFixtures.thumseeLoopFastGpxBytes()
                    } else {
                        null
                    }
            },
            launchConfigHolder = LaunchConfigHolder(config),
            clock = TestClock(Instant.parse("2026-06-01T12:00:00Z")),
            ioDispatcher = dispatcher,
            defaultDispatcher = dispatcher
        )
    }
}
