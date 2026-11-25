package app.openhikes.core.map

import app.openhikes.core.model.AppBuildInfo
import app.openhikes.core.network.OpenHikesUserAgent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MapRuntimeConfiguratorTest {
    @Test
    fun attachesOpenHikesUserAgentToOsmfAndPublicTileHostsOnly() {
        val userAgent = OpenHikesUserAgent.build(AppBuildInfo(versionName = "0.1.0", versionCode = 1))

        assertThat(
            openHikesMapRequestHeaders(
                url = "https://tile.openstreetmap.org/14/8782/5716.png",
                userAgent = userAgent
            )
        ).containsExactly(OpenHikesUserAgent.HEADER_NAME, userAgent)

        assertThat(
            openHikesMapRequestHeaders(
                url = "https://tile.waymarkedtrails.org/hiking/14/8782/5716.png",
                userAgent = userAgent
            )
        ).containsExactly(OpenHikesUserAgent.HEADER_NAME, userAgent)

        assertThat(
            openHikesMapRequestHeaders(
                url = "https://tiles.openfreemap.org/styles/liberty",
                userAgent = userAgent
            )
        ).containsExactly(OpenHikesUserAgent.HEADER_NAME, userAgent)

        assertThat(
            openHikesMapRequestHeaders(
                url = "https://example.com/tiles/1/2/3.png",
                userAgent = userAgent
            )
        ).isEmpty()

        assertThat(
            openHikesMapRequestHeaders(
                url = "not a valid uri :::",
                userAgent = userAgent
            )
        ).isEmpty()
    }
}
