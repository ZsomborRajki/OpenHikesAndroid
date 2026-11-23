package app.openhikes.core.model

import kotlin.math.abs
import kotlin.math.max

/**
 * Filters GPS altitude fixes based on reported vertical uncertainty so barometer/GPS jitter
 * does not poison running climb totals.
 */
object AltitudePolicy {
    const val MAXIMUM_VERTICAL_ACCURACY_METERS = 15.0

    fun trustedAltitude(elevationMeters: Double?, verticalAccuracyMeters: Double?): Double? {
        val validInputs = elevationMeters != null &&
            verticalAccuracyMeters != null &&
            elevationMeters.isFinite() &&
            verticalAccuracyMeters.isFinite()
        val withinAccuracy = validInputs &&
            verticalAccuracyMeters >= 0.0 &&
            verticalAccuracyMeters <= MAXIMUM_VERTICAL_ACCURACY_METERS
        return if (withinAccuracy) PlausibleElevation.validated(elevationMeters) else null
    }
}

/**
 * Baseline validity, freshness, and bearing-trust rules shared across foreground/background feeds.
 */
object LocationFixPolicy {
    const val FOREGROUND_MAXIMUM_AGE_SECONDS = 30.0
    const val BACKGROUND_MAXIMUM_AGE_SECONDS = 300.0
    const val FUTURE_TIMESTAMP_TOLERANCE_SECONDS = 5.0
    const val MINIMUM_COURSE_SPEED_MPS = 0.5
    const val MAXIMUM_COURSE_ACCURACY_DEGREES = 45.0

    private const val MIN_LATITUDE = -90.0
    private const val MAX_LATITUDE = 90.0
    private const val MIN_LONGITUDE = -180.0
    private const val MAX_LONGITUDE = 180.0
    private const val MILLIS_PER_SECOND = 1000.0

    fun course(fix: LocationFix): Double? {
        val speed = fix.speedMps
        val bearing = fix.courseDegrees
        val speedValid = speed != null && speed.isFinite() && speed >= MINIMUM_COURSE_SPEED_MPS
        val bearingValid = bearing != null && bearing.isFinite() && bearing >= 0.0
        val accuracyAcceptable = isCourseAccuracyAcceptable(fix.courseAccuracyDegrees)
        return if (speedValid && bearingValid && accuracyAcceptable) bearing else null
    }

    private fun isCourseAccuracyAcceptable(accuracy: Double?): Boolean =
        accuracy == null || !accuracy.isFinite() || accuracy < 0.0 || accuracy <= MAXIMUM_COURSE_ACCURACY_DEGREES

    fun accepts(
        fix: LocationFix,
        maximumAgeSeconds: Double,
        maximumHorizontalAccuracyMeters: Double? = null,
        nowEpochMillis: Long
    ): Boolean {
        val ageSeconds = (nowEpochMillis - fix.timestampEpochMillis) / MILLIS_PER_SECOND
        val withinAge = ageSeconds >= -FUTURE_TIMESTAMP_TOLERANCE_SECONDS && ageSeconds <= maximumAgeSeconds
        val accuracyOk = maximumHorizontalAccuracyMeters == null ||
            fix.horizontalAccuracyMeters <= maximumHorizontalAccuracyMeters
        return areCoordinatesValid(fix) && withinAge && accuracyOk
    }

    private fun areCoordinatesValid(fix: LocationFix): Boolean = fix.latitude.isFinite() &&
        fix.longitude.isFinite() &&
        fix.latitude in MIN_LATITUDE..MAX_LATITUDE &&
        fix.longitude in MIN_LONGITUDE..MAX_LONGITUDE &&
        fix.horizontalAccuracyMeters.isFinite() &&
        fix.horizontalAccuracyMeters >= 0.0
}

/**
 * Earliest timestamp a live fix may carry and still belong to the active recording leg.
 * Blocks cached pre-start or paused fixes from anchoring a new segment.
 */
class RecordingFixWindow(opensAtEpochMillis: Long) {
    var opensAtEpochMillis: Long = opensAtEpochMillis
        private set

