package app.openhikes.core.common.gpx

import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.GpxWaypoint
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteGeometry
import app.openhikes.core.model.toTrailRuns
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Spatial index matching waypoints onto multi-track routes within [WAYPOINT_THRESHOLD_METERS],
 * excluding unwalked pause legs while keeping lone fixes and antimeridian-crossing tracks.
 */
internal object GpxWaypointSpatialIndex {
    private const val WAYPOINT_THRESHOLD_METERS = 150.0
    private const val METERS_PER_DEGREE = 110_000.0
    private const val STEEPEST_LATITUDE = 89.0
    private const val HALF_TURN = 180.0
    private const val FULL_TURN = 360.0
    private const val CELL_METERS = 2.0 * WAYPOINT_THRESHOLD_METERS
    private const val MOST_CELLS_PER_PIECE = 16
    private const val LOW_32_BITS_MASK = 0xFFFF_FFFFL
    private const val LATITUDE_MARGIN = WAYPOINT_THRESHOLD_METERS / METERS_PER_DEGREE

    fun assignPlaces(
        places: List<GpxWaypoint>,
        routes: List<List<RouteCoordinate>>,
        maxPlaces: Int
    ): List<List<GpxWaypoint>> {
        val buckets = List(routes.size) { ArrayList<GpxWaypoint>() }
        if (places.isEmpty() || routes.isEmpty() || maxPlaces <= 0) return buckets

        val reaches = routes.map { TrackReach.from(it) }
        val grids = routes.map { TrackGrid.create(it) }
        var assignedCount = 0
        var placeIndex = 0
        while (placeIndex < places.size && assignedCount < maxPlaces) {
            val place = places[placeIndex]
            var bestTrackIndex: Int? = null
            var bestDistance = WAYPOINT_THRESHOLD_METERS
            for (index in routes.indices) {
                val distance = if (reaches[index].mayHold(place.coordinate)) {
                    grids[index]?.offRouteMeters(place.coordinate)
                } else {
                    null
                }
                if (distance != null && distance <= bestDistance) {
                    bestDistance = distance
                    bestTrackIndex = index
                }
            }
            if (bestTrackIndex != null) {
                buckets[bestTrackIndex].add(place)
                assignedCount += 1
            }
            placeIndex += 1
        }
        return buckets
    }

    private fun longitudeMargin(nearLatitude: Double): Double {
        val clamped = min(abs(nearLatitude) + LATITUDE_MARGIN, STEEPEST_LATITUDE)
        return WAYPOINT_THRESHOLD_METERS / (METERS_PER_DEGREE * cos(clamped * PI / HALF_TURN))
    }

    private fun unwrapLongitudes(route: List<RouteCoordinate>): DoubleArray {
        val result = DoubleArray(route.size)
        if (route.isEmpty()) return result
        var lon = route.first().longitude
        result[0] = lon
        for (i in 1 until route.size) {
            lon += RouteGeometry.normalizedLongitudeDelta(route[i].longitude - route[i - 1].longitude)
            result[i] = lon
        }
        return result
    }

    private fun buildPieces(route: List<RouteCoordinate>): List<Piece> {
        val runs = route.toTrailRuns()
        val result = ArrayList<Piece>(route.size)
        for (range in runs.walkedRuns) {
            for (index in range.first until range.last) {
                result.add(Piece(start = index, end = index + 1))
            }
        }
        for (loneIndex in runs.loneFixes) {
            result.add(Piece(start = loneIndex, end = loneIndex))
        }
        return result
    }

    private fun pieceDistance(coord: GeoCoordinate, piece: Piece, route: List<RouteCoordinate>): Double {
        val endCoord = route[piece.end].coordinate
        return if (piece.start == piece.end) {
            RouteGeometry.distanceMeters(coord, endCoord)
        } else {
            RouteGeometry.project(coord, route[piece.start].coordinate, endCoord).offRouteMeters
        }
    }

    private data class Piece(val start: Int, val end: Int)

    private class TrackReach private constructor(
        private val south: Double,
        private val north: Double,
        private val west: Double,
        private val span: Double,
        private val wraps: Boolean,
        private val isEmpty: Boolean
    ) {
        fun mayHold(coordinate: GeoCoordinate): Boolean {
            var inside = false
            if (!isEmpty && coordinate.latitude in south..north) {
                inside = if (wraps) {
                    true
                } else {
                    var eastward = (coordinate.longitude - west) % FULL_TURN
                    if (eastward < 0.0) eastward += FULL_TURN
                    eastward <= span
                }
            }
            return inside
        }

        companion object {
            fun from(route: List<RouteCoordinate>): TrackReach {
                if (route.size < 2) {
                    return TrackReach(0.0, 0.0, 0.0, 0.0, wraps = false, isEmpty = true)
                }
                var south = Double.POSITIVE_INFINITY
                var north = Double.NEGATIVE_INFINITY
                for (pt in route) {
                    south = min(south, pt.latitude)
                    north = max(north, pt.latitude)
                }
                var lon = route.first().longitude
                var west = lon
                var east = lon
                for (i in 1 until route.size) {
                    lon += RouteGeometry.normalizedLongitudeDelta(route[i].longitude - route[i - 1].longitude)
                    west = min(west, lon)
                    east = max(east, lon)
                }
                val lonMargin = longitudeMargin(max(abs(south), abs(north)))
                val expandedWest = west - lonMargin
                val span = east - expandedWest + lonMargin
                return TrackReach(
                    south = south - LATITUDE_MARGIN,
                    north = north + LATITUDE_MARGIN,
                    west = expandedWest,
                    span = span,
                    wraps = span >= FULL_TURN,
                    isEmpty = false
                )
            }
        }
    }

