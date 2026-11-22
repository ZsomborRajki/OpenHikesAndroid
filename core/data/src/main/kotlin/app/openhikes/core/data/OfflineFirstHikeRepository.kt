package app.openhikes.core.data

import androidx.room.withTransaction
import app.openhikes.core.common.DefaultDispatcher
import app.openhikes.core.common.HikeTitle
import app.openhikes.core.common.HikeTitleEdit
import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.common.gpx.GpxExporter
import app.openhikes.core.common.gpx.GpxParseResult
import app.openhikes.core.common.gpx.GpxParser
import app.openhikes.core.database.OpenHikesDatabase
import app.openhikes.core.database.RouteBlobCodec
import app.openhikes.core.database.dao.HikeDao
import app.openhikes.core.database.dao.HikeLocalStateDao
import app.openhikes.core.database.dao.TrailPointDao
import app.openhikes.core.database.entity.HikeEntity
import app.openhikes.core.database.entity.HikeLocalStateEntity
import app.openhikes.core.database.entity.HikeRouteEntity
import app.openhikes.core.database.entity.TrailPointEntity
import app.openhikes.core.model.ExportedGpx
import app.openhikes.core.model.GpxImportFailure
import app.openhikes.core.model.GpxImportResult
import app.openhikes.core.model.GpxTrack
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeLocalState
import app.openhikes.core.model.HikeMapRoute
import app.openhikes.core.model.HikeRouteStatistics
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.RouteStyleUpdate
import app.openhikes.core.model.RouteTint
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room-backed [HikeRepository] enforcing portable vs. device-local separation,
 * off-main GPX parsing/export, and deterministic test overrides from [LaunchConfigHolder].
 */
