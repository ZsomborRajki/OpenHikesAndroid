package app.openhikes.core.database

import app.openhikes.core.model.RouteBoundary
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteMotion
import app.openhikes.core.model.RouteProvenance
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class RouteBlobCodecTest {
    @Test
    fun roundTripsOptionalFieldsAndPauseInferredNonPedestrianFlags() {
        val route = listOf(
            RouteCoordinate(
                latitude = 47.718420,
                longitude = 12.831774,
                elevationMeters = 533.5,
                timestampEpochMillis = 1_767_225_600_000L
            ),
            RouteCoordinate(
                latitude = 47.719219,
                longitude = 12.830877,
                elevationMeters = null,
                timestampEpochMillis = null,
                motion = RouteMotion.NON_PEDESTRIAN,
                provenance = RouteProvenance.INFERRED,
                boundary = RouteBoundary.PAUSED
            )
        )
        val decoded = RouteBlobCodec.decode(RouteBlobCodec.encode(route))

        assertThat(decoded).isEqualTo(route)
        assertThat(RouteBlobCodec.decode(ByteArray(0))).isEmpty()
    }

    @Test
    fun chunksLargeRoutesWithinByteBoundAndRoundTripsAcrossChunkBoundaries() {
        val route = List(80_000) { idx ->
            RouteCoordinate(
                latitude = 47.0 + idx * 0.00001,
                longitude = 12.8 + (idx % 100) * 0.00001,
                elevationMeters = 500.0 + (idx % 300),
                timestampEpochMillis = 1_767_225_600_000L + idx * 1_000L,
                boundary = if (idx == 40_000) RouteBoundary.PAUSED else null
            )
        }

        val chunks = RouteBlobCodec.encodeChunked(route)
        assertThat(chunks.size).isGreaterThan(1)
        assertThat(chunks.all { it.size <= RouteBlobCodec.MAX_CHUNK_BYTES }).isTrue()
        assertThat(chunks.sumOf { it.size }).isGreaterThan(2 * 1024 * 1024)
        assertThat(RouteBlobCodec.decodeChunks(chunks)).isEqualTo(route)

        // Verify mid-primitive chunk splits work even with tiny chunk sizes.
        val tinyChunks = RouteBlobCodec.encodeChunked(route.take(50), maxChunkBytes = 17)
        assertThat(tinyChunks.all { it.size <= 17 }).isTrue()
        assertThat(RouteBlobCodec.decodeChunks(tinyChunks)).isEqualTo(route.take(50))

        // Empty route still encodes to one header chunk and decodes cleanly.
        val emptyChunks = RouteBlobCodec.encodeChunked(emptyList())
        assertThat(emptyChunks).hasSize(1)
        assertThat(RouteBlobCodec.decodeChunks(emptyChunks)).isEmpty()
        assertThat(RouteBlobCodec.decodeChunks(emptyList())).isEmpty()
    }

    @Test
    fun throwsOnCorruptBlobAndDecodeOrEmptyFallsBackSafely() {
        val truncated = byteArrayOf(1, 0, 0, 0, 2)
        assertThrows(IllegalArgumentException::class.java) {
            RouteBlobCodec.decode(truncated)
        }
        assertThat(RouteBlobCodec.decodeOrEmpty(truncated)).isEmpty()
        assertThat(RouteBlobCodec.decodeChunksOrEmpty(listOf(truncated))).isEmpty()

        val wrongVersion = byteArrayOf(99, 0, 0, 0, 0)
        assertThrows(IllegalArgumentException::class.java) {
            RouteBlobCodec.decode(wrongVersion)
        }
        assertThat(RouteBlobCodec.decodeOrEmpty(wrongVersion)).isEmpty()
    }
}
