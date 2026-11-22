package app.openhikes.core.common.gpx

import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteBoundary
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.TrailPoint
import com.google.common.truth.Truth.assertThat
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Test

class GpxExporterTest {
    @Test
    fun roundTripsPauseBoundariesAndEscapesXmlUnderCommaDecimalLocale() {
        val hike = samplePausedHikeWithSpecialChars()
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        val exported = try {
            GpxExporter.export(hike, ZoneOffset.UTC)
        } finally {
            Locale.setDefault(previousLocale)
        }

        assertThat(exported.xml).contains("lat=\"47.7184200\"")
        assertThat(exported.xml).contains("<name>Ridge &amp; Valley &lt;Loop&gt;</name>")
        assertThat(exported.xml).doesNotContain("\u0007")
        assertThat(exported.fileName).endsWith("-2026-01-01.gpx")
        assertThat(exported.fileName).doesNotContain("<")

        val reparsed = (GpxParser.parseSingle(exported.xml.encodeToByteArray()) as GpxParseResult.Success).value
        assertThat(reparsed.route).hasSize(4)
        assertThat(reparsed.route[2].boundary).isEqualTo(RouteBoundary.PAUSED)
        assertThat(reparsed.places.map { it.name }).containsExactly("Spring & Fountain")
    }

    private fun samplePausedHikeWithSpecialChars(): Hike = Hike(
        id = HikeId("h1"),
        title = "Ridge & Valley <Loop> \"West\"",
        customName = "Ridge & Valley <Loop>\u0007",
        distanceMeters = 2500.0,
        dateEpochMillis = 1_767_225_600_000L, // 2026-01-01T00:00:00Z
        trackDescription = "Steep & scenic",
        author = "Ada",
        keywords = "ridge",
        route = listOf(
            RouteCoordinate(
                47.718420,
                12.831774,
                elevationMeters = 533.5,
                timestampEpochMillis = 1_767_225_600_000L
            ),
            RouteCoordinate(
                47.719219,
                12.830877,
                elevationMeters = 542.0,
                timestampEpochMillis = 1_767_225_900_000L
            ),
            RouteCoordinate(
                latitude = 47.730000,
                longitude = 12.850000,
                elevationMeters = 620.0,
                timestampEpochMillis = 1_767_229_200_000L,
                boundary = RouteBoundary.PAUSED
            ),
            RouteCoordinate(
                47.730800,
                12.850900,
                elevationMeters = 635.0,
                timestampEpochMillis = 1_767_229_500_000L
            )
        ),
        places = listOf(
            TrailPoint(
                id = "w1",
                hikeId = HikeId("h1"),
                coordinate = GeoCoordinate(47.7185, 12.8318),
                name = "Spring & Fountain"
            )
        )
    )

    @Test
    fun capsExportFilenameWithinPosixByteLimitAndTruncatesReservedChars() {
        val longEmojiTitle = "path/with:reserved?" + "\uD83E\uDD7E".repeat(80)
        val hike = Hike(
            id = HikeId("h2"),
            title = longEmojiTitle,
            distanceMeters = 100.0,
            dateEpochMillis = 1_767_225_600_000L
        )
        val fileName = GpxExporter.fileName(hike, ZoneOffset.UTC)

        assertThat(fileName.toByteArray(Charsets.UTF_8).size).isAtMost(255)
        assertThat(fileName).doesNotContain("/")
        assertThat(fileName).doesNotContain(":")
        assertThat(fileName).doesNotContain("?")
        assertThat(fileName).endsWith("-2026-01-01.gpx")
    }
}
