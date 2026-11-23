package app.openhikes.core.common.pmtiles

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

private const val COMPRESSION_UNKNOWN = 0
private const val COMPRESSION_UNCOMPRESSED = 1
private const val COMPRESSION_GZIP = 2
private const val COMPRESSION_BROTLI = 3
private const val COMPRESSION_ZSTD = 4

/**
 * Why a byte stream or file cannot be read as a valid PMTiles v3 archive.
 */
sealed class PMTilesException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Corrupt(message: String = "Corrupt PMTiles structure", cause: Throwable? = null) :
        PMTilesException(message, cause)

    class NotPMTiles(message: String = "Missing PMTiles magic bytes") : PMTilesException(message)

    class TooDeep(message: String = "PMTiles directory depth exceeded limit") : PMTilesException(message)

    class Truncated(message: String = "PMTiles archive ended unexpectedly", cause: Throwable? = null) :
        PMTilesException(message, cause)

    class UnsupportedCompression(val rawValue: Int) :
        PMTilesException("Unsupported PMTiles compression: $rawValue")

    class UnsupportedVersion(val version: Int) : PMTilesException("Unsupported PMTiles version: $version")
}

/**
 * Compression algorithm codes defined by the PMTiles v3 specification.
 */
enum class PMTilesCompression(val code: Int) {
    UNKNOWN(COMPRESSION_UNKNOWN),
    UNCOMPRESSED(COMPRESSION_UNCOMPRESSED),
    GZIP(COMPRESSION_GZIP),
    BROTLI(COMPRESSION_BROTLI),
    ZSTD(COMPRESSION_ZSTD);

    companion object {
        fun fromCodeOrNull(code: Int): PMTilesCompression? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The fixed 127-byte little-endian PMTiles v3 header.
 */
data class PMTilesHeader(
    val rootDirectoryOffset: Long = 0L,
    val rootDirectoryLength: Long = 0L,
    val metadataOffset: Long = 0L,
    val metadataLength: Long = 0L,
    val leafDirectoryOffset: Long = 0L,
    val leafDirectoryLength: Long = 0L,
    val tileDataOffset: Long = 0L,
    val tileDataLength: Long = 0L,
    val addressedTiles: Long = 0L,
    val tileEntries: Long = 0L,
    val tileContents: Long = 0L,
    val isClustered: Boolean = false,
    val internalCompression: Int = PMTilesCompression.UNCOMPRESSED.code,
    val tileCompression: Int = PMTilesCompression.UNCOMPRESSED.code,
    val tileType: Int = TILE_TYPE_MVT,
    val minimumZoom: Int = 0,
    val maximumZoom: Int = 0,
    val boundsE7: List<Int> = listOf(0, 0, 0, 0),
    val centerZoom: Int = 0,
    val centerE7: List<Int> = listOf(0, 0)
) {
    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(BYTE_COUNT).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC)
        buffer.put(VERSION.toByte())
        buffer.putLong(rootDirectoryOffset)
        buffer.putLong(rootDirectoryLength)
        buffer.putLong(metadataOffset)
        buffer.putLong(metadataLength)
        buffer.putLong(leafDirectoryOffset)
        buffer.putLong(leafDirectoryLength)
        buffer.putLong(tileDataOffset)
        buffer.putLong(tileDataLength)
        buffer.putLong(addressedTiles)
        buffer.putLong(tileEntries)
        buffer.putLong(tileContents)
        buffer.put(if (isClustered) 1.toByte() else 0.toByte())
        buffer.put(internalCompression.toByte())
        buffer.put(tileCompression.toByte())
        buffer.put(tileType.toByte())
        buffer.put(minimumZoom.toByte())
        buffer.put(maximumZoom.toByte())
        for (i in 0 until BOUNDS_COUNT) {
            buffer.putInt(boundsE7.getOrElse(i) { 0 })
        }
        buffer.put(centerZoom.toByte())
        for (i in 0 until CENTER_COUNT) {
            buffer.putInt(centerE7.getOrElse(i) { 0 })
        }
        return buffer.array()
    }

