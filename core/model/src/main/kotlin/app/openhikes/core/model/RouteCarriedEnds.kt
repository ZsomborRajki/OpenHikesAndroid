package app.openhikes.core.model

import kotlin.math.max

/**
 * Leading and trailing non-walked stretches of a route (e.g., driving home with recording still running),
 * offered as one-tap trim actions in Edit Route.
 */
data class RouteCarriedEnds(val leading: Stretch? = null, val trailing: Stretch? = null) {
    /**
     * Single carried stretch at either end of the line.
     */
    data class Stretch(val startMeters: Double, val endMeters: Double, val carriedMeters: Double) {
        val range: ClosedFloatingPointRange<Double>
            get() = startMeters..max(startMeters, endMeters)
    }

    /**
     * Detection thresholds aligning motion tags, speed, and pause-jump heuristics with iOS OpenHikes.
     */
    data class Policy(
        val carriedMetersPerSecond: Double = DEFAULT_CARRIED_MPS,
        val minimumCarriedMeters: Double = DEFAULT_MIN_CARRIED_METERS,
        val maximumFootGapMeters: Double = DEFAULT_MAX_FOOT_GAP_METERS,
        val pauseJumpMeters: Double = DEFAULT_PAUSE_JUMP_METERS,
        val minimumWalkMeters: Double = DEFAULT_MIN_WALK_METERS
    ) {
        companion object {
            const val DEFAULT_CARRIED_MPS = 6.0
            const val DEFAULT_MIN_CARRIED_METERS = 500.0
            const val DEFAULT_MAX_FOOT_GAP_METERS = 200.0
            const val DEFAULT_PAUSE_JUMP_METERS = 1_000.0
            const val DEFAULT_MIN_WALK_METERS = 300.0
        }
    }

    val isEmpty: Boolean
        get() = leading == null && trailing == null

    fun walkedRange(totalMeters: Double): ClosedFloatingPointRange<Double> {
        val start = leading?.endMeters ?: 0.0
        val end = trailing?.startMeters ?: totalMeters
        return start..max(start, end)
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1_000.0

        fun found(
            route: List<RouteCoordinate>,
            distances: List<Double> = RouteTrim.distances(route),
            policy: Policy = Policy()
        ): RouteCarriedEnds {
            val total = distances.lastOrNull()
            if (route.size <= 1 || distances.size != route.size || total == null) {
                return RouteCarriedEnds()
            }
            val legs = ArrayList<Leg>(route.size - 1)
            for (index in 1 until route.size) {
                val meters = distances[index] - distances[index - 1]
                legs.add(classifyLeg(route[index - 1], route[index], meters, policy))
            }
            val opening = carriedRun(legs, policy)?.let { run ->
                Stretch(
                    startMeters = 0.0,
                    endMeters = distances[run.legCount],
                    carriedMeters = run.carriedMeters
                )
            }
            val closing = carriedRun(legs.asReversed(), policy)?.let { run ->
                Stretch(
                    startMeters = distances[legs.size - run.legCount],
                    endMeters = total,
                    carriedMeters = run.carriedMeters
                )
            }
            val ends = RouteCarriedEnds(leading = opening, trailing = closing)
            val walked = ends.walkedRange(total)
            return if (walked.endInclusive - walked.start >= policy.minimumWalkMeters) {
                ends
            } else {
                RouteCarriedEnds()
            }
        }

        private fun classifyLeg(
            previous: RouteCoordinate,
            current: RouteCoordinate,
            meters: Double,
            policy: Policy
        ): Leg {
            val carried = when {
                current.isPauseBoundary -> {
                    val jump = RouteGeometry.distanceMeters(previous.coordinate, current.coordinate)
                    if (jump >= policy.pauseJumpMeters) jump else null
                }
                current.motion == RouteMotion.NON_PEDESTRIAN || isFast(previous, current, meters, policy) -> meters
                else -> null
            }
            return Leg(meters = meters, carriedMeters = carried)
        }

        private fun isFast(
            previous: RouteCoordinate,
            current: RouteCoordinate,
            meters: Double,
            policy: Policy
        ): Boolean {
            val began = previous.timestampEpochMillis
            val arrived = current.timestampEpochMillis
            val seconds = if (began != null && arrived != null) (arrived - began) / MILLIS_PER_SECOND else 0.0
            return seconds > 0.0 && meters / seconds >= policy.carriedMetersPerSecond
        }

        private fun carriedRun(legs: List<Leg>, policy: Policy): Run? {
            var legCount = 0
            var carriedMeters = 0.0
            var footGap = 0.0
            for (index in legs.indices) {
                val leg = legs[index]
                if (leg.carriedMeters != null) {
                    legCount = index + 1
                    carriedMeters += leg.carriedMeters
                    footGap = 0.0
                } else {
                    footGap += leg.meters
                    if (footGap > policy.maximumFootGapMeters) break
                }
            }
            return if (carriedMeters >= policy.minimumCarriedMeters) {
                Run(legCount = legCount, carriedMeters = carriedMeters)
            } else {
                null
            }
        }

        private data class Leg(val meters: Double, val carriedMeters: Double?)

        private data class Run(val legCount: Int, val carriedMeters: Double)
    }
}
