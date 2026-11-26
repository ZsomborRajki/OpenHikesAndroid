package app.openhikes.core.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import app.openhikes.core.common.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Payload read from a picked image URI (`PickMultipleVisualMedia`) or temporary camera capture URI.
 */
data class PickedImagePayload(val bytes: ByteArray, val assetLocalIdentifier: String?) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PickedImagePayload) return false
        return assetLocalIdentifier == other.assetLocalIdentifier && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + (assetLocalIdentifier?.hashCode() ?: 0)
        return result
    }
}

/**
 * Injectable seam reading image bytes from content URIs and writing binary ZIP archives.
 */
interface PhotoFileIo {
    suspend fun readPickedUri(uriString: String): PickedImagePayload?

    suspend fun writeBytesToUri(uriString: String, bytes: ByteArray): Result<Unit>
}

@Singleton
internal class AndroidPhotoFileIo @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : PhotoFileIo {
    override suspend fun readPickedUri(uriString: String): PickedImagePayload? = withContext(ioDispatcher) {
        val uri = runCatching { uriString.toUri() }.getOrNull() ?: return@withContext null
        val bytes = readBoundedBytes(uri, MAX_IMAGE_BYTES) ?: return@withContext null
        PickedImagePayload(
            bytes = bytes,
            assetLocalIdentifier = extractStableAssetId(uri)
        )
    }

    override suspend fun writeBytesToUri(uriString: String, bytes: ByteArray): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                val uri = uriString.toUri()
                val stream = context.contentResolver.openOutputStream(uri, "w")
                    ?: context.contentResolver.openOutputStream(uri)
                    ?: throw IOException("Unable to open output stream for $uriString")
                stream.use { output ->
                    output.write(bytes)
                    output.flush()
                }
            }
        }

    private fun readBoundedBytes(uri: Uri, maxBytes: Int): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            var total = 0
            while (total < maxBytes) {
                val read = stream.read(buffer, 0, minOf(buffer.size, maxBytes - total))
                if (read <= 0) break
                output.write(buffer, 0, read)
                total += read
            }
            output.toByteArray()
        }
    }.getOrNull()

    private fun extractStableAssetId(uri: Uri): String? {
        val fromMediaId = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Images.Media._ID),
                null,
                null,
                null
            )?.use { cursor ->
                val col = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                if (col >= 0 && cursor.moveToFirst() && !cursor.isNull(col)) {
                    cursor.getLong(col).toString()
                } else {
                    null
                }
            }
        }.getOrNull()
        val fromDoc = if (fromMediaId == null && DocumentsContract.isDocumentUri(context, uri)) {
            runCatching { DocumentsContract.getDocumentId(uri).substringAfterLast(':') }.getOrNull()
        } else {
            null
        }
        return fromMediaId ?: fromDoc ?: uri.lastPathSegment?.takeIf { it.all(Char::isDigit) }
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 64 * 1024 * 1024
        const val STREAM_BUFFER_SIZE = 8192
    }
}
