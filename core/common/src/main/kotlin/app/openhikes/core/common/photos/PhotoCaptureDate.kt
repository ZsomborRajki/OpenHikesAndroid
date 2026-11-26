package app.openhikes.core.common.photos

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure-JVM parser and formatter for EXIF capture timestamps (`yyyy:MM:dd HH:mm:ss`) and timezone
 * offsets (`±HH:MM`), plus UTC GPS date/time stamps for lossless metadata stamping.
 */
object PhotoCaptureDate {
    private const val OFFSET_LENGTH = 6
    private const val MAX_OFFSET_HOURS = 14
    private const val MAX_OFFSET_MINUTES = 59
    private const val MIN_YEAR = 1970
    private const val MAX_YEAR = 9999
    private const val MIN_MONTH = 1
    private const val MAX_MONTH = 12
    private const val MIN_DAY = 1
    private const val MAX_HOUR = 23
    private const val MAX_MINUTE = 59
    private const val LEAP_SECOND = 60
    private const val MAX_STANDARD_SECOND = 59
    private const val MILLIS_PER_SECOND = 1000L

    private val EXIF_WALL_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT)
    private val GPS_DATE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy:MM:dd", Locale.ROOT)

    /**
     * Parses an EXIF `OffsetTimeOriginal` / `OffsetTime` value (`±HH:MM`, hours `0..14`, minutes
     * `0..59`) into a [ZoneOffset], or returns `null` when malformed or out of bounds.
     */
    fun parseZoneOffset(raw: String?): ZoneOffset? {
        val text = raw?.trim()?.takeIf { it.length == OFFSET_LENGTH && it[3] == ':' }
        val sign = when (text?.get(0)) {
            '+' -> 1
            '-' -> -1
            else -> null
        }
        val hours = text?.substring(1, 3)?.toIntOrNull()
        val minutes = text?.substring(4, 6)?.toIntOrNull()
        val valid = sign != null &&
            hours != null &&
            minutes != null &&
            hours in 0..MAX_OFFSET_HOURS &&
            minutes in 0..MAX_OFFSET_MINUTES &&
            !(hours == MAX_OFFSET_HOURS && minutes > 0)
        return if (valid) {
            ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes)
        } else {
            null
        }
    }

    /**
     * Parses an EXIF timestamp string (`yyyy:MM:dd HH:mm:ss`) using [offsetText] if valid or
     * [fallbackZone] otherwise. Rejects placeholder dates (`0000:00:00`) and impossible calendar
     * dates (such as February 30), while accepting leap second `60`.
     */
    private val EXIF_DATE_REGEX = Regex("""^(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})$""")

    fun parseExifTimestamp(
        rawDateTime: String?,
        offsetText: String? = null,
        fallbackZone: ZoneId = ZoneId.systemDefault()
    ): Long? {
        val parts = parseComponents(rawDateTime)
        val date = parts?.let { runCatching { LocalDate.of(it.year, it.month, it.day) }.getOrNull() }
        return if (parts != null && date != null) {
            val zone: ZoneId = parseZoneOffset(offsetText) ?: fallbackZone
            val clampedSecond = minOf(parts.second, MAX_STANDARD_SECOND)
            val extraMillis = if (parts.second == LEAP_SECOND) MILLIS_PER_SECOND else 0L
            val localDateTime = LocalDateTime.of(date, LocalTime.of(parts.hour, parts.minute, clampedSecond))
            localDateTime.atZone(zone).toInstant().toEpochMilli() + extraMillis
        } else {
            null
        }
    }

    /**
     * Selects the highest-priority valid EXIF timestamp (`DateTimeOriginal` > `DateTimeDigitized` >
     * TIFF `DateTime`).
     */
    fun resolveCaptureTimestamp(
        dateTimeOriginal: String?,
        offsetTimeOriginal: String?,
        dateTimeDigitized: String?,
        offsetTimeDigitized: String?,
        tiffDateTime: String?,
        offsetTime: String?,
        fallbackZone: ZoneId = ZoneId.systemDefault()
    ): Long? = parseExifTimestamp(dateTimeOriginal, offsetTimeOriginal, fallbackZone)
        ?: parseExifTimestamp(dateTimeDigitized, offsetTimeDigitized, fallbackZone)
        ?: parseExifTimestamp(tiffDateTime, offsetTime, fallbackZone)

    /**
     * Formats [epochMillis] as a local EXIF wall-clock timestamp (`yyyy:MM:dd HH:mm:ss`).
     */
    fun formatExifWallClock(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(EXIF_WALL_FORMAT)

    /**
     * Formats [epochMillis]'s offset in [zone] as an EXIF `±HH:MM` offset string.
     */
    fun formatExifOffset(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val offset = Instant.ofEpochMilli(epochMillis).atZone(zone).offset
        val totalSeconds = offset.totalSeconds
        val sign = if (totalSeconds >= 0) '+' else '-'
        val absSeconds = kotlin.math.abs(totalSeconds)
        val hours = absSeconds / SECONDS_PER_HOUR
        val minutes = (absSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        return String.format(Locale.ROOT, "%c%02d:%02d", sign, hours, minutes)
    }

    /**
     * Formats [epochMillis] as UTC `GPSDateStamp` (`yyyy:MM:dd`).
     */
    fun formatGpsDateStamp(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).format(GPS_DATE_FORMAT)

    /**
     * Formats [epochMillis] as UTC `GPSTimeStamp` (`HH/1,MM/1,SS/1`).
     */
    fun formatGpsTimeStamp(epochMillis: Long): String {
        val utc = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC)
        return String.format(Locale.ROOT, "%02d/1,%02d/1,%02d/1", utc.hour, utc.minute, utc.second)
    }

    private data class ExifDateParts(
        val year: Int,
        val month: Int,
        val day: Int,
        val hour: Int,
        val minute: Int,
        val second: Int
    )

    private fun parseComponents(raw: String?): ExifDateParts? {
        val match = raw?.trim()?.let { EXIF_DATE_REGEX.matchEntire(it) } ?: return null
        val values = match.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
        val parts = if (values.size == EXIF_PART_COUNT) {
            ExifDateParts(
                year = values[0],
                month = values[1],
                day = values[2],
                hour = values[3],
                minute = values[4],
                second = values[5]
            )
        } else {
            null
        }
        return parts?.takeIf(::isWithinCalendarBounds)
    }

    private fun isWithinCalendarBounds(parts: ExifDateParts): Boolean = parts.year in MIN_YEAR..MAX_YEAR &&
        parts.month in MIN_MONTH..MAX_MONTH &&
        parts.day >= MIN_DAY &&
        parts.hour in 0..MAX_HOUR &&
        parts.minute in 0..MAX_MINUTE &&
        parts.second in 0..LEAP_SECOND

    private const val EXIF_PART_COUNT = 6
    private const val SECONDS_PER_HOUR = 3600
    private const val SECONDS_PER_MINUTE = 60
}
