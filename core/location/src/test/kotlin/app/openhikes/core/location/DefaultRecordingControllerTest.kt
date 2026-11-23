package app.openhikes.core.location

import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeLocalState
import app.openhikes.core.model.LocationFix
import app.openhikes.core.model.RecordingEnergyReason
import app.openhikes.core.model.RecordingFailure
import app.openhikes.core.model.RecordingPhase
import app.openhikes.core.model.RecordingPoint
import app.openhikes.core.model.RecordingPointFlags
import app.openhikes.core.model.RecordingRecoveryState
import app.openhikes.core.model.RecordingStopStep
import app.openhikes.core.model.RouteBoundary
import app.openhikes.core.model.ThermalSeverity
import app.openhikes.core.testing.TestClock
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DefaultRecordingControllerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun startRejectsPreStartFixAndCapturesValidFixesIntoDraft() = runTest {
        val harness = createHarness(UnconfinedTestDispatcher(testScheduler))
        try {
            harness.controller.start()
            assertThat(harness.controller.state.value.phase).isEqualTo(RecordingPhase.WAITING_FOR_FIX)
            assertThat(harness.serviceBridge.ensureStartedCount).isEqualTo(1)
            assertThat(harness.hikeRepository.hikes.first()).isEmpty()

            val preStartFix = fixAt(offsetSeconds = -5L, lat = 47.71800)
            harness.locationFeed.emitFix(preStartFix)
            assertThat(harness.controller.state.value.phase).isEqualTo(RecordingPhase.WAITING_FOR_FIX)
            assertThat(harness.controller.stats.value.pointCount).isEqualTo(0)

            harness.locationFeed.emitFix(fixAt(offsetSeconds = 0L, lat = 47.71842, clock = harness.clock))
            harness.clock.advanceBy(Duration.ofSeconds(10))
            harness.locationFeed.emitFix(fixAt(offsetSeconds = 0L, lat = 47.71900, clock = harness.clock))

            assertThat(harness.controller.state.value.phase).isEqualTo(RecordingPhase.RECORDING)
            assertThat(harness.controller.stats.value.pointCount).isEqualTo(2)
            assertThat(harness.controller.stats.value.distanceMeters).isGreaterThan(50.0)
            assertThat(harness.controller.trace.value.points).hasSize(2)
            assertThat(harness.hikeRepository.hikes.first()).isEmpty()
        } finally {
            harness.scope.cancel()
        }
    }

    @Test
    fun permissionTransitionsAndPermissionResultRecoveryWorkCleanly() = runTest {
        val harness = createHarness(UnconfinedTestDispatcher(testScheduler))
        try {
            harness.locationFeed.setPermission(LocationPermissionStatus.DENIED)
            harness.controller.start()
            assertThat(harness.controller.state.value.phase).isEqualTo(RecordingPhase.FAILED)
            assertThat(harness.controller.state.value.failure).isEqualTo(RecordingFailure.LocationDenied)

            harness.locationFeed.setPermission(LocationPermissionStatus.GRANTED_COARSE)
            harness.controller.onPermissionResult(grantedFine = false)
            assertThat(harness.controller.state.value.failure).isEqualTo(RecordingFailure.PreciseLocationRequired)

            harness.locationFeed.setPermission(LocationPermissionStatus.GRANTED_FINE)
            harness.controller.onPermissionResult(grantedFine = true)
            assertThat(harness.controller.state.value.phase).isEqualTo(RecordingPhase.WAITING_FOR_FIX)
            assertThat(harness.controller.state.value.sessionId).isNotNull()
        } finally {
            harness.scope.cancel()
        }
    }

    @Test
    fun pauseStopsSensorsAndResumeMarksSegmentBoundary() = runTest {
        val harness = createHarness(UnconfinedTestDispatcher(testScheduler))
        try {
            harness.controller.start()
            harness.locationFeed.emitFix(fixAt(0L, 47.71842, clock = harness.clock))
            harness.clock.advanceBy(Duration.ofSeconds(10))
            harness.locationFeed.emitFix(fixAt(0L, 47.71900, clock = harness.clock))

            harness.controller.pause()
            assertThat(harness.controller.state.value.phase).isEqualTo(RecordingPhase.PAUSED)
            assertThat(harness.locationFeed.stopCount).isEqualTo(1)

            harness.clock.advanceBy(Duration.ofMinutes(2))
            harness.controller.resume()
            harness.locationFeed.emitFix(fixAt(0L, 47.72100, clock = harness.clock))
            harness.clock.advanceBy(Duration.ofSeconds(10))
            harness.locationFeed.emitFix(fixAt(0L, 47.72160, clock = harness.clock))

            assertThat(harness.controller.stats.value.segments).hasSize(2)
            assertThat(harness.controller.trace.value.points[2].boundary).isEqualTo(RouteBoundary.PAUSED)
        } finally {
            harness.scope.cancel()
        }
    }

    @Test
    fun autoPauseBackdatesCutoffTakesOverManualPauseAndSplitsDepartureSegment() = runTest {
        val harness = createHarness(
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            launchConfig = LaunchConfig(isUiTesting = true, autoPauseSecondsOverride = 5.0)
        )
        try {
            harness.controller.start()
            harness.locationFeed.emitFix(fixAt(0L, 47.71842, clock = harness.clock))
            harness.clock.advanceBy(Duration.ofSeconds(10))
            harness.locationFeed.emitFix(fixAt(0L, 47.71880, clock = harness.clock))
            harness.clock.advanceBy(Duration.ofSeconds(6))
            harness.locationFeed.emitFix(fixAt(0L, 47.71881, clock = harness.clock))

            assertThat(harness.controller.state.value.phase).isEqualTo(RecordingPhase.PAUSED)
            assertThat(harness.controller.state.value.isAutoPaused).isTrue()

            harness.clock.advanceBy(Duration.ofSeconds(10))
            harness.locationFeed.emitFix(fixAt(0L, 47.71940, clock = harness.clock))
            harness.clock.advanceBy(Duration.ofSeconds(10))
            harness.locationFeed.emitFix(fixAt(0L, 47.72000, clock = harness.clock))
            assertThat(harness.controller.state.value.phase).isEqualTo(RecordingPhase.RECORDING)
            assertThat(harness.controller.state.value.isAutoPaused).isFalse()
            assertThat(harness.controller.stats.value.segments).hasSize(2)

            harness.clock.advanceBy(Duration.ofSeconds(10))
            harness.locationFeed.emitFix(fixAt(0L, 47.72001, clock = harness.clock))
            assertThat(harness.controller.state.value.isAutoPaused).isTrue()

            harness.controller.pause()
            assertThat(harness.controller.state.value.phase).isEqualTo(RecordingPhase.PAUSED)
            assertThat(harness.controller.state.value.isAutoPaused).isFalse()
            assertThat(harness.locationFeed.activeProfile.value).isNull()
        } finally {
            harness.scope.cancel()
        }
    }

    @Test
    fun batterySaverAndThermalSeverityUpdateEnergyProfile() = runTest {
        val harness = createHarness(UnconfinedTestDispatcher(testScheduler))
        try {
            harness.controller.start()
            assertThat(harness.controller.state.value.energyProfile.reason).isNull()

            harness.powerMonitor.setPowerState(isPowerSaveMode = true, thermalSeverity = ThermalSeverity.SEVERE)
            assertThat(harness.controller.state.value.energyProfile.reason)
                .isEqualTo(RecordingEnergyReason.LOW_POWER_AND_HOT)
            assertThat(harness.locationFeed.activeProfile.value?.reason)
                .isEqualTo(RecordingEnergyReason.LOW_POWER_AND_HOT)
        } finally {
            harness.scope.cancel()
        }
    }

    @Test
    fun stopFailureKeepsDraftAndJournalForRetrySave() = runTest {
        val harness = createHarness(UnconfinedTestDispatcher(testScheduler))
        try {
            harness.controller.start()
            harness.locationFeed.emitFix(fixAt(0L, 47.71842, clock = harness.clock))
            harness.clock.advanceBy(Duration.ofSeconds(10))
            harness.locationFeed.emitFix(fixAt(0L, 47.71900, clock = harness.clock))

            assertThat(harness.controller.settledStopStep()).isEqualTo(RecordingStopStep.ASK_NAME)
            harness.hikeRepository.nextFinalizeFailure = IOException("Disk full")

            val failed = harness.controller.stop(customName = "Ridge Attempt", offeredTitle = "Morning Hike")
            assertThat(failed.isFailure).isTrue()
            assertThat(harness.controller.state.value.phase).isEqualTo(RecordingPhase.FAILED)
            assertThat(harness.controller.state.value.canRetrySave).isTrue()
            assertThat(harness.journal.metadataFile.exists()).isTrue()

            val retried = harness.controller.retrySave()
            assertThat(retried.isSuccess).isTrue()
            assertThat(retried.getOrThrow().displayTitle).isEqualTo("Ridge Attempt")
            assertThat(harness.controller.state.value.phase).isEqualTo(RecordingPhase.IDLE)
            assertThat(harness.journal.journalFile.exists()).isFalse()
            assertThat(harness.hikeRepository.hikes.first()).hasSize(1)
        } finally {
            harness.scope.cancel()
        }
    }

    @Test
    fun recoverOpenSessionAutoResumesRecentAndPromptsOrFinalizesOlderDrafts() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val harness = createHarness(dispatcher)
        try {
            val recentId = UUID.randomUUID().toString()
            val t0 = harness.clock.now().toEpochMilli()
            harness.journal.start(sessionId = recentId, startedAtEpochMillis = t0, title = "Recent Hike")
            harness.journal.append(pointAt(t0, 47.71842))
            harness.journal.append(pointAt(t0 + 10_000L, 47.71900))
            harness.journal.close()
            harness.clock.advanceBy(Duration.ofMinutes(2))

            harness.controller.recoverOpenSession(automaticallyResume = true)
            assertThat(harness.controller.state.value.phase).isEqualTo(RecordingPhase.RECORDING)
            assertThat(harness.controller.state.value.recoveryState).isEqualTo(RecordingRecoveryState.Resumed)
            harness.controller.discard()

            val staleId = UUID.randomUUID().toString()
            val ownedStale = HikeId("owned-orphan")
            val foreignDraft = HikeId("foreign-draft")
            harness.hikeRepository.createOrClaimRecordingDraft(ownedStale, t0, "Owned Orphan")
            harness.hikeRepository.createOrClaimRecordingDraft(foreignDraft, t0, "Foreign Draft")
            harness.hikeRepository.setLocalStateForTest(
                HikeLocalState(hikeId = foreignDraft, ownsRecordingDraft = false)
            )

            val t1 = harness.clock.now().toEpochMilli()
            harness.journal.start(sessionId = staleId, startedAtEpochMillis = t1, title = "Stale Hike")
            harness.journal.append(pointAt(t1, 47.71842))
            harness.journal.append(pointAt(t1 + 10_000L, 47.71900))
            harness.journal.close()
            harness.clock.advanceBy(Duration.ofMinutes(10))

            harness.controller.recoverOpenSession(automaticallyResume = true)
            assertThat(harness.controller.state.value.phase).isEqualTo(RecordingPhase.PAUSED)
            assertThat(harness.controller.state.value.recoveryState)
                .isInstanceOf(RecordingRecoveryState.NeedsDecision::class.java)
            assertThat(harness.hikeRepository.getHike(ownedStale)).isNull()
            assertThat(harness.hikeRepository.getHike(foreignDraft)).isNotNull()
        } finally {
            harness.scope.cancel()
        }
    }

    @Test
    fun recoverFinishedJournalFinalizesIntoLibraryAutomatically() = runTest {
        val harness = createHarness(UnconfinedTestDispatcher(testScheduler))
        try {
            val finishedId = UUID.randomUUID().toString()
            val t0 = harness.clock.now().toEpochMilli()
            harness.journal.start(sessionId = finishedId, startedAtEpochMillis = t0, title = "Completed Before Crash")
            harness.journal.append(pointAt(t0, 47.71842))
            harness.journal.append(pointAt(t0 + 10_000L, 47.71900))
            harness.journal.finish(t0 + 20_000L)

            harness.controller.recoverOpenSession(automaticallyResume = true)
            assertThat(harness.controller.state.value.phase).isEqualTo(RecordingPhase.IDLE)
            assertThat(harness.journal.journalFile.exists()).isFalse()
            assertThat(harness.hikeRepository.hikes.first().map { it.title }).containsExactly("Completed Before Crash")
        } finally {
            harness.scope.cancel()
        }
    }

    private fun createHarness(
        dispatcher: CoroutineDispatcher,
        launchConfig: LaunchConfig = LaunchConfig.Production
    ): Harness {
        val clock = TestClock()
        val journalDir: File = tempFolder.newFolder()
        val journal = TrackJournal(journalDir, clock, dispatcher)
        val locationFeed = LocalFakeLocationFeed()
        val motionFeed = LocalFakeMotionFeed()
        val powerMonitor = LocalFakeDevicePowerMonitor()
        val userSettingsRepository = LocalFakeUserSettingsRepository()
        val hikeRepository = LocalFakeHikeRepository()
        val poster = LocalFakeNotificationPoster()
        val notificationController = LiveUpdateNotificationController(
            builder = RecordingNotificationBuilder(ApplicationProvider.getApplicationContext()),
            poster = poster,
            clock = clock
        )
        val serviceBridge = LocalFakeServiceBridge()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val controller = DefaultRecordingController(
            clock = clock,
            locationFeed = locationFeed,
            motionFeed = motionFeed,
            powerMonitor = powerMonitor,
            userSettingsRepository = userSettingsRepository,
            hikeRepository = hikeRepository,
            journal = journal,
            notificationController = notificationController,
            serviceBridge = serviceBridge,
            launchConfigHolder = LaunchConfigHolder(launchConfig),
            applicationScope = scope
        )
        return Harness(
            clock = clock,
            journal = journal,
            locationFeed = locationFeed,
            powerMonitor = powerMonitor,
            hikeRepository = hikeRepository,
            serviceBridge = serviceBridge,
            scope = scope,
            controller = controller
        )
    }

    private fun fixAt(
        offsetSeconds: Long,
        lat: Double,
        lon: Double = 12.83177,
        clock: TestClock = TestClock()
    ): LocationFix = LocationFix(
        latitude = lat,
        longitude = lon,
        timestampEpochMillis = clock.now().toEpochMilli() + offsetSeconds * 1_000L,
        horizontalAccuracyMeters = 4.0,
        elevationMeters = 540.0,
        verticalAccuracyMeters = 3.0,
        speedMps = 1.4
    )

    private fun pointAt(epochMillis: Long, lat: Double): RecordingPoint = RecordingPoint(
        latitude = lat,
        longitude = 12.83177,
        timestampEpochMillis = epochMillis,
        horizontalAccuracyMeters = 4.0,
        elevationMeters = 540.0,
        flags = RecordingPointFlags.NONE
    )

    private data class Harness(
        val clock: TestClock,
        val journal: TrackJournal,
        val locationFeed: LocalFakeLocationFeed,
        val powerMonitor: LocalFakeDevicePowerMonitor,
        val hikeRepository: LocalFakeHikeRepository,
        val serviceBridge: LocalFakeServiceBridge,
        val scope: CoroutineScope,
        val controller: DefaultRecordingController
    )
}
