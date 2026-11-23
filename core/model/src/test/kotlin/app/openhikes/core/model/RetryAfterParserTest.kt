package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

class RetryAfterParserTest {
    @Test
    fun parsesDeltaSecondsAndClampsAtFifteenMinutesFor429And503Only() {
        assertThat(RetryAfterParser.parseDelayMillis(429, "120", 1_000L)).isEqualTo(120_000L)
        assertThat(RetryAfterParser.parseDelayMillis(503, "3600", 1_000L))
            .isEqualTo(RetryAfterParser.MAX_DELAY_MILLIS)
        assertThat(RetryAfterParser.parseDelayMillis(429, "0", 1_000L)).isNull()
        assertThat(RetryAfterParser.parseDelayMillis(500, "120", 1_000L)).isNull()
    }

    @Test
    fun parsesImfFixDateAndRejectsPastOrInvalidDates() {
        val now = Instant.parse("2026-11-23T08:00:00Z").toEpochMilli()
        val future = RetryAfterParser.parseDelayMillis(503, "Mon, 23 Nov 2026 08:02:30 GMT", now)
        assertThat(future).isEqualTo(150_000L)

        val past = RetryAfterParser.parseDelayMillis(503, "Mon, 23 Nov 2026 07:59:00 GMT", now)
        assertThat(past).isNull()

        assertThat(RetryAfterParser.parseDelayMillis(429, "not-a-date", now)).isNull()
    }
}
