package app.openhikes.core.model

/**
 * Version metadata surfaced in Settings About and support reports.
 */
data class AppBuildInfo(val versionName: String, val versionCode: Int) {
    val displayVersion: String
        get() = "$versionName ($versionCode)"
}
