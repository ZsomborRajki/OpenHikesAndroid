package app.openhikes.core.model

import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/**
 * Shared map coloring mode applied to the selected hike route (and Trail Maker hiking routes).
 */
@Serializable
enum class RouteColoring(val rawValue: String) {
    DIFFICULTY("difficulty"),
    ELEVATION("elevation"),
    OFF("off");

    /**
     * Maps [ELEVATION] to [DIFFICULTY] when elevation shading is unavailable on a drawing surface.
     */
    fun lockingElevation(locked: Boolean): RouteColoring = if (locked && this == ELEVATION) DIFFICULTY else this

    companion object {
        val Default: RouteColoring = ELEVATION

        val displayOrder: List<RouteColoring> = listOf(DIFFICULTY, ELEVATION, OFF)

        fun fromStoredId(id: String?): RouteColoring = entries.firstOrNull { it.rawValue == id } ?: Default
    }
}

/**
 * Six-step route shading scale shared between SAC difficulty grades and elevation steepness so
 * green through near-black always means "easiest to hardest" across the map and breakdown bars.
 */
enum class RouteShade(val hexColor: String) {
    EASIEST("#2E9E44"),
    EASY("#E6C200"),
    MODERATE("#F57C00"),
    HARD("#D93025"),
    HARDER("#800021"),
    HARDEST("#141417");

    val step: Int
        get() = ordinal

    companion object {
        val scale: List<RouteShade> = listOf(EASIEST, EASY, MODERATE, HARD, HARDER, HARDEST)

        fun fromStep(step: Int): RouteShade = scale[step.coerceIn(0, scale.lastIndex)]
    }
}

/**
 * One unbroken stretch of route painted in a single [RouteShade] step.
 */
data class RouteShadeStretch(val shade: RouteShade, val coordinates: List<GeoCoordinate>)

/**
 * One route-ordered colored polyline span ready for map rendering, carrying either a flat
 * [RouteShade] color or an interpolated hex color bridging two adjacent stretches.
 */
data class BlendedShadeSegment(val colorHex: String, val coordinates: List<GeoCoordinate>)

/**
 * Computes stretch-by-stretch route steepness on the [RouteShade] scale from the route's own
 * elevations, using distance-weighted height smoothing, 200 m grade windows, and short-stretch
 * absorption while strictly respecting pause and missing-elevation boundaries.
 */
object RouteSteepness {
    const val WINDOW_METERS: Double = 200.0
    const val SMOOTHING_RADIUS_METERS: Double = 50.0
    const val MINIMUM_STRETCH_METERS: Double = 300.0

    /**
     * Grade thresholds in percent at which steps 1..5 of [RouteShade.scale] begin.
     */
    val thresholdsPercent: List<Double> = listOf(10.0, 15.0, 20.0, 30.0, 40.0)

    /**
     * Classifies a signed percent grade (uphill or downhill equally) onto [RouteShade].
     */
    fun shadeForGradePercent(gradePercent: Double): RouteShade {
        val magnitude = abs(gradePercent)
        val step = thresholdsPercent.indexOfFirst { magnitude < it }.let { found ->
            if (found >= 0) found else thresholdsPercent.size
        }
        return RouteShade.scale[step]
    }

    /**
     * Lowest percent grade drawn in [shade].
     */
    fun lowerBoundPercent(shade: RouteShade): Double = if (shade.step == 0) 0.0 else thresholdsPercent[shade.step - 1]

    /**
     * Measures contiguous steepness runs along [route], never bridging pauses or missing heights.
     */
    fun runs(route: List<RouteCoordinate>): List<RouteShadeStretch> {
        if (route.size <= 1) return emptyList()
        val heights = smoothedHeights(route)
        val builder = SteepnessRunBuilder(route, heights)
        for (piece in route.toTrailRuns().pieces) {
            measurePiece(route, heights, piece, builder)
        }
        return builder.build()
    }

    /**
     * Distance-weighted average elevation within [SMOOTHING_RADIUS_METERS] on each side of every
     * finite-elevation point, bounded strictly within each contiguous elevation stretch and each
     * pause-delimited [TrailRuns.pieces] slice.
     */
    fun smoothedHeights(route: List<RouteCoordinate>): List<Double?> {
        val smoothed = MutableList<Double?>(route.size) { null }
        for (piece in route.toTrailRuns().pieces) {
            var index = piece.first
            while (index <= piece.last) {
                val ele = route[index].elevationMeters
                if (ele == null || !ele.isFinite()) {
                    index += 1
                    continue
                }
                var end = index + 1
                while (end <= piece.last) {
                    val nextEle = route[end].elevationMeters
                    if (nextEle == null || !nextEle.isFinite()) break
                    end += 1
                }
                smoothStretch(route, startIndex = index, endExclusive = end, into = smoothed)
                index = end
            }
        }
        return smoothed
    }

