package app.openhikes.core.location

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.openhikes.core.common.gpx.GpxParseResult
import app.openhikes.core.common.gpx.GpxParser
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.JunctionManeuver
import app.openhikes.core.model.LocationFix
import app.openhikes.core.model.RouteProfile
import app.openhikes.core.model.TrailJunction
import app.openhikes.core.model.TrailWalkCoverage
import app.openhikes.core.model.TrailWalkEndReason
import app.openhikes.core.model.TrailWalkPhase
import app.openhikes.core.model.TrailWalkPolicy
import app.openhikes.core.model.TrailWalkRecord
import app.openhikes.core.testing.TestClock
import app.openhikes.core.testing.TestFixtures
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DefaultTrailWalkControllerTest {
    private lateinit var clock: TestClock
    private lateinit var hikeRepository: WalkTestHikeRepository
    private lateinit var settingsRepository: LocalFakeUserSettingsRepository
    private lateinit var locationFeed: LocalFakeLocationFeed
    private lateinit var recordingController: LocalFakeRecordingController
    private lateinit var serviceBridge: LocalFakeServiceBridge
    private lateinit var notificationPoster: RecordingNotificationPoster
    private lateinit var notificationController: LiveUpdateNotificationController
    private lateinit var voice: RecordingJunctionVoice
    private lateinit var hike: Hike
    private lateinit var profile: RouteProfile
    private var stubJunctions: List<TrailJunction> = emptyList()

    @Before
    fun setUp() {
        clock = TestClock(Instant.ofEpochMilli(START_TIME_MILLIS))
        val parsed = GpxParser.parseAll(TestFixtures.thumseeLoopFastGpxBytes()) as GpxParseResult.Success
        val track = parsed.value.first()
        hike = Hike(
            id = HikeId("thumsee-1"),
            title = TestFixtures.THUMSEE_LOOP_FAST_TITLE,
            distanceMeters = track.distanceMeters,
            dateEpochMillis = START_TIME_MILLIS,
            autoFollowEnabled = true,
            route = track.route
        )
        profile = RouteProfile.from(hike.route)
        hikeRepository = WalkTestHikeRepository(listOf(hike))
        settingsRepository = LocalFakeUserSettingsRepository()
        locationFeed = LocalFakeLocationFeed()
        recordingController = LocalFakeRecordingController()
        serviceBridge = LocalFakeServiceBridge()
        notificationPoster = RecordingNotificationPoster()
        val context = ApplicationProvider.getApplicationContext<Context>()
        notificationController = LiveUpdateNotificationController(
            builder = RecordingNotificationBuilder(context),
            poster = notificationPoster,
            clock = clock
        )
        voice = RecordingJunctionVoice()
        stubJunctions = emptyList()
    }

    @Test
    fun autoStartsAfterMovingAlongTrailPausesResumesAndEndsIntoHistory() = runTest {
        val controller = createController()
        controller.attachForegroundHike(hike, profile)

        // Off-trail fix starts nothing.
        emitCoordinate(TestFixtures.OFF_TRAIL_COORDINATE, stepMillis = 1_000L)
        assertThat(controller.sessionState.value.isWalkActive).isFalse()

        // Walk first 4 Thumsee points (116 m > 100 m minimum, 2+ outward steps) -> auto-starts!
        for (coord in TestFixtures.TRAIL_POINTS.take(4)) {
            emitCoordinate(coord, stepMillis = 2_000L)
        }
        assertThat(controller.sessionState.value.phase).isEqualTo(TrailWalkPhase.FOLLOWING)
        assertThat(controller.sessionState.value.startNotice?.hikeId).isEqualTo(hike.id)
        assertThat(serviceBridge.ensureStartedCount).isAtLeast(1)

        // Dismissing pill does not end the walk.
        controller.dismissStartNotice()
        assertThat(controller.sessionState.value.startNotice).isNull()
        assertThat(controller.sessionState.value.phase).isEqualTo(TrailWalkPhase.FOLLOWING)

        // Pause -> Resume -> End keeps walk row.
        assertThat(controller.pause()).isTrue()
        assertThat(controller.sessionState.value.phase).isEqualTo(TrailWalkPhase.PAUSED)
        assertThat(controller.resume()).isTrue()
        assertThat(controller.sessionState.value.phase).isEqualTo(TrailWalkPhase.FOLLOWING)

        val end = controller.endWalk()
        assertThat(end).isInstanceOf(TrailWalkEnd.Kept::class.java)
        assertThat(controller.sessionState.value.isWalkActive).isFalse()
        assertThat(hikeRepository.observeWalks(hike.id).first()).hasSize(1)
        assertThat(serviceBridge.stopServiceCount).isAtLeast(1)
    }

    @Test
    fun startByHandOffTrailHasNoStartPillAndFollowOffKeepsAccruingWhileParkingChart() = runTest {
        val controller = createController()
        controller.attachForegroundHike(hike, profile)

        assertThat(controller.startByHand(hike.id)).isTrue()
        assertThat(controller.sessionState.value.phase).isEqualTo(TrailWalkPhase.FOLLOWING)
        assertThat(controller.sessionState.value.startNotice).isNull()

        emitCoordinate(TestFixtures.TRAIL_POINTS[0], stepMillis = 2_000L)
        val initialMarker = controller.progressState.value.followMarkerDistanceMeters
        assertThat(initialMarker).isNotNull()

        // Turn Follow This Trail OFF: chart marker must stay parked while coverage & metersLeft still advance.
        controller.onAutoFollowChanged(hike.id, false)
        val beforeLeft = controller.progressState.value.metersLeft
        emitCoordinate(TestFixtures.TRAIL_POINTS[3], stepMillis = 2_000L)

        assertThat(controller.progressState.value.metersLeft).isLessThan(beforeLeft)
        assertThat(controller.progressState.value.followMarkerDistanceMeters).isEqualTo(initialMarker)
        assertThat(controller.progressState.value.timeLeftMark).isNotNull()
    }

    @Test
    fun endUnderMinimumDiscardsAndFollowRearmAllowsFreshAutoStart() = runTest {
        val controller = createController()
        controller.attachForegroundHike(hike, profile)

        assertThat(controller.startByHand(hike.id)).isTrue()
        val end = controller.endWalk()
        assertThat(end).isEqualTo(TrailWalkEnd.Discarded)
        assertThat(hikeRepository.observeWalks(hike.id).first()).isEmpty()

        // Without rearming, walking along the same trail does not immediately restart.
        for (coord in TestFixtures.TRAIL_POINTS.take(4)) {
            emitCoordinate(coord, stepMillis = 2_000L)
        }
        assertThat(controller.sessionState.value.isWalkActive).isFalse()

        // Turning auto-follow ON rearms auto-start.
        controller.onAutoFollowChanged(hike.id, true)
        for (coord in TestFixtures.TRAIL_POINTS.take(4)) {
            emitCoordinate(coord, stepMillis = 2_000L)
        }
        assertThat(controller.sessionState.value.phase).isEqualTo(TrailWalkPhase.FOLLOWING)
    }

    @Test
    fun refusesPauseAndEndWhenPersistenceFailsLeavingWalkActive() = runTest {
        val controller = createController()
        controller.attachForegroundHike(hike, profile)
        assertThat(controller.startByHand(hike.id)).isTrue()

        hikeRepository.nextPersistFailure = IOException("Disk full")
        assertThat(controller.pause()).isFalse()
        assertThat(controller.sessionState.value.phase).isEqualTo(TrailWalkPhase.FOLLOWING)

        hikeRepository.nextFinishFailure = IOException("Disk full")
        assertThat(controller.endWalk()).isEqualTo(TrailWalkEnd.Refused)
        assertThat(controller.sessionState.value.phase).isEqualTo(TrailWalkPhase.FOLLOWING)
    }

    @Test
    fun offTrailDwellRaisesQuestionAndJunctionWarningsSpeakAheadCue() = runTest {
        stubJunctions = listOf(
            TrailJunction(
                distanceMeters = 120.0,
                towardsEnd = JunctionManeuver.KEEP_LEFT,
                towardsStart = JunctionManeuver.TURN_RIGHT
            )
        )
        val controller = createController()
        controller.attachForegroundHike(hike, profile)
        assertThat(controller.startByHand(hike.id)).isTrue()

        controller.toggleJunctionWarnings()
        testScheduler.advanceUntilIdle()
        assertThat(controller.sessionState.value.isJunctionWarningsEnabled).isTrue()
        assertThat(voice.messages).contains(JunctionCueFormatter.WARNINGS_ENABLED_ANNOUNCEMENT)

        // Move along trail toward junction at 120 m -> ahead cue fires.
        emitCoordinate(profile.coordinateAt(0.0)!!, stepMillis = 2_000L)
        emitCoordinate(profile.coordinateAt(40.0)!!, stepMillis = 2_000L)
        assertThat(voice.messages.any { it.startsWith("Keep left at the fork in ") }).isTrue()

        // Depart off-trail for >= 2 minutes -> off-trail question raised once.
        emitCoordinate(TestFixtures.OFF_TRAIL_COORDINATE, stepMillis = 2_000L)
        assertThat(controller.sessionState.value.offTrailQuestion).isNull()
        emitCoordinate(TestFixtures.OFF_TRAIL_COORDINATE, stepMillis = 125_000L)
        assertThat(controller.sessionState.value.offTrailQuestion?.hikeId).isEqualTo(hike.id)

        controller.keepFollowingAfterOffTrail()
        assertThat(controller.sessionState.value.offTrailQuestion).isNull()
    }

    @Test
    fun restoreAtLaunchAdoptsRecentWalkAndAbandonsStaleWalk() = runTest {
        val recentRecord = TrailWalkRecord(
            hikeId = hike.id.value,
            startedAtEpochMillis = START_TIME_MILLIS - 60_000L,
            lastMatchedAtEpochMillis = START_TIME_MILLIS - 30_000L,
            coverage = TrailWalkCoverage().withRecordedMatch(0.0).withRecordedMatch(200.0),
            routeDistanceMeters = hike.distanceMeters,
            routeRevision = TrailWalkRecord.routeRevision(hike.route)
        )
        hikeRepository.seedOpenWalk(recentRecord)
        val controller = createController()
        controller.restoreAtLaunch()
        assertThat(controller.sessionState.value.walkedHikeId).isEqualTo(hike.id)
        assertThat(controller.sessionState.value.phase).isEqualTo(TrailWalkPhase.FOLLOWING)

        // Stale > 24h walk closes as ABANDONED on launch at its lastActivityAt.
        val staleController = createController()
        val staleTime = START_TIME_MILLIS - TrailWalkPolicy.STALE_AT_LAUNCH_AFTER_MILLIS - 60_000L
        hikeRepository.seedOpenWalk(
            recentRecord.copy(
                startedAtEpochMillis = staleTime - 600_000L,
                lastMatchedAtEpochMillis = staleTime
            )
        )
        staleController.restoreAtLaunch()
        assertThat(staleController.sessionState.value.isWalkActive).isFalse()
        val savedWalk = hikeRepository.observeWalks(hike.id).first().first()
        assertThat(savedWalk.endReason).isEqualTo(TrailWalkEndReason.ABANDONED)
        assertThat(savedWalk.endedAtEpochMillis).isEqualTo(staleTime)
    }

    @Test
    fun recordingActiveBlocksStartingWalkAndOutranksFollowingNotification() = runTest {
        val controller = createController()
        controller.attachForegroundHike(hike, profile)
        recordingController.start()

        assertThat(controller.startByHand(hike.id)).isFalse()
        assertThat(controller.sessionState.value.isWalkActive).isFalse()
    }

    @Test
    fun deniesStartResumeAndPausesRestoredWalkWhenFineLocationMissing() = runTest {
        val controller = createController()
        controller.attachForegroundHike(hike, profile)

        locationFeed.setPermission(LocationPermissionStatus.DENIED)
        assertThat(controller.startByHand(hike.id)).isFalse()
        assertThat(serviceBridge.ensureStartedCount).isEqualTo(0)
        assertThat(controller.sessionState.value.permissionIssue).isEqualTo(TrailWalkPermissionIssue.LOCATION_DENIED)

        locationFeed.setPermission(LocationPermissionStatus.GRANTED_COARSE)
        assertThat(controller.startByHand(hike.id)).isFalse()
        assertThat(serviceBridge.ensureStartedCount).isEqualTo(0)
        assertThat(controller.sessionState.value.permissionIssue)
            .isEqualTo(TrailWalkPermissionIssue.PRECISE_LOCATION_REQUIRED)

        // Start cleanly with fine permission, pause, then revoke before resume.
        locationFeed.setPermission(LocationPermissionStatus.GRANTED_FINE)
        assertThat(controller.startByHand(hike.id)).isTrue()
        assertThat(controller.pause()).isTrue()
        val startsBeforeDeniedResume = serviceBridge.ensureStartedCount
        locationFeed.setPermission(LocationPermissionStatus.DENIED)
        assertThat(controller.resume()).isFalse()
        assertThat(controller.sessionState.value.phase).isEqualTo(TrailWalkPhase.PAUSED)
        assertThat(serviceBridge.ensureStartedCount).isEqualTo(startsBeforeDeniedResume)
        assertThat(controller.sessionState.value.permissionIssue).isEqualTo(TrailWalkPermissionIssue.LOCATION_DENIED)

        // Restoring an open FOLLOWING walk with coarse-only permission pauses safely without starting FGS.
        val openRecord = TrailWalkRecord(
            hikeId = hike.id.value,
            startedAtEpochMillis = START_TIME_MILLIS - 60_000L,
            lastMatchedAtEpochMillis = START_TIME_MILLIS - 10_000L,
            routeDistanceMeters = hike.distanceMeters,
            routeRevision = TrailWalkRecord.routeRevision(hike.route),
            walkId = "restored-coarse-walk"
        )
        hikeRepository.seedOpenWalk(openRecord)
        val restoredController = createController()
        val startsBeforeRestore = serviceBridge.ensureStartedCount
        locationFeed.setPermission(LocationPermissionStatus.GRANTED_COARSE)
        restoredController.restoreAtLaunch()
        assertThat(restoredController.sessionState.value.phase).isEqualTo(TrailWalkPhase.PAUSED)
        assertThat(serviceBridge.ensureStartedCount).isEqualTo(startsBeforeRestore)
        assertThat(restoredController.sessionState.value.permissionIssue)
            .isEqualTo(TrailWalkPermissionIssue.PRECISE_LOCATION_REQUIRED)
    }

    @Test
    fun publishesWalkSessionBeforeStartingForegroundService() = runTest {
        val controller = createController()
        controller.attachForegroundHike(hike, profile)
        val observedPhases = mutableListOf<TrailWalkPhase?>()
        serviceBridge.onEnsureStarted = { observedPhases.add(controller.sessionState.value.phase) }

        // Manual start + resume must publish FOLLOWING before ensureStarted().
        assertThat(controller.startByHand(hike.id)).isTrue()
        assertThat(controller.pause()).isTrue()
        assertThat(controller.resume()).isTrue()
        assertThat(controller.endWalk()).isEqualTo(TrailWalkEnd.Discarded)
        controller.onAutoFollowChanged(hike.id, true)

        // Automatic start must also publish FOLLOWING before ensureStarted().
        for (coord in TestFixtures.TRAIL_POINTS.take(4)) {
            emitCoordinate(coord, stepMillis = 2_000L)
        }
        assertThat(controller.sessionState.value.phase).isEqualTo(TrailWalkPhase.FOLLOWING)
        assertThat(observedPhases).containsExactly(
            TrailWalkPhase.FOLLOWING,
            TrailWalkPhase.FOLLOWING,
            TrailWalkPhase.FOLLOWING
        )
    }

    @Test
    fun restoreAtLaunchClearsOpenWalkAlreadyCommittedToHistory() = runTest {
        val record = TrailWalkRecord(
            hikeId = hike.id.value,
            startedAtEpochMillis = START_TIME_MILLIS - 120_000L,
            lastMatchedAtEpochMillis = START_TIME_MILLIS - 10_000L,
            coverage = TrailWalkCoverage().withRecordedMatch(0.0).withRecordedMatch(250.0),
            routeDistanceMeters = hike.distanceMeters,
            routeRevision = TrailWalkRecord.routeRevision(hike.route),
            walkId = "already-finished-walk"
        )
        hikeRepository.finishWalk(record, START_TIME_MILLIS - 5_000L, TrailWalkEndReason.ENDED)
        hikeRepository.seedOpenWalk(record)

        val controller = createController()
        controller.restoreAtLaunch()

        assertThat(controller.sessionState.value.isWalkActive).isFalse()
        assertThat(serviceBridge.ensureStartedCount).isEqualTo(0)
        assertThat(hikeRepository.getOpenWalksInProgress().getOrThrow()).isEmpty()
        assertThat(hikeRepository.observeWalks(hike.id).first()).hasSize(1)
    }

    @Test
    fun restoresPreviewLocationFeedWhenRecordingRelinquishesIt() = runTest {
        val controller = createController()
        controller.attachForegroundHike(hike, profile)
        assertThat(locationFeed.activeProfile.value).isNotNull()

        recordingController.start()
        locationFeed.stopUpdates()
        recordingController.discard()

        assertThat(locationFeed.activeProfile.value).isNotNull()
    }

    @Test
    fun ignoresStaleAndLowConfidenceFixesForAutoStartAndGuidance() = runTest {
        val controller = createController()
        controller.attachForegroundHike(hike, profile)

        // Four on-trail fixes with 350 m horizontal uncertainty and 0 speed must not start a walk.
        for (coord in TestFixtures.TRAIL_POINTS.take(4)) {
            clock.advanceBy(java.time.Duration.ofSeconds(2))
            locationFeed.emitFix(
                LocationFix(
                    latitude = coord.latitude,
                    longitude = coord.longitude,
                    timestampEpochMillis = clock.now().toEpochMilli(),
                    horizontalAccuracyMeters = 350.0,
                    speedMps = 0.0
                )
            )
        }
        assertThat(controller.sessionState.value.isWalkActive).isFalse()
        assertThat(controller.progressState.value.followMarkerDistanceMeters).isNull()

        // Four stale fixes (> 30 s old) with good accuracy must also be ignored.
        for (coord in TestFixtures.TRAIL_POINTS.take(4)) {
            clock.advanceBy(java.time.Duration.ofSeconds(2))
            locationFeed.emitFix(
                LocationFix(
                    latitude = coord.latitude,
                    longitude = coord.longitude,
                    timestampEpochMillis = clock.now().toEpochMilli() - 60_000L,
                    horizontalAccuracyMeters = 5.0,
                    speedMps = 1.4
                )
            )
        }
        assertThat(controller.sessionState.value.isWalkActive).isFalse()
    }

    @Test
    fun onHikeRouteChangedEndsActiveWalkWithRouteChangedReason() = runTest {
        val controller = createController()
        controller.attachForegroundHike(hike, profile)
        assertThat(controller.startByHand(hike.id)).isTrue()
        assertThat(controller.sessionState.value.isWalkActive).isTrue()

        // Advance along the trail so the walk has recorded progress before the route changes.
        for (coord in TestFixtures.TRAIL_POINTS) {
            emitCoordinate(coord, stepMillis = 15_000L)
        }

        controller.onHikeRouteChanged(hike.id, newRoute = hike.route.dropLast(1))

        assertThat(controller.sessionState.value.isWalkActive).isFalse()
        assertThat(hikeRepository.savedWalks().last().endReason).isEqualTo(TrailWalkEndReason.ROUTE_CHANGED)
    }

    private fun TestScope.createController(): DefaultTrailWalkController = DefaultTrailWalkController(
        clock = clock,
        hikeRepository = hikeRepository,
        userSettingsRepository = settingsRepository,
        locationFeed = locationFeed,
        recordingController = recordingController,
        notificationController = notificationController,
        serviceBridge = serviceBridge,
        junctionProvider = object : TrailJunctionProvider {
            override suspend fun junctionsAlong(profile: RouteProfile): List<TrailJunction> = stubJunctions
        },
        junctionVoice = voice,
        applicationScope = backgroundScope.let { scope ->
            kotlinx.coroutines.CoroutineScope(scope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        }
    )

    private suspend fun emitCoordinate(coordinate: GeoCoordinate, stepMillis: Long) {
        clock.advanceBy(java.time.Duration.ofMillis(stepMillis))
        locationFeed.emitFix(
            LocationFix(
                latitude = coordinate.latitude,
                longitude = coordinate.longitude,
                timestampEpochMillis = clock.now().toEpochMilli(),
                horizontalAccuracyMeters = 6.0,
                elevationMeters = 535.0,
                speedMps = 1.4
            )
        )
    }

    private class RecordingJunctionVoice : JunctionVoice {
        val messages = ArrayList<String>()

        override fun speak(message: String) {
            messages.add(message)
        }

        override fun stopSpeaking() = Unit
    }

    private class RecordingNotificationPoster : NotificationPoster {
        var postCount: Int = 0
        var cancelCount: Int = 0

        override fun canPostPromotedNotifications(): Boolean = true

        override fun post(notificationId: Int, notification: android.app.Notification) {
            postCount += 1
        }

        override fun cancel(notificationId: Int) {
            cancelCount += 1
        }
    }

    @Suppress("TooManyFunctions")
    private class WalkTestHikeRepository(initialHikes: List<Hike>) : app.openhikes.core.data.HikeRepository {
        private val hikesFlow = kotlinx.coroutines.flow.MutableStateFlow(initialHikes)
        private val walksFlow = kotlinx.coroutines.flow.MutableStateFlow<List<app.openhikes.core.model.HikeWalk>>(
            emptyList()
        )
        private val openWalks = LinkedHashMap<HikeId, TrailWalkRecord>()
        var nextPersistFailure: Throwable? = null
        var nextFinishFailure: Throwable? = null

        fun seedOpenWalk(record: TrailWalkRecord) {
            openWalks[HikeId(record.hikeId)] = record
        }

        fun savedWalks(): List<app.openhikes.core.model.HikeWalk> = walksFlow.value

        override val hikes: kotlinx.coroutines.flow.Flow<List<app.openhikes.core.model.HikeSummary>> =
            kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        override val mapRoutes: kotlinx.coroutines.flow.Flow<List<app.openhikes.core.model.HikeMapRoute>> =
            kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        override val libraryLines: kotlinx.coroutines.flow.Flow<List<app.openhikes.core.model.LibraryLine>> =
            kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        override val totalsInvalidation: kotlinx.coroutines.flow.Flow<Long> =
            kotlinx.coroutines.flow.MutableStateFlow(0L)

        override fun observeHike(hikeId: HikeId) = kotlinx.coroutines.flow.flowOf(
            hikesFlow.value.firstOrNull { it.id == hikeId }
        )

        override fun observeLocalState(hikeId: HikeId) = kotlinx.coroutines.flow.flowOf(null)

        override fun observeWalks(hikeId: HikeId) = kotlinx.coroutines.flow.flowOf(
            walksFlow.value.filter { it.hikeId == hikeId }
        )

        override fun observeWalk(walkId: String) = kotlinx.coroutines.flow.flowOf(
            walksFlow.value.firstOrNull { it.id == walkId }
        )

        override suspend fun importGpxBytes(bytes: ByteArray, fileName: String?) = error("Unused")
        override suspend fun importGpxUri(uriString: String) = error("Unused")
        override suspend fun exportHike(hikeId: HikeId) = error("Unused")
        override suspend fun exportHikeToUri(hikeId: HikeId, uriString: String) = error("Unused")
        override suspend fun renameHike(hikeId: HikeId, draftTitle: String) = error("Unused")
        override suspend fun updateRouteStyle(hikeId: HikeId, style: app.openhikes.core.model.RouteStyleUpdate) =
            error("Unused")
        override suspend fun setAutoFollowEnabled(hikeId: HikeId, enabled: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun saveWalkInProgress(hikeId: HikeId, record: TrailWalkRecord?): Result<Unit> {
            nextPersistFailure?.let {
                nextPersistFailure = null
                return Result.failure(it)
            }
            if (record == null) openWalks.remove(hikeId) else openWalks[hikeId] = record
            return Result.success(Unit)
        }

        override suspend fun getOpenWalksInProgress(): Result<List<TrailWalkRecord>> =
            Result.success(openWalks.values.toList())

        override suspend fun finishWalk(
            record: TrailWalkRecord,
            endedAtEpochMillis: Long,
            reason: TrailWalkEndReason
        ): Result<app.openhikes.core.model.HikeWalk?> {
            nextFinishFailure?.let {
                nextFinishFailure = null
                return Result.failure(it)
            }
            openWalks.remove(HikeId(record.hikeId))
            val walk = if (record.coverage.meetsMinimum) {
                app.openhikes.core.model.HikeWalk(
                    id = record.durableWalkId,
                    hikeId = HikeId(record.hikeId),
                    startedAtEpochMillis = record.startedAtEpochMillis,
                    endedAtEpochMillis = endedAtEpochMillis,
                    distanceMeters = record.coverage.coveredMeters,
                    durationSeconds = record.activeSeconds(endedAtEpochMillis),
                    coveredIntervals = record.coverage.intervals,
                    furthestDistanceMeters = record.coverage.furthestDistanceMeters,
                    routeDistanceMeters = record.routeDistanceMeters,
                    endReason = reason,
                    routeRevision = record.routeRevision
                ).also { saved ->
                    walksFlow.value = walksFlow.value.filterNot { it.id == saved.id } + saved
                }
            } else {
                null
            }
            return Result.success(walk)
        }

        override suspend fun deleteHike(hikeId: HikeId) = error("Unused")
        override suspend fun saveDrawnTrail(
            title: String,
            distanceMeters: Double,
            route: List<app.openhikes.core.model.RouteCoordinate>,
            madeOnEpochMillis: Long
        ) = error("Unused")
        override suspend fun createOrClaimRecordingDraft(hikeId: HikeId, startedAtEpochMillis: Long, title: String) =
            error("Unused")
        override suspend fun finalizeRecordingDraft(
            hikeId: HikeId,
            title: String,
            customName: String?,
            startedAtEpochMillis: Long,
            endedAtEpochMillis: Long,
            prepared: app.openhikes.core.model.PreparedRecording
        ) = error("Unused")
        override suspend fun deleteRecordingDraft(hikeId: HikeId) = error("Unused")
        override suspend fun deleteOwnedOrphanedRecordingDrafts(exceptHikeId: HikeId?) = error("Unused")
        override suspend fun loadRouteEditSources(hikeId: HikeId) = error("Unused")
        override suspend fun applyRouteEdit(
            hikeId: HikeId,
            expectedCurrentRoute: List<app.openhikes.core.model.RouteCoordinate>,
            edited: app.openhikes.core.model.EditedLine
        ) = error("Unused")
        override suspend fun revertRouteEdit(
            hikeId: HikeId,
            expectedCurrentRoute: List<app.openhikes.core.model.RouteCoordinate>
        ) = error("Unused")
        override suspend fun addPlace(hikeId: HikeId, place: app.openhikes.core.model.TrailPoint) = Result.success(true)
        override suspend fun editPlace(
            hikeId: HikeId,
            placeId: String,
            name: String,
            symbol: app.openhikes.core.model.TrailPlaceSymbol?,
            note: String
        ) = Result.success(true)
        override suspend fun removePlace(hikeId: HikeId, placeId: String) = Result.success(Unit)
        override suspend fun saveCustomHikeOrder(orderedIds: List<HikeId>): Result<Unit> = Result.success(Unit)
        override suspend fun clearCustomHikeOrder(): Result<Unit> = Result.success(Unit)
        override suspend fun validateElevationMetrics(hikeIds: List<HikeId>): Result<Boolean> = Result.success(false)
        override suspend fun computeLibraryTotals(
            asOfEpochMillis: Long,
            zoneId: java.time.ZoneId
        ): Result<app.openhikes.core.model.LibraryTotals> = error("Unused")
        override suspend fun addCameraPhoto(
            hikeId: HikeId,
            rawBytes: ByteArray,
            capturedAtEpochMillis: Long?,
            coordinate: GeoCoordinate?,
            placeId: String?,
            saveToSystemLibrary: Boolean
        ) = error("Unused")
        override suspend fun addPickedPhoto(
            hikeId: HikeId,
            bytes: ByteArray,
            assetLocalIdentifier: String?,
            fallbackCoordinate: GeoCoordinate?,
            placeId: String?
        ) = error("Unused")
        override suspend fun addPickedPhotoUri(
            hikeId: HikeId,
            uriString: String,
            fallbackCoordinate: GeoCoordinate?,
            placeId: String?
        ) = error("Unused")
        override suspend fun addDiscoveredPhoto(
            hikeId: HikeId,
            bytes: ByteArray,
            match: app.openhikes.core.model.LibraryPhotoMatch,
            expectedPlanFingerprint: Long
        ) = error("Unused")
        override suspend fun removePhoto(hikeId: HikeId, photoId: String) = Result.success(Unit)
        override suspend fun readPhotoBytes(photo: app.openhikes.core.model.HikePhoto): ByteArray? = null
        override suspend fun readPhotoThumbnailBytes(photo: app.openhikes.core.model.HikePhoto): ByteArray? = null
        override suspend fun hasPhotoFile(photo: app.openhikes.core.model.HikePhoto): Boolean = false
        override suspend fun prepareSharePhotoFile(
            hikeTitle: String,
            positionOneBased: Int,
            photo: app.openhikes.core.model.HikePhoto
        ) = error("Unused")
        override suspend fun exportHikeArchive(hikeId: HikeId) = error("Unused")
        override suspend fun exportHikeArchiveToUri(hikeId: HikeId, uriString: String) = error("Unused")
        override suspend fun measurePhotoStorageBytes(): Long = 0L
        override suspend fun reclaimOrphanedPhotos(): Result<Int> = Result.success(0)
        override suspend fun seedFixtureIfConfigured(): HikeId? = null
    }

    private companion object {
        const val START_TIME_MILLIS = 1_780_000_000_000L
    }
}
