package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Test

class RouteStepsTest {
    private val baseMillis = 1_780_000_000_000L

    private fun openLine(): List<RouteCoordinate> = List(6) { index ->
        RouteCoordinate(
            latitude = 47.71842 + index * 0.001,
            longitude = 12.83177 + if (index == 2 || index == 3) 0.002 else 0.0,
            elevationMeters = 500.0 + index * 10.0,
            timestampEpochMillis = baseMillis + index * 60_000L
        )
    }

    @Test
    fun straightenReplacesMiddleStretchWithInferredSegmentAndPreservesClock() {
        val line = openLine()
        val distances = RouteTrim.distances(line)
        val step = RouteStep.Replace(distances[1], distances[4], RoutePath.STRAIGHT)

        val applied = RouteSteps.applying(listOf(step), line)

        assertThat(applied.lastStepChangedLine).isTrue()
        assertThat(applied.route).hasSize(4)
        assertThat(applied.route[2].provenance).isEqualTo(RouteProvenance.INFERRED)
        assertThat(applied.recordedSeconds).isWithin(0.01).of(RouteTrim.recordedSeconds(line))
        assertThat(applied.movesStart).isFalse()
        assertThat(applied.movesEnd).isFalse()
    }

    @Test
    fun replaceAcrossPausePreservesPauseDurationInRecordedSeconds() {
        val line = listOf(
            RouteCoordinate(47.71842, 12.83177, timestampEpochMillis = baseMillis),
            RouteCoordinate(47.71942, 12.83177, timestampEpochMillis = baseMillis + 60_000L),
            RouteCoordinate(
                47.72042,
                12.83177,
                timestampEpochMillis = baseMillis + 360_000L,
                boundary = RouteBoundary.PAUSED
            ),
            RouteCoordinate(47.72142, 12.83177, timestampEpochMillis = baseMillis + 420_000L),
            RouteCoordinate(47.72242, 12.83177, timestampEpochMillis = baseMillis + 480_000L)
        )
        val distances = RouteTrim.distances(line)
        val beforeSeconds = RouteTrim.recordedSeconds(line)
        val step = RouteStep.Replace(distances[1] * 0.5, distances[3] + 20.0, RoutePath.STRAIGHT)

        val applied = RouteSteps.applying(listOf(step), line)

        assertThat(RouteTrim.recordedSeconds(applied.route)).isWithin(1.0).of(beforeSeconds)
        assertThat(applied.recordedSeconds).isWithin(1.0).of(beforeSeconds)
    }

    @Test
    fun pauseMiddleStretchRemovesDistanceAndMarksBoundary() {
        val line = openLine()
        val distances = RouteTrim.distances(line)

        val applied = RouteSteps.applying(listOf(RouteStep.Pause(distances[1], distances[4])), line)

        assertThat(applied.lastStepChangedLine).isTrue()
        assertThat(applied.route.any { it.isPauseBoundary }).isTrue()
        assertThat(RouteGeometry.routeLengthMeters(applied.route)).isLessThan(distances.last())
    }

    @Test
    fun reverseClearsTimestampsPreservesRecordedSecondsAndTransfersBoundary() {
        val line = listOf(
            RouteCoordinate(47.71842, 12.83177, timestampEpochMillis = baseMillis),
            RouteCoordinate(47.71942, 12.83177, timestampEpochMillis = baseMillis + 60_000L),
            RouteCoordinate(
                47.72142,
                12.83177,
                timestampEpochMillis = baseMillis + 180_000L,
                boundary = RouteBoundary.PAUSED
            ),
            RouteCoordinate(47.72242, 12.83177, timestampEpochMillis = baseMillis + 240_000L)
        )
        val originalSeconds = RouteTrim.recordedSeconds(line)

        val applied = RouteSteps.applying(listOf(RouteStep.Reverse), line)

        assertThat(applied.lastStepChangedLine).isTrue()
        assertThat(applied.route.all { it.timestampEpochMillis == null }).isTrue()
        assertThat(applied.route.first().latitude).isWithin(1e-6).of(47.72242)
        assertThat(applied.route.last().latitude).isWithin(1e-6).of(47.71842)
        assertThat(applied.route[2].isPauseBoundary).isTrue()
        assertThat(applied.recordedSeconds).isWithin(0.01).of(originalSeconds)
        assertThat(applied.clockedRoute).isEqualTo(line)
        assertThat(applied.movesStart).isTrue()
        assertThat(applied.movesEnd).isTrue()
    }

    @Test
    fun outAndBackDoublesOpenLineAndSkipsClosedLoop() {
        val open = openLine()
        val doubled = RouteSteps.applying(listOf(RouteStep.OutAndBack), open)

        assertThat(doubled.lastStepChangedLine).isTrue()
        assertThat(RouteGeometry.routeLengthMeters(doubled.route))
            .isWithin(1.0)
            .of(RouteGeometry.routeLengthMeters(open) * 2.0)
        assertThat(doubled.route.last().coordinate).isEqualTo(open.first().coordinate)
        assertThat(doubled.route.last().isInferred).isTrue()

        val doubledAgain = RouteSteps.applying(listOf(RouteStep.OutAndBack, RouteStep.OutAndBack), open)
        assertThat(doubledAgain.lastStepChangedLine).isFalse()
    }

    @Test
    fun closeLoopConnectsEndBackToStart() {
        val open = openLine()
        val via = GeoCoordinate(47.720, 12.828)
        val closed = RouteSteps.applying(
            listOf(RouteStep.CloseLoop(RoutePath.fromCoordinates(listOf(via)))),
            open
        )

        assertThat(closed.lastStepChangedLine).isTrue()
        assertThat(closed.route.last().coordinate).isEqualTo(open.first().coordinate)
        assertThat(closed.route.last().isInferred).isTrue()
        assertThat(RouteSteps.endsMeet(closed.route)).isTrue()
    }

    @Test
    fun startAtRotatesLoopWithoutLosingLength() {
        val centerLat = 47.71842
        val centerLon = 12.83177
        val radiusLat = 300.0 / 111_000.0
        val radiusLon = 300.0 / (111_000.0 * cos(centerLat * PI / 180.0))
        val loop = (0..12).map { step ->
            val angle = step * 2.0 * PI / 12.0
            RouteCoordinate(
                latitude = centerLat + sin(angle) * radiusLat,
                longitude = centerLon + cos(angle) * radiusLon,
                timestampEpochMillis = baseMillis + step * 30_000L
            )
        }
        val distances = RouteTrim.distances(loop)
        val rotateTo = distances[4]

        val applied = RouteSteps.applying(listOf(RouteStep.StartAt(rotateTo)), loop)

        assertThat(applied.lastStepChangedLine).isTrue()
        assertThat(applied.route.first().coordinate).isEqualTo(loop[4].coordinate)
        assertThat(applied.route.last().coordinate).isEqualTo(loop[4].coordinate)
        assertThat(applied.route.all { it.timestampEpochMillis == null }).isTrue()
        assertThat(RouteGeometry.routeLengthMeters(applied.route)).isWithin(1.0).of(distances.last())
    }
}
