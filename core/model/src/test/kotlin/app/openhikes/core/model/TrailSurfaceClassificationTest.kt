package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrailSurfaceClassificationTest {
    @Test
    fun mapsCommonOsmSurfaceTagsIntoFourSurveyedCategories() {
        val paved = listOf(
            "paved",
            "asphalt",
            "concrete",
            "concrete:lanes",
            "paving_stones",
            "cobblestone",
            "sett",
            "wood",
            "metal",
            "stone"
        )
        for (tag in paved) {
            assertThat(TrailSurface.fromOsm(tag)).isEqualTo(TrailSurface.PAVED)
        }

        val gravel = listOf("gravel", "fine_gravel", "compacted", "pebblestone", "shells")
        for (tag in gravel) {
            assertThat(TrailSurface.fromOsm(tag)).isEqualTo(TrailSurface.GRAVEL)
        }

        val ground = listOf("unpaved", "ground", "dirt", "earth", "grass", "mud", "sand", "snow", "clay")
        for (tag in ground) {
            assertThat(TrailSurface.fromOsm(tag)).isEqualTo(TrailSurface.GROUND)
        }

        val rock = listOf("rock", "bare_rock", "scree", "stepping_stones")
        for (tag in rock) {
            assertThat(TrailSurface.fromOsm(tag)).isEqualTo(TrailSurface.ROCK)
        }
    }

    @Test
    fun parsesLeadingTokenOfSemicolonListsAndIgnoresCaseAndWhitespace() {
        assertThat(TrailSurface.fromOsm("  Asphalt ; gravel  ")).isEqualTo(TrailSurface.PAVED)
        assertThat(TrailSurface.fromOsm("FINE_GRAVEL;dirt")).isEqualTo(TrailSurface.GRAVEL)
        assertThat(TrailSurface.fromOsm("scree;rock")).isEqualTo(TrailSurface.ROCK)
        assertThat(TrailSurface.fromOsm("   ")).isEqualTo(TrailSurface.UNKNOWN)
        assertThat(TrailSurface.fromOsm(";asphalt")).isEqualTo(TrailSurface.UNKNOWN)
        assertThat(TrailSurface.fromOsm(null)).isEqualTo(TrailSurface.UNKNOWN)
    }

    @Test
    fun fallsBackToTracktypeOnlyWhenSurfaceIsMissingOrUnrecognized() {
        assertThat(TrailSurface.fromOsm(surface = null, tracktype = "grade1")).isEqualTo(TrailSurface.PAVED)
        assertThat(TrailSurface.fromOsm(surface = null, tracktype = "grade2")).isEqualTo(TrailSurface.GRAVEL)
        assertThat(TrailSurface.fromOsm(surface = null, tracktype = "GRADE3")).isEqualTo(TrailSurface.GRAVEL)
        assertThat(TrailSurface.fromOsm(surface = null, tracktype = "grade4")).isEqualTo(TrailSurface.GROUND)
        assertThat(TrailSurface.fromOsm(surface = null, tracktype = " grade5 ")).isEqualTo(TrailSurface.GROUND)
        assertThat(TrailSurface.fromOsm(surface = "mystery_material", tracktype = "grade2"))
            .isEqualTo(TrailSurface.GRAVEL)
        // Explicit surface wins over conflicting tracktype.
        assertThat(TrailSurface.fromOsm(surface = "dirt", tracktype = "grade1")).isEqualTo(TrailSurface.GROUND)
        assertThat(TrailSurface.fromOsm(surface = null, tracktype = "grade9")).isEqualTo(TrailSurface.UNKNOWN)
    }

    @Test
    fun surveyedFlagsAndDisplayOrderKeepFourSurveyedCategoriesFirst() {
        val surveyed = TrailSurface.displayOrdering.filter { it.isSurveyed }
        assertThat(surveyed).containsExactly(
            TrailSurface.PAVED,
            TrailSurface.GRAVEL,
            TrailSurface.GROUND,
            TrailSurface.ROCK
        ).inOrder()
        assertThat(TrailSurface.UNKNOWN.isSurveyed).isFalse()
        assertThat(TrailSurface.UNMAPPED.isSurveyed).isFalse()
    }
}
