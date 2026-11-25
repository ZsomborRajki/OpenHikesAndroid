package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MapStyleCatalogTest {
    @Test
    fun openHikesIsDefaultAndOsmProvidersForbidBulkDownload() {
        assertThat(MapStyleCatalog.default).isEqualTo(MapStyleCatalog.openHikes)
        assertThat(MapStyleCatalog.openHikes.supportsOfflineCountries).isTrue()
        assertThat(MapStyleCatalog.openHikes.supportsBulkDownload).isFalse()
        assertThat(MapStyleCatalog.openStreetMapRaster.supportsBulkDownload).isFalse()
        assertThat(MapStyleCatalog.thunderforestOutdoors.supportsBulkDownload).isFalse()
    }

    @Test
    fun everyCatalogStyleHasHttpsAttributionsAndValidZoomBounds() {
        assertThat(MapStyleCatalog.all.map { it.id }).containsExactly(
            MapStyleId.OPENHIKES,
            MapStyleId.OPENFREEMAP,
            MapStyleId.OSM_RASTER,
            MapStyleId.STADIA_OUTDOORS,
            MapStyleId.STAMEN_TERRAIN,
            MapStyleId.THUNDERFOREST_OUTDOORS
        ).inOrder()

        for (style in MapStyleCatalog.all) {
            assertThat(style.id).isNotEmpty()
            assertThat(style.maximumZoom).isAtLeast(15)
            assertThat(style.attribution.credits).isNotEmpty()
            for (credit in style.attribution.credits) {
                assertThat(credit.url).startsWith("https://")
            }
        }
    }

    @Test
    fun renderableFallsBackToOpenHikesWhenUnknownOrMissingApiKey() {
        assertThat(MapStyleCatalog.renderable(null)).isEqualTo(MapStyleCatalog.openHikes)
        assertThat(MapStyleCatalog.renderable("")).isEqualTo(MapStyleCatalog.openHikes)
        assertThat(MapStyleCatalog.renderable("unknown-style")).isEqualTo(MapStyleCatalog.openHikes)

        assertThat(MapStyleCatalog.renderable(MapStyleId.OPENFREEMAP, MapProviderKeys.Empty))
            .isEqualTo(MapStyleCatalog.openFreeMap)
        assertThat(MapStyleCatalog.renderable(MapStyleId.OSM_RASTER, MapProviderKeys.Empty))
            .isEqualTo(MapStyleCatalog.openStreetMapRaster)
        assertThat(MapStyleCatalog.renderable(MapStyleId.STADIA_OUTDOORS, MapProviderKeys.Empty))
            .isEqualTo(MapStyleCatalog.openHikes)
        assertThat(MapStyleCatalog.renderable(MapStyleId.STAMEN_TERRAIN, MapProviderKeys.Empty))
            .isEqualTo(MapStyleCatalog.openHikes)
        assertThat(MapStyleCatalog.renderable(MapStyleId.THUNDERFOREST_OUTDOORS, MapProviderKeys.Empty))
            .isEqualTo(MapStyleCatalog.openHikes)
    }

    @Test
    fun providerKeysUnlockMatchingStylesAndResolveUrlTemplates() {
        val keys = MapProviderKeys(stadiaApiKey = " stadia-secret ", thunderforestApiKey = "tf-secret")

        assertThat(MapStyleCatalog.renderable(MapStyleId.STADIA_OUTDOORS, keys))
            .isEqualTo(MapStyleCatalog.stadiaOutdoors)
        assertThat(MapStyleCatalog.renderable(MapStyleId.STAMEN_TERRAIN, keys))
            .isEqualTo(MapStyleCatalog.stamenTerrain)
        assertThat(MapStyleCatalog.renderable(MapStyleId.THUNDERFOREST_OUTDOORS, keys))
            .isEqualTo(MapStyleCatalog.thunderforestOutdoors)

        assertThat(MapStyleCatalog.stadiaOutdoors.resolvedUrl(keys))
            .isEqualTo("https://tiles.stadiamaps.com/tiles/outdoors/{z}/{x}/{y}.png?api_key=stadia-secret")
        assertThat(MapStyleCatalog.thunderforestOutdoors.resolvedUrl(keys))
            .isEqualTo("https://tile.thunderforest.com/outdoors/{z}/{x}/{y}.png?apikey=tf-secret")
        assertThat(MapStyleCatalog.stadiaOutdoors.resolvedUrl(MapProviderKeys.Empty)).isNull()
        assertThat(keys.hasMissingKeys()).isFalse()
        assertThat(MapProviderKeys.Empty.hasMissingKeys()).isTrue()
    }
}
