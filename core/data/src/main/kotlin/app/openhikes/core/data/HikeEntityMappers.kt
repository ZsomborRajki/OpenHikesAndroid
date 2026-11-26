package app.openhikes.core.data

import app.openhikes.core.database.RouteBlobCodec
import app.openhikes.core.database.dao.HikeMapRouteChunkRow
import app.openhikes.core.database.entity.HikeEntity
import app.openhikes.core.database.entity.HikeLocalStateEntity
import app.openhikes.core.database.entity.HikePhotoEntity
import app.openhikes.core.database.entity.HikeRouteChunkEntity
import app.openhikes.core.database.entity.HikeWalkEntity
import app.openhikes.core.database.entity.TrailPointEntity
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeLocalState
import app.openhikes.core.model.HikeMapRoute
import app.openhikes.core.model.HikePhoto
import app.openhikes.core.model.HikePhotoLicense
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.model.HikeWalk
import app.openhikes.core.model.PhotoMatchEvidence
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.TrailPlaceFact
import app.openhikes.core.model.TrailPlaceOsm
import app.openhikes.core.model.TrailPoint
import app.openhikes.core.model.TrailWalkEndReason
import kotlinx.serialization.json.Json

internal fun HikeEntity.toSummary(localState: HikeLocalStateEntity?, walkCount: Int = 0): HikeSummary = HikeSummary(
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
    descentMeters = localState?.descentMeters,
    listOrder = localState?.listOrder,
    walkCount = walkCount
)

internal fun HikeEntity.toDomain(
    routeChunks: List<HikeRouteChunkEntity>,
    trailPoints: List<TrailPointEntity>,
    photos: List<HikePhotoEntity> = emptyList()
): Hike = Hike(
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
    route = RouteBlobCodec.decodeChunksOrEmpty(
        routeChunks.sortedBy { it.chunkIndex }.map { it.chunkBlob }
    ),
    places = trailPoints.map { it.toDomain() },
    photos = photos.map { it.toDomain() }
)

internal fun HikePhotoEntity.toDomain(): HikePhoto {
    val lat = latitude
    val lon = longitude
    val coordinate = if (lat != null && lon != null) {
        runCatching { GeoCoordinate(latitude = lat, longitude = lon) }.getOrNull()
    } else {
        null
    }
    val license = licenseJson?.let { raw ->
        runCatching { Json.decodeFromString<HikePhotoLicense>(raw) }.getOrNull()
    }
    return HikePhoto(
        id = id,
        hikeId = HikeId(hikeId),
        capturedAtEpochMillis = capturedAtEpochMillis,
        pathExtension = pathExtension,
        coordinate = coordinate,
        assetLocalIdentifier = assetLocalIdentifier,
        matchEvidence = PhotoMatchEvidence.fromStoredId(matchEvidenceId),
        importedFromListingId = importedFromListingId,
        importedAuthorName = importedAuthorName,
        sentToCommunityAtEpochMillis = sentToCommunityAtEpochMillis,
        isPlaceOnly = isPlaceOnly,
        placeId = placeId,
        license = license
    )
}

internal fun HikePhoto.toEntity(): HikePhotoEntity = HikePhotoEntity(
    id = id,
    hikeId = hikeId.value,
    capturedAtEpochMillis = capturedAtEpochMillis,
    pathExtension = pathExtension,
    latitude = coordinate?.latitude,
    longitude = coordinate?.longitude,
    assetLocalIdentifier = assetLocalIdentifier,
    matchEvidenceId = matchEvidence?.storedId,
    importedFromListingId = importedFromListingId,
    importedAuthorName = importedAuthorName,
    sentToCommunityAtEpochMillis = sentToCommunityAtEpochMillis,
    isPlaceOnly = isPlaceOnly,
    placeId = placeId,
    licenseJson = license?.let { Json.encodeToString(it) }
)

internal fun TrailPointEntity.toDomain(): TrailPoint {
    val osm = if (osmElementType in TrailPlaceOsm.ELEMENT_TYPES && osmElementId > 0L) {
        val facts = runCatching { Json.decodeFromString<List<TrailPlaceFact>>(osmFactsJson) }
            .getOrDefault(emptyList())
        TrailPlaceOsm(
            elementType = osmElementType,
            elementId = osmElementId,
            facts = facts,
            wikidata = TrailPlaceOsm.wikidataItem(osmWikidata)
        )
    } else {
        null
    }
    return TrailPoint(
        id = id,
        hikeId = HikeId(hikeId),
        coordinate = GeoCoordinate(latitude = latitude, longitude = longitude),
        elevationMeters = elevationMeters,
        name = name,
        symbol = symbol,
        note = note,
        osm = osm,
        createdAtEpochMillis = createdAtEpochMillis
    )
}

internal fun TrailPoint.toEntity(hikeIdValue: String, sortOrder: Int): TrailPointEntity = TrailPointEntity(
    id = id,
    hikeId = hikeIdValue,
    latitude = coordinate.latitude,
    longitude = coordinate.longitude,
    elevationMeters = elevationMeters,
    name = name,
    symbol = symbol,
    note = note,
    sortOrder = sortOrder,
    osmElementType = osm?.elementType.orEmpty(),
    osmElementId = osm?.elementId ?: 0L,
    osmFactsJson = osm?.facts?.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) } ?: "[]",
    osmWikidata = osm?.wikidata,
    createdAtEpochMillis = createdAtEpochMillis
)

internal fun List<HikeMapRouteChunkRow>.toMapRoutes(): List<HikeMapRoute> {
    if (isEmpty()) return emptyList()
    val grouped = LinkedHashMap<String, MutableList<HikeMapRouteChunkRow>>()
    for (row in this) {
        grouped.getOrPut(row.hikeId) { ArrayList() }.add(row)
    }
    return grouped.values.map { chunks ->
        val first = chunks.first()
        HikeMapRoute(
            hikeId = HikeId(first.hikeId),
            route = RouteBlobCodec.decodeChunksOrEmpty(
                chunks.sortedBy { it.chunkIndex }.map { it.chunkBlob }
            ),
            tintHex = first.tintHex,
            routeBorderHex = first.routeBorderHex,
            routeWidthDp = first.routeWidthDp,
            linePattern = RouteLinePattern.fromStoredId(first.routeLinePatternId)
        )
    }
}

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

internal fun HikeWalkEntity.toDomain(): HikeWalk {
    val intervals = runCatching { Json.decodeFromString<List<Double>>(coveredIntervalsJson) }
        .getOrDefault(emptyList())
    return HikeWalk(
        id = id,
        hikeId = HikeId(hikeId),
        startedAtEpochMillis = startedAtEpochMillis,
        endedAtEpochMillis = endedAtEpochMillis,
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        coveredIntervals = intervals,
        furthestDistanceMeters = furthestDistanceMeters,
        routeDistanceMeters = routeDistanceMeters,
        endReason = TrailWalkEndReason.fromStoredId(endReasonId) ?: TrailWalkEndReason.ENDED,
        routeRevision = routeRevision
    )
}