    companion object {
        const val BYTE_COUNT = 127
        const val ROOT_READ_BYTES = 16_384
        const val VERSION = 3
        const val TILE_TYPE_MVT = 1
        private const val BOUNDS_COUNT = 4
        private const val CENTER_COUNT = 2
        private const val UNSIGNED_BYTE_MASK = 0xFF
        val MAGIC: ByteArray = "PMTiles".encodeToByteArray()

        fun parse(bytes: ByteArray): PMTilesHeader {
            validateHeaderPrefix(bytes)
            val foundVersion = bytes[MAGIC.size].toInt() and UNSIGNED_BYTE_MASK
            if (foundVersion != VERSION) throw PMTilesException.UnsupportedVersion(foundVersion)

            val buffer = ByteBuffer.wrap(bytes, MAGIC.size + 1, BYTE_COUNT - MAGIC.size - 1)
                .order(ByteOrder.LITTLE_ENDIAN)
            return PMTilesHeader(
                rootDirectoryOffset = buffer.long,
                rootDirectoryLength = buffer.long,
                metadataOffset = buffer.long,
                metadataLength = buffer.long,
                leafDirectoryOffset = buffer.long,
                leafDirectoryLength = buffer.long,
                tileDataOffset = buffer.long,
                tileDataLength = buffer.long,
                addressedTiles = buffer.long,
                tileEntries = buffer.long,
                tileContents = buffer.long,
                isClustered = (buffer.get().toInt() and UNSIGNED_BYTE_MASK) == 1,
                internalCompression = buffer.get().toInt() and UNSIGNED_BYTE_MASK,
                tileCompression = buffer.get().toInt() and UNSIGNED_BYTE_MASK,
                tileType = buffer.get().toInt() and UNSIGNED_BYTE_MASK,
                minimumZoom = buffer.get().toInt() and UNSIGNED_BYTE_MASK,
                maximumZoom = buffer.get().toInt() and UNSIGNED_BYTE_MASK,
                boundsE7 = List(BOUNDS_COUNT) { buffer.int },
                centerZoom = buffer.get().toInt() and UNSIGNED_BYTE_MASK,
                centerE7 = List(CENTER_COUNT) { buffer.int }
            )
        }

        private fun validateHeaderPrefix(bytes: ByteArray) {
            if (bytes.size < BYTE_COUNT) throw PMTilesException.Truncated()
            for (i in MAGIC.indices) {
                if (bytes[i] != MAGIC[i]) throw PMTilesException.NotPMTiles()
            }
        }
    }
}

/**
 * Single row of a PMTiles directory: either a tile run (`runLength > 0`) or a leaf directory
 * pointer (`runLength == 0`).
 */
data class PMTilesEntry(val tileId: Long, val offset: Long, val length: Long, val runLength: Long) {
    val isLeaf: Boolean
        get() = runLength == 0L
}

/**
 * Hilbert-curve tile ID encoding from the PMTiles v3 specification (`0 <= z <= 31`).
 */
object PMTilesTileId {
    const val MAXIMUM_ZOOM = 31
    private const val LEVEL_OFFSET_DIVISOR = 3UL
    private const val QUADRANT_RIGHT_WEIGHT = 3UL

    fun id(z: Int, x: Int, y: Int): Long {
        require(z in 0..MAXIMUM_ZOOM) { "zoom $z is outside PMTiles range 0..$MAXIMUM_ZOOM" }
        var id: ULong = if (z == 0) 0UL else ((1UL shl (2 * z)) - 1UL) / LEVEL_OFFSET_DIVISOR
        var column = x.toULong()
        var row = y.toULong()
        var size: ULong = if (z == 0) 0UL else (1UL shl (z - 1))
        while (size >= 1UL) {
            val right: ULong = if ((column and size) == 0UL) 0UL else 1UL
            val down: ULong = if ((row and size) == 0UL) 0UL else 1UL
            id += size * size * ((QUADRANT_RIGHT_WEIGHT * right) xor down)
            if (down == 0UL) {
                if (right == 1UL) {
                    val mask = size - 1UL
                    column = mask - (column and mask)
                    row = mask - (row and mask)
                }
                val temp = column
                column = row
                row = temp
            }
            size = size shr 1
        }
        return id.toLong()
    }
}

