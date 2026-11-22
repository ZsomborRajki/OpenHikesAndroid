package app.openhikes.core.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.model.GpxImportLimits
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Raw GPX file bytes and display filename resolved from a document provider URI.
 */
data class GpxFilePayload(val fileName: String?, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GpxFilePayload) return false
        return fileName == other.fileName && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = fileName?.hashCode() ?: 0
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

interface GpxFileSource {
    suspend fun read(uriString: String): GpxFilePayload?
}

interface GpxFileSink {
    suspend fun write(uriString: String, xml: String): Result<Unit>
}

interface BundledGpxFixtureSource {
    suspend fun loadFixture(fixtureName: String): Pair<String, ByteArray>?
}

internal class AndroidGpxFileSource @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : GpxFileSource {
    override suspend fun read(uriString: String): GpxFilePayload? = withContext(ioDispatcher) {
        val uri = runCatching { uriString.toUri() }.getOrNull() ?: return@withContext null
        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment
        val bytes = readBoundedBytes(uri, GpxImportLimits.STANDARD_MAX_FILE_SIZE_BYTES + 1)
            ?: return@withContext null
        GpxFilePayload(fileName = displayName, bytes = bytes)
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        }
    }.getOrNull()

    private fun readBoundedBytes(uri: Uri, maxBytes: Int): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            var total = 0
            while (total < maxBytes) {
                val toRead = minOf(buffer.size, maxBytes - total)
                val read = stream.read(buffer, 0, toRead)
                if (read <= 0) break
                output.write(buffer, 0, read)
                total += read
            }
            output.toByteArray()
        }
    }.getOrNull()

    private companion object {
        const val STREAM_BUFFER_SIZE = 8192
    }
}

internal class AndroidGpxFileSink @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : GpxFileSink {
    override suspend fun write(uriString: String, xml: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val uri = uriString.toUri()
            val stream = context.contentResolver.openOutputStream(uri, "wt")
                ?: context.contentResolver.openOutputStream(uri)
                ?: throw IOException("Unable to open output stream for $uriString")
            stream.use { output ->
                output.write(xml.toByteArray(Charsets.UTF_8))
                output.flush()
            }
        }
    }
}

internal class AssetBundledGpxFixtureSource @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : BundledGpxFixtureSource {
    override suspend fun loadFixture(fixtureName: String): Pair<String, ByteArray>? = withContext(ioDispatcher) {
        val sanitized = fixtureName.trim()
        val hasUnsafePath = sanitized.contains("..") || sanitized.any { it == '/' || it == '\\' }
        if (sanitized.isEmpty() || hasUnsafePath) {
            return@withContext null
        }
        val fileName = "$sanitized.gpx"
        runCatching {
            context.assets.open("fixtures/$fileName").use { stream ->
                fileName to stream.readBytes()
            }
        }.getOrNull()
    }
}
