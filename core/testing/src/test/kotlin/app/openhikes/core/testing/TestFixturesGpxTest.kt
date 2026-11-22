package app.openhikes.core.testing

import app.openhikes.core.common.HikeTitle
import app.openhikes.core.common.gpx.GpxExporter
import app.openhikes.core.common.gpx.GpxParseResult
import app.openhikes.core.common.gpx.GpxParser
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteBoundary
import app.openhikes.core.model.RouteProfile
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TestFixturesGpxTest {
    @Test
    fun bundledThumseeLoopFixtureParsesBuildsProfileAndRoundTrips() {
        val bytes = TestFixtures.thumseeLoopFastGpxBytes()
        val parsed = GpxParser.parseSingle(bytes)
        assertThat(parsed).isInstanceOf(GpxParseResult.Success::class.java)

        val track = (parsed as GpxParseResult.Success).value
        assertThat(HikeTitle.imported(track.name, TestFixtures.THUMSEE_LOOP_FAST_NAME))
            .isEqualTo(TestFixtures.THUMSEE_LOOP_FAST_TITLE)
        assertThat(track.route.size).isGreaterThan(10)
        assertThat(track.distanceMeters).isGreaterThan(1000.0)

        val profile = RouteProfile.from(track.route)
        assertThat(profile.samples.size).isGreaterThan(2)
        assertThat(profile.elevation.gainMeters ?: 0.0).isGreaterThan(0.0)

        val hike = Hike(
            id = HikeId("thumsee"),
            title = TestFixtures.THUMSEE_LOOP_FAST_TITLE,
            distanceMeters = track.distanceMeters,
            dateEpochMillis = track.startTimeEpochMillis ?: 0L,
            route = track.route
        )
        val exported = GpxExporter.export(hike)
        val reparsed = (GpxParser.parseSingle(exported.xml.encodeToByteArray()) as GpxParseResult.Success).value
        assertThat(reparsed.route.size).isEqualTo(track.route.size)
    }

    @Test
    fun samplePausedFixturePreservesPauseSegmentAcrossParseAndExport() {
        val track = (
            GpxParser.parseSingle(TestFixtures.samplePausedGpxXml().encodeToByteArray())
                as GpxParseResult.Success
            ).value
        assertThat(track.route).hasSize(4)
        assertThat(track.route[2].boundary).isEqualTo(RouteBoundary.PAUSED)

        val xml = GpxExporter.exportXml(
            Hike(
                id = HikeId("paused"),
                title = track.name ?: "Paused",
                distanceMeters = track.distanceMeters,
                dateEpochMillis = track.startTimeEpochMillis ?: 0L,
                route = track.route
            )
        )
        val reparsed = (GpxParser.parseSingle(xml.encodeToByteArray()) as GpxParseResult.Success).value
        assertThat(reparsed.route[2].boundary).isEqualTo(RouteBoundary.PAUSED)
    }
}
