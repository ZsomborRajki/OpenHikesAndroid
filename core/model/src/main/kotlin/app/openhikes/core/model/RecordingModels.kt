package app.openhikes.core.model

import kotlinx.serialization.Serializable

/**
 * Raw GPS/fused location sample delivered by a location feed before recording fix filtering.
 */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val timestampEpochMillis: Long,
    val horizontalAccuracyMeters: Double,
    val elevationMeters: Double? = null,
    val verticalAccuracyMeters: Double? = null,
    val courseDegrees: Double? = null,
    val courseAccuracyDegrees: Double? = null,
    val speedMps: Double? = null
) {
    val coordinate: GeoCoordinate
        get() = GeoCoordinate(latitude = latitude, longitude = longitude)
}

/**
 * Coarsened motion classification supplied alongside location updates when sensors are available.
 */
enum class RecordingMotionState {
    UNKNOWN,
    STATIONARY,
    PEDESTRIAN,
    NON_PEDESTRIAN
}

/**
 * Bitmask flags stored on each [RecordingPoint] record inside the crash-safe journal.
 */
object RecordingPointFlags {
    const val NONE = 0
    const val RESUMED = 1 shl 0
    const val WIDGET_SOURCED = 1 shl 2
    const val MOTION_STATIONARY = 1 shl 3
    const val NON_PEDESTRIAN = 1 shl 4
    const val INFERRED = 1 shl 5
    const val CARRIED = 1 shl 6

    fun hasFlag(flags: Int, flag: Int): Boolean = (flags and flag) == flag
}

/**
 * Accepted fix persisted to the recording journal and replayed for live metrics, recovery, and save.
 */
@Serializable
data class RecordingPoint(
    val latitude: Double,
    val longitude: Double,
    val timestampEpochMillis: Long,
    val horizontalAccuracyMeters: Double,
    val elevationMeters: Double? = null,
    val courseDegrees: Double? = null,
    val speedMps: Double? = null,
    val flags: Int = RecordingPointFlags.NONE
) {
    val coordinate: GeoCoordinate
        get() = GeoCoordinate(latitude = latitude, longitude = longitude)

    fun hasFlag(flag: Int): Boolean = RecordingPointFlags.hasFlag(flags, flag)

    fun toRouteCoordinate(): RouteCoordinate = RouteCoordinate(
        latitude = latitude,
        longitude = longitude,
        elevationMeters = elevationMeters?.takeIf { it.isFinite() },
        timestampEpochMillis = timestampEpochMillis,
        motion = if (hasFlag(RecordingPointFlags.NON_PEDESTRIAN)) RouteMotion.NON_PEDESTRIAN else null,
        provenance = if (hasFlag(RecordingPointFlags.INFERRED)) RouteProvenance.INFERRED else null,
        boundary = if (hasFlag(RecordingPointFlags.RESUMED)) RouteBoundary.PAUSED else null
    )
}

/**
 * High-level lifecycle state of the live recording engine.
 */
enum class RecordingPhase {
    IDLE,
    RECOVERING,
    WAITING_FOR_FIX,
    RECORDING,
    PAUSED,
    SAVING,
    FAILED;

    val isCapturingFixes: Boolean
        get() = this == WAITING_FOR_FIX || this == RECORDING

    val isStoppable: Boolean
        get() = this == WAITING_FOR_FIX || this == RECORDING || this == PAUSED
}

/**
 * Recoverable or terminal failures surfaced by the live recording engine.
 */
sealed interface RecordingFailure {
    data object LocationDenied : RecordingFailure

    data object PreciseLocationRequired : RecordingFailure

    data object TooShort : RecordingFailure

    data object StorageUnavailable : RecordingFailure

    data class Storage(val detail: String) : RecordingFailure

    data class Save(val detail: String) : RecordingFailure
}

/**
 * Closed or currently open pause interval persisted inside [TrackJournalMetadata].
 */
@Serializable
data class RecordingPauseInterval(val startedAtEpochMillis: Long, val endedAtEpochMillis: Long? = null) {
    fun contains(epochMillis: Long): Boolean =
        epochMillis > startedAtEpochMillis && (endedAtEpochMillis == null || epochMillis < endedAtEpochMillis)
}

/**
 * JSON sidecar metadata describing an active or crashed recording session.
 */
@Serializable
data class TrackJournalMetadata(
    val sessionId: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long? = null,
    val lastUpdatedAtEpochMillis: Long,
    val pausedIntervals: List<RecordingPauseInterval> = emptyList(),
    val title: String? = null
)

/**
 * Decoded journal session returned during crash recovery and final save preparation.
 */
data class TrackJournalSession(val metadata: TrackJournalMetadata, val points: List<RecordingPoint>)

/**
 * Summary of an interrupted recording surfaced when user confirmation is needed after relaunch.
 */
data class RecordingRecoverySummary(
    val startedAtEpochMillis: Long,
    val lastUpdatedAtEpochMillis: Long,
    val distanceMeters: Double,
    val pointCount: Int
)

/**
 * Outcome of automatic crash recovery at startup.
 */
sealed interface RecordingRecoveryState {
    data object Absent : RecordingRecoveryState

    data object Resumed : RecordingRecoveryState

    data class NeedsDecision(val summary: RecordingRecoverySummary) : RecordingRecoveryState
}

/**
 * Action chosen when the user taps Stop on an active recording.
 */
enum class RecordingStopStep {
    ASK_NAME,
    CONFIRM_DISCARD,
    END_EMPTY;

    companion object {
        private const val MINIMUM_LINE_POINTS = 2

        fun resolve(pointCount: Int, holdsKeepsakes: Boolean): RecordingStopStep = when {
            pointCount >= MINIMUM_LINE_POINTS -> ASK_NAME
            holdsKeepsakes -> CONFIRM_DISCARD
            else -> END_EMPTY
        }
    }
}

/**
 * Live metrics emitted on each accepted fix for leaf UI subscribers.
 */
data class RecordingStatsSnapshot(
    val distanceMeters: Double = 0.0,
    val recordedDurationSeconds: Double = 0.0,
    val movingSeconds: Double = 0.0,
    val elevationGainMeters: Double? = null,
    val elevationLossMeters: Double? = null,
    val averageSpeedMps: Double? = null,
    val recentSpeedMps: Double? = null,
    val isStationary: Boolean = false,
    val horizontalAccuracyMeters: Double? = null,
    val pointCount: Int = 0,
    val segments: List<RouteSegment> = emptyList()
)

/**
 * Live recorded track geometry pushed to the map surface independently of coarse session state.
 */
data class RecordingTraceSnapshot(
    val hikeId: HikeId? = null,
    val points: List<RouteCoordinate> = emptyList(),
    val tintHex: String = RouteTint.DEFAULT_HEX
)
