package app.openhikes.core.common

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Test

class RecordingTitleTest {
    @Test
    fun formatsTimeOfDayAndLocalizedDateWithinTitleBounds() {
        val morningMillis = Instant.parse("2026-11-20T08:30:00Z").toEpochMilli()
        val afternoonMillis = Instant.parse("2026-11-20T14:15:00Z").toEpochMilli()
        val eveningMillis = Instant.parse("2026-11-20T19:45:00Z").toEpochMilli()
        val nightMillis = Instant.parse("2026-11-20T02:10:00Z").toEpochMilli()

        assertThat(RecordingTitle.defaultTitle(morningMillis, ZoneOffset.UTC, Locale.US))
            .isEqualTo("Morning Hike – Nov 20, 2026")
        assertThat(RecordingTitle.defaultTitle(afternoonMillis, ZoneOffset.UTC, Locale.US))
            .isEqualTo("Afternoon Hike – Nov 20, 2026")
        assertThat(RecordingTitle.defaultTitle(eveningMillis, ZoneOffset.UTC, Locale.US))
            .isEqualTo("Evening Hike – Nov 20, 2026")
        assertThat(RecordingTitle.defaultTitle(nightMillis, ZoneOffset.UTC, Locale.US))
            .isEqualTo("Hike – Nov 20, 2026")
    }

    @Test
    fun recordingStopNameDistinguishesUnchangedOfferedTitleFromCustomInput() {
        val offered = "Afternoon Hike – Nov 20, 2026"
        assertThat(RecordingStopName.typed(offered, offered)).isNull()
        assertThat(RecordingStopName.typed("   ", offered)).isEqualTo(null)
        assertThat(RecordingStopName.typed("Summit Day", offered)).isEqualTo("Summit Day")

        val tooLong = "🥾".repeat(140)
        val result = RecordingStopName.typed(tooLong, offered)
        assertThat(result).isNotNull()
        assertThat(result!!.toByteArray(Charsets.UTF_8).size).isAtMost(HikeTitle.MAXIMUM_UTF8_BYTES)
    }
}
