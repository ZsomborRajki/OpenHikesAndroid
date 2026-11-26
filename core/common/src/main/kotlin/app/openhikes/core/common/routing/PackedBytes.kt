package app.openhikes.core.common.routing

import java.io.ByteArrayOutputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Failure decoding a packed OpenHikes square binary file (`OHGS`, etc.).
 */
sealed class PackedFileException(message: String) : Exception(message) {
    data object WrongFormat : PackedFileException("Unexpected magic header")

    data class UnsupportedVersion(val version: Int) : PackedFileException("Unsupported version: $version")

    data class UnsupportedCompression(val method: Int) : PackedFileException("Unsupported compression: $method")

    data object Truncated : PackedFileException("Unexpected end of packed file")

    data object Corrupt : PackedFileException("Corrupt packed binary payload")
}

/**
 * Envelope framing (`magic` + `version` + compression method + unpacked length + payload)
 * shared with OpenHikes pre-built square files.
 */
object PackedEnvelope {
    const val METHOD_STORED = 0
    const val METHOD_DEFLATE = 1
    const val MAXIMUM_BODY_BYTES = 64 * 1024 * 1024

    fun encode(magic: ByteArray, version: Int, body: ByteArray): ByteArray {
        val deflated = deflateIfSmaller(body)
        val method = if (deflated != null) METHOD_DEFLATE else METHOD_STORED
        val payload = deflated ?: body
        val out = ByteArrayOutputStream(magic.size + 2 + PackedVarint.MAX_BYTES + payload.size)
        out.write(magic)
        out.write(version and BYTE_MASK)
        out.write(method and BYTE_MASK)
        out.write(PackedVarint.encode(body.size.toLong()))
        out.write(payload)
        return out.toByteArray()
    }

    fun decode(file: ByteArray, magic: ByteArray, expectedVersion: Int): ByteArray {
        val reader = PackedReader(file)
        validateHeader(reader, magic, expectedVersion)
        val method = reader.readUnsignedByte()
        val unpackedLength = reader.readCount(limit = MAXIMUM_BODY_BYTES)
        val remainder = reader.remainder()
        return decodeBody(method, remainder, unpackedLength)
    }

    private fun validateHeader(reader: PackedReader, magic: ByteArray, expectedVersion: Int) {
        val actualMagic = reader.readBytes(magic.size)
        if (!actualMagic.contentEquals(magic)) throw PackedFileException.WrongFormat
        val version = reader.readUnsignedByte()
        if (version != expectedVersion) throw PackedFileException.UnsupportedVersion(version)
    }

    private fun decodeBody(method: Int, remainder: ByteArray, unpackedLength: Int): ByteArray = when (method) {
        METHOD_STORED -> {
            if (remainder.size != unpackedLength) throw PackedFileException.Truncated
            remainder
        }
        METHOD_DEFLATE -> inflateExact(remainder, unpackedLength)
        else -> throw PackedFileException.UnsupportedCompression(method)
    }

    private fun deflateIfSmaller(body: ByteArray): ByteArray? {
        if (body.size <= 1) return null
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        return try {
            deflater.setInput(body)
            deflater.finish()
            val buffer = ByteArray(body.size - 1)
            val written = deflater.deflate(buffer)
            if (deflater.finished() && written in 1 until body.size) {
                buffer.copyOf(written)
            } else {
                null
            }
        } finally {
            deflater.end()
        }
    }

    private fun inflateExact(packed: ByteArray, expectedCount: Int): ByteArray =
        inflateExactOrNull(packed, expectedCount) ?: throw PackedFileException.Corrupt

