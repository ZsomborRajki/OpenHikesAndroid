package app.openhikes.core.location

import android.app.Notification
import app.openhikes.core.common.HikeTitle
import app.openhikes.core.common.RecordingTitle
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.ExportedGpx
import app.openhikes.core.model.GpxImportResult
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeLocalState
import app.openhikes.core.model.HikeMapRoute
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.model.LocationFix
import app.openhikes.core.model.PreparedRecording
import app.openhikes.core.model.RecordingEnergyProfile
import app.openhikes.core.model.RecordingMotionState
import app.openhikes.core.model.RecordingPhase
import app.openhikes.core.model.RecordingStatsSnapshot
import app.openhikes.core.model.RecordingStopStep
import app.openhikes.core.model.RecordingTraceSnapshot
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.RouteStyleUpdate
import app.openhikes.core.model.RouteTint
import app.openhikes.core.model.ThermalSeverity
import app.openhikes.core.model.UserSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

@Singleton
internal class LocalFakeLocationFeed @Inject constructor() : LocationFeed {
    private val permissionFlow = MutableStateFlow(LocationPermissionStatus.GRANTED_FINE)
    private val fixesFlow = MutableSharedFlow<LocationFix>(extraBufferCapacity = 64)
    private val activeProfileFlow = MutableStateFlow<RecordingEnergyProfile?>(null)

    override val permissionStatus: StateFlow<LocationPermissionStatus> = permissionFlow.asStateFlow()
    override val fixes: Flow<LocationFix> = fixesFlow.asSharedFlow()
    override val activeProfile: StateFlow<RecordingEnergyProfile?> = activeProfileFlow.asStateFlow()

    var stopCount: Int = 0
        private set

    fun setPermission(status: LocationPermissionStatus) {
        permissionFlow.value = status
    }

    suspend fun emitFix(fix: LocationFix) {
        fixesFlow.emit(fix)
    }

    override fun refreshPermissionStatus() = Unit

    override fun startUpdates(profile: RecordingEnergyProfile) {
        activeProfileFlow.value = profile
    }

    override fun updateProfile(profile: RecordingEnergyProfile) {
        activeProfileFlow.value = profile
    }

    override fun stopUpdates() {
        stopCount += 1
        activeProfileFlow.value = null
    }
}

@Singleton
internal class LocalFakeMotionFeed @Inject constructor() : MotionFeed {
    private val state = MutableStateFlow(RecordingMotionState.UNKNOWN)
    override val motionState: StateFlow<RecordingMotionState> = state.asStateFlow()
    override fun start() = Unit
    override fun stop() {
        state.value = RecordingMotionState.UNKNOWN
    }
}

@Singleton
internal class LocalFakeDevicePowerMonitor @Inject constructor() : DevicePowerMonitor {
    private val state = MutableStateFlow(DevicePowerState())
    override val powerState: Flow<DevicePowerState> = state.asStateFlow()
    override fun currentState(): DevicePowerState = state.value

    fun setPowerState(isPowerSaveMode: Boolean, thermalSeverity: ThermalSeverity) {
        state.value = DevicePowerState(isPowerSaveMode = isPowerSaveMode, thermalSeverity = thermalSeverity)
    }
}

@Singleton
internal class LocalFakeServiceBridge @Inject constructor() : RecordingServiceBridge {
    var ensureStartedCount: Int = 0
        private set
    var stopServiceCount: Int = 0
        private set
    var nextEnsureStartedError: Throwable? = null
    var onEnsureStarted: (() -> Unit)? = null

    override fun ensureStarted() {
        ensureStartedCount += 1
        onEnsureStarted?.invoke()
        nextEnsureStartedError?.let { error ->
            nextEnsureStartedError = null
            throw error
        }
    }

    override fun stopService() {
        stopServiceCount += 1
    }
}

@Singleton
internal class LocalFakeNotificationPoster @Inject constructor() : NotificationPoster {
    override fun canPostPromotedNotifications(): Boolean = true
    override fun post(notificationId: Int, notification: Notification) = Unit
    override fun cancel(notificationId: Int) = Unit
}

internal class LocalFakeUserSettingsRepository : UserSettingsRepository {
    private val state = MutableStateFlow(UserSettings())
    override val userSettings: Flow<UserSettings> = state.asStateFlow()
    override suspend fun setDistanceUnit(unit: DistanceUnit) = Unit
    override suspend fun setShowsHikingRoutes(enabled: Boolean) = Unit
    override suspend fun setAutoPauseEnabled(enabled: Boolean) {
        state.update { it.copy(autoPauseEnabled = enabled) }
    }

