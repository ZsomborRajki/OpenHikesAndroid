package app.openhikes.feature.offlinemaps

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.openhikes.core.designsystem.theme.OpenHikesTheme
import app.openhikes.core.model.InstalledCountryRecord
import app.openhikes.core.model.OfflineBounds
import app.openhikes.core.model.OfflineCountriesState
import app.openhikes.core.model.OfflineCountry
import app.openhikes.core.model.OfflineCountryDirectoryBuilder
import app.openhikes.core.model.OfflineCountryDownload
import app.openhikes.core.model.OfflineCountryFile
import app.openhikes.core.model.OfflineCountrySort
import app.openhikes.core.model.OfflineDownloadFailure
import app.openhikes.core.model.OfflineDownloadPhase
import app.openhikes.core.model.OfflineFileRole
import app.openhikes.core.model.OfflineListPhase
import com.android.tools.screenshot.PreviewTest
import java.util.Locale

@PreviewTest
@Preview(name = "Offline Maps Catalog Light", showBackground = true, widthDp = 411, heightDp = 760)
@Composable
fun OfflineMapsCatalogLightPreview() {
    OfflineMapsPreviewHost(darkTheme = false, uiState = sampleCatalogUiState())
}

@PreviewTest
@Preview(name = "Offline Maps Downloading Dark", showBackground = true, widthDp = 411, heightDp = 760)
@Composable
fun OfflineMapsDownloadingDarkPreview() {
    OfflineMapsPreviewHost(darkTheme = true, uiState = sampleActiveDownloadsUiState())
}

@PreviewTest
@Preview(name = "Offline Maps Load Error Light", showBackground = true, widthDp = 411, heightDp = 700)
@Composable
fun OfflineMapsLoadErrorLightPreview() {
    OfflineMapsPreviewHost(
        darkTheme = false,
        uiState = OfflineMapsUiState(
            isAvailable = true,
            isPrepared = true,
            listPhase = OfflineListPhase.FAILED
        )
    )
}

@PreviewTest
@Preview(name = "Offline Maps Large Font 200", showBackground = true, widthDp = 411, heightDp = 891, fontScale = 2.0f)
@Composable
fun OfflineMapsLargeFontPreview() {
    OfflineMapsPreviewHost(darkTheme = false, uiState = sampleActiveDownloadsUiState())
}

@Composable
private fun OfflineMapsPreviewHost(darkTheme: Boolean, uiState: OfflineMapsUiState) {
    OpenHikesTheme(darkTheme = darkTheme, dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            OfflineMapsScreen(
                uiState = uiState,
                onBack = {},
                onSearchQueryChange = {},
                onClearSearch = {},
                onSortChange = {},
                onToggleGroup = {},
                onSelectCountry = {},
                onSetIncludesDetail = {},
                onUseOpenHikesMap = {},
                onDownload = {},
                onRetry = {},
                onRepair = {},
                onCancel = {},
                onDelete = {},
                onRefresh = {}
            )
        }
    }
}

private fun sampleCountries(): List<OfflineCountry> = listOf(
    previewCountry("AT", baseBytes = 735_373_771L, detailBytes = 731_274_809L),
    previewCountry("DE", baseBytes = 2_734_096_642L, detailBytes = 2_800_123_519L),
    previewCountry("FR", baseBytes = 3_023_591_526L, detailBytes = 3_127_066_993L),
    previewCountry("SI", baseBytes = 197_772_131L, detailBytes = 198_529_057L),
    previewCountry("US-CO", group = "US", name = "Colorado", baseBytes = 595_070_538L, detailBytes = 613_619_169L),
    previewCountry("US-WA", group = "US", name = "Washington", baseBytes = 695_020_779L, detailBytes = 716_318_242L)
)

private fun sampleCatalogUiState(): OfflineMapsUiState {
    val countries = sampleCountries()
    val state = OfflineCountriesState(
        isPrepared = true,
        listPhase = OfflineListPhase.LOADED,
        offered = countries,
        includesDetail = true
    )
    val dir = OfflineCountryDirectoryBuilder.build(state, OfflineCountrySort.NAME, locale = Locale.US)
    return OfflineMapsUiState(
        isAvailable = true,
        isPrepared = true,
        listPhase = OfflineListPhase.LOADED,
        sort = OfflineCountrySort.NAME,
        availableSorts = listOf(OfflineCountrySort.NAME, OfflineCountrySort.LARGEST),
        includesDetail = true,
        selectedCountryId = "AT",
        expandedGroups = setOf("US"),
        unfinished = dir.unfinished,
        downloaded = dir.downloaded,
        sections = dir.sections
    )
}

private fun sampleActiveDownloadsUiState(): OfflineMapsUiState {
    val countries = sampleCountries()
    val austria = countries[0]
    val germany = countries[1]
    val france = countries[2]
    val state = OfflineCountriesState(
        isPrepared = true,
        listPhase = OfflineListPhase.LOADED,
        offered = countries,
        installed = mapOf(
            "DE" to InstalledCountryRecord(germany, installedAtEpochMillis = 1_000L, hasDetail = true)
        ),
        downloads = mapOf(
            "AT" to OfflineCountryDownload(
                country = austria,
                roles = listOf(OfflineFileRole.BASEMAP, OfflineFileRole.DETAIL),
                phase = OfflineDownloadPhase.DOWNLOADING,
                writtenBytes = 620_000_000L,
                totalBytes = 1_466_648_580L
            ),
            "FR" to OfflineCountryDownload(
                country = france,
                roles = listOf(OfflineFileRole.BASEMAP),
                phase = OfflineDownloadPhase.FAILED,
                failure = OfflineDownloadFailure.CONNECTION
            )
        ),
        includesDetail = true
    )
    val dir = OfflineCountryDirectoryBuilder.build(state, OfflineCountrySort.NAME, locale = Locale.US)
    return OfflineMapsUiState(
        isAvailable = true,
        isPrepared = true,
        listPhase = OfflineListPhase.LOADED,
        sort = OfflineCountrySort.NAME,
        availableSorts = listOf(OfflineCountrySort.NAME, OfflineCountrySort.LARGEST),
        includesDetail = true,
        unfinished = dir.unfinished,
        downloaded = dir.downloaded,
        sections = dir.sections,
        installed = state.installed,
        downloads = state.downloads
    )
}

private fun previewCountry(
    id: String,
    baseBytes: Long,
    detailBytes: Long,
    group: String? = null,
    name: String? = null
): OfflineCountry = OfflineCountry(
    id = id,
    group = group,
    names = if (name != null) mapOf("en" to name) else emptyMap(),
    build = "20261117",
    schema = 4,
    bounds = OfflineBounds(9.5, 46.4, 17.0, 49.0),
    files = listOf(
        OfflineCountryFile(
            role = OfflineFileRole.BASEMAP,
            path = "countries/$id-20261117.pmtiles",
            byteCount = baseBytes,
            sha256 = "0".repeat(64),
            minimumZoom = 0,
            maximumZoom = 14
        ),
        OfflineCountryFile(
            role = OfflineFileRole.DETAIL,
            path = "countries/$id-detail-20261117.pmtiles",
            byteCount = detailBytes,
            sha256 = "0".repeat(64),
            minimumZoom = 15,
            maximumZoom = 15
        )
    )
)
