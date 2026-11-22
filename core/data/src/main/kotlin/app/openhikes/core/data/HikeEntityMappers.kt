package app.openhikes.core.data

import app.openhikes.core.database.RouteBlobCodec
import app.openhikes.core.database.dao.HikeMapRouteRow
import app.openhikes.core.database.entity.HikeEntity
import app.openhikes.core.database.entity.HikeLocalStateEntity
import app.openhikes.core.database.entity.TrailPointEntity
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeLocalState
import app.openhikes.core.model.HikeMapRoute
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.TrailPoint
import kotlinx.serialization.json.Json

internal fun HikeEntity.toSummary(localState: HikeLocalStateEntity?): HikeSummary = HikeSummary(
    id = HikeId(id),
    title = title,
    customName = customName,
    distanceMeters = distanceMeters,
    durationSeconds = durationSeconds,
    dateEpochMillis = dateEpochMillis,
    tintHex = tintHex,
    routeWidthDp = routeWidthDp,
    linePattern = RouteLinePattern.fromStoredId(routeLinePatternId),
    routeBorderHex = routeBorderHex,
    isRecording = isRecording,
    routePointCount = routePointCount,
    trackDescription = trackDescription,
    author = author,
    keywords = keywords,
    climbMeters = localState?.climbMeters,
    descentMeters = localState?.descentMeters
)

internal fun HikeEntity.toDomain(routeBlob: ByteArray?, trailPoints: List<TrailPointEntity>): Hike = Hike(
    id = HikeId(id),
    title = title,
    customName = customName,
    distanceMeters = distanceMeters,
    durationSeconds = durationSeconds,
    dateEpochMillis = dateEpochMillis,
    tintHex = tintHex,
    routeWidthDp = routeWidthDp,
    linePattern = RouteLinePattern.fromStoredId(routeLinePatternId),
    routeBorderHex = routeBorderHex,
    isRecording = isRecording,
    autoFollowEnabled = autoFollowEnabled,
    trackDescription = trackDescription,
    author = author,
    keywords = keywords,
    route = routeBlob?.let(RouteBlobCodec::decodeOrEmpty).orEmpty(),
    places = trailPoints.map { it.toDomain() }
)

internal fun TrailPointEntity.toDomain(): TrailPoint = TrailPoint(
    id = id,
    hikeId = HikeId(hikeId),
    coordinate = GeoCoordinate(latitude = latitude, longitude = longitude),
    elevationMeters = elevationMeters,
    name = name,
    symbol = symbol,
    note = note
)

internal fun HikeMapRouteRow.toDomain(): HikeMapRoute = HikeMapRoute(
    hikeId = HikeId(hikeId),
    route = RouteBlobCodec.decodeOrEmpty(routeBlob),
    tintHex = tintHex,
    routeBorderHex = routeBorderHex,
    routeWidthDp = routeWidthDp,
    linePattern = RouteLinePattern.fromStoredId(routeLinePatternId)
)

internal fun HikeLocalStateEntity.toDomain(): HikeLocalState {
    val tileKeys = runCatching { Json.decodeFromString<List<String>>(autoSavedTileKeysJson) }
        .getOrDefault(emptyList())
    return HikeLocalState(
        hikeId = HikeId(hikeId),
        listOrder = listOrder,
        climbMeters = climbMeters,
        descentMeters = descentMeters,
        elevationRouteRevision = elevationRouteRevision,
        ownsRecordingDraft = ownsRecordingDraft,
        autoSavedTileKeys = tileKeys
    )
}
