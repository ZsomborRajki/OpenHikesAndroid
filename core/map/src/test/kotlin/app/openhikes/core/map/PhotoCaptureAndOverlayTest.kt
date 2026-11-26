package app.openhikes.core.map

import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikePhoto
import app.openhikes.core.model.PhotoMapPin
import app.openhikes.core.model.RouteGeometry
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PhotoCaptureAndOverlayTest {
    private val hikeId = HikeId("hike-1")
    private val spot = GeoCoordinate(47.7185, 12.8318)

    @Test
    fun captureControllerArbitratesDeepestRouteAndWithdrawsFloatingButtonsAtRoot() {
        val controller = PhotoCaptureController()
        val homeKey = "home"
        val hikeKey = "hike-detail"
        val placeKey = "place-detail"

        controller.syncBackStack(listOf(homeKey, hikeKey))
        assertThat(controller.showFloatingButtons.value).isFalse()
        controller.registerSubject(
            PhotoCaptureSubject(routeKey = hikeKey, depth = 1, hikeId = hikeId, placeId = null)
        )
        assertThat(controller.activeSubject.value?.routeKey).isEqualTo(hikeKey)
        assertThat(controller.showFloatingButtons.value).isTrue()

        controller.registerSubject(
            PhotoCaptureSubject(routeKey = placeKey, depth = 2, hikeId = hikeId, placeId = "place-1")
        )
        controller.syncBackStack(listOf(homeKey, hikeKey, placeKey))
        assertThat(controller.activeSubject.value?.placeId).isEqualTo("place-1")
        assertThat(controller.requestCamera()).isTrue()
        assertThat(controller.pendingRequest.value?.subject?.placeId).isEqualTo("place-1")

        // Popping back to root removes claims, hides floating buttons, and bumps import cancellation epoch.
        val epochBefore = controller.importCancellationEpoch.value
        controller.syncBackStack(listOf(homeKey))
        assertThat(controller.activeSubject.value).isNull()
        assertThat(controller.showFloatingButtons.value).isFalse()
        assertThat(controller.importCancellationEpoch.value).isGreaterThan(epochBefore)
    }

    @Test
    fun photoOverlaySelectsGroupedPinByAnySiblingPhotoIdAndFramesFiveHundredMeterSpot() {
        val overlayState = TrailPhotosOverlayState()
        val p1 = HikePhoto(id = "p1", hikeId = hikeId, capturedAtEpochMillis = 1_000L, coordinate = spot)
        val p2 = HikePhoto(id = "p2", hikeId = hikeId, capturedAtEpochMillis = 2_000L, coordinate = spot)
        val pins = PhotoMapPin.pinsFor(listOf(p1, p2)).map {
            MapPhotoPinItem(pin = it, tintHex = "#2E7D32", spokenLabel = "2 photos taken here")
        }
        overlayState.setClaim(TrailPhotosClaim(routeKey = "viewer", overlay = MapTrailPhotosOverlay(pins = pins)))

        overlayState.selectPhoto(routeKey = "viewer", photoId = "p2")
        assertThat(overlayState.claimFor("viewer")?.overlay?.selectedPinId).isEqualTo("p1")

        overlayState.selectPhoto(routeKey = "viewer", photoId = null)
        assertThat(overlayState.claimFor("viewer")?.overlay?.selectedPinId).isNull()

        val bounds = TrailPhotosOverlayState.photoSpotBounds(spot)
        val northSouthMeters = RouteGeometry.distanceMeters(
            GeoCoordinate(bounds.southLat, spot.longitude),
            GeoCoordinate(bounds.northLat, spot.longitude)
        )
        assertThat(northSouthMeters).isWithin(15.0).of(500.0)
    }
}
