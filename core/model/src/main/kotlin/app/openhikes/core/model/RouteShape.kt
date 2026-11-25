package app.openhikes.core.model

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Geometric classification of a walked or drawn route: loop, out-and-back, or point-to-point.
 */
enum class RouteShape {
    /** Ends where it began via distinct ground. */
    LOOP,

    /** Ends where it began by retracing the outward path. */
    OUT_AND_BACK,

    /** Ends away from its start. */
    POINT_TO_POINT;

    /**
     * Leg opened by a pause boundary between the last point before the pause and first point after it.
     */
    data class PausedLeg(val from: GeoCoordinate, val to: GeoCoordinate) {
        val keepsTheWalkInPlace: Boolean
            get() {
                val dist = RouteGeometry.distanceMeters(from, to)
                return dist.isFinite() && dist <= CLOSING_TOLERANCE_METERS
            }
    }

    companion object {
        /** Maximum separation between start and end for a route to count as returning to its start. */
        const val CLOSING_TOLERANCE_METERS = 150.0
        internal const val RETRACED_SHARE = 0.5
        internal const val SAMPLE_SPACING_METERS = 10.0
        internal const val MAXIMUM_SAMPLES = 20_000
        private const val CELL_SPACINGS = 2.0
        private const val COMPASS_SECTORS = 8
        private const val AGAINST_SECTOR_MIN = 3
        private const val AGAINST_SECTOR_MAX = 5
        private const val TWO_PI = 2.0 * PI

        fun of(route: List<RouteCoordinate>): RouteShape? {
            val coordinates = route.map { it.coordinate }
            val pausedLegs = route.toTrailRuns().pausedLegs.map { leg ->
                PausedLeg(from = route[leg].coordinate, to = route[leg + 1].coordinate)
            }
            return of(coordinates, pausedLegs)
        }

        fun of(coordinates: List<GeoCoordinate>, pausedLegs: List<PausedLeg> = emptyList()): RouteShape? {
            if (coordinates.size <= 1 || !pausedLegs.all { it.keepsTheWalkInPlace }) return null
            val start = coordinates.first()
            val gap = RouteGeometry.distanceMeters(start, coordinates.last())
            return when {
                !gap.isFinite() -> null
                gap > CLOSING_TOLERANCE_METERS -> POINT_TO_POINT
                else -> classifyClosedRoute(start, coordinates)
            }
        }

        private fun classifyClosedRoute(start: GeoCoordinate, coordinates: List<GeoCoordinate>): RouteShape? {
            val line = coordinates
                .map { RouteGeometry.localOffset(start, it) }
                .filter { (x, y) -> x.isFinite() && y.isFinite() }
            val reach = line.fold(0.0) { acc, (x, y) -> max(acc, hypot(x, y)) }
            if (reach <= CLOSING_TOLERANCE_METERS) return null

            var length = 0.0
            for (index in 1 until line.size) {
                val (prevX, prevY) = line[index - 1]
                val (curX, curY) = line[index]
                length += hypot(curX - prevX, curY - prevY)
            }
            val spacing = max(SAMPLE_SPACING_METERS, length / MAXIMUM_SAMPLES.toDouble())
            val samples = sampleAlong(line, spacing)
            val half = samples.size / 2
            val wayBackCount = samples.size - half
            return if (wayBackCount <= 0) {
                null
            } else {
                val retraced = countRetracedSamples(samples, half, cellSize = spacing * CELL_SPACINGS)
                if (retraced.toDouble() >= wayBackCount.toDouble() * RETRACED_SHARE) OUT_AND_BACK else LOOP
            }
        }

        private fun countRetracedSamples(samples: List<Pair<Double, Double>>, half: Int, cellSize: Double): Int {
            val covered = HashMap<GridCell, Int>(half)
            for (index in 0 until half) {
                val cell = GridCell.of(samples[index], cellSize)
                covered[cell] = (covered[cell] ?: 0) or headingMask(index, samples)
            }
            var retraced = 0
            for (index in half until samples.size) {
                val against = againstMask(headingMask(index, samples))
                val center = GridCell.of(samples[index], cellSize)
                if (center.matchesNeighborhood { cell -> ((covered[cell] ?: 0) and against) != 0 }) {
                    retraced += 1
                }
            }
            return retraced
        }

        private fun headingMask(index: Int, samples: List<Pair<Double, Double>>): Int {
            val from = samples[max(index - 1, 0)]
            val to = samples[min(index + 1, samples.lastIndex)]
            val angle = atan2(to.second - from.second, to.first - from.first)
            val turn = (angle + PI) / TWO_PI
            val sector = floor(turn * COMPASS_SECTORS).toInt()
            val wrapped = ((sector % COMPASS_SECTORS) + COMPASS_SECTORS) % COMPASS_SECTORS
            return 1 shl wrapped
        }

        private fun againstMask(singleSectorMask: Int): Int {
            val sector = singleSectorMask.countTrailingZeroBits()
            var mask = 0
            for (step in AGAINST_SECTOR_MIN..AGAINST_SECTOR_MAX) {
                val wrapped = (((sector + step) % COMPASS_SECTORS) + COMPASS_SECTORS) % COMPASS_SECTORS
                mask = mask or (1 shl wrapped)
            }
            return mask
        }

        private fun sampleAlong(line: List<Pair<Double, Double>>, spacing: Double): List<Pair<Double, Double>> {
            val first = line.firstOrNull() ?: return emptyList()
            val result = ArrayList<Pair<Double, Double>>()
            result.add(first)
            var untilNext = spacing
            for (index in 1 until line.size) {
                val from = line[index - 1]
                val to = line[index]
                val legLength = hypot(to.first - from.first, to.second - from.second)
                if (legLength <= 0.0) continue
                while (untilNext <= legLength) {
                    val fraction = untilNext / legLength
                    result.add(
                        (from.first + (to.first - from.first) * fraction) to
                            (from.second + (to.second - from.second) * fraction)
                    )
                    untilNext += spacing
                }
                untilNext -= legLength
            }
            return result
        }

        private data class GridCell(val column: Int, val row: Int) {
            fun matchesNeighborhood(predicate: (GridCell) -> Boolean): Boolean {
                for (dc in -1..1) {
                    for (dr in -1..1) {
                        if (predicate(GridCell(column + dc, row + dr))) return true
                    }
                }
                return false
            }

            companion object {
                fun of(point: Pair<Double, Double>, size: Double): GridCell = GridCell(
                    column = floor(point.first / size).toInt(),
                    row = floor(point.second / size).toInt()
                )
            }
        }
    }
}
