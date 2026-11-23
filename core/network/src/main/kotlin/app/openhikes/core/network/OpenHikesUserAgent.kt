package app.openhikes.core.network

import app.openhikes.core.model.AppBuildInfo
import app.openhikes.core.model.LegalLinks

/**
 * Formats the standard OpenHikes HTTP `User-Agent` header sent on tile, manifest, and offline
 * country archive requests.
 */
object OpenHikesUserAgent {
    const val HEADER_NAME = "User-Agent"

    fun build(buildInfo: AppBuildInfo): String {
        val version = buildInfo.versionName.ifBlank { DEFAULT_VERSION }
        return "OpenHikes/$version (Android; +${LegalLinks.GITHUB_REPOSITORY_URL})"
    }

    private const val DEFAULT_VERSION = "0.1.0"
}
