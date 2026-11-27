package app.openhikes.core.data

import android.database.SQLException
import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.database.RouteBlobCodec
import app.openhikes.core.database.dao.ElevationMetricsRowUpdate
import app.openhikes.core.database.dao.HikeDao
import app.openhikes.core.database.dao.HikeLocalStateDao
import app.openhikes.core.database.dao.HikeMapRouteChunkRow
import app.openhikes.core.database.dao.HikeWalkDao
import app.openhikes.core.database.entity.HikeEntity
import app.openhikes.core.database.entity.HikeWalkEntity
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeRouteStatistics
import app.openhikes.core.model.LibraryFootprint
import app.openhikes.core.model.LibraryHikeFacts
import app.openhikes.core.model.LibraryLine
import app.openhikes.core.model.LibraryLineOutline
import app.openhikes.core.model.LibraryTotals
import app.openhikes.core.model.LibraryWalkFacts
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteElevationRevision
import app.openhikes.core.model.RouteGeometry
import app.openhikes.core.model.RouteProfile
import app.openhikes.core.model.TrailWalkEndReason
import app.openhikes.core.model.TrailWalkRecord
import app.openhikes.core.model.climb
import java.io.IOException
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Off-main coordinator for custom hike ordering, Hilliest elevation validation,
 * [LibraryTotals] aggregation, thinned [LibraryLine] outlines, and debug library fixtures.
 */
