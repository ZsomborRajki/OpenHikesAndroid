package app.openhikes.core.testing

import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeWalk
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.TrailWalkEndReason
import app.openhikes.core.model.TrailWalkRecord

/**
 * Shared pure-JVM test fixtures ported from iOS OpenHikesUITests / SimulatedLocations.
 */
object TestFixtures {
    const val THUMSEE_LOOP_FAST_NAME = "ThumseeLoopFast"
    const val THUMSEE_LOOP_FAST_TITLE = "Thumsee Loop (fast, simulated)"
    const val THUMSEE_WALK_THEN_DRIVE_NAME = "ThumseeWalkThenDrive"
    const val THUMSEE_WALK_THEN_DRIVE_TITLE = "Thumsee Walk Then Drive (simulated)"
    const val SEED_WALK_HALF_LOOP = "HalfLoop"

    val TRAILHEAD = GeoCoordinate(latitude = 47.718420, longitude = 12.831774)
    val OFF_TRAIL_COORDINATE = GeoCoordinate(latitude = 47.709420, longitude = 12.831774)
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
    private const val THUMSEE_WALK_THEN_DRIVE_RESOURCE = "/fixtures/ThumseeWalkThenDrive.gpx"
    private const val OFFLINE_COUNTRIES_SEED_RESOURCE = "/fixtures/OfflineCountriesSeed.json"

    fun thumseeLoopFastGpxBytes(): ByteArray {
        val stream = requireNotNull(TestFixtures::class.java.getResourceAsStream(THUMSEE_LOOP_FAST_RESOURCE)) {
            "Missing bundled GPX fixture: $THUMSEE_LOOP_FAST_RESOURCE"
        }
        return stream.use { it.readBytes() }
    }

    fun thumseeWalkThenDriveGpxBytes(): ByteArray {
        val stream = requireNotNull(TestFixtures::class.java.getResourceAsStream(THUMSEE_WALK_THEN_DRIVE_RESOURCE)) {
            "Missing bundled GPX fixture: $THUMSEE_WALK_THEN_DRIVE_RESOURCE"
        }
        return stream.use { it.readBytes() }
    }

    fun offlineCountriesSeedJson(): String {
        val stream = requireNotNull(TestFixtures::class.java.getResourceAsStream(OFFLINE_COUNTRIES_SEED_RESOURCE)) {
            "Missing bundled offline countries seed fixture: $OFFLINE_COUNTRIES_SEED_RESOURCE"
        }
        return stream.use { it.readBytes().decodeToString() }
    }

    fun halfLoopWalk(
        hikeId: HikeId,
        routeDistanceMeters: Double,
        route: List<RouteCoordinate>,
        endedAtEpochMillis: Long = 1_760_000_000_000L
    ): HikeWalk {
        val half = routeDistanceMeters / 2.0
        val durationSeconds = 45.0 * 60.0
        return HikeWalk(
            id = "seed-half-loop-${hikeId.value}",
            hikeId = hikeId,
            startedAtEpochMillis = endedAtEpochMillis - (durationSeconds * 1000.0).toLong(),
            endedAtEpochMillis = endedAtEpochMillis,
            distanceMeters = half,
            durationSeconds = durationSeconds,
            coveredIntervals = listOf(0.0, half),
            furthestDistanceMeters = half,
            routeDistanceMeters = routeDistanceMeters,
            endReason = TrailWalkEndReason.ENDED,
            routeRevision = TrailWalkRecord.routeRevision(route)
        )
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