/**
 * Overflow-guarded unsigned LEB128 varint reader/writer used by PMTiles columnar directories.
 */
object PMTilesVarint {
    private const val PAYLOAD_MASK = 0x7F
    private const val CONTINUATION_MASK = 0x80
    private const val SHIFT_STEP = 7
    private const val MAX_SHIFT = 63

    class Reader(private val bytes: ByteArray) {
        private var offset = 0

        fun readVarint(): ULong {
            var value = 0UL
            var shift = 0
            while (offset < bytes.size) {
                val raw = bytes[offset++].toInt() and 0xFF
                val payload = (raw and PAYLOAD_MASK).toULong()
                if (shift > MAX_SHIFT || payload > (ULong.MAX_VALUE shr shift)) {
                    throw PMTilesException.Corrupt("Varint overflow")
                }
                value = value or (payload shl shift)
                if ((raw and CONTINUATION_MASK) == 0) return value
                shift += SHIFT_STEP
            }
            throw PMTilesException.Corrupt("Unterminated varint")
        }

        fun readCount(atMost: Int): Int {
            val raw = readVarint()
            if (raw > atMost.toULong()) {
                throw PMTilesException.Corrupt("Directory entry count $raw exceeds maximum $atMost")
            }
            return raw.toInt()
        }
    }

    fun writeVarint(out: ByteArrayOutputStream, value: ULong) {
        var remaining = value
        while (true) {
            val low = (remaining and PAYLOAD_MASK.toULong()).toInt()
            remaining = remaining shr SHIFT_STEP
            if (remaining == 0UL) {
                out.write(low)
                return
            }
            out.write(low or CONTINUATION_MASK)
        }
    }
}

/**
 * Columnar PMTiles v3 directory codec and binary search lookup.
 */
object PMTilesDirectory {
    private const val MIN_BYTES_PER_ENTRY = 4

    fun decode(bytes: ByteArray): List<PMTilesEntry> {
        val reader = PMTilesVarint.Reader(bytes)
        val count = reader.readCount(atMost = bytes.size / MIN_BYTES_PER_ENTRY)
        val tileIds = LongArray(count)
        val runLengths = LongArray(count)
        val lengths = LongArray(count)
        val offsets = LongArray(count)

        var currentTileId = 0UL
        for (i in 0 until count) {
            val delta = reader.readVarint()
            if (delta > ULong.MAX_VALUE - currentTileId) {
                throw PMTilesException.Corrupt("Tile ID delta overflow")
            }
            currentTileId += delta
            tileIds[i] = currentTileId.toLong()
        }
        for (i in 0 until count) {
            runLengths[i] = reader.readVarint().toLong()
        }
        for (i in 0 until count) {
            lengths[i] = reader.readVarint().toLong()
        }
        decodeOffsets(reader, count, lengths, offsets)
        return List(count) { i ->
            PMTilesEntry(tileId = tileIds[i], offset = offsets[i], length = lengths[i], runLength = runLengths[i])
        }
    }

    private fun decodeOffsets(reader: PMTilesVarint.Reader, count: Int, lengths: LongArray, offsets: LongArray) {
        for (i in 0 until count) {
            val encoded = reader.readVarint()
            if (encoded == 0UL) {
                if (i == 0) throw PMTilesException.Corrupt("First directory entry cannot have zero offset")
                val prevOffset = offsets[i - 1].toULong()
                val prevLength = lengths[i - 1].toULong()
                if (prevLength > ULong.MAX_VALUE - prevOffset) {
                    throw PMTilesException.Corrupt("Continuation offset overflow")
                }
                offsets[i] = (prevOffset + prevLength).toLong()
            } else {
                offsets[i] = (encoded - 1UL).toLong()
            }
        }
    }

