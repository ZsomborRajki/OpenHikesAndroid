package app.openhikes.core.network

import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.common.offline.OfflineCountryManifestParser
import app.openhikes.core.model.OfflineCountryFile
import app.openhikes.core.model.OfflineCountryManifest
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Outcome of streaming a single country archive transfer with optional byte-slice budgeting
 * and cooperative policy/cancellation interruption.
 */
enum class OfflineFileTransferStatus {
    COMPLETE,
    PARTIAL_SLICE,
    INTERRUPTED
}

/**
 * Network seam for fetching `countries/manifest.json` and downloading country `.pmtiles` archives
 * with HTTP `Range` resume support.
 */
interface OfflineCountryServer {
    suspend fun fetchManifest(): OfflineCountryManifest

    fun fileUrl(file: OfflineCountryFile): String

    /**
     * Streams [file] into [destinationFile], resuming from [partialFile] when present, honoring
     * optional [maxBytesToTransfer] slice bounds and [shouldContinue] cooperative stop checks, and
     * reporting cumulative bytes written via [onProgress].
     */
    suspend fun downloadFile(
        file: OfflineCountryFile,
        partialFile: File,
        destinationFile: File,
        maxBytesToTransfer: Long? = null,
        shouldContinue: () -> Boolean = { true },
        onProgress: (Long) -> Unit
    ): OfflineFileTransferStatus
}

