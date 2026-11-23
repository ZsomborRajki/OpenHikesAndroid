package app.openhikes.core.model

import kotlin.math.abs

/**
 * Finalized geometry and statistics prepared off the main thread before persisting a stopped recording.
 */
data class PreparedRecording(
    val route: List<RouteCoordinate>,
    val distanceMeters: Double,
    val routeLengthMeters: Double,
    val recordedSeconds: Double,
    val elevationGainMeters: Double?,
    val elevationLossMeters: Double?,
    val startedAtEpochMillis: Long
)

/**
 * Result of normalizing and preparing journalled points for persistence.
 */
sealed interface RecordingPreparationResult {
    data class Success(val prepared: PreparedRecording) : RecordingPreparationResult

    data object TooShort : RecordingPreparationResult
}

/**
 * Normalizes recorded fixes (sorting, deduplicating widget fixes) and replays them through
 * [RecordingDistanceAccumulator] so live figures match saved hikes identically.
 */
object RecordingPreparation {
    const val MINIMUM_POINT_COUNT = 2
    private const val WIDGET_DUPLICATE_TOLERANCE_MILLIS = 5_000L

    fun prepare(points: List<RecordingPoint>, startedAtEpochMillis: Long): RecordingPreparationResult {
        val normalized = normalizedPoints(points)
        if (normalized.size < MINIMUM_POINT_COUNT) return RecordingPreparationResult.TooShort

        val accumulator = RecordingDistanceAccumulator()
        var routeLengthMeters = 0.0
        var previousCoordinate: GeoCoordinate? = null
        for (point in normalized) {
            accumulator.append(point)
            val prev = previousCoordinate
            if (prev != null && !point.hasFlag(RecordingPointFlags.RESUMED)) {
                routeLengthMeters += RouteGeometry.distanceMeters(prev, point.coordinate)
            }
            previousCoordinate = point.coordinate
        }

        val route = normalized.map { it.toRouteCoordinate() }
        return RecordingPreparationResult.Success(
            PreparedRecording(
                route = route,
                distanceMeters = accumulator.distanceMeters,
                routeLengthMeters = routeLengthMeters,
                recordedSeconds = accumulator.recordedDurationSeconds,
                elevationGainMeters = accumulator.elevationGainMeters,
                elevationLossMeters = accumulator.elevationLossMeters,
                startedAtEpochMillis = startedAtEpochMillis
            )
        )
    }

    fun normalizedPoints(points: List<RecordingPoint>): List<RecordingPoint> {
        if (points.isEmpty()) return emptyList()
        val ordered = points.sortedWith(
            compareBy<RecordingPoint> { it.timestampEpochMillis }
                .thenBy { it.hasFlag(RecordingPointFlags.WIDGET_SOURCED) }
                .thenBy { it.horizontalAccuracyMeters }
        )
        val foregroundTimes = ordered
            .filterNot { it.hasFlag(RecordingPointFlags.WIDGET_SOURCED) }
            .map { it.timestampEpochMillis }

        val deduplicated = ArrayList<RecordingPoint>(ordered.size)
        for (point in ordered) {
            val duplicateWidget = point.hasFlag(RecordingPointFlags.WIDGET_SOURCED) &&
                hasNearbyTimestamp(foregroundTimes, point.timestampEpochMillis)
            val duplicateTimestamp = point.timestampEpochMillis == deduplicated.lastOrNull()?.timestampEpochMillis
            if (!duplicateWidget && !duplicateTimestamp) {
                deduplicated.add(point)
            }
        }
        return deduplicated
    }

    private fun hasNearbyTimestamp(sortedTimestamps: List<Long>, targetMillis: Long): Boolean {
        if (sortedTimestamps.isEmpty()) return false
        var low = 0
        var high = sortedTimestamps.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sortedTimestamps[mid] < targetMillis) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        val atOrAfter = sortedTimestamps.getOrNull(low)
        val before = sortedTimestamps.getOrNull(low - 1)
        val closeAfter = atOrAfter != null && abs(atOrAfter - targetMillis) <= WIDGET_DUPLICATE_TOLERANCE_MILLIS
        val closeBefore = before != null && abs(before - targetMillis) <= WIDGET_DUPLICATE_TOLERANCE_MILLIS
        return closeAfter || closeBefore
    }
}
