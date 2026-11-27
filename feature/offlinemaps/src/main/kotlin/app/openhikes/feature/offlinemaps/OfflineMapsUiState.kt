package app.openhikes.feature.offlinemaps

import androidx.compose.runtime.Immutable
import app.openhikes.core.model.InstalledCountryRecord
import app.openhikes.core.model.OfflineCountryDownload
import app.openhikes.core.model.OfflineCountryEntry
import app.openhikes.core.model.OfflineCountryOffer
import app.openhikes.core.model.OfflineCountrySort
import app.openhikes.core.model.OfflineDirectorySection
import app.openhikes.core.model.OfflineFileRole
import app.openhikes.core.model.OfflineListPhase

/**
 * Immutable UI state rendered by [OfflineMapsScreen].
 */
@Immutable
data class OfflineMapsUiState(
    val isAvailable: Boolean = true,
    val isPrepared: Boolean = false,
    val listPhase: OfflineListPhase = OfflineListPhase.IDLE,
    val sort: OfflineCountrySort = OfflineCountrySort.NAME,
    val availableSorts: List<OfflineCountrySort> = listOf(OfflineCountrySort.NAME, OfflineCountrySort.LARGEST),
    val searchQuery: String = "",
    val includesDetail: Boolean = true,
    val includesElevation: Boolean = true,
    val showUseOpenHikesBanner: Boolean = false,
    val activeMapStyleId: String = "",
    val selectedCountryId: String? = null,
    val expandedGroups: Set<String> = emptySet(),
    val unfinished: List<OfflineCountryEntry> = emptyList(),
    val downloaded: List<OfflineCountryEntry> = emptyList(),
    val sections: List<OfflineDirectorySection> = emptyList(),
    val searchResults: List<OfflineCountryEntry> = emptyList(),
    val installed: Map<String, InstalledCountryRecord> = emptyMap(),
    val downloads: Map<String, OfflineCountryDownload> = emptyMap(),
    val additions: Map<String, OfflineCountryOffer> = emptyMap(),
    val removals: Map<String, OfflineCountryOffer> = emptyMap(),
    val unopenable: Map<String, Set<OfflineFileRole>> = emptyMap(),
    val installedByteCount: Long = 0L,
    val scrollRevealCountryId: String? = null,
    val scrollRevealTick: Long = 0L
) {
    val isSearching: Boolean
        get() = searchQuery.trim().isNotEmpty()

    val hasAnyEntries: Boolean
        get() = unfinished.isNotEmpty() || downloaded.isNotEmpty() || sections.isNotEmpty()
}