@Suppress("LongParameterList")
internal class LibraryMetricsStore(
    private val hikeDao: HikeDao,
    private val hikeWalkDao: HikeWalkDao,
    private val hikeLocalStateDao: HikeLocalStateDao,
    private val gpxImportStore: GpxImportStore,
    private val bundledGpxFixtureSource: BundledGpxFixtureSource,
    private val clock: OpenHikesClock,
    private val shouldFailFirstSave: () -> Boolean,
    private val ioDispatcher: CoroutineDispatcher,
    private val defaultDispatcher: CoroutineDispatcher
) {
    private data class CachedOutline(val chunkHash: Int, val runs: List<List<GeoCoordinate>>)

    private val outlineCache = ConcurrentHashMap<String, CachedOutline>()

    suspend fun saveCustomHikeOrder(orderedIds: List<HikeId>): Result<Unit> = withContext(ioDispatcher) {
        if (shouldFailFirstSave()) {
            return@withContext Result.failure(IOException("Simulated custom order save failure"))
        }
        runStorageCatching {
            hikeLocalStateDao.replaceListOrders(orderedIds.map { it.value })
        }
    }

    suspend fun clearCustomHikeOrder(): Result<Unit> = withContext(ioDispatcher) {
        if (shouldFailFirstSave()) {
            return@withContext Result.failure(IOException("Simulated custom order reset failure"))
        }
        runStorageCatching {
            hikeLocalStateDao.clearAllListOrders()
            Unit
        }
    }

    /**
     * Validates cached climb and descent figures for [hikeIds] against each hike's current route,
     * applying field-scoped updates so concurrent walk checkpoints, list positions, or offline
     * tile claims in `hike_local_state` are never overwritten.
     */
    suspend fun validateElevationMetrics(hikeIds: List<HikeId>): Result<Boolean> = withContext(ioDispatcher) {
        if (hikeIds.isEmpty()) return@withContext Result.success(false)
        runStorageCatching {
            val updates = ArrayList<ElevationMetricsRowUpdate>()
            for (hikeId in hikeIds) {
                measureIfStale(hikeId.value)?.let(updates::add)
            }
            if (updates.isNotEmpty()) {
                hikeLocalStateDao.upsertElevationMetricsBatch(updates)
                true
            } else {
                false
            }
        }
    }

    private suspend fun measureIfStale(hikeId: String): ElevationMetricsRowUpdate? {
        hikeDao.getHikeById(hikeId) ?: return null
        val chunks = hikeDao.getRouteChunks(hikeId)
        val existing = hikeLocalStateDao.getByHikeId(hikeId)
        val measurement = withContext(defaultDispatcher) {
            val route = RouteBlobCodec.decodeChunksOrEmpty(chunks.sortedBy { it.chunkIndex }.map { it.chunkBlob })
            RouteElevationRevision.measure(route)
        }
        val upToDate = existing?.elevationRouteRevision == measurement.revision &&
            existing.climbMeters == measurement.climbMeters &&
            existing.descentMeters == measurement.descentMeters
        val missingNoChange = existing == null && measurement.climbMeters == null && measurement.descentMeters == null
        return if (upToDate || missingNoChange) {
            null
        } else {
            ElevationMetricsRowUpdate(
                hikeId = hikeId,
                climbMeters = measurement.climbMeters,
                descentMeters = measurement.descentMeters,
                elevationRouteRevision = measurement.revision
            )
        }
    }

    /**
     * Sweeps all finished hikes and walks off the main thread, holding full decoded routes only
     * for trails that have followed walks, and computes [LibraryTotals].
     */
    suspend fun computeLibraryTotals(asOfEpochMillis: Long, zoneId: ZoneId): Result<LibraryTotals> =
        withContext(ioDispatcher) {
            runStorageCatching {
                val hikes = hikeDao.getAllFinishedHikes()
                val walks = hikeWalkDao.getAllWalks()
                val walkedIds = walks.mapTo(HashSet()) { it.hikeId }
                val followedIds = walks
                    .filter { it.endReasonId != TrailWalkEndReason.RECORDED.storedId }
                    .mapTo(HashSet()) { it.hikeId }

                val revisions = HashMap<String, String>()
                val followedRoutes = HashMap<String, List<RouteCoordinate>>()
                val hikeFacts = ArrayList<LibraryHikeFacts>(hikes.size)

                for (entity in hikes) {
                    val chunks = hikeDao.getRouteChunks(entity.id)
                    val fact = withContext(defaultDispatcher) {
                        val route = RouteBlobCodec.decodeChunksOrEmpty(
                            chunks.sortedBy { it.chunkIndex }.map { it.chunkBlob }
                        )
                        if (entity.id in walkedIds && entity.id !in revisions) {
                            revisions[entity.id] = TrailWalkRecord.routeRevision(route)
                            if (entity.id in followedIds) {
                                followedRoutes[entity.id] = route
                            }
                        }
                        buildHikeFacts(entity, route)
                    }
                    hikeFacts.add(fact)
                }

                withContext(defaultDispatcher) {
                    val walkFacts = buildWalkFacts(walks, revisions, followedRoutes)
                    LibraryTotals.compute(
                        hikes = hikeFacts,
                        walks = walkFacts,
                        asOfEpochMillis = asOfEpochMillis,
                        zoneId = zoneId
                    )
                }
            }
        }

    /**
     * Builds thinned [LibraryLine] outlines from route chunk rows, reusing cached outlines per
     * hike whenever its encoded route chunks are unchanged (so tint or title edits never re-thin).
     */
    fun toLibraryLines(rows: List<HikeMapRouteChunkRow>): List<LibraryLine> {
        if (rows.isEmpty()) {
            outlineCache.clear()
            return emptyList()
        }
        val grouped = LinkedHashMap<String, MutableList<HikeMapRouteChunkRow>>()
        for (row in rows) {
            grouped.getOrPut(row.hikeId) { ArrayList() }.add(row)
        }
        outlineCache.keys.retainAll(grouped.keys)
        return grouped.entries.mapNotNull { (hikeId, chunks) ->
            val sorted = chunks.sortedBy { it.chunkIndex }
            val chunkHash = computeChunksHash(sorted)
            val cached = outlineCache[hikeId]
            val runs = if (cached != null && cached.chunkHash == chunkHash) {
                cached.runs
            } else {
                val decoded = RouteBlobCodec.decodeChunksOrEmpty(sorted.map { it.chunkBlob })
                val thinned = LibraryLineOutline.runs(decoded)
                outlineCache[hikeId] = CachedOutline(chunkHash, thinned)
                thinned
            }
            if (runs.isEmpty()) {
                null
            } else {
                LibraryLine(
                    hikeId = HikeId(hikeId),
                    tintHex = sorted.first().tintHex,
                    runs = runs
                )
            }
        }
    }

    suspend fun seedLibraryFixtures(config: LaunchConfig) {
        if (config.seedHikeCount > 0) {
            seedHikeBatch(config.seedHikeCount)
        }
        if (config.seedNearbyTrails) {
            seedNearbyTrailsBatch()
        }
    }

    private suspend fun seedHikeBatch(count: Int) {
        val baseRoute = loadSeedRouteOrFallback()
        val distance = RouteGeometry.routeLengthMeters(baseRoute)
        val nowMillis = clock.now().toEpochMilli()
        val trails = (1..count).map { index ->
            // Seeded Trail 1 is newest, Seeded Trail N is oldest (matches iOS HikeOrderUITests).
            GpxImportStore.DrawnTrailDraft(
                title = "Seeded Trail $index",
                distanceMeters = distance,
                route = baseRoute,
                madeOnEpochMillis = nowMillis - (index - 1) * SEED_DAY_MILLIS
            )
        }
        gpxImportStore.persistDrawnTrails(trails)
    }

    private suspend fun seedNearbyTrailsBatch() {
        val nowMillis = clock.now().toEpochMilli()
        val definitions = NEARBY_SEED_TRAILS
        val trails = definitions.mapIndexed { idx, (title, route) ->
            // Newest first: Malerwinkel Loop, Rinnkendlsteig, Gotzenalm.
            GpxImportStore.DrawnTrailDraft(
                title = title,
                distanceMeters = RouteGeometry.routeLengthMeters(route),
                route = route,
                madeOnEpochMillis = nowMillis - (definitions.lastIndex - idx) * SEED_DAY_MILLIS
            )
        }
        gpxImportStore.persistDrawnTrails(trails)
    }

    private suspend fun loadSeedRouteOrFallback(): List<RouteCoordinate> {
        val fixture = bundledGpxFixtureSource.loadFixture(SEED_DEFAULT_GPX_FIXTURE)
        if (fixture != null) {
            val parsed = withContext(defaultDispatcher) {
                app.openhikes.core.common.gpx.GpxParser.parseAll(fixture.second)
            }
            val route = (parsed as? app.openhikes.core.common.gpx.GpxParseResult.Success)
                ?.value
                ?.firstOrNull()
                ?.route
            if (route != null && route.size >= 2) return route
        }
        return FALLBACK_SEED_ROUTE
    }

    private fun buildHikeFacts(entity: HikeEntity, route: List<RouteCoordinate>): LibraryHikeFacts {
        val stats = HikeRouteStatistics.from(entity.distanceMeters, route)
        val hasClock = stats.durationSeconds != null && stats.startEpochMillis != null && stats.endEpochMillis != null
        val clockStart = if (hasClock) stats.startEpochMillis else null
        val clockEnd = if (hasClock && clockStart != null) {
            max(clockStart, stats.endEpochMillis ?: clockStart)
        } else {
            null
        }
        return LibraryHikeFacts(
            hikeId = HikeId(entity.id),
            title = entity.customName?.takeIf { it.isNotEmpty() } ?: entity.title,
            dateEpochMillis = entity.dateEpochMillis,
            distanceMeters = entity.distanceMeters,
            climbMeters = stats.elevationGainMeters,
            highestMeters = stats.maxElevationMeters,
            movingSeconds = stats.movingDurationSeconds ?: stats.durationSeconds,
            clockStartEpochMillis = clockStart,
            clockEndEpochMillis = clockEnd,
            isFromCommunity = false,
            footprint = if (hasClock) LibraryFootprint.clockedPartOf(route) else null
        )
    }

    private fun buildWalkFacts(
        walks: List<HikeWalkEntity>,
        revisions: Map<String, String>,
        routes: Map<String, List<RouteCoordinate>>
    ): List<LibraryWalkFacts> {
        val lineCache = HashMap<String, Pair<RouteProfile, LibraryFootprint.Line>>()
        return walks.map { entity ->
            val domain = entity.toDomain()
            val coverage = domain.coverage
            val isRecordingsOwn = domain.endReason == TrailWalkEndReason.RECORDED
            val currentRevision = revisions[entity.hikeId]
            val trailHasChanged = currentRevision != null && !domain.isAlong(currentRevision)
            var climbMeters = 0.0
            var footprint: LibraryFootprint? = null
            if (!isRecordingsOwn && !trailHasChanged) {
                val route = routes[entity.hikeId]
                if (route != null) {
                    val (profile, groundLine) = lineCache.getOrPut(entity.hikeId) {
                        val profile = RouteProfile.from(route)
                        profile to LibraryFootprint.Line(profile)
                    }
                    climbMeters = profile.climb(coverage)?.gainMeters ?: 0.0
                    footprint = groundLine.footprint(coverage.ranges)
                }
            }
            LibraryWalkFacts(
                hikeId = domain.hikeId,
                startedAtEpochMillis = domain.startedAtEpochMillis,
                endedAtEpochMillis = domain.endedAtEpochMillis,
                coveredMeters = coverage.coveredMeters,
                walkedMeters = coverage.walkedMeters,
                activeSeconds = domain.activeSeconds,
                climbMeters = climbMeters,
                isRecordingsOwn = isRecordingsOwn,
                trailHasChanged = trailHasChanged,
                footprint = footprint
            )
        }
    }

    private fun computeChunksHash(chunks: List<HikeMapRouteChunkRow>): Int {
        var hash = 1
        for (chunk in chunks) {
            hash = HASH_MULTIPLIER * hash + chunk.chunkIndex
            hash = HASH_MULTIPLIER * hash + chunk.chunkBlob.contentHashCode()
        }
        return hash
    }

    private suspend fun <T> runStorageCatching(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: SQLException) {
        Result.failure(error)
    } catch (error: IOException) {
        Result.failure(error)
    } catch (error: IllegalStateException) {
        Result.failure(error)
    }

    private companion object {
        const val HASH_MULTIPLIER = 31
        const val SEED_DAY_MILLIS = 86_400_000L
        const val SEED_DEFAULT_GPX_FIXTURE = "ThumseeLoopFast.gpx"

        val FALLBACK_SEED_ROUTE: List<RouteCoordinate> = listOf(
            RouteCoordinate(47.717, 12.828, elevationMeters = 530.0),
            RouteCoordinate(47.721, 12.834, elevationMeters = 610.0),
            RouteCoordinate(47.725, 12.840, elevationMeters = 580.0)
        )

        val NEARBY_SEED_TRAILS: List<Pair<String, List<RouteCoordinate>>> = listOf(
            "Gotzenalm" to listOf(
                RouteCoordinate(47.575, 12.990, elevationMeters = 1_100.0),
                RouteCoordinate(47.581, 13.001, elevationMeters = 1_450.0),
                RouteCoordinate(47.588, 13.012, elevationMeters = 1_685.0)
            ),
            "Rinnkendlsteig" to listOf(
                RouteCoordinate(47.588, 12.976, elevationMeters = 610.0),
                RouteCoordinate(47.594, 12.982, elevationMeters = 980.0),
                RouteCoordinate(47.601, 12.989, elevationMeters = 1_240.0)
            ),
            "Malerwinkel Loop" to listOf(
                RouteCoordinate(47.596, 12.987, elevationMeters = 605.0),
                RouteCoordinate(47.602, 12.996, elevationMeters = 720.0),
                RouteCoordinate(47.598, 13.004, elevationMeters = 680.0),
                RouteCoordinate(47.596, 12.987, elevationMeters = 605.0)
            )
        )
    }
}
