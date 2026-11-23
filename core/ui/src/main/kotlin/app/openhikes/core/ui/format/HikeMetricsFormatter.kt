package app.openhikes.core.ui.format

import app.openhikes.core.model.DistanceUnit
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Locale-aware formatter for hiking distances, elevations, speeds, durations, and dates.
 */
@Suppress("TooManyFunctions")
object HikeMetricsFormatter {
    private const val METERS_PER_KILOMETER = 1000.0
    private const val METERS_PER_MILE = 1609.344
    private const val FEET_PER_METER = 3.280839895
    private const val SHORT_DISTANCE_FEET_THRESHOLD = 528.0
    private const val METRIC_KM_THRESHOLD_METERS = 1000.0
    private const val MPS_TO_KMH = 3.6
    private const val MPS_TO_MPH = 2.23693629
    private const val SECONDS_PER_MINUTE = 60
    private const val MINUTES_PER_HOUR = 60
    private const val HIGH_DISTANCE_DECIMALS_THRESHOLD = 100.0
    private const val PERCENT_SCALE = 100.0
    private const val MAX_PERCENT = 100

    fun formatDistance(meters: Double, unit: DistanceUnit, locale: Locale = Locale.getDefault()): String {
        val safeMeters = meters.coerceAtLeast(0.0)
        return when (unit) {
            DistanceUnit.METRIC -> if (safeMeters >= METRIC_KM_THRESHOLD_METERS) {
                val km = safeMeters / METERS_PER_KILOMETER
                val decimals = if (km >= HIGH_DISTANCE_DECIMALS_THRESHOLD) 0 else 1
                "${formatNumber(km, decimals, locale)} km"
            } else {
                "${formatNumber(safeMeters, 0, locale)} m"
            }

            DistanceUnit.IMPERIAL -> {
                val feet = safeMeters * FEET_PER_METER
                if (feet < SHORT_DISTANCE_FEET_THRESHOLD) {
                    "${formatNumber(feet, 0, locale)} ft"
                } else {
                    val miles = safeMeters / METERS_PER_MILE
                    val decimals = if (miles >= HIGH_DISTANCE_DECIMALS_THRESHOLD) 0 else 1
                    "${formatNumber(miles, decimals, locale)} mi"
                }
            }
        }
    }

    fun formatSpokenDistance(meters: Double, unit: DistanceUnit, locale: Locale = Locale.getDefault()): String {
        val safeMeters = meters.coerceAtLeast(0.0)
        return when (unit) {
            DistanceUnit.METRIC -> if (safeMeters >= METRIC_KM_THRESHOLD_METERS) {
                val km = safeMeters / METERS_PER_KILOMETER
                "${formatNumber(km, 1, locale)} kilometers"
            } else {
                "${formatNumber(safeMeters, 0, locale)} meters"
            }

            DistanceUnit.IMPERIAL -> {
                val feet = safeMeters * FEET_PER_METER
                if (feet < SHORT_DISTANCE_FEET_THRESHOLD) {
                    "${formatNumber(feet, 0, locale)} feet"
                } else {
                    val miles = safeMeters / METERS_PER_MILE
                    "${formatNumber(miles, 1, locale)} miles"
                }
            }
        }
    }

    fun formatElevation(meters: Double, unit: DistanceUnit, locale: Locale = Locale.getDefault()): String =
        when (unit) {
            DistanceUnit.METRIC -> "${formatNumber(meters, 0, locale)} m"
            DistanceUnit.IMPERIAL -> "${formatNumber(meters * FEET_PER_METER, 0, locale)} ft"
        }

    fun formatSpokenElevation(meters: Double, unit: DistanceUnit, locale: Locale = Locale.getDefault()): String =
        when (unit) {
            DistanceUnit.METRIC -> "${formatNumber(meters, 0, locale)} meters"
            DistanceUnit.IMPERIAL -> "${formatNumber(meters * FEET_PER_METER, 0, locale)} feet"
        }

