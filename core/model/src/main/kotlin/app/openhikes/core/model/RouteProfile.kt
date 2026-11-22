package app.openhikes.core.model

import kotlin.math.abs

/**
 * Single plotted elevation profile point along a route's cumulative distance axis.
 */
data class ElevationSample(
    val distanceMeters: Double,
    val elevationMeters: Double,
    val resumesAfterPause: Boolean = false
)

/**
 * Full-resolution elevation extremes and deadband totals for a route.
 */
data class RouteElevationSummary(
    val lowMeters: Double?,
    val highMeters: Double?,
    val gainMeters: Double?,
    val lossMeters: Double?
)

/**
 * Per-segment summary derived from walked pieces separated by pause boundaries.
 */
data class RouteSegmentSummary(
    val index: Int,
    val distanceMeters: Double,
    val elevationGainMeters: Double?,
    val elevationLossMeters: Double?,
    val pointCount: Int
)

/**
 * Result of projecting an arbitrary coordinate onto a walked leg of a [RouteProfile].
 */
data class RouteProjection(
    val distanceAlongRouteMeters: Double,
    val offRouteMeters: Double,
    val projectedCoordinate: GeoCoordinate
)

/**
 * Precomputed distance and elevation index for a hike route.
 * Built off the main thread once per route revision so chart scrubbing resolves in O(log n).
 */
