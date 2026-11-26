package app.openhikes.core.model

/**
 * Marked point of interest along a hike (imported GPX waypoint, OpenStreetMap place, or user-pinned place).
 */
data class TrailPoint(
    val id: String,
    val hikeId: HikeId,
    val coordinate: GeoCoordinate,
    val elevationMeters: Double? = null,
    val name: String = "",
    val symbol: String? = null,
    val note: String = "",
    val osm: TrailPlaceOsm? = null,
    val createdAtEpochMillis: Long = 0L
) {
    val symbolKind: TrailPlaceSymbol?
        get() = TrailPlaceSymbol.fromStoredId(symbol)

    /**
     * Places from OpenStreetMap stay OpenStreetMap's to name and categorize; only user-created
     * or non-OSM imported places can be edited.
     */
    val isHikersOwn: Boolean
        get() = osm == null
}

/**
 * Finished walk recorded along a saved hike trail.
 */
data class HikeWalk(
    val id: String,
    val hikeId: HikeId,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val coveredIntervals: List<Double> = emptyList(),
    val furthestDistanceMeters: Double = distanceMeters,
    val routeDistanceMeters: Double = distanceMeters,
    val endReason: TrailWalkEndReason = TrailWalkEndReason.ENDED,
    val routeRevision: String? = null
) {
    val activeSeconds: Double
        get() = durationSeconds

    val coverage: TrailWalkCoverage
        get() = TrailWalkCoverage.fromStored(coveredIntervals, furthestDistanceMeters)

    val coveredFraction: Double
        get() = coverage.fractionComplete(routeDistanceMeters)
            ?: if (routeDistanceMeters > 0.0) (distanceMeters / routeDistanceMeters).coerceIn(0.0, 1.0) else 0.0

    val uncoveredMeters: Double
        get() = (routeDistanceMeters - coverage.coveredMeters).coerceAtLeast(0.0)

    fun isAlong(revision: String): Boolean = routeRevision != null && routeRevision == revision
}

/**
 * Lightweight portable hike metadata used by the library list and search results
 * without decoding full route geometry.
 */
data class HikeSummary(
    val id: HikeId,
    val title: String,
    val customName: String? = null,
    val distanceMeters: Double,
    val durationSeconds: Double? = null,
    val dateEpochMillis: Long,
    val tintHex: String = RouteTint.DEFAULT_HEX,
    val routeWidthDp: Double = DEFAULT_ROUTE_WIDTH_DP,
    val linePattern: RouteLinePattern = RouteLinePattern.Default,
    val routeBorderHex: String = RouteBorder.NONE_HEX,
    val isRecording: Boolean = false,
    val routePointCount: Int = 0,
    val trackDescription: String? = null,
    val author: String? = null,
    val keywords: String? = null,
    val climbMeters: Double? = null,
    val descentMeters: Double? = null
) {
    val displayTitle: String
        get() = customName?.takeIf { it.isNotEmpty() } ?: title

    companion object {
        const val DEFAULT_ROUTE_WIDTH_DP = 3.0
    }
}

/**
 * Full portable hike aggregate including route points and trail waypoints.
 */
data class Hike(
    val id: HikeId,
    val title: String,
    val customName: String? = null,
    val distanceMeters: Double,
    val durationSeconds: Double? = null,
    val dateEpochMillis: Long,
    val tintHex: String = RouteTint.DEFAULT_HEX,
    val routeWidthDp: Double = HikeSummary.DEFAULT_ROUTE_WIDTH_DP,
    val linePattern: RouteLinePattern = RouteLinePattern.Default,
    val routeBorderHex: String = RouteBorder.NONE_HEX,
    val isRecording: Boolean = false,
    val autoFollowEnabled: Boolean = true,
    val trackDescription: String? = null,
    val author: String? = null,
    val keywords: String? = null,
    val route: List<RouteCoordinate> = emptyList(),
    val places: List<TrailPoint> = emptyList(),
    val photos: List<HikePhoto> = emptyList()
) {
    val displayTitle: String
        get() = customName?.takeIf { it.isNotEmpty() } ?: title

    val hasPhotos: Boolean
        get() = photos.isNotEmpty()

    val orderedPhotos: List<HikePhoto>
        get() = OrderedHikePhotos.from(photos).photos

    val anchoredPhotoCount: Int
        get() = photos.count { it.isAnchored }

    val importedPhotoAssetIdentifiers: Set<String>
        get() = OrderedHikePhotos.from(photos).importedAssetIdentifiers

    fun photosOfPlace(placeId: String): List<HikePhoto> = orderedPhotos.filter { it.placeId == placeId }

    fun toSummary(climbMeters: Double? = null, descentMeters: Double? = null): HikeSummary = HikeSummary(
        id = id,
        title = title,
        customName = customName,
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        dateEpochMillis = dateEpochMillis,
        tintHex = tintHex,
        routeWidthDp = routeWidthDp,
        linePattern = linePattern,
        routeBorderHex = routeBorderHex,
        isRecording = isRecording,
        routePointCount = route.size,
        trackDescription = trackDescription,
        author = author,
        keywords = keywords,
        climbMeters = climbMeters,
        descentMeters = descentMeters
    )
}

/**
 * Device-bound state for a hike kept separate from portable hike records and excluded from transfer.
 */
data class HikeLocalState(
    val hikeId: HikeId,
    val listOrder: Int? = null,
    val climbMeters: Double? = null,
    val descentMeters: Double? = null,
    val elevationRouteRevision: String? = null,
    val ownsRecordingDraft: Boolean = false,
    val autoSavedTileKeys: List<String> = emptyList()
)

/**
 * Lightweight route projection fed to `:core:map` for rendering polylines.
 */
data class HikeMapRoute(
    val hikeId: HikeId,
    val route: List<RouteCoordinate>,
    val tintHex: String,
    val routeBorderHex: String,
    val routeWidthDp: Double,
    val linePattern: RouteLinePattern
)
