package app.openhikes.core.network

import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.common.offline.OfflineCountryManifestParser
import app.openhikes.core.model.OfflineCountryFile
import app.openhikes.core.model.OfflineCountryManifest
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Network seam for fetching `countries/manifest.json` and downloading country `.pmtiles` archives
 * with HTTP `Range` resume support.
 */
interface OfflineCountryServer {
    suspend fun fetchManifest(): OfflineCountryManifest

    fun fileUrl(file: OfflineCountryFile): String

    /**
     * Streams [file] into [destinationFile], resuming from [partialFile] when present, and reporting
     * cumulative bytes written via [onProgress].
     */
    suspend fun downloadFile(
        file: OfflineCountryFile,
        partialFile: File,
        destinationFile: File,
        onProgress: (Long) -> Unit
    )
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
            OfflineCountryManifestParser.parseCatalog(response.body.string())
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
        onProgress: (Long) -> Unit
    ) = withContext(ioDispatcher) {
        val url = fileUrl(file)
        partialFile.parentFile?.mkdirs()
        val existingBytes = if (partialFile.isFile) partialFile.length() else 0L
        val resumeOffset = if (existingBytes in 1L until file.byteCount) existingBytes else 0L

        val requestBuilder = Request.Builder().url(url).get()
        if (resumeOffset > 0L) {
            requestBuilder.header(HEADER_RANGE, "bytes=$resumeOffset-")
        }
        downloadClient.newCall(requestBuilder.build()).execute().use { response ->
            streamResponseToFile(response, file.fileName, partialFile, resumeOffset, onProgress)
        }
        promotePartialFile(partialFile, destinationFile)
    }

    private suspend fun streamResponseToFile(
        response: Response,
        fileName: String,
        partialFile: File,
        resumeOffset: Long,
        onProgress: (Long) -> Unit
    ) {
        val append = response.code == HttpURLConnection.HTTP_PARTIAL && resumeOffset > 0L
        if (!append && response.code != HttpURLConnection.HTTP_OK) {
            throw IOException("Unexpected download status ${response.code} for $fileName")
        }
        var totalWritten = if (append) resumeOffset else 0L
        onProgress(totalWritten)
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        response.body.byteStream().use { input ->
            FileOutputStream(partialFile, append).use { output ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    totalWritten += read
                    onProgress(totalWritten)
                }
                output.flush()
            }
        }
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
        onProgress: (Long) -> Unit
    ) {
        if (currentScenario() != null) {
            throw IOException("Downloads disabled for seeded UI test scenario '${currentScenario()}'")
        }
        httpServer.downloadFile(file, partialFile, destinationFile, onProgress)
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
