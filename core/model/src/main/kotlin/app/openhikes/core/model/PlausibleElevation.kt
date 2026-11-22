package app.openhikes.core.model

import kotlin.math.abs

/**
 * Filters implausible elevation readings and isolated single-point spikes while keeping real terrain.
 */
object PlausibleElevation {
    const val MIN_ELEVATION_METERS = -500.0
    const val MAX_ELEVATION_METERS = 9000.0
    const val SPIKE_METERS = 100.0

    fun validated(elevationMeters: Double?): Double? = elevationMeters
        ?.takeIf { it.isFinite() && it in MIN_ELEVATION_METERS..MAX_ELEVATION_METERS }

    /**
     * Identifies indices whose elevation reading is an isolated placeholder spike
     * (differing by more than [SPIKE_METERS] from both adjacent measured neighbors when
     * those neighbors are within [SPIKE_METERS] of each other and the step slope exceeds 1:1).
     */
    fun spikes(samples: List<Pair<GeoCoordinate, Double?>>): Set<Int> {
        val measured = samples.indices.filter { samples[it].second != null }
        if (measured.size <= 2) return emptySet()

        val result = HashSet<Int>()
        for (pos in 1 until measured.lastIndex) {
            val before = measured[pos - 1]
            val here = measured[pos]
            val after = measured[pos + 1]
            val prevElev = samples[before].second
            val hereElev = samples[here].second
            val nextElev = samples[after].second
            if (prevElev != null && hereElev != null && nextElev != null) {
                val rise = abs(hereElev - prevElev)
                val fall = abs(hereElev - nextElev)
                val neighborsClose = abs(nextElev - prevElev) <= SPIKE_METERS
                if (rise > SPIKE_METERS && fall > SPIKE_METERS && neighborsClose) {
                    val toBefore = segmentDistance(samples, before, here)
                    val toAfter = segmentDistance(samples, here, after)
                    if (rise > toBefore && fall > toAfter) {
                        result.add(here)
                    }
                }
            }
        }
        return result
    }

    /**
     * Returns [route] with isolated elevation spikes cleared to `null`, analyzing one walked piece at a time.
     */
    fun despiked(route: List<RouteCoordinate>): List<RouteCoordinate> {
        if (route.isEmpty()) return route
        val mutable = route.toMutableList()
        val runs = route.toTrailRuns()
        for (piece in runs.pieces) {
            val slice = route.subList(piece.first, piece.last + 1)
                .map { it.coordinate to it.elevationMeters }
            for (offset in spikes(slice)) {
                val index = piece.first + offset
                mutable[index] = mutable[index].copy(elevationMeters = null)
            }
        }
        return mutable
    }

    private fun segmentDistance(samples: List<Pair<GeoCoordinate, Double?>>, startIndex: Int, endIndex: Int): Double {
        var total = 0.0
        for (i in startIndex until endIndex) {
            total += RouteGeometry.distanceMeters(samples[i].first, samples[i + 1].first)
        }
        return total
    }
}
