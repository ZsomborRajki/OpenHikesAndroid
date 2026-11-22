package app.openhikes.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HikeTitleTest {
    @Test
    fun importedPrefersTrackNameFallsBackToFileStemAndCapsLongNames() {
        assertThat(HikeTitle.imported("  Alpine   Ridge ", "ignored.gpx")).isEqualTo("Alpine   Ridge")
        assertThat(HikeTitle.imported("   ", "/storage/emulated/0/Download/Thumsee Loop.gpx"))
            .isEqualTo("Thumsee Loop")
        assertThat(HikeTitle.imported(null, ".gpx")).isEmpty()

        val hugeFileName = "T".repeat(160) + ".gpx"
        assertThat(HikeTitle.imported(null, hugeFileName)).hasLength(128)
    }

    @Test
    fun titleEditDistinguishesUnchangedRenamedAndClearedDrafts() {
        assertThat(HikeTitleEdit.of("  Thumsee Loop ", "Thumsee Loop"))
            .isEqualTo(HikeTitleEdit.Unchanged)
        assertThat(HikeTitleEdit.of("Evening Walk", "Thumsee Loop"))
            .isEqualTo(HikeTitleEdit.Renamed("Evening Walk"))
        assertThat(HikeTitleEdit.of("   ", "Custom Title"))
            .isEqualTo(HikeTitleEdit.Renamed(null))
    }
}
