package app.openhikes.core.common.photos

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Test

class PhotoCaptureDateTest {
    @Test
    fun parsesValidExifTimestampWithOffsetAndPriorityFallback() {
        val parsed = PhotoCaptureDate.resolveCaptureTimestamp(
            dateTimeOriginal = "2026:07:14 10:30:45",
            offsetTimeOriginal = "+02:00",
            dateTimeDigitized = "2026:07:14 09:00:00",
            offsetTimeDigitized = "+02:00",
            tiffDateTime = null,
            offsetTime = null,
            fallbackZone = ZoneOffset.UTC
        )
        assertThat(parsed).isEqualTo(Instant.parse("2026-07-14T08:30:45Z").toEpochMilli())

        val fallbackParsed = PhotoCaptureDate.resolveCaptureTimestamp(
            dateTimeOriginal = "0000:00:00 00:00:00",
            offsetTimeOriginal = null,
            dateTimeDigitized = "2026:07:14 09:15:00",
            offsetTimeDigitized = "-05:00",
            tiffDateTime = null,
            offsetTime = null,
            fallbackZone = ZoneOffset.UTC
        )
        assertThat(fallbackParsed).isEqualTo(Instant.parse("2026-07-14T14:15:00Z").toEpochMilli())
    }

    @Test
    fun rejectsImpossibleDatesAndOutOfRangeOffsetsWhileAcceptingLeapSecond() {
        assertThat(PhotoCaptureDate.parseExifTimestamp("2026:02:30 12:00:00", "+00:00")).isNull()
        assertThat(PhotoCaptureDate.parseExifTimestamp("0000:00:00 00:00:00", "+00:00")).isNull()
        assertThat(PhotoCaptureDate.parseZoneOffset("+14:30")).isNull()
        assertThat(PhotoCaptureDate.parseZoneOffset("+14:00")).isEqualTo(ZoneOffset.ofHours(14))

        val leap = PhotoCaptureDate.parseExifTimestamp("2016:12:31 23:59:60", "+00:00")
        assertThat(leap).isEqualTo(Instant.parse("2017-01-01T00:00:00Z").toEpochMilli())
    }

    @Test
    fun formatsWallClockAndUtcGpsStamps() {
        val instant = Instant.parse("2026-08-05T14:05:09Z").toEpochMilli()
        assertThat(PhotoCaptureDate.formatExifWallClock(instant, ZoneOffset.ofHours(2)))
            .isEqualTo("2026:08:05 16:05:09")
        assertThat(PhotoCaptureDate.formatExifOffset(instant, ZoneOffset.ofHours(2)))
            .isEqualTo("+02:00")
        assertThat(PhotoCaptureDate.formatGpsDateStamp(instant)).isEqualTo("2026:08:05")
        assertThat(PhotoCaptureDate.formatGpsTimeStamp(instant)).isEqualTo("14/1,05/1,09/1")
    }
}
