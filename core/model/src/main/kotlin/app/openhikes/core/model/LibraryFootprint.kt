package app.openhikes.core.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Compact antimeridian-aware bounding box of a hike's clocked segment or a walk's covered
 * stretches, used by [LibraryTotals] to distinguish same-ground overlap from an unrelated timed
 * track recorded during the same hours.
 */
data class LibraryFootprint(
    val southLat: Double,
    val northLat: Double,
    /** West edge normalized to `[-180, 180)`. */
    val westLon: Double,
    /** Degrees east from [westLon] to the east edge, in `[0, 360]`. */
    val lonSpan: Double
) {
    /**
     * Symmetric proximity test: whether this footprint and [other] come within [marginMeters].
     * Longitude padding uses the poleward edge of the shared latitude band so the answer is
     * identical regardless of receiver order.
     */
    fun meets(other: LibraryFootprint, marginMeters: Double): Boolean {
        val latitudePad = marginMeters / RouteGeometry.METERS_PER_DEGREE_LATITUDE
        val bandSouth = max(southLat, other.southLat)
        val bandNorth = min(northLat, other.northLat)
        if (bandSouth - bandNorth > latitudePad) return false

        val poleward = min(MAX_LATITUDE, max(abs(bandSouth), abs(bandNorth)) + latitudePad)
        val cosine = cos(poleward * PI / HALF_TURN_DEGREES)
        val longitudePad = if (cosine > 0.0) latitudePad / cosine else Double.POSITIVE_INFINITY
        val paddedSpan = lonSpan + DOUBLE_FACTOR * longitudePad
        return if (paddedSpan >= FULL_TURN_DEGREES || other.lonSpan >= FULL_TURN_DEGREES) {
            true
        } else {
            var offset = (other.westLon - (westLon - longitudePad)) % FULL_TURN_DEGREES
            if (offset < 0.0) offset += FULL_TURN_DEGREES
            offset <= paddedSpan || offset + other.lonSpan >= FULL_TURN_DEGREES
        }
    }

    /**
     * Block-indexed representation of a followed trail so repeated walk footprints on a long trail
     * avoid rescanning every interior point while producing bit-identical bounds.
     */
    class Line(private val profile: RouteProfile) {
        private val blocks: List<BoundsAccumulator> = buildBlocks(profile.coordinates)

        fun footprint(covering: List<ClosedFloatingPointRange<Double>>): LibraryFootprint? {
            if (covering.isEmpty() || profile.coordinates.isEmpty()) return null
            val bounds = BoundsAccumulator()
            for (stretch in covering) {
                if (!stretch.start.isFinite() || !stretch.endInclusive.isFinite()) continue
                profile.coordinateAt(stretch.start)?.let(bounds::add)
                profile.coordinateAt(stretch.endInclusive)?.let(bounds::add)
                addPoints(pointsPassed(stretch), bounds)
            }
            return bounds.toFootprint()
        }

        private fun pointsPassed(stretch: ClosedFloatingPointRange<Double>): IntRange {
            val distances = profile.distances
            val first = lowerBound(distances, stretch.start)
            val past = upperBound(distances, stretch.endInclusive)
            return first until past
        }

        private fun addPoints(range: IntRange, bounds: BoundsAccumulator) {
            var index = range.first
            val endExclusive = range.last + 1
            while (index < endExclusive) {
                if (index % BLOCK_LENGTH == 0 && index + BLOCK_LENGTH <= endExclusive) {
                    bounds.add(blocks[index / BLOCK_LENGTH])
                    index += BLOCK_LENGTH
                } else {
                    bounds.add(profile.coordinates[index])
                    index += 1
                }
            }
        }

        private companion object {
            const val BLOCK_LENGTH = 64

            fun buildBlocks(coordinates: List<GeoCoordinate>): List<BoundsAccumulator> {
                if (coordinates.isEmpty()) return emptyList()
                val blockCount = (coordinates.size + BLOCK_LENGTH - 1) / BLOCK_LENGTH
                val result = ArrayList<BoundsAccumulator>(blockCount)
                var start = 0
                while (start < coordinates.size) {
                    val end = min(start + BLOCK_LENGTH, coordinates.size)
                    val acc = BoundsAccumulator()
                    for (idx in start until end) {
                        acc.add(coordinates[idx])
                    }
                    result.add(acc)
                    start = end
                }
                return result
            }

            fun lowerBound(sorted: List<Double>, target: Double): Int {
                var low = 0
                var high = sorted.size
                while (low < high) {
                    val mid = (low + high) ushr 1
                    if (sorted[mid] < target) low = mid + 1 else high = mid
                }
                return low
            }

            fun upperBound(sorted: List<Double>, target: Double): Int {
                var low = 0
                var high = sorted.size
                while (low < high) {
                    val mid = (low + high) ushr 1
                    if (sorted[mid] <= target) low = mid + 1 else high = mid
                }
                return low
            }
        }
    }

    private class BoundsAccumulator {
        private var south = Double.POSITIVE_INFINITY
        private var north = Double.NEGATIVE_INFINITY
        private var west = Double.POSITIVE_INFINITY
        private var east = Double.NEGATIVE_INFINITY
        private var westFromZero = Double.POSITIVE_INFINITY
        private var eastFromZero = Double.NEGATIVE_INFINITY

        fun add(point: GeoCoordinate) {
            if (!point.latitude.isFinite() || abs(point.latitude) > MAX_LATITUDE || !point.longitude.isFinite()) {
                return
            }
            south = min(south, point.latitude)
            north = max(north, point.latitude)
            val normalized = normalizeHalfOpen(point.longitude)
            west = min(west, normalized)
            east = max(east, normalized)
            val fromZero = if (normalized < 0.0) normalized + FULL_TURN_DEGREES else normalized
            westFromZero = min(westFromZero, fromZero)
            eastFromZero = max(eastFromZero, fromZero)
        }

        fun add(other: BoundsAccumulator) {
            if (other.south > other.north) return
            south = min(south, other.south)
            north = max(north, other.north)
            west = min(west, other.west)
            east = max(east, other.east)
            westFromZero = min(westFromZero, other.westFromZero)
            eastFromZero = max(eastFromZero, other.eastFromZero)
        }

        fun toFootprint(): LibraryFootprint? {
            if (south > north) return null
            val standardSpan = east - west
            val wrappedSpan = eastFromZero - westFromZero
            return if (wrappedSpan < standardSpan) {
                LibraryFootprint(
                    southLat = south,
                    northLat = north,
                    westLon = normalizeHalfOpen(westFromZero),
                    lonSpan = wrappedSpan
                )
            } else {
                LibraryFootprint(
                    southLat = south,
                    northLat = north,
                    westLon = west,
                    lonSpan = standardSpan
                )
            }
        }
    }

    companion object {
        private const val MAX_LATITUDE = 90.0
        private const val HALF_TURN_DEGREES = 180.0
        private const val FULL_TURN_DEGREES = 360.0
        private const val DOUBLE_FACTOR = 2.0

        /**
         * Bounds of [route] strictly from its first timestamped point to its last.
         */
        fun clockedPartOf(route: List<RouteCoordinate>): LibraryFootprint? {
            val first = route.indexOfFirst { it.timestampEpochMillis != null }
            if (first < 0) return null
            val last = route.indexOfLast { it.timestampEpochMillis != null }
            val bounds = BoundsAccumulator()
            for (index in first..last) {
                bounds.add(route[index].coordinate)
            }
            return bounds.toFootprint()
        }

        fun fromCoordinates(points: Iterable<GeoCoordinate>): LibraryFootprint? {
            val bounds = BoundsAccumulator()
            for (point in points) {
                bounds.add(point)
            }
            return bounds.toFootprint()
        }

        private fun normalizeHalfOpen(longitude: Double): Double {
            var normalized = RouteGeometry.normalizedLongitude(longitude)
            if (normalized >= HALF_TURN_DEGREES) normalized -= FULL_TURN_DEGREES
            return normalized
        }
    }
}