    private class TrackGrid private constructor(
        private val route: List<RouteCoordinate>,
        private val pieces: List<Piece>,
        private val cells: Map<Long, List<Int>>,
        private val unfiled: List<Int>,
        private val originLongitude: Double,
        private val latitudeStep: Double,
        private val longitudeStep: Double,
        private val isFiled: Boolean
    ) {
        fun offRouteMeters(coordinate: GeoCoordinate): Double? {
            val lat = coordinate.latitude
            val lon = originLongitude + RouteGeometry.normalizedLongitudeDelta(coordinate.longitude - originLongitude)
            val lonMargin = longitudeMargin(lat)
            val rows = if (isFiled) cellRange(lat - LATITUDE_MARGIN, lat + LATITUDE_MARGIN, latitudeStep) else null
            val cols = if (isFiled) cellRange(lon - lonMargin, lon + lonMargin, longitudeStep) else null
            return if (rows == null || cols == null) {
                scanAll(coordinate)
            } else {
                var nearest = Double.POSITIVE_INFINITY
                for (idx in unfiled) {
                    nearest = min(nearest, pieceDistance(coordinate, pieces[idx], route))
                }
                for (row in rows) {
                    for (col in cols) {
                        val key = (row.toLong() shl Int.SIZE_BITS) or (col.toLong() and LOW_32_BITS_MASK)
                        val bucket = cells[key] ?: continue
                        for (idx in bucket) {
                            nearest = min(nearest, pieceDistance(coordinate, pieces[idx], route))
                        }
                    }
                }
                nearest.takeIf { it.isFinite() }
            }
        }

        private fun scanAll(coordinate: GeoCoordinate): Double? {
            var nearest = Double.POSITIVE_INFINITY
            for (piece in pieces) {
                nearest = min(nearest, pieceDistance(coordinate, piece, route))
            }
            return nearest.takeIf { it.isFinite() }
        }

        companion object {
            fun create(route: List<RouteCoordinate>): TrackGrid? {
                val pieces = if (route.size >= 2) buildPieces(route) else emptyList()
                if (pieces.isEmpty()) return null

                val unwrappedLons = unwrapLongitudes(route)
                var south = Double.POSITIVE_INFINITY
                var north = Double.NEGATIVE_INFINITY
                var west = Double.POSITIVE_INFINITY
                var east = Double.NEGATIVE_INFINITY
                for (i in route.indices) {
                    south = min(south, route[i].latitude)
                    north = max(north, route[i].latitude)
                    west = min(west, unwrappedLons[i])
                    east = max(east, unwrappedLons[i])
                }
                val widestLat = max(abs(south), abs(north))
                val spansWholeHemisphere = east - west + 2.0 * longitudeMargin(widestLat) >= HALF_TURN
                return if (spansWholeHemisphere) {
                    TrackGrid(route, pieces, emptyMap(), emptyList(), route.first().longitude, 0.0, 0.0, false)
                } else {
                    val latStep = CELL_METERS / METERS_PER_DEGREE
                    val centerLat = min(abs((south + north) / 2.0), STEEPEST_LATITUDE)
                    val lonStep = latStep / cos(centerLat * PI / HALF_TURN)
                    val cells = HashMap<Long, MutableList<Int>>(pieces.size)
                    val unfiled = ArrayList<Int>()
                    filePieces(route, unwrappedLons, pieces, latStep, lonStep, cells, unfiled)
                    TrackGrid(route, pieces, cells, unfiled, route.first().longitude, latStep, lonStep, true)
                }
            }

            @Suppress("LongParameterList")
            private fun filePieces(
                route: List<RouteCoordinate>,
                unwrappedLons: DoubleArray,
                pieces: List<Piece>,
                latStep: Double,
                lonStep: Double,
                cells: MutableMap<Long, MutableList<Int>>,
                unfiled: MutableList<Int>
            ) {
                for (pieceIdx in pieces.indices) {
                    val piece = pieces[pieceIdx]
                    val latA = route[piece.start].latitude
                    val latB = route[piece.end].latitude
                    val lonA = unwrappedLons[piece.start]
                    val lonB = unwrappedLons[piece.end]
                    val rows = cellRange(min(latA, latB), max(latA, latB), latStep)
                    val cols = cellRange(min(lonA, lonB), max(lonA, lonB), lonStep)
                    val cellCount = if (rows != null && cols != null) {
                        (rows.last.toLong() - rows.first.toLong() + 1L) *
                            (cols.last.toLong() - cols.first.toLong() + 1L)
                    } else {
                        Long.MAX_VALUE
                    }
                    if (rows == null || cols == null || cellCount > MOST_CELLS_PER_PIECE) {
                        unfiled.add(pieceIdx)
                    } else {
                        for (row in rows) {
                            for (col in cols) {
                                val key = (row.toLong() shl Int.SIZE_BITS) or (col.toLong() and LOW_32_BITS_MASK)
                                cells.getOrPut(key) { ArrayList(2) }.add(pieceIdx)
                            }
                        }
                    }
                }
            }

            private fun cellRange(lower: Double, upper: Double, step: Double): IntRange? {
                val first = floor(lower / step)
                val last = floor(upper / step)
                val valid = first.isFinite() &&
                    last.isFinite() &&
                    first <= last &&
                    abs(first) < Int.MAX_VALUE &&
                    abs(last) < Int.MAX_VALUE
                return if (valid) first.toInt()..last.toInt() else null
            }
        }
    }
}
