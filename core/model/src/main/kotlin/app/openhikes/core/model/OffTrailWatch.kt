package app.openhikes.core.model

import kotlin.math.max

/**
 * Thresholds for off-trail departure warnings and paused-walk movement reminders.
 */
object OffTrailPolicy {
    const val OFF_TRAIL_METERS = 150.0
    const val OFF_TRAIL_DWELL_MILLIS = 120_000L
    const val ON_TRAIL_AGAIN_METERS = RouteProfile.FOLLOW_MATCH_THRESHOLD_METERS
    const val PARK_JUNCTION_FEED_METERS = 1_000.0

    const val PAUSE_AWAY_METERS = 500.0
    const val PAUSE_PACE_METERS = 250.0
    const val PAUSE_PACE_WINDOW_MILLIS = 120_000L
    const val PAUSE_QUIET_FOR_MILLIS = 15L * 60L * 1000L
    const val PAUSE_MAXIMUM_REMINDERS = 3
}

/**
 * Stateful departure detector for an active followed walk:
 * reports once per committed departure (>= 150 m off route for >= 2 minutes), and re-arms only
 * after returning within the follow threshold (<= 75 m). Unmatched (`null`) observations are ignored.
 */
data class OffTrailWatch(private val departedAtEpochMillis: Long? = null, private val hasReported: Boolean = false) {
    val isOffTrail: Boolean
        get() = departedAtEpochMillis != null

    fun observe(offRouteMeters: Double?, atEpochMillis: Long): Pair<OffTrailWatch, Boolean> = when {
        offRouteMeters == null || !offRouteMeters.isFinite() -> this to false
        offRouteMeters <= OffTrailPolicy.ON_TRAIL_AGAIN_METERS -> OffTrailWatch() to false
        offRouteMeters <= OffTrailPolicy.OFF_TRAIL_METERS -> this to false
        departedAtEpochMillis == null -> copy(departedAtEpochMillis = atEpochMillis) to false
        hasReported -> this to false
        (atEpochMillis - departedAtEpochMillis) < OffTrailPolicy.OFF_TRAIL_DWELL_MILLIS -> this to false
        else -> copy(hasReported = true) to true
    }

    fun reset(): OffTrailWatch = OffTrailWatch()
}

/**
 * Detects sustained displacement from a pause anchor along a followed route (500 m net or 250 m
 * inside 2 minutes), capped at 3 reminders with a 15-minute quiet floor between prompts.
 */
data class PausedMovementWatch(
    private val baselineMeters: Double = 0.0,
    private val samples: List<Sample> = emptyList(),
    val lastObservedAtEpochMillis: Long? = null,
    val remindersSent: Int = 0,
    private val quietSinceEpochMillis: Long? = null,
    val owedMeters: Double? = null
) {
    data class Sample(val awayMeters: Double, val atEpochMillis: Long)

    val isSpent: Boolean
        get() = remindersSent >= OffTrailPolicy.PAUSE_MAXIMUM_REMINDERS

    fun observe(awayMeters: Double, atEpochMillis: Long): Pair<PausedMovementWatch, Boolean> {
        if (lastObservedAtEpochMillis != null && atEpochMillis <= lastObservedAtEpochMillis) {
            return this to false
        }
        val activeSamples = samples.filter {
            (atEpochMillis - it.atEpochMillis) <= OffTrailPolicy.PAUSE_PACE_WINDOW_MILLIS
        }
        val oldest = activeSamples.firstOrNull()
        val nextSamples = activeSamples + Sample(awayMeters = awayMeters, atEpochMillis = atEpochMillis)
        val traveled = awayMeters - baselineMeters
        val burst = oldest?.let { awayMeters - it.awayMeters } ?: 0.0
        val qualifies = !isSpent &&
            (traveled >= OffTrailPolicy.PAUSE_AWAY_METERS || burst >= OffTrailPolicy.PAUSE_PACE_METERS)
        val inQuietPeriod = quietSinceEpochMillis != null &&
            (atEpochMillis - quietSinceEpochMillis) < OffTrailPolicy.PAUSE_QUIET_FOR_MILLIS

        return when {
            !qualifies -> copy(
                samples = nextSamples,
                lastObservedAtEpochMillis = atEpochMillis,
                owedMeters = null
            ) to false
            inQuietPeriod -> copy(
                samples = nextSamples,
                lastObservedAtEpochMillis = atEpochMillis,
                owedMeters = if (owedMeters != null) awayMeters else null
            ) to false
            else -> copy(
                samples = nextSamples,
                lastObservedAtEpochMillis = atEpochMillis,
                quietSinceEpochMillis = atEpochMillis,
                owedMeters = awayMeters
            ) to true
        }
    }

    fun spendReminder(atEpochMillis: Long): Pair<PausedMovementWatch, Double?> {
        val owed = owedMeters ?: return this to null
        val nextQuiet = max(quietSinceEpochMillis ?: atEpochMillis, atEpochMillis)
        return copy(
            baselineMeters = owed,
            samples = emptyList(),
            remindersSent = remindersSent + 1,
            quietSinceEpochMillis = nextQuiet,
            owedMeters = null
        ) to owed
    }
}
