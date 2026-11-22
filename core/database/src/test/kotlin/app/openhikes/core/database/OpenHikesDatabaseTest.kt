package app.openhikes.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.openhikes.core.database.entity.HikeEntity
import app.openhikes.core.database.entity.HikeRouteChunkEntity
import app.openhikes.core.database.entity.TrailPointEntity
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteLinePattern
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenHikesDatabaseTest {
    private lateinit var database: OpenHikesDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OpenHikesDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertsQueriesUpdatesAndCascadeDeletesHikeRouteAndTrailPoints() = runTest {
        val hikeDao = database.hikeDao()
        val trailPointDao = database.trailPointDao()
        val route = listOf(RouteCoordinate(47.71, 12.83), RouteCoordinate(47.72, 12.84))

        hikeDao.insertHikeWithRoute(
            hike = sampleHikeEntity(id = "h1", title = "Thumsee", routePointCount = route.size),
            routeChunks = RouteBlobCodec.encodeChunked(route).mapIndexed { index, chunk ->
                HikeRouteChunkEntity("h1", index, chunk)
            }
        )
        trailPointDao.insertAll(
            listOf(
                TrailPointEntity(
                    id = "w1",
                    hikeId = "h1",
                    latitude = 47.71,
                    longitude = 12.83,
                    elevationMeters = 530.0,
                    name = "Start",
                    symbol = null,
                    note = "",
                    sortOrder = 0
                )
            )
        )

        hikeDao.updateCustomName("h1", "Renamed Lake")
        hikeDao.updateRouteStyle("h1", "#D85A17", "#FFFFFFFF", 6.0, RouteLinePattern.DASHED.id)

        val updated = hikeDao.getHikeById("h1")!!
        assertThat(updated.customName).isEqualTo("Renamed Lake")
        assertThat(updated.tintHex).isEqualTo("#D85A17")
        assertThat(updated.routeLinePatternId).isEqualTo(RouteLinePattern.DASHED.id)

        hikeDao.observeAllMapRouteChunks().test {
            val rows = awaitItem()
            assertThat(rows).hasSize(1)
            assertThat(RouteBlobCodec.decodeChunks(rows.map { it.chunkBlob })).isEqualTo(route)
            cancelAndIgnoreRemainingEvents()
        }

        hikeDao.deleteHikeById("h1")
        assertThat(hikeDao.getHikeById("h1")).isNull()
        assertThat(hikeDao.observeHikeWithRoute("h1").first()).isNull()
        assertThat(hikeDao.observeAllMapRouteChunks().first()).isEmpty()
        assertThat(trailPointDao.getByHikeId("h1")).isEmpty()
    }

    @Test
    fun readsLarge80000PointRouteExceedingCursorWindowLimit() = runTest {
        val hikeDao = database.hikeDao()
        val route = List(80_000) { idx ->
            RouteCoordinate(
                latitude = 47.0 + idx * 0.00001,
                longitude = 12.8,
                elevationMeters = 500.0 + (idx % 200),
                timestampEpochMillis = 1_767_225_600_000L + idx * 1_000L
            )
        }
        val chunks = RouteBlobCodec.encodeChunked(route).mapIndexed { index, blob ->
            HikeRouteChunkEntity(hikeId = "large", chunkIndex = index, chunkBlob = blob)
        }
        assertThat(chunks.sumOf { it.chunkBlob.size }).isGreaterThan(2 * 1024 * 1024)

        hikeDao.insertHikeWithRoute(
            hike = sampleHikeEntity(id = "large", title = "Long Alpine Traverse", routePointCount = route.size),
            routeChunks = chunks
        )

        val mapChunkRows = hikeDao.observeAllMapRouteChunks().first()
        assertThat(mapChunkRows).hasSize(chunks.size)
        assertThat(RouteBlobCodec.decodeChunks(mapChunkRows.map { it.chunkBlob })).hasSize(80_000)

        val detailRow = hikeDao.observeHikeWithRoute("large").first()!!
        val decodedDetail = RouteBlobCodec.decodeChunks(
            detailRow.routeChunks.sortedBy {
                it.chunkIndex
            }.map { it.chunkBlob }
        )
        assertThat(decodedDetail).isEqualTo(route)
    }

    private fun sampleHikeEntity(id: String, title: String, routePointCount: Int): HikeEntity = HikeEntity(
        id = id,
        title = title,
        customName = null,
        distanceMeters = 1200.0,
        durationSeconds = 900.0,
        dateEpochMillis = 10_000L,
        tintHex = "#1B7F3B",
        routeWidthDp = 3.0,
        routeLinePatternId = RouteLinePattern.SOLID.id,
        routeBorderHex = "",
        isRecording = false,
        autoFollowEnabled = true,
        trackDescription = null,
        author = null,
        keywords = null,
        routePointCount = routePointCount
    )
}
