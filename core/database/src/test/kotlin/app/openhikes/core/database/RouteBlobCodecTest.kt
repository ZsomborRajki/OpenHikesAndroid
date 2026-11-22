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
    fun throwsOnCorruptBlobAndDecodeOrEmptyFallsBackSafely() {
        val truncated = byteArrayOf(1, 0, 0, 0, 2)
        assertThrows(IllegalArgumentException::class.java) {
            RouteBlobCodec.decode(truncated)
        }
        assertThat(RouteBlobCodec.decodeOrEmpty(truncated)).isEmpty()

        val wrongVersion = byteArrayOf(99, 0, 0, 0, 0)
        assertThrows(IllegalArgumentException::class.java) {
            RouteBlobCodec.decode(wrongVersion)
        }
        assertThat(RouteBlobCodec.decodeOrEmpty(wrongVersion)).isEmpty()
    }
}