    override suspend fun setHikeRemindersEnabled(enabled: Boolean) = Unit
    override suspend fun setLiveUpdatesEnabled(enabled: Boolean) = Unit
    override suspend fun setKeepScreenAwake(enabled: Boolean) = Unit
    override suspend fun setAutoSaveTilesEnabled(enabled: Boolean) = Unit
    override suspend fun setSavePhotosToLibrary(enabled: Boolean) = Unit
    override suspend fun setMapStyleId(styleId: String) = Unit
    override suspend fun setOfflineCountriesExtraDetail(enabled: Boolean) = Unit
    override suspend fun setJunctionWarningsEnabled(enabled: Boolean) {
        state.update { it.copy(junctionWarningsEnabled = enabled) }
    }
}

@Suppress("TooManyFunctions")
internal class LocalFakeHikeRepository : HikeRepository {
    private val hikesState = MutableStateFlow<List<Hike>>(emptyList())
    private val localStateMap = MutableStateFlow<Map<HikeId, HikeLocalState>>(emptyMap())
    var nextFinalizeFailure: Throwable? = null
    var finalizeGate: CompletableDeferred<Unit>? = null

    override val hikes: Flow<List<HikeSummary>> = hikesState.map { list ->
        list.filterNot { it.isRecording }.map { it.toSummary() }
    }
    override val mapRoutes: Flow<List<HikeMapRoute>> = MutableStateFlow(emptyList())

    fun setLocalStateForTest(localState: HikeLocalState) {
        localStateMap.update { it + (localState.hikeId to localState) }
    }

    override fun observeHike(hikeId: HikeId): Flow<Hike?> = hikesState.map { list ->
        list.firstOrNull { it.id == hikeId }
    }
    override fun observeLocalState(hikeId: HikeId): Flow<HikeLocalState?> = localStateMap.map { it[hikeId] }
    override fun observeWalks(hikeId: HikeId): Flow<List<app.openhikes.core.model.HikeWalk>> =
        MutableStateFlow(emptyList())
    override fun observeWalk(walkId: String): Flow<app.openhikes.core.model.HikeWalk?> = MutableStateFlow(null)
    override suspend fun importGpxBytes(bytes: ByteArray, fileName: String?): GpxImportResult = error("Unused")
    override suspend fun importGpxUri(uriString: String): GpxImportResult = error("Unused")
    override suspend fun exportHike(hikeId: HikeId): Result<ExportedGpx> = error("Unused")
    override suspend fun exportHikeToUri(hikeId: HikeId, uriString: String): Result<Unit> = error("Unused")
    override suspend fun renameHike(hikeId: HikeId, draftTitle: String): Result<Unit> = error("Unused")
    override suspend fun updateRouteStyle(hikeId: HikeId, style: RouteStyleUpdate): Result<Unit> = error("Unused")
    override suspend fun setAutoFollowEnabled(hikeId: HikeId, enabled: Boolean): Result<Unit> = Result.success(Unit)
    override suspend fun saveWalkInProgress(
        hikeId: HikeId,
        record: app.openhikes.core.model.TrailWalkRecord?
    ): Result<Unit> = Result.success(Unit)
    override suspend fun getOpenWalksInProgress(): Result<List<app.openhikes.core.model.TrailWalkRecord>> =
        Result.success(emptyList())
    override suspend fun finishWalk(
        record: app.openhikes.core.model.TrailWalkRecord,
        endedAtEpochMillis: Long,
        reason: app.openhikes.core.model.TrailWalkEndReason
    ): Result<app.openhikes.core.model.HikeWalk?> = Result.success(null)
    override suspend fun deleteHike(hikeId: HikeId): Result<Unit> = error("Unused")
    override suspend fun seedFixtureIfConfigured(): HikeId? = null

    fun getHike(hikeId: HikeId): Hike? = hikesState.value.firstOrNull { it.id == hikeId }

    override suspend fun createOrClaimRecordingDraft(
        hikeId: HikeId,
        startedAtEpochMillis: Long,
        title: String
    ): Result<Hike> {
        val draft = Hike(
            id = hikeId,
            title = HikeTitle.bounded(title).orEmpty().ifBlank { "Hike" },
            distanceMeters = 0.0,
            dateEpochMillis = startedAtEpochMillis,
            tintHex = RouteTint.DEFAULT_HEX,
            isRecording = true
        )
        hikesState.update { it.filterNot { h -> h.id == hikeId } + draft }
        localStateMap.update { it + (hikeId to HikeLocalState(hikeId = hikeId, ownsRecordingDraft = true)) }
        return Result.success(draft)
    }

