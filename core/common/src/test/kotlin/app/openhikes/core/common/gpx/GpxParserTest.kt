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

    @Test
    fun ignoresForeignNamespaceAndExtensionsSubtreesWithoutCorruptingPointsOrNames() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1"
                 xmlns="http://www.topografix.com/GPX/1/1"
                 xmlns:vendor="https://example.com/vendor/gpx">
              <metadata>
                <name>True Ridge</name>
                <extensions>
                  <name>Vendor Metadata Override</name>
                  <wpt lat="1.0" lon="1.0"><name>Fake Extension Place</name></wpt>
                </extensions>
              </metadata>
              <trk>
                <name>Alpine Ridge</name>
                <extensions>
                  <name>Vendor Track Override</name>
                </extensions>
                <trkseg>
                  <trkpt lat="47.7180" lon="12.8300">
                    <ele>530.0</ele>
                    <extensions>
                      <vendor:trkpt lat="1.0" lon="1.0">
                        <vendor:ele>9999.0</vendor:ele>
                      </vendor:trkpt>
                      <trkpt lat="2.0" lon="2.0"/>
                    </extensions>
                    <time>2026-01-01T08:00:00Z</time>
                  </trkpt>
                  <trkpt lat="47.7190" lon="12.8310">
                    <ele>545.0</ele>
                    <vendor:trkpt lat="3.0" lon="3.0"/>
                    <time>2026-01-01T08:05:00Z</time>
                  </trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent().encodeToByteArray()

        val track = (GpxParser.parseSingle(gpx) as GpxParseResult.Success).value
        assertThat(track.name).isEqualTo("Alpine Ridge")
        assertThat(track.places).isEmpty()
        assertThat(track.route).hasSize(2)
        assertThat(track.route[0].latitude).isWithin(1e-6).of(47.7180)
        assertThat(track.route[0].longitude).isWithin(1e-6).of(12.8300)
        assertThat(track.route[0].elevationMeters).isEqualTo(530.0)
        assertThat(track.route[0].timestampEpochMillis).isEqualTo(1_767_254_400_000L)
        assertThat(track.route[1].latitude).isWithin(1e-6).of(47.7190)
        assertThat(track.distanceMeters).isLessThan(200.0)
    }

    @Test
    fun parsesPrefixedGpxNamespaceAndUnnamespacedGpxDocuments() {
        val prefixedGpx = """
            <gpx:gpx version="1.1" xmlns:gpx="http://www.topografix.com/GPX/1/1">
              <gpx:trk>
                <gpx:name>Prefixed Walk</gpx:name>
                <gpx:trkseg>
                  <gpx:trkpt lat="47.71" lon="12.83"><gpx:ele>500</gpx:ele></gpx:trkpt>
                  <gpx:trkpt lat="47.72" lon="12.84"><gpx:ele>520</gpx:ele></gpx:trkpt>
                </gpx:trkseg>
              </gpx:trk>
            </gpx:gpx>
        """.trimIndent().encodeToByteArray()
        val unnamespacedGpx = """
            <gpx version="1.1">
              <trk>
                <name>Plain Walk</name>
                <trkseg>
                  <trkpt lat="47.71" lon="12.83"/>
                  <trkpt lat="47.72" lon="12.84"/>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent().encodeToByteArray()

        val prefixedTrack = (GpxParser.parseSingle(prefixedGpx) as GpxParseResult.Success).value
        val plainTrack = (GpxParser.parseSingle(unnamespacedGpx) as GpxParseResult.Success).value
        assertThat(prefixedTrack.name).isEqualTo("Prefixed Walk")
        assertThat(prefixedTrack.route).hasSize(2)
        assertThat(plainTrack.name).isEqualTo("Plain Walk")
        assertThat(plainTrack.route).hasSize(2)
    }

    @Test
    fun parsesOsmLinkHrefOnWaypointsAndLeavesNonOsmLinksHikersOwn() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <wpt lat="47.7185" lon="12.8318">
                <name>Boathouse</name>
                <desc>Rowing boats in summer</desc>
                <sym>Shelter</sym>
              </wpt>
              <wpt lat="47.7210" lon="12.8300">
                <name>Thumsee Spring</name>
                <sym>Water</sym>
                <link href="https://www.openstreetmap.org/node/123456"/>
              </wpt>
              <trk>
                <name>Thumsee Loop</name>
                <trkseg>
                  <trkpt lat="47.7184" lon="12.8317"/>
                  <trkpt lat="47.7215" lon="12.8295"/>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent().encodeToByteArray()

        val track = (GpxParser.parseSingle(gpx) as GpxParseResult.Success).value
        assertThat(track.places).hasSize(2)

        val boathouse = track.places.first { it.name == "Boathouse" }
        assertThat(boathouse.osm).isNull()
        assertThat(boathouse.symbol).isEqualTo("Shelter")
        assertThat(boathouse.note).isEqualTo("Rowing boats in summer")

        val spring = track.places.first { it.name == "Thumsee Spring" }
        assertThat(spring.osm?.elementType).isEqualTo("node")
        assertThat(spring.osm?.elementId).isEqualTo(123456L)
        assertThat(spring.symbol).isEqualTo("Water")
    }
}
