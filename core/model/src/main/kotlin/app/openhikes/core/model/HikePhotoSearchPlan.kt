package app.openhikes.core.model

/**
 * Lightweight descriptor of a photo discovered in the device's media library.
 */
data class PhotoLibraryAsset(
    val localIdentifier: String,
    val createdAtEpochMillis: Long,
    val coordinate: GeoCoordinate? = null
)

/**
 * Candidate photo from the device's library matched to a spot on a hike's trail.
 */
data class LibraryPhotoMatch(
    val asset: PhotoLibraryAsset,
    val coordinate: GeoCoordinate,
    val evidence: PhotoMatchEvidence,
    val secondsFromFix: Double
) {
    val id: String
        get() = asset.localIdentifier
}

/**
 * EXIF timestamp and coordinate extracted off-main from a user-picked photo's raw bytes.
 */
data class PickedPhotoMetadata(val capturedAtEpochMillis: Long? = null, val coordinate: GeoCoordinate? = null)

/**
 * Resolved placement for a photo chosen from the system Photo Picker.
 */
data class PickedPhotoResolution(
    val coordinate: GeoCoordinate?,
    val capturedAtEpochMillis: Long,
    val matchEvidence: PhotoMatchEvidence?
)

/**
 * Matches device library photos to either a recorded route's timeline or a finished walk along an
 * untimestamped route.
 */
object LibraryPhotoMatcher {
    const val SEPARATION_TOLERANCE_METERS = 150.0
    const val MAXIMUM_OFF_ROUTE_METERS = 100.0

    fun matchRecorded(
        asset: PhotoLibraryAsset,
        timeline: HikePhotoTimeline,
        legs: RecordedRouteLegs
    ): LibraryPhotoMatch? {
        if (asset.createdAtEpochMillis !in timeline.searchWindow) return null
        val position = timeline.positionAt(asset.createdAtEpochMillis)
        val camera = asset.coordinate
        return when {
            camera == null -> position?.let {
                LibraryPhotoMatch(
                    asset = asset,
                    coordinate = it.coordinate,
                    evidence = PhotoMatchEvidence.TIME,
                    secondsFromFix = it.secondsFromFix
                )
            }
            position != null &&
                RouteGeometry.distanceMeters(camera, position.coordinate) <= SEPARATION_TOLERANCE_METERS ->
                LibraryPhotoMatch(
                    asset = asset,
                    coordinate = position.coordinate,
                    evidence = PhotoMatchEvidence.TIME_AND_PLACE,
                    secondsFromFix = position.secondsFromFix
                )
            else -> legs.nearestPoint(camera)
                ?.takeIf { it.offRouteMeters <= MAXIMUM_OFF_ROUTE_METERS }
                ?.let { nearest ->
                    LibraryPhotoMatch(
                        asset = asset,
                        coordinate = nearest.coordinate,
                        evidence = PhotoMatchEvidence.PLACE,
                        secondsFromFix = 0.0
                    )
                }
        }
    }

    fun matchWalk(asset: PhotoLibraryAsset, walk: HikeWalkPhotoTimeline, profile: RouteProfile): LibraryPhotoMatch? {
        if (asset.createdAtEpochMillis !in walk.searchWindow) return null
        val camera = asset.coordinate
        return if (camera != null) {
            profile.nearestPoint(coordinate = camera, preferDistance = walk::covers)
                ?.takeIf { it.offRouteMeters <= MAXIMUM_OFF_ROUTE_METERS && walk.covers(it.distanceAlongRouteMeters) }
                ?.let { nearest ->
                    LibraryPhotoMatch(
                        asset = asset,
                        coordinate = nearest.projectedCoordinate,
                        evidence = PhotoMatchEvidence.PLACE,
                        secondsFromFix = 0.0
                    )
                }
        } else {
            val alongMeters = walk.distanceAlongRoute(asset.createdAtEpochMillis)
            val coordinate = alongMeters?.let(profile::coordinateAt)
            coordinate?.let {
                LibraryPhotoMatch(
                    asset = asset,
                    coordinate = it,
                    evidence = PhotoMatchEvidence.WALK,
                    secondsFromFix = 0.0
                )
            }
        }
    }
}

