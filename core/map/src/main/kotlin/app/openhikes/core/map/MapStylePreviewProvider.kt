package app.openhikes.core.map

import androidx.compose.ui.graphics.ImageBitmap
import app.openhikes.core.model.MapStyle
import app.openhikes.core.model.TilePreviewFrame

/**
 * Seam for asynchronously loading composited raster tile previews for Map Modes style cards.
 *
 * Returns `null` when a style does not use raster tiles, lacks an API key, or cannot fetch
 * previews (offline, Data Saver, or transient error), allowing UI cards to show their stylized
 * cartographic fallback immediately without blocking or touching the network in tests.
 */
interface MapStylePreviewProvider {
    suspend fun loadPreview(style: MapStyle, frame: TilePreviewFrame): ImageBitmap?
}
