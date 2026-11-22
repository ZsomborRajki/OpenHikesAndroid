package app.openhikes.core.map

import android.net.ConnectivityManager
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
    fun stylePlanUsesEmptyStyleWhenRemoteBlockedOnColdStartAndAddsWaymarkedWhenEnabled() {
        val blockedColdStart = resolveMapStylePlan(
            overlayState = MapOverlayState(showsHikingRoutes = true),
            allowRemoteRequests = false,
            hasLoadedRemoteStyle = false
        )
        assertThat(blockedColdStart.baseStyle).isEqualTo(BaseStyle.Empty)
        assertThat(blockedColdStart.hikingRoutesTileUrl).isNull()

        val onlineWithRoutes = resolveMapStylePlan(
            overlayState = MapOverlayState(showsHikingRoutes = true),
            allowRemoteRequests = true,
            hasLoadedRemoteStyle = false
        )
        assertThat(onlineWithRoutes.baseStyle).isEqualTo(BaseStyle.Uri(OPENFREEMAP_LIBERTY_STYLE_URL))
        assertThat(onlineWithRoutes.hikingRoutesTileUrl).isEqualTo(WAYMARKED_HIKING_TILES_URL)

        val onlineWithoutRoutes = resolveMapStylePlan(
            overlayState = MapOverlayState(showsHikingRoutes = false),
            allowRemoteRequests = true,
            hasLoadedRemoteStyle = true
        )
        assertThat(onlineWithoutRoutes.baseStyle).isEqualTo(BaseStyle.Uri(OPENFREEMAP_LIBERTY_STYLE_URL))
        assertThat(onlineWithoutRoutes.hikingRoutesTileUrl).isNull()
    }
}
