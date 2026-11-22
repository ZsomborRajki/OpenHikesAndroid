package app.openhikes.core.database

import app.openhikes.core.model.RouteBoundary
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteMotion
import app.openhikes.core.model.RouteProvenance
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * Compact deterministic binary serializer for [List]<[RouteCoordinate]> stored in `hike_routes`.
 */
object RouteBlobCodec {
    private const val FORMAT_VERSION: Int = 1
    private const val FLAG_HAS_ELEVATION = 1 shl 0
    private const val FLAG_HAS_TIMESTAMP = 1 shl 1
    private const val FLAG_IS_PAUSED = 1 shl 2
    private const val FLAG_IS_INFERRED = 1 shl 3
    private const val FLAG_IS_NON_PEDESTRIAN = 1 shl 4
    private const val ESTIMATED_BYTES_PER_POINT = 32
    private const val HEADER_BYTES = 5
    private const val MAX_DECODED_POINT_COUNT = 2_000_000

    fun encode(route: List<RouteCoordinate>): ByteArray {
        val buffer = ByteArrayOutputStream(HEADER_BYTES + route.size * ESTIMATED_BYTES_PER_POINT)
        DataOutputStream(buffer).use { out ->
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
        return buffer.toByteArray()
    }

    fun decode(bytes: ByteArray): List<RouteCoordinate> {
        if (bytes.isEmpty()) return emptyList()
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val version = input.readUnsignedByte()
                require(version == FORMAT_VERSION) { "Unsupported route blob version: $version" }
                val count = input.readInt()
                require(count in 0..MAX_DECODED_POINT_COUNT) { "Invalid route point count: $count" }
                val points = ArrayList<RouteCoordinate>(count)
                repeat(count) {
                    val lat = input.readDouble()
                    val lon = input.readDouble()
                    val flags = input.readUnsignedByte()
                    val elevation = if ((flags and FLAG_HAS_ELEVATION) != 0) input.readDouble() else null
                    val timestamp = if ((flags and FLAG_HAS_TIMESTAMP) != 0) input.readLong() else null
                    val boundary = if ((flags and FLAG_IS_PAUSED) != 0) RouteBoundary.PAUSED else null
                    val provenance = if ((flags and FLAG_IS_INFERRED) != 0) RouteProvenance.INFERRED else null
                    val motion = if ((flags and FLAG_IS_NON_PEDESTRIAN) != 0) RouteMotion.NON_PEDESTRIAN else null
                    points.add(
                        RouteCoordinate(
                            latitude = lat,
                            longitude = lon,
                            elevationMeters = elevation,
                            timestampEpochMillis = timestamp,
                            motion = motion,
                            provenance = provenance,
                            boundary = boundary
                        )
                    )
                }
                return points
            }
        } catch (eof: EOFException) {
            throw IllegalArgumentException("Truncated route blob", eof)
        }
    }

    fun decodeOrEmpty(bytes: ByteArray): List<RouteCoordinate> = runCatching { decode(bytes) }.getOrDefault(emptyList())
}
