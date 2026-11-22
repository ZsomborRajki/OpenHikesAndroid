package app.openhikes.core.common.gpx

import app.openhikes.core.model.GpxImportFailure
import app.openhikes.core.model.GpxImportLimits
import app.openhikes.core.model.RouteBoundary
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GpxParserTest {
    @Test
    fun parsesMultiSegmentTrackWithPauseBoundaryWaypointsAndFiltersPhotoMarker() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="Test" xmlns="http://www.topografix.com/GPX/1/1">
              <metadata>
                <name>Fallback Title</name>
                <desc>Scenic lake walk</desc>
                <author><name>Hiker</name></author>
                <keywords>lake,alpine</keywords>
              </metadata>
              <wpt lat="47.7185" lon="12.8318"><ele>535</ele><name>Trailhead</name></wpt>
              <wpt lat="47.7186" lon="12.8319"><name>Camera</name><sym>Photo</sym></wpt>
              <trk>
                <name>Lake Circuit</name>
                <trkseg>
                  <trkpt lat="47.7184" lon="12.8317"><ele>533</ele><time>2026-01-01T08:00:00Z</time></trkpt>
                  <trkpt lat="47.7192" lon="12.8308"><ele>540</ele><time>2026-01-01T08:05:00Z</time></trkpt>
                </trkseg>
                <trkseg>
                  <trkpt lat="47.7300" lon="12.8500"><ele>620</ele><time>2026-01-01T09:00:00Z</time></trkpt>
                  <trkpt lat="47.7308" lon="12.8509"><ele>630</ele><time>2026-01-01T09:05:00Z</time></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent().encodeToByteArray()

        val result = GpxParser.parseSingle(gpx) as GpxParseResult.Success
        val track = result.value

        assertThat(track.name).isEqualTo("Lake Circuit")
        assertThat(track.author).isEqualTo("Hiker")
        assertThat(track.keywords).isEqualTo("lake,alpine")
        assertThat(track.route).hasSize(4)
        assertThat(track.route[2].boundary).isEqualTo(RouteBoundary.PAUSED)
        assertThat(track.places.map { it.name }).containsExactly("Trailhead")
    }

    @Test
    fun dropsZeroOriginTeleportsAndElevationSpikes() {
        val gpx = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk>
                <trkseg>
                  <trkpt lat="0.0" lon="0.0"><ele>0</ele></trkpt>
                  <trkpt lat="47.7180" lon="12.8300"><ele>530</ele></trkpt>
                  <trkpt lat="47.7182" lon="12.8302"><ele>1500</ele></trkpt>
                  <trkpt lat="10.0" lon="10.0"><ele>535</ele></trkpt>
                  <trkpt lat="47.7185" lon="12.8305"><ele>536</ele></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent().encodeToByteArray()

        val track = (GpxParser.parseSingle(gpx) as GpxParseResult.Success).value
        assertThat(track.route).hasSize(3)
        assertThat(track.route[1].elevationMeters).isNull()
        assertThat(track.route[2].elevationMeters).isEqualTo(536.0)
    }

    @Test
    fun rejectsMultipleTracksOnParseSingleWhileParseAllAssignsWaypointsToNearestTrack() {
        val gpx = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <wpt lat="47.0005" lon="12.0000"><name>South Point</name></wpt>
              <wpt lat="48.0005" lon="13.0000"><name>North Point</name></wpt>
              <trk><name>South</name><trkseg>
                <trkpt lat="47.0000" lon="12.0000"/><trkpt lat="47.0010" lon="12.0000"/>
              </trkseg></trk>
              <trk><name>North</name><trkseg>
                <trkpt lat="48.0000" lon="13.0000"/><trkpt lat="48.0010" lon="13.0000"/>
              </trkseg></trk>
            </gpx>
        """.trimIndent().encodeToByteArray()

        val single = GpxParser.parseSingle(gpx) as GpxParseResult.Failure
        assertThat(single.reason).isEqualTo(GpxImportFailure.MULTIPLE_TRACKS)

        val all = (GpxParser.parseAll(gpx) as GpxParseResult.Success).value
        assertThat(all).hasSize(2)
        assertThat(all[0].places.map { it.name }).containsExactly("South Point")
        assertThat(all[1].places.map { it.name }).containsExactly("North Point")
    }

    @Test
    fun enforcesLimitsAndRejectsUnreadableXmlOrExternalEntities() {
        val tinyGpx = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><trkseg><trkpt lat="47.0" lon="12.0"/><trkpt lat="47.1" lon="12.1"/></trkseg></trk>
            </gpx>
        """.trimIndent().encodeToByteArray()

        val sizeLimitFail = GpxParser.parseAll(tinyGpx, GpxImportLimits(maxFileSizeBytes = 10))
        assertThat(sizeLimitFail).isEqualTo(GpxParseResult.Failure(GpxImportFailure.TOO_LARGE))

        val pointLimitFail = GpxParser.parseAll(tinyGpx, GpxImportLimits(maxPointCount = 1))
        assertThat(pointLimitFail).isEqualTo(GpxParseResult.Failure(GpxImportFailure.TOO_LARGE))

        val malformed = "<gpx><trk>".encodeToByteArray()
        assertThat(GpxParser.parseAll(malformed)).isEqualTo(GpxParseResult.Failure(GpxImportFailure.UNREADABLE))

        val noUsable = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><trkseg><trkpt lat="0.0" lon="0.0"/></trkseg></trk>
            </gpx>
        """.trimIndent().encodeToByteArray()
        assertThat(GpxParser.parseAll(noUsable)).isEqualTo(GpxParseResult.Failure(GpxImportFailure.NO_USABLE_POINTS))
    }

    @Test
    fun fallsBackToRoutePointsOrWaypointsWhenTrackPointsAreAbsent() {
        val rteGpx = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <rte><name>Planned Trail</name>
                <rtept lat="47.70" lon="12.80"><ele>510</ele></rtept>
                <rtept lat="47.71" lon="12.81"><ele>540</ele></rtept>
              </rte>
            </gpx>
        """.trimIndent().encodeToByteArray()

        val track = (GpxParser.parseSingle(rteGpx) as GpxParseResult.Success).value
        assertThat(track.name).isEqualTo("Planned Trail")
        assertThat(track.route).hasSize(2)
        assertThat(track.places).isEmpty()
    }
}
