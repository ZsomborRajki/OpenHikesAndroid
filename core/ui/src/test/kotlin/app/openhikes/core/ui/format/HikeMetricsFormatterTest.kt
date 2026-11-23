package app.openhikes.core.ui.format

import app.openhikes.core.model.DistanceUnit
import com.google.common.truth.Truth.assertThat
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Test

class HikeMetricsFormatterTest {
    @Test
    fun formatsMetricAndImperialDistancesElevationsSpeedsAndDurations() {
        assertThat(HikeMetricsFormatter.formatDistance(750.0, DistanceUnit.METRIC, Locale.US))
            .isEqualTo("750 m")
        assertThat(HikeMetricsFormatter.formatDistance(5400.0, DistanceUnit.METRIC, Locale.US))
            .isEqualTo("5.4 km")
        assertThat(HikeMetricsFormatter.formatDistance(5400.0, DistanceUnit.METRIC, Locale.GERMANY))
            .isEqualTo("5,4 km")
        assertThat(HikeMetricsFormatter.formatDistance(100.0, DistanceUnit.IMPERIAL, Locale.US))
            .isEqualTo("328 ft")
        assertThat(HikeMetricsFormatter.formatDistance(3218.688, DistanceUnit.IMPERIAL, Locale.US))
            .isEqualTo("2.0 mi")

        assertThat(HikeMetricsFormatter.formatElevation(500.0, DistanceUnit.METRIC, Locale.US))
            .isEqualTo("500 m")
        assertThat(HikeMetricsFormatter.formatSpokenElevation(500.0, DistanceUnit.METRIC, Locale.US))
            .isEqualTo("500 meters")
        assertThat(HikeMetricsFormatter.formatSpokenDistance(2000.0, DistanceUnit.METRIC, Locale.US))
            .isEqualTo("2.0 kilometers")
        assertThat(HikeMetricsFormatter.formatSpeed(1.5, DistanceUnit.METRIC, Locale.US))
            .isEqualTo("5.4 km/h")
        assertThat(HikeMetricsFormatter.formatSpokenSpeed(1.5, DistanceUnit.METRIC, Locale.US))
            .isEqualTo("5.4 kilometers per hour")
        assertThat(HikeMetricsFormatter.formatAccuracy(8.0, DistanceUnit.METRIC, Locale.US))
            .isEqualTo("±8 m")
        assertThat(HikeMetricsFormatter.formatCount(1250, Locale.US))
            .isEqualTo("1,250")

        assertThat(HikeMetricsFormatter.formatDuration(45.0)).isEqualTo("45s")
        assertThat(HikeMetricsFormatter.formatDuration(720.0)).isEqualTo("12m")
        assertThat(HikeMetricsFormatter.formatDuration(3600.0)).isEqualTo("1h")
        assertThat(HikeMetricsFormatter.formatDuration(5400.0)).isEqualTo("1h 30m")
        assertThat(HikeMetricsFormatter.formatSpokenDuration(5400.0)).isEqualTo("1 hour 30 minutes")

        val dateText = HikeMetricsFormatter.formatDate(1_767_225_600_000L, Locale.US, ZoneOffset.UTC)
        assertThat(dateText).contains("2026")
    }
}
