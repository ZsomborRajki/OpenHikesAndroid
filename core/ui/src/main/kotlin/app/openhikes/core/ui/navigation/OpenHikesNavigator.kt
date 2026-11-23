package app.openhikes.core.ui.navigation

import androidx.compose.runtime.Stable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.openhikes.core.model.HikeId

/**
 * App-owned Navigation 3 back stack wrapper enforcing duplicate-push prevention,
 * root preservation, sheet collapse coordination, and atomic removal of deleted hike routes.
 */
@Stable
class OpenHikesNavigator(val backStack: NavBackStack<NavKey>, private val onCollapseSheet: () -> Unit = {}) {
    val currentRoute: NavKey?
        get() = backStack.lastOrNull()

    val canPop: Boolean
        get() = backStack.size > 1

    /**
     * Pushes [route] unless it is already the top entry on [backStack].
     */
    fun navigate(route: OpenHikesRoute) {
        if (backStack.lastOrNull() == route) return
        backStack.add(route)
    }

    /**
     * Replaces the current top destination above the root entry with [route], or pushes [route]
     * if currently at the root entry.
     */
    fun replaceTop(route: OpenHikesRoute) {
        if (backStack.size > 1) {
            backStack[backStack.lastIndex] = route
        } else if (backStack.lastOrNull() != route) {
            backStack.add(route)
        }
    }

    /**
     * Pops the top destination while always keeping the root entry intact.
     */
    fun pop(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    /**
     * Pops every destination above the root entry.
     */
    fun popToRoot() {
        while (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    /**
     * Requests the bottom sheet (when in portrait/bottom-sheet layout) to collapse to peek.
     */
    fun collapseSheet() {
        onCollapseSheet()
    }

    /**
     * Atomically removes all pushed entries associated with [hikeId].
     */
    fun removeRoutesForHike(hikeId: HikeId) {
        removeRoutesMatching { route ->
            route is HikeScopedRoute && route.hikeId == hikeId
        }
    }

    /**
     * Atomically removes all pushed entries matching [predicate], never removing the root entry.
     */
    fun removeRoutesMatching(predicate: (NavKey) -> Boolean) {
        for (index in backStack.lastIndex downTo 1) {
            if (predicate(backStack[index])) {
                backStack.removeAt(index)
            }
        }
    }
}
