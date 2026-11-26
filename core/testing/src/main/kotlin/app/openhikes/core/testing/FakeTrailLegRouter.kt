package app.openhikes.core.testing

import app.openhikes.core.model.TrailLegEnds
import app.openhikes.core.model.TrailLegGap
import app.openhikes.core.model.TrailLegRoute
import app.openhikes.core.model.TrailLegRouter
import app.openhikes.core.model.TrailLegSnap
import app.openhikes.core.model.TrailTravelMode

/**
 * Pure-JVM deterministic [TrailLegRouter] fake for ViewModel and domain tests.
 */
class FakeTrailLegRouter(
    var supportedModes: Set<TrailTravelMode> = setOf(TrailTravelMode.HIKING),
    var handler: suspend (TrailLegEnds, TrailTravelMode) -> TrailLegRoute? = { ends, mode ->
        if (mode == TrailTravelMode.HIKING) {
            TrailLegRoute.straight(ends, TrailLegSnap.Snapped)
        } else {
            TrailLegRoute.straight(ends, TrailLegSnap.Unmapped(TrailLegGap.NO_DIRECTIONS))
        }
    }
) : TrailLegRouter {
    val requestedLegs = mutableListOf<Pair<TrailLegEnds, TrailTravelMode>>()

    override fun supportsMode(mode: TrailTravelMode): Boolean = supportedModes.contains(mode)

    override suspend fun route(ends: TrailLegEnds, mode: TrailTravelMode): TrailLegRoute? {
        requestedLegs.add(ends to mode)
        return handler(ends, mode)
    }
}
