package app.openhikes.core.model

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * One eighth-degree square of the Copernicus DEM GLO-30 elevation grid: rows counted southward
 * from the North Pole and columns eastward from the antimeridian.
 */
data class ElevationSquare(val row: Int, val column: Int)

/**
 * One post of the Copernicus DEM GLO-30 grid: rows southward from the North Pole (1 arc-second
 * spacing) and columns eastward from the antimeridian at the spacing of the row's latitude band.
 */
data class ElevationPost(val row: Int, val column: Int)

/**
 * Resolves an [ElevationPost] to its owning [ElevationSquare] and row-major index inside that square.
 */
data class ElevationPostPlace(val square: ElevationSquare, val index: Int)

/**
 * Required Copernicus WorldDEM-30 attribution strings under the COP-DEM-GLO-30-F license.
 *
 * Licensor wording in [NOTICE] and [LIABILITY] is kept verbatim in English and must not be translated.
 */
object ElevationCredit {
    /** Article 6(b) notice for data adapted from Copernicus DEM. */
    const val NOTICE = "produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and " +
        "© Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the " +
        "European Union and ESA; all rights reserved"

    /** Article 6(c) non-liability clause. */
    const val LIABILITY = "The organisations in charge of the Copernicus programme by law or by " +
        "delegation do not incur any liability for any use of the Copernicus WorldDEM-30"

    const val SOURCE_NAME = "Copernicus DEM"

    const val PRODUCT_PAGE_URL =
        "https://dataspace.copernicus.eu/explore-data/data-collections/" +
            "copernicus-contributing-missions/collections-description/COP-DEM"
}

/**
 * Addressing and bilinear interpolation math for OpenHikes's Copernicus DEM GLO-30 elevation squares.
 *
 * Mirrors iOS `ElevationSquareGrid.swift` and the archive builder byte-for-byte so both platforms
 * read the exact same `elevation-<build>/12/<col>/<row>.bin` tiles from `tiles.openhikes.app` and
 * per-country offline `-elevation-<build>.pmtiles` archives.
 */
object ElevationSquareGrid {
    const val GENERATION = 1
    const val SQUARES_PER_DEGREE = 8
    const val POSTS_PER_DEGREE_LATITUDE = 3_600
    const val ROWS_PER_SQUARE = POSTS_PER_DEGREE_LATITUDE / SQUARES_PER_DEGREE
    const val ROW_COUNT = 180 * SQUARES_PER_DEGREE
    const val COLUMN_COUNT = 360 * SQUARES_PER_DEGREE
    const val POST_ROW_COUNT = 180 * POSTS_PER_DEGREE_LATITUDE
    const val ARCHIVE_ZOOM = 12

    private const val QUARTER_TURN_DEGREES = 90.0
    private const val HALF_TURN_DEGREES = 180.0
    private const val FULL_TURN_DEGREES = 360
    private const val QUARTER_TURN_INT = 90
    private const val NAME_PREFIX = "elevation$GENERATION"
    private const val NAME_PART_COUNT = 3

    private const val BAND_FIFTY = 50
    private const val BAND_SIXTY = 60
    private const val BAND_SEVENTY = 70
    private const val BAND_EIGHTY = 80
    private const val BAND_EIGHTY_FIVE = 85
    private const val POSTS_FROM_FIFTY = 2_400
    private const val POSTS_FROM_SIXTY = 1_800
    private const val POSTS_FROM_SEVENTY = 1_200
    private const val POSTS_FROM_EIGHTY = 720
    private const val POSTS_FROM_EIGHTY_FIVE = 360

    /**
     * Posts per degree of longitude in the Copernicus tile band whose southern edge is [tileSouth]
     * degrees. Both hemispheres mirror equatorward: e.g. 50°..51° N (`tileSouth = 50`) and
     * 50°..51° S (`tileSouth = -51`) share the 1.5 arc-second (2,400 posts/degree) band.
     */
    fun postsPerDegreeOfLongitude(tileSouth: Int): Int {
        val equatorward = if (tileSouth >= 0) tileSouth else -(tileSouth + 1)
        return when {
            equatorward >= BAND_EIGHTY_FIVE -> POSTS_FROM_EIGHTY_FIVE
            equatorward >= BAND_EIGHTY -> POSTS_FROM_EIGHTY
            equatorward >= BAND_SEVENTY -> POSTS_FROM_SEVENTY
            equatorward >= BAND_SIXTY -> POSTS_FROM_SIXTY
            equatorward >= BAND_FIFTY -> POSTS_FROM_FIFTY
            else -> POSTS_PER_DEGREE_LATITUDE
        }
    }

