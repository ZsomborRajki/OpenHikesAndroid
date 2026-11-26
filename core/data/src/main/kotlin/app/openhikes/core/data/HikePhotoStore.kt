package app.openhikes.core.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.LruCache
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import app.openhikes.core.common.DefaultDispatcher
import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.model.HikePhoto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Local file storage for hike photo originals and pre-rendered JPEG thumbnails, kept inside
 * `noBackupFilesDir` (never cloud-backed-up) and strictly accessed off the main thread.
 */
interface HikePhotoStore {
    suspend fun writeOriginal(photoId: String, bytes: ByteArray, pathExtension: String): Result<File>

    suspend fun writeCameraJpeg(photoId: String, rawBytes: ByteArray): Result<File>

    suspend fun readOriginalBytes(photo: HikePhoto): ByteArray?

    suspend fun readOrBuildThumbnailBytes(photo: HikePhoto): ByteArray?

    suspend fun hasOriginalFile(photo: HikePhoto): Boolean

    suspend fun originalFile(photo: HikePhoto): File?

    suspend fun deleteFiles(photo: HikePhoto)

    suspend fun deleteFiles(photos: List<HikePhoto>)

    suspend fun totalStorageBytes(): Long

    suspend fun reclaimOrphans(
        claimedFileNames: Set<String>,
        claimedThumbnailNames: Set<String>,
        nowEpochMillis: Long,
        gracePeriodMillis: Long = ORPHAN_GRACE_PERIOD_MILLIS
    ): Int

    fun isRemoved(photoId: String): Boolean

    companion object {
        const val DIRECTORY_NAME = "hike_photos"
        const val THUMBNAILS_DIR_NAME = "thumbnails"
        const val THUMBNAIL_MAX_EDGE_PX = 512
        const val DISPLAY_MAX_EDGE_PX = 2688
        const val THUMBNAIL_JPEG_QUALITY = 80
        const val CAMERA_JPEG_QUALITY = 90
        const val ORPHAN_GRACE_PERIOD_MILLIS = 300_000L
        const val THUMBNAIL_CACHE_BYTES = 32 * 1024 * 1024
    }
}

/**
 * Atomic disk implementation of [HikePhotoStore].
 */
internal class DiskHikePhotoStore(
    private val rootDir: File,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : HikePhotoStore {
    private val thumbnailsDir: File = File(rootDir, HikePhotoStore.THUMBNAILS_DIR_NAME)
    private val removedIds: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val thumbnailCache = object : LruCache<String, ByteArray>(HikePhotoStore.THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size.coerceAtLeast(1)
    }

    override suspend fun writeOriginal(photoId: String, bytes: ByteArray, pathExtension: String): Result<File> =
        withContext(ioDispatcher) {
            runCatching {
                ensureDirectories()
                val target = File(rootDir, "$photoId.$pathExtension")
                writeAtomically(target, bytes)
                removedIds.remove(photoId)
                target
            }
        }

    override suspend fun writeCameraJpeg(photoId: String, rawBytes: ByteArray): Result<File> {
        val uprightBytes = withContext(defaultDispatcher) {
            PhotoBitmapEncoder.bakeOrientationToJpeg(rawBytes, HikePhotoStore.CAMERA_JPEG_QUALITY)
        }
        return if (uprightBytes == null) {
            Result.failure(IOException("Unable to decode captured image"))
        } else {
            writeOriginal(photoId, uprightBytes, HikePhoto.DEFAULT_PATH_EXTENSION)
        }
    }

    override suspend fun readOriginalBytes(photo: HikePhoto): ByteArray? = withContext(ioDispatcher) {
        if (photo.recordsPlaceOnly) {
            null
        } else {
            val file = File(rootDir, photo.fileName)
            if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
        }
    }

    override suspend fun readOrBuildThumbnailBytes(photo: HikePhoto): ByteArray? {
        if (photo.recordsPlaceOnly) return null
        return thumbnailCache.get(photo.id)
            ?: readCachedDiskThumbnail(photo)
            ?: buildAndCacheThumbnail(photo)
    }

    override suspend fun hasOriginalFile(photo: HikePhoto): Boolean = withContext(ioDispatcher) {
        !photo.recordsPlaceOnly && File(rootDir, photo.fileName).isFile
    }

    override suspend fun originalFile(photo: HikePhoto): File? = withContext(ioDispatcher) {
        if (photo.recordsPlaceOnly) {
            null
        } else {
            File(rootDir, photo.fileName).takeIf { it.isFile }
        }
    }

    override suspend fun deleteFiles(photo: HikePhoto) {
        removedIds.add(photo.id)
        thumbnailCache.remove(photo.id)
        withContext(ioDispatcher) {
            runCatching { File(rootDir, photo.fileName).delete() }
            runCatching { File(thumbnailsDir, photo.thumbnailFileName).delete() }
        }
    }

    override suspend fun deleteFiles(photos: List<HikePhoto>) {
        if (photos.isEmpty()) return
        for (photo in photos) {
            removedIds.add(photo.id)
            thumbnailCache.remove(photo.id)
        }
        withContext(ioDispatcher) {
            for (photo in photos) {
                runCatching { File(rootDir, photo.fileName).delete() }
                runCatching { File(thumbnailsDir, photo.thumbnailFileName).delete() }
            }
        }
    }

    override suspend fun totalStorageBytes(): Long = withContext(ioDispatcher) {
        PhotoDiskHelper.directoryBytes(rootDir) + PhotoDiskHelper.directoryBytes(thumbnailsDir)
    }

    override suspend fun reclaimOrphans(
        claimedFileNames: Set<String>,
        claimedThumbnailNames: Set<String>,
        nowEpochMillis: Long,
        gracePeriodMillis: Long
    ): Int = withContext(ioDispatcher) {
        val cutoffMillis = nowEpochMillis - gracePeriodMillis
        PhotoDiskHelper.sweepDirectory(rootDir, claimedFileNames, cutoffMillis) +
            PhotoDiskHelper.sweepDirectory(thumbnailsDir, claimedThumbnailNames, cutoffMillis)
    }

    override fun isRemoved(photoId: String): Boolean = removedIds.contains(photoId)

    private suspend fun readCachedDiskThumbnail(photo: HikePhoto): ByteArray? = withContext(ioDispatcher) {
        val file = File(thumbnailsDir, photo.thumbnailFileName)
        val bytes = if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
        if (bytes != null) {
            thumbnailCache.put(photo.id, bytes)
        }
        bytes
    }

    private suspend fun buildAndCacheThumbnail(photo: HikePhoto): ByteArray? {
        val originalBytes = readOriginalBytes(photo) ?: return null
        val rendered = withContext(defaultDispatcher) {
            PhotoBitmapEncoder.renderThumbnailJpeg(originalBytes, HikePhotoStore.THUMBNAIL_MAX_EDGE_PX)
        }
        if (rendered != null && !removedIds.contains(photo.id)) {
            thumbnailCache.put(photo.id, rendered)
            withContext(ioDispatcher) {
                runCatching {
                    ensureDirectories()
                    writeAtomically(File(thumbnailsDir, photo.thumbnailFileName), rendered)
                }
            }
        }
        return rendered
    }

    private fun ensureDirectories() {
        if (!rootDir.exists()) rootDir.mkdirs()
        if (!thumbnailsDir.exists()) thumbnailsDir.mkdirs()
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, "${target.name}.${UUID.randomUUID()}.tmp")
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                target.writeBytes(bytes)
                temp.delete()
            }
        } catch (error: IOException) {
            temp.delete()
            throw error
        }
    }
}

