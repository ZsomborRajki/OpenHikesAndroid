package app.openhikes.feature.trailmaker

import app.openhikes.core.data.StopPlaceSearchSource
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.TrailDraftEngine
import app.openhikes.core.model.TrailLegEnds
import app.openhikes.core.model.TrailLegRouter
import app.openhikes.core.model.TrailTravelMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Owns Trail Maker's sequential leg-routing and reverse-stop-naming queues so only one leg route
 * and one reverse lookup run at a time without racing main-actor [TrailDraftEngine] mutations.
 */
internal class TrailDraftBackgroundQueues(
    private val scope: CoroutineScope,
    private val engine: TrailDraftEngine,
    private val trailLegRouter: TrailLegRouter,
    private val stopPlaceSearchSource: StopPlaceSearchSource,
    private val onRoutingUpdate: () -> Unit,
    private val onStopNamed: () -> Unit
) {
    private var routingChannel: Channel<TrailLegEnds>? = null
    private var routingJob: Job? = null
    private val legsInFlight = HashSet<TrailLegEnds>()

    private data class NamingQuestion(val waypointId: String, val coordinate: GeoCoordinate)

    private var namingChannel: Channel<NamingQuestion>? = null
    private var namingJob: Job? = null
    private val settledNames = HashSet<NamingQuestion>()
    private val waitingNames = HashSet<NamingQuestion>()

    fun resolveLegs(retryingRefusals: Boolean) {
        val mode = engine.travelMode
        val shouldRoute = trailLegRouter.supportsMode(mode) || mode != TrailTravelMode.HIKING
        val pending = if (shouldRoute) engine.legsAwaitingRoutes(retryingRefusals) else emptyList()
        if (pending.isEmpty()) return
        engine.beginRouting(pending)
        onRoutingUpdate()
        val asking = pending.filter { legsInFlight.add(it) }
        if (asking.isNotEmpty()) {
            val channel = routingChannel ?: startRoutingConsumer(mode)
            for (ends in asking) channel.trySend(ends)
        }
    }

    fun cancelRouting() {
        routingChannel?.close()
        routingChannel = null
        routingJob?.cancel()
        routingJob = null
        legsInFlight.clear()
        engine.stopRouting()
    }

    fun enqueueUnnamedStops() {
        val wanted = engine.waypoints
            .filter { it.name.isEmpty() }
            .map { NamingQuestion(it.id, it.coordinate) }
            .filter { !settledNames.contains(it) && !waitingNames.contains(it) }
        if (wanted.isEmpty()) return
        val channel = namingChannel ?: startNamingConsumer()
        for (question in wanted) {
            waitingNames.add(question)
            channel.trySend(question)
        }
    }

    fun cancelNaming() {
        namingChannel?.close()
        namingChannel = null
        namingJob?.cancel()
        namingJob = null
        waitingNames.clear()
        settledNames.clear()
    }

    private fun startRoutingConsumer(mode: TrailTravelMode): Channel<TrailLegEnds> {
        val channel = Channel<TrailLegEnds>(Channel.UNLIMITED)
        routingChannel = channel
        routingJob = scope.launch {
            for (ends in channel) {
                val stillWaiting = engine.legs.any { it.ends == ends && it.snap.isRouting }
                if (!stillWaiting) {
                    legsInFlight.remove(ends)
                } else {
                    val routed = try {
                        trailLegRouter.route(ends, mode)
                    } finally {
                        legsInFlight.remove(ends)
                    }
                    if (routed != null) {
                        engine.applyRoute(ends, routed)
                    } else {
                        engine.abandonRouting(ends)
                    }
                    onRoutingUpdate()
                }
            }
        }
        return channel
    }

    private fun startNamingConsumer(): Channel<NamingQuestion> {
        val channel = Channel<NamingQuestion>(Channel.UNLIMITED)
        namingChannel = channel
        namingJob = scope.launch {
            for (question in channel) {
                waitingNames.remove(question)
                settledNames.add(question)
                val resolved = stopPlaceSearchSource.reverseName(question.coordinate)
                if (resolved != null && engine.describe(question.waypointId, question.coordinate, resolved)) {
                    onStopNamed()
                }
            }
        }
        return channel
    }
}
