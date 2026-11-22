package app.openhikes.feature.hikes

import androidx.compose.runtime.Immutable
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.GpxImportFailure
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeSummary

/**
 * Coarse UI state for the hike library screen.
 */
@Immutable
data class HikeLibraryUiState(
    val hikes: List<HikeSummary> = emptyList(),
    val distanceUnit: DistanceUnit = DistanceUnit.METRIC,
    val selectedHikeId: HikeId? = null,
    val isImporting: Boolean = false,
    val importError: GpxImportFailure? = null,
    val actionError: String? = null,
    val pendingDeleteHike: HikeSummary? = null
)

internal fun filterHikes(hikes: List<HikeSummary>, searchQuery: String): List<HikeSummary> {
    val trimmed = searchQuery.trim()
    if (trimmed.isEmpty()) return hikes
    return hikes.filter { hike ->
        hike.displayTitle.contains(trimmed, ignoreCase = true) ||
            hike.title.contains(trimmed, ignoreCase = true) ||
            (hike.author?.contains(trimmed, ignoreCase = true) == true) ||
            (hike.keywords?.contains(trimmed, ignoreCase = true) == true) ||
            (hike.trackDescription?.contains(trimmed, ignoreCase = true) == true)
    }
}