    private fun measurePiece(
        route: List<RouteCoordinate>,
        heights: List<Double?>,
        piece: IntRange,
        builder: SteepnessRunBuilder
    ) {
        var windowStart: Int? = null
        var windowLength = 0.0
        for (index in piece) {
            val start = windowStart
            when {
                heights[index] == null -> {
                    start?.let { builder.closeTail(it, index - 1, windowLength) }
                    windowStart = null
                    windowLength = 0.0
                }
                start == null -> {
                    windowStart = index
                    windowLength = 0.0
                }
                else -> {
                    windowLength += RouteGeometry.distanceMeters(route[index - 1].coordinate, route[index].coordinate)
                    if (windowLength >= WINDOW_METERS) {
                        builder.add(start, index, windowLength)
                        windowStart = index
                        windowLength = 0.0
                    }
                }
            }
        }
        windowStart?.let { start -> builder.closeTail(start, piece.last, windowLength) }
    }

    private fun smoothStretch(
        route: List<RouteCoordinate>,
        startIndex: Int,
        endExclusive: Int,
        into: MutableList<Double?>
    ) {
        SteepnessSmoothing.smoothStretch(route, startIndex, endExclusive, into)
    }
}

private data class SteepnessPiece(
    var shade: RouteShade,
    var start: Int,
    var end: Int,
    var length: Double,
    var joinsPrevious: Boolean
)

private class SteepnessRunBuilder(private val route: List<RouteCoordinate>, private val heights: List<Double?>) {
    private val pieces = ArrayList<SteepnessPiece>()

    fun add(start: Int, end: Int, length: Double) {
        if (end <= start || length <= 0.0) return
        val from = heights.getOrNull(start)
        val to = heights.getOrNull(end)
        if (from != null && to != null) {
            val shade = RouteSteepness.shadeForGradePercent((to - from) / length * PERCENT_MULTIPLIER)
            append(shade, start, end, length)
        }
    }

    fun closeTail(start: Int, end: Int, length: Double) {
        if (end <= start) return
        val previous = pieces.lastOrNull()
        if (length < RouteSteepness.WINDOW_METERS / HALF_DIVISOR && previous != null && previous.end == start) {
            append(previous.shade, start, end, length)
        } else {
            add(start, end, length)
        }
    }

    fun build(): List<RouteShadeStretch> = SteepnessAbsorption.absorbingShort(pieces).map { piece ->
        RouteShadeStretch(
            shade = piece.shade,
            coordinates = route.subList(piece.start, piece.end + 1).map { it.coordinate }
        )
    }

    private fun append(shade: RouteShade, start: Int, end: Int, length: Double) {
        val previous = pieces.lastOrNull()
        val joins = previous?.end == start
        if (joins && previous.shade == shade) {
            previous.end = end
            previous.length += length
        } else {
            pieces.add(SteepnessPiece(shade, start, end, length, joinsPrevious = joins))
        }
    }

    private companion object {
        const val HALF_DIVISOR = 2.0
        const val PERCENT_MULTIPLIER = 100.0
    }
}

private object SteepnessSmoothing {
    private const val HALF_DIVISOR = 2.0

    fun smoothStretch(route: List<RouteCoordinate>, startIndex: Int, endExclusive: Int, into: MutableList<Double?>) {
        val count = endExclusive - startIndex
        if (count <= 0) return
        val heights = DoubleArray(count) { offset -> route[startIndex + offset].elevationMeters ?: 0.0 }
        val along = DoubleArray(count)
        for (i in 1 until count) {
            along[i] = along[i - 1] + RouteGeometry.distanceMeters(
                route[startIndex + i - 1].coordinate,
                route[startIndex + i].coordinate
            )
        }
        val areas = DoubleArray(count)
        for (i in 1 until count) {
            areas[i] = areas[i - 1] + (along[i] - along[i - 1]) * (heights[i - 1] + heights[i]) / HALF_DIVISOR
        }
        val total = along[count - 1]
        for (offset in 0 until count) {
            val dist = along[offset]
            val radius = min(RouteSteepness.SMOOTHING_RADIUS_METERS, min(dist, total - dist))
            into[startIndex + offset] = if (radius > 0.0) {
                val rightArea = areaUpTo(dist + radius, along, heights, areas)
                val leftArea = areaUpTo(dist - radius, along, heights, areas)
                (rightArea - leftArea) / (HALF_DIVISOR * radius)
            } else {
                heights[offset]
            }
        }
    }

