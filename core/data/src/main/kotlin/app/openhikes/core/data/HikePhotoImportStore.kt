package app.openhikes.core.data

import android.content.Context
import android.database.SQLException
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.common.gpx.GpxExporter
import app.openhikes.core.common.photos.HikePhotoArchiveBuilder
import app.openhikes.core.common.photos.ImageDataFormat
import app.openhikes.core.database.RouteBlobCodec
import app.openhikes.core.database.dao.HikeDao
import app.openhikes.core.database.dao.HikePhotoDao
import app.openhikes.core.database.dao.HikeWalkDao
import app.openhikes.core.database.dao.TrailPointDao
import app.openhikes.core.model.ExportedHikeArchive
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikePhoto
import app.openhikes.core.model.HikePhotoSearchPlan
import app.openhikes.core.model.LibraryPhotoMatch
import app.openhikes.core.model.PhotoMatchEvidence
import app.openhikes.core.model.PickedPhotoPlacement
import app.openhikes.core.model.RouteProfile
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Main-safe persistence coordinator for attaching, deduplicating, mirroring, sharing, removing,
 * and sweeping hike photographs while keeping Room metadata and local disk files consistent.
 */
@Suppress("LongParameterList", "TooManyFunctions")
internal class HikePhotoImportStore(
    private val context: Context,
    private val hikeDao: HikeDao,
    private val trailPointDao: TrailPointDao,
    private val hikeWalkDao: HikeWalkDao,
    private val hikePhotoDao: HikePhotoDao,
    private val photoStore: HikePhotoStore,
    private val photoLibraryMirror: PhotoLibraryMirror,
    private val photoFileIo: PhotoFileIo,
    private val clock: OpenHikesClock,
    private val shouldFailFirstSave: () -> Boolean,
    private val ioDispatcher: CoroutineDispatcher,
    private val defaultDispatcher: CoroutineDispatcher
) {
    suspend fun addCameraPhoto(
        hikeId: HikeId,
        rawBytes: ByteArray,
        capturedAtEpochMillis: Long?,
        coordinate: GeoCoordinate?,
        placeId: String?,
        saveToSystemLibrary: Boolean
    ): Result<HikePhoto> = withContext(ioDispatcher) {
        if (shouldFailFirstSave()) {
            Result.failure(IOException("Simulated photo save failure"))
        } else {
            runPhotoCatching {
                val resolvedTime = resolveCameraCaptureTimestamp(rawBytes, capturedAtEpochMillis)
                val photoId = UUID.randomUUID().toString()
                val writeResult = photoStore.writeCameraJpeg(photoId, rawBytes)
                val writtenFile = writeResult.getOrNull()
                if (writtenFile == null) {
                    Result.failure(writeResult.exceptionOrNull() ?: IOException("Unable to store photo"))
                } else {
                    val committed = commitCameraPhotoRow(
                        hikeId = hikeId,
                        photoId = photoId,
                        capturedAtEpochMillis = resolvedTime,
                        coordinate = coordinate,
                        placeId = placeId,
                        writtenFile = writtenFile
                    )
                    committed.onSuccess { saved ->
                        if (saveToSystemLibrary) {
                            mirrorCapturedPhotoBestEffort(saved, writtenFile)
                        }
                    }
                }
            }
        }
    }

    suspend fun addPickedPhoto(
        hikeId: HikeId,
        bytes: ByteArray,
        assetLocalIdentifier: String?,
        fallbackCoordinate: GeoCoordinate?,
        placeId: String?
    ): Result<HikePhoto> = withContext(ioDispatcher) {
        if (shouldFailFirstSave()) {
            Result.failure(IOException("Simulated photo import failure"))
        } else {
            runPhotoCatching {
                val extension = ImageDataFormat.detectExtension(bytes)
                    ?: return@runPhotoCatching Result.failure(IOException("Unsupported image format"))
                val existing = findExistingByAsset(hikeId, assetLocalIdentifier, placeId)
                if (existing != null) {
                    Result.success(existing)
                } else {
                    persistPickedBytes(
                        hikeId = hikeId,
                        bytes = bytes,
                        extension = extension,
                        assetLocalIdentifier = assetLocalIdentifier,
                        fallbackCoordinate = fallbackCoordinate,
                        placeId = placeId
                    )
                }
            }
        }
    }

    suspend fun addPickedPhotoUri(
        hikeId: HikeId,
        uriString: String,
        fallbackCoordinate: GeoCoordinate?,
        placeId: String?
    ): Result<HikePhoto> {
        val payload = photoFileIo.readPickedUri(uriString)
            ?: return Result.failure(IOException("Unable to read picked photo"))
        return addPickedPhoto(
            hikeId = hikeId,
            bytes = payload.bytes,
            assetLocalIdentifier = payload.assetLocalIdentifier,
            fallbackCoordinate = fallbackCoordinate,
            placeId = placeId
        )
    }

    suspend fun addDiscoveredPhoto(
        hikeId: HikeId,
        bytes: ByteArray,
        match: LibraryPhotoMatch,
        expectedPlanFingerprint: Long
    ): Result<HikePhoto?> = withContext(ioDispatcher) {
        if (shouldFailFirstSave()) {
            Result.failure(IOException("Simulated discovered photo save failure"))
        } else {
            runPhotoCatching {
                val extension = ImageDataFormat.detectExtension(bytes)
                    ?: return@runPhotoCatching Result.failure(IOException("Unsupported image format"))
                val deduped = hikePhotoDao.findByHikeAndAssetId(hikeId.value, match.asset.localIdentifier)
                if (deduped != null) {
                    Result.success(deduped.toDomain())
                } else {
                    persistDiscoveredBytes(hikeId, bytes, extension, match, expectedPlanFingerprint)
                }
            }
        }
    }

    suspend fun removePhoto(hikeId: HikeId, photoId: String): Result<Unit> = withContext(ioDispatcher) {
        if (shouldFailFirstSave()) {
            Result.failure(IOException("Simulated photo removal failure"))
        } else {
            runPhotoCatching {
                val existing = hikePhotoDao.getById(photoId)?.takeIf { it.hikeId == hikeId.value }?.toDomain()
                if (existing != null) {
                    hikePhotoDao.deleteById(hikeId.value, photoId)
                    photoStore.deleteFiles(existing)
                }
                Result.success(Unit)
            }
        }
    }

    suspend fun exportHikeArchive(hike: Hike): Result<ExportedHikeArchive> = withContext(ioDispatcher) {
        runPhotoCatching {
            val bytesById = LinkedHashMap<String, ByteArray>()
            for (photo in hike.orderedPhotos) {
                photoStore.readOriginalBytes(photo)?.let { bytesById[photo.id] = it }
            }
            val archive = withContext(defaultDispatcher) {
                HikePhotoArchiveBuilder.build(
                    hike = hike,
                    readPhotoBytes = { photo -> bytesById[photo.id] }
                )
            }
            Result.success(archive)
        }
    }

    suspend fun prepareSharePhotoFile(hikeTitle: String, positionOneBased: Int, photo: HikePhoto): Result<File> =
        withContext(ioDispatcher) {
            runPhotoCatching {
                val sourceBytes = photoStore.readOriginalBytes(photo)
                    ?: return@runPhotoCatching Result.failure(IOException("Photo file is not on this device"))
                val shareDir = File(context.cacheDir, SHARE_CACHE_DIR).apply { mkdirs() }
                val safeTitle = sanitizeFileStem(hikeTitle)
                val target = File(shareDir, "$safeTitle-$positionOneBased.${photo.pathExtension}")
                target.writeBytes(sourceBytes)
                val credited = if (photo.license != null) {
                    PhotoMetadataCodec.stampCreditMetadata(target, photo)
                } else {
                    true
                }
                if (!credited) {
                    target.delete()
                    Result.failure(IOException("Unable to write photo attribution credit"))
                } else {
                    Result.success(target)
                }
            }
        }

    suspend fun measurePhotoStorageBytes(): Long = photoStore.totalStorageBytes()

    suspend fun reclaimOrphanedPhotos(): Result<Int> = withContext(ioDispatcher) {
        runPhotoCatching {
            val persisted = hikePhotoDao.getAll().map { it.toDomain() }
            val claimedOriginals = persisted.mapTo(HashSet()) { it.fileName }
            val claimedThumbnails = persisted.mapTo(HashSet()) { it.thumbnailFileName }
            val reclaimed = photoStore.reclaimOrphans(
                claimedFileNames = claimedOriginals,
                claimedThumbnailNames = claimedThumbnails,
                nowEpochMillis = clock.now().toEpochMilli()
            )
            Result.success(reclaimed)
        }
    }

    suspend fun seedPhotosForHike(hike: Hike, count: Int, omitFiles: Boolean) = withContext(ioDispatcher) {
        val capped = count.coerceIn(0, MAX_SEEDED_PHOTOS)
        if (capped == 0) return@withContext
        val profile = withContext(defaultDispatcher) { RouteProfile.from(hike.route) }
        val totalDistance = profile.totalDistanceMeters
        val baseTime = hike.route.firstNotNullOfOrNull { it.timestampEpochMillis } ?: hike.dateEpochMillis
        val created = ArrayList<HikePhoto>(capped)
        for (index in 0 until capped) {
            val fraction = (index + 1).toDouble() / (capped + 1).toDouble()
            val coordinate = if (totalDistance > 0.0) {
                profile.coordinateAt(totalDistance * fraction)
            } else {
                hike.route.firstOrNull()?.coordinate
            }
            val bytes = withContext(defaultDispatcher) { SeededPhotoGenerator.generateJpeg(index) }
            val added = addCameraPhoto(
                hikeId = hike.id,
                rawBytes = bytes,
                capturedAtEpochMillis = baseTime + (index + 1) * SEED_PHOTO_STEP_MILLIS,
                coordinate = coordinate,
                placeId = null,
                saveToSystemLibrary = false
            ).getOrNull()
            if (added != null) created.add(added)
        }
        if (omitFiles && created.isNotEmpty()) {
            photoStore.deleteFiles(created)
        }
    }

    private fun resolveCameraCaptureTimestamp(rawBytes: ByteArray, explicitMillis: Long?): Long = explicitMillis
        ?: PhotoMetadataCodec.readMetadata(rawBytes).capturedAtEpochMillis
        ?: clock.now().toEpochMilli()

    private suspend fun commitCameraPhotoRow(
        hikeId: HikeId,
        photoId: String,
        capturedAtEpochMillis: Long,
        coordinate: GeoCoordinate?,
        placeId: String?,
        writtenFile: File
    ): Result<HikePhoto> {
        val hikeExists = hikeDao.getHikeById(hikeId.value) != null
        if (!hikeExists) {
            writtenFile.delete()
            return Result.failure(NoSuchElementException("Hike not found: ${hikeId.value}"))
        }
        val verifiedPlaceId = resolveExistingPlaceId(hikeId, placeId)
        val photo = HikePhoto(
            id = photoId,
            hikeId = hikeId,
            capturedAtEpochMillis = capturedAtEpochMillis,
            pathExtension = HikePhoto.DEFAULT_PATH_EXTENSION,
            coordinate = coordinate,
            placeId = verifiedPlaceId
        )
        return try {
            hikePhotoDao.insert(photo.toEntity())
            Result.success(photo)
        } catch (error: SQLException) {
            photoStore.deleteFiles(photo)
            Result.failure(error)
        }
    }

    private suspend fun persistPickedBytes(
        hikeId: HikeId,
        bytes: ByteArray,
        extension: String,
        assetLocalIdentifier: String?,
        fallbackCoordinate: GeoCoordinate?,
        placeId: String?
    ): Result<HikePhoto> {
        val initialPlan = if (placeId == null) buildSearchPlan(hikeId) else null
        val metadata = withContext(defaultDispatcher) { PhotoMetadataCodec.readMetadata(bytes) }
        val nowMillis = clock.now().toEpochMilli()
        val photoId = UUID.randomUUID().toString()
        photoStore.writeOriginal(photoId, bytes, extension).getOrElse {
            return Result.failure(it)
        }
        val activePlan = if (placeId == null) {
            val latest = buildSearchPlan(hikeId)
            if (latest != null && initialPlan != null && latest.fingerprint != initialPlan.fingerprint) {
                latest
            } else {
                initialPlan
            }
        } else {
            null
        }
        val resolution = withContext(defaultDispatcher) {
            PickedPhotoPlacement.resolve(
                metadata = metadata,
                plan = activePlan,
                fallbackCoordinate = fallbackCoordinate,
                nowEpochMillis = nowMillis
            )
        }
        return commitPickedPhotoRow(
            hikeId = hikeId,
            photoId = photoId,
            extension = extension,
            capturedAtEpochMillis = resolution.capturedAtEpochMillis,
            coordinate = resolution.coordinate,
            matchEvidence = resolution.matchEvidence,
            assetLocalIdentifier = assetLocalIdentifier,
            placeId = placeId
        )
    }

    private suspend fun commitPickedPhotoRow(
        hikeId: HikeId,
        photoId: String,
        extension: String,
        capturedAtEpochMillis: Long,
        coordinate: GeoCoordinate?,
        matchEvidence: PhotoMatchEvidence?,
        assetLocalIdentifier: String?,
        placeId: String?
    ): Result<HikePhoto> {
        val candidate = HikePhoto(
            id = photoId,
            hikeId = hikeId,
            capturedAtEpochMillis = capturedAtEpochMillis,
            pathExtension = extension,
            coordinate = coordinate,
            assetLocalIdentifier = assetLocalIdentifier,
            matchEvidence = matchEvidence,
            placeId = resolveExistingPlaceId(hikeId, placeId)
        )
        val hikeExists = hikeDao.getHikeById(hikeId.value) != null
        val raceDuplicate = if (hikeExists) findExistingByAsset(hikeId, assetLocalIdentifier, placeId) else null
        return when {
            !hikeExists -> {
                photoStore.deleteFiles(candidate)
                Result.failure(NoSuchElementException("Hike not found: ${hikeId.value}"))
            }
            raceDuplicate != null -> {
                photoStore.deleteFiles(candidate)
                Result.success(raceDuplicate)
            }
            else -> try {
                hikePhotoDao.insert(candidate.toEntity())
                Result.success(candidate)
            } catch (error: SQLException) {
                photoStore.deleteFiles(candidate)
                Result.failure(error)
            }
        }
    }

    private suspend fun persistDiscoveredBytes(
        hikeId: HikeId,
        bytes: ByteArray,
        extension: String,
        match: LibraryPhotoMatch,
        expectedPlanFingerprint: Long
    ): Result<HikePhoto?> {
        val photoId = UUID.randomUUID().toString()
        photoStore.writeOriginal(photoId, bytes, extension).getOrElse {
            return Result.failure(it)
        }
        val candidate = HikePhoto(
            id = photoId,
            hikeId = hikeId,
            capturedAtEpochMillis = match.asset.createdAtEpochMillis,
            pathExtension = extension,
            coordinate = match.coordinate,
            assetLocalIdentifier = match.asset.localIdentifier,
            matchEvidence = match.evidence
        )
        val currentPlan = buildSearchPlan(hikeId)
        val pinStillHolds = currentPlan != null && currentPlan.fingerprint == expectedPlanFingerprint
        val existingRace = if (pinStillHolds) {
            hikePhotoDao.findByHikeAndAssetId(hikeId.value, match.asset.localIdentifier)?.toDomain()
        } else {
            null
        }
        return when {
            !pinStillHolds -> {
                photoStore.deleteFiles(candidate)
                Result.success(null)
            }
            existingRace != null -> {
                photoStore.deleteFiles(candidate)
                Result.success(existingRace)
            }
            else -> try {
                hikePhotoDao.insert(candidate.toEntity())
                Result.success(candidate)
            } catch (error: SQLException) {
                photoStore.deleteFiles(candidate)
                Result.failure(error)
            }
        }
    }

    private suspend fun mirrorCapturedPhotoBestEffort(photo: HikePhoto, writtenFile: File) {
        val bytes = runCatching { writtenFile.readBytes() }.getOrNull() ?: return
        val mirroredAssetId = photoLibraryMirror.saveCameraPhoto(
            jpegBytes = bytes,
            capturedAtEpochMillis = photo.capturedAtEpochMillis,
            coordinate = photo.coordinate
        ).getOrNull() ?: return
        val concurrentDuplicate = hikePhotoDao.findByHikeAndAssetId(photo.hikeId.value, mirroredAssetId)
        if (concurrentDuplicate != null && concurrentDuplicate.id != photo.id) {
            val mergedPlaceId = photo.placeId ?: concurrentDuplicate.placeId
            hikePhotoDao.updatePlaceId(photo.hikeId.value, photo.id, mergedPlaceId)
            hikePhotoDao.deleteById(photo.hikeId.value, concurrentDuplicate.id)
            hikePhotoDao.linkAssetIdentifier(photo.hikeId.value, photo.id, mirroredAssetId)
            photoStore.deleteFiles(concurrentDuplicate.toDomain())
        } else {
            hikePhotoDao.linkAssetIdentifier(photo.hikeId.value, photo.id, mirroredAssetId)
        }
    }

    private suspend fun findExistingByAsset(
        hikeId: HikeId,
        assetLocalIdentifier: String?,
        requestedPlaceId: String?
    ): HikePhoto? {
        val assetId = assetLocalIdentifier?.takeIf { it.isNotBlank() }
        val existing = assetId?.let { hikePhotoDao.findByHikeAndAssetId(hikeId.value, it) }
        return if (existing == null) {
            null
        } else {
            val targetPlaceId = resolveExistingPlaceId(hikeId, requestedPlaceId)
            if (targetPlaceId != null && existing.placeId != targetPlaceId) {
                hikePhotoDao.updatePlaceId(hikeId.value, existing.id, targetPlaceId)
                existing.copy(placeId = targetPlaceId).toDomain()
            } else {
                existing.toDomain()
            }
        }
    }

    private suspend fun resolveExistingPlaceId(hikeId: HikeId, placeId: String?): String? {
        val id = placeId?.takeIf { it.isNotBlank() } ?: return null
        val entity = trailPointDao.getById(id)
        return if (entity != null && entity.hikeId == hikeId.value) id else null
    }

    private suspend fun buildSearchPlan(hikeId: HikeId): HikePhotoSearchPlan? {
        hikeDao.getHikeById(hikeId.value) ?: return null
        val chunks = hikeDao.getRouteChunks(hikeId.value)
        val route = withContext(defaultDispatcher) {
            RouteBlobCodec.decodeChunksOrEmpty(chunks.sortedBy { it.chunkIndex }.map { it.chunkBlob })
        }
        val walks = hikeWalkDao.getByHikeId(hikeId.value).map { it.toDomain() }
        return withContext(defaultDispatcher) {
            HikePhotoSearchPlan.build(route = route, walks = walks, profileRoute = true)
        }
    }

    private fun sanitizeFileStem(title: String): String {
        val gpxName = GpxExporter.fileName(
            Hike(
                id = HikeId("share"),
                title = title.ifBlank { "Hike" },
                distanceMeters = 0.0,
                dateEpochMillis = 0L
            )
        )
        return gpxName.substringBeforeLast("-1970-01-01.gpx").ifBlank { "Hike" }
    }

    private suspend fun <T> runPhotoCatching(block: suspend () -> Result<T>): Result<T> = try {
        block()
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
        const val SHARE_CACHE_DIR = "shared_photos"
        const val MAX_SEEDED_PHOTOS = 24
        const val SEED_PHOTO_STEP_MILLIS = 60_000L
    }
}
