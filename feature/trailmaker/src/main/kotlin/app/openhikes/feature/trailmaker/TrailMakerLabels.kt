package app.openhikes.feature.trailmaker

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.TrailLegGap
import app.openhikes.core.model.TrailLegOutage
import app.openhikes.core.model.TrailLegSnap
import app.openhikes.core.model.TrailStopRole
import app.openhikes.core.model.TrailTravelMode

@Composable
internal fun stopRoleLabel(role: TrailStopRole): String = when (role) {
    TrailStopRole.Start -> stringResource(R.string.trail_stop_start)
    TrailStopRole.End -> stringResource(R.string.trail_stop_destination)
    is TrailStopRole.Stop -> stringResource(R.string.trail_stop_numbered, role.number)
}

@Composable
internal fun travelModeLabel(mode: TrailTravelMode): String = when (mode) {
    TrailTravelMode.HIKING -> stringResource(R.string.trail_mode_hiking)
    TrailTravelMode.WALKING -> stringResource(R.string.trail_mode_walking)
    TrailTravelMode.CYCLING -> stringResource(R.string.trail_mode_cycling)
    TrailTravelMode.DRIVING -> stringResource(R.string.trail_mode_driving)
}

internal fun travelModeIcon(mode: TrailTravelMode): Int = when (mode) {
    TrailTravelMode.HIKING -> OpenHikesIcons.Hiking
    TrailTravelMode.WALKING -> OpenHikesIcons.DirectionsWalk
    TrailTravelMode.CYCLING -> OpenHikesIcons.DirectionsBike
    TrailTravelMode.DRIVING -> OpenHikesIcons.DirectionsCar
}

@Composable
internal fun legNoticeText(snap: TrailLegSnap): String? = when (snap) {
    TrailLegSnap.Freehand, TrailLegSnap.Snapped -> null
    TrailLegSnap.Routing -> stringResource(R.string.trail_notice_routing)
    is TrailLegSnap.Unmapped -> when (snap.gap) {
        TrailLegGap.NO_PATH_BETWEEN -> stringResource(R.string.trail_notice_no_path)
        TrailLegGap.TOO_FAR_APART -> stringResource(R.string.trail_notice_too_far)
        TrailLegGap.NO_DIRECTIONS -> stringResource(R.string.trail_notice_no_directions)
        TrailLegGap.ENDPOINT_OFF_NETWORK -> stringResource(R.string.trail_notice_off_network)
    }
    is TrailLegSnap.Refused -> when (snap.outage) {
        TrailLegOutage.OFFLINE -> stringResource(R.string.trail_notice_offline)
        TrailLegOutage.RATE_LIMITED -> stringResource(R.string.trail_notice_rate_limited)
        TrailLegOutage.UNAVAILABLE -> stringResource(R.string.trail_notice_unavailable)
    }
}

@Composable
internal fun buildStopSubtitle(isNamed: Boolean, roleLabel: String, distanceText: String?): String? {
    val along = distanceText?.let { stringResource(R.string.trail_stop_along_route, it) }
    val parts = listOfNotNull(if (isNamed) roleLabel else null, along)
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}
