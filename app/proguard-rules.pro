# App-specific ProGuard / R8 keep rules.
-keep,allowobfuscation,allowshrinking class * implements app.openhikes.core.ui.navigation.OpenHikesRoute { *; }
-keep,allowobfuscation,allowshrinking class * implements androidx.navigation3.runtime.NavKey { *; }
-keepclassmembers class * implements app.openhikes.core.ui.navigation.OpenHikesRoute {
    public static final ** Companion;
}
