package app.openhikes.core.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

internal object RouteStepTransforms {
    private const val MILLIS_PER_SECOND = 1_000.0

    fun replaced(
        line: List<RouteCoordinate>,
        startMeters: Double,
        endMeters: Double,
        path: RoutePath
    ): List<RouteCoordinate> {
        val sides = RouteStepSides.split(line, startMeters, endMeters) ?: return line
        sides.tail[0] = sides.tail[0].copy(provenance = RouteProvenance.INFERRED, boundary = null)
        val way = between(path.points, after = sides.head.lastOrNull(), before = sides.tail.firstOrNull())
        val drawn = ArrayList<RouteCoordinate>(sides.head.size + way.size + sides.tail.size)
        drawn.addAll(sides.head)
        drawn.addAll(way)
        drawn.addAll(sides.tail)
        val pausedSeconds = RouteTrim.recordedSeconds(drawn) - RouteTrim.recordedSeconds(line)
        val stop = if (pausedSeconds > 0.0) pauseAfter(sides.head, pausedSeconds) else null
        return if (stop == null) {
            drawn
        } else {
            ArrayList<RouteCoordinate>(drawn.size + 1).apply {
                addAll(sides.head)
                add(stop)
                addAll(way)
                addAll(sides.tail)
            }
        }
    }

    fun paused(line: List<RouteCoordinate>, startMeters: Double, endMeters: Double): List<RouteCoordinate> {
        val sides = RouteStepSides.split(line, startMeters, endMeters) ?: return line
        return when {
            sides.reachesStart -> {
                sides.tail[0] = sides.tail[0].copy(boundary = null, provenance = null)
                sides.tail
            }
            sides.reachesEnd -> sides.head
            else -> {
                sides.tail[0] = sides.tail[0].copy(boundary = RouteBoundary.PAUSED, provenance = null)
                sides.head + sides.tail
            }
        }
    }

    fun turned(line: List<RouteCoordinate>): List<RouteCoordinate> {
        val last = line.lastIndex
        return List(line.size) { index ->
            val point = line[last - index]
            val incoming = if (index == 0) null else line[last - index + 1]
            point.copy(boundary = incoming?.boundary, provenance = incoming?.provenance)
        }
    }

    fun withoutTimes(line: List<RouteCoordinate>): List<RouteCoordinate> =
        line.map { it.copy(timestampEpochMillis = null) }

    fun returning(line: List<RouteCoordinate>): List<RouteCoordinate> =
        withoutTimes(turned(line)).mapIndexed { index, point ->
            if (index > 0 && !point.isPauseBoundary) point.copy(provenance = RouteProvenance.INFERRED) else point
        }

    fun closed(line: List<RouteCoordinate>, path: RoutePath): List<RouteCoordinate> {
        val first = line.firstOrNull()
        val last = line.lastOrNull()
        if (first == null || last == null || isSameSpot(first, last)) return line
        val home = RouteCoordinate(
            latitude = first.latitude,
            longitude = first.longitude,
            elevationMeters = first.elevationMeters,
            provenance = RouteProvenance.INFERRED
        )
        return line + between(path.points, after = last, before = home) + home
    }

    fun rotated(line: List<RouteCoordinate>, meters: Double): List<RouteCoordinate> {
        val distances = RouteTrim.distances(line)
        val total = distances.lastOrNull()
        if (total == null || meters <= 0.0 || meters >= total) return line
        val onward = RouteTrim.trimmed(line, meters, total, distances)
        val round = RouteTrim.trimmed(line, 0.0, meters, distances).toMutableList()
        val seam = onward.lastOrNull()
        val begun = round.firstOrNull()
        return if (seam == null || begun == null) {
            line
        } else {
            if (isSameSpot(seam, begun)) {
                round.removeAt(0)
            } else {
                round[0] = round[0].copy(provenance = RouteProvenance.INFERRED, boundary = null)
            }
            withoutTimes(onward + round)
        }
    }

    fun isSameFix(one: RouteCoordinate?, other: RouteCoordinate?): Boolean = one != null &&
        other != null &&
        one.latitude == other.latitude &&
        one.longitude == other.longitude &&
        one.timestampEpochMillis == other.timestampEpochMillis

    private fun pauseAfter(head: List<RouteCoordinate>, seconds: Double): RouteCoordinate? {
        val spot = head.lastOrNull()
        val began = head.lastOrNull { it.timestampEpochMillis != null }?.timestampEpochMillis
        return if (spot != null && began != null) {
            RouteCoordinate(
                latitude = spot.latitude,
                longitude = spot.longitude,
                elevationMeters = spot.elevationMeters,
                timestampEpochMillis = began + (seconds * MILLIS_PER_SECOND).roundToLong(),
                boundary = RouteBoundary.PAUSED
            )
        } else {
            null
        }
    }

