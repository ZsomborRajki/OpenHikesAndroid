package app.openhikes.core.network

import app.openhikes.core.common.pmtiles.PMTilesByteReader
import app.openhikes.core.common.pmtiles.PMTilesException
import java.io.IOException
import java.net.HttpURLConnection
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * HTTP `Range`-based [PMTilesByteReader] allowing [app.openhikes.core.common.pmtiles.PMTilesArchive]
 * to read remote PMTiles archives over HTTPS via `HEAD` + `GET Range: bytes=<start>-<end>`.
 */
class HttpPMTilesRangeReader private constructor(
    private val okHttpClient: OkHttpClient,
    private val url: String,
    override val size: Long
) : PMTilesByteReader {
    override fun read(offset: Long, length: Int): ByteArray {
        if (length == 0) return ByteArray(0)
        if (offset < 0L || length < 0 || offset + length.toLong() > size) {
            throw PMTilesException.Truncated("Range $offset..${offset + length - 1} exceeds size $size")
        }
        val endInclusive = offset + length.toLong() - 1L
        val request = Request.Builder()
            .url(url)
            .header(HEADER_RANGE, "bytes=$offset-$endInclusive")
            .get()
            .build()
        return try {
            okHttpClient.newCall(request).execute().use { response ->
                decodeRangeResponse(response, offset, length)
            }
        } catch (ex: IOException) {
            throw PMTilesException.Truncated("Range read failed: ${ex.message}", ex)
        }
    }

    private fun decodeRangeResponse(response: Response, offset: Long, length: Int): ByteArray {
        val validStatus = response.code == HttpURLConnection.HTTP_PARTIAL ||
            (response.code == HttpURLConnection.HTTP_OK && offset == 0L && length.toLong() == size)
        if (!validStatus) {
            throw PMTilesException.Truncated("Unexpected range HTTP status ${response.code}")
        }
        val bytes = response.body.bytes()
        if (bytes.size != length) {
            throw PMTilesException.Truncated("Expected $length bytes but got ${bytes.size}")
        }
        return bytes
    }

    override fun close() = Unit

    companion object {
        private const val HEADER_RANGE = "Range"
        private const val HEADER_CONTENT_LENGTH = "Content-Length"

        /**
         * Probes [url] via HTTP `HEAD` to verify availability and discover `Content-Length`.
         */
        fun open(okHttpClient: OkHttpClient, url: String): HttpPMTilesRangeReader {
            val headRequest = Request.Builder().url(url).head().build()
            val contentLength = try {
                okHttpClient.newCall(headRequest).execute().use(::parseContentLength)
            } catch (ex: IOException) {
                throw PMTilesException.Truncated("HEAD request failed: ${ex.message}", ex)
            }
            return HttpPMTilesRangeReader(okHttpClient = okHttpClient, url = url, size = contentLength)
        }

        private fun parseContentLength(response: Response): Long {
            if (response.code != HttpURLConnection.HTTP_OK) {
                throw PMTilesException.Truncated("HEAD failed with status ${response.code}")
            }
            return response.header(HEADER_CONTENT_LENGTH)?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: throw PMTilesException.Truncated("Missing Content-Length header for PMTiles archive")
        }
    }
}
