package app.openhikes.core.common

import com.google.common.truth.Truth.assertThat
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Test

class HikeTitleDrawnTest {
    @Test
    fun drawnPrefersBoundedCustomName() {
        val title = HikeTitle.drawn(
            name = "  Saturday Ridge  ",
            madeOnEpochMillis = 1_760_000_000_000L,
            zoneId = ZoneOffset.UTC,
            locale = Locale.US
        )
        assertThat(title).isEqualTo("Saturday Ridge")
    }

    @Test
    fun drawnFallsBackToTimestampedTrailNameWhenBlank() {
        val title = HikeTitle.drawn(
            name = "   ",
            madeOnEpochMillis = 1_760_000_000_000L,
            zoneId = ZoneOffset.UTC,
            locale = Locale.US
        )
        assertThat(title).startsWith("Trail, ")
        assertThat(title).contains("2025")
    }

    @Test
    fun drawnRespectsGraphemeAndByteLimits() {
        val longEmoji = "🥾".repeat(150)
        val title = HikeTitle.drawn(
            name = longEmoji,
            madeOnEpochMillis = 1_760_000_000_000L,
            zoneId = ZoneOffset.UTC,
            locale = Locale.US
        )
        assertThat(title.encodeToByteArray().size).isAtMost(HikeTitle.MAXIMUM_UTF8_BYTES)
        assertThat(title).isEqualTo("🥾".repeat(128))
    }
}