class RouteProfile private constructor(
    val coordinates: List<GeoCoordinate>,
    val distances: List<Double>,
    val samples: List<ElevationSample>,
    val pauseDistances: List<Double>,
    val pausedSegments: Set<Int>,
    val elevation: RouteElevationSummary,
    val segments: List<RouteSegmentSummary>,
    val boundingBox: GeoBoundingBox?
) {
    private val sampleDistances: List<Double> = samples.map { it.distanceMeters }

    val totalDistanceMeters: Double
        get() = distances.lastOrNull() ?: 0.0

    val elevationRange: ClosedFloatingPointRange<Double>?
        get() {
            var minElev = Double.POSITIVE_INFINITY
            var maxElev = Double.NEGATIVE_INFINITY
            var found = false
            for (sample in samples) {
                val elev = sample.elevationMeters
                if (!elev.isFinite()) continue
                if (elev < minElev) minElev = elev
                if (elev > maxElev) maxElev = elev
                found = true
            }
            return if (found) minElev..maxElev else null
        }

    fun fractionComplete(distanceMeters: Double): Double? {
        val total = totalDistanceMeters
        if (total <= 0.0) return null
        return (distanceMeters / total).coerceIn(0.0, 1.0)
    }

    fun remainingDistanceMeters(distanceMeters: Double): Double =
        (totalDistanceMeters - distanceMeters).coerceAtLeast(0.0)

    fun coordinateAt(distanceMeters: Double): GeoCoordinate? {
        val firstDistance = distances.firstOrNull()
        val lastDistance = distances.lastOrNull()
        return if (firstDistance == null || lastDistance == null) {
            null
        } else {
            when {
                distanceMeters <= firstDistance -> coordinates.firstOrNull()
                distanceMeters >= lastDistance -> coordinates[lowerBound(distances, lastDistance)]
                else -> {
                    val upper = lowerBound(distances, distanceMeters).coerceIn(1, distances.lastIndex)
                    val lower = upper - 1
                    val span = distances[upper] - distances[lower]
                    if (span <= 0.0) {
                        coordinates[upper]
                    } else {
                        val fraction = (distanceMeters - distances[lower]) / span
                        RouteGeometry.interpolate(coordinates[lower], coordinates[upper], fraction)
                    }
                }
            }
        }
    }

    fun sampleAt(distanceMeters: Double): ElevationSample? = if (samples.isEmpty()) {
        null
    } else {
        samples[nearestIndex(sampleDistances, distanceMeters)]
    }

    fun nearestPoint(coordinate: GeoCoordinate): RouteProjection? = when {
        coordinates.isEmpty() -> null
        coordinates.size == 1 -> {
            val only = coordinates[0]
            RouteProjection(
                distanceAlongRouteMeters = 0.0,
                offRouteMeters = RouteGeometry.distanceMeters(coordinate, only),
                projectedCoordinate = only
            )
        }
        else -> projectOntoWalkedSegments(coordinate)
    }

    private fun projectOntoWalkedSegments(coordinate: GeoCoordinate): RouteProjection? {
        var bestDistance = Double.POSITIVE_INFINITY
        var bestIndex = -1
        var bestFraction = 0.0
        var bestAlong = 0.0

        for (index in 0 until coordinates.lastIndex) {
            if (pausedSegments.contains(index)) continue
            val proj = RouteGeometry.project(coordinate, coordinates[index], coordinates[index + 1])
            if (proj.offRouteMeters < bestDistance) {
                bestDistance = proj.offRouteMeters
                bestIndex = index
                bestFraction = proj.fraction
                bestAlong = distances[index] + proj.fraction * (distances[index + 1] - distances[index])
            }
        }
        return if (bestIndex < 0) {
            null
        } else {
            val projected = RouteGeometry.interpolate(
                coordinates[bestIndex],
                coordinates[bestIndex + 1],
                bestFraction
            )
            RouteProjection(
                distanceAlongRouteMeters = bestAlong,
                offRouteMeters = bestDistance,
                projectedCoordinate = projected
            )
        }
    }

    private fun lowerBound(list: List<Double>, target: Double): Int {
        var low = 0
        var high = list.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (list[mid] < target) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        return low
    }

    private fun nearestIndex(sorted: List<Double>, target: Double): Int {
        val pos = lowerBound(sorted, target)
        return when {
            pos == 0 -> 0
            pos >= sorted.size -> sorted.lastIndex
            else -> {
                val before = pos - 1
                if (abs(sorted[pos] - target) < abs(target - sorted[before])) pos else before
            }
        }
    }

    companion object {
        const val PLOTTED_SAMPLE_BUDGET = 500
        private const val ENDPOINT_RESERVE = 2
        private const val BUCKET_SAMPLE_COUNT = 2

        fun from(route: List<RouteCoordinate>): RouteProfile {
            val runs = route.toTrailRuns()
            val pausedSegments = runs.pausedLegs.toSet()
            val coordinates = ArrayList<GeoCoordinate>(route.size)
            val distances = ArrayList<Double>(route.size)
            val rawSamples = ArrayList<ElevationSample>()
            val pauseDistances = ArrayList<Double>()
            val accumulator = ElevationAccumulator()

            var cumulative = 0.0
            var previous: RouteCoordinate? = null
            for (index in route.indices) {
                val point = route[index]
                val coord = point.coordinate
                val leg = previous?.let { RouteGeometry.legMeters(it, point) } ?: 0.0
                cumulative += leg
                previous = point
                coordinates.add(coord)
                distances.add(cumulative)

                val resumes = pausedSegments.contains(index - 1)
                if (resumes && pauseDistances.lastOrNull() != cumulative) {
                    pauseDistances.add(cumulative)
                }
                accumulator.record(point)
                val elev = point.elevationMeters
                if (elev != null && elev.isFinite()) {
                    appendSample(
                        ElevationSample(
                            distanceMeters = cumulative,
                            elevationMeters = elev,
                            resumesAfterPause = resumes
                        ),
                        rawSamples,
                        mergingRepeats = false
                    )
                }
            }

            val elevation = RouteElevationSummary(
                lowMeters = accumulator.minimumMeters,
                highMeters = accumulator.maximumMeters,
                gainMeters = if (accumulator.hasChange) accumulator.gainMeters else null,
                lossMeters = if (accumulator.hasChange) accumulator.lossMeters else null
            )
            return RouteProfile(
                coordinates = coordinates,
                distances = distances,
                samples = downsampleForDrawing(rawSamples),
                pauseDistances = pauseDistances,
                pausedSegments = pausedSegments,
                elevation = elevation,
                segments = buildSegmentSummaries(route, runs),
                boundingBox = GeoBoundingBox.from(coordinates)
            )
        }

        private fun buildSegmentSummaries(route: List<RouteCoordinate>, runs: TrailRuns): List<RouteSegmentSummary> =
            runs.pieces.mapIndexed {
                    segmentIdx,
                    range
                ->
                val slice = route.subList(range.first, range.last + 1)
                val distance = RouteGeometry.routeLengthMeters(slice)
                val elevAcc = ElevationAccumulator()
                for (pt in slice) {
                    elevAcc.record(pt.elevationMeters)
                }
                RouteSegmentSummary(
                    index = segmentIdx,
                    distanceMeters = distance,
                    elevationGainMeters = if (elevAcc.hasChange) elevAcc.gainMeters else null,
                    elevationLossMeters = if (elevAcc.hasChange) elevAcc.lossMeters else null,
                    pointCount = slice.size
                )
            }

        private fun appendSample(
            sample: ElevationSample,
            series: MutableList<ElevationSample>,
            mergingRepeats: Boolean
        ) {
            val last = series.lastOrNull()
            if (last == null || sample.distanceMeters > last.distanceMeters) {
                series.add(sample)
                return
            }
            if (sample.resumesAfterPause && !last.resumesAfterPause) {
                series.add(sample)
            } else if (last.resumesAfterPause || sample.resumesAfterPause || mergingRepeats) {
                series[series.lastIndex] = ElevationSample(
                    distanceMeters = sample.distanceMeters,
                    elevationMeters = sample.elevationMeters,
                    resumesAfterPause = last.resumesAfterPause || sample.resumesAfterPause
                )
            } else {
                series.add(sample)
            }
        }

        private fun downsampleForDrawing(samples: List<ElevationSample>): List<ElevationSample> {
            val bucketCount = (PLOTTED_SAMPLE_BUDGET - ENDPOINT_RESERVE) / BUCKET_SAMPLE_COUNT
            if (samples.size <= PLOTTED_SAMPLE_BUDGET || bucketCount <= 0) return samples

            val lastIndex = samples.lastIndex
            val interiorCount = lastIndex - 1
            val picked = ArrayList<Int>(PLOTTED_SAMPLE_BUDGET)
            picked.add(0)

            for (bucket in 0 until bucketCount) {
                val start = 1 + interiorCount * bucket / bucketCount
                val end = 1 + interiorCount * (bucket + 1) / bucketCount
                if (start >= end) continue

                var lowest = start
                var highest = start
                for (index in start until end) {
                    if (samples[index].elevationMeters < samples[lowest].elevationMeters) lowest = index
                    if (samples[index].elevationMeters > samples[highest].elevationMeters) highest = index
                }
                if (lowest == highest) {
                    picked.add(lowest)
                } else {
                    picked.add(minOf(lowest, highest))
                    picked.add(maxOf(lowest, highest))
                }
            }
            picked.add(lastIndex)

            val plotted = ArrayList<ElevationSample>(picked.size)
            var previousIndex = -1
            for (index in picked) {
                val sample = samples[index]
                val resumes = ((previousIndex + 1)..index).any { samples[it].resumesAfterPause }
                previousIndex = index
                appendSample(
                    sample.copy(resumesAfterPause = resumes),
                    plotted,
                    mergingRepeats = true
                )
            }
            return plotted
        }
    }
}