    fun formatSpeed(metersPerSecond: Double, unit: DistanceUnit, locale: Locale = Locale.getDefault()): String =
        when (unit) {
            DistanceUnit.METRIC -> "${formatNumber(metersPerSecond * MPS_TO_KMH, 1, locale)} km/h"
            DistanceUnit.IMPERIAL -> "${formatNumber(metersPerSecond * MPS_TO_MPH, 1, locale)} mph"
        }

    fun formatSpokenSpeed(metersPerSecond: Double, unit: DistanceUnit, locale: Locale = Locale.getDefault()): String =
        when (unit) {
            DistanceUnit.METRIC -> "${formatNumber(metersPerSecond * MPS_TO_KMH, 1, locale)} kilometers per hour"
            DistanceUnit.IMPERIAL -> "${formatNumber(metersPerSecond * MPS_TO_MPH, 1, locale)} miles per hour"
        }

    fun formatAccuracy(
        horizontalAccuracyMeters: Double,
        unit: DistanceUnit,
        locale: Locale = Locale.getDefault()
    ): String = "±${formatDistance(horizontalAccuracyMeters, unit, locale)}"

    fun formatCount(count: Int, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getIntegerInstance(locale).format(count)

    fun formatDuration(seconds: Double): String {
        val totalSeconds = seconds.roundToInt().coerceAtLeast(0)
        val totalMinutes = totalSeconds / SECONDS_PER_MINUTE
        val hours = totalMinutes / MINUTES_PER_HOUR
        val minutes = totalMinutes % MINUTES_PER_HOUR
        val remSeconds = totalSeconds % SECONDS_PER_MINUTE
        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            totalMinutes > 0 -> "${totalMinutes}m"
            else -> "${remSeconds}s"
        }
    }

    fun formatSpokenDuration(seconds: Double): String {
        val totalSeconds = seconds.roundToInt().coerceAtLeast(0)
        val totalMinutes = totalSeconds / SECONDS_PER_MINUTE
        val hours = totalMinutes / MINUTES_PER_HOUR
        val minutes = totalMinutes % MINUTES_PER_HOUR
        val remSeconds = totalSeconds % SECONDS_PER_MINUTE
        val hourUnit = if (hours == 1) "1 hour" else "$hours hours"
        val minuteUnit = if (minutes == 1) "1 minute" else "$minutes minutes"
        return when {
            hours > 0 && minutes > 0 -> "$hourUnit $minuteUnit"
            hours > 0 -> hourUnit
            totalMinutes > 0 -> if (totalMinutes == 1) "1 minute" else "$totalMinutes minutes"
            else -> if (remSeconds == 1) "1 second" else "$remSeconds seconds"
        }
    }

    fun formatDate(
        epochMillis: Long,
        locale: Locale = Locale.getDefault(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale)
        .withZone(zoneId)
        .format(Instant.ofEpochMilli(epochMillis))

    fun formatHikeSubtitle(
        distanceMeters: Double,
        dateEpochMillis: Long,
        unit: DistanceUnit,
        locale: Locale = Locale.getDefault()
    ): String = "${formatDistance(distanceMeters, unit, locale)} · ${formatDate(dateEpochMillis, locale)}"

    fun wholePercent(fraction: Double): Int = if (!fraction.isFinite()) {
        0
    } else {
        (
            fraction.coerceIn(
                0.0,
                1.0
            ) * PERCENT_SCALE
            ).roundToInt().coerceIn(0, MAX_PERCENT)
    }

    fun formatPercent(fraction: Double, locale: Locale = Locale.getDefault()): String =
        "${NumberFormat.getIntegerInstance(locale).format(wholePercent(fraction))}%"

    fun formatTimeLeft(seconds: Double): String = "${formatDuration(seconds)} left"

    fun formatDateTime(
        epochMillis: Long,
        locale: Locale = Locale.getDefault(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(zoneId)
        .format(Instant.ofEpochMilli(epochMillis))

    fun formatTimeOfDay(
        epochMillis: Long,
        locale: Locale = Locale.getDefault(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(zoneId)
        .format(Instant.ofEpochMilli(epochMillis))

    private fun formatNumber(value: Double, fractionDigits: Int, locale: Locale): String {
        val formatter = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = fractionDigits
            maximumFractionDigits = fractionDigits
        }
        return formatter.format(value)
    }
}