private object PhotoDiskHelper {
    fun directoryBytes(directory: File): Long {
        val files = directory.listFiles() ?: return 0L
        var total = 0L
        for (entry in files) {
            if (entry.isFile && !entry.name.endsWith(".tmp")) {
                total += entry.length().coerceAtLeast(0L)
            }
        }
        return total
    }

    fun sweepDirectory(directory: File, claimed: Set<String>, cutoffMillis: Long): Int {
        val files = directory.listFiles() ?: return 0
        var removed = 0
        for (entry in files) {
            val eligible = entry.isFile &&
                !claimed.contains(entry.name) &&
                entry.lastModified() <= cutoffMillis
            if (eligible && entry.delete()) {
                removed += 1
            }
        }
        return removed
    }
}

private object PhotoBitmapEncoder {
    private const val ROTATE_90 = 90f
    private const val ROTATE_180 = 180f
    private const val ROTATE_270 = 270f

    fun bakeOrientationToJpeg(rawBytes: ByteArray, quality: Int): ByteArray? {
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
        val orientation = readOrientation(rawBytes)
        val upright = applyExifOrientation(decoded, orientation)
        val output = ByteArrayOutputStream()
        val encoded = upright.compress(Bitmap.CompressFormat.JPEG, quality, output)
        if (upright !== decoded) upright.recycle()
        decoded.recycle()
        return if (encoded) output.toByteArray() else null
    }

    fun renderThumbnailJpeg(rawBytes: ByteArray, maxEdgePx: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        val sampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options) ?: return null
        val oriented = applyExifOrientation(decoded, readOrientation(rawBytes))
        val longest = max(oriented.width, oriented.height)
        val scaled = if (longest > maxEdgePx && longest > 0) {
            val ratio = maxEdgePx.toFloat() / longest.toFloat()
            val width = (oriented.width * ratio).roundToInt().coerceAtLeast(1)
            val height = (oriented.height * ratio).roundToInt().coerceAtLeast(1)
            oriented.scale(width, height)
        } else {
            oriented
        }
        val output = ByteArrayOutputStream()
        val encoded = scaled.compress(Bitmap.CompressFormat.JPEG, HikePhotoStore.THUMBNAIL_JPEG_QUALITY, output)
        if (scaled !== oriented) scaled.recycle()
        if (oriented !== decoded) oriented.recycle()
        decoded.recycle()
        return if (encoded) output.toByteArray() else null
    }

    private fun readOrientation(rawBytes: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(rawBytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun computeSampleSize(width: Int, height: Int, maxEdgePx: Int): Int {
        var sample = 1
        var longest = max(width, height)
        while (longest / 2 >= maxEdgePx) {
            sample *= 2
            longest /= 2
        }
        return sample
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(ROTATE_90)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(ROTATE_180)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(ROTATE_270)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(ROTATE_90)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(ROTATE_270)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
