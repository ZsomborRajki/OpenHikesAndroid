package app.openhikes.core.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import app.openhikes.core.common.DefaultDispatcher
import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.PhotoLibraryAsset
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Current authorization posture for reading the device's system photo library during Photo Discovery.
 */
enum class PhotoLibraryAccess {
    GRANTED,
    LIMITED,
    DENIED,
    RESTRICTED
}

/**
 * Injectable seam reading device library photos by date range for *Find Photos of This Hike*.
 */
interface PhotoLibrarySource {
    suspend fun currentAccess(): PhotoLibraryAccess

    suspend fun queryAssets(window: LongRange): List<PhotoLibraryAsset>

    suspend fun readImageBytes(localIdentifier: String): ByteArray?

    suspend fun readThumbnailBytes(localIdentifier: String, maxDimensionPx: Int = DEFAULT_THUMBNAIL_PX): ByteArray?

    companion object {
        const val DEFAULT_THUMBNAIL_PX = 400
    }
}

/**
 * Deterministic generator of minimal valid JPEG images for UI tests and stubbed photo libraries.
 */
object SeededPhotoGenerator {
    private const val WIDTH_PX = 320
    private const val HEIGHT_PX = 240
    private const val JPEG_QUALITY = 85
    private const val RED_BASE = 54
    private const val RED_STEP = 37
    private const val GREEN_BASE = 112
    private const val GREEN_STEP = 29
    private const val BLUE_BASE = 84
    private const val BLUE_STEP = 43
    private const val COLOR_MOD = 180
    private const val ACCENT_RADIUS = 56f

    fun generateJpeg(seed: Int): ByteArray {
        val bitmap = createBitmap(WIDTH_PX, HEIGHT_PX)
        val canvas = Canvas(bitmap)
        val red = (RED_BASE + seed * RED_STEP) % COLOR_MOD
        val green = (GREEN_BASE + seed * GREEN_STEP) % COLOR_MOD
        val blue = (BLUE_BASE + seed * BLUE_STEP) % COLOR_MOD
        canvas.drawColor(Color.rgb(red, green, blue))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(
                (red + COLOR_MOD / 2).coerceAtMost(255),
                (green + COLOR_MOD / 2).coerceAtMost(255),
                (blue + COLOR_MOD / 2).coerceAtMost(255)
            )
        }
        canvas.drawCircle(WIDTH_PX / 2f, HEIGHT_PX / 2f, ACCENT_RADIUS, paint)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}

/**
 * Production `MediaStore` reader for Photo Discovery with automatic launch-config stubbing for
 * deterministic UI tests (`LaunchConfig.stubPhotoLibraryCount`).
 */
@Singleton
internal class AndroidPhotoLibrarySource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val launchConfigHolder: LaunchConfigHolder,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : PhotoLibrarySource {
    private val stubBytesCache = ConcurrentHashMap<String, ByteArray>()

    override suspend fun currentAccess(): PhotoLibraryAccess {
        val config = launchConfigHolder.current.value
        val stubAccess = config.stubPhotoLibraryAccess
        return when {
            stubAccess != null -> parseStubAccess(stubAccess)
            config.stubPhotoLibraryCount != null -> PhotoLibraryAccess.GRANTED
            else -> querySystemPermissionAccess()
        }
    }

    override suspend fun queryAssets(window: LongRange): List<PhotoLibraryAsset> {
        val stubCount = launchConfigHolder.current.value.stubPhotoLibraryCount
        return if (stubCount != null) {
            buildStubAssets(stubCount.coerceIn(0, MAX_STUB_ASSETS), window)
        } else {
            withContext(ioDispatcher) { queryMediaStoreAssets(window) }
        }
    }

