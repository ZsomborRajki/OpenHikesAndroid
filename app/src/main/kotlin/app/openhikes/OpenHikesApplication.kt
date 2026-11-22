package app.openhikes

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point bootstrapping Hilt dependency injection.
 */
@HiltAndroidApp
class OpenHikesApplication : Application()
