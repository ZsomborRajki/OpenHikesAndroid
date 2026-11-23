package app.openhikes.core.map

import android.net.ConnectivityManager
import app.openhikes.core.model.MapStyleId
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.maplibre.compose.style.BaseStyle

class MapNetworkPolicyTest {
    @Test
    fun interactiveRequestsBlockedWhenSimulatesOfflineOrNoActiveNetwork() {
        assertThat(
            shouldAllowInteractiveMapRequests(
                hasActiveNetwork = true,
                isMetered = false,
                restrictBackgroundStatus = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED,
                simulatesOffline = true
            )
        ).isFalse()

        assertThat(
            shouldAllowInteractiveMapRequests(
                hasActiveNetwork = false,
                isMetered = false,
                restrictBackgroundStatus = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED,
                simulatesOffline = false
            )
        ).isFalse()
    }

    @Test
    fun interactiveRequestsBlockedUnderDataSaverOnMeteredConnection() {
        assertThat(
            shouldAllowInteractiveMapRequests(
                hasActiveNetwork = true,
                isMetered = true,
                restrictBackgroundStatus = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
                simulatesOffline = false
            )
        ).isFalse()
    }

    @Test
    fun interactiveRequestsAllowedWhenUnmeteredOrWhitelistedOrDataSaverOff() {
        assertThat(
            shouldAllowInteractiveMapRequests(
                hasActiveNetwork = true,
                isMetered = false,
                restrictBackgroundStatus = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
                simulatesOffline = false
            )
        ).isTrue()

        assertThat(
            shouldAllowInteractiveMapRequests(
                hasActiveNetwork = true,
                isMetered = true,
                restrictBackgroundStatus = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED,
                simulatesOffline = false
            )
        ).isTrue()

        assertThat(
            shouldAllowInteractiveMapRequests(
                hasActiveNetwork = true,
                isMetered = true,
                restrictBackgroundStatus = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED,
                simulatesOffline = false
            )
        ).isTrue()
    }

    @Test
    fun stylePlanUsesOpenHikesVectorOfflineAndOpenFreeMapWhenSelectedOnline() {
        val openHikesOffline = resolveMapStylePlan(
            overlayState = MapOverlayState(mapStyleId = MapStyleId.OPENHIKES, showsHikingRoutes = true),
            allowRemoteRequests = false,
            hasLoadedRemoteStyle = false
        )
        assertThat(openHikesOffline.usesOpenHikesVector).isTrue()
        assertThat(openHikesOffline.baseStyle).isEqualTo(BaseStyle.Empty)
        assertThat(openHikesOffline.hikingRoutesTileUrl).isNull()

        val openHikesOnline = resolveMapStylePlan(
            overlayState = MapOverlayState(mapStyleId = MapStyleId.OPENHIKES, showsHikingRoutes = true),
            allowRemoteRequests = true,
            hasLoadedRemoteStyle = false
        )
        assertThat(openHikesOnline.usesOpenHikesVector).isTrue()
        assertThat(openHikesOnline.hikingRoutesTileUrl).isEqualTo(WAYMARKED_HIKING_TILES_URL)

        val openFreeMapOffline = resolveMapStylePlan(
            overlayState = MapOverlayState(mapStyleId = MapStyleId.OPENFREEMAP, showsHikingRoutes = true),
            allowRemoteRequests = false,
            hasLoadedRemoteStyle = false
        )
        assertThat(openFreeMapOffline.usesOpenHikesVector).isFalse()
        assertThat(openFreeMapOffline.baseStyle).isEqualTo(BaseStyle.Empty)
        assertThat(openFreeMapOffline.hikingRoutesTileUrl).isNull()

        val openFreeMapOnline = resolveMapStylePlan(
            overlayState = MapOverlayState(mapStyleId = MapStyleId.OPENFREEMAP, showsHikingRoutes = false),
            allowRemoteRequests = true,
            hasLoadedRemoteStyle = true
        )
        assertThat(openFreeMapOnline.usesOpenHikesVector).isFalse()
        assertThat(openFreeMapOnline.baseStyle).isEqualTo(BaseStyle.Uri(OPENFREEMAP_LIBERTY_STYLE_URL))
        assertThat(openFreeMapOnline.hikingRoutesTileUrl).isNull()
    }
}
