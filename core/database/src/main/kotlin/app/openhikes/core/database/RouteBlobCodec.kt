package app.openhikes.core.database

import app.openhikes.core.model.RouteBoundary
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteMotion
import app.openhikes.core.model.RouteProvenance
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.min

/**
 * Compact deterministic binary serializer for [List]<[RouteCoordinate]> stored in bounded
 * `hike_route_chunks` rows so long tracks never exceed Android's 2 MiB SQLite CursorWindow.
 */
object RouteBlobCodec {
    const val MAX_CHUNK_BYTES: Int = 256 * 1024

    private const val FORMAT_VERSION: Int = 1
    private const val FLAG_HAS_ELEVATION = 1 shl 0
    private const val FLAG_HAS_TIMESTAMP = 1 shl 1
    private const val FLAG_IS_PAUSED = 1 shl 2
    private const val FLAG_IS_INFERRED = 1 shl 3
    private const val FLAG_IS_NON_PEDESTRIAN = 1 shl 4
    private const val ESTIMATED_BYTES_PER_POINT = 32
    private const val HEADER_BYTES = 5
    private const val MAX_DECODED_POINT_COUNT = 2_000_000
    private const val BYTE_MASK = 0xFF
    private const val INITIAL_CHUNK_CAPACITY = 4096

    fun encode(route: List<RouteCoordinate>): ByteArray {
        val buffer = ByteArrayOutputStream(HEADER_BYTES + route.size * ESTIMATED_BYTES_PER_POINT)
        writeRoute(route, buffer)
        return buffer.toByteArray()
    }

    fun encodeChunked(route: List<RouteCoordinate>, maxChunkBytes: Int = MAX_CHUNK_BYTES): List<ByteArray> {
        require(maxChunkBytes > 0) { "Chunk size must be positive: $maxChunkBytes" }
        val chunkedOut = ChunkedByteArrayOutputStream(maxChunkBytes)
        writeRoute(route, chunkedOut)
        return chunkedOut.toChunks()
    }

    fun decode(bytes: ByteArray): List<RouteCoordinate> {
        if (bytes.isEmpty()) return emptyList()
        return decodeChunks(listOf(bytes))
    }

    fun decodeChunks(chunks: List<ByteArray>): List<RouteCoordinate> {
        if (chunks.isEmpty() || chunks.all { it.isEmpty() }) return emptyList()
        try {
            DataInputStream(ChunkedByteArrayInputStream(chunks)).use { input ->
                val version = input.readUnsignedByte()
                require(version == FORMAT_VERSION) { "Unsupported route blob version: $version" }
                val count = input.readInt()
                require(count in 0..MAX_DECODED_POINT_COUNT) { "Invalid route point count: $count" }
                val points = ArrayList<RouteCoordinate>(count)
                repeat(count) {
                    points.add(readPoint(input))
                }
                return points
            }
        } catch (eof: EOFException) {
            throw IllegalArgumentException("Truncated route blob", eof)
        }
    }

    fun decodeOrEmpty(bytes: ByteArray): List<RouteCoordinate> = runCatching { decode(bytes) }.getOrDefault(emptyList())

    fun decodeChunksOrEmpty(chunks: List<ByteArray>): List<RouteCoordinate> =
        runCatching { decodeChunks(chunks) }.getOrDefault(emptyList())

    private fun writeRoute(route: List<RouteCoordinate>, target: OutputStream) {
        DataOutputStream(target).use { out ->
            out.writeByte(FORMAT_VERSION)
            out.writeInt(route.size)
            for (point in route) {
                out.writeDouble(point.latitude)
                out.writeDouble(point.longitude)
                var flags = 0
                if (point.elevationMeters != null) flags = flags or FLAG_HAS_ELEVATION
                if (point.timestampEpochMillis != null) flags = flags or FLAG_HAS_TIMESTAMP
                if (point.isPauseBoundary) flags = flags or FLAG_IS_PAUSED
                if (point.isInferred) flags = flags or FLAG_IS_INFERRED
                if (point.motion == RouteMotion.NON_PEDESTRIAN) flags = flags or FLAG_IS_NON_PEDESTRIAN
                out.writeByte(flags)
                point.elevationMeters?.let { out.writeDouble(it) }
                point.timestampEpochMillis?.let { out.writeLong(it) }
            }
        }
    }

    private fun readPoint(input: DataInputStream): RouteCoordinate {
        val lat = input.readDouble()
        val lon = input.readDouble()
        val flags = input.readUnsignedByte()
        val elevation = if ((flags and FLAG_HAS_ELEVATION) != 0) input.readDouble() else null
        val timestamp = if ((flags and FLAG_HAS_TIMESTAMP) != 0) input.readLong() else null
        val boundary = if ((flags and FLAG_IS_PAUSED) != 0) RouteBoundary.PAUSED else null
        val provenance = if ((flags and FLAG_IS_INFERRED) != 0) RouteProvenance.INFERRED else null
        val motion = if ((flags and FLAG_IS_NON_PEDESTRIAN) != 0) RouteMotion.NON_PEDESTRIAN else null
        return RouteCoordinate(
            latitude = lat,
            longitude = lon,
            elevationMeters = elevation,
            timestampEpochMillis = timestamp,
            motion = motion,
            provenance = provenance,
            boundary = boundary
        )
    }

    private class ChunkedByteArrayOutputStream(private val maxChunkBytes: Int) : OutputStream() {
        private val completedChunks = ArrayList<ByteArray>()
        private var current = ByteArrayOutputStream(min(maxChunkBytes, INITIAL_CHUNK_CAPACITY))

        override fun write(b: Int) {
            if (current.size() >= maxChunkBytes) {
                completedChunks.add(current.toByteArray())
                current = ByteArrayOutputStream(min(maxChunkBytes, INITIAL_CHUNK_CAPACITY))
            }
            current.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off
            var remaining = len
            while (remaining > 0) {
                val space = maxChunkBytes - current.size()
                if (space <= 0) {
                    completedChunks.add(current.toByteArray())
                    current = ByteArrayOutputStream(min(maxChunkBytes, INITIAL_CHUNK_CAPACITY))
                } else {
                    val step = min(remaining, space)
                    current.write(b, offset, step)
                    offset += step
                    remaining -= step
                }
            }
        }

        fun toChunks(): List<ByteArray> {
            if (completedChunks.isEmpty() || current.size() > 0) {
                completedChunks.add(current.toByteArray())
            }
            return completedChunks
        }
    }

    private class ChunkedByteArrayInputStream(private val chunks: List<ByteArray>) : InputStream() {
        private var chunkIndex = 0
        private var byteOffset = 0

        override fun read(): Int {
            while (chunkIndex < chunks.size) {
                val chunk = chunks[chunkIndex]
                if (byteOffset < chunk.size) {
                    return chunk[byteOffset++].toInt() and BYTE_MASK
                }
                chunkIndex += 1
                byteOffset = 0
            }
            return -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            var totalRead = 0
            var dstOffset = off
            var remaining = len
            while (remaining > 0 && chunkIndex < chunks.size) {
                val chunk = chunks[chunkIndex]
                val available = chunk.size - byteOffset
                if (available <= 0) {
                    chunkIndex += 1
                    byteOffset = 0
                } else {
                    val step = min(remaining, available)
                    System.arraycopy(chunk, byteOffset, b, dstOffset, step)
                    byteOffset += step
                    dstOffset += step
                    remaining -= step
                    totalRead += step
                }
            }
            return if (totalRead == 0) -1 else totalRead
        }
    }
}
