package app.openhikes.core.location

import app.openhikes.core.common.ApplicationScope
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.JunctionAnnouncer
import app.openhikes.core.model.LocationFix
import app.openhikes.core.model.LocationFixPolicy
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteProfile
import app.openhikes.core.model.TrailWalkEndReason
import app.openhikes.core.model.TrailWalkPhase
import app.openhikes.core.model.TrailWalkRecord
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Production [TrailWalkController] owning walk start/auto-start, coverage union accrual,
 * pause/resume/end persistence, elevation chart follow separation, off-trail prompts,
 * spoken junction warnings, and Android 16.0-safe Live Update notifications.
 */
@Singleton
@Suppress("LongParameterList", "TooManyFunctions")
internal class DefaultTrailWalkController @Inject constructor(
    private val clock: OpenHikesClock,
    private val hikeRepository: HikeRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val locationFeed: LocationFeed,
    private val recordingController: RecordingController,
    private val notificationController: LiveUpdateNotificationController,
    private val serviceBridge: RecordingServiceBridge,
    private val junctionProvider: TrailJunctionProvider,
    private val junctionVoice: JunctionVoice,
    @ApplicationScope private val applicationScope: CoroutineScope
) : TrailWalkController {
    private val mutex = Mutex()
    private val engineContext = applicationScope.coroutineContext.minusKey(Job)
    private val runtime = TrailWalkRuntimeState(
        hikeRepository = hikeRepository,
        locationFeed = locationFeed,
        notificationController = notificationController,
        serviceBridge = serviceBridge,
        junctionVoice = junctionVoice,
        isRecordingActive = { recordingController.state.value.isActive }
    )
    private val sessionStateFlow = MutableStateFlow(TrailWalkSessionState())
    private val progressStateFlow = MutableStateFlow(TrailWalkProgressState())
    private var junctionPrepareJob: Job? = null

    override val sessionState: StateFlow<TrailWalkSessionState> = sessionStateFlow.asStateFlow()
    override val progressState: StateFlow<TrailWalkProgressState> = progressStateFlow.asStateFlow()

    init {
        applicationScope.launch {
            locationFeed.fixes.collect { fix ->
                mutex.withLock { handleIncomingFix(fix) }
            }
        }
        applicationScope.launch {
            userSettingsRepository.userSettings.collect { settings ->
                mutex.withLock {
                    runtime.distanceUnit = settings.distanceUnit
                    runtime.hikeRemindersEnabled = settings.hikeRemindersEnabled
                    val changed = runtime.isJunctionWarningsEnabled != settings.junctionWarningsEnabled
                    runtime.isJunctionWarningsEnabled = settings.junctionWarningsEnabled
                    if (changed) {
                        syncJunctionGuideLocked()
                        publishFlowsLocked(clock.now().toEpochMilli())
                    }
                }
            }
        }
        applicationScope.launch {
            recordingController.completedSave.collect { saved ->
                if (saved != null) {
                    mutex.withLock { runtime.endedHikeIds.add(saved.hikeId) }
                }
            }
        }
        applicationScope.launch {
            recordingController.state
                .map { it.isActive }
                .distinctUntilChanged()
                .collect {
                    mutex.withLock {
                        locationFeed.refreshPermissionStatus()
                        runtime.syncLocationFeed()
                        publishFlowsLocked(clock.now().toEpochMilli())
                    }
                }
        }
    }

    override fun activeSeconds(): Double = runtime.activeRecord?.activeSeconds(clock.now().toEpochMilli()) ?: 0.0

    override suspend fun restoreAtLaunch() = withContext(engineContext) {
        mutex.withLock {
            val now = clock.now().toEpochMilli()
            val openWalks = hikeRepository.getOpenWalksInProgress().getOrNull().orEmpty()
            val newest = openWalks.maxByOrNull { it.lastActivityAtEpochMillis } ?: return@withLock
            val alreadyFinished = hikeRepository.observeWalk(newest.durableWalkId).first()
            if (alreadyFinished != null) {
                hikeRepository.saveWalkInProgress(HikeId(newest.hikeId), null)
                return@withLock
            }
            val hike = hikeRepository.observeHike(HikeId(newest.hikeId)).first()
            if (hike == null) {
                hikeRepository.saveWalkInProgress(HikeId(newest.hikeId), null)
                return@withLock
            }
            val profile = RouteProfile.from(hike.route)
            when {
                newest.isStaleAtLaunch(now) -> finishRestoredWalkLocked(
                    record = newest,
                    hike = hike,
                    profile = profile,
                    reason = TrailWalkEndReason.ABANDONED,
                    now = now
                )
                !newest.isAlong(hike.route) -> finishRestoredWalkLocked(
                    record = newest,
                    hike = hike,
                    profile = profile,
                    reason = TrailWalkEndReason.ROUTE_CHANGED,
                    now = now
                )
                else -> restoreLiveWalkLocked(newest, hike, profile, now)
            }
            publishFlowsLocked(now)
        }
    }

    private suspend fun finishRestoredWalkLocked(
        record: TrailWalkRecord,
        hike: Hike,
        profile: RouteProfile,
        reason: TrailWalkEndReason,
        now: Long
    ) {
        runtime.adoptWalk(record, hike, profile)
        runtime.finishWalk(reason, now)
    }

    private suspend fun restoreLiveWalkLocked(record: TrailWalkRecord, hike: Hike, profile: RouteProfile, now: Long) {
        when {
            record.phase != TrailWalkPhase.FOLLOWING -> {
                runtime.adoptWalk(record, hike, profile)
                publishFlowsLocked(now)
                runtime.publishLiveUpdate(now)
                runtime.syncLocationFeed()
            }
            !runtime.verifyFineLocationPermission() -> pauseRestoredWalkLocked(record, hike, profile, now)
            else -> {
                runtime.adoptWalk(record, hike, profile)
                publishFlowsLocked(now)
                runtime.publishLiveUpdate(now)
                if (runtime.tryEnsureServiceStarted()) {
                    runtime.syncLocationFeed()
                    syncJunctionGuideLocked()
                } else {
                    pauseRestoredWalkLocked(record, hike, profile, now)
                }
            }
        }
    }

    private suspend fun pauseRestoredWalkLocked(record: TrailWalkRecord, hike: Hike, profile: RouteProfile, now: Long) {
        val paused = record.pausedAt(now)
        runtime.adoptWalk(paused, hike, profile)
        runtime.persistMilestone(paused, now)
        publishFlowsLocked(now)
        runtime.publishLiveUpdate(now)
        runtime.syncLocationFeed()
    }

    override fun attachForegroundHike(hike: Hike, profile: RouteProfile) {
        applicationScope.launch {
            mutex.withLock {
                locationFeed.refreshPermissionStatus()
                val now = clock.now().toEpochMilli()
                runtime.foregroundHike = hike
                runtime.foregroundProfile = profile
                val active = runtime.activeRecord
                if (active != null && active.hikeId == hike.id.value) {
                    if (!active.isAlong(hike.route)) {
                        runtime.finishWalk(TrailWalkEndReason.ROUTE_CHANGED, now)
                        cancelJunctionGuideLocked()
                    } else {
                        runtime.walkedHike = hike
                        runtime.walkedProfile = profile
                    }
                }
                runtime.syncLocationFeed()
                publishFlowsLocked(now)
            }
        }
    }

    override fun detachForegroundHike(hikeId: HikeId) {
        applicationScope.launch {
            mutex.withLock {
                if (runtime.foregroundHike?.id == hikeId) {
                    runtime.foregroundHike = null
                    runtime.foregroundProfile = null
                }
                if (runtime.followMarkerHikeId == hikeId) {
                    runtime.followMarkerHikeId = null
                    runtime.followMarkerDistanceMeters = null
                }
                runtime.syncLocationFeed()
                publishFlowsLocked(clock.now().toEpochMilli())
            }
        }
    }

    override suspend fun startByHand(hikeId: HikeId): Boolean = withContext(engineContext) {
        mutex.withLock {
            val now = clock.now().toEpochMilli()
            endIfAbandonedLocked(now)
            if (!runtime.verifyFineLocationPermission()) {
                publishFlowsLocked(now)
                return@withLock false
            }
            val hike = resolveHikeLocked(hikeId)
            val profile = hike?.let { resolveProfileLocked(it) }
            val ready = hike != null && profile != null && runtime.canStartByHand(hike)
            if (!ready || profile.totalDistanceMeters <= 0.0) {
                return@withLock false
            }
            val started = TrailWalkRecord(
                hikeId = hike.id.value,
                startedAtEpochMillis = now,
                routeDistanceMeters = profile.totalDistanceMeters,
                routeRevision = TrailWalkRecord.routeRevision(hike.route),
                walkId = TrailWalkRecord.newWalkId()
            )
            activateFollowingWalkLocked(started, hike, profile, startNotice = null, now = now)
        }
    }

    override suspend fun pause(): Boolean = withContext(engineContext) {
        mutex.withLock {
            val now = clock.now().toEpochMilli()
            val current = runtime.activeRecord
            if (current == null || current.phase != TrailWalkPhase.FOLLOWING) return@withLock false
            val paused = current.pausedAt(now)
            if (!runtime.persistMilestone(paused, now)) return@withLock false
            runtime.activeRecord = paused
            runtime.offTrailQuestion = null
            runtime.publishLiveUpdate(now)
            runtime.syncLocationFeed()
            syncJunctionGuideLocked()
            publishFlowsLocked(now)
            true
        }
    }

    override suspend fun resume(): Boolean = withContext(engineContext) {
        mutex.withLock {
            val now = clock.now().toEpochMilli()
            val current = runtime.activeRecord
            val canResume = current != null && current.phase == TrailWalkPhase.PAUSED
            when {
                !canResume -> false
                !runtime.verifyFineLocationPermission() -> {
                    publishFlowsLocked(now)
                    false
                }
                else -> executeResumeLocked(current, now)
            }
        }
    }

    private suspend fun executeResumeLocked(current: TrailWalkRecord, now: Long): Boolean {
        val resumed = current.resumedAt(now)
        if (!runtime.persistMilestone(resumed, now)) return false
        runtime.activeRecord = resumed
        publishFlowsLocked(now)
        runtime.publishLiveUpdate(now)
        return if (runtime.tryEnsureServiceStarted()) {
            runtime.syncLocationFeed()
            syncJunctionGuideLocked()
            true
        } else {
            runtime.activeRecord = current
            runtime.persistMilestone(current, now)
            runtime.publishLiveUpdate(now)
            runtime.syncLocationFeed()
            publishFlowsLocked(now)
            false
        }
    }

    override suspend fun endWalk(): TrailWalkEnd = withContext(engineContext) {
        mutex.withLock {
            val now = clock.now().toEpochMilli()
            val end = runtime.finishWalk(TrailWalkEndReason.ENDED, now)
            if (end !is TrailWalkEnd.Refused) {
                cancelJunctionGuideLocked()
            }
            publishFlowsLocked(now)
            end
        }
    }

    override fun onAutoFollowChanged(hikeId: HikeId, enabled: Boolean) {
        applicationScope.launch {
            mutex.withLock {
                if (enabled) {
                    runtime.endedHikeIds.remove(hikeId)
                }
                if (runtime.foregroundHike?.id == hikeId) {
                    runtime.foregroundHike = runtime.foregroundHike?.copy(autoFollowEnabled = enabled)
                }
                if (runtime.walkedHike?.id == hikeId) {
                    runtime.walkedHike = runtime.walkedHike?.copy(autoFollowEnabled = enabled)
                }
                if (!enabled && runtime.pendingStart?.hikeId == hikeId.value) {
                    runtime.pendingStart = null
                }
                runtime.syncLocationFeed()
                publishFlowsLocked(clock.now().toEpochMilli())
            }
        }
    }

    override fun onHikeDeleted(hikeId: HikeId) {
        applicationScope.launch {
            mutex.withLock {
                runtime.endedHikeIds.remove(hikeId)
                if (runtime.pendingStart?.hikeId == hikeId.value) runtime.pendingStart = null
                if (runtime.foregroundHike?.id == hikeId) {
                    runtime.foregroundHike = null
                    runtime.foregroundProfile = null
                }
                if (runtime.activeRecord?.hikeId == hikeId.value) {
                    runtime.clearActiveWalkState()
                    cancelJunctionGuideLocked()
                    notificationController.endFollowing()
                    if (!recordingController.state.value.isActive) {
                        serviceBridge.stopService()
                    }
                    runtime.syncLocationFeed()
                }
                publishFlowsLocked(clock.now().toEpochMilli())
            }
        }
    }

    override fun onHikeRouteChanged(hikeId: HikeId, newRoute: List<RouteCoordinate>) {
        applicationScope.launch {
            mutex.withLock {
                val now = clock.now().toEpochMilli()
                if (runtime.pendingStart?.hikeId == hikeId.value) runtime.pendingStart = null
                val active = runtime.activeRecord
                if (active != null && active.hikeId == hikeId.value && !active.isAlong(newRoute)) {
                    runtime.finishWalk(TrailWalkEndReason.ROUTE_CHANGED, now)
                    cancelJunctionGuideLocked()
                }
                publishFlowsLocked(now)
            }
        }
    }

    override fun dismissStartNotice() {
        applicationScope.launch {
            mutex.withLock {
                runtime.startNotice = null
                publishFlowsLocked(clock.now().toEpochMilli())
            }
        }
    }

    override fun keepFollowingAfterOffTrail() {
        applicationScope.launch {
            mutex.withLock {
                runtime.offTrailQuestion = null
                publishFlowsLocked(clock.now().toEpochMilli())
            }
        }
    }

    override fun consumeLastEndedWalk() {
        applicationScope.launch {
            mutex.withLock {
                runtime.lastEndedWalk = null
                publishFlowsLocked(clock.now().toEpochMilli())
            }
        }
    }

    override fun onLocationPermissionDenied(hasCoarse: Boolean) {
        applicationScope.launch {
            mutex.withLock {
                runtime.recordLocationPermissionDenied(hasCoarse)
                publishFlowsLocked(clock.now().toEpochMilli())
            }
        }
    }

    override fun dismissPermissionIssue() {
        applicationScope.launch {
            mutex.withLock {
                runtime.permissionIssue = null
                publishFlowsLocked(clock.now().toEpochMilli())
            }
        }
    }

    override suspend fun toggleJunctionWarnings() = withContext(engineContext) {
        val next = mutex.withLock { !runtime.isJunctionWarningsEnabled }
        userSettingsRepository.setJunctionWarningsEnabled(next)
        mutex.withLock {
            runtime.isJunctionWarningsEnabled = next
            if (next) {
                junctionVoice.speak(JunctionCueFormatter.WARNINGS_ENABLED_ANNOUNCEMENT)
            }
            syncJunctionGuideLocked()
            publishFlowsLocked(clock.now().toEpochMilli())
        }
    }

    override fun onAppForegrounded() {
        applicationScope.launch {
            mutex.withLock {
                val now = clock.now().toEpochMilli()
                locationFeed.refreshPermissionStatus()
                if (locationFeed.permissionStatus.value == LocationPermissionStatus.GRANTED_FINE) {
                    runtime.permissionIssue = null
                }
                endIfAbandonedLocked(now)
                if (runtime.isJunctionFeedParked) {
                    runtime.isJunctionFeedParked = false
                }
                runtime.syncLocationFeed()
                publishFlowsLocked(now)
            }
        }
    }

    private suspend fun handleIncomingFix(fix: LocationFix) {
        val now = clock.now().toEpochMilli()
        endIfAbandonedLocked(now)
        val accepted = LocationFixPolicy.accepts(
            fix = fix,
            maximumAgeSeconds = LocationFixPolicy.FOREGROUND_MAXIMUM_AGE_SECONDS,
            maximumHorizontalAccuracyMeters = RouteProfile.FOLLOW_MATCH_THRESHOLD_METERS,
            nowEpochMillis = now
        )
        if (!accepted) {
            publishFlowsLocked(now)
            return
        }
        val matchedAt = maxOf(fix.timestampEpochMillis, 0L).takeIf { it > 0L } ?: now
        val activeHike = runtime.walkedHike
        val activeProfile = runtime.walkedProfile
        if (runtime.activeRecord != null && activeHike != null && activeProfile != null) {
            processFixForActiveWalkLocked(fix, activeHike, activeProfile, matchedAt, now)
        } else {
            val fgHike = runtime.foregroundHike
            val fgProfile = runtime.foregroundProfile
            if (fgHike != null && fgProfile != null) {
                processFixForForegroundPreviewLocked(fix, fgHike, fgProfile, matchedAt, now)
            }
        }
        publishFlowsLocked(now)
    }

    private suspend fun processFixForActiveWalkLocked(
        fix: LocationFix,
        hike: Hike,
        profile: RouteProfile,
        matchedAt: Long,
        now: Long
    ) {
        val current = runtime.activeRecord ?: return
        val projection = profile.nearestPoint(
            coordinate = fix.coordinate,
            nearDistanceMeters = current.lastFollowedDistanceMeters,
            headingDegrees = LocationFixPolicy.course(fix)
        ) ?: return
        val onTrail = projection.offRouteMeters <= RouteProfile.FOLLOW_MATCH_THRESHOLD_METERS
        if (onTrail && hike.autoFollowEnabled && runtime.foregroundHike?.id == hike.id) {
            runtime.followMarkerHikeId = hike.id
            runtime.followMarkerDistanceMeters = projection.distanceAlongRouteMeters
        }
        runtime.evaluateOffTrailAndJunctions(fix, projection.distanceAlongRouteMeters, projection.offRouteMeters, now)
        if (!onTrail) {
            runtime.handleOffRouteForActiveWalk(current, now)
        } else {
            val completed = runtime.recordMatchedActiveFix(
                current = current,
                distanceAlongRoute = projection.distanceAlongRouteMeters,
                accuracyMeters = fix.horizontalAccuracyMeters,
                matchedAt = matchedAt,
                nowEpochMillis = now
            )
            if (completed) {
                cancelJunctionGuideLocked()
            }
        }
    }

    private suspend fun processFixForForegroundPreviewLocked(
        fix: LocationFix,
        hike: Hike,
        profile: RouteProfile,
        matchedAt: Long,
        now: Long
    ) {
        val projection = profile.nearestPoint(
            coordinate = fix.coordinate,
            nearDistanceMeters = runtime.pendingStart?.record?.lastFollowedDistanceMeters,
            headingDegrees = LocationFixPolicy.course(fix)
        ) ?: return
        val onTrail = projection.offRouteMeters <= RouteProfile.FOLLOW_MATCH_THRESHOLD_METERS
        if (!onTrail) {
            runtime.endedHikeIds.remove(hike.id)
            if (runtime.pendingStart?.hikeId == hike.id.value) runtime.pendingStart = null
            return
        }
        if (hike.autoFollowEnabled) {
            runtime.followMarkerHikeId = hike.id
            runtime.followMarkerDistanceMeters = projection.distanceAlongRouteMeters
        }
        val confirmed = runtime.advancePendingAutoStart(
            hike = hike,
            profile = profile,
            distanceMeters = projection.distanceAlongRouteMeters,
            accuracyMeters = fix.horizontalAccuracyMeters,
            matchedAt = matchedAt,
            nowEpochMillis = now
        )
        if (confirmed != null && runtime.verifyFineLocationPermission()) {
            activateFollowingWalkLocked(
                record = confirmed,
                hike = hike,
                profile = profile,
                startNotice = TrailWalkStartNotice(hikeId = hike.id, title = hike.displayTitle),
                now = now
            )
        }
    }

    private suspend fun activateFollowingWalkLocked(
        record: TrailWalkRecord,
        hike: Hike,
        profile: RouteProfile,
        startNotice: TrailWalkStartNotice?,
        now: Long
    ): Boolean {
        runtime.adoptWalk(record, hike, profile)
        runtime.startNotice = startNotice
        publishFlowsLocked(now)
        runtime.publishLiveUpdate(now)
        if (!runtime.tryEnsureServiceStarted()) {
            runtime.clearActiveWalkState()
            notificationController.endFollowing()
            runtime.syncLocationFeed()
            publishFlowsLocked(now)
            return false
        }
        runtime.syncLocationFeed()
        runtime.persistMilestone(record, now)
        syncJunctionGuideLocked()
        return true
    }

    private suspend fun endIfAbandonedLocked(nowEpochMillis: Long) {
        val current = runtime.activeRecord ?: return
        if (current.isAbandoned(nowEpochMillis)) {
            runtime.finishWalk(TrailWalkEndReason.ABANDONED, nowEpochMillis)
            cancelJunctionGuideLocked()
        }
    }

    private fun syncJunctionGuideLocked() {
        val record = runtime.activeRecord
        val profile = runtime.walkedProfile
        val canGuide = record != null && record.phase == TrailWalkPhase.FOLLOWING && profile != null
        if (!runtime.isJunctionWarningsEnabled || !canGuide) {
            cancelJunctionGuideLocked()
            return
        }
        if (runtime.junctionAnnouncer != null || junctionPrepareJob?.isActive == true) return
        val hikeId = record.hikeId
        val initialDirection = record.coverage.direction
        junctionPrepareJob = applicationScope.launch {
            val junctions = junctionProvider.junctionsAlong(profile)
            mutex.withLock {
                if (runtime.activeRecord?.hikeId == hikeId && runtime.isJunctionWarningsEnabled) {
                    runtime.junctionAnnouncer = JunctionAnnouncer(junctions, initialDirection)
                }
            }
        }
    }

    private fun cancelJunctionGuideLocked() {
        junctionPrepareJob?.cancel()
        junctionPrepareJob = null
        runtime.junctionAnnouncer = null
        junctionVoice.stopSpeaking()
    }

    private suspend fun resolveHikeLocked(hikeId: HikeId): Hike? = runtime.foregroundHike?.takeIf { it.id == hikeId }
        ?: hikeRepository.observeHike(hikeId).first()

    private fun resolveProfileLocked(hike: Hike): RouteProfile =
        runtime.foregroundProfile?.takeIf { runtime.foregroundHike?.id == hike.id }
            ?: RouteProfile.from(hike.route)

    private fun publishFlowsLocked(nowEpochMillis: Long) {
        sessionStateFlow.value = runtime.currentSessionState()
        progressStateFlow.value = runtime.currentProgressState(nowEpochMillis)
    }
}
