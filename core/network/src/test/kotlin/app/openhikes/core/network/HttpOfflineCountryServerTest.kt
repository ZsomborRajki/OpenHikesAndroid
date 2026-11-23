package app.openhikes.core.network

import app.openhikes.core.common.offline.OfflineCountryDigest
import app.openhikes.core.model.OfflineCountryFile
import app.openhikes.core.model.OfflineFileRole
import app.openhikes.core.testing.TestFixtures
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
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

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.target) {
                "/countries/manifest.json" -> MockResponse.Builder()
                    .code(200)
                    .body(TestFixtures.offlineCountriesSeedJson())
                    .build()

                "/countries/AT-20261117.pmtiles" -> {
                    val range = request.headers["Range"]
                    seenRanges.add(range)
                    val offset = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                    val slice = fullPayload.copyOfRange(offset, fullPayload.size)
                    MockResponse.Builder()
                        .code(if (offset > 0) 206 else 200)
                        .body(Buffer().write(slice))
                        .build()
                }

                else -> MockResponse.Builder().code(404).build()
            }
        }

        val okHttp = OkHttpClient()
        val countryServer = HttpOfflineCountryServer(
            manifestClient = okHttp,
            downloadClient = okHttp,
            serverBaseUrl = server.url("/").toString(),
            ioDispatcher = dispatcher
        )

        val manifest = countryServer.fetchManifest()
        assertThat(manifest.readableCountries).hasSize(10)

        val file = OfflineCountryFile(
            role = OfflineFileRole.BASEMAP,
            path = "countries/AT-20261117.pmtiles",
            byteCount = fullPayload.size.toLong(),
            sha256 = OfflineCountryDigest.sha256Hex(fullPayload),
            minimumZoom = 0,
            maximumZoom = 14
        )
        val partial = File(tempFolder.root, "AT-20261117.pmtiles.part").apply {
            writeBytes(fullPayload.copyOfRange(0, 10))
        }
        val destination = File(tempFolder.root, "AT-20261117.pmtiles")
        val progressSteps = mutableListOf<Long>()

        countryServer.downloadFile(file, partial, destination) { progressSteps.add(it) }

        assertThat(seenRanges).containsExactly("bytes=10-")
        assertThat(destination.readBytes()).isEqualTo(fullPayload)
        assertThat(partial.exists()).isFalse()
        assertThat(progressSteps.last()).isEqualTo(fullPayload.size.toLong())
    }
}
