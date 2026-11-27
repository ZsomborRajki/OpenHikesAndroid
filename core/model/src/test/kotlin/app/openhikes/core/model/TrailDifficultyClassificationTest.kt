package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrailDifficultyClassificationTest {
    @Test
    fun mapsAllSixSacGradesWithOneToOneRouteShadeProgression() {
        val expected = listOf(
            "hiking" to (TrailDifficulty.HIKING to RouteShade.EASIEST),
            "mountain_hiking" to (TrailDifficulty.MOUNTAIN_HIKING to RouteShade.EASY),
            "demanding_mountain_hiking" to (TrailDifficulty.DEMANDING_MOUNTAIN_HIKING to RouteShade.MODERATE),
            "alpine_hiking" to (TrailDifficulty.ALPINE_HIKING to RouteShade.HARD),
            "demanding_alpine_hiking" to (TrailDifficulty.DEMANDING_ALPINE_HIKING to RouteShade.HARDER),
            "difficult_alpine_hiking" to (TrailDifficulty.DIFFICULT_ALPINE_HIKING to RouteShade.HARDEST)
        )
        for ((tag, pair) in expected) {
            val parsed = TrailDifficulty.fromSacScale(tag)
            assertThat(parsed).isEqualTo(pair.first)
            assertThat(parsed.shade).isEqualTo(pair.second)
            assertThat(parsed.isSurveyed).isTrue()
        }
    }

    @Test
    fun trimsCaseAndMapsMissingOrUnknownSacValuesToUnknown() {
        assertThat(TrailDifficulty.fromSacScale("  MOUNTAIN_HIKING  ")).isEqualTo(TrailDifficulty.MOUNTAIN_HIKING)
        assertThat(TrailDifficulty.fromSacScale(null)).isEqualTo(TrailDifficulty.UNKNOWN)
        assertThat(TrailDifficulty.fromSacScale("")).isEqualTo(TrailDifficulty.UNKNOWN)
        assertThat(TrailDifficulty.fromSacScale("strolling")).isEqualTo(TrailDifficulty.UNKNOWN)
        assertThat(TrailDifficulty.UNKNOWN.shade).isNull()
        assertThat(TrailDifficulty.UNMAPPED.shade).isNull()
        assertThat(TrailDifficulty.UNKNOWN.isSurveyed).isFalse()
        assertThat(TrailDifficulty.UNMAPPED.isSurveyed).isFalse()
    }
}
