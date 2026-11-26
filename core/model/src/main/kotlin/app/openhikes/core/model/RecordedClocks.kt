package app.openhikes.core.model

import kotlin.math.max
import kotlin.math.min

/**
 * Balanced implicit interval tree over counted hike clocks, allowing [LibraryTotals] to measure
 * the union of overlapping same-ground clocks for each walk in `O(log n + k)` instead of `O(n)`.
 */
class RecordedClocks(clocks: List<Clock>) {
    data class Clock(val startEpochMillis: Long, val endEpochMillis: Long, val footprint: LibraryFootprint?)

    private val sortedClocks: List<Clock> = clocks
        .filter { it.endEpochMillis > it.startEpochMillis }
        .sortedBy { it.startEpochMillis }

    private val latestEnds: LongArray = LongArray(sortedClocks.size) { sortedClocks[it].endEpochMillis }.also {
        gatherLatestEnds(it, 0, sortedClocks.size)
    }

    /**
     * Exact union duration in seconds over `[startEpochMillis, endEpochMillis)` covered by clocks
     * satisfying [counts].
     */
    fun overlapSeconds(startEpochMillis: Long, endEpochMillis: Long, counts: (Clock) -> Boolean): Double {
        if (endEpochMillis <= startEpochMillis || sortedClocks.isEmpty()) return 0.0
        var totalMillis = 0L
        var reached = startEpochMillis
        visit(startEpochMillis, endEpochMillis, 0, sortedClocks.size) { clock ->
            if (!counts(clock)) return@visit
            val from = max(reached, clock.startEpochMillis)
            val to = min(endEpochMillis, clock.endEpochMillis)
            if (to > from) {
                totalMillis += to - from
                reached = to
            }
        }
        return totalMillis / MILLIS_PER_SECOND
    }

    private fun visit(
        startEpochMillis: Long,
        endEpochMillis: Long,
        low: Int,
        highExclusive: Int,
        body: (Clock) -> Unit
    ) {
        if (low >= highExclusive) return
        val middle = low + (highExclusive - low) / 2
        if (latestEnds[middle] <= startEpochMillis) return
        visit(startEpochMillis, endEpochMillis, low, middle, body)
        val pivot = sortedClocks[middle]
        if (pivot.startEpochMillis < endEpochMillis) {
            if (pivot.endEpochMillis > startEpochMillis) {
                body(pivot)
            }
            visit(startEpochMillis, endEpochMillis, middle + 1, highExclusive, body)
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0

        fun gatherLatestEnds(latestEnds: LongArray, low: Int, highExclusive: Int): Long? {
            if (low >= highExclusive) return null
            val middle = low + (highExclusive - low) / 2
            gatherLatestEnds(latestEnds, low, middle)?.let { before ->
                latestEnds[middle] = max(latestEnds[middle], before)
            }
            gatherLatestEnds(latestEnds, middle + 1, highExclusive)?.let { after ->
                latestEnds[middle] = max(latestEnds[middle], after)
            }
            return latestEnds[middle]
        }
    }
}
