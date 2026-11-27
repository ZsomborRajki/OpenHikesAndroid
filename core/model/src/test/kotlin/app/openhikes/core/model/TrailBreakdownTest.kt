package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrailBreakdownTest {
    @Test
    fun ordersSurveyedFirstThenLongestThenDisplayOrderAndNormalizesFractions() {
        val breakdown = TrailBreakdown.fromMeters(
            mapOf(
                TrailSurface.UNMAPPED to 900.0,
                TrailSurface.GROUND to 200.0,
                TrailSurface.PAVED to 200.0,
                TrailSurface.GRAVEL to 500.0,
                TrailSurface.ROCK to 0.0,
                TrailSurface.UNKNOWN to Double.NaN
            )
        )

        assertThat(breakdown.shares.map { it.category }).containsExactly(
            TrailSurface.GRAVEL,
            TrailSurface.PAVED,
            TrailSurface.GROUND,
            TrailSurface.UNMAPPED
        ).inOrder()
        assertThat(breakdown.dominant?.category).isEqualTo(TrailSurface.GRAVEL)
        assertThat(breakdown.totalMeters).isWithin(1e-6).of(1_800.0)
        assertThat(breakdown.shares.sumOf { it.fraction }).isWithin(1e-9).of(1.0)
        assertThat(breakdown.surveyedFraction).isWithin(1e-9).of(0.5)
    }

    @Test
    fun storedMetersRoundTripAndIgnoreUnknownFutureKeys() {
        val storedSurface = mapOf(
            "gravel" to 600.0,
            "paved" to 400.0,
            "future_boardwalk_v2" to 250.0,
            "rock" to -10.0
        )
        val surface = TrailBreakdown.fromStoredSurfaceMeters(storedSurface)
        assertThat(surface).isNotNull()
        assertThat(surface!!.shares.map { it.category }).containsExactly(
            TrailSurface.GRAVEL,
            TrailSurface.PAVED
        ).inOrder()
        assertThat(surface.totalMeters).isWithin(1e-6).of(1_000.0)

        val onlyUnknownFutureKeys = TrailBreakdown.fromStoredDifficultyMeters(mapOf("future_t7" to 500.0))
        assertThat(onlyUnknownFutureKeys).isNull()
    }

    @Test
    fun hikeNeedsTrailBreakdownsUntilBothHalvesAreStored() {
        val base = Hike(
            id = HikeId("h1"),
            title = "Ridge",
            distanceMeters = 1_000.0,
            dateEpochMillis = 0L
        )
        assertThat(base.needsTrailBreakdowns).isTrue()
        assertThat(base.surfaceBreakdown).isNull()
        assertThat(base.difficultyBreakdown).isNull()

        val surfaceOnly = base.copy(surfaceMetersByCategory = mapOf("gravel" to 1_000.0))
        assertThat(surfaceOnly.needsTrailBreakdowns).isTrue()
        assertThat(surfaceOnly.surfaceBreakdown?.dominant?.category).isEqualTo(TrailSurface.GRAVEL)
        assertThat(surfaceOnly.difficultyBreakdown).isNull()

        val both = surfaceOnly.copy(difficultyMetersByGrade = mapOf("hiking" to 1_000.0))
        assertThat(both.needsTrailBreakdowns).isFalse()
        assertThat(both.difficultyBreakdown?.dominant?.category).isEqualTo(TrailDifficulty.HIKING)
    }
}
