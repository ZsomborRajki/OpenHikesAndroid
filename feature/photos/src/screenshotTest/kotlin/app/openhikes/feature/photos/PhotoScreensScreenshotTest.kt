package app.openhikes.feature.photos

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikePhoto
import app.openhikes.core.model.HikePhotoLicense
import app.openhikes.core.model.LibraryPhotoMatch
import app.openhikes.core.model.PhotoLibraryAsset
import app.openhikes.core.model.PhotoMatchEvidence
import app.openhikes.core.model.PhotoUnavailableReason
import com.android.tools.screenshot.PreviewTest

private val PreviewHikeId = HikeId("preview-hike")

private val SampleAnchoredPhoto = HikePhoto(
    id = "preview-photo-1",
    hikeId = PreviewHikeId,
    capturedAtEpochMillis = 1_750_000_000_000L,
    coordinate = GeoCoordinate(47.7195, 12.8320),
    importedAuthorName = "Bavarian Hiker",
    license = HikePhotoLicense(
        name = "CC BY-SA 4.0",
        url = "https://creativecommons.org/licenses/by-sa/4.0/",
        sourceUrl = "https://commons.wikimedia.org/wiki/File:Thumsee.jpg"
    )
)

private val SampleSecondPhoto = HikePhoto(
    id = "preview-photo-2",
    hikeId = PreviewHikeId,
    capturedAtEpochMillis = 1_750_000_600_000L,
    coordinate = GeoCoordinate(47.7212, 12.8330)
)

private fun sampleMatches(): List<LibraryPhotoMatch> = listOf(
    LibraryPhotoMatch(
        asset = PhotoLibraryAsset(
            localIdentifier = "preview-asset-1",
            createdAtEpochMillis = 1_750_000_030_000L,
            coordinate = GeoCoordinate(47.7191, 12.8319)
        ),
        coordinate = GeoCoordinate(47.7191, 12.8319),
        evidence = PhotoMatchEvidence.TIME_AND_PLACE,
        secondsFromFix = 0.0
    ),
    LibraryPhotoMatch(
        asset = PhotoLibraryAsset(
            localIdentifier = "preview-asset-2",
            createdAtEpochMillis = 1_750_000_090_000L,
            coordinate = GeoCoordinate(47.7205, 12.8326)
        ),
        coordinate = GeoCoordinate(47.7205, 12.8326),
        evidence = PhotoMatchEvidence.TIME,
        secondsFromFix = 12.0
    ),
    LibraryPhotoMatch(
        asset = PhotoLibraryAsset(
            localIdentifier = "preview-asset-3",
            createdAtEpochMillis = 1_750_000_150_000L,
            coordinate = GeoCoordinate(47.7215, 12.8335)
        ),
        coordinate = GeoCoordinate(47.7215, 12.8335),
        evidence = PhotoMatchEvidence.TIME_AND_PLACE,
        secondsFromFix = 0.0
    )
)

@PreviewTest
@Preview(name = "Photo Viewer Unavailable Dark", showBackground = true, widthDp = 411, heightDp = 760)
@Composable
fun PhotoViewerUnavailableDarkPreview() {
    OpenHikesTheme(darkTheme = true, dynamicColor = false) {
        Surface {
            HikePhotoViewerScreen(
                uiState = HikePhotoViewerUiState(
                    hikeTitle = "Thumsee Loop",
                    photos = listOf(SampleAnchoredPhoto, SampleSecondPhoto),
                    currentPhotoId = SampleAnchoredPhoto.id,
                    shareablePhotoIds = emptySet()
                ),
                loadPageContent = { PhotoPageContent.Unavailable(PhotoUnavailableReason.NOT_ON_THIS_DEVICE) },
                onAction = {},
                onBack = {},
                onOpenUrl = {}
            )
        }
    }
}

@PreviewTest
@Preview(name = "Photo Discovery Results Light", showBackground = true, widthDp = 411, heightDp = 760)
@Composable
fun PhotoDiscoveryResultsLightPreview() {
    val matches = sampleMatches()
    OpenHikesTheme(darkTheme = false, dynamicColor = false) {
        Surface {
            PhotoDiscoveryScreen(
                uiState = PhotoDiscoveryUiState(
                    phase = PhotoDiscoveryPhase.Results(
                        matches = matches,
                        selectedIds = setOf("preview-asset-1", "preview-asset-2")
                    )
                ),
                loadThumbnailBytes = { null },
                onAction = {},
                onDone = {},
                onOpenAppSettings = {}
            )
        }
    }
}

@PreviewTest
@Preview(name = "Photo Discovery Empty Dark", showBackground = true, widthDp = 411, heightDp = 760)
@Composable
fun PhotoDiscoveryEmptyDarkPreview() {
    OpenHikesTheme(darkTheme = true, dynamicColor = false) {
        Surface {
            PhotoDiscoveryScreen(
                uiState = PhotoDiscoveryUiState(
                    phase = PhotoDiscoveryPhase.Empty(isLimitedAccess = true)
                ),
                loadThumbnailBytes = { null },
                onAction = {},
                onDone = {},
                onOpenAppSettings = {}
            )
        }
    }
}

@PreviewTest
@Preview(
    name = "Photo Discovery Large Font 200%",
    showBackground = true,
    widthDp = 411,
    heightDp = 891,
    fontScale = 2.0f
)
@Composable
fun PhotoDiscoveryLargeFontPreview() {
    val matches = sampleMatches()
    OpenHikesTheme(darkTheme = false, dynamicColor = false) {
        Surface {
            PhotoDiscoveryScreen(
                uiState = PhotoDiscoveryUiState(
                    phase = PhotoDiscoveryPhase.Results(
                        matches = matches,
                        selectedIds = setOf("preview-asset-1")
                    )
                ),
                loadThumbnailBytes = { null },
                onAction = {},
                onDone = {},
                onOpenAppSettings = {}
            )
        }
    }
}
