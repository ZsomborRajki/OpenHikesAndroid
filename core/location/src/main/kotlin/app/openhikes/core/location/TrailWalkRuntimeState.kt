package app.openhikes.core.location

import app.openhikes.core.data.HikeRepository
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeWalk
import app.openhikes.core.model.JunctionAnnouncer
import app.openhikes.core.model.LiveUpdateKind
import app.openhikes.core.model.LiveUpdatePayload
import app.openhikes.core.model.LiveUpdateRunState
import app.openhikes.core.model.LocationFix
import app.openhikes.core.model.OffTrailPolicy
import app.openhikes.core.model.OffTrailWatch
import app.openhikes.core.model.PendingWalkStart
import app.openhikes.core.model.RecordingEnergyProfile
import app.openhikes.core.model.RouteProfile
import app.openhikes.core.model.TrailWalkEndReason
import app.openhikes.core.model.TrailWalkPhase
import app.openhikes.core.model.TrailWalkPolicy
import app.openhikes.core.model.TrailWalkRecord
import app.openhikes.core.model.WalkTimeLeft
import app.openhikes.core.model.WalkTimeLeftMark
import kotlin.math.roundToInt

/**
 * Internal mutable state and pure fix-processing support owned under [DefaultTrailWalkController]'s
 * mutex so each fix is evaluated sequentially without overlapping writes or races.
 */