    fun reopen(epochMillis: Long) {
        opensAtEpochMillis = maxOf(opensAtEpochMillis, epochMillis)
    }

    fun admits(timestampEpochMillis: Long): Boolean = timestampEpochMillis >= opensAtEpochMillis
}

/**
 * Acceptance gate deciding which live location fixes join the recorded track.
 */
object RecordingFixPolicy {
    const val MAXIMUM_HORIZONTAL_ACCURACY_METERS = 50.0
    const val MAXIMUM_SPEED_MPS = MovementThresholds.MAXIMUM_ON_FOOT_SPEED_MPS
    const val MINIMUM_DISPLACEMENT_METERS = 5.0
    const val MAXIMUM_INTERVAL_SECONDS = 10.0
    const val BEARING_CHANGE_DEGREES = 15.0

    private const val DISPLACEMENT_GATE_FRACTION = 0.5
    private const val SPEED_TOLERANCE_BASE_MPS = 2.0
    private const val SPEED_TOLERANCE_FRACTION = 0.35
    private const val MILLIS_PER_SECOND = 1000.0

    fun accepts(
        fix: LocationFix,
        afterPrevious: RecordingPoint?,
        motionState: RecordingMotionState = RecordingMotionState.UNKNOWN,
        nowEpochMillis: Long
    ): Boolean {
        val baseAccepted = LocationFixPolicy.accepts(
            fix = fix,
            maximumAgeSeconds = LocationFixPolicy.FOREGROUND_MAXIMUM_AGE_SECONDS,
            maximumHorizontalAccuracyMeters = MAXIMUM_HORIZONTAL_ACCURACY_METERS,
            nowEpochMillis = nowEpochMillis
        ) &&
            Mercator.isRepresentable(fix.latitude, fix.longitude)
        return when {
            !baseAccepted -> false
            afterPrevious == null -> true
            else -> acceptsAfterPrevious(fix, afterPrevious, motionState)
        }
    }

    private fun acceptsAfterPrevious(
        fix: LocationFix,
        afterPrevious: RecordingPoint,
        motionState: RecordingMotionState
    ): Boolean {
        val intervalSeconds = (fix.timestampEpochMillis - afterPrevious.timestampEpochMillis) / MILLIS_PER_SECOND
        if (intervalSeconds <= 0.0) return false
        val displacement = RouteGeometry.distanceMeters(afterPrevious.coordinate, fix.coordinate)
        val impliedSpeed = displacement / intervalSeconds
        val speedRejected = impliedSpeed > MAXIMUM_SPEED_MPS &&
            motionState != RecordingMotionState.NON_PEDESTRIAN &&
            !reportedSpeedCorroborates(fix.speedMps, impliedSpeed) &&
            intervalSeconds < MAXIMUM_INTERVAL_SECONDS
        val displacementGate = max(
            MINIMUM_DISPLACEMENT_METERS,
            fix.horizontalAccuracyMeters * DISPLACEMENT_GATE_FRACTION
        )
        return !speedRejected &&
            (
                displacement >= displacementGate ||
                    intervalSeconds >= MAXIMUM_INTERVAL_SECONDS ||
                    hasSignificantBearingChange(afterPrevious, fix)
                )
    }

    private fun hasSignificantBearingChange(previous: RecordingPoint, next: LocationFix): Boolean {
        val previousCourse = previous.courseDegrees
        val nextCourse = LocationFixPolicy.course(next)
        return previousCourse != null &&
            nextCourse != null &&
            RouteGeometry.bearingDifference(previousCourse, nextCourse) > BEARING_CHANGE_DEGREES
    }

    fun reportedSpeedCorroborates(reportedMps: Double?, impliedMps: Double): Boolean {
        val inputsValid = reportedMps != null &&
            reportedMps.isFinite() &&
            reportedMps >= 0.0 &&
            impliedMps.isFinite()
        val tolerance = max(SPEED_TOLERANCE_BASE_MPS, impliedMps * SPEED_TOLERANCE_FRACTION)
        return inputsValid && abs(reportedMps - impliedMps) <= tolerance
    }
}
