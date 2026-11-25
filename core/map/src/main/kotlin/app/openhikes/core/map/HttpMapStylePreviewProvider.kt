package app.openhikes.core.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.model.MapProviderKeys
import app.openhikes.core.model.MapStyle
import app.openhikes.core.model.TileNetworkPurpose
import app.openhikes.core.model.TilePreviewFrame
import app.openhikes.core.model.TilePreviewSource
import app.openhikes.core.model.TilePreviewTile
import app.openhikes.core.network.PreviewTileOkHttpClient
import app.openhikes.core.network.TileNetworkPolicy
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Production [MapStylePreviewProvider] fetching and compositing 2×2 raster tile blocks for
 * Map Modes style cards via [okHttpClient], respecting [tileNetworkPolicy] and [mapProviderKeys].
 *
 * Requires all four tiles of a [TilePreviewFrame] so a card never renders a broken partial grid,
 * and caches finished composites in memory keyed by `(styleId, z, x, y)` so reopening Map Modes
 * reuses previews without re-billing commercial tile providers.
 */
@Singleton
internal class HttpMapStylePreviewProvider @Inject constructor(
    @PreviewTileOkHttpClient private val okHttpClient: OkHttpClient,
    private val tileNetworkPolicy: TileNetworkPolicy,
    private val mapProviderKeys: MapProviderKeys,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : MapStylePreviewProvider {
    private val cacheLock = Any()
    private val finishedCache = object : LinkedHashMap<String, ImageBitmap>(CACHE_CAPACITY, CACHE_LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > CACHE_CAPACITY
    }

    override suspend fun loadPreview(style: MapStyle, frame: TilePreviewFrame): ImageBitmap? {
        val source = TilePreviewSource.from(style, mapProviderKeys) as? TilePreviewSource.RasterTiles
            ?: return null
        val compositeKey = "${source.styleId}/${frame.z}/${frame.x}/${frame.y}"
        val cached = synchronized(cacheLock) { finishedCache[compositeKey] }
        return cached ?: loadAndCacheIfAllowed(source = source, frame = frame, compositeKey = compositeKey)
    }

    private suspend fun loadAndCacheIfAllowed(
        source: TilePreviewSource.RasterTiles,
        frame: TilePreviewFrame,
        compositeKey: String
    ): ImageBitmap? {
        if (!tileNetworkPolicy.decide(TileNetworkPurpose.INTERACTIVE).isAllowed) return null
        return fetchAndComposite(source = source, frame = frame)?.also { composite ->
            synchronized(cacheLock) {
                finishedCache[compositeKey] = composite
            }
        }
    }

    private suspend fun fetchAndComposite(
        source: TilePreviewSource.RasterTiles,
        frame: TilePreviewFrame
    ): ImageBitmap? = withContext(ioDispatcher) {
        val loaded = coroutineScope {
            frame.tiles.map { tile ->
                async { tile to fetchTileBitmap(source.urlFor(tile)) }
            }.awaitAll()
        }
        if (loaded.any { it.second == null }) {
            loaded.forEach { it.second?.recycle() }
            null
        } else {
            val bitmaps = loaded.mapNotNull { (tile, bitmap) -> bitmap?.let { tile to it } }
            val compositeBitmap = compositeBitmaps(bitmaps)
            bitmaps.forEach { it.second.recycle() }
            compositeBitmap?.asImageBitmap()
        }
    }

    private fun fetchTileBitmap(url: String): Bitmap? = try {
        val request = Request.Builder().url(url).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            val bytes = if (response.isSuccessful) response.body.bytes() else ByteArray(0)
            if (bytes.isNotEmpty()) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun compositeBitmaps(tiles: List<Pair<TilePreviewTile, Bitmap>>): Bitmap? {
        val first = tiles.firstOrNull()?.second ?: return null
        val tileSide = first.width.coerceAtLeast(1)
        val totalSide = tileSide * TilePreviewFrame.TILES_PER_SIDE
        val output = createBitmap(totalSide, totalSide)
        val canvas = Canvas(output)
        for ((tile, bitmap) in tiles) {
            val left = tile.column * tileSide
            val top = tile.row * tileSide
            canvas.drawBitmap(
                bitmap,
                null,
                Rect(left, top, left + tileSide, top + tileSide),
                null
            )
        }
        return output
    }

    private companion object {
        const val CACHE_CAPACITY = 24
        const val CACHE_LOAD_FACTOR = 0.75f
    }
}
