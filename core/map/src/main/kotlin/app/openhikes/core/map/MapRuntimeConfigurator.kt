package app.openhikes.core.map

import app.openhikes.core.model.AppBuildInfo
import app.openhikes.core.network.OpenHikesUserAgent
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.resource.MapRequestInterceptor

private val IDENTIFIED_TILE_HOSTS = setOf(
    "tile.openstreetmap.org",
    "tile.waymarkedtrails.org",
    "tiles.openfreemap.org"
)

/**
 * Returns OpenHikes app-identification headers for live MapLibre tile/style requests targeting
 * OSMF and public community map hosts so requests never rely on SDK-default identification.
 */
internal fun openHikesMapRequestHeaders(url: String, userAgent: String): Map<String, String> {
    val host = try {
        URI(url).host?.lowercase(Locale.ROOT)
    } catch (_: URISyntaxException) {
        null
    }
    return if (host in IDENTIFIED_TILE_HOSTS) {
        mapOf(OpenHikesUserAgent.HEADER_NAME to userAgent)
    } else {
        emptyMap()
    }
}

/**
 * Configures MapLibre Compose's process-default runtime once before the first map instance is
 * created so live raster/style requests carry the OpenHikes `User-Agent`.
 */
@Singleton
class MapRuntimeConfigurator @Inject constructor(private val buildInfo: AppBuildInfo) {
    private val configured = AtomicBoolean(false)

    fun ensureConfigured() {
        if (!configured.compareAndSet(false, true)) return
        val userAgent = OpenHikesUserAgent.build(buildInfo)
        runCatching {
            DefaultMapRuntime.configure {
                requestInterceptor = MapRequestInterceptor(
                    headers = { request -> openHikesMapRequestHeaders(request.url, userAgent) }
                )
            }
        }
    }
}
