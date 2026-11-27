package app.openhikes.core.common.elevation

import app.openhikes.core.common.routing.PackedEnvelope
import app.openhikes.core.common.routing.PackedFileException
import app.openhikes.core.common.routing.PackedReader
import app.openhikes.core.common.routing.PackedVarint
import app.openhikes.core.model.ElevationSquareGrid
import java.io.ByteArrayOutputStream

/**
 * Pure-JVM codec for OpenHikes `OHEL` v1 Copernicus DEM elevation square files.
 *
 * Each post stores its whole-meter height (or [UNKNOWN] = [Short.MIN_VALUE]) as a 16-bit wrapping
 * residual from the plane through its west, north, and north-west neighbors, zigzag-encoded and
 * split into two byte planes (all low bytes followed by all high bytes) inside a [PackedEnvelope].
 */
class ElevationSquareFile(val rows: Int, val columns: Int, val heights: ShortArray) {
    init {
        require(rows > 0 && columns > 0 && heights.size == rows * columns) {
            "ElevationSquareFile must hold rows * columns posts"
        }
    }

    /**
     * Height in meters at row-major [index], or `null` when that post is [UNKNOWN].
     */
    fun heightAt(index: Int): Double? {
        val raw = heights[index]
        return if (raw == UNKNOWN) null else raw.toDouble()
    }

    /**
     * Whether every post in this square sits at 0 m sea level.
     */
    val isSeaLevel: Boolean
        get() = heights.all { it == SEA_LEVEL }

    fun encode(): ByteArray = PackedEnvelope.encode(
        magic = MAGIC,
        version = FORMAT_VERSION,
        body = encodeBody()
    )

    fun encodeBody(): ByteArray {
        val count = heights.size
        val lowHighPlanes = ByteArray(count * PLANE_COUNT)
        for (index in 0 until count) {
            val residual = (heights[index].toInt() - predicted(index, heights, columns)).toShort().toInt()
            val zigzag = ((residual shl 1) xor (residual shr SIGN_SHIFT)) and UNSIGNED_SHORT_MASK
            lowHighPlanes[index] = (zigzag and BYTE_MASK).toByte()
            lowHighPlanes[count + index] = ((zigzag ushr BYTE_BITS) and BYTE_MASK).toByte()
        }
        val out = ByteArrayOutputStream(PackedVarint.MAX_BYTES * 2 + lowHighPlanes.size)
        out.write(PackedVarint.encode(rows.toLong()))
        out.write(PackedVarint.encode(columns.toLong()))
        out.write(lowHighPlanes)
        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ElevationSquareFile) return false
        return rows == other.rows && columns == other.columns && heights.contentEquals(other.heights)
    }

    override fun hashCode(): Int {
        var result = rows
        result = 31 * result + columns
        result = 31 * result + heights.contentHashCode()
        return result
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val UNKNOWN: Short = Short.MIN_VALUE
        private const val SEA_LEVEL: Short = 0
        private const val PLANE_COUNT = 2
        private const val BYTE_BITS = 8
        private const val BYTE_MASK = 0xFF
        private const val UNSIGNED_SHORT_MASK = 0xFFFF
        private const val SIGN_SHIFT = 15
        private const val MAXIMUM_SIDE = ElevationSquareGrid.POSTS_PER_DEGREE_LATITUDE
        internal val MAGIC: ByteArray = "OHEL".encodeToByteArray()

        fun decode(bytes: ByteArray): ElevationSquareFile {
            val body = PackedEnvelope.decode(
                file = bytes,
                magic = MAGIC,
                expectedVersion = FORMAT_VERSION
            )
            val reader = PackedReader(body)
            val rows = reader.readCount(limit = MAXIMUM_SIDE)
            val columns = reader.readCount(limit = MAXIMUM_SIDE)
            if (rows <= 0 || columns <= 0) throw PackedFileException.Corrupt
            val countLong = rows.toLong() * columns.toLong()
            val expectedPlaneBytes = countLong * PLANE_COUNT
            val planes = reader.remainder()
            if (expectedPlaneBytes != planes.size.toLong()) throw PackedFileException.Corrupt
            val count = countLong.toInt()
            val heights = ShortArray(count)
            for (index in 0 until count) {
                val low = planes[index].toInt() and BYTE_MASK
                val high = planes[count + index].toInt() and BYTE_MASK
                val zigzag = low or (high shl BYTE_BITS)
                val residual = ((zigzag ushr 1) xor -(zigzag and 1)).toShort().toInt()
                heights[index] = (residual + predicted(index, heights, columns)).toShort()
            }
            return ElevationSquareFile(rows = rows, columns = columns, heights = heights)
        }

        private fun predicted(index: Int, posts: ShortArray, columns: Int): Int {
            val column = index % columns
            val isFirstRow = index < columns
            val isFirstColumn = column == 0
            return when {
                isFirstRow && isFirstColumn -> 0
                isFirstRow -> posts[index - 1].toInt()
                isFirstColumn -> posts[index - columns].toInt()
                else -> (
                    posts[index - 1].toInt() +
                        posts[index - columns].toInt() -
                        posts[index - columns - 1].toInt()
                    ).toShort().toInt()
            }
        }
    }
}
