package app.openhikes.core.model

import kotlin.math.floor

/**
 * Single tile inside a 2×2 [TilePreviewFrame] block, carrying both slippy coordinates and block slot.
 */
data class TilePreviewTile(val z: Int, val x: Int, val y: Int, val column: Int, val row: Int) {
    val address: TileAddress
        get() = TileAddress(z = z, x = x, y = y)
}

/**
 * Web Mercator unit-square rectangle `[0.0, 1.0]` covered by a [TilePreviewFrame].
 */
data class TilePreviewUnitRect(val originX: Double, val originY: Double, val side: Double)

/**
 * A 2×2 square block of slippy tiles at [z] used to sample map styles consistently in Map Modes.
 *
 * Snapped to the tile grid so minor location drift inside a tile reuses cached preview imagery
 * instead of re-fetching on every visit.
 */
data class TilePreviewFrame(val z: Int, val x: Int, val y: Int) {
    val tiles: List<TilePreviewTile>
        get() {
            val count = 1 shl z
            return buildList(TILES_PER_SIDE * TILES_PER_SIDE) {
                for (row in 0 until TILES_PER_SIDE) {
                    for (column in 0 until TILES_PER_SIDE) {
                        add(
                            TilePreviewTile(
                                z = z,
                                x = wrapTileIndex(x + column, count),
                                y = (y + row).coerceIn(0, count - 1),
                                column = column,
                                row = row
                            )
                        )
                    }
                }
            }
        }

    val unitRect: TilePreviewUnitRect
        get() {
            val count = (1 shl z).toDouble()
            return TilePreviewUnitRect(
                originX = x.toDouble() / count,
                originY = y.toDouble() / count,
                side = TILES_PER_SIDE.toDouble() / count
            )
        }

    companion object {
        const val DEFAULT_ZOOM = 14
        const val TILES_PER_SIDE = 2

        private const val FALLBACK_LATITUDE = 47.5536
        private const val FALLBACK_LONGITUDE = 12.9752
        private const val HALF_TILE = 0.5
        private const val MIN_ZOOM = 1
        private const val MAX_ZOOM = 20

        /**
         * Königssee valley fallback used when the hiker's location is unknown (lake, forest, peak, trails).
         */
        val FALLBACK_COORDINATE = GeoCoordinate(
            latitude = FALLBACK_LATITUDE,
            longitude = FALLBACK_LONGITUDE
        )

        /**
         * Computes the 2×2 tile block containing [coordinate] inside its central half.
         */
        fun around(coordinate: GeoCoordinate?, z: Int = DEFAULT_ZOOM): TilePreviewFrame {
            val resolvedCoordinate = coordinate ?: FALLBACK_COORDINATE
            val clampedZoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
            val count = 1 shl clampedZoom
            val unitX = Mercator.wrappedUnitX(Mercator.unitX(resolvedCoordinate.longitude))
            val unitY = Mercator.unitY(resolvedCoordinate.latitude)
            val column = unitX * count.toDouble()
            val row = unitY * count.toDouble()
            val westward = column - floor(column) < HALF_TILE
            val northward = row - floor(row) < HALF_TILE
            val westX = wrapTileIndex(floor(column).toInt() - if (westward) 1 else 0, count)
            val northY = (floor(row).toInt() - if (northward) 1 else 0)
                .coerceIn(0, (count - TILES_PER_SIDE).coerceAtLeast(0))
            return TilePreviewFrame(z = clampedZoom, x = westX, y = northY)
        }

        private fun wrapTileIndex(index: Int, count: Int): Int {
            val remainder = index % count
            return if (remainder < 0) remainder + count else remainder
        }
    }
}

/**
 * Preview tile resolution strategy for a [MapStyle] given [MapProviderKeys].
 */
sealed interface TilePreviewSource {
    /** Key-gated style missing its local API key — must never issue tile requests. */
    data object Unavailable : TilePreviewSource

    /** Vector style rendered cleanly via stylized swatch without raster tile requests. */
    data class VectorSwatch(val styleId: String) : TilePreviewSource

    /** Raster tile style whose 2×2 preview block can be fetched and composited when online. */
    data class RasterTiles(val styleId: String, val resolvedUrlTemplate: String) : TilePreviewSource {
        fun urlFor(tile: TilePreviewTile): String = resolvedUrlTemplate
            .replace("{z}", tile.z.toString())
            .replace("{x}", tile.x.toString())
            .replace("{y}", tile.y.toString())

        fun cacheKeyFor(tile: TilePreviewTile): String = "${styleId}_${tile.z}_${tile.x}_${tile.y}"
    }

    companion object {
        fun from(style: MapStyle, keys: MapProviderKeys = MapProviderKeys.Empty): TilePreviewSource =
            if (!style.isUsable(keys)) {
                Unavailable
            } else {
                when (style.kind) {
                    MapStyleKind.OPENHIKES_VECTOR,
                    MapStyleKind.REMOTE_VECTOR_STYLE -> VectorSwatch(style.id)

                    MapStyleKind.RASTER_TILES -> style.resolvedUrl(keys)?.let { resolved ->
                        RasterTiles(styleId = style.id, resolvedUrlTemplate = resolved)
                    } ?: Unavailable
                }
            }
    }
}
