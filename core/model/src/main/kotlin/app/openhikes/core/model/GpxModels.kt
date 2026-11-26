package app.openhikes.core.model

/**
 * Resource bounds enforced when parsing untrusted GPX files.
 */
data class GpxImportLimits(
    val maxFileSizeBytes: Int = STANDARD_MAX_FILE_SIZE_BYTES,
    val maxPointCount: Int = STANDARD_MAX_POINT_COUNT,
    val maxTrackCount: Int = STANDARD_MAX_TRACK_COUNT,
    val maxPlaces: Int = STANDARD_MAX_PLACES
) {
    companion object {
        private const val BYTES_PER_MIB = 1024 * 1024
        const val STANDARD_MAX_FILE_SIZE_BYTES = 32 * BYTES_PER_MIB
        const val STANDARD_MAX_POINT_COUNT = 500_000
        const val STANDARD_MAX_TRACK_COUNT = 50
        const val STANDARD_MAX_PLACES = 200
    }
}

/**
 * Domain failure reasons when importing a GPX document.
 */
enum class GpxImportFailure {
    MULTIPLE_TRACKS,
    NO_USABLE_POINTS,
    TOO_LARGE,
    TOO_SHORT,
    UNREADABLE
}

/**
 * Parsed `<wpt>` place from a GPX file.
 */
data class GpxWaypoint(
    val coordinate: GeoCoordinate,
    val elevationMeters: Double? = null,
    val name: String = "",
    val symbol: String? = null,
    val note: String = "",
    val osm: TrailPlaceOsm? = null
)

/**
 * Single parsed GPX track or route ready to persist as a [Hike].
 */
data class GpxTrack(
    val name: String?,
    val trackDescription: String?,
    val author: String?,
    val keywords: String?,
    val startTimeEpochMillis: Long?,
    val route: List<RouteCoordinate>,
    val distanceMeters: Double,
    val places: List<GpxWaypoint> = emptyList()
)

/**
 * GPX 1.1 document payload prepared for sharing or saving.
 */
data class ExportedGpx(val fileName: String, val xml: String)

/**
 * Outcome of importing one or more hikes from GPX bytes or URI.
 */
sealed interface GpxImportResult {
    data class Success(val importedHikes: List<HikeSummary>) : GpxImportResult

    data class Failure(val reason: GpxImportFailure) : GpxImportResult
}
