package app.openhikes.core.ui.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.Serializable
import org.junit.Test

class OpenHikesNavigatorTest {
    @Serializable
    private data class DummyHikeRoute(val hikeId: String) : OpenHikesRoute

    @Test
    fun navigateAvoidsConsecutiveDuplicatePushAndPopPreservesRoot() {
        val backStack = NavBackStack<NavKey>(MapHomeRoute)
        val navigator = OpenHikesNavigator(backStack)

        assertThat(navigator.canPop).isFalse()
        assertThat(navigator.pop()).isFalse()
        assertThat(navigator.currentRoute).isEqualTo(MapHomeRoute)

        navigator.navigate(SettingsRoute)
        navigator.navigate(SettingsRoute)
        assertThat(backStack.toList()).containsExactly(MapHomeRoute, SettingsRoute).inOrder()
        assertThat(navigator.canPop).isTrue()

        assertThat(navigator.pop()).isTrue()
        assertThat(backStack.toList()).containsExactly(MapHomeRoute)
        assertThat(navigator.pop()).isFalse()
    }

    @Test
    fun removeRoutesMatchingStripsMatchingPushedEntriesAtomically() {
        val backStack = NavBackStack<NavKey>(
            MapHomeRoute,
            DummyHikeRoute("h-1"),
            SettingsRoute,
            DummyHikeRoute("h-1"),
            DummyHikeRoute("h-2")
        )
        val navigator = OpenHikesNavigator(backStack)

        navigator.removeRoutesMatching { route ->
            route is DummyHikeRoute && route.hikeId == "h-1"
        }

        assertThat(backStack.toList())
            .containsExactly(MapHomeRoute, SettingsRoute, DummyHikeRoute("h-2"))
            .inOrder()

        navigator.popToRoot()
        assertThat(backStack.toList()).containsExactly(MapHomeRoute)
    }
}
