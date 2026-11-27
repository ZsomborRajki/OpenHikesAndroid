package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RouteSteepnessTest {
    @Test
    fun classifiesUpAndDownGradesAcrossAllSixThresholds() {
        assertThat(RouteSteepness.shadeForGradePercent(0.0)).isEqualTo(RouteShade.EASIEST)
        assertThat(RouteSteepness.shadeForGradePercent(9.9)).isEqualTo(RouteShade.EASIEST)
        assertThat(RouteSteepness.shadeForGradePercent(-9.9)).isEqualTo(RouteShade.EASIEST)
        assertThat(RouteSteepness.shadeForGradePercent(10.0)).isEqualTo(RouteShade.EASY)
        assertThat(RouteSteepness.shadeForGradePercent(-14.9)).isEqualTo(RouteShade.EASY)
        assertThat(RouteSteepness.shadeForGradePercent(15.0)).isEqualTo(RouteShade.MODERATE)
        assertThat(RouteSteepness.shadeForGradePercent(-20.0)).isEqualTo(RouteShade.HARD)
        assertThat(RouteSteepness.shadeForGradePercent(30.0)).isEqualTo(RouteShade.HARDER)
        assertThat(RouteSteepness.shadeForGradePercent(-40.0)).isEqualTo(RouteShade.HARDEST)
        assertThat(RouteSteepness.shadeForGradePercent(65.0)).isEqualTo(RouteShade.HARDEST)

        assertThat(RouteSteepness.lowerBoundPercent(RouteShade.EASIEST)).isEqualTo(0.0)
        assertThat(RouteSteepness.lowerBoundPercent(RouteShade.EASY)).isEqualTo(10.0)
        assertThat(RouteSteepness.lowerBoundPercent(RouteShade.MODERATE)).isEqualTo(15.0)
        assertThat(RouteSteepness.lowerBoundPercent(RouteShade.HARD)).isEqualTo(20.0)
        assertThat(RouteSteepness.lowerBoundPercent(RouteShade.HARDER)).isEqualTo(30.0)
        assertThat(RouteSteepness.lowerBoundPercent(RouteShade.HARDEST)).isEqualTo(40.0)
    }

    @Test
    fun levelRouteThenClimbProducesTwoJoinedStretchesAndDescentMatchesUphill() {
        val uphill = routeBySegments(
            listOf(
                500.0 to 0.0,
                500.0 to 0.25
            )
        )
        val uphillRuns = RouteSteepness.runs(uphill)
        assertThat(uphillRuns).hasSize(2)
        assertThat(uphillRuns[0].shade).isEqualTo(RouteShade.EASIEST)
        assertThat(uphillRuns[1].shade).isEqualTo(RouteShade.HARD)
        assertThat(uphillRuns[0].coordinates.last()).isEqualTo(uphillRuns[1].coordinates.first())

        val downhill = routeBySegments(
            listOf(
                500.0 to 0.0,
                500.0 to -0.25
            )
        )
        val downhillRuns = RouteSteepness.runs(downhill)
        assertThat(downhillRuns.map { it.shade }).isEqualTo(uphillRuns.map { it.shade })
    }

    @Test
    fun missingElevationAndPausesNeverBridgeAndNeverSmearHeights() {
        val noElevations = routeWithElevations(List(15) { null })
        assertThat(RouteSteepness.runs(noElevations)).isEmpty()

        // Missing height gap splits into two disjoint runs.
        val withGap = buildList {
            addAll(linearRoute(startMeters = 0.0, count = 16, stepMeters = 25.0, startElevation = 500.0, grade = 0.0))
            add(RouteCoordinate(latForMeters(425.0), 12.83, elevationMeters = null))
            addAll(
                linearRoute(startMeters = 450.0, count = 16, stepMeters = 25.0, startElevation = 500.0, grade = 0.25)
            )
        }
        val gapRuns = RouteSteepness.runs(withGap)
        assertThat(gapRuns).hasSize(2)
        assertThat(gapRuns[0].shade).isEqualTo(RouteShade.EASIEST)
        assertThat(gapRuns[1].shade).isEqualTo(RouteShade.HARD)
        assertThat(gapRuns[0].coordinates.last()).isNotEqualTo(gapRuns[1].coordinates.first())

        // Pause boundary between level 500m and level 1500m stays level on both sides (never averages 1000m jump).
        val firstLeg =
            linearRoute(startMeters = 0.0, count = 17, stepMeters = 25.0, startElevation = 500.0, grade = 0.0)
        val secondLeg = linearRoute(
            startMeters = 500.0,
            count = 17,
            stepMeters = 25.0,
            startElevation = 1_500.0,
            grade = 0.0,
            firstBoundary = RouteBoundary.PAUSED
        )
        val pausedRuns = RouteSteepness.runs(firstLeg + secondLeg)
        assertThat(pausedRuns).hasSize(2)
        assertThat(pausedRuns.map { it.shade }).containsExactly(RouteShade.EASIEST, RouteShade.EASIEST)
        assertThat(pausedRuns[0].coordinates.last()).isNotEqualTo(pausedRuns[1].coordinates.first())
    }

    @Test
    fun foldsShortTailAbsorbsShortStretchSmoothsSingleSpikeAndHandlesBunchedPoints() {
        // Short 60m tail folds into the previous 400m level stretch rather than creating a tiny steep blip.
        val tailed = buildList {
            addAll(linearRoute(startMeters = 0.0, count = 17, stepMeters = 25.0, startElevation = 500.0, grade = 0.0))
            add(RouteCoordinate(latForMeters(460.0), 12.83, elevationMeters = 530.0))
        }
        val tailedRuns = RouteSteepness.runs(tailed)
        assertThat(tailedRuns).hasSize(1)
        assertThat(tailedRuns.single().shade).isEqualTo(RouteShade.EASIEST)

        // Standalone 150m route shorter than one 200m window still gets measured on its own.
        val standaloneShort = linearRoute(
            startMeters = 0.0,
            count = 7,
            stepMeters = 25.0,
            startElevation = 500.0,
            grade = 0.25
        )
        val standaloneRuns = RouteSteepness.runs(standaloneShort)
        assertThat(standaloneRuns).hasSize(1)
        assertThat(standaloneRuns.single().shade).isEqualTo(RouteShade.HARD)

        // Single 25m GPS elevation spike on a flat 600m route averages away cleanly.
        val flatWithSpike = linearRoute(
            startMeters = 0.0,
            count = 25,
            stepMeters = 25.0,
            startElevation = 500.0,
            grade = 0.0
        ).mapIndexed { index, pt ->
            if (index == 12) pt.copy(elevationMeters = 525.0) else pt
        }
        val spikeRuns = RouteSteepness.runs(flatWithSpike)
        assertThat(spikeRuns).hasSize(1)
        assertThat(spikeRuns.single().shade).isEqualTo(RouteShade.EASIEST)

        // Short 200m steep bump sandwiched between two 400m level stretches absorbs into level.
        val sandwiched = routeBySegments(
            listOf(
                400.0 to 0.0,
                200.0 to 0.25,
                400.0 to 0.0
            )
        )
        val sandwichedRuns = RouteSteepness.runs(sandwiched)
        assertThat(sandwichedRuns).hasSize(1)
        assertThat(sandwichedRuns.single().shade).isEqualTo(RouteShade.EASIEST)

        // Bunched GPS points at one spot don't bias distance-weighted smoothing.
        val bunched = buildList {
            addAll(linearRoute(startMeters = 0.0, count = 11, stepMeters = 10.0, startElevation = 500.0, grade = 0.0))
            // 20 bunched points within 1m at elevated noise
            repeat(20) { i ->
                add(RouteCoordinate(latForMeters(100.0 + i * 0.05), 12.83, elevationMeters = 520.0))
            }
            addAll(linearRoute(startMeters = 110.0, count = 11, stepMeters = 10.0, startElevation = 500.0, grade = 0.0))
        }
        val smoothed = RouteSteepness.smoothedHeights(bunched)
        val midSmoothed = smoothed[15]!!
        // Because the 20 bunched points span only ~1m inside a 100m window, smoothed height stays near 500m, not 520m.
        assertThat(midSmoothed).isLessThan(505.0)
    }

    private fun routeBySegments(
        segments: List<Pair<Double, Double>>,
        stepMeters: Double = 25.0
    ): List<RouteCoordinate> {
        val points = ArrayList<RouteCoordinate>()
        var dist = 0.0
        var ele = 500.0
        points.add(RouteCoordinate(latForMeters(0.0), 12.83, elevationMeters = ele))
        for ((length, grade) in segments) {
            val steps = (length / stepMeters).toInt().coerceAtLeast(1)
            val actualStep = length / steps
            repeat(steps) {
                dist += actualStep
                ele += actualStep * grade
                points.add(RouteCoordinate(latForMeters(dist), 12.83, elevationMeters = ele))
            }
        }
        return points
    }

    private fun linearRoute(
        startMeters: Double,
        count: Int,
        stepMeters: Double,
        startElevation: Double,
        grade: Double,
        firstBoundary: RouteBoundary? = null
    ): List<RouteCoordinate> = List(count) { i ->
        val offset = i * stepMeters
        RouteCoordinate(
            latitude = latForMeters(startMeters + offset),
            longitude = 12.83,
            elevationMeters = startElevation + offset * grade,
            boundary = if (i == 0) firstBoundary else null
        )
    }

    private fun routeWithElevations(elevations: List<Double?>): List<RouteCoordinate> = elevations.mapIndexed {
            i,
            ele
        ->
        RouteCoordinate(latForMeters(i * 25.0), 12.83, elevationMeters = ele)
    }

    private fun latForMeters(meters: Double): Double = 47.70 + Math.toDegrees(meters / 6_371_000.0)
}
