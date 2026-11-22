package app.openhikes.core.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MapCameraStateTest {
    @Test
    fun zoomClampsToValidBoundsAndResetNorthZerosBearingAndPitch() {
        val tilted = MapCameraState(zoom = 19.5, bearing = 42.0, pitch = 30.0)
        assertThat(tilted.zoomedIn().zoom).isEqualTo(MapCameraState.MAX_ZOOM)

        val zoomedOut = MapCameraState(zoom = 1.5).zoomedOut()
        assertThat(zoomedOut.zoom).isEqualTo(MapCameraState.MIN_ZOOM)

        val north = tilted.resetNorth()
        assertThat(north.bearing).isEqualTo(0.0)
        assertThat(north.pitch).isEqualTo(0.0)
    }
}