    private fun inflateExactOrNull(packed: ByteArray, expectedCount: Int): ByteArray? {
        if (expectedCount <= 0 || packed.isEmpty()) return null
        val inflater = Inflater(true)
        return try {
            inflater.setInput(packed)
            val output = ByteArray(expectedCount + 1)
            var total = 0
            var stalled = false
            while (!inflater.finished() && total < output.size && !stalled) {
                val read = inflater.inflate(output, total, output.size - total)
                if (read == 0) {
                    stalled = true
                } else {
                    total += read
                }
            }
            if (inflater.finished() && total == expectedCount) output.copyOf(expectedCount) else null
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    private const val BYTE_MASK = 0xFF
}

internal object PackedVarint {
    const val PAYLOAD_BITS = 7
    const val PAYLOAD_MASK = 0x7FL
    const val CONTINUATION_BIT = 0x80
    const val MAX_BYTES = 10

    fun encode(value: Long): ByteArray {
        val out = ByteArrayOutputStream(MAX_BYTES)
        var remaining = value
        while ((remaining ushr PAYLOAD_BITS) != 0L) {
            out.write(((remaining and PAYLOAD_MASK).toInt() or CONTINUATION_BIT))
            remaining = remaining ushr PAYLOAD_BITS
        }
        out.write((remaining and PAYLOAD_MASK).toInt())
        return out.toByteArray()
    }
}

internal class PackedStringTable {
    private val items = ArrayList<String>()
    private val indices = HashMap<String, Long>()

    val all: List<String>
        get() = items

    fun indexOf(value: String): Long = indices.getOrPut(value) {
        val idx = items.size.toLong()
        items.add(value)
        idx
    }
}

internal class PackedWriter {
    private val out = ByteArrayOutputStream()

    fun writeVarint(value: Long) {
        out.write(PackedVarint.encode(value))
    }

    fun writeStep(step: Long) {
        writeVarint((step shl 1) xor (step shr SIGN_SHIFT))
    }

    fun writeStrings(table: PackedStringTable) {
        writeVarint(table.all.size.toLong())
        for (item in table.all) {
            val utf8 = item.encodeToByteArray()
            writeVarint(utf8.size.toLong())
            out.write(utf8)
        }
    }

    fun writeRaw(bytes: ByteArray) {
        out.write(bytes)
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private companion object {
        const val SIGN_SHIFT = 63
    }
}

internal class PackedReader(private val bytes: ByteArray) {
    private var offset: Int = 0

    val isAtEnd: Boolean
        get() = offset == bytes.size

    fun remainder(): ByteArray = bytes.copyOfRange(offset, bytes.size)

    fun readUnsignedByte(): Int {
        if (offset >= bytes.size) throw PackedFileException.Truncated
        return bytes[offset++].toInt() and BYTE_MASK
    }

    fun readBytes(count: Int): ByteArray {
        if (count < 0 || count > bytes.size - offset) throw PackedFileException.Truncated
        val slice = bytes.copyOfRange(offset, offset + count)
        offset += count
        return slice
    }

    fun readVarint(): Long {
        var value = 0L
        for (index in 0 until PackedVarint.MAX_BYTES) {
            val byte = readUnsignedByte()
            val payload = byte.toLong() and PackedVarint.PAYLOAD_MASK
            val shift = index * PackedVarint.PAYLOAD_BITS
            if (index == PackedVarint.MAX_BYTES - 1 && payload > 1L) {
                throw PackedFileException.Corrupt
            }
            value = value or (payload shl shift)
            if ((byte and PackedVarint.CONTINUATION_BIT) == 0) return value
        }
        throw PackedFileException.Corrupt
    }

    fun readStep(): Long {
        val zigzag = readVarint()
        return (zigzag ushr 1) xor -(zigzag and 1L)
    }

    fun readCount(limit: Int? = null): Int {
        val raw = readVarint()
        val maxAllowed = (limit ?: (bytes.size - offset)).toLong()
        if (raw < 0L || raw > maxAllowed) throw PackedFileException.Corrupt
        return raw.toInt()
    }

    fun readStrings(): List<String> {
        val count = readCount()
        return List(count) { readUtf8String() }
    }

    fun readIndexedString(strings: List<String>): String {
        val index = readVarint()
        if (index < 0L || index >= strings.size.toLong()) throw PackedFileException.Corrupt
        return strings[index.toInt()]
    }

    private fun readUtf8String(): String {
        val raw = readBytes(readCount())
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(raw))
                .toString()
        } catch (_: CharacterCodingException) {
            throw PackedFileException.Corrupt
        }
    }

    private companion object {
        const val BYTE_MASK = 0xFF
    }
}