@Singleton
internal class HttpOfflineCountryServer @Inject constructor(
    @ManifestOkHttpClient private val manifestClient: OkHttpClient,
    @DownloadOkHttpClient private val downloadClient: OkHttpClient,
    @TileServerBaseUrl private val serverBaseUrl: String,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : OfflineCountryServer {
    override suspend fun fetchManifest(): OfflineCountryManifest = withContext(ioDispatcher) {
        val url = "${serverBaseUrl.trimEnd('/')}/$COUNTRIES_MANIFEST_PATH"
        val request = Request.Builder().url(url).get().build()
        manifestClient.newCall(request).execute().use { response ->
            if (response.code != HttpURLConnection.HTTP_OK) {
                throw IOException("Unexpected country manifest status ${response.code}")
            }
            val raw = response.body.string()
            try {
                OfflineCountryManifestParser.parseCatalog(raw)
            } catch (ex: IllegalArgumentException) {
                throw IOException("Malformed offline country catalog", ex)
            }
        }
    }

    override fun fileUrl(file: OfflineCountryFile): String {
        require(file.hasSafeName) { "Refusing unsafe offline country file name: ${file.path}" }
        return "${serverBaseUrl.trimEnd('/')}/countries/${file.fileName}"
    }

    override suspend fun downloadFile(
        file: OfflineCountryFile,
        partialFile: File,
        destinationFile: File,
        maxBytesToTransfer: Long?,
        shouldContinue: () -> Boolean,
        onProgress: (Long) -> Unit
    ): OfflineFileTransferStatus = withContext(ioDispatcher) {
        val url = fileUrl(file)
        partialFile.parentFile?.mkdirs()
        val existingBytes = if (partialFile.isFile) partialFile.length() else 0L
        val resumeOffset = if (existingBytes in 1L until file.byteCount) existingBytes else 0L
        val remainingBytes = (file.byteCount - resumeOffset).coerceAtLeast(0L)
        val sliceBytes = maxBytesToTransfer?.takeIf { it > 0L && it < remainingBytes }

        val request = buildDownloadRequest(url, resumeOffset, sliceBytes)
        val call = downloadClient.newCall(request)
        val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        val status = try {
            call.execute().use { response ->
                streamResponseToFile(
                    StreamTransferContext(
                        response = response,
                        file = file,
                        partialFile = partialFile,
                        resumeOffset = resumeOffset,
                        sliceBytes = sliceBytes,
                        shouldContinue = shouldContinue,
                        onProgress = onProgress
                    )
                )
            }
        } finally {
            cancelHandle?.dispose()
        }
        if (status == OfflineFileTransferStatus.COMPLETE) {
            promotePartialFile(partialFile, destinationFile)
        }
        status
    }

    private fun buildDownloadRequest(url: String, resumeOffset: Long, sliceBytes: Long?): Request {
        val builder = Request.Builder().url(url).get()
        when {
            sliceBytes != null -> {
                val endInclusive = resumeOffset + sliceBytes - 1L
                builder.header(HEADER_RANGE, "bytes=$resumeOffset-$endInclusive")
            }

            resumeOffset > 0L -> builder.header(HEADER_RANGE, "bytes=$resumeOffset-")
        }
        return builder.build()
    }

    private class StreamTransferContext(
        val response: Response,
        val file: OfflineCountryFile,
        val partialFile: File,
        val resumeOffset: Long,
        val sliceBytes: Long?,
        val shouldContinue: () -> Boolean,
        val onProgress: (Long) -> Unit
    )

    private suspend fun streamResponseToFile(ctx: StreamTransferContext): OfflineFileTransferStatus {
        val append = resolveAppendMode(ctx)
        var totalWritten = if (append) ctx.resumeOffset else 0L
        ctx.onProgress(totalWritten)
        val outcome = copyStreamToPartial(ctx, append, totalWritten)
        totalWritten = outcome.totalWritten
        return when {
            outcome.interrupted -> OfflineFileTransferStatus.INTERRUPTED
            ctx.sliceBytes != null && totalWritten < ctx.file.byteCount -> OfflineFileTransferStatus.PARTIAL_SLICE
            else -> OfflineFileTransferStatus.COMPLETE
        }
    }

    private fun resolveAppendMode(ctx: StreamTransferContext): Boolean {
        val append = ctx.response.code == HttpURLConnection.HTTP_PARTIAL && ctx.resumeOffset > 0L
        val validPartial = ctx.response.code == HttpURLConnection.HTTP_PARTIAL && ctx.sliceBytes != null
        if (!append && !validPartial && ctx.response.code != HttpURLConnection.HTTP_OK) {
            throw IOException("Unexpected download status ${ctx.response.code} for ${ctx.file.fileName}")
        }
        return append
    }

    private data class StreamCopyOutcome(val totalWritten: Long, val interrupted: Boolean)

    private suspend fun copyStreamToPartial(
        ctx: StreamTransferContext,
        append: Boolean,
        initialWritten: Long
    ): StreamCopyOutcome = ctx.response.body.byteStream().use { input ->
        FileOutputStream(ctx.partialFile, append).use { output ->
            try {
                copyLoop(input, output, ctx, initialWritten)
            } finally {
                output.flush()
            }
        }
    }

    private suspend fun copyLoop(
        input: InputStream,
        output: OutputStream,
        ctx: StreamTransferContext,
        initialWritten: Long
    ): StreamCopyOutcome {
        var totalWritten = initialWritten
        var bytesThisCall = 0L
        var interrupted = false
        var done = false
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        while (!done) {
            currentCoroutineContext().ensureActive()
            val sliceDone = ctx.sliceBytes != null && bytesThisCall >= ctx.sliceBytes
            when {
                sliceDone -> done = true
                !ctx.shouldContinue() -> {
                    interrupted = true
                    done = true
                }

                else -> {
                    val read = input.read(buffer)
                    if (read <= 0) {
                        done = true
                    } else {
                        output.write(buffer, 0, read)
                        totalWritten += read
                        bytesThisCall += read
                        ctx.onProgress(totalWritten)
                    }
                }
            }
        }
        return StreamCopyOutcome(totalWritten = totalWritten, interrupted = interrupted)
    }

    private fun promotePartialFile(partialFile: File, destinationFile: File) {
        destinationFile.parentFile?.mkdirs()
        if (destinationFile.exists()) {
            destinationFile.delete()
        }
        if (!partialFile.renameTo(destinationFile)) {
            partialFile.copyTo(destinationFile, overwrite = true)
            partialFile.delete()
        }
    }

    private companion object {
        const val COUNTRIES_MANIFEST_PATH = "countries/manifest.json"
        const val HEADER_RANGE = "Range"
        const val STREAM_BUFFER_BYTES = 64 * 1024
    }
}

/**
 * Delegates offline country requests to deterministic bundled fixtures when a debug launch
 * specifies `offlineCountriesScenarioName` (`"seeded"` or `"refused"`), and to
 * [HttpOfflineCountryServer] in production.
 */
@Singleton
internal class LaunchAwareOfflineCountryServer @Inject constructor(
    private val httpServer: HttpOfflineCountryServer,
    private val launchConfigHolder: LaunchConfigHolder,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : OfflineCountryServer {
    override suspend fun fetchManifest(): OfflineCountryManifest = when (currentScenario()) {
        SCENARIO_SEEDED -> withContext(ioDispatcher) { loadSeededManifest() }
        SCENARIO_REFUSED -> throw IOException("Simulated refused offline country catalog")
        else -> httpServer.fetchManifest()
    }

    override fun fileUrl(file: OfflineCountryFile): String {
        require(file.hasSafeName) { "Refusing unsafe offline country file name: ${file.path}" }
        return if (currentScenario() != null) {
            "https://offline-countries.invalid/countries/${file.fileName}"
        } else {
            httpServer.fileUrl(file)
        }
    }

    override suspend fun downloadFile(
        file: OfflineCountryFile,
        partialFile: File,
        destinationFile: File,
        maxBytesToTransfer: Long?,
        shouldContinue: () -> Boolean,
        onProgress: (Long) -> Unit
    ): OfflineFileTransferStatus {
        if (currentScenario() != null) {
            throw IOException("Downloads disabled for seeded UI test scenario '${currentScenario()}'")
        }
        return httpServer.downloadFile(
            file = file,
            partialFile = partialFile,
            destinationFile = destinationFile,
            maxBytesToTransfer = maxBytesToTransfer,
            shouldContinue = shouldContinue,
            onProgress = onProgress
        )
    }

    private fun currentScenario(): String? = launchConfigHolder.current.value.offlineCountriesScenarioName

    private fun loadSeededManifest(): OfflineCountryManifest {
        val stream = LaunchAwareOfflineCountryServer::class.java
            .getResourceAsStream(SEEDED_MANIFEST_RESOURCE)
            ?: throw IOException("Missing bundled offline countries seed fixture")
        val raw = stream.use { it.readBytes().decodeToString() }
        return OfflineCountryManifestParser.parseCatalog(raw)
    }

    companion object {
        const val SCENARIO_SEEDED = "seeded"
        const val SCENARIO_REFUSED = "refused"
        private const val SEEDED_MANIFEST_RESOURCE = "/manifests/OfflineCountriesSeed.json"
    }
}
