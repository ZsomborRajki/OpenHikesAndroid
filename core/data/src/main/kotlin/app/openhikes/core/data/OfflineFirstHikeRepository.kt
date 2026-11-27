package app.openhikes.core.data

import android.content.Context
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
import app.openhikes.core.database.dao.HikePhotoDao
import app.openhikes.core.database.dao.HikeWalkDao
import app.openhikes.core.database.dao.TrailPointDao
import app.openhikes.core.model.EditedLine
import app.openhikes.core.model.ExportedGpx
import app.openhikes.core.model.ExportedHikeArchive
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.GpxImportFailure
import app.openhikes.core.model.GpxImportResult
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeLocalState
import app.openhikes.core.model.HikeMapRoute
import app.openhikes.core.model.HikePhoto
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.model.HikeWalk
import app.openhikes.core.model.LibraryLine
import app.openhikes.core.model.LibraryPhotoMatch
import app.openhikes.core.model.LibraryTotals
import app.openhikes.core.model.PreparedRecording
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteEditOutcome
import app.openhikes.core.model.RouteEditSources
import app.openhikes.core.model.RouteStyleUpdate
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.TrailPoint
import app.openhikes.core.model.TrailWalkEndReason
import app.openhikes.core.model.TrailWalkRecord
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
@Suppress("LongParameterList", "TooManyFunctions")
internal class OfflineFirstHikeRepository @Inject constructor(
    @ApplicationContext context: Context,
    database: OpenHikesDatabase,
    private val hikeDao: HikeDao,
    private val trailPointDao: TrailPointDao,
    private val hikePhotoDao: HikePhotoDao,
    hikeWalkDao: HikeWalkDao,
    private val hikeLocalStateDao: HikeLocalStateDao,
    private val photoStore: HikePhotoStore,
    photoLibraryMirror: PhotoLibraryMirror,
    photoFileIo: PhotoFileIo,
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
    private val totalsRevisionCounter = AtomicLong(0L)
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
        hikePhotoDao = hikePhotoDao,
        localStateRepairer = localStateRepairer,
        clock = clock,
        ioDispatcher = ioDispatcher,
        defaultDispatcher = defaultDispatcher
    )
    private val recordingDraftStore = RecordingDraftStore(
        database = database,
        hikeDao = hikeDao,
        trailPointDao = trailPointDao,
        hikePhotoDao = hikePhotoDao,
        hikeWalkDao = hikeWalkDao,
        hikeLocalStateDao = hikeLocalStateDao,
        localStateRepairer = localStateRepairer,
        photoStore = photoStore,
        shouldFailFirstSave = {
            launchConfigHolder.current.value.failsFirstSave && !hasConsumedFirstSaveFailure.getAndSet(true)
        },
        ioDispatcher = ioDispatcher,
        defaultDispatcher = defaultDispatcher
    )
    private val walkPersistenceStore = WalkPersistenceStore(
        database = database,
        hikeDao = hikeDao,
        hikeWalkDao = hikeWalkDao,
        hikeLocalStateDao = hikeLocalStateDao,
        shouldFailFirstSave = {
            launchConfigHolder.current.value.failsFirstSave && !hasConsumedFirstSaveFailure.getAndSet(true)
        },
        ioDispatcher = ioDispatcher,
        defaultDispatcher = defaultDispatcher
    )
    private val routeEditStore = RouteEditStore(
        database = database,
        hikeDao = hikeDao,
        hikeWalkDao = hikeWalkDao,
        hikeLocalStateDao = hikeLocalStateDao,
        localStateRepairer = localStateRepairer,
        shouldFailFirstSave = {
            launchConfigHolder.current.value.failsFirstSave && !hasConsumedFirstSaveFailure.getAndSet(true)
        },
        ioDispatcher = ioDispatcher,
        defaultDispatcher = defaultDispatcher
    )
    private val hikePlaceStore = HikePlaceStore(
        database = database,
        hikeDao = hikeDao,
        trailPointDao = trailPointDao,
        hikePhotoDao = hikePhotoDao,
        clock = clock,
        shouldFailFirstSave = {
            launchConfigHolder.current.value.failsFirstSave && !hasConsumedFirstSaveFailure.getAndSet(true)
        },
        ioDispatcher = ioDispatcher
    )
    private val hikePhotoImportStore = HikePhotoImportStore(
        context = context,
        hikeDao = hikeDao,
        trailPointDao = trailPointDao,
        hikeWalkDao = hikeWalkDao,
        hikePhotoDao = hikePhotoDao,
        photoStore = photoStore,
        photoLibraryMirror = photoLibraryMirror,
        photoFileIo = photoFileIo,
        clock = clock,
        shouldFailFirstSave = {
            launchConfigHolder.current.value.failsFirstSave && !hasConsumedFirstSaveFailure.getAndSet(true)
        },
        ioDispatcher = ioDispatcher,
        defaultDispatcher = defaultDispatcher
    )
    private val libraryMetricsStore = LibraryMetricsStore(
        hikeDao = hikeDao,
        hikeWalkDao = hikeWalkDao,
        hikeLocalStateDao = hikeLocalStateDao,
        gpxImportStore = gpxImportStore,
        bundledGpxFixtureSource = bundledGpxFixtureSource,
        clock = clock,
        shouldFailFirstSave = {
            launchConfigHolder.current.value.failsFirstSave && !hasConsumedFirstSaveFailure.getAndSet(true)
        },
        ioDispatcher = ioDispatcher,
        defaultDispatcher = defaultDispatcher
    )

    override val hikes: Flow<List<HikeSummary>> = combine(
        hikeDao.observeAllHikes(),
        hikeLocalStateDao.observeAll(),
        hikeWalkDao.observeWalkCounts()
    ) { entities, localStates, walkCounts ->
        val existingByHikeId = localStates.associateBy { it.hikeId }
        val resolvedByHikeId = localStateRepairer.repairMissingForHikes(entities, existingByHikeId)
        val walkCountsByHikeId = walkCounts.associate { it.hikeId to it.walkCount }
        entities.map { entity ->
            entity.toSummary(
                localState = resolvedByHikeId[entity.id],
                walkCount = walkCountsByHikeId[entity.id] ?: 0
            )
        }
    }.flowOn(defaultDispatcher)

    override val mapRoutes: Flow<List<HikeMapRoute>> = hikeDao.observeAllMapRouteChunks()
        .map { rows -> rows.toMapRoutes() }
        .flowOn(defaultDispatcher)

    override val libraryLines: Flow<List<LibraryLine>> = hikeDao.observeAllMapRouteChunks()
        .map { rows -> libraryMetricsStore.toLibraryLines(rows) }
        .flowOn(defaultDispatcher)

    override val totalsInvalidation: Flow<Long> = combine(
        hikeDao.observeAllHikes(),
        hikeDao.observeRouteChunkCount(),
        hikeWalkDao.observeTotalWalkCount()
    ) { _, _, _ ->
        totalsRevisionCounter.incrementAndGet()
    }

    override fun observeHike(hikeId: HikeId): Flow<Hike?> = combine(
        hikeDao.observeHikeWithRoute(hikeId.value),
        trailPointDao.observeByHikeId(hikeId.value),
        hikePhotoDao.observeByHikeId(hikeId.value)
    ) { hikeWithRoute, trailPoints, photos ->
        hikeWithRoute?.hike?.toDomain(hikeWithRoute.routeChunks, trailPoints, photos)
    }.flowOn(defaultDispatcher)

    override fun observeLocalState(hikeId: HikeId): Flow<HikeLocalState?> =
        hikeLocalStateDao.observeByHikeId(hikeId.value)
            .map { entity ->
                val resolved = entity ?: localStateRepairer.repairIfMissing(hikeId.value)
                resolved?.toDomain()
            }
            .flowOn(defaultDispatcher)

    override fun observeWalks(hikeId: HikeId): Flow<List<HikeWalk>> = walkPersistenceStore.observeWalks(hikeId)

    override fun observeWalk(walkId: String): Flow<HikeWalk?> = walkPersistenceStore.observeWalk(walkId)

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

    override suspend fun loadRouteEditSources(hikeId: HikeId): RouteEditSources? =
        routeEditStore.loadRouteEditSources(hikeId)

    override suspend fun applyRouteEdit(
        hikeId: HikeId,
        expectedCurrentRoute: List<RouteCoordinate>,
        edited: EditedLine
    ): RouteEditOutcome = routeEditStore.applyRouteEdit(hikeId, expectedCurrentRoute, edited)

    override suspend fun revertRouteEdit(
        hikeId: HikeId,
        expectedCurrentRoute: List<RouteCoordinate>
    ): RouteEditOutcome = routeEditStore.revertRouteEdit(hikeId, expectedCurrentRoute)

    override suspend fun setAutoFollowEnabled(hikeId: HikeId, enabled: Boolean): Result<Unit> =
        withContext(ioDispatcher) {
            val updated = hikeDao.updateAutoFollowEnabled(hikeId.value, enabled)
            if (updated > 0) {
                Result.success(Unit)
            } else {
                Result.failure(NoSuchElementException("Hike not found: ${hikeId.value}"))
            }
        }

    override suspend fun saveWalkInProgress(hikeId: HikeId, record: TrailWalkRecord?): Result<Unit> =
        walkPersistenceStore.saveWalkInProgress(hikeId, record)

    override suspend fun getOpenWalksInProgress(): Result<List<TrailWalkRecord>> =
        walkPersistenceStore.getOpenWalksInProgress()

    override suspend fun finishWalk(
        record: TrailWalkRecord,
        endedAtEpochMillis: Long,
        reason: TrailWalkEndReason
    ): Result<HikeWalk?> = walkPersistenceStore.finishWalk(record, endedAtEpochMillis, reason)

    override suspend fun deleteHike(hikeId: HikeId): Result<Unit> = withContext(ioDispatcher) {
        if (launchConfigHolder.current.value.refusesHikeDeletion) {
            return@withContext Result.failure(IOException("Hike deletion refused by launch configuration"))
        }
        val photos = hikePhotoDao.getByHikeId(hikeId.value).map { it.toDomain() }
        val deleted = hikeDao.deleteHikeById(hikeId.value)
        if (deleted > 0) {
            localStateRepairer.deleteBestEffort(hikeId.value)
            photoStore.deleteFiles(photos)
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

    override suspend fun saveDrawnTrail(
        title: String,
        distanceMeters: Double,
        route: List<RouteCoordinate>,
        madeOnEpochMillis: Long
    ): Result<Hike> = withContext(ioDispatcher) {
        if (route.size < 2) {
            return@withContext Result.failure(IllegalArgumentException("A drawn trail needs at least two points"))
        }
        val failSave = launchConfigHolder.current.value.failsFirstSave && !hasConsumedFirstSaveFailure.getAndSet(true)
        if (failSave) {
            return@withContext Result.failure(IOException("Simulated drawn trail save failure"))
        }
        runCatching {
            gpxImportStore.persistDrawnTrail(
                title = title,
                distanceMeters = distanceMeters,
                route = route,
                madeOnEpochMillis = madeOnEpochMillis
            )
        }
    }

    override suspend fun addPlace(hikeId: HikeId, place: TrailPoint): Result<Boolean> =
        hikePlaceStore.addPlace(hikeId, place)

    override suspend fun editPlace(
        hikeId: HikeId,
        placeId: String,
        name: String,
        symbol: TrailPlaceSymbol?,
        note: String
    ): Result<Boolean> = hikePlaceStore.editPlace(hikeId, placeId, name, symbol, note)

    override suspend fun removePlace(hikeId: HikeId, placeId: String): Result<Unit> =
        hikePlaceStore.removePlace(hikeId, placeId)

    override suspend fun addCameraPhoto(
        hikeId: HikeId,
        rawBytes: ByteArray,
        capturedAtEpochMillis: Long?,
        coordinate: GeoCoordinate?,
        placeId: String?,
        saveToSystemLibrary: Boolean
    ): Result<HikePhoto> = hikePhotoImportStore.addCameraPhoto(
        hikeId = hikeId,
        rawBytes = rawBytes,
        capturedAtEpochMillis = capturedAtEpochMillis,
        coordinate = coordinate,
        placeId = placeId,
        saveToSystemLibrary = saveToSystemLibrary
    )

    override suspend fun addPickedPhoto(
        hikeId: HikeId,
        bytes: ByteArray,
        assetLocalIdentifier: String?,
        fallbackCoordinate: GeoCoordinate?,
        placeId: String?
    ): Result<HikePhoto> = hikePhotoImportStore.addPickedPhoto(
        hikeId = hikeId,
        bytes = bytes,
        assetLocalIdentifier = assetLocalIdentifier,
        fallbackCoordinate = fallbackCoordinate,
        placeId = placeId
    )

    override suspend fun addPickedPhotoUri(
        hikeId: HikeId,
        uriString: String,
        fallbackCoordinate: GeoCoordinate?,
        placeId: String?
    ): Result<HikePhoto> = hikePhotoImportStore.addPickedPhotoUri(
        hikeId = hikeId,
        uriString = uriString,
        fallbackCoordinate = fallbackCoordinate,
        placeId = placeId
    )

    override suspend fun addDiscoveredPhoto(
        hikeId: HikeId,
        bytes: ByteArray,
        match: LibraryPhotoMatch,
        expectedPlanFingerprint: Long
    ): Result<HikePhoto?> = hikePhotoImportStore.addDiscoveredPhoto(
        hikeId = hikeId,
        bytes = bytes,
        match = match,
        expectedPlanFingerprint = expectedPlanFingerprint
    )

    override suspend fun removePhoto(hikeId: HikeId, photoId: String): Result<Unit> =
        hikePhotoImportStore.removePhoto(hikeId, photoId)

    override suspend fun readPhotoBytes(photo: HikePhoto): ByteArray? = photoStore.readOriginalBytes(photo)

    override suspend fun readPhotoThumbnailBytes(photo: HikePhoto): ByteArray? =
        photoStore.readOrBuildThumbnailBytes(photo)

    override suspend fun hasPhotoFile(photo: HikePhoto): Boolean = photoStore.hasOriginalFile(photo)

    override suspend fun prepareSharePhotoFile(
        hikeTitle: String,
        positionOneBased: Int,
        photo: HikePhoto
    ): Result<PreparedSharePhoto> = hikePhotoImportStore.prepareSharePhotoFile(hikeTitle, positionOneBased, photo)

    override suspend fun exportHikeArchive(hikeId: HikeId): Result<ExportedHikeArchive> {
        val hike = observeHike(hikeId).first()
            ?: return Result.failure(NoSuchElementException("Hike not found: ${hikeId.value}"))
        return hikePhotoImportStore.exportHikeArchive(hike)
    }

    override suspend fun exportHikeArchiveToUri(hikeId: HikeId, uriString: String): Result<Unit> {
        val hike = observeHike(hikeId).first()
            ?: return Result.failure(NoSuchElementException("Hike not found: ${hikeId.value}"))
        return hikePhotoImportStore.exportHikeArchiveToUri(hike, uriString)
    }

    override suspend fun measurePhotoStorageBytes(): Long = hikePhotoImportStore.measurePhotoStorageBytes()

    override suspend fun reclaimOrphanedPhotos(): Result<Int> = hikePhotoImportStore.reclaimOrphanedPhotos()

    override suspend fun saveCustomHikeOrder(orderedIds: List<HikeId>): Result<Unit> =
        libraryMetricsStore.saveCustomHikeOrder(orderedIds)

    override suspend fun clearCustomHikeOrder(): Result<Unit> = libraryMetricsStore.clearCustomHikeOrder()

    override suspend fun validateElevationMetrics(hikeIds: List<HikeId>): Result<Boolean> =
        libraryMetricsStore.validateElevationMetrics(hikeIds)

    override suspend fun computeLibraryTotals(asOfEpochMillis: Long, zoneId: ZoneId): Result<LibraryTotals> =
        libraryMetricsStore.computeLibraryTotals(asOfEpochMillis, zoneId)

    override suspend fun seedFixtureIfConfigured(): HikeId? {
        val config = launchConfigHolder.current.value
        val shouldSeed = hasSeededFixture.compareAndSet(false, true)
        val fixtureName = config.importedGpxFixtureName
        val loaded = if (fixtureName != null && shouldSeed) {
            bundledGpxFixtureSource.loadFixture(fixtureName)
        } else {
            null
        }
        val importedId = loaded?.let { (fileName, bytes) ->
            (importGpxBytes(bytes, fileName) as? GpxImportResult.Success)?.importedHikes?.firstOrNull()?.id
        }
        if (shouldSeed && (config.seedHikeCount > 0 || config.seedNearbyTrails)) {
            libraryMetricsStore.seedLibraryFixtures(config)
        }
        if (importedId != null) {
            val hike = observeHike(importedId).first()
            if (hike != null && config.seedWalkPresetName == WalkPersistenceStore.SEED_WALK_HALF_LOOP) {
                val walk = WalkPersistenceStore.buildHalfLoopSeedWalk(
                    hikeId = importedId,
                    routeDistanceMeters = hike.distanceMeters,
                    routeRevision = TrailWalkRecord.routeRevision(hike.route),
                    nowEpochMillis = clock.now().toEpochMilli()
                )
                walkPersistenceStore.seedWalk(walk)
            }
            if (hike != null && config.seedPhotoCount > 0) {
                hikePhotoImportStore.seedPhotosForHike(
                    hike = hike,
                    count = config.seedPhotoCount,
                    omitFiles = config.omitSeededPhotoFiles
                )
            }
        }
        return importedId
    }
}
