package app.openhikes.core.ui.format

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

class StorageSizeFormatterTest {
    @Test
    fun formatsBytesKilobytesMegabytesAndGigabytes() {
        assertThat(StorageSizeFormatter.formatBytes(0L, Locale.US)).isEqualTo("0 B")
        assertThat(StorageSizeFormatter.formatBytes(512L, Locale.US)).isEqualTo("512 B")
        assertThat(StorageSizeFormatter.formatBytes(12_400L, Locale.US)).isEqualTo("12 KB")
        assertThat(StorageSizeFormatter.formatBytes(735_373_771L, Locale.US)).isEqualTo("735 MB")
        assertThat(StorageSizeFormatter.formatBytes(1_466_648_580L, Locale.US)).isEqualTo("1.47 GB")
        assertThat(StorageSizeFormatter.formatBytes(12_340_000_000L, Locale.US)).isEqualTo("12.3 GB")
    }
}
