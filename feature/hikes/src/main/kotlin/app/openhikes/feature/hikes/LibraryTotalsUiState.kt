package app.openhikes.feature.hikes

import androidx.compose.runtime.Immutable
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.LibraryTotals

/**
 * Loading lifecycle for [LibraryTotalsScreen].
 */
@Immutable
sealed interface LibraryTotalsLoadState {
    data object Loading : LibraryTotalsLoadState

    data object Error : LibraryTotalsLoadState

    data class Loaded(val totals: LibraryTotals) : LibraryTotalsLoadState
}

/**
 * Immutable UI state for the Library Totals destination.
 */
@Immutable
data class LibraryTotalsUiState(
    val loadState: LibraryTotalsLoadState = LibraryTotalsLoadState.Loading,
    val distanceUnit: DistanceUnit = DistanceUnit.METRIC
)