/**
 * Search plan over a hike's recorded timeline and matching finished walks, merging overlapping
 * search windows while keeping disjoint windows separate so old hikes walked recently never scan
 * years of unrelated photos.
 */
class HikePhotoSearchPlan private constructor(
    val timeline: HikePhotoTimeline?,
    val legs: RecordedRouteLegs,
    val walks: List<HikeWalkPhotoTimeline>,
    val profile: RouteProfile?,
    val searchWindows: List<LongRange>,
    val fingerprint: Long
) {
    val isEmpty: Boolean
        get() = searchWindows.isEmpty()

    fun matches(assets: List<PhotoLibraryAsset>, alreadyImported: Set<String>): List<LibraryPhotoMatch> {
        if (isEmpty || assets.isEmpty()) return emptyList()
        val matched = ArrayList<LibraryPhotoMatch>()
        for (asset in assets) {
            if (asset.localIdentifier in alreadyImported) continue
            matchSingle(asset)?.let(matched::add)
        }
        matched.sortWith(
            compareBy<LibraryPhotoMatch> { it.asset.createdAtEpochMillis }
                .thenBy { it.asset.localIdentifier }
        )
        return matched
    }

    private fun matchSingle(asset: PhotoLibraryAsset): LibraryPhotoMatch? {
        // Absolute precedence: if a photo falls inside the recorded route's window, only the
        // recorded timeline and legs answer (including refusals — a walk never overrules it).
        val activeProfile = profile
        return when {
            timeline != null && asset.createdAtEpochMillis in timeline.searchWindow ->
                LibraryPhotoMatcher.matchRecorded(asset, timeline, legs)
            activeProfile != null -> walks.firstOrNull { asset.createdAtEpochMillis in it.searchWindow }
                ?.let { walk -> LibraryPhotoMatcher.matchWalk(asset, walk, activeProfile) }
            else -> null
        }
    }

    companion object {
        private const val FNV_OFFSET_BASIS = -3750763034362895579L
        private const val FNV_PRIME = 1099511628211L
        private const val BYTE_MASK = 0xFFL
        private const val ABSENT_SENTINEL = Long.MIN_VALUE

        fun canMatchPhotos(route: List<RouteCoordinate>, walks: List<HikeWalk>): Boolean = when {
            route.any { it.timestampEpochMillis != null } -> true
            route.isEmpty() || walks.isEmpty() -> false
            else -> {
                val revision = TrailWalkRecord.routeRevision(route)
                walks.any { HikeWalkPhotoTimeline.from(it, revision) != null }
            }
        }

        fun build(
            route: List<RouteCoordinate>,
            walks: List<HikeWalk>,
            profileRoute: Boolean = false
        ): HikePhotoSearchPlan {
            val revision = TrailWalkRecord.routeRevision(route)
            val timeline = HikePhotoTimeline.from(route)
            val legs = RecordedRouteLegs.from(route)
            val walkTimelines = HikeWalkPhotoTimeline.allMatching(walks, revision)
            val profile = if (profileRoute || walkTimelines.isNotEmpty()) RouteProfile.from(route) else null
            val windows = mergeWindows(
                buildList {
                    timeline?.searchWindow?.let(::add)
                    walkTimelines.forEach { add(it.searchWindow) }
                }
            )
            return HikePhotoSearchPlan(
                timeline = timeline,
                legs = legs,
                walks = walkTimelines,
                profile = profile,
                searchWindows = windows,
                fingerprint = fingerprint(route, walks)
            )
        }

        /**
         * Stable 64-bit fingerprint over route geometry, timestamps, pause boundaries, provenance,
         * and walks — deliberately ignoring elevation and motion so elevation-fill updates never
         * invalidate an active photo discovery review.
         */
        fun fingerprint(route: List<RouteCoordinate>, walks: List<HikeWalk>): Long {
            var hash = FNV_OFFSET_BASIS
            hash = mixLong(hash, route.size.toLong())
            for (point in route) {
                hash = mixLong(hash, point.latitude.toRawBits())
                hash = mixLong(hash, point.longitude.toRawBits())
                hash = mixLong(hash, point.timestampEpochMillis ?: ABSENT_SENTINEL)
                hash = mixLong(hash, point.boundary?.ordinal?.toLong() ?: -1L)
                hash = mixLong(hash, point.provenance?.ordinal?.toLong() ?: -1L)
            }
            val sortedWalks = walks.sortedWith(
                compareBy<HikeWalk> { it.startedAtEpochMillis }.thenBy { it.id }
            )
            hash = mixLong(hash, sortedWalks.size.toLong())
            for (walk in sortedWalks) {
                hash = mixString(hash, walk.id)
                hash = mixLong(hash, walk.startedAtEpochMillis)
                hash = mixLong(hash, walk.endedAtEpochMillis)
                hash = mixString(hash, walk.routeRevision.orEmpty())
                hash = mixLong(hash, walk.coveredIntervals.size.toLong())
                for (interval in walk.coveredIntervals) {
                    hash = mixLong(hash, interval.toRawBits())
                }
            }
            return hash
        }

        private fun mergeWindows(windows: List<LongRange>): List<LongRange> {
            if (windows.size <= 1) return windows
            val sorted = windows.sortedBy { it.first }
            val merged = ArrayList<LongRange>(sorted.size)
            for (window in sorted) {
                val last = merged.lastOrNull()
                if (last != null && window.first <= last.last) {
                    merged[merged.lastIndex] = last.first..maxOf(last.last, window.last)
                } else {
                    merged.add(window)
                }
            }
            return merged
        }

        private fun mixLong(seed: Long, value: Long): Long {
            var hash = seed
            for (shift in 0 until Long.SIZE_BITS step Byte.SIZE_BITS) {
                hash = (hash xor ((value ushr shift) and BYTE_MASK)) * FNV_PRIME
            }
            return hash
        }

        private fun mixString(seed: Long, value: String): Long {
            var hash = mixLong(seed, value.length.toLong())
            for (ch in value) {
                hash = (hash xor ch.code.toLong()) * FNV_PRIME
            }
            return hash
        }
    }
}