    override suspend fun finalizeRecordingDraft(
        hikeId: HikeId,
        title: String,
        customName: String?,
        startedAtEpochMillis: Long,
        endedAtEpochMillis: Long,
        prepared: PreparedRecording
    ): Result<Hike> {
        finalizeGate?.await()
        val failure = nextFinalizeFailure?.also { nextFinalizeFailure = null }
        val finalized = Hike(
            id = hikeId,
            title = title,
            customName = customName,
            distanceMeters = prepared.distanceMeters,
            durationSeconds = prepared.recordedSeconds,
            dateEpochMillis = startedAtEpochMillis,
            tintHex = RouteTint.DEFAULT_HEX,
            routeWidthDp = HikeSummary.DEFAULT_ROUTE_WIDTH_DP,
            linePattern = RouteLinePattern.Default,
            routeBorderHex = RouteBorder.NONE_HEX,
            route = prepared.route
        )
        return if (failure != null) {
            Result.failure(failure)
        } else {
            hikesState.update { it.filterNot { h -> h.id == hikeId } + finalized }
            localStateMap.update { it + (hikeId to HikeLocalState(hikeId = hikeId, ownsRecordingDraft = false)) }
            Result.success(finalized)
        }
    }

    override suspend fun deleteRecordingDraft(hikeId: HikeId): Result<Unit> {
        hikesState.update { it.filterNot { h -> h.id == hikeId && h.isRecording } }
        return Result.success(Unit)
    }

    override suspend fun deleteOwnedOrphanedRecordingDrafts(exceptHikeId: HikeId?): Result<Int> {
        val owned = localStateMap.value.filterValues { it.ownsRecordingDraft }.keys
        val removed = hikesState.value.filter { it.isRecording && it.id != exceptHikeId && owned.contains(it.id) }
            .map { it.id }.toSet()
        hikesState.update { it.filterNot { h -> removed.contains(h.id) } }
        return Result.success(removed.size)
    }
}

@Singleton
@Suppress("TooManyFunctions")
internal class LocalFakeRecordingController @Inject constructor() : RecordingController {
    private val stateFlow = MutableStateFlow(RecordingSessionState())
    override val state: StateFlow<RecordingSessionState> = stateFlow.asStateFlow()
    override val stats: StateFlow<RecordingStatsSnapshot> = MutableStateFlow(RecordingStatsSnapshot())
    override val trace: StateFlow<RecordingTraceSnapshot> = MutableStateFlow(RecordingTraceSnapshot())
    override val completedSave: StateFlow<CompletedRecordingSave?> = MutableStateFlow(null)

    var pauseCount: Int = 0
        private set
    var resumeCount: Int = 0
        private set

    override fun elapsedSeconds(): Double = 0.0
    override fun consumeCompletedSave(hikeId: HikeId) = Unit
    override suspend fun start() {
        stateFlow.value = RecordingSessionState(
            phase = RecordingPhase.WAITING_FOR_FIX,
            sessionId = "service-test-1",
            sessionStartedAtEpochMillis = 1_781_510_400_000L,
            draftTitle = RecordingTitle.defaultTitle(1_781_510_400_000L)
        )
    }

    override suspend fun onPermissionResult(grantedFine: Boolean) = Unit
    override suspend fun pause() {
        pauseCount += 1
        stateFlow.value = stateFlow.value.copy(phase = RecordingPhase.PAUSED)
    }

    override suspend fun resume() {
        resumeCount += 1
        stateFlow.value = stateFlow.value.copy(phase = RecordingPhase.RECORDING)
    }

    override suspend fun settledStopStep(): RecordingStopStep? = null
    override fun suggestedTitle(): String = "Test Hike"
    override suspend fun stop(customName: String?, offeredTitle: String?): Result<Hike> = error("Unused")
    override suspend fun retrySave(): Result<Hike> = error("Unused")
    override suspend fun discard() {
        stateFlow.value = RecordingSessionState()
    }

    override fun dismissFailure() = Unit
    override fun dismissRecoveryNotice() = Unit
    override suspend fun recoverOpenSession(automaticallyResume: Boolean) = Unit
    override suspend fun flushJournal() = Unit
}
