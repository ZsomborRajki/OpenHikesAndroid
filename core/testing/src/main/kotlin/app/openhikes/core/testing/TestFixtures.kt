package app.openhikes.core.testing

import app.openhikes.core.model.GeoCoordinate

/**
 * Shared pure-JVM test fixtures ported from iOS OpenHikesUITests / SimulatedLocations.
 */
object TestFixtures {
    const val THUMSEE_LOOP_FAST_NAME = "ThumseeLoopFast"
    const val THUMSEE_LOOP_FAST_TITLE = "Thumsee Loop (fast, simulated)"

    val TRAILHEAD = GeoCoordinate(latitude = 47.718420, longitude = 12.831774)
    val REVIEWABLE_TRACE: List<GeoCoordinate> = listOf(
        GeoCoordinate(latitude = 47.71840, longitude = 12.83180),
        GeoCoordinate(latitude = 47.71860, longitude = 12.83180),
        GeoCoordinate(latitude = 47.71880, longitude = 12.83180)
    )
    val TRAIL_POINTS: List<GeoCoordinate> = listOf(
        TRAILHEAD,
        GeoCoordinate(latitude = 47.718598, longitude = 12.831420),
        GeoCoordinate(latitude = 47.718823, longitude = 12.831149),
        GeoCoordinate(latitude = 47.719219, longitude = 12.830877),
        GeoCoordinate(latitude = 47.719317, longitude = 12.830874),
        GeoCoordinate(latitude = 47.719474, longitude = 12.830948)
    )

    private const val THUMSEE_LOOP_FAST_RESOURCE = "/fixtures/ThumseeLoopFast.gpx"

    fun thumseeLoopFastGpxBytes(): ByteArray {
        val stream = requireNotNull(TestFixtures::class.java.getResourceAsStream(THUMSEE_LOOP_FAST_RESOURCE)) {
            "Missing bundled GPX fixture: $THUMSEE_LOOP_FAST_RESOURCE"
        }
        return stream.use { it.readBytes() }
    }

    /**
     * Two-segment GPX track with an explicit pause gap between `<trkseg>` blocks,
     * used to verify pause-boundary preservation across import, profile building, and export.
     */
    fun samplePausedGpxXml(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="OpenHikesTest" xmlns="http://www.topografix.com/GPX/1/1">
          <trk>
            <name>Paused Ridge Walk</name>
            <desc>Morning climb and afternoon return</desc>
            <trkseg>
              <trkpt lat="47.718420" lon="12.831774">
                <ele>533.50</ele>
                <time>2026-01-01T08:00:00Z</time>
              </trkpt>
              <trkpt lat="47.719219" lon="12.830877">
                <ele>542.00</ele>
                <time>2026-01-01T08:05:00Z</time>
              </trkpt>
            </trkseg>
            <trkseg>
              <trkpt lat="47.730000" lon="12.850000">
                <ele>620.00</ele>
                <time>2026-01-01T09:00:00Z</time>
              </trkpt>
              <trkpt lat="47.730800" lon="12.850900">
                <ele>635.00</ele>
                <time>2026-01-01T09:05:00Z</time>
              </trkpt>
            </trkseg>
          </trk>
        </gpx>
    """.trimIndent()
}
