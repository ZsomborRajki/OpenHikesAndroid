package app.openhikes.core.common.gpx

import app.openhikes.core.common.BoundedText
import app.openhikes.core.common.TextBound
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.GpxTrack
import app.openhikes.core.model.GpxWaypoint
import app.openhikes.core.model.RouteBoundary
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteGeometry

internal object GpxTrackAssembler {
    private const val PHOTOGRAPH_MARKER = "Photo"
    private const val WAYPOINT_ASSIGNMENT_THRESHOLD_METERS = 150.0

    fun buildSingleTrack(
        document: ParsedGpxDocument,
        words: ContainerWords,
        segments: List<List<ValidatedGpxPoint>>,
        places: List<GpxWaypoint>
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
            places = places
        )
    }

    fun buildSplitTracks(
        document: ParsedGpxDocument,
        wordsMap: Map<Int, ContainerWords>,
        containers: List<Pair<Int, List<List<ValidatedGpxPoint>>>>,
        places: List<GpxWaypoint>,
        maxPlaces: Int
    ): List<GpxTrack> {
        val routes = containers.map { (_, segments) -> buildRoute(segments) }
        val assigned = assignPlacesToTracks(places, routes, maxPlaces)
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
                places = assigned[index]
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
                note = BoundedText.boundedOrEmpty(raw.note, TextBound.NOTES)
            )
        }
    }

    private fun assignPlacesToTracks(
        places: List<GpxWaypoint>,
        routes: List<List<RouteCoordinate>>,
        maxPlaces: Int
    ): List<List<GpxWaypoint>> {
        val buckets = List(routes.size) { ArrayList<GpxWaypoint>() }
        places.asSequence()
            .mapNotNull { place -> nearestTrackIndex(place, routes)?.let { it to place } }
            .take(maxPlaces)
            .forEach { (trackIndex, place) -> buckets[trackIndex].add(place) }
        return buckets
    }

    private fun nearestTrackIndex(place: GpxWaypoint, routes: List<List<RouteCoordinate>>): Int? {
        var bestTrackIndex: Int? = null
        var bestDistance = WAYPOINT_ASSIGNMENT_THRESHOLD_METERS
        routes.forEachIndexed { trackIndex, route ->
            val dist = minDistanceToRoute(place.coordinate, route)
            if (dist <= bestDistance) {
                bestDistance = dist
                bestTrackIndex = trackIndex
            }
        }
        return bestTrackIndex
    }

    private fun minDistanceToRoute(coord: GeoCoordinate, route: List<RouteCoordinate>): Double = when {
        route.isEmpty() -> Double.POSITIVE_INFINITY
        route.size == 1 -> RouteGeometry.distanceMeters(coord, route.first().coordinate)
        else -> {
            var best = Double.POSITIVE_INFINITY
            for (i in 0 until route.lastIndex) {
                val next = route[i + 1]
                if (!next.isPauseBoundary) {
                    val proj = RouteGeometry.project(coord, route[i].coordinate, next.coordinate)
                    if (proj.offRouteMeters < best) best = proj.offRouteMeters
                }
            }
            best
        }
    }

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
