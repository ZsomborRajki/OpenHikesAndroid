package app.openhikes.feature.trailmaker

import app.openhikes.core.common.HikeTitle
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.TrailDraftDroppedPin
import app.openhikes.core.model.TrailDraftEngine
import app.openhikes.core.model.TrailDraftRefusal
import app.openhikes.core.model.TrailDraftSelection
import app.openhikes.core.model.TrailStopSlot

/**
 * Snapshot parameters supplied by [TrailMakerViewModel] to project immutable [TrailMakerUiState].
 */
internal data class TrailMakerUiProjectionInput(
    val engine: TrailDraftEngine,
    val distanceUnit: DistanceUnit,
    val canSnapToPaths: Boolean,
    val isRestoring: Boolean,
    val isSaving: Boolean,
    val showCloseDialog: Boolean,
    val showNameDialog: Boolean,
    val namingStartedAtEpochMillis: Long?,
    val nameDraft: String,
    val refusal: TrailDraftRefusal?,
    val stopSearch: TrailStopSearchUiState?,
    val droppedPin: TrailDraftDroppedPin?,
    val selection: TrailDraftSelection?,
    val placeCardAddress: String?
)

/**
 * Pure mapper converting [TrailDraftEngine] and session chrome into [TrailMakerUiState].
 */
internal object TrailMakerUiStateMapper {
    fun map(input: TrailMakerUiProjectionInput): TrailMakerUiState {
        val engine = input.engine
        val rows = buildStopRows(engine)
        return TrailMakerUiState(
            travelMode = engine.travelMode,
            rows = rows,
            distanceMeters = engine.distanceMeters,
            travelTimeSeconds = if (engine.canBeSaved) engine.travelTimeSeconds else null,
            distanceUnit = input.distanceUnit,
            canBeSaved = engine.canBeSaved,
            isEmpty = engine.isEmpty,
            canSnapToPaths = input.canSnapToPaths,
            snapsToPaths = engine.snapsToPaths,
            isRouting = engine.isRouting,
            hasRetryableLegs = engine.hasRetryableLegs,
            worstLegSnap = engine.worstLegSnap,
            isRestoring = input.isRestoring,
            isSaving = input.isSaving,
            showCloseDialog = input.showCloseDialog,
            showNameDialog = input.showNameDialog,
            namePlaceholder = input.namingStartedAtEpochMillis?.let { HikeTitle.drawn(null, it) }.orEmpty(),
            nameDraft = input.nameDraft,
            refusal = input.refusal,
            stopSearch = input.stopSearch,
            placeCard = buildPlaceCard(engine, input.droppedPin, input.selection, input.placeCardAddress)
        )
    }

    private fun buildStopRows(engine: TrailDraftEngine): List<TrailStopRowItem> =
        engine.slots.mapIndexed { position, slot ->
            when (slot) {
                is TrailStopSlot.Open -> TrailStopRowItem(
                    slotId = slot.id,
                    position = position,
                    isOpen = true,
                    waypointId = null,
                    role = slot.role,
                    name = "",
                    distanceMeters = 0.0,
                    arrivingSnap = null,
                    pointNumber = null,
                    canMoveUp = false,
                    canMoveDown = false
                )
                is TrailStopSlot.Point -> {
                    val wp = engine.waypoints[slot.waypointIndex]
                    TrailStopRowItem(
                        slotId = slot.id,
                        position = position,
                        isOpen = false,
                        waypointId = wp.id,
                        role = engine.roleOfWaypointAt(slot.waypointIndex),
                        name = wp.name,
                        distanceMeters = engine.distanceAlongLineToWaypointAt(slot.waypointIndex),
                        arrivingSnap = engine.legArrivingAtWaypointAt(slot.waypointIndex)?.snap,
                        pointNumber = slot.waypointIndex + 1,
                        canMoveUp = slot.waypointIndex > 0,
                        canMoveDown = slot.waypointIndex < engine.waypoints.lastIndex
                    )
                }
            }
        }

    private fun buildPlaceCard(
        engine: TrailDraftEngine,
        droppedPin: TrailDraftDroppedPin?,
        selection: TrailDraftSelection?,
        placeCardAddress: String?
    ): TrailPlaceCardState? = when (selection) {
        null -> null
        TrailDraftSelection.DroppedPin -> droppedPin?.let { spot ->
            TrailPlaceCardState(
                isDroppedPin = true,
                waypointId = null,
                role = null,
                title = spot.name.ifBlank { "Dropped Pin" },
                nameForStop = spot.name,
                distanceAlongRouteMeters = null,
                coordinate = spot.coordinate,
                coordinatesText = TrailMakerOverlayBuilder.formatCoordinates(spot.coordinate),
                address = placeCardAddress,
                preferredLegEnds = spot.legEnds
            )
        }
        is TrailDraftSelection.Stop -> {
            val index = engine.waypoints.indexOfFirst { it.id == selection.waypointId }
            if (index < 0) {
                null
            } else {
                val wp = engine.waypoints[index]
                val role = engine.roleOfWaypointAt(index)
                TrailPlaceCardState(
                    isDroppedPin = false,
                    waypointId = wp.id,
                    role = role,
                    title = wp.name.ifBlank { TrailMakerOverlayBuilder.roleFallbackLabel(role) },
                    nameForStop = wp.name,
                    distanceAlongRouteMeters = engine.distanceAlongLineToWaypointAt(index).takeIf { it > 0.0 },
                    coordinate = wp.coordinate,
                    coordinatesText = TrailMakerOverlayBuilder.formatCoordinates(wp.coordinate),
                    address = placeCardAddress,
                    preferredLegEnds = null
                )
            }
        }
    }
}