    private fun between(
        points: List<RouteCoordinate>,
        after: RouteCoordinate?,
        before: RouteCoordinate?
    ): List<RouteCoordinate> {
        var start = 0
        var end = points.size
        if (start < end && after != null && isSameSpot(after, points[start])) start += 1
        if (start < end && before != null && isSameSpot(before, points[end - 1])) end -= 1
        return if (start < end) points.subList(start, end) else emptyList()
    }

    private fun isSameSpot(one: RouteCoordinate, other: RouteCoordinate): Boolean =
        RouteGeometry.distanceMeters(one.coordinate, other.coordinate) <= RouteSteps.JOIN_METERS
}

private object RouteStepSides {
    data class Sides(
        val head: MutableList<RouteCoordinate>,
        val tail: MutableList<RouteCoordinate>,
        val reachesStart: Boolean,
        val reachesEnd: Boolean
    )

    fun split(line: List<RouteCoordinate>, startMeters: Double, endMeters: Double): Sides? {
        val distances = RouteTrim.distances(line)
        val total = distances.lastOrNull()
        val lower = max(startMeters, 0.0)
        val upper = if (total != null) min(endMeters, total) else 0.0
        if (total == null || line.isEmpty() || upper <= lower) return null

        val head = sliceHead(line, distances, lower)
        val tail = sliceTail(line, distances, upper, total)
        val headEnd = head.lastOrNull()?.let { absorbHeadPauses(head, it, lower, line, distances) }
        val tailBegin = tail.firstOrNull()?.let { absorbTailPauses(tail, it, upper, line, distances) }
        val indicesDisjoint = headEnd == null || tailBegin == null || headEnd < tailBegin
        val valid = head.isNotEmpty() && tail.isNotEmpty() && indicesDisjoint
        return if (valid) {
            Sides(head = head, tail = tail, reachesStart = lower <= 0.0, reachesEnd = upper >= total)
        } else {
            null
        }
    }

    private fun sliceHead(
        line: List<RouteCoordinate>,
        distances: List<Double>,
        lower: Double
    ): MutableList<RouteCoordinate> = if (lower > 0.0) {
        RouteTrim.trimmed(line, 0.0, lower, distances).toMutableList()
    } else {
        mutableListOf(line.first())
    }

    private fun sliceTail(
        line: List<RouteCoordinate>,
        distances: List<Double>,
        upper: Double,
        total: Double
    ): MutableList<RouteCoordinate> = if (upper < total) {
        RouteTrim.trimmed(line, upper, total, distances).toMutableList()
    } else {
        mutableListOf(line.last())
    }

    private fun absorbHeadPauses(
        head: MutableList<RouteCoordinate>,
        ends: RouteCoordinate,
        lower: Double,
        line: List<RouteCoordinate>,
        distances: List<Double>
    ): Int? {
        val headEnd = findIndex(ends, nearMeters = lower, line = line, distances = distances) ?: return null
        var next = headEnd + 1
        while (next < line.size && line[next].isPauseBoundary) {
            head.add(line[next])
            next += 1
        }
        return next - 1
    }

    private fun absorbTailPauses(
        tail: MutableList<RouteCoordinate>,
        begins: RouteCoordinate,
        upper: Double,
        line: List<RouteCoordinate>,
        distances: List<Double>
    ): Int? {
        val tailBegin = findIndex(begins, nearMeters = upper, line = line, distances = distances) ?: return null
        var stopped = tailBegin
        if (line[tailBegin].isPauseBoundary) {
            stopped = tailBegin - 1
            while (stopped > 0 && line[stopped].isPauseBoundary) {
                stopped -= 1
            }
            tail.addAll(0, line.subList(stopped, tailBegin))
        }
        return stopped
    }

    private fun findIndex(
        point: RouteCoordinate,
        nearMeters: Double,
        line: List<RouteCoordinate>,
        distances: List<Double>
    ): Int? {
        var index = RouteTrim.lowerBound(distances, nearMeters - RouteTrim.SNAP_METERS)
        while (index < line.size && distances[index] <= nearMeters + RouteTrim.SNAP_METERS) {
            if (RouteStepTransforms.isSameFix(line[index], point)) return index
            index += 1
        }
        return null
    }
}
