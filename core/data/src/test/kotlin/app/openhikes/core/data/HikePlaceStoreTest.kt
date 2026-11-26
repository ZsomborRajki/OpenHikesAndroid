package app.openhikes.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.database.OpenHikesDatabase
import app.openhikes.core.database.OpenHikesLocalDatabase
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.GpxImportResult
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.PreparedRecording
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.TrailPlaceFact
import app.openhikes.core.model.TrailPlaceOsm
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.TrailPoint
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
class HikePlaceStoreTest {
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
    fun importsFixturePlacesAndEditsOwnWhileRefusingOsm() = runTest {
        val repo = createRepository(UnconfinedTestDispatcher(testScheduler))
        val hikeId = importFixtureHike(repo)

        val loaded = repo.observeHike(hikeId).first()!!
        assertThat(loaded.places).hasSize(2)
        val boathouse = loaded.places.first { it.name == "Boathouse" }
        val spring = loaded.places.first { it.name == "Thumsee Spring" }
        assertThat(boathouse.isHikersOwn).isTrue()
        assertThat(spring.isHikersOwn).isFalse()
        assertThat(spring.osm).isEqualTo(TrailPlaceOsm(elementType = "node", elementId = 123456L))

        assertThat(
            repo.editPlace(hikeId, boathouse.id, "North Boathouse", TrailPlaceSymbol.VIEWPOINT, "Open May-Sep")
                .getOrThrow()
        ).isTrue()
        val afterEdit = repo.observeHike(hikeId).first()!!.places.first { it.id == boathouse.id }
        assertThat(afterEdit.name).isEqualTo("North Boathouse")
        assertThat(afterEdit.symbolKind).isEqualTo(TrailPlaceSymbol.VIEWPOINT)
        assertThat(afterEdit.note).isEqualTo("Open May-Sep")

        assertThat(
            repo.editPlace(hikeId, spring.id, "Renamed Spring", TrailPlaceSymbol.CAMP, "Nope").getOrThrow()
        ).isFalse()
        assertThat(repo.observeHike(hikeId).first()!!.places.first { it.id == spring.id }.name)
            .isEqualTo("Thumsee Spring")
    }

    @Test
    fun addsDeduplicatedOsmPlacesAndRemovesPlace() = runTest {
        val repo = createRepository(UnconfinedTestDispatcher(testScheduler))
        val hikeId = importFixtureHike(repo)
        val loaded = repo.observeHike(hikeId).first()!!
        val boathouse = loaded.places.first { it.name == "Boathouse" }
        val spring = loaded.places.first { it.name == "Thumsee Spring" }

        val duplicateOsm = TrailPoint(
            id = "dup-spring",
            hikeId = hikeId,
            coordinate = spring.coordinate,
            name = "Thumsee Spring",
            symbol = TrailPlaceSymbol.WATER.rawValue,
            osm = TrailPlaceOsm(
                elementType = "node",
                elementId = 123456L,
                facts = listOf(TrailPlaceFact(TrailPlaceFact.Kind.ELEVATION, "532"))
            )
        )
        assertThat(repo.addPlace(hikeId, duplicateOsm).getOrThrow()).isFalse()
        assertThat(repo.observeHike(hikeId).first()!!.places).hasSize(2)

        val summit = TrailPoint(
            id = "summit-1",
            hikeId = hikeId,
            coordinate = GeoCoordinate(47.7215, 12.841363),
            name = "Thumsee Kopf",
            symbol = TrailPlaceSymbol.SUMMIT.rawValue,
            osm = TrailPlaceOsm(
                elementType = "node",
                elementId = 9_100_004L,
                facts = listOf(TrailPlaceFact(TrailPlaceFact.Kind.ELEVATION, "712")),
                wikidata = "Q4115189"
            )
        )
        assertThat(repo.addPlace(hikeId, summit).getOrThrow()).isTrue()
        val persistedSummit = repo.observeHike(hikeId).first()!!.places.first { it.id == "summit-1" }
        assertThat(persistedSummit.osm?.wikidata).isEqualTo("Q4115189")
        assertThat(persistedSummit.osm?.facts).containsExactly(TrailPlaceFact(TrailPlaceFact.Kind.ELEVATION, "712"))

        repo.removePlace(hikeId, boathouse.id).getOrThrow()
        assertThat(repo.observeHike(hikeId).first()!!.places.map { it.name })
            .containsExactly("Thumsee Spring", "Thumsee Kopf")
    }

    private suspend fun importFixtureHike(repo: OfflineFirstHikeRepository): HikeId {
        val imported = repo.importGpxBytes(
            bytes = TestFixtures.thumseeLoopPlacesGpxBytes(),
            fileName = TestFixtures.THUMSEE_LOOP_PLACES_NAME
        ) as GpxImportResult.Success
        return imported.importedHikes.single().id
    }

    @Test
    fun finalizingRecordingDraftPreservesPlacesAddedDuringRecording() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = createRepository(dispatcher)
        val draftId = HikeId("rec-draft-1")

        repo.createOrClaimRecordingDraft(draftId, startedAtEpochMillis = 10_000L, title = "Morning Walk").getOrThrow()
        val addedPlace = TrailPoint(
            id = "rec-place-1",
            hikeId = draftId,
            coordinate = GeoCoordinate(47.7190, 12.8310),
            name = "Bench",
            symbol = TrailPlaceSymbol.VIEWPOINT.rawValue
        )
        assertThat(repo.addPlace(draftId, addedPlace).getOrThrow()).isTrue()

        val finalized = repo.finalizeRecordingDraft(
            hikeId = draftId,
            title = "Morning Walk",
            customName = null,
            startedAtEpochMillis = 10_000L,
            endedAtEpochMillis = 40_000L,
            prepared = PreparedRecording(
                route = listOf(
                    RouteCoordinate(47.7185, 12.8315, timestampEpochMillis = 10_000L),
                    RouteCoordinate(47.7195, 12.8305, timestampEpochMillis = 40_000L)
                ),
                distanceMeters = 140.0,
                routeLengthMeters = 140.0,
                recordedSeconds = 30.0,
                elevationGainMeters = 10.0,
                elevationLossMeters = 2.0,
                startedAtEpochMillis = 10_000L
            )
        ).getOrThrow()

        assertThat(finalized.places.map { it.name }).containsExactly("Bench")
        assertThat(repo.observeHike(draftId).first()!!.places.map { it.name }).containsExactly("Bench")
    }

    private fun createRepository(dispatcher: kotlinx.coroutines.CoroutineDispatcher): OfflineFirstHikeRepository =
        OfflineFirstHikeRepository(
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
            launchConfigHolder = LaunchConfigHolder(LaunchConfig.Production),
            clock = TestClock(1_780_000_000_000L),
            ioDispatcher = dispatcher,
            defaultDispatcher = dispatcher
        )
}
