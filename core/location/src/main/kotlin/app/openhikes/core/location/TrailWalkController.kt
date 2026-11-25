package app.openhikes.core.location

import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeWalk
import app.openhikes.core.model.JunctionCue
import app.openhikes.core.model.RouteProfile
import app.openhikes.core.model.TrailWalkPhase
import app.openhikes.core.model.WalkTimeLeftMark
import kotlinx.coroutines.flow.StateFlow

/**
 * Announcement shown over the map when an automatic walk begins from movement along a trail.
 */
data class TrailWalkStartNotice(val hikeId: HikeId, val title: String)

/**
 * Prompt raised once per committed off-trail departure while actively following a hike.
 */
data class OffTrailQuestion(val hikeId: HikeId, val trailTitle: String, val handOffFailed: Boolean = false)

/**
 * Outcome of ending an active trail walk.
 */
sealed interface TrailWalkEnd {
    data class Kept(val walk: HikeWalk) : TrailWalkEnd

    data object Discarded : TrailWalkEnd

    data object Refused : TrailWalkEnd
}

/**
 * Location permission condition preventing a trail walk from starting or resuming.
 */
enum class TrailWalkPermissionIssue {
    LOCATION_DENIED,
    PRECISE_LOCATION_REQUIRED
}

/**
 * Coarse UI/lifecycle state for trail following, separated from per-fix [TrailWalkProgressState]
 * so fix-rate progress updates never recompose screen-wide chrome or controls.
 */
data class TrailWalkSessionState(
    val walkedHikeId: HikeId? = null,
    val walkedHikeTitle: String = "",
    val phase: TrailWalkPhase? = null,
    val startNotice: TrailWalkStartNotice? = null,
    val offTrailQuestion: OffTrailQuestion? = null,
    val lastEndedWalk: HikeWalk? = null,
    val isJunctionWarningsEnabled: Boolean = false,
    val isJunctionWarningsOffered: Boolean = false,
    val lastJunctionCue: JunctionCue? = null,
    val permissionIssue: TrailWalkPermissionIssue? = null
) {
    val isWalkActive: Boolean
        get() = walkedHikeId != null && phase != null

    val isFollowing: Boolean
        get() = walkedHikeId != null && phase == TrailWalkPhase.FOLLOWING

    val isPaused: Boolean
        get() = walkedHikeId != null && phase == TrailWalkPhase.PAUSED
}

/**
 * Fine per-fix trail-following progress read only by narrow leaf composables and map overlays.
 */
data class TrailWalkProgressState(
    val hikeId: HikeId? = null,
    val coveredFraction: Double = 0.0,
    val metersLeft: Double = 0.0,
    val secondsLeft: Double? = null,
    val timeLeftMark: WalkTimeLeftMark? = null,
    val lastMatchedDistanceMeters: Double? = null,
    val isOnRoute: Boolean = true,
    val followMarkerHikeId: HikeId? = null,
    val followMarkerDistanceMeters: Double? = null
)

/**
 * App-scoped trail-following engine shared between `:feature:hikes`, `:feature:walks`, `:app`,
 * and [RecordingForegroundService].
 */
@Suppress("TooManyFunctions")
interface TrailWalkController {
    val sessionState: StateFlow<TrailWalkSessionState>
    val progressState: StateFlow<TrailWalkProgressState>

    fun activeSeconds(): Double

    suspend fun restoreAtLaunch()

    fun attachForegroundHike(hike: Hike, profile: RouteProfile)

    fun detachForegroundHike(hikeId: HikeId)

    suspend fun startByHand(hikeId: HikeId): Boolean

    suspend fun pause(): Boolean

    suspend fun resume(): Boolean

    suspend fun endWalk(): TrailWalkEnd

    fun onAutoFollowChanged(hikeId: HikeId, enabled: Boolean)

    fun onHikeDeleted(hikeId: HikeId)

    fun dismissStartNotice()

    fun keepFollowingAfterOffTrail()

    fun consumeLastEndedWalk()

    fun onLocationPermissionDenied(hasCoarse: Boolean)

    fun dismissPermissionIssue()

    suspend fun toggleJunctionWarnings()

    fun onAppForegrounded()
}
