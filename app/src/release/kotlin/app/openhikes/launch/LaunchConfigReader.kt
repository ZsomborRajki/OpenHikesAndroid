package app.openhikes.launch

import android.content.Intent
import app.openhikes.core.common.LaunchConfig

/**
 * Release stub ensuring zero UI-test launch hooks or argument parsers ship in release builds.
 */
object LaunchConfigReader {
    fun fromIntent(@Suppress("UNUSED_PARAMETER") intent: Intent?): LaunchConfig = LaunchConfig.Production

    fun merge(base: LaunchConfig, @Suppress("UNUSED_PARAMETER") intent: Intent?): LaunchConfig = base
}