@Singleton
@Suppress("LongParameterList")
internal class OfflineFirstHikeRepository @Inject constructor(
    private val database: OpenHikesDatabase,
    private val hikeDao: HikeDao,
    private val trailPointDao: TrailPointDao,
    private val hikeLocalStateDao: HikeLocalStateDao,
    private val gpxFileSource: GpxFileSource,
    private val gpxFileSink: GpxFileSink,
    private val bundledGpxFixtureSource: BundledGpxFixtureSource,
    private val launchConfigHolder: LaunchConfigHolder,
    private val clock: OpenHikesClock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : HikeRepository {
    private val hasSeededFixture = AtomicBoolean(false)
    private val hasConsumedFirstSaveFailure = AtomicBoolean(false)

    override val hikes: Flow<List<HikeSummary>> = combine(
        hikeDao.observeAllHikes(),
        hikeLocalStateDao.observeAll()
    ) { entities, localStates ->
        val localByHikeId = localStates.associateBy { it.hikeId }
        entities.map { entity -> entity.toSummary(localByHikeId[entity.id]) }
    }.flowOn(defaultDispatcher)

    override val mapRoutes: Flow<List<HikeMapRoute>> = hikeDao.observeAllMapRoutes()
        .map { rows -> rows.map { it.toDomain() } }
        .flowOn(defaultDispatcher)

    override fun observeHike(hikeId: HikeId): Flow<Hike?> = combine(
        hikeDao.observeHikeWithRoute(hikeId.value),
        trailPointDao.observeByHikeId(hikeId.value)
    ) { hikeWithRoute, trailPoints ->
        hikeWithRoute?.hike?.toDomain(hikeWithRoute.routeBlob, trailPoints)
    }.flowOn(defaultDispatcher)

    override fun observeLocalState(hikeId: HikeId): Flow<HikeLocalState?> =
        hikeLocalStateDao.observeByHikeId(hikeId.value)
            .map { it?.toDomain() }
            .flowOn(defaultDispatcher)

    override suspend fun importGpxBytes(bytes: ByteArray, fileName: String?): GpxImportResult =
        when (val parseResult = withContext(defaultDispatcher) { GpxParser.parseAll(bytes) }) {
            is GpxParseResult.Failure -> GpxImportResult.Failure(parseResult.reason)
            is GpxParseResult.Success -> {
                val tracks = parseResult.value.filter { it.route.size >= 2 }
                if (tracks.isEmpty()) {
                    GpxImportResult.Failure(GpxImportFailure.TOO_SHORT)
                } else {
                    persistTracks(tracks, fileName)
                }
            }
        }

    override suspend fun importGpxUri(uriString: String): GpxImportResult {
        val payload = gpxFileSource.read(uriString)
            ?: return GpxImportResult.Failure(GpxImportFailure.UNREADABLE)
        return importGpxBytes(payload.bytes, payload.fileName)
    }

    override suspend fun exportHike(hikeId: HikeId): Result<ExportedGpx> = withContext(defaultDispatcher) {
        val hike = observeHike(hikeId).first()
            ?: return@withContext Result.failure(NoSuchElementException("Hike not found: ${hikeId.value}"))
        Result.success(GpxExporter.export(hike))
    }

    override suspend fun exportHikeToUri(hikeId: HikeId, uriString: String): Result<Unit> {
        val exported = exportHike(hikeId).getOrElse { return Result.failure(it) }
        return gpxFileSink.write(uriString, exported.xml)
    }

    override suspend fun renameHike(hikeId: HikeId, draftTitle: String): Result<Unit> = withContext(ioDispatcher) {
        if (shouldFailFirstSave()) {
            return@withContext Result.failure(IOException("Simulated rename failure"))
        }
        val existing = hikeDao.getHikeById(hikeId.value)
            ?: return@withContext Result.failure(NoSuchElementException("Hike not found: ${hikeId.value}"))
        val displayTitle = existing.customName?.takeIf { it.isNotEmpty() } ?: existing.title
        when (val edit = HikeTitleEdit.of(draftTitle, displayTitle)) {
            HikeTitleEdit.Unchanged -> Result.success(Unit)
            is HikeTitleEdit.Renamed -> {
                hikeDao.updateCustomName(hikeId.value, edit.customName)
                Result.success(Unit)
            }
        }
    }

    override suspend fun updateRouteStyle(hikeId: HikeId, style: RouteStyleUpdate): Result<Unit> =
        withContext(ioDispatcher) {
            if (shouldFailFirstSave()) {
                return@withContext Result.failure(IOException("Simulated style update failure"))
            }
            val updated = hikeDao.updateRouteStyle(
                hikeId = hikeId.value,
                tintHex = style.tintHex,
                routeBorderHex = style.routeBorderHex,
                routeWidthDp = style.clampedWidthDp,
                routeLinePatternId = style.linePattern.id
            )
            if (updated > 0) {
                Result.success(Unit)
            } else {
                Result.failure(NoSuchElementException("Hike not found: ${hikeId.value}"))
            }
        }

    override suspend fun deleteHike(hikeId: HikeId): Result<Unit> = withContext(ioDispatcher) {
        if (launchConfigHolder.current.value.refusesHikeDeletion) {
            return@withContext Result.failure(IOException("Hike deletion refused by launch configuration"))
        }
        hikeLocalStateDao.deleteByHikeId(hikeId.value)
        val deleted = hikeDao.deleteHikeById(hikeId.value)
        if (deleted > 0) {
            Result.success(Unit)
        } else {
            Result.failure(NoSuchElementException("Hike not found: ${hikeId.value}"))
        }
    }

    override suspend fun seedFixtureIfConfigured(): HikeId? {
        val fixtureName = launchConfigHolder.current.value.importedGpxFixtureName
        val loaded = if (fixtureName != null && hasSeededFixture.compareAndSet(false, true)) {
            bundledGpxFixtureSource.loadFixture(fixtureName)
        } else {
            null
        }
        return loaded?.let { (fileName, bytes) ->
            (importGpxBytes(bytes, fileName) as? GpxImportResult.Success)?.importedHikes?.firstOrNull()?.id
        }
    }

    private suspend fun persistTracks(tracks: List<GpxTrack>, fileName: String?): GpxImportResult =
        withContext(ioDispatcher) {
            val baseCount = hikeDao.getHikeCount()
            val prepared = withContext(defaultDispatcher) {
                tracks.mapIndexed { index, track -> prepareRecord(track, fileName, baseCount + index) }
            }
            database.withTransaction {
                for (record in prepared) {
                    hikeDao.insertHikeWithRoute(record.hike, record.route)
                    if (record.places.isNotEmpty()) {
                        trailPointDao.insertAll(record.places)
                    }
                }
            }
            for (record in prepared) {
                hikeLocalStateDao.upsert(record.localState)
            }
            GpxImportResult.Success(prepared.map { it.hike.toSummary(it.localState) })
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
        val routeEntity = HikeRouteEntity(
            hikeId = hikeId,
            routeBlob = RouteBlobCodec.encode(track.route)
        )
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
                sortOrder = idx
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
        return PreparedImportRecord(hikeEntity, routeEntity, placeEntities, localEntity)
    }

    private fun shouldFailFirstSave(): Boolean =
        launchConfigHolder.current.value.failsFirstSave && !hasConsumedFirstSaveFailure.getAndSet(true)

    private data class PreparedImportRecord(
        val hike: HikeEntity,
        val route: HikeRouteEntity,
        val places: List<TrailPointEntity>,
        val localState: HikeLocalStateEntity
    )

    private companion object {
        const val DEFAULT_IMPORTED_TITLE = "Imported Hike"
    }
}
