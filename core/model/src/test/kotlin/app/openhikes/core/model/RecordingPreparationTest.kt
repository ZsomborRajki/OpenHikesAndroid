package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecordingPreparationTest {
    private val baseMillis = 1_767_225_600_000L

    @Test
    fun normalizesOrderDeduplicatesWidgetFixesAndPreparesFinalStats() {
        val points = listOf(
            RecordingPoint(47.71900, 12.83100, baseMillis + 20_000L, 6.0, elevationMeters = 512.0),
            // Duplicate widget fix within 5 s of foreground fix -> dropped
            RecordingPoint(
                latitude = 47.71810,
                longitude = 12.83100,
                timestampEpochMillis = baseMillis + 2_000L,
                horizontalAccuracyMeters = 4.0,
                flags = RecordingPointFlags.WIDGET_SOURCED
            ),
            // Distinct widget fix outside 5 s window -> kept
            RecordingPoint(
                latitude = 47.71850,
                longitude = 12.83100,
                timestampEpochMillis = baseMillis + 10_000L,
                horizontalAccuracyMeters = 8.0,
                elevationMeters = 506.0,
                flags = RecordingPointFlags.WIDGET_SOURCED
            ),
            RecordingPoint(47.71800, 12.83100, baseMillis, 5.0, elevationMeters = 500.0)
        )

        val normalized = RecordingPreparation.normalizedPoints(points)
        assertThat(normalized.map { it.timestampEpochMillis })
            .containsExactly(baseMillis, baseMillis + 10_000L, baseMillis + 20_000L)
            .inOrder()

        val result = RecordingPreparation.prepare(points, baseMillis)
        assertThat(result).isInstanceOf(RecordingPreparationResult.Success::class.java)
        val prepared = (result as RecordingPreparationResult.Success).prepared
        assertThat(prepared.route).hasSize(3)
        assertThat(prepared.distanceMeters).isGreaterThan(100.0)
        assertThat(prepared.recordedSeconds).isWithin(0.01).of(20.0)
        assertThat(prepared.elevationGainMeters).isWithin(0.01).of(12.0)

        assertThat(RecordingPreparation.prepare(listOf(points.last()), baseMillis))
            .isEqualTo(RecordingPreparationResult.TooShort)
    }

    @Test
    fun requiresAtLeastOneUnpausedDrawableLegToPrepareOrOfferSave() {
        val first = RecordingPoint(47.71800, 12.83100, baseMillis, 5.0)
        val resumedElsewhere = RecordingPoint(
            latitude = 47.71950,
            longitude = 12.83100,
            timestampEpochMillis = baseMillis + 60_000L,
            horizontalAccuracyMeters = 5.0,
            flags = RecordingPointFlags.RESUMED
        )
        val separatedPair = listOf(first, resumedElsewhere)

        assertThat(RecordingPreparation.hasDrawableLeg(separatedPair)).isFalse()
        assertThat(RecordingPreparation.hasDrawableRouteLeg(separatedPair.map { it.toRouteCoordinate() })).isFalse()
        assertThat(
            RecordingPreparation.prepare(separatedPair, baseMillis)
        ).isEqualTo(RecordingPreparationResult.TooShort)
        assertThat(
            RecordingStopStep.resolve(
                hasDrawableLeg = RecordingPreparation.hasDrawableLeg(separatedPair),
                holdsKeepsakes = false
            )
        ).isEqualTo(RecordingStopStep.END_EMPTY)

        val continuedLeg = separatedPair + RecordingPoint(47.72010, 12.83100, baseMillis + 70_000L, 5.0)
        assertThat(RecordingPreparation.hasDrawableLeg(continuedLeg)).isTrue()
        assertThat(RecordingPreparation.hasDrawableRouteLeg(continuedLeg.map { it.toRouteCoordinate() })).isTrue()
        assertThat(RecordingPreparation.prepare(continuedLeg, baseMillis))
            .isInstanceOf(RecordingPreparationResult.Success::class.java)
    }
}