/**
 * Resolves the placement and capture timestamp for a photo picked via the system Photo Picker.
 */
object PickedPhotoPlacement {
    fun resolve(
        metadata: PickedPhotoMetadata,
        plan: HikePhotoSearchPlan?,
        fallbackCoordinate: GeoCoordinate?,
        nowEpochMillis: Long
    ): PickedPhotoResolution {
        val capturedAt = metadata.capturedAtEpochMillis ?: nowEpochMillis
        val matched = if (plan != null) {
            matchViaPlan(metadata, plan, capturedAt) ?: snapToRoute(metadata, plan, capturedAt)
        } else {
            null
        }
        return matched ?: PickedPhotoResolution(
            coordinate = fallbackCoordinate,
            capturedAtEpochMillis = capturedAt,
            matchEvidence = null
        )
    }

    private fun matchViaPlan(
        metadata: PickedPhotoMetadata,
        plan: HikePhotoSearchPlan,
        capturedAt: Long
    ): PickedPhotoResolution? {
        val exifCapturedAt = metadata.capturedAtEpochMillis ?: return null
        val asset = PhotoLibraryAsset(
            localIdentifier = "picked",
            createdAtEpochMillis = exifCapturedAt,
            coordinate = metadata.coordinate
        )
        return plan.matches(assets = listOf(asset), alreadyImported = emptySet()).firstOrNull()?.let { matched ->
            PickedPhotoResolution(
                coordinate = matched.coordinate,
                capturedAtEpochMillis = capturedAt,
                matchEvidence = matched.evidence
            )
        }
    }

    private fun snapToRoute(
        metadata: PickedPhotoMetadata,
        plan: HikePhotoSearchPlan,
        capturedAt: Long
    ): PickedPhotoResolution? {
        val camera = metadata.coordinate
        val profile = plan.profile
        return if (camera != null && profile != null) {
            profile.nearestPoint(camera)
                ?.takeIf { it.offRouteMeters <= LibraryPhotoMatcher.MAXIMUM_OFF_ROUTE_METERS }
                ?.let { nearest ->
                    PickedPhotoResolution(
                        coordinate = nearest.projectedCoordinate,
                        capturedAtEpochMillis = capturedAt,
                        matchEvidence = PhotoMatchEvidence.PLACE
                    )
                }
        } else {
            null
        }
    }
}