    private fun areaUpTo(distance: Double, along: DoubleArray, heights: DoubleArray, areas: DoubleArray): Double {
        val next = upperBound(along, distance)
        return when {
            next >= along.size -> areas[areas.lastIndex]
            next <= 0 -> 0.0
            else -> {
                val previous = next - 1
                val span = along[next] - along[previous]
                if (span <= 0.0) {
                    areas[previous]
                } else {
                    val covered = distance - along[previous]
                    val reached = heights[previous] + (heights[next] - heights[previous]) * covered / span
                    areas[previous] + covered * (heights[previous] + reached) / HALF_DIVISOR
                }
            }
        }
    }

    private fun upperBound(values: DoubleArray, target: Double): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (values[mid] <= target) low = mid + 1 else high = mid
        }
        return low
    }
}

private object SteepnessAbsorption {
    fun absorbingShort(initial: List<SteepnessPiece>): List<SteepnessPiece> {
        val pieces = initial.mapTo(ArrayList(initial.size)) { it.copy() }
        var shortIndex = findShortestAbsorbableIndex(pieces)
        while (shortIndex != null) {
            val targetIndex = chooseNeighborToAbsorbInto(pieces, shortIndex)
            if (targetIndex == null) {
                shortIndex = null
            } else {
                absorbPiece(pieces, shortIndex, targetIndex)
                shortIndex = findShortestAbsorbableIndex(pieces)
            }
        }
        return pieces
    }

    private fun findShortestAbsorbableIndex(pieces: List<SteepnessPiece>): Int? {
        var bestIndex: Int? = null
        var bestLength = Double.POSITIVE_INFINITY
        for (i in pieces.indices) {
            val piece = pieces[i]
            if (piece.length < RouteSteepness.MINIMUM_STRETCH_METERS && neighborsOf(pieces, i).isNotEmpty()) {
                if (piece.length < bestLength) {
                    bestLength = piece.length
                    bestIndex = i
                }
            }
        }
        return bestIndex
    }

    private fun chooseNeighborToAbsorbInto(pieces: List<SteepnessPiece>, shortIndex: Int): Int? {
        val neighbors = neighborsOf(pieces, shortIndex)
        val shortStep = pieces[shortIndex].shade.step
        return neighbors.minWithOrNull(
            compareBy<Int> { abs(pieces[it].shade.step - shortStep) }
                .thenByDescending { pieces[it].length }
        )
    }

    private fun neighborsOf(pieces: List<SteepnessPiece>, index: Int): List<Int> = buildList(2) {
        if (index - 1 >= 0 && pieces[index].joinsPrevious) add(index - 1)
        if (index + 1 < pieces.size && pieces[index + 1].joinsPrevious) add(index + 1)
    }

    private fun absorbPiece(pieces: MutableList<SteepnessPiece>, shortIndex: Int, intoIndex: Int) {
        val absorbed = pieces.removeAt(shortIndex)
        val kept = if (intoIndex < shortIndex) intoIndex else intoIndex - 1
        pieces[kept].length += absorbed.length
        if (intoIndex < shortIndex) {
            pieces[kept].end = absorbed.end
        } else {
            pieces[kept].start = absorbed.start
            pieces[kept].joinsPrevious = absorbed.joinsPrevious
        }
        val after = kept + 1
        if (after < pieces.size && pieces[after].joinsPrevious && pieces[after].shade == pieces[kept].shade) {
            pieces[kept].end = pieces[after].end
            pieces[kept].length += pieces.removeAt(after).length
        }
        if (kept > 0 && pieces[kept].joinsPrevious && pieces[kept - 1].shade == pieces[kept].shade) {
            pieces[kept - 1].end = pieces[kept].end
            pieces[kept - 1].length += pieces.removeAt(kept).length
        }
    }
}

/**
 * Splits contiguous [RouteShadeStretch] boundaries into smooth RGB transition spans over 25% of
 * the shorter neighbor while preserving exact route draw order (so out-and-back return legs stay
 * painted on top of outward legs) and never blending across gaps or pauses.
 */
object RouteShadeBlend {
    const val REACH_SHARE: Double = 0.25
    const val BLEND_SUBSTEPS: Int = 5

    private const val CHANNEL_MASK = 0xFF
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val COLOR_RADIX = 16
    private const val MIN_COORDINATES = 2