@Suppress("TooManyFunctions")
internal class TrailWalkRuntimeState(
    private val hikeRepository: HikeRepository,
    private val locationFeed: LocationFeed,
    private val notificationController: LiveUpdateNotificationController,
    private val serviceBridge: RecordingServiceBridge,
    private val junctionVoice: JunctionVoice,
    private val isRecordingActive: () -> Boolean
) {
    var activeRecord: TrailWalkRecord? = null
    var walkedHike: Hike? = null
    var walkedProfile: RouteProfile? = null
    var foregroundHike: Hike? = null
    var foregroundProfile: RouteProfile? = null
    var pendingStart: PendingWalkStart? = null
    val endedHikeIds: MutableSet<HikeId> = HashSet()
    var offTrailWatch: OffTrailWatch = OffTrailWatch()
    var junctionAnnouncer: JunctionAnnouncer? = null
    var isJunctionFeedParked: Boolean = false
    var lastPersistedAtMillis: Long? = null
    var lastPersistedRecord: TrailWalkRecord? = null
    var isJunctionWarningsEnabled: Boolean = false
    var hikeRemindersEnabled: Boolean = true
    var distanceUnit: DistanceUnit = DistanceUnit.METRIC
    var ownsLocationUpdates: Boolean = false
    var startNotice: TrailWalkStartNotice? = null
    var offTrailQuestion: OffTrailQuestion? = null
    var lastEndedWalk: HikeWalk? = null
    var lastJunctionCue: app.openhikes.core.model.JunctionCue? = null
    var permissionIssue: TrailWalkPermissionIssue? = null
    var followMarkerHikeId: HikeId? = null
    var followMarkerDistanceMeters: Double? = null
    var lastIsOnRoute: Boolean = true

    fun canAutoStart(hike: Hike): Boolean = activeRecord == null &&
        !isRecordingActive() &&
        !hike.isRecording &&
        hike.autoFollowEnabled &&
        hike.id !in endedHikeIds

    fun canStartByHand(hike: Hike): Boolean = activeRecord == null &&
        !isRecordingActive() &&
        !hike.isRecording

    fun currentSessionState(): TrailWalkSessionState {
        val record = activeRecord
        return TrailWalkSessionState(
            walkedHikeId = record?.let { HikeId(it.hikeId) },
            walkedHikeTitle = walkedHike?.displayTitle.orEmpty(),
            phase = record?.phase,
            startNotice = startNotice,
            offTrailQuestion = offTrailQuestion,
            lastEndedWalk = lastEndedWalk,
            isJunctionWarningsEnabled = isJunctionWarningsEnabled,
            isJunctionWarningsOffered = record != null,
            lastJunctionCue = lastJunctionCue,
            permissionIssue = permissionIssue
        )
    }

    fun currentProgressState(nowEpochMillis: Long): TrailWalkProgressState {
        val record = activeRecord
        val profile = walkedProfile
        val position = record?.lastFollowedDistanceMeters
        val metersLeft = record?.let { metersLeftOf(it) } ?: 0.0
        val secondsLeft = if (record != null && profile != null && position != null) {
            WalkTimeLeft.seconds(profile, position, record.coverage, record.activeSeconds(nowEpochMillis))
        } else {
            null
        }
        val mark = if (secondsLeft != null && record != null && profile != null) {
            val pos = position ?: 0.0
            val midway = WalkTimeLeft.midwayAhead(pos, record.coverage, profile.totalDistanceMeters)
            profile.coordinateAt(midway)?.let { coord ->
                WalkTimeLeftMark(HikeId(record.hikeId), coord, secondsLeft)
            }
        } else {
            null
        }
        return TrailWalkProgressState(
            hikeId = record?.let { HikeId(it.hikeId) },
            coveredFraction = record?.coveredFraction ?: 0.0,
            metersLeft = metersLeft,
            secondsLeft = secondsLeft,
            timeLeftMark = mark,
            lastMatchedDistanceMeters = position,
            isOnRoute = lastIsOnRoute,
            followMarkerHikeId = followMarkerHikeId,
            followMarkerDistanceMeters = followMarkerDistanceMeters
        )
    }

    fun syncLocationFeed() {
        if (isRecordingActive()) {
            ownsLocationUpdates = false
            return
        }
        val hasFinePermission = locationFeed.permissionStatus.value == LocationPermissionStatus.GRANTED_FINE
        val wantsUpdates = activeRecord?.phase == TrailWalkPhase.FOLLOWING ||
            foregroundHike?.let { !it.isRecording && it.autoFollowEnabled } == true
        val needsUpdates = hasFinePermission && wantsUpdates
        val feedStopped = locationFeed.activeProfile.value == null
        if (needsUpdates && (!ownsLocationUpdates || feedStopped)) {
            locationFeed.startUpdates(RecordingEnergyProfile.Precise)
            ownsLocationUpdates = true
        } else if (!needsUpdates && ownsLocationUpdates) {
            locationFeed.stopUpdates()
            ownsLocationUpdates = false
        }
    }

    fun publishLiveUpdate(nowEpochMillis: Long) {
        val record = activeRecord ?: return
        val progress = currentProgressState(nowEpochMillis)
        val runState = if (record.phase == TrailWalkPhase.PAUSED) {
            LiveUpdateRunState.PAUSED
        } else {
            LiveUpdateRunState.FOLLOWING
        }
        val payload = LiveUpdatePayload(
            kind = LiveUpdateKind.FOLLOWING,
            sessionId = "walk-${record.hikeId}",
            title = walkedHike?.displayTitle.orEmpty().ifBlank { DEFAULT_HIKE_TITLE },
            runState = runState,
            distanceMeters = progress.metersLeft,
            timerStartEpochMillis = if (record.phase == TrailWalkPhase.FOLLOWING) {
                nowEpochMillis - (record.activeSeconds(nowEpochMillis) * MILLIS_PER_SECOND).toLong()
            } else {
                null
            },
            isOnRoute = lastIsOnRoute,
            progressPercent = (record.coveredFraction * PERCENT_SCALE).roundToInt().coerceIn(0, MAX_PERCENT),
            remainingSeconds = progress.secondsLeft,
            hikeId = record.hikeId
        )
        notificationController.onFollowingUpdate(payload)
    }

    fun adoptWalk(record: TrailWalkRecord, hike: Hike, profile: RouteProfile?) {
        activeRecord = if (record.routeRevision == null && hike.route.isNotEmpty()) {
            record.stampedAlong(hike.route)
        } else {
            record
        }
        walkedHike = hike
        walkedProfile = profile ?: RouteProfile.from(hike.route)
        endedHikeIds.remove(hike.id)
        pendingStart = null
        lastEndedWalk = null
        offTrailWatch = OffTrailWatch()
        offTrailQuestion = null
        lastJunctionCue = null
        isJunctionFeedParked = false
        lastIsOnRoute = true
    }

    suspend fun finishWalk(reason: TrailWalkEndReason, nowEpochMillis: Long): TrailWalkEnd {
        val closing = activeRecord ?: return TrailWalkEnd.Discarded
        val endedAt = if (reason == TrailWalkEndReason.ABANDONED) closing.lastActivityAtEpochMillis else nowEpochMillis
        val result = hikeRepository.finishWalk(closing, endedAt, reason)
        return if (result.isFailure) {
            lastPersistedAtMillis = null
            TrailWalkEnd.Refused
        } else {
            val saved = result.getOrNull()
            val endedId = HikeId(closing.hikeId)
            clearActiveWalkState()
            if (reason != TrailWalkEndReason.ABANDONED) {
                endedHikeIds.add(endedId)
            }
            if (saved != null && reason != TrailWalkEndReason.ABANDONED) {
                lastEndedWalk = saved
            }
            notificationController.endFollowing()
            if (!isRecordingActive()) {
                serviceBridge.stopService()
            }
            syncLocationFeed()
            if (saved != null) TrailWalkEnd.Kept(saved) else TrailWalkEnd.Discarded
        }
    }

    fun clearActiveWalkState() {
        activeRecord = null
        walkedHike = null
        walkedProfile = null
        lastPersistedAtMillis = null
        lastPersistedRecord = null
        startNotice = null
        offTrailQuestion = null
        offTrailWatch = OffTrailWatch()
        junctionAnnouncer = null
        isJunctionFeedParked = false
        lastJunctionCue = null
        lastIsOnRoute = true
        junctionVoice.stopSpeaking()
    }

    suspend fun persistMilestone(record: TrailWalkRecord, nowEpochMillis: Long): Boolean {
        val result = hikeRepository.saveWalkInProgress(HikeId(record.hikeId), record)
        return if (result.isSuccess) {
            lastPersistedAtMillis = nowEpochMillis
            lastPersistedRecord = record
            true
        } else {
            false
        }
    }

    suspend fun persistIfDue(record: TrailWalkRecord, nowEpochMillis: Long) {
        if (lastPersistedRecord == record) return
        val last = lastPersistedAtMillis
        val due = last == null || (nowEpochMillis - last) >= TrailWalkPolicy.PERSIST_INTERVAL_MILLIS
        if (due) {
            persistMilestone(record, nowEpochMillis)
        }
    }

    fun evaluateOffTrailAndJunctions(
        fix: LocationFix,
        distanceAlongRoute: Double,
        offRouteMeters: Double,
        nowEpochMillis: Long
    ) {
        val record = activeRecord ?: return
        if (record.phase != TrailWalkPhase.FOLLOWING) return
        val onTrail = offRouteMeters <= RouteProfile.FOLLOW_MATCH_THRESHOLD_METERS
        lastIsOnRoute = onTrail
        if (hikeRemindersEnabled) {
            val (nextWatch, fired) = offTrailWatch.observe(offRouteMeters, nowEpochMillis)
            offTrailWatch = nextWatch
            if (fired) {
                offTrailQuestion = OffTrailQuestion(
                    hikeId = HikeId(record.hikeId),
                    trailTitle = walkedHike?.displayTitle.orEmpty()
                )
            } else if (!nextWatch.isOffTrail && offTrailQuestion != null) {
                offTrailQuestion = null
            }
        }
        evaluateJunctionCue(distanceAlongRoute, offRouteMeters, onTrail, fix)
    }

    private fun evaluateJunctionCue(
        distanceAlongRoute: Double,
        offRouteMeters: Double,
        onTrail: Boolean,
        @Suppress("UNUSED_PARAMETER") fix: LocationFix
    ) {
        if (!isJunctionWarningsEnabled || isJunctionFeedParked) return
        if (offRouteMeters > OffTrailPolicy.PARK_JUNCTION_FEED_METERS) {
            isJunctionFeedParked = true
            junctionAnnouncer?.forgetPosition()
        } else {
            junctionAnnouncer?.advance(distanceAlongRoute, onTrail)?.let { cue ->
                lastJunctionCue = cue
                junctionVoice.speak(JunctionCueFormatter.formatSpokenCue(cue, distanceUnit))
            }
        }
    }

    fun verifyFineLocationPermission(): Boolean {
        locationFeed.refreshPermissionStatus()
        return when (locationFeed.permissionStatus.value) {
            LocationPermissionStatus.GRANTED_FINE -> {
                permissionIssue = null
                true
            }
            LocationPermissionStatus.GRANTED_COARSE -> {
                permissionIssue = TrailWalkPermissionIssue.PRECISE_LOCATION_REQUIRED
                false
            }
            LocationPermissionStatus.DENIED -> {
                permissionIssue = TrailWalkPermissionIssue.LOCATION_DENIED
                false
            }
        }
    }

    fun recordLocationPermissionDenied(hasCoarse: Boolean) {
        locationFeed.refreshPermissionStatus()
        val status = locationFeed.permissionStatus.value
        permissionIssue = if (hasCoarse || status == LocationPermissionStatus.GRANTED_COARSE) {
            TrailWalkPermissionIssue.PRECISE_LOCATION_REQUIRED
        } else {
            TrailWalkPermissionIssue.LOCATION_DENIED
        }
    }

    fun tryEnsureServiceStarted(): Boolean = try {
        serviceBridge.ensureStarted()
        true
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalStateException) {
        false
    }

    suspend fun handleOffRouteForActiveWalk(current: TrailWalkRecord, nowEpochMillis: Long) {
        if (current.coverage.lastMatchedDistance != null) {
            val broken = current.copy(coverage = current.coverage.withBrokenContinuity())
            activeRecord = broken
            persistIfDue(broken, nowEpochMillis)
        }
        publishLiveUpdate(nowEpochMillis)
    }

    suspend fun recordMatchedActiveFix(
        current: TrailWalkRecord,
        distanceAlongRoute: Double,
        accuracyMeters: Double,
        matchedAt: Long,
        nowEpochMillis: Long
    ): Boolean {
        if (matchedAt < current.lastActivityAtEpochMillis) return false
        return if (current.phase == TrailWalkPhase.PAUSED) {
            val updated = current.copy(lastMatchedAtEpochMillis = matchedAt)
            activeRecord = updated
            persistIfDue(updated, nowEpochMillis)
            false
        } else {
            val nextCoverage = current.coverage.withRecordedMatch(distanceAlongRoute, accuracyMeters)
            val updated = current.copy(
                coverage = nextCoverage,
                lastMatchedAtEpochMillis = matchedAt,
                lastFollowedDistanceMeters = distanceAlongRoute
            )
            activeRecord = updated
            val completed = updated.reachesEnd(distanceAlongRoute) &&
                finishWalk(TrailWalkEndReason.REACHED_END, nowEpochMillis) !is TrailWalkEnd.Refused
            if (!completed) {
                publishLiveUpdate(nowEpochMillis)
                persistIfDue(updated, nowEpochMillis)
            }
            completed
        }
    }

    fun advancePendingAutoStart(
        hike: Hike,
        profile: RouteProfile,
        distanceMeters: Double,
        accuracyMeters: Double,
        matchedAt: Long,
        nowEpochMillis: Long
    ): TrailWalkRecord? {
        if (!canAutoStart(hike) || profile.totalDistanceMeters <= 0.0) {
            if (pendingStart?.hikeId == hike.id.value) pendingStart = null
            return null
        }
        val revision = TrailWalkRecord.routeRevision(hike.route)
        val existing = pendingStart
        val canExtend = existing != null &&
            existing.hikeId == hike.id.value &&
            existing.record.routeRevision == revision &&
            !existing.isExpired(nowEpochMillis)
        val updated = if (canExtend) {
            existing.withRecordedMatch(distanceMeters, matchedAt)
        } else {
            PendingWalkStart.start(
                hikeId = hike.id.value,
                routeDistanceMeters = profile.totalDistanceMeters,
                endsApartMeters = PendingWalkStart.endsApartMeters(profile),
                routeRevision = revision,
                distanceMeters = distanceMeters,
                atEpochMillis = matchedAt
            )
        }
        return if (!updated.isConfirmed) {
            pendingStart = updated
            null
        } else {
            pendingStart = null
            updated.confirmedRecord.let { base ->
                base.copy(coverage = base.coverage.withRecordedMatch(distanceMeters, accuracyMeters))
            }
        }
    }

    companion object {
        private const val DEFAULT_HIKE_TITLE = "Hike"
        private const val MILLIS_PER_SECOND = 1000.0
        private const val PERCENT_SCALE = 100.0
        private const val MAX_PERCENT = 100

        fun metersLeftOf(record: TrailWalkRecord): Double {
            val position = record.lastFollowedDistanceMeters ?: return record.routeDistanceMeters
            return WalkTimeLeft.metersAhead(position, record.coverage, record.routeDistanceMeters)
        }
    }
}
