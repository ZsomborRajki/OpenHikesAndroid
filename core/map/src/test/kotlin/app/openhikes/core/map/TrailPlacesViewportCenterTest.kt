package app.openhikes.core.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.openhikes.core.model.GeoCoordinate
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TrailPlacesViewportCenterTest {
    @Test
    fun computesUnobstructedCenterForPortraitHalfSheetAndSidePanel() {
        // Portrait 411x891dp with half-sheet reserving 463dp at bottom and 24dp status bar at top.
        val halfSheetCenter = unobstructedViewportCenter(
            maxWidth = 411.dp,
            maxHeight = 891.dp,
            viewportPadding = PaddingValues(start = 0.dp, top = 24.dp, end = 0.dp, bottom = 463.dp),
            layoutDirection = LayoutDirection.Ltr
        )
        // Top unobstructed strip is [24..428]dp -> midpoint 226dp (well above the 428dp sheet top).
        assertThat(halfSheetCenter).isEqualTo(DpOffset(x = 205.5.dp, y = 226.dp))

        // Landscape 800x400dp with 340dp docked side panel on start edge (LTR).
        val sidePanelLtr = unobstructedViewportCenter(
            maxWidth = 800.dp,
            maxHeight = 400.dp,
            viewportPadding = PaddingValues(start = 340.dp, top = 0.dp, end = 0.dp, bottom = 0.dp),
            layoutDirection = LayoutDirection.Ltr
        )
        assertThat(sidePanelLtr).isEqualTo(DpOffset(x = 570.dp, y = 200.dp))

        // In RTL, start padding sits on the physical right edge.
        val sidePanelRtl = unobstructedViewportCenter(
            maxWidth = 800.dp,
            maxHeight = 400.dp,
            viewportPadding = PaddingValues(start = 340.dp, top = 0.dp, end = 0.dp, bottom = 0.dp),
            layoutDirection = LayoutDirection.Rtl
        )
        assertThat(sidePanelRtl).isEqualTo(DpOffset(x = 230.dp, y = 200.dp))
    }

    @Test
    fun canSearchVisibleAreaReactsToZoomChangesAcrossThreshold() = runTest {
        val tracker = MapCameraTracker()
        val thumsee = GeoCoordinate(47.72, 12.83)

        tracker.update(MapCameraState(center = thumsee, zoom = 10.0))
        assertThat(tracker.canSearchVisibleArea.first()).isFalse()

        tracker.update(MapCameraState(center = thumsee, zoom = 16.0))
        assertThat(tracker.canSearchVisibleArea.first()).isTrue()

        tracker.update(MapCameraState(center = thumsee, zoom = 8.0))
        assertThat(tracker.canSearchVisibleArea.first()).isFalse()
    }
}
