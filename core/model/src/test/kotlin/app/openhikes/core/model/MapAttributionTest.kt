package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MapAttributionTest {
    @Test
    fun openHikesIncludesOsmWorldCoverAndWaymarkedWhenHikingRoutesShown() {
        val attribution = MapAttribution.forOpenHikes(showsHikingRoutes = true)

        assertThat(attribution.credits.map { it.compactTitle }).containsExactly(
            "OpenStreetMap",
            "ESA WorldCover",
            "Waymarked Trails"
        ).inOrder()
        assertThat(attribution.compactText)
            .isEqualTo("© OpenStreetMap, ESA WorldCover, Waymarked Trails")
        assertThat(attribution.plainText)
            .contains("Data © OpenStreetMap contributors")
        assertThat(attribution.plainText)
            .contains("Land cover © ESA WorldCover")
        assertThat(attribution.plainText)
            .contains("Hiking routes © Waymarked Trails")
    }

    @Test
    fun openHikesOmitsWaymarkedWhenHikingRoutesHidden() {
        val attribution = MapAttribution.forOpenHikes(showsHikingRoutes = false)

        assertThat(attribution.credits.map { it.compactTitle }).containsExactly(
            "OpenStreetMap",
            "ESA WorldCover"
        ).inOrder()
        assertThat(attribution.compactText).isEqualTo("© OpenStreetMap, ESA WorldCover")
    }
}