    fun blend(stretches: List<RouteShadeStretch>): List<BlendedShadeSegment> {
        val valid = stretches.filter { it.coordinates.size >= MIN_COORDINATES }
        if (valid.isEmpty()) return emptyList()
        val lengths = DoubleArray(valid.size) { i -> RouteGeometry.lengthMeters(valid[i].coordinates) }
        val jointReaches = DoubleArray(valid.size - 1) { i ->
            val prev = valid[i]
            val next = valid[i + 1]
            val meets = prev.coordinates.last() == next.coordinates.first() && prev.shade != next.shade
            if (meets) REACH_SHARE * min(lengths[i], lengths[i + 1]) else 0.0
        }
        val result = ArrayList<BlendedShadeSegment>(valid.size * 2)
        for (i in valid.indices) {
            val stretch = valid[i]
            val startTrim = if (i > 0) jointReaches[i - 1] else 0.0
            val endTrim = if (i < jointReaches.size) jointReaches[i] else 0.0
            val middle = slicePolyline(stretch.coordinates, startTrim, (lengths[i] - endTrim).coerceAtLeast(startTrim))
            if (middle.size >= MIN_COORDINATES) {
                result.add(BlendedShadeSegment(stretch.shade.hexColor, middle))
            }
            if (endTrim > 0.0) {
                appendTransition(
                    fromStretch = stretch,
                    toStretch = valid[i + 1],
                    fromLength = lengths[i],
                    reach = endTrim,
                    into = result
                )
            }
        }
        return result
    }

    private fun appendTransition(
        fromStretch: RouteShadeStretch,
        toStretch: RouteShadeStretch,
        fromLength: Double,
        reach: Double,
        into: MutableList<BlendedShadeSegment>
    ) {
        val tail = slicePolyline(fromStretch.coordinates, (fromLength - reach).coerceAtLeast(0.0), fromLength)
        val head = slicePolyline(toStretch.coordinates, 0.0, reach)
        val joined = if (tail.size >= MIN_COORDINATES && head.size >= MIN_COORDINATES) {
            tail + head.drop(1)
        } else {
            return
        }
        val totalSpan = RouteGeometry.lengthMeters(joined)
        if (totalSpan <= 0.0) return
        val stepMeters = totalSpan / BLEND_SUBSTEPS.toDouble()
        for (step in 0 until BLEND_SUBSTEPS) {
            val sub = slicePolyline(joined, step * stepMeters, (step + 1) * stepMeters)
            if (sub.size >= MIN_COORDINATES) {
                val t = (step.toDouble() + 0.5) / BLEND_SUBSTEPS.toDouble()
                val hex = interpolateHex(fromStretch.shade.hexColor, toStretch.shade.hexColor, t)
                into.add(BlendedShadeSegment(hex, sub))
            }
        }
    }

    internal fun interpolateHex(startHex: String, endHex: String, fraction: Double): String {
        val t = fraction.coerceIn(0.0, 1.0)
        val start = startHex.removePrefix("#").toInt(COLOR_RADIX)
        val end = endHex.removePrefix("#").toInt(COLOR_RADIX)
        val r = lerpChannel((start ushr RED_SHIFT) and CHANNEL_MASK, (end ushr RED_SHIFT) and CHANNEL_MASK, t)
        val g = lerpChannel((start ushr GREEN_SHIFT) and CHANNEL_MASK, (end ushr GREEN_SHIFT) and CHANNEL_MASK, t)
        val b = lerpChannel(start and CHANNEL_MASK, end and CHANNEL_MASK, t)
        return String.format(Locale.US, "#%02X%02X%02X", r, g, b)
    }

    private fun lerpChannel(a: Int, b: Int, t: Double): Int = (a + (b - a) * t).roundToInt().coerceIn(0, CHANNEL_MASK)

    private fun slicePolyline(
        coords: List<GeoCoordinate>,
        startMeters: Double,
        endMeters: Double
    ): List<GeoCoordinate> {
        if (coords.size < MIN_COORDINATES || endMeters <= startMeters) return emptyList()
        val sliced = ArrayList<GeoCoordinate>()
        var walked = 0.0
        for (i in 0 until coords.lastIndex) {
            val a = coords[i]
            val b = coords[i + 1]
            val segLen = RouteGeometry.distanceMeters(a, b)
            val segEnd = walked + segLen
            if (segLen > 0.0 && segEnd >= startMeters && walked <= endMeters) {
                val localStart = ((startMeters - walked) / segLen).coerceIn(0.0, 1.0)
                val localEnd = ((endMeters - walked) / segLen).coerceIn(0.0, 1.0)
                val p0 = if (localStart == 0.0) a else RouteGeometry.interpolate(a, b, localStart)
                val p1 = if (localEnd == 1.0) b else RouteGeometry.interpolate(a, b, localEnd)
                if (sliced.isEmpty() || sliced.last() != p0) sliced.add(p0)
                if (sliced.last() != p1) sliced.add(p1)
            }
            walked = segEnd
            if (walked >= endMeters) break
        }
        return sliced
    }
}
