package app.openhikes.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.database.OpenHikesLocalDatabase
import app.openhikes.core.model.AssembledTrailGraph
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.StoredTrailDraft
import app.openhikes.core.model.TrailGraph
import app.openhikes.core.model.TrailGraphProvider
import app.openhikes.core.model.TrailLegEnds
import app.openhikes.core.model.TrailLegGap
import app.openhikes.core.model.TrailLegOutage
import app.openhikes.core.model.TrailLegSnap
import app.openhikes.core.model.TrailStopRecent
import app.openhikes.core.model.TrailTravelMode
import app.openhikes.core.model.TrailWaypoint
import app.openhikes.core.testing.TestClock
import app.openhikes.core.testing.TestFixtures
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TrailDraftAndRoutingRepositoryTest {
    private lateinit var localDb: OpenHikesLocalDatabase
    private lateinit var clock: TestClock
    private lateinit var repository: OfflineFirstTrailDraftRepository

    @Before
    fun setUp() {
        localDb = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OpenHikesLocalDatabase::class.java
        ).allowMainThreadQueries().build()
        clock = TestClock(1_780_000_000_000L)
        repository = OfflineFirstTrailDraftRepository(
            trailDraftDao = localDb.trailDraftDao(),
            trailStopRecentDao = localDb.trailStopRecentDao(),
            clock = clock,
            ioDispatcher = UnconfinedTestDispatcher()
        )
    }

    @After
    fun tearDown() {
        localDb.close()
    }

    @Test
    fun draftRoundTripsAndClearsCleanly() = runTest {
        assertThat(repository.loadDraft()).isEqualTo(StoredTrailDraft.Empty)

        val draft = StoredTrailDraft(
            waypoints = listOf(
                TrailWaypoint(id = "a", latitude = 47.718, longitude = 12.831, name = "Trailhead"),
                TrailWaypoint(id = "b", latitude = 47.719, longitude = 12.832, name = "Ridge")
            ),
            snapsToPaths = false,
            travelMode = TrailTravelMode.WALKING,
            startIsOpen = false
        )
        repository.saveDraft(draft)
        assertThat(repository.loadDraft()).isEqualTo(draft)

        repository.clearDraft()
        assertThat(repository.loadDraft()).isEqualTo(StoredTrailDraft.Empty)
    }

    @Test
    fun recentStopsDeduplicateWithinFiftyMetersAndCapAtLimit() = runTest {
        val base =
            TrailStopRecent(id = "1", name = "Thumsee", subtitle = "Bavaria", latitude = 47.718, longitude = 12.831)
        repository.recordRecentStop(base)
        clock.advanceBy(Duration.ofSeconds(1L))
        repository.recordRecentStop(
            TrailStopRecent(id = "2", name = "Thumsee", subtitle = "Updated", latitude = 47.71801, longitude = 12.831)
        )
        assertThat(repository.recentStops.first()).hasSize(1)
        assertThat(repository.recentStops.first().first().subtitle).isEqualTo("Updated")

        for (i in 1..15) {
            clock.advanceBy(Duration.ofSeconds(1L))
            repository.recordRecentStop(
                TrailStopRecent(
                    id = "place-$i",
                    name = "Peak $i",
                    subtitle = "",
                    latitude = 47.0 + i * 0.01,
                    longitude = 12.0
                )
            )
        }
        val recents = repository.recentStops.first()
        assertThat(recents).hasSize(TrailStopRecent.LIMIT)
        assertThat(recents.first().name).isEqualTo("Peak 15")
    }

    @Test
    fun osmTrailLegRouterSnapsOverBundledFixtureAndReportsOutagesWithoutCachingRefusals() = runTest {
        val json = Json { ignoreUnknownKeys = true }
        val graph = json.decodeFromString(TrailGraph.serializer(), TestFixtures.thumseeRidgePathGraphJson())
        var shouldFail = true
        var calls = 0
        val provider = object : TrailGraphProvider {
            override suspend fun assembledGraph(covering: List<GeoCoordinate>): AssembledTrailGraph {
                calls += 1
                return if (shouldFail) {
                    AssembledTrailGraph(graph = null, outage = TrailLegOutage.OFFLINE)
                } else {
                    AssembledTrailGraph(graph = graph, outage = null)
                }
            }
        }
        val router = OsmTrailLegRouter(provider, UnconfinedTestDispatcher(testScheduler))
        val ends = TrailLegEnds(GeoCoordinate(47.71836, 12.83171), GeoCoordinate(47.71889, 12.83171))

        val refused = router.route(ends, TrailTravelMode.HIKING)
        assertThat(refused?.snap).isEqualTo(TrailLegSnap.Refused(TrailLegOutage.OFFLINE))

        shouldFail = false
        val snapped = router.route(ends, TrailTravelMode.HIKING)
        assertThat(snapped?.snap).isEqualTo(TrailLegSnap.Snapped)
        assertThat(calls).isEqualTo(2)

        // Cached after settled answer
        val cached = router.route(ends, TrailTravelMode.HIKING)
        assertThat(cached).isEqualTo(snapped)
        assertThat(calls).isEqualTo(2)

        // Non-hiking travel mode returns NO_DIRECTIONS cleanly
        val driving = router.route(ends, TrailTravelMode.DRIVING)
        assertThat(driving?.snap).isEqualTo(TrailLegSnap.Unmapped(TrailLegGap.NO_DIRECTIONS))
    }
}
