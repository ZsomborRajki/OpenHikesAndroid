package app.openhikes.core.model

import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Parses HTTP `Retry-After` headers (integer delta-seconds or IMF-fixdate) for HTTP 429 and 503
 * responses, clamping positive delays to [MAX_DELAY_MILLIS].
 */
object RetryAfterParser {
    const val HTTP_TOO_MANY_REQUESTS = 429
    const val HTTP_SERVICE_UNAVAILABLE = 503
    const val MAX_DELAY_MILLIS = 15L * 60L * 1_000L
    private const val MILLIS_PER_SECOND = 1_000L

    private val imfFixDateFormatter: DateTimeFormatter = DateTimeFormatter
        .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        .withZone(ZoneOffset.UTC)

    /**
     * Parses [headerValue] for [statusCode] relative to [nowEpochMillis], returning a positive delay
     * in milliseconds clamped to [MAX_DELAY_MILLIS], or `null` when inapplicable, past, or invalid.
     */
    fun parseDelayMillis(statusCode: Int, headerValue: String?, nowEpochMillis: Long): Long? {
        val trimmed = headerValue?.trim()?.takeIf { it.isNotEmpty() }
        val applicable = statusCode == HTTP_TOO_MANY_REQUESTS || statusCode == HTTP_SERVICE_UNAVAILABLE
        return if (!applicable || trimmed == null) {
            null
        } else {
            parseDeltaSecondsMillis(trimmed) ?: parseImfFixDateMillis(trimmed, nowEpochMillis)
        }
    }

    private fun parseDeltaSecondsMillis(value: String): Long? {
        val seconds = value.toLongOrNull() ?: return null
        return if (seconds <= 0L) {
            null
        } else if (seconds > MAX_DELAY_MILLIS / MILLIS_PER_SECOND) {
            MAX_DELAY_MILLIS
        } else {
            (seconds * MILLIS_PER_SECOND).coerceAtMost(MAX_DELAY_MILLIS)
        }
    }

    private fun parseImfFixDateMillis(value: String, nowEpochMillis: Long): Long? {
        val targetInstant = parseImfFixDate(value) ?: return null
        val deltaMillis = targetInstant.toEpochMilli() - nowEpochMillis
        return deltaMillis.takeIf { it > 0L }?.coerceAtMost(MAX_DELAY_MILLIS)
    }

    private fun parseImfFixDate(value: String): Instant? = try {
        ZonedDateTime.parse(value, imfFixDateFormatter).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }
}
