package app.openhikes.core.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.model.GeoCoordinate
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Opt-in writer mirroring newly captured OpenHikes camera photos to the device's system Photos
 * gallery (`Pictures/OpenHikes`) with stamped EXIF timestamp and trail GPS coordinates.
 */
interface PhotoLibraryMirror {
    suspend fun saveCameraPhoto(
        jpegBytes: ByteArray,
        capturedAtEpochMillis: Long,
        coordinate: GeoCoordinate?
    ): Result<String>
}

@Singleton
internal class MediaStorePhotoLibraryMirror @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : PhotoLibraryMirror {
    override suspend fun saveCameraPhoto(
        jpegBytes: ByteArray,
        capturedAtEpochMillis: Long,
        coordinate: GeoCoordinate?
    ): Result<String> = withContext(ioDispatcher) {
        val tempFile = File(context.cacheDir, "mirror-${UUID.randomUUID()}.jpeg")
        try {
            tempFile.writeBytes(jpegBytes)
            PhotoMetadataCodec.stampCaptureMetadata(
                file = tempFile,
                capturedAtEpochMillis = capturedAtEpochMillis,
                coordinate = coordinate
            )
            val stampedBytes = tempFile.readBytes()
            writeToMediaStore(stampedBytes, capturedAtEpochMillis)
        } catch (error: IOException) {
            Result.failure(error)
        } catch (error: SecurityException) {
            Result.failure(error)
        } finally {
            tempFile.delete()
        }
    }

    private fun writeToMediaStore(bytes: ByteArray, capturedAtEpochMillis: Long): Result<String> {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "OpenHikes-$capturedAtEpochMillis.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/OpenHikes")
            put(MediaStore.Images.Media.DATE_TAKEN, capturedAtEpochMillis)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val insertedUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return Result.failure(IOException("Unable to create MediaStore entry"))
        return runCatching {
            val stream = resolver.openOutputStream(insertedUri)
                ?: throw IOException("Unable to open MediaStore stream")
            stream.use { output ->
                output.write(bytes)
                output.flush()
            }
            val publishValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(insertedUri, publishValues, null, null)
            insertedUri.lastPathSegment ?: insertedUri.toString()
        }.onFailure {
            runCatching { resolver.delete(insertedUri, null, null) }
        }
    }
}