    fun encode(entries: List<PMTilesEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        PMTilesVarint.writeVarint(out, entries.size.toULong())
        var previousId = 0UL
        for (entry in entries) {
            val id = entry.tileId.toULong()
            PMTilesVarint.writeVarint(out, id - previousId)
            previousId = id
        }
        for (entry in entries) {
            PMTilesVarint.writeVarint(out, entry.runLength.toULong())
        }
        for (entry in entries) {
            PMTilesVarint.writeVarint(out, entry.length.toULong())
        }
        for (i in entries.indices) {
            val entry = entries[i]
            val prev = if (i > 0) entries[i - 1] else null
            if (prev != null && entry.offset.toULong() == prev.offset.toULong() + prev.length.toULong()) {
                PMTilesVarint.writeVarint(out, 0UL)
            } else {
                PMTilesVarint.writeVarint(out, entry.offset.toULong() + 1UL)
            }
        }
        return out.toByteArray()
    }

    /**
     * Binary search for the entry covering [tileId], or the leaf directory pointer preceding it.
     */
    fun entryHolding(tileId: Long, entries: List<PMTilesEntry>): PMTilesEntry? {
        val target = tileId.toULong()
        var low = 0
        var high = entries.lastIndex
        var exact: PMTilesEntry? = null
        while (low <= high && exact == null) {
            val mid = (low + high) ushr 1
            val current = entries[mid].tileId.toULong()
            when {
                current < target -> low = mid + 1
                current > target -> high = mid - 1
                else -> exact = entries[mid]
            }
        }
        return exact ?: entries.getOrNull(high)?.takeIf { candidate ->
            val delta = target - candidate.tileId.toULong()
            candidate.isLeaf || delta < candidate.runLength.toULong()
        }
    }
}

/**
 * RFC 1952 gzip decompression/compression with an explicit 64 MiB zip-bomb ceiling and exact
 * ISIZE/stream-end validation.
 */
object PMTilesGzip {
    const val MAX_BODY_BYTES = 64 * 1024 * 1024
    private const val MAGIC_BYTE_0 = 0x1F
    private const val MAGIC_BYTE_1 = 0x8B
    private const val DEFLATE_METHOD = 8
    private const val FIXED_HEADER_BYTES = 10
    private const val TRAILER_BYTES = 8
    private const val FLAG_HEADER_CRC = 0x02
    private const val FLAG_EXTRA = 0x04
    private const val FLAG_NAME = 0x08
    private const val FLAG_COMMENT = 0x10
    private const val UNSIGNED_BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8

    fun unpack(bytes: ByteArray, compressionCode: Int): ByteArray {
        val method = PMTilesCompression.fromCodeOrNull(compressionCode)
            ?: throw PMTilesException.UnsupportedCompression(compressionCode)
        return when (method) {
            PMTilesCompression.UNCOMPRESSED, PMTilesCompression.UNKNOWN -> bytes
            PMTilesCompression.GZIP -> inflate(bytes)
            PMTilesCompression.BROTLI, PMTilesCompression.ZSTD ->
                throw PMTilesException.UnsupportedCompression(compressionCode)
        }
    }

