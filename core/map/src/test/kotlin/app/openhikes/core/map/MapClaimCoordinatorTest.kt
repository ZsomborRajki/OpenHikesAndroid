package app.openhikes.core.map

import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MapClaimCoordinatorTest {
    @Test
    fun deepestBackStackEntryAlwaysWinsRegardlessOfRegistrationOrder() {
        val coordinator = MapClaimCoordinator()

        coordinator.registerClaim(MapClaim(routeKey = "home", depth = 0))
        coordinator.registerClaim(MapClaim(routeKey = "detail", depth = 2))
        coordinator.registerClaim(MapClaim(routeKey = "list", depth = 1))

        assertThat(coordinator.activeClaim.value?.routeKey).isEqualTo("detail")

        // Releasing a shallower claim never clears or steals ownership from the deeper entry.
        coordinator.releaseClaim("list")
        assertThat(coordinator.activeClaim.value?.routeKey).isEqualTo("detail")
    }

    @Test
    fun syncBackStackUpdatesDepthsAndDropsRemovedRoutesAtomically() {
        val coordinator = MapClaimCoordinator()

        coordinator.registerClaim(MapClaim(routeKey = "home", depth = 0))
        coordinator.registerClaim(MapClaim(routeKey = "hike-1", depth = 1))
        coordinator.registerClaim(MapClaim(routeKey = "hike-1-photo", depth = 2))
        assertThat(coordinator.activeClaim.value?.routeKey).isEqualTo("hike-1-photo")

        coordinator.syncBackStack(listOf("home"))
        assertThat(coordinator.activeClaim.value?.routeKey).isEqualTo("home")
        assertThat(coordinator.activeClaim.value?.depth).isEqualTo(0)

        coordinator.registerClaim(MapClaim(routeKey = "settings", depth = 0))
        coordinator.syncBackStack(listOf("home", "settings"))
        assertThat(coordinator.activeClaim.value?.routeKey).isEqualTo("settings")
        assertThat(coordinator.activeClaim.value?.depth).isEqualTo(1)
    }

    @Test
    fun requestCameraCommandFiltersByOwnerAllowsRootKeyAndAdvancesMonotonicIds() {
        val coordinator = MapClaimCoordinator()
        val id1 = coordinator.nextCommandId()
        val id2 = coordinator.nextCommandId()
        assertThat(id2).isGreaterThan(id1)

        coordinator.registerClaim(MapClaim(routeKey = "detail", depth = 1, selectedHikeId = HikeId("h1")))

        val ignored = MapCameraCommand.MoveTo(id = id1, center = GeoCoordinate(47.0, 12.0))
        coordinator.requestCameraCommand("other", ignored)
        assertThat(coordinator.pendingCameraCommand.value).isNull()

        val ownerCmd = MapCameraCommand.MoveTo(id = id2, center = GeoCoordinate(47.7, 12.8))
        coordinator.requestCameraCommand("detail", ownerCmd)
        assertThat(coordinator.pendingCameraCommand.value).isEqualTo(ownerCmd)

        val rootId = coordinator.nextCommandId()
        val rootCmd = MapCameraCommand.ZoomIn(id = rootId)
        coordinator.requestCameraCommand(MapClaimCoordinator.ROOT_CAMERA_KEY, rootCmd)
        assertThat(coordinator.pendingCameraCommand.value).isEqualTo(rootCmd)

        coordinator.consumeCameraCommand(rootId)
        assertThat(coordinator.pendingCameraCommand.value).isNull()
    }
}
