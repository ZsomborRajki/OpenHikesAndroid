package app.openhikes.shell

import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.data.PhotoMetadataCodec
import app.openhikes.core.data.SeededPhotoGenerator
import app.openhikes.core.map.MapCameraTracker
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.PhotoCaptureController
import app.openhikes.core.map.RouteEditOverlayState
import app.openhikes.core.map.RouteHighlightState
import app.openhikes.core.map.TrailDraftOverlayState
import app.openhikes.core.map.TrailPhotosOverlayState
import app.openhikes.core.map.TrailPlacesOverlayState
import app.openhikes.core.map.WalkOverlayState
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.MapProviderKeys
import app.openhikes.core.testing.FakeTrailGraphProvider
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.android.FakeHikeRepository
import app.openhikes.core.testing.android.FakeOfflineCountryRepository
import app.openhikes.core.testing.android.FakeRecordingController
import app.openhikes.core.testing.android.FakeTrailWalkController
import app.openhikes.core.testing.android.FakeUserSettingsRepository
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MapShellPhotoCaptureTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun cameraCaptureResolvesRecordingAnchorWithExifShutterTimeAndFallsBackWhenExifAbsent() = runTest {
        val recordingController = FakeRecordingController()
        recordingController.start()
        val recordingHikeId = HikeId(requireNotNull(recordingController.state.value.sessionId))
        val exifShutterMillis = 1_781_510_460_000L
        val callbackFallbackMillis = 1_781_510_700_000L
        val exifPoint = GeoCoordinate(47.7185, 12.8318)
        val fallbackPoint = GeoCoordinate(47.7215, 12.8350)
        recordingController.photoCoordinateLookup = { shutterMillis ->
            when (shutterMillis) {
                exifShutterMillis -> exifPoint
                callbackFallbackMillis -> fallbackPoint
                else -> null
            }
        }
        val repo = FakeHikeRepository(listOf(recordingDraftHike(recordingHikeId)))
        val viewModel = createShellViewModel(repo, recordingController)

        viewModel.onCameraPhotoCaptured(
            hikeId = recordingHikeId,
            placeId = null,
            bytes = createExifStampedJpeg(exifShutterMillis),
            fallbackCapturedAtMillis = callbackFallbackMillis
        )
        advanceUntilIdle()

        val firstPhoto = repo.observeHike(recordingHikeId).first()!!.photos.single()
        assertThat(recordingController.lastPhotoCoordinateShutterMillis).isEqualTo(exifShutterMillis)
        assertThat(firstPhoto.capturedAtEpochMillis).isEqualTo(exifShutterMillis)
        assertThat(firstPhoto.coordinate).isEqualTo(exifPoint)

        // When EXIF omits capture time, callback fallback timestamp is used for anchor and saved time.
        viewModel.onCameraPhotoCaptured(
            hikeId = recordingHikeId,
            placeId = null,
            bytes = SeededPhotoGenerator.generateJpeg(2),
            fallbackCapturedAtMillis = callbackFallbackMillis
        )
        advanceUntilIdle()

        val photos = repo.observeHike(recordingHikeId).first()!!.photos
        assertThat(photos).hasSize(2)
        val secondPhoto = photos.last()
        assertThat(recordingController.lastPhotoCoordinateShutterMillis).isEqualTo(callbackFallbackMillis)
        assertThat(secondPhoto.capturedAtEpochMillis).isEqualTo(callbackFallbackMillis)
        assertThat(secondPhoto.coordinate).isEqualTo(fallbackPoint)
    }

    private fun createExifStampedJpeg(capturedAtEpochMillis: Long): ByteArray {
        val stampedFile = File.createTempFile("shell-camera-exif-", ".jpg")
        return try {
            stampedFile.writeBytes(SeededPhotoGenerator.generateJpeg(1))
            assertThat(
                PhotoMetadataCodec.stampCaptureMetadata(
                    file = stampedFile,
                    capturedAtEpochMillis = capturedAtEpochMillis,
                    coordinate = null,
                    zone = ZoneOffset.UTC
                )
            ).isTrue()
            stampedFile.readBytes()
        } finally {
            stampedFile.delete()
        }
    }

    private fun recordingDraftHike(hikeId: HikeId): Hike = Hike(
        id = hikeId,
        title = "Recording Draft",
        distanceMeters = 0.0,
        dateEpochMillis = 1_781_510_400_000L,
        isRecording = true
    )

    private fun createShellViewModel(
        repo: FakeHikeRepository,
        recordingController: FakeRecordingController
    ): MapShellViewModel = MapShellViewModel(
        userSettingsRepository = FakeUserSettingsRepository(),
        hikeRepository = repo,
        offlineCountryRepository = FakeOfflineCountryRepository(),
        mapClaimCoordinator = MapClaimCoordinator(),
        recordingController = recordingController,
        trailWalkController = FakeTrailWalkController(),
        routeHighlightState = RouteHighlightState(),
        walkOverlayState = WalkOverlayState(),
        routeEditOverlayState = RouteEditOverlayState(),
        trailDraftOverlayState = TrailDraftOverlayState(),
        mapCameraTracker = MapCameraTracker(),
        trailPlacesOverlayState = TrailPlacesOverlayState(),
        trailPhotosOverlayState = TrailPhotosOverlayState(),
        photoCaptureController = PhotoCaptureController(),
        trailGraphProvider = FakeTrailGraphProvider(isAvailable = false),
        mapProviderKeys = MapProviderKeys.Empty,
        launchConfigHolder = LaunchConfigHolder(LaunchConfig.Production),
        defaultDispatcher = mainDispatcherRule.testDispatcher
    )
}
