package app.openhikes.core.common.gpx

import app.openhikes.core.common.BoundedText
import app.openhikes.core.common.TextBound
import app.openhikes.core.model.GpxPhotoWaypoint
import app.openhikes.core.model.GpxTrack
import app.openhikes.core.model.GpxWaypoint
import app.openhikes.core.model.RouteBoundary
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteGeometry
import app.openhikes.core.model.TrailPlaceOsm

internal object GpxTrackAssembler {
    private const val PHOTOGRAPH_MARKER = "Photo"

    fun buildSingleTrack(
        document: ParsedGpxDocument,
        words: ContainerWords,
        segments: List<List<ValidatedGpxPoint>>,
        places: List<GpxWaypoint>,
        photos: List<GpxPhotoWaypoint> = emptyList()
    ): GpxTrack {
        val route = buildRoute(segments)
        return GpxTrack(
            name = nonEmpty(words.name) ?: nonEmpty(document.metadataName),
            trackDescription = BoundedText.bounded(
                nonEmpty(words.description) ?: nonEmpty(words.comment) ?: nonEmpty(document.metadataDescription),
                TextBound.NOTES
            ),
            author = BoundedText.bounded(document.metadataAuthor, TextBound.CREDIT),
            keywords = BoundedText.bounded(document.metadataKeywords, TextBound.KEYWORDS),
            startTimeEpochMillis = document.metadataTimeEpochMillis
                ?: segments.asSequence().flatten().firstOrNull { it.timestampEpochMillis != null }
                    ?.timestampEpochMillis,
            route = route,
            distanceMeters = RouteGeometry.routeLengthMeters(route),
            places = places,
            photos = photos
        )
    }

    fun buildSplitTracks(
        document: ParsedGpxDocument,
        wordsMap: Map<Int, ContainerWords>,
        containers: List<Pair<Int, List<List<ValidatedGpxPoint>>>>,
        places: List<GpxWaypoint>,
        photos: List<GpxPhotoWaypoint> = emptyList(),
        maxPlaces: Int
    ): List<GpxTrack> {
        val routes = containers.map { (_, segments) -> buildRoute(segments) }
        val assignedPlaces = assignPlacesToTracks(places, routes, maxPlaces)
        val assignedPhotos = GpxWaypointSpatialIndex.assignByCoordinate(
            items = photos,
            routes = routes,
            maxItems = maxPlaces,
            coordinateOf = { it.coordinate }
        )
        return containers.mapIndexed { index, (containerIndex, segments) ->
            val words = wordsMap[containerIndex] ?: ContainerWords()
            val route = routes[index]
            GpxTrack(
                name = nonEmpty(words.name),
                trackDescription = BoundedText.bounded(
                    nonEmpty(words.description) ?: nonEmpty(words.comment),
                    TextBound.NOTES
                ),
                author = BoundedText.bounded(document.metadataAuthor, TextBound.CREDIT),
                keywords = BoundedText.bounded(document.metadataKeywords, TextBound.KEYWORDS),
                startTimeEpochMillis = segments.asSequence().flatten()
                    .firstOrNull { it.timestampEpochMillis != null }?.timestampEpochMillis,
                route = route,
                distanceMeters = RouteGeometry.routeLengthMeters(route),
                places = assignedPlaces[index],
                photos = assignedPhotos[index]
            )
        }
    }

    fun groupSegmentsByContainer(
        usableSegments: List<Pair<Int, List<ValidatedGpxPoint>>>
    ): List<Pair<Int, List<List<ValidatedGpxPoint>>>> {
        val grouped = ArrayList<Pair<Int, MutableList<List<ValidatedGpxPoint>>>>()
        for ((containerIndex, points) in usableSegments) {
            val last = grouped.lastOrNull()
            if (last != null && last.first == containerIndex) {
                last.second.add(points)
            } else {
                grouped.add(containerIndex to mutableListOf(points))
            }
        }
        return grouped
    }

    fun parsePlace(raw: RawGpxPoint): GpxWaypoint? {
        val point = if (!isPhotographMarker(raw)) GpxPlaceholderFilter.toValidatedPoint(raw) else null
        return point?.let { validated ->
            GpxWaypoint(
                coordinate = validated.coordinate,
                elevationMeters = validated.elevationMeters,
                name = BoundedText.boundedOrEmpty(raw.name, TextBound.TITLE),
                symbol = raw.symbol?.trim()?.takeIf { it.isNotEmpty() },
                note = BoundedText.boundedOrEmpty(raw.note, TextBound.NOTES),
                osm = TrailPlaceOsm.fromUrl(raw.linkHref)
            )
        }
    }

    fun parsePhoto(raw: RawGpxPoint): GpxPhotoWaypoint? {
        val timestamp = raw.timestampEpochMillis
        if (!isPhotographMarker(raw) || timestamp == null) return null
        return GpxPlaceholderFilter.toValidatedPoint(raw)?.let { point ->
            GpxPhotoWaypoint(
                coordinate = point.coordinate,
                capturedAtEpochMillis = timestamp,
                authorName = extractPhotoAuthor(raw.note),
                sourceUrl = raw.firstHttpsLinkHref
            )
        }
    }

    private fun extractPhotoAuthor(note: String?): String? {
        val prefix = "Photo by "
        val trimmed = note?.trim()?.takeIf { it.startsWith(prefix) }
        return trimmed?.let {
            BoundedText.bounded(it.removePrefix(prefix).substringBefore(" · ").trim(), TextBound.CREDIT)
        }
    }

    private fun assignPlacesToTracks(
        places: List<GpxWaypoint>,
        routes: List<List<RouteCoordinate>>,
        maxPlaces: Int
    ): List<List<GpxWaypoint>> = GpxWaypointSpatialIndex.assignPlaces(places, routes, maxPlaces)

    private fun buildRoute(segments: List<List<ValidatedGpxPoint>>): List<RouteCoordinate> {
        val total = segments.sumOf { it.size }
        val result = ArrayList<RouteCoordinate>(total)
        segments.forEachIndexed { segIndex, segment ->
            segment.forEachIndexed { ptIndex, pt ->
                result.add(
                    RouteCoordinate(
                        latitude = pt.coordinate.latitude,
                        longitude = pt.coordinate.longitude,
                        elevationMeters = pt.elevationMeters,
                        timestampEpochMillis = pt.timestampEpochMillis,
                        boundary = if (segIndex > 0 && ptIndex == 0) RouteBoundary.PAUSED else null
                    )
                )
            }
        }
        return result
    }

    private fun isPhotographMarker(raw: RawGpxPoint): Boolean =
        raw.symbol.equals(PHOTOGRAPH_MARKER, ignoreCase = true) ||
            raw.name.equals(PHOTOGRAPH_MARKER, ignoreCase = true)

    private fun nonEmpty(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}