    override suspend fun readImageBytes(localIdentifier: String): ByteArray? {
        if (localIdentifier.startsWith(STUB_ID_PREFIX)) {
            val seed = localIdentifier.removePrefix(STUB_ID_PREFIX).substringBefore(':').toIntOrNull() ?: 0
            return stubBytesCache.getOrPut(localIdentifier) {
                withContext(defaultDispatcher) { SeededPhotoGenerator.generateJpeg(seed) }
            }
        }
        return withContext(ioDispatcher) {
            val id = localIdentifier.toLongOrNull() ?: return@withContext null
            val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            val resolvedUri = if (hasMediaLocationPermission()) {
                runCatching { MediaStore.setRequireOriginal(contentUri) }.getOrDefault(contentUri)
            } else {
                contentUri
            }
            runCatching {
                context.contentResolver.openInputStream(resolvedUri)?.use { it.readBytes() }
            }.getOrNull()
        }
    }

    override suspend fun readThumbnailBytes(localIdentifier: String, maxDimensionPx: Int): ByteArray? =
        readImageBytes(localIdentifier)

    private fun queryMediaStoreAssets(window: LongRange): List<PhotoLibraryAsset> {
        if (querySystemPermissionAccess() == PhotoLibraryAccess.DENIED) return emptyList()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED
        )
        val startSeconds = window.first / MILLIS_PER_SECOND
        val endSeconds = window.last / MILLIS_PER_SECOND
        val selection =
            "(${MediaStore.Images.Media.DATE_TAKEN} BETWEEN ? AND ?) OR " +
                "(${MediaStore.Images.Media.DATE_TAKEN} IS NULL AND " +
                "${MediaStore.Images.Media.DATE_ADDED} BETWEEN ? AND ?)"
        val selectionArgs = arrayOf(
            window.first.toString(),
            window.last.toString(),
            startSeconds.toString(),
            endSeconds.toString()
        )
        val results = ArrayList<PhotoLibraryAsset>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Images.Media.DATE_TAKEN} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val taken = if (cursor.isNull(takenCol)) 0L else cursor.getLong(takenCol)
                    val createdMillis = if (taken > 0L) taken else cursor.getLong(addedCol) * MILLIS_PER_SECOND
                    if (createdMillis in window) {
                        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        val coordinate = readCoordinateFromUri(uri)
                        results.add(
                            PhotoLibraryAsset(
                                localIdentifier = id.toString(),
                                createdAtEpochMillis = createdMillis,
                                coordinate = coordinate
                            )
                        )
                    }
                }
            }
        }
        return results
    }

    private fun readCoordinateFromUri(uri: Uri): GeoCoordinate? {
        val targetUri = if (hasMediaLocationPermission()) {
            runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
        } else {
            uri
        }
        return runCatching {
            context.contentResolver.openInputStream(targetUri)?.use { stream ->
                val bytes = stream.readBytes()
                PhotoMetadataCodec.readMetadata(bytes).coordinate
            }
        }.getOrNull()
    }

    private fun buildStubAssets(count: Int, window: LongRange): List<PhotoLibraryAsset> {
        if (count <= 0) return emptyList()
        val span = (window.last - window.first).coerceAtLeast(0L)
        return List(count) { index ->
            val fraction = (index + 1).toDouble() / (count + 1).toDouble()
            val timestamp = window.first + (span * fraction).toLong()
            PhotoLibraryAsset(
                localIdentifier = "$STUB_ID_PREFIX$index",
                createdAtEpochMillis = timestamp,
                coordinate = null
            )
        }
    }

    private fun querySystemPermissionAccess(): PhotoLibraryAccess {
        val hasFull = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_IMAGES
        ) == PackageManager.PERMISSION_GRANTED
        val hasSelected = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        ) == PackageManager.PERMISSION_GRANTED
        return when {
            hasFull -> PhotoLibraryAccess.GRANTED
            hasSelected -> PhotoLibraryAccess.LIMITED
            else -> PhotoLibraryAccess.DENIED
        }
    }

    private fun hasMediaLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_MEDIA_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun parseStubAccess(raw: String): PhotoLibraryAccess = when (raw.lowercase()) {
        "limited" -> PhotoLibraryAccess.LIMITED
        "denied" -> PhotoLibraryAccess.DENIED
        "restricted" -> PhotoLibraryAccess.RESTRICTED
        else -> PhotoLibraryAccess.GRANTED
    }

    private companion object {
        const val STUB_ID_PREFIX = "stub-photo-"
        const val MAX_STUB_ASSETS = 24
        const val MILLIS_PER_SECOND = 1000L
    }
}
