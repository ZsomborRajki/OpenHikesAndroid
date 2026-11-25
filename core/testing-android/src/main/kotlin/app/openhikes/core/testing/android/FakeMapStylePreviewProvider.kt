package app.openhikes.core.testing.android

import androidx.compose.ui.graphics.ImageBitmap
import app.openhikes.core.map.MapStylePreviewProvider
import app.openhikes.core.model.MapStyle
import app.openhikes.core.model.TilePreviewFrame
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic [MapStylePreviewProvider] fake for JVM, Robolectric, and Compose tests.
 * Never performs network I/O; optionally returns pre-seeded [ImageBitmap]s keyed by style ID.
 */
@Singleton
class FakeMapStylePreviewProvider @Inject constructor() : MapStylePreviewProvider {
    private val recordedRequests = CopyOnWriteArrayList<Pair<String, TilePreviewFrame>>()

    @Volatile
    var previewsByStyleId: Map<String, ImageBitmap> = emptyMap()

    val requests: List<Pair<String, TilePreviewFrame>>
        get() = recordedRequests.toList()

    override suspend fun loadPreview(style: MapStyle, frame: TilePreviewFrame): ImageBitmap? {
        recordedRequests.add(style.id to frame)
        return previewsByStyleId[style.id]
    }
}
