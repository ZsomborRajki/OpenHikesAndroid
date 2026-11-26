package app.openhikes.core.data

import androidx.room.withTransaction
import app.openhikes.core.common.HikeTitle
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.database.OpenHikesDatabase
import app.openhikes.core.database.RouteBlobCodec
import app.openhikes.core.database.dao.HikeDao
import app.openhikes.core.database.dao.HikePhotoDao
import app.openhikes.core.database.dao.TrailPointDao
import app.openhikes.core.database.entity.HikeEntity
import app.openhikes.core.database.entity.HikeLocalStateEntity
import app.openhikes.core.database.entity.HikePhotoEntity
import app.openhikes.core.database.entity.HikeRouteChunkEntity
import app.openhikes.core.database.entity.TrailPointEntity
import app.openhikes.core.model.GpxImportResult
import app.openhikes.core.model.GpxTrack
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeRouteStatistics
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteElevationRevision
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
    private val hikePhotoDao: HikePhotoDao,
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
                if (record.photos.isNotEmpty()) {
                    hikePhotoDao.insertAll(record.photos)
                }
            }
        }
        for (record in prepared) {
            localStateRepairer.upsertBestEffort(record.localState)
        }
        GpxImportResult.Success(prepared.map { it.hike.toSummary(it.localState) })
    }

    internal data class DrawnTrailDraft(
        val title: String,
        val distanceMeters: Double,
        val route: List<RouteCoordinate>,
        val madeOnEpochMillis: Long
    )

    suspend fun persistDrawnTrails(trails: List<DrawnTrailDraft>): List<Hike> = withContext(ioDispatcher) {
        if (trails.isEmpty()) return@withContext emptyList()
        val baseCount = hikeDao.getHikeCount()
        val prepared = withContext(defaultDispatcher) {
            trails.mapIndexed { offset, trail -> prepareDrawnRecord(trail, baseCount + offset) }
        }
        database.withTransaction {
            for ((entity, chunks, _) in prepared) {
                hikeDao.insertHikeWithRoute(entity, chunks)
            }
        }
        for ((_, _, local) in prepared) {
            localStateRepairer.upsertBestEffort(local)
        }
        prepared.map { (entity, chunks, _) -> entity.toDomain(chunks, emptyList()) }
    }

    suspend fun persistDrawnTrail(
        title: String,
        distanceMeters: Double,
        route: List<RouteCoordinate>,
        madeOnEpochMillis: Long
    ): Hike = persistDrawnTrails(
        listOf(DrawnTrailDraft(title, distanceMeters, route, madeOnEpochMillis))
    ).single()

    private fun prepareDrawnRecord(
        trail: DrawnTrailDraft,
        tintIndex: Int
    ): Triple<HikeEntity, List<HikeRouteChunkEntity>, HikeLocalStateEntity> {
        val hikeId = UUID.randomUUID().toString()
        val boundedTitle = HikeTitle.drawn(trail.title, trail.madeOnEpochMillis)
        val stats = HikeRouteStatistics.from(trail.distanceMeters, trail.route)
        val entity = HikeEntity(
            id = hikeId,
            title = boundedTitle,
            customName = null,
            distanceMeters = trail.distanceMeters,
            durationSeconds = null,
            dateEpochMillis = trail.madeOnEpochMillis,
            tintHex = RouteTint.forIndex(tintIndex),
            routeWidthDp = HikeSummary.DEFAULT_ROUTE_WIDTH_DP,
            routeLinePatternId = RouteLinePattern.Default.id,
            routeBorderHex = RouteBorder.NONE_HEX,
            isRecording = false,
            autoFollowEnabled = true,
            trackDescription = null,
            author = null,
            keywords = null,
            routePointCount = trail.route.size
        )
        val chunks = RouteBlobCodec.encodeChunked(trail.route).mapIndexed { index, blob ->
            HikeRouteChunkEntity(hikeId = hikeId, chunkIndex = index, chunkBlob = blob)
        }
        val local = HikeLocalStateEntity(
            hikeId = hikeId,
            listOrder = null,
            climbMeters = stats.elevationGainMeters,
            descentMeters = stats.elevationLossMeters,
            elevationRouteRevision = RouteElevationRevision.of(trail.route),
            ownsRecordingDraft = false,
            autoSavedTileKeysJson = "[]"
        )
        return Triple(entity, chunks, local)
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
        val placeEntities = buildPlaceEntities(hikeId, dateMillis, track)
        val photoEntities = buildPhotoEntities(hikeId, track)
        val localEntity = HikeLocalStateEntity(
            hikeId = hikeId,
            listOrder = null,
            climbMeters = stats.elevationGainMeters,
            descentMeters = stats.elevationLossMeters,
            elevationRouteRevision = RouteElevationRevision.of(track.route),
            ownsRecordingDraft = false,
            autoSavedTileKeysJson = "[]"
        )
        return PreparedImportRecord(hikeEntity, routeChunks, placeEntities, photoEntities, localEntity)
    }

    private fun buildPlaceEntities(hikeId: String, dateMillis: Long, track: GpxTrack): List<TrailPointEntity> =
        track.places.mapIndexed { idx, wpt ->
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

    private fun buildPhotoEntities(hikeId: String, track: GpxTrack): List<HikePhotoEntity> = track.photos.map { wpt ->
        HikePhotoEntity(
            id = UUID.randomUUID().toString(),
            hikeId = hikeId,
            capturedAtEpochMillis = wpt.capturedAtEpochMillis,
            latitude = wpt.coordinate.latitude,
            longitude = wpt.coordinate.longitude,
            importedAuthorName = wpt.authorName,
            isPlaceOnly = true
        )
    }

    private data class PreparedImportRecord(
        val hike: HikeEntity,
        val routeChunks: List<HikeRouteChunkEntity>,
        val places: List<TrailPointEntity>,
        val photos: List<HikePhotoEntity>,
        val localState: HikeLocalStateEntity
    )

    private companion object {
        const val DEFAULT_IMPORTED_TITLE = "Imported Hike"
    }
}
