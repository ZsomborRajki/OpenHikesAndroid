package app.openhikes.core.model

/**
 * Splits a route into walked stretches and paused gaps using explicit [RouteBoundary.PAUSED]
 * markers so distance, elevation, matching, rendering, and GPX export share one boundary rule.
 */
class TrailRuns(pointCount: Int, pauseIndices: Iterable<Int>) {
    val pointCount: Int = pointCount.coerceAtLeast(0)

    /**
     * Indices of legs opened by a pause (`leg i` connects point `i` to `i + 1`).
     */
    val pausedLegs: List<Int> = pauseIndices
        .filter { it in 1 until this.pointCount }
        .map { it - 1 }
        .distinct()
        .sorted()

    /**
     * All point index ranges cut at every paused leg, including single-point segments.
     */
    val pieces: List<IntRange> = buildPieces(this.pointCount, pausedLegs)

    /**
     * Walked stretches containing two or more points (`count >= 2`).
     */
    val walkedRuns: List<IntRange> = pieces.filter { it.first < it.last }

    /**
     * Contiguous unwalked paused stretches from the departure point to the resume point.
     */
    val pausedStretches: List<IntRange> = buildPausedStretches(pausedLegs)

    /**
     * Single-point fixes bounded on both sides by pauses or line ends.
     */
    val loneFixes: List<Int> = pieces.filter { it.first == it.last }.map { it.first }

    val isPaused: Boolean
        get() = pausedLegs.isNotEmpty()

    private companion object {
        fun buildPieces(pointCount: Int, pausedLegs: List<Int>): List<IntRange> {
            if (pointCount <= 0) return emptyList()
            val result = ArrayList<IntRange>(pausedLegs.size + 1)
            var start = 0
            for (leg in pausedLegs) {
                result.add(start..leg)
                start = leg + 1
            }
            result.add(start until pointCount)
            return result
        }

        fun buildPausedStretches(pausedLegs: List<Int>): List<IntRange> {
            if (pausedLegs.isEmpty()) return emptyList()
            val stretches = ArrayList<IntRange>()
            for (leg in pausedLegs) {
                val last = stretches.lastOrNull()
                if (last != null && last.last == leg) {
                    stretches[stretches.lastIndex] = last.first..(leg + 1)
                } else {
                    stretches.add(leg..(leg + 1))
                }
            }
            return stretches
        }
    }
}

/**
 * Builds [TrailRuns] from a route's [RouteCoordinate.isPauseBoundary] markers.
 */
fun List<RouteCoordinate>.toTrailRuns(): TrailRuns {
    val pauseIndices = indices.filter { this[it].isPauseBoundary }
    return TrailRuns(pointCount = size, pauseIndices = pauseIndices)
}
