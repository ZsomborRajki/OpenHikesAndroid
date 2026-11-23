package app.openhikes.core.location

import app.openhikes.core.model.RecordingPoint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Failure raised when reading or mutating [TrackJournal] files.
 */
sealed class TrackJournalException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidHeader(message: String) : TrackJournalException(message)

    class UnsupportedVersion(val version: Int) : TrackJournalException("Unsupported journal version: $version")

    class Io(message: String, cause: Throwable? = null) : TrackJournalException(message, cause)
}

internal data class DecodedJournalHeader(val sessionUuid: UUID, val startedAtEpochMillis: Long)

/**
 * Little-endian binary codec for the `recording.otrk` fixed-width record journal (64-byte header
 * followed by 44-byte track point records).
 */
internal object TrackJournalCodec {
    const val HEADER_BYTE_COUNT = 64
    const val RECORD_BYTE_COUNT = 44
    const val FORMAT_VERSION: Short = 1
    const val TIMESTAMP_MATCH_TOLERANCE_MILLIS = 5L

    private val MAGIC = byteArrayOf(0x4F, 0x54, 0x52, 0x4B) // OTRK
    private const val MILLIS_PER_SECOND = 1000.0
    private const val VERSION_OFFSET = 4
    private const val UUID_OFFSET = 8
    private const val STARTED_AT_OFFSET = 24
    private const val UNSIGNED_SHORT_MASK = 0xFFFF

    fun sessionUuid(sessionId: String): UUID = runCatching { UUID.fromString(sessionId) }.getOrElse {
        UUID.nameUUIDFromBytes(sessionId.toByteArray(Charsets.UTF_8))
    }

    fun pointCount(fileSize: Long): Int {
        if (fileSize < HEADER_BYTE_COUNT) return 0
        return ((fileSize - HEADER_BYTE_COUNT) / RECORD_BYTE_COUNT).toInt()
    }

    fun encodeHeader(sessionId: String, startedAtEpochMillis: Long): ByteArray {
        val uuid = sessionUuid(sessionId)
        val buffer = ByteBuffer.allocate(HEADER_BYTE_COUNT).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC)
        buffer.putShort(FORMAT_VERSION)
        buffer.putShort(0)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        buffer.putDouble(startedAtEpochMillis / MILLIS_PER_SECOND)
        return buffer.array()
    }

    fun decodeHeader(bytes: ByteArray): DecodedJournalHeader {
        val buffer = validateHeaderBuffer(bytes)
        val msb = buffer.getLong(UUID_OFFSET)
        val lsb = buffer.getLong(UUID_OFFSET + Long.SIZE_BYTES)
        val startedSeconds = buffer.getDouble(STARTED_AT_OFFSET)
        if (!startedSeconds.isFinite()) {
            throw TrackJournalException.InvalidHeader("Non-finite start timestamp in journal header")
        }
        return DecodedJournalHeader(
            sessionUuid = UUID(msb, lsb),
            startedAtEpochMillis = (startedSeconds * MILLIS_PER_SECOND).roundToLong()
        )
    }

    private fun validateHeaderBuffer(bytes: ByteArray): ByteBuffer {
        verifyHeaderMagic(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version = buffer.getShort(VERSION_OFFSET).toInt() and UNSIGNED_SHORT_MASK
        if (version != FORMAT_VERSION.toInt()) {
            throw TrackJournalException.UnsupportedVersion(version)
        }
        return buffer
    }

    private fun verifyHeaderMagic(bytes: ByteArray) {
        if (bytes.size < HEADER_BYTE_COUNT) {
            throw TrackJournalException.InvalidHeader("Journal header truncated (${bytes.size} bytes)")
        }
        if (MAGIC.indices.any { index -> bytes[index] != MAGIC[index] }) {
            throw TrackJournalException.InvalidHeader("Missing OTRK header magic")
        }
    }

    fun headerMatchesMetadata(header: DecodedJournalHeader, sessionId: String, startedAtEpochMillis: Long): Boolean =
        header.sessionUuid == sessionUuid(sessionId) &&
            abs(header.startedAtEpochMillis - startedAtEpochMillis) <= TIMESTAMP_MATCH_TOLERANCE_MILLIS

    fun encodePoint(point: RecordingPoint): ByteArray {
        val buffer = ByteBuffer.allocate(RECORD_BYTE_COUNT).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putDouble(point.latitude)
        buffer.putDouble(point.longitude)
        buffer.putDouble(point.timestampEpochMillis / MILLIS_PER_SECOND)
        buffer.putFloat(point.elevationMeters?.toFloat() ?: Float.NaN)
        buffer.putFloat(point.horizontalAccuracyMeters.toFloat())
        buffer.putFloat(point.courseDegrees?.toFloat() ?: Float.NaN)
        buffer.putFloat(point.speedMps?.toFloat() ?: Float.NaN)
        buffer.putInt(point.flags)
        return buffer.array()
    }

    fun encodePoints(points: List<RecordingPoint>): ByteArray {
        val out = ByteArray(points.size * RECORD_BYTE_COUNT)
        for (index in points.indices) {
            val encoded = encodePoint(points[index])
            System.arraycopy(encoded, 0, out, index * RECORD_BYTE_COUNT, RECORD_BYTE_COUNT)
        }
        return out
    }

    fun decodeRecords(bytes: ByteArray): List<RecordingPoint> {
        val count = pointCount(bytes.size.toLong())
        if (count == 0) return emptyList()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val result = ArrayList<RecordingPoint>(count)
        for (index in 0 until count) {
            buffer.position(HEADER_BYTE_COUNT + index * RECORD_BYTE_COUNT)
            val lat = buffer.getDouble()
            val lon = buffer.getDouble()
            val timestampSeconds = buffer.getDouble()
            val elevation = buffer.getFloat().takeIf { it.isFinite() }?.toDouble()
            val accuracy = buffer.getFloat().takeIf { it.isFinite() }?.toDouble() ?: 0.0
            val course = buffer.getFloat().takeIf { it.isFinite() }?.toDouble()
            val speed = buffer.getFloat().takeIf { it.isFinite() }?.toDouble()
            val flags = buffer.getInt()
            if (lat.isFinite() && lon.isFinite() && timestampSeconds.isFinite()) {
                result.add(
                    RecordingPoint(
                        latitude = lat,
                        longitude = lon,
                        timestampEpochMillis = (timestampSeconds * MILLIS_PER_SECOND).roundToLong(),
                        horizontalAccuracyMeters = accuracy,
                        elevationMeters = elevation,
                        courseDegrees = course,
                        speedMps = speed,
                        flags = flags
                    )
                )
            }
        }
        return result
    }
}
