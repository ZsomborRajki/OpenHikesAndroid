package app.openhikes.core.network

import app.openhikes.core.common.offline.OfflineCountryDigest
import app.openhikes.core.model.OfflineCountryFile
import app.openhikes.core.model.OfflineFileRole
import app.openhikes.core.testing.TestFixtures
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HttpOfflineCountryServerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun fetchesCountryCatalogAndResumesPartialDownloadWithRangeHeader() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fullPayload = "0123456789abcdefghijklmnopqrstuvwxyz".encodeToByteArray()
        val seenRanges = mutableListOf<String?>()
        server.dispatcher = payloadDispatcher(fullPayload, seenRanges)

        val countryServer = createCountryServer(dispatcher)
        assertThat(countryServer.fetchManifest().readableCountries).hasSize(10)

        val file = sampleFile(fullPayload)
        val partial = File(tempFolder.root, "AT-20261117.pmtiles.part").apply {
            writeBytes(fullPayload.copyOfRange(0, 10))
        }
        val destination = File(tempFolder.root, "AT-20261117.pmtiles")
        val progressSteps = mutableListOf<Long>()

        val status = countryServer.downloadFile(file, partial, destination) { progressSteps.add(it) }

        assertThat(status).isEqualTo(OfflineFileTransferStatus.COMPLETE)
        assertThat(seenRanges).containsExactly("bytes=10-")
        assertThat(destination.readBytes()).isEqualTo(fullPayload)
        assertThat(partial.exists()).isFalse()
        assertThat(progressSteps.last()).isEqualTo(fullPayload.size.toLong())
    }

    @Test
    fun boundedSliceRequestsPreservePartialFileAndResumeToCompletion() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fullPayload = "0123456789abcdefghijklmnopqrstuvwxyz".encodeToByteArray()
        val seenRanges = mutableListOf<String?>()
        server.dispatcher = payloadDispatcher(fullPayload, seenRanges)

        val countryServer = createCountryServer(dispatcher)
        val file = sampleFile(fullPayload)
        val partial = File(tempFolder.root, "AT-20261117.pmtiles.part")
        val destination = File(tempFolder.root, "AT-20261117.pmtiles")

        val firstStatus = countryServer.downloadFile(
            file = file,
            partialFile = partial,
            destinationFile = destination,
            maxBytesToTransfer = 16L
        ) {}
        assertThat(firstStatus).isEqualTo(OfflineFileTransferStatus.PARTIAL_SLICE)
        assertThat(partial.length()).isEqualTo(16L)
        assertThat(destination.exists()).isFalse()

        val secondStatus = countryServer.downloadFile(
            file = file,
            partialFile = partial,
            destinationFile = destination,
            maxBytesToTransfer = 100L
        ) {}
        assertThat(secondStatus).isEqualTo(OfflineFileTransferStatus.COMPLETE)
        assertThat(seenRanges).containsExactly("bytes=0-15", "bytes=16-").inOrder()
        assertThat(destination.readBytes()).isEqualTo(fullPayload)
    }

    @Test
    fun malformedCatalogJsonThrowsRecoverableIOException() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder()
                .code(200)
                .body("""{"version": 1, "countries": [""")
                .build()
        }
        val countryServer = createCountryServer(dispatcher)

        val failure = runCatching { countryServer.fetchManifest() }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IOException::class.java)
    }

    @Test
    fun interruptedTransferPreservesPartialFile() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fullPayload = "0123456789abcdefghijklmnopqrstuvwxyz".encodeToByteArray()
        server.dispatcher = payloadDispatcher(fullPayload, mutableListOf())
        val countryServer = createCountryServer(dispatcher)
        val file = sampleFile(fullPayload)
        val partial = File(tempFolder.root, "AT-20261117.pmtiles.part")
        val destination = File(tempFolder.root, "AT-20261117.pmtiles")

        val status = countryServer.downloadFile(
            file = file,
            partialFile = partial,
            destinationFile = destination,
            shouldContinue = { false }
        ) {}
        assertThat(status).isEqualTo(OfflineFileTransferStatus.INTERRUPTED)
        assertThat(destination.exists()).isFalse()
        assertThat(
            assertThrows(IllegalArgumentException::class.java) {
                countryServer.fileUrl(file.copy(path = "countries/../../evil.pmtiles"))
            }
        ).isNotNull()
    }

    private fun createCountryServer(dispatcher: kotlinx.coroutines.CoroutineDispatcher): HttpOfflineCountryServer {
        val okHttp = OkHttpClient()
        return HttpOfflineCountryServer(
            manifestClient = okHttp,
            downloadClient = okHttp,
            serverBaseUrl = server.url("/").toString(),
            ioDispatcher = dispatcher
        )
    }

    private fun sampleFile(payload: ByteArray): OfflineCountryFile = OfflineCountryFile(
        role = OfflineFileRole.BASEMAP,
        path = "countries/AT-20261117.pmtiles",
        byteCount = payload.size.toLong(),
        sha256 = OfflineCountryDigest.sha256Hex(payload),
        minimumZoom = 0,
        maximumZoom = 14
    )

    private fun payloadDispatcher(fullPayload: ByteArray, seenRanges: MutableList<String?>): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.target) {
                "/countries/manifest.json" -> MockResponse.Builder()
                    .code(200)
                    .body(TestFixtures.offlineCountriesSeedJson())
                    .build()

                "/countries/AT-20261117.pmtiles" -> buildRangeResponse(
                    fullPayload,
                    request.headers["Range"],
                    seenRanges
                )
                else -> MockResponse.Builder().code(404).build()
            }
        }

    private fun buildRangeResponse(
        fullPayload: ByteArray,
        rangeHeader: String?,
        seenRanges: MutableList<String?>
    ): MockResponse {
        seenRanges.add(rangeHeader)
        if (rangeHeader == null) {
            return MockResponse.Builder().code(200).body(Buffer().write(fullPayload)).build()
        }
        val spec = rangeHeader.removePrefix("bytes=").split("-")
        val start = spec[0].toInt()
        val endInclusive = spec.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toInt() ?: (fullPayload.size - 1)
        val slice = fullPayload.copyOfRange(start, endInclusive + 1)
        return MockResponse.Builder()
            .code(206)
            .body(Buffer().write(slice))
            .build()
    }
}
