package app.openhikes.core.data

import app.openhikes.core.common.DefaultDispatcher
import app.openhikes.core.common.HikeTitleEdit
import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.common.gpx.GpxExporter
import app.openhikes.core.common.gpx.GpxParseResult
import app.openhikes.core.common.gpx.GpxParser
import app.openhikes.core.database.OpenHikesDatabase
import app.openhikes.core.database.dao.HikeDao
import app.openhikes.core.database.dao.HikeLocalStateDao
import app.openhikes.core.database.dao.HikeWalkDao
import app.openhikes.core.database.dao.TrailPointDao
import app.openhikes.core.model.ExportedGpx
import app.openhikes.core.model.GpxImportFailure
import app.openhikes.core.model.GpxImportResult
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeLocalState
import app.openhikes.core.model.HikeMapRoute
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.model.PreparedRecording
import app.openhikes.core.model.RouteStyleUpdate
import java.io.IOException
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
 * off-main GPX parsing/export, live recording draft storage, and deterministic test overrides.
 */
@Singleton
@Suppress("LongParameterList")
internal class OfflineFirstHikeRepository @Inject constructor(
    database: OpenHikesDatabase,
    private val hikeDao: HikeDao,
    private val trailPointDao: TrailPointDao,
    hikeWalkDao: HikeWalkDao,
    private val hikeLocalStateDao: HikeLocalStateDao,
    private val gpxFileSource: GpxFileSource,
    private val gpxFileSink: GpxFileSink,
    private val bundledGpxFixtureSource: BundledGpxFixtureSource,
    private val launchConfigHolder: LaunchConfigHolder,
    clock: OpenHikesClock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : HikeRepository {
    private val hasSeededFixture = AtomicBoolean(false)
    private val hasConsumedFirstSaveFailure = AtomicBoolean(false)
    private val localStateRepairer = HikeLocalStateRepairer(
        hikeDao = hikeDao,
        hikeLocalStateDao = hikeLocalStateDao,
        ioDispatcher = ioDispatcher,
        defaultDispatcher = defaultDispatcher
    )
    private val gpxImportStore = GpxImportStore(
        database = database,
        hikeDao = hikeDao,
        trailPointDao = trailPointDao,
        localStateRepairer = localStateRepairer,
        clock = clock,
        ioDispatcher = ioDispatcher,
        defaultDispatcher = defaultDispatcher
    )
    private val recordingDraftStore = RecordingDraftStore(
        database = database,
        hikeDao = hikeDao,
        trailPointDao = trailPointDao,
        hikeWalkDao = hikeWalkDao,
        hikeLocalStateDao = hikeLocalStateDao,
        localStateRepairer = localStateRepairer,
        shouldFailFirstSave = {
            launchConfigHolder.current.value.failsFirstSave && !hasConsumedFirstSaveFailure.getAndSet(true)
        },
        ioDispatcher = ioDispatcher,
        defaultDispatcher = defaultDispatcher
    )

    override val hikes: Flow<List<HikeSummary>> = combine(
        hikeDao.observeAllHikes(),
        hikeLocalStateDao.observeAll()
    ) { entities, localStates ->
        val existingByHikeId = localStates.associateBy { it.hikeId }
        val resolvedByHikeId = localStateRepairer.repairMissingForHikes(entities, existingByHikeId)
        entities.map { entity -> entity.toSummary(resolvedByHikeId[entity.id]) }
    }.flowOn(defaultDispatcher)

    override val mapRoutes: Flow<List<HikeMapRoute>> = hikeDao.observeAllMapRouteChunks()
        .map { rows -> rows.toMapRoutes() }
        .flowOn(defaultDispatcher)

    override fun observeHike(hikeId: HikeId): Flow<Hike?> = combine(
        hikeDao.observeHikeWithRoute(hikeId.value),
        trailPointDao.observeByHikeId(hikeId.value)
    ) { hikeWithRoute, trailPoints ->
        hikeWithRoute?.hike?.toDomain(hikeWithRoute.routeChunks, trailPoints)
    }.flowOn(defaultDispatcher)

    override fun observeLocalState(hikeId: HikeId): Flow<HikeLocalState?> =
        hikeLocalStateDao.observeByHikeId(hikeId.value)
            .map { entity ->
                val resolved = entity ?: localStateRepairer.repairIfMissing(hikeId.value)
                resolved?.toDomain()
            }
            .flowOn(defaultDispatcher)

    override suspend fun importGpxBytes(bytes: ByteArray, fileName: String?): GpxImportResult =
        when (val parseResult = withContext(defaultDispatcher) { GpxParser.parseAll(bytes) }) {
            is GpxParseResult.Failure -> GpxImportResult.Failure(parseResult.reason)
            is GpxParseResult.Success -> {
                val tracks = parseResult.value.filter { it.route.size >= 2 }
                if (tracks.isEmpty()) {
                    GpxImportResult.Failure(GpxImportFailure.TOO_SHORT)
                } else {
                    gpxImportStore.persistTracks(tracks, fileName)
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
        val failSave = launchConfigHolder.current.value.failsFirstSave && !hasConsumedFirstSaveFailure.getAndSet(true)
        if (failSave) {
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
            val failSave = launchConfigHolder.current.value.failsFirstSave &&
                !hasConsumedFirstSaveFailure.getAndSet(true)
            if (failSave) {
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
        val deleted = hikeDao.deleteHikeById(hikeId.value)
        if (deleted > 0) {
            localStateRepairer.deleteBestEffort(hikeId.value)
            Result.success(Unit)
        } else {
            Result.failure(NoSuchElementException("Hike not found: ${hikeId.value}"))
        }
    }

    override suspend fun createOrClaimRecordingDraft(
        hikeId: HikeId,
        startedAtEpochMillis: Long,
        title: String
    ): Result<Hike> = recordingDraftStore.createOrClaimRecordingDraft(hikeId, startedAtEpochMillis, title)

    override suspend fun finalizeRecordingDraft(
        hikeId: HikeId,
        title: String,
        customName: String?,
        startedAtEpochMillis: Long,
        endedAtEpochMillis: Long,
        prepared: PreparedRecording
    ): Result<Hike> = recordingDraftStore.finalizeRecordingDraft(
        hikeId = hikeId,
        title = title,
        customName = customName,
        startedAtEpochMillis = startedAtEpochMillis,
        endedAtEpochMillis = endedAtEpochMillis,
        prepared = prepared
    )

    override suspend fun deleteRecordingDraft(hikeId: HikeId): Result<Unit> =
        recordingDraftStore.deleteRecordingDraft(hikeId)

    override suspend fun deleteOwnedOrphanedRecordingDrafts(exceptHikeId: HikeId?): Result<Int> =
        recordingDraftStore.deleteOwnedOrphanedRecordingDrafts(exceptHikeId)

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
}
