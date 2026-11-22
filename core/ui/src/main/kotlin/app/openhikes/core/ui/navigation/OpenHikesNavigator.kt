package app.openhikes.core.ui.navigation

import androidx.compose.runtime.Stable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

/**
 * App-owned Navigation 3 back stack wrapper enforcing duplicate-push prevention,
 * root preservation, and atomic removal of deleted hike routes.
 */
@Stable
class OpenHikesNavigator(val backStack: NavBackStack<NavKey>) {
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
