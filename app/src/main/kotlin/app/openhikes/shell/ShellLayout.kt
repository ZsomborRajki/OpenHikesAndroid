package app.openhikes.shell

import androidx.window.core.layout.WindowSizeClass

/**
 * Adaptive chrome posture for OpenHikes's map-first primary shell.
 */
enum class ShellLayout {
    BOTTOM_SHEET,
    SIDE_PANEL
}

/**
 * Resting snap positions for [MapBottomSheet].
 */
enum class SheetDetent {
    PEEK,
    HALF,
    EXPANDED
}

/**
 * Selects persistent bottom sheet vs. docked side panel strictly from the current app window's
 * [WindowSizeClass] so split-screen, foldables, and landscape phones adapt cleanly.
 */
fun resolveShellLayout(windowSizeClass: WindowSizeClass): ShellLayout {
    val isMediumOrWiderWidth =
        windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val isCompactHeight =
        !windowSizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
    return if (isMediumOrWiderWidth || isCompactHeight) {
        ShellLayout.SIDE_PANEL
    } else {
        ShellLayout.BOTTOM_SHEET
    }
}
