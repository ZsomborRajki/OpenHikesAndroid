package app.openhikes.feature.walks

import androidx.compose.runtime.Immutable
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeWalk
import app.openhikes.core.model.RouteTint

/**
 * Immutable UI state for [WalkSummaryScreen].
 */
@Immutable
data class WalkSummaryUiState(
    val hikeId: HikeId,
    val walkId: String,
    val hikeTitle: String = "",
    val tintHex: String = RouteTint.DEFAULT_HEX,
    val walk: HikeWalk? = null,
    val trailAscentMeters: Double? = null,
    val distanceUnit: DistanceUnit = DistanceUnit.METRIC,
    val isRouteChanged: Boolean = false,
    val canShowOnMap: Boolean = false,
    val isLoaded: Boolean = false
)
