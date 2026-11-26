package app.openhikes.core.data

import androidx.room.withTransaction
import app.openhikes.core.common.HikeTitle
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.database.OpenHikesDatabase
import app.openhikes.core.database.RouteBlobCodec
import app.openhikes.core.database.dao.HikeDao
import app.openhikes.core.database.dao.TrailPointDao
import app.openhikes.core.database.entity.HikeEntity
import app.openhikes.core.database.entity.HikeLocalStateEntity
import app.openhikes.core.database.entity.HikeRouteChunkEntity
import app.openhikes.core.database.entity.TrailPointEntity
import app.openhikes.core.model.GpxImportResult
import app.openhikes.core.model.GpxTrack
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeRouteStatistics
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.RouteTint
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Helper persisting parsed GPX tracks and their initial local elevation cache off the main thread.
 */
internal class GpxImportStore(
    private val database: OpenHikesDatabase,
    private val hikeDao: HikeDao,
    private val trailPointDao: TrailPointDao,
    private val localStateRepairer: HikeLocalStateRepairer,
    private val clock: OpenHikesClock,
    private val ioDispatcher: CoroutineDispatcher,
    private val defaultDispatcher: CoroutineDispatcher
) {
    suspend fun persistTracks(tracks: List<GpxTrack>, fileName: String?): GpxImportResult = withContext(ioDispatcher) {
        val baseCount = hikeDao.getHikeCount()
        val prepared = withContext(defaultDispatcher) {
            tracks.mapIndexed { index, track -> prepareRecord(track, fileName, baseCount + index) }
        }
        database.withTransaction {
            for (record in prepared) {
                hikeDao.insertHikeWithRoute(record.hike, record.routeChunks)
                if (record.places.isNotEmpty()) {
                    trailPointDao.insertAll(record.places)
                }
            }
        }
        for (record in prepared) {
            localStateRepairer.upsertBestEffort(record.localState)
        }
        GpxImportResult.Success(prepared.map { it.hike.toSummary(it.localState) })
    }

    suspend fun persistDrawnTrail(
        title: String,
        distanceMeters: Double,
        route: List<RouteCoordinate>,
        madeOnEpochMillis: Long
    ): Hike = withContext(ioDispatcher) {
        val tintIndex = hikeDao.getHikeCount()
        val hikeId = UUID.randomUUID().toString()
        val boundedTitle = HikeTitle.drawn(title, madeOnEpochMillis)
        val (hikeEntity, routeChunks, localEntity) = withContext(defaultDispatcher) {
            val stats = HikeRouteStatistics.from(distanceMeters, route)
            val entity = HikeEntity(
                id = hikeId,
                title = boundedTitle,
                customName = null,
                distanceMeters = distanceMeters,
                durationSeconds = null,
                dateEpochMillis = madeOnEpochMillis,
                tintHex = RouteTint.forIndex(tintIndex),
                routeWidthDp = HikeSummary.DEFAULT_ROUTE_WIDTH_DP,
                routeLinePatternId = RouteLinePattern.Default.id,
                routeBorderHex = RouteBorder.NONE_HEX,
                isRecording = false,
                autoFollowEnabled = true,
                trackDescription = null,
                author = null,
                keywords = null,
                routePointCount = route.size
            )
            val chunks = RouteBlobCodec.encodeChunked(route).mapIndexed { index, blob ->
                HikeRouteChunkEntity(hikeId = hikeId, chunkIndex = index, chunkBlob = blob)
            }
            val local = HikeLocalStateEntity(
                hikeId = hikeId,
                listOrder = null,
                climbMeters = stats.elevationGainMeters,
                descentMeters = stats.elevationLossMeters,
                elevationRouteRevision = null,
                ownsRecordingDraft = false,
                autoSavedTileKeysJson = "[]"
            )
            Triple(entity, chunks, local)
        }
        database.withTransaction {
            hikeDao.insertHikeWithRoute(hikeEntity, routeChunks)
        }
        localStateRepairer.upsertBestEffort(localEntity)
        hikeEntity.toDomain(routeChunks, emptyList())
    }

    private fun prepareRecord(track: GpxTrack, fileName: String?, tintIndex: Int): PreparedImportRecord {
        val hikeId = UUID.randomUUID().toString()
        val title = HikeTitle.imported(track.name, fileName).ifBlank { DEFAULT_IMPORTED_TITLE }
        val stats = HikeRouteStatistics.from(track.distanceMeters, track.route)
        val dateMillis = track.startTimeEpochMillis ?: clock.now().toEpochMilli()
        val hikeEntity = HikeEntity(
            id = hikeId,
            title = title,
            customName = null,
            distanceMeters = track.distanceMeters,
            durationSeconds = stats.durationSeconds,
            dateEpochMillis = dateMillis,
            tintHex = RouteTint.forIndex(tintIndex),
            routeWidthDp = HikeSummary.DEFAULT_ROUTE_WIDTH_DP,
            routeLinePatternId = RouteLinePattern.Default.id,
            routeBorderHex = RouteBorder.NONE_HEX,
            isRecording = false,
            autoFollowEnabled = true,
            trackDescription = track.trackDescription,
            author = track.author,
            keywords = track.keywords,
            routePointCount = track.route.size
        )
        val routeChunks = RouteBlobCodec.encodeChunked(track.route).mapIndexed { index, blob ->
            HikeRouteChunkEntity(hikeId = hikeId, chunkIndex = index, chunkBlob = blob)
        }
        val placeEntities = track.places.mapIndexed { idx, wpt ->
            TrailPointEntity(
                id = UUID.randomUUID().toString(),
                hikeId = hikeId,
                latitude = wpt.coordinate.latitude,
                longitude = wpt.coordinate.longitude,
                elevationMeters = wpt.elevationMeters,
                name = wpt.name,
                symbol = wpt.symbol,
                note = wpt.note,
                sortOrder = idx,
                osmElementType = wpt.osm?.elementType.orEmpty(),
                osmElementId = wpt.osm?.elementId ?: 0L,
                osmFactsJson = "[]",
                osmWikidata = wpt.osm?.wikidata,
                createdAtEpochMillis = dateMillis + idx.toLong()
            )
        }
        val localEntity = HikeLocalStateEntity(
            hikeId = hikeId,
            listOrder = null,
            climbMeters = stats.elevationGainMeters,
            descentMeters = stats.elevationLossMeters,
            elevationRouteRevision = null,
            ownsRecordingDraft = false,
            autoSavedTileKeysJson = "[]"
        )
        return PreparedImportRecord(hikeEntity, routeChunks, placeEntities, localEntity)
    }

    private data class PreparedImportRecord(
        val hike: HikeEntity,
        val routeChunks: List<HikeRouteChunkEntity>,
        val places: List<TrailPointEntity>,
        val localState: HikeLocalStateEntity
    )

    private companion object {
        const val DEFAULT_IMPORTED_TITLE = "Imported Hike"
    }
}
