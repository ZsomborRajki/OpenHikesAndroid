package app.openhikes.core.network

import app.openhikes.core.common.pmtiles.PMTilesArchive
import app.openhikes.core.common.pmtiles.PMTilesWriter
import com.google.common.truth.Truth.assertThat
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

class HttpPMTilesRangeReaderTest {
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
    fun readsRemotePMTilesArchiveUsingHeadAndByteRangeRequests() {
        val expectedTile = "remote-range-mvt".encodeToByteArray()
        val archiveBytes = PMTilesWriter.buildArchive(
            tiles = listOf(PMTilesWriter.TileSpec(z = 5, x = 17, y = 11, payload = expectedTile)),
            compressInternal = false,
            compressTiles = true
        )

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "HEAD" -> MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Length", archiveBytes.size.toString())
                    .build()

                request.headers["Range"] == null -> MockResponse.Builder().code(400).build()

                else -> {
                    val (startStr, endStr) = request.headers["Range"]!!.removePrefix("bytes=").split("-")
                    val slice = archiveBytes.copyOfRange(startStr.toInt(), endStr.toInt() + 1)
                    MockResponse.Builder()
                        .code(206)
                        .body(Buffer().write(slice))
                        .build()
                }
            }
        }

        val url = server.url("/planet.pmtiles").toString()
        val reader = HttpPMTilesRangeReader.open(OkHttpClient(), url)
        PMTilesArchive.open(reader).use { archive ->
            assertThat(archive.tile(5, 17, 11)).isEqualTo(expectedTile)
            assertThat(archive.tile(5, 0, 0)).isNull()
        }
    }
}
