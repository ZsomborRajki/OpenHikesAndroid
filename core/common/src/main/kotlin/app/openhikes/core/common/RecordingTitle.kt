package app.openhikes.core.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Generates default hike titles for recordings and applies [HikeTitle] bounds.
 */
object RecordingTitle {
    const val SEPARATOR = " – "
    private const val MORNING_START_HOUR = 5
    private const val NOON_HOUR = 12
    private const val AFTERNOON_END_HOUR = 17
    private const val EVENING_END_HOUR = 22

    fun defaultTitle(
        startedAtEpochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault()
    ): String {
        val instant = Instant.ofEpochMilli(startedAtEpochMillis)
        val localTime = instant.atZone(zoneId)
        val timeOfDay = when (localTime.hour) {
            in MORNING_START_HOUR until NOON_HOUR -> "Morning Hike"
            in NOON_HOUR until AFTERNOON_END_HOUR -> "Afternoon Hike"
            in AFTERNOON_END_HOUR until EVENING_END_HOUR -> "Evening Hike"
            else -> "Hike"
        }
        val dayText = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(locale)
            .withZone(zoneId)
            .format(instant)
        val raw = "$timeOfDay$SEPARATOR$dayText"
        return HikeTitle.bounded(raw).orEmpty().ifBlank { timeOfDay }
    }
}

/**
 * Resolves whether the hiker typed a custom name at Stop or kept the offered title.
 */
object RecordingStopName {
    fun typed(field: String, offered: String?): String? = when (val edit = HikeTitleEdit.of(field, offered.orEmpty())) {
        HikeTitleEdit.Unchanged -> null
        is HikeTitleEdit.Renamed -> edit.customName
    }
}