    fun inflate(bytes: ByteArray): ByteArray {
        validateGzipHeader(bytes)
        val flags = bytes[3].toInt() and UNSIGNED_BYTE_MASK
        val end = bytes.size - TRAILER_BYTES
        val start = skipOptionalGzipHeaders(bytes, flags, end)
        if (start >= end) throw PMTilesException.Corrupt("Empty gzip payload")

        val expectedSize = ByteBuffer.wrap(bytes, bytes.size - 4, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
            .toUInt()
        if (expectedSize > MAX_BODY_BYTES.toUInt()) {
            throw PMTilesException.Corrupt("Gzip output exceeds maximum size")
        }
        return inflateRawDeflate(bytes, start, end - start, expectedSize.toInt())
    }

    private fun validateGzipHeader(bytes: ByteArray) {
        if (bytes.size < FIXED_HEADER_BYTES + TRAILER_BYTES) {
            throw PMTilesException.Corrupt("Gzip buffer too short")
        }
        val b0 = bytes[0].toInt() and UNSIGNED_BYTE_MASK
        val b1 = bytes[1].toInt() and UNSIGNED_BYTE_MASK
        val method = bytes[2].toInt() and UNSIGNED_BYTE_MASK
        if (b0 != MAGIC_BYTE_0 || b1 != MAGIC_BYTE_1 || method != DEFLATE_METHOD) {
            throw PMTilesException.Corrupt("Invalid gzip header")
        }
    }

    /**
     * Compresses [raw] into an RFC 1952 gzip frame (used by PMTiles writers and tests).
     */
    fun compress(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        val body = ByteArrayOutputStream()
        try {
            deflater.setInput(raw)
            deflater.finish()
            val scratch = ByteArray(DEFAULT_BUFFER_SIZE)
            while (!deflater.finished()) {
                val count = deflater.deflate(scratch)
                if (count > 0) body.write(scratch, 0, count)
            }
        } finally {
            deflater.end()
        }
        val crc = CRC32().apply { update(raw) }.value.toInt()
        val deflated = body.toByteArray()
        val out = ByteBuffer.allocate(FIXED_HEADER_BYTES + deflated.size + TRAILER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        out.put(MAGIC_BYTE_0.toByte())
        out.put(MAGIC_BYTE_1.toByte())
        out.put(DEFLATE_METHOD.toByte())
        out.put(0.toByte())
        out.putInt(0)
        out.put(0.toByte())
        out.put(UNSIGNED_BYTE_MASK.toByte())
        out.put(deflated)
        out.putInt(crc)
        out.putInt(raw.size)
        return out.array()
    }

    private fun skipOptionalGzipHeaders(bytes: ByteArray, flags: Int, end: Int): Int {
        var pos = FIXED_HEADER_BYTES
        if ((flags and FLAG_EXTRA) != 0) {
            if (pos + 2 > end) throw PMTilesException.Corrupt("Truncated gzip extra field")
            val xlen = (bytes[pos].toInt() and UNSIGNED_BYTE_MASK) or
                ((bytes[pos + 1].toInt() and UNSIGNED_BYTE_MASK) shl BITS_PER_BYTE)
            pos += 2 + xlen
        }
        if ((flags and FLAG_NAME) != 0) {
            pos = advancePastNull(bytes, pos, end)
        }
        if ((flags and FLAG_COMMENT) != 0) {
            pos = advancePastNull(bytes, pos, end)
        }
        if ((flags and FLAG_HEADER_CRC) != 0) {
            pos += 2
        }
        return pos
    }

    private fun advancePastNull(bytes: ByteArray, start: Int, end: Int): Int {
        var pos = start
        while (pos < end) {
            if (bytes[pos] == 0.toByte()) return pos + 1
            pos++
        }
        throw PMTilesException.Corrupt("Unterminated gzip header string")
    }

    private fun inflateRawDeflate(bytes: ByteArray, offset: Int, length: Int, expectedSize: Int): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(bytes, offset, length)
            val output = ByteArray(expectedSize + 1)
            val totalWritten = runInflaterLoop(inflater, output)
            if (!inflater.finished() || totalWritten != expectedSize) {
                throw PMTilesException.Corrupt("Gzip decoded size mismatch")
            }
            return output.copyOf(expectedSize)
        } catch (ex: DataFormatException) {
            throw PMTilesException.Corrupt("Gzip inflation failed", ex)
        } finally {
            inflater.end()
        }
    }

    private fun runInflaterLoop(inflater: Inflater, output: ByteArray): Int {
        var totalWritten = 0
        while (!inflater.finished()) {
            val space = output.size - totalWritten
            if (space <= 0) throw PMTilesException.Corrupt("Gzip output exceeded declared ISIZE")
            val n = inflater.inflate(output, totalWritten, space)
            if (n == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) {
                    throw PMTilesException.Corrupt("Truncated gzip deflate stream")
                }
                break
            }
            totalWritten += n
        }
        return totalWritten
    }
}