    fun tileSouthOfSquareRow(row: Int): Int = QUARTER_TURN_INT - 1 - row / SQUARES_PER_DEGREE

    fun tileSouthOfPostRow(row: Int): Int = QUARTER_TURN_INT - 1 - row / POSTS_PER_DEGREE_LATITUDE

    fun columnsPerSquare(row: Int): Int = postsPerDegreeOfLongitude(tileSouthOfSquareRow(row)) / SQUARES_PER_DEGREE

    fun placeOf(post: ElevationPost): ElevationPostPlace {
        val squareRow = post.row / ROWS_PER_SQUARE
        val columns = columnsPerSquare(squareRow)
        return ElevationPostPlace(
            square = ElevationSquare(row = squareRow, column = post.column / columns),
            index = (post.row % ROWS_PER_SQUARE) * columns + post.column % columns
        )
    }

    /**
     * The four grid posts bracketing a point, together with horizontal weights on the northern and
     * southern rows (which can differ across latitude-band boundaries at 50°, 60°, 70°, 80°, 85°)
     * and the southward weight between the two rows.
     */
    data class Neighborhood(
        val northWest: ElevationPost,
        val northEast: ElevationPost,
        val southWest: ElevationPost,
        val southEast: ElevationPost,
        val northEastward: Double,
        val southEastward: Double,
        val southward: Double
    ) {
        val posts: List<ElevationPost>
            get() = listOf(northWest, northEast, southWest, southEast)

        /**
         * Bilinear height from the four surrounding posts, or `null` if any post has no height.
         */
        fun height(postHeight: (ElevationPost) -> Double?): Double? {
            val north = lerp(postHeight(northWest), postHeight(northEast), northEastward)
            val south = lerp(postHeight(southWest), postHeight(southEast), southEastward)
            return lerp(north, south, southward)
        }

        private fun lerp(a: Double?, b: Double?, weight: Double): Double? =
            if (a != null && b != null) a + (b - a) * weight else null
    }

    /**
     * Returns the four posts bracketing ([latitude], [longitude]), or `null` for non-finite or
     * out-of-range coordinates.
     */
    fun postsAround(latitude: Double, longitude: Double): Neighborhood? {
        if (!latitude.isFinite() || !longitude.isFinite() || abs(latitude) > QUARTER_TURN_DEGREES) {
            return null
        }
        val position = (QUARTER_TURN_DEGREES - latitude) * POSTS_PER_DEGREE_LATITUDE
        val lastRow = POST_ROW_COUNT - 1
        val north = min(floor(position).toInt(), lastRow)
        val south = min(north + 1, lastRow)
        val northColumns = columnsOnPostRow(north, longitude)
        val southColumns = columnsOnPostRow(south, longitude)
        return Neighborhood(
            northWest = ElevationPost(row = north, column = northColumns.west),
            northEast = ElevationPost(row = north, column = northColumns.east),
            southWest = ElevationPost(row = south, column = southColumns.west),
            southEast = ElevationPost(row = south, column = southColumns.east),
            northEastward = northColumns.eastward,
            southEastward = southColumns.eastward,
            southward = (position - north.toDouble()).coerceIn(0.0, 1.0)
        )
    }

    fun recordName(square: ElevationSquare): String = "${NAME_PREFIX}_${square.row}_${square.column}"

    fun squareNamed(name: String): ElevationSquare? {
        val parts = name.split('_')
        val row = if (parts.size == NAME_PART_COUNT && parts[0] == NAME_PREFIX) {
            parts[1].toIntOrNull()?.takeIf { it in 0 until ROW_COUNT }
        } else {
            null
        }
        val column = if (row != null) {
            parts[2].toIntOrNull()?.takeIf { it in 0 until COLUMN_COUNT }
        } else {
            null
        }
        return if (row != null && column != null) ElevationSquare(row = row, column = column) else null
    }

    private data class ColumnBracket(val west: Int, val east: Int, val eastward: Double)

    private fun columnsOnPostRow(row: Int, longitude: Double): ColumnBracket {
        val perDegree = postsPerDegreeOfLongitude(tileSouthOfPostRow(row))
        val count = FULL_TURN_DEGREES * perDegree
        val countDouble = count.toDouble()
        var position = ((longitude + HALF_TURN_DEGREES) * perDegree) % countDouble
        if (position < 0.0) position += countDouble
        val west = min(floor(position).toInt(), count - 1)
        val east = (west + 1) % count
        return ColumnBracket(
            west = west,
            east = east,
            eastward = (position - west.toDouble()).coerceIn(0.0, 1.0)
        )
    }
}
