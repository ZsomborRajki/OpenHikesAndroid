package app.openhikes.core.model

/**
 * Live matched trail position supplied while the hiker is walking or recording.
 */
data class LiveTrailAnchor(val distanceAlongRouteMeters: Double, val coordinate: GeoCoordinate)

/**
 * Resolves where a newly captured or added photo/place should be pinned on a trail.
 */
object PhotoTrailAnchor {
    const val RECENT_RECORDING_WINDOW_MILLIS = 30L * 60L * 1000L

    /**
     * Resolves a photo anchor on a saved hike detail screen:
     * 1. [live] wins whenever its along-route distance is finite and `>= 0` (including `0.0` at
     *    trailhead), returning [LiveTrailAnchor.coordinate] directly rather than re-projecting
     *    through distance lookup (which can jump across a zero-distance pause boundary).
     * 2. Otherwise, an explicitly scrubbed distance (`> 0`) resolves on [profile].
     * 3. Otherwise (untouched `0` placeholder or missing profile), returns `null` (unanchored).
     */
    fun coordinate(profile: RouteProfile?, live: LiveTrailAnchor?, scrubbedMeters: Double?): GeoCoordinate? = when {
        live != null && live.distanceAlongRouteMeters.isFinite() && live.distanceAlongRouteMeters >= 0.0 ->
            live.coordinate
        scrubbedMeters != null && scrubbedMeters.isFinite() && scrubbedMeters > 0.0 ->
            profile?.coordinateAt(scrubbedMeters)
        else -> null
    }

    /**
     * Resolves an initial coordinate for adding a place on a saved hike detail screen. Unlike
     * [coordinate], an untouched `0` placeholder falls back to the trailhead because a place always
     * needs a coordinate.
     */
    fun placeCoordinate(profile: RouteProfile?, live: LiveTrailAnchor?, scrubbedMeters: Double?): GeoCoordinate? =
        coordinate(profile = profile, live = live, scrubbedMeters = scrubbedMeters)
            ?: profile?.coordinateAt((scrubbedMeters ?: 0.0).takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0)

    /**
     * Resolves where a recording photo was taken at shutter time, even when the camera review UI
     * stayed open while the hiker kept walking.
     */
    fun recordingCoordinate(
        shutterEpochMillis: Long?,
        recent: List<RecordingPoint>,
        latest: RecordingPoint?
    ): GeoCoordinate? {
        val newest = recent.lastOrNull()
        return when {
            shutterEpochMillis == null || newest == null || newest.timestampEpochMillis <= shutterEpochMillis ->
                latest?.coordinate
            shutterEpochMillis < newest.timestampEpochMillis - RECENT_RECORDING_WINDOW_MILLIS ->
                null
            else -> {
                val timeline = HikePhotoTimeline.from(recent.map { it.toRouteCoordinate() })
                timeline?.positionAt(shutterEpochMillis)?.coordinate
            }
        }
    }
}
