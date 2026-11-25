package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MapAttributionTest {
    @Test
    fun openFreeMapIncludesOpenMapTilesOpenStreetMapAndWaymarkedWhenHikingRoutesShown() {
        val attribution = MapAttribution.forOpenFreeMap(showsHikingRoutes = true)

        assertThat(attribution.credits.map { it.compactTitle }).containsExactly(
            "OpenFreeMap",
            "OpenMapTiles",
            "OpenStreetMap",
            "Waymarked Trails"
        ).inOrder()
        assertThat(attribution.compactText)
            .isEqualTo("© OpenFreeMap, OpenMapTiles, OpenStreetMap, Waymarked Trails")
        assertThat(attribution.plainText).contains("Maps © OpenFreeMap")
        assertThat(attribution.plainText).contains("© OpenMapTiles")
        assertThat(attribution.plainText).contains("Data © OpenStreetMap contributors")
        assertThat(attribution.plainText).contains("Hiking routes © Waymarked Trails")
        assertThat(attribution.credits.first { it.compactTitle == "OpenMapTiles" }.url)
            .isEqualTo("https://www.openmaptiles.org/")
    }

    @Test
    fun openFreeMapOmitsWaymarkedWhenHikingRoutesHidden() {
        val attribution = MapAttribution.forOpenFreeMap(showsHikingRoutes = false)

        assertThat(attribution.credits.map { it.compactTitle }).containsExactly(
            "OpenFreeMap",
            "OpenMapTiles",
            "OpenStreetMap"
        ).inOrder()
        assertThat(attribution.compactText)
            .isEqualTo("© OpenFreeMap, OpenMapTiles, OpenStreetMap")
    }

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
    }

    @Test
    fun rasterAndCommercialStylesCarryRequiredCreditsAndOptionalHikingRoutes() {
        assertThat(MapAttribution.forStyle(MapStyleId.OSM_RASTER, showsHikingRoutes = false).compactText)
            .isEqualTo("© OpenStreetMap")
        assertThat(MapAttribution.forStyle(MapStyleId.OSM_RASTER, showsHikingRoutes = true).compactText)
            .isEqualTo("© OpenStreetMap, Waymarked Trails")

        assertThat(
            MapAttribution.forStyle(MapStyleId.STADIA_OUTDOORS, showsHikingRoutes = false).credits.map {
                it.compactTitle
            }
        )
            .containsExactly("Stadia Maps", "OpenMapTiles", "OpenStreetMap")
            .inOrder()
        assertThat(
            MapAttribution.forStyle(MapStyleId.STAMEN_TERRAIN, showsHikingRoutes = false).credits.map {
                it.compactTitle
            }
        )
            .containsExactly("Stadia Maps", "Stamen Design", "OpenMapTiles", "OpenStreetMap")
            .inOrder()
        assertThat(
            MapAttribution.forStyle(MapStyleId.THUNDERFOREST_OUTDOORS, showsHikingRoutes = false).credits.map {
                it.compactTitle
            }
        ).containsExactly("Thunderforest", "OpenStreetMap").inOrder()
    }

    @Test
    fun mapModesAttributionMergesSelectedUsableCardsAndSampleRouteCreditsWithoutDuplicates() {
        val usable = MapStyleCatalog.usableStyles(MapProviderKeys.Empty)
        val attribution = MapAttribution.forMapModes(
            selected = MapStyleCatalog.openHikes,
            showsHikingRoutes = false,
            picturedStyles = usable
        )

        assertThat(attribution.credits.map { it.compactTitle }).containsExactly(
            "OpenStreetMap",
            "ESA WorldCover",
            "OpenFreeMap",
            "OpenMapTiles",
            "Waymarked Trails"
        ).inOrder()
        assertThat(attribution.credits.map { it.url }).containsNoDuplicates()
    }
}
