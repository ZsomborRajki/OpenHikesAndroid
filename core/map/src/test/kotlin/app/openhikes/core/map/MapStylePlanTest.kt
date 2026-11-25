package app.openhikes.core.map

import app.openhikes.core.model.MapProviderKeys
import app.openhikes.core.model.MapStyleCatalog
import app.openhikes.core.model.MapStyleId
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.maplibre.compose.style.BaseStyle

class MapStylePlanTest {
    @Test
    fun openHikesUsesCustomVectorLayersOfflineAndOnline() {
        val offlinePlan = resolveMapStylePlan(
            overlayState = MapOverlayState(mapStyleId = MapStyleId.OPENHIKES, showsHikingRoutes = true),
            allowRemoteRequests = false,
            hasLoadedRemoteStyle = false
        )
        assertThat(offlinePlan.usesOpenHikesVector).isTrue()
        assertThat(offlinePlan.baseStyle).isEqualTo(BaseStyle.Empty)
        assertThat(offlinePlan.baseRasterTileUrl).isNull()
        assertThat(offlinePlan.hikingRoutesTileUrl).isNull()

        val onlinePlan = resolveMapStylePlan(
            overlayState = MapOverlayState(mapStyleId = MapStyleId.OPENHIKES, showsHikingRoutes = true),
            allowRemoteRequests = true,
            hasLoadedRemoteStyle = false
        )
        assertThat(onlinePlan.usesOpenHikesVector).isTrue()
        assertThat(onlinePlan.hikingRoutesTileUrl).isEqualTo(WAYMARKED_HIKING_TILES_URL)
    }

    @Test
    fun openFreeMapUsesRemoteStyleWhenOnlineAndEmptyWhenOffline() {
        val online = resolveMapStylePlan(
            overlayState = MapOverlayState(mapStyleId = MapStyleId.OPENFREEMAP, showsHikingRoutes = false),
            allowRemoteRequests = true,
            hasLoadedRemoteStyle = false
        )
        assertThat(online.usesOpenHikesVector).isFalse()
        assertThat(online.baseStyle).isEqualTo(BaseStyle.Uri(OPENFREEMAP_LIBERTY_STYLE_URL))
        assertThat(online.baseRasterTileUrl).isNull()
        assertThat(online.hikingRoutesTileUrl).isNull()

        val offline = resolveMapStylePlan(
            overlayState = MapOverlayState(mapStyleId = MapStyleId.OPENFREEMAP, showsHikingRoutes = true),
            allowRemoteRequests = false,
            hasLoadedRemoteStyle = false
        )
        assertThat(offline.baseStyle).isEqualTo(BaseStyle.Empty)
        assertThat(offline.hikingRoutesTileUrl).isNull()
    }

    @Test
    fun osmRasterAndKeyGatedRasterStylesResolveRasterUrlOnlyWhenAllowedAndKeyed() {
        val osmOnline = resolveMapStylePlan(
            overlayState = MapOverlayState(mapStyleId = MapStyleId.OSM_RASTER, showsHikingRoutes = true),
            allowRemoteRequests = true,
            hasLoadedRemoteStyle = false
        )
        assertThat(osmOnline.usesOpenHikesVector).isFalse()
        assertThat(osmOnline.baseStyle).isEqualTo(BaseStyle.Empty)
        assertThat(osmOnline.baseRasterTileUrl).isEqualTo(MapStyleCatalog.OSM_RASTER_URL_TEMPLATE)
        assertThat(osmOnline.baseRasterMaxZoom).isEqualTo(19)
        assertThat(osmOnline.hikingRoutesTileUrl).isEqualTo(WAYMARKED_HIKING_TILES_URL)

        val stadiaWithoutKey = resolveMapStylePlan(
            overlayState = MapOverlayState(mapStyleId = MapStyleId.STADIA_OUTDOORS, showsHikingRoutes = false),
            allowRemoteRequests = true,
            hasLoadedRemoteStyle = false,
            mapProviderKeys = MapProviderKeys.Empty
        )
        assertThat(stadiaWithoutKey.usesOpenHikesVector).isTrue()
        assertThat(stadiaWithoutKey.baseRasterTileUrl).isNull()

        val stadiaWithKey = resolveMapStylePlan(
            overlayState = MapOverlayState(mapStyleId = MapStyleId.STADIA_OUTDOORS, showsHikingRoutes = false),
            allowRemoteRequests = true,
            hasLoadedRemoteStyle = false,
            mapProviderKeys = MapProviderKeys(stadiaApiKey = "demo-key")
        )
        assertThat(stadiaWithKey.usesOpenHikesVector).isFalse()
        assertThat(stadiaWithKey.baseRasterTileUrl)
            .isEqualTo("https://tiles.stadiamaps.com/tiles/outdoors/{z}/{x}/{y}.png?api_key=demo-key")
        assertThat(stadiaWithKey.baseRasterMaxZoom).isEqualTo(20)
    }
}
