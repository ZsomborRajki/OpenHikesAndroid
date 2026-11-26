package app.openhikes.core.common.gpx

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

internal class PointLimitExceededException : RuntimeException()

internal data class ContainerWords(
    var name: String? = null,
    var description: String? = null,
    var comment: String? = null
)

internal data class ParsedSegment(val containerIndex: Int, val points: MutableList<RawGpxPoint> = mutableListOf())

internal class ParsedGpxDocument {
    var metadataName: String? = null
    var metadataDescription: String? = null
    var metadataAuthor: String? = null
    var metadataKeywords: String? = null
    var metadataTimeEpochMillis: Long? = null
    val trackWords = HashMap<Int, ContainerWords>()
    val routeWords = HashMap<Int, ContainerWords>()
    val trackSegments = ArrayList<ParsedSegment>()
    val routeSegments = ArrayList<ParsedSegment>()
    val waypoints = ArrayList<RawGpxPoint>()
}

private enum class PointKind {
    TRACK,
    ROUTE,
    WAYPOINT
}

private data class PendingPoint(
    val kind: PointKind,
    val element: String,
    val depth: Int,
    var latitude: Double?,
    var longitude: Double?,
    var elevationMeters: Double? = null,
    var timestampEpochMillis: Long? = null,
    var name: String? = null,
    var symbol: String? = null,
    var note: String? = null,
    var linkHref: String? = null
)

internal class GpxSaxHandler(private val maximumPointCount: Int) : DefaultHandler() {
    val document = ParsedGpxDocument()

    private val path = ArrayList<String>()
    private val textBuffer = StringBuilder()
    private var currentTrackIndex = -1
    private var currentRouteIndex = -1
    private var hasOpenTrackSegment = false
    private var hasOpenRouteSegment = false
    private var ignoredSubtreeDepth = 0
    private var pendingPoint: PendingPoint? = null
    private var parsedPointCount = 0

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
        if (ignoredSubtreeDepth > 0) {
            ignoredSubtreeDepth += 1
            return
        }
        val element = resolveElementName(localName, qName)
        val inGpxNamespace = uri.isNullOrEmpty() || uri == GPX_1_1_NAMESPACE || uri == GPX_1_0_NAMESPACE
        if (!inGpxNamespace || element == ELEM_EXTENSIONS) {
            ignoredSubtreeDepth = 1
            return
        }

        path.add(element)
        textBuffer.setLength(0)
        dispatchStartElement(element, attributes)
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (ignoredSubtreeDepth > 0) return
        textBuffer.append(ch, start, length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        if (ignoredSubtreeDepth > 0) {
            ignoredSubtreeDepth -= 1
            if (ignoredSubtreeDepth == 0) {
                textBuffer.setLength(0)
            }
            return
        }
        val element = resolveElementName(localName, qName)
        val value = textBuffer.toString().trim()
        updatePendingPoint(element, value)
        applyElementValue(element, value)
        if (isPath(ELEM_GPX, ELEM_TRACK, ELEM_TRACK_SEGMENT) || isPath(ELEM_GPX, ELEM_TRACK)) {
            hasOpenTrackSegment = false
        } else if (isPath(ELEM_GPX, ELEM_ROUTE)) {
            hasOpenRouteSegment = false
        }
        if (path.isNotEmpty()) {
            path.removeAt(path.lastIndex)
        }
        textBuffer.setLength(0)
        if (parsedPointCount > maximumPointCount) {
            throw PointLimitExceededException()
        }
    }

    private fun dispatchStartElement(element: String, attributes: Attributes?) {
        when (element) {
            ELEM_TRACK -> if (isPath(ELEM_GPX, ELEM_TRACK)) {
                currentTrackIndex += 1
                hasOpenTrackSegment = false
            }
            ELEM_TRACK_SEGMENT -> if (isPath(ELEM_GPX, ELEM_TRACK, ELEM_TRACK_SEGMENT)) {
                openTrackSegment()
            }
            ELEM_ROUTE -> if (isPath(ELEM_GPX, ELEM_ROUTE)) {
                currentRouteIndex += 1
                hasOpenRouteSegment = true
                document.routeSegments.add(ParsedSegment(containerIndex = currentRouteIndex))
            }
            ELEM_TRACK_POINT, ELEM_ROUTE_POINT, ELEM_WAYPOINT -> maybeStartPoint(element, attributes)
            ELEM_LINK -> captureWaypointLink(attributes)
        }
    }

    private fun captureWaypointLink(attributes: Attributes?) {
        val point = pendingPoint
        val canAttach = point != null &&
            point.kind == PointKind.WAYPOINT &&
            point.linkHref == null &&
            path.size == point.depth + 1 &&
            path[path.lastIndex - 1] == point.element
        if (canAttach) {
            val href = attributes?.getValue("", ATTR_HREF) ?: attributes?.getValue(ATTR_HREF)
            if (!href.isNullOrBlank()) {
                point.linkHref = href.trim()
            }
        }
    }

    private fun maybeStartPoint(element: String, attributes: Attributes?) {
        if (pendingPoint != null) return
        val isTrackPoint = element == ELEM_TRACK_POINT &&
            (
                isPath(ELEM_GPX, ELEM_TRACK, ELEM_TRACK_SEGMENT, ELEM_TRACK_POINT) ||
                    isPath(ELEM_GPX, ELEM_TRACK, ELEM_TRACK_POINT)
                )
        val kind = when {
            isTrackPoint -> PointKind.TRACK
            element == ELEM_ROUTE_POINT && isPath(ELEM_GPX, ELEM_ROUTE, ELEM_ROUTE_POINT) -> PointKind.ROUTE
            element == ELEM_WAYPOINT && isPath(ELEM_GPX, ELEM_WAYPOINT) -> PointKind.WAYPOINT
            else -> null
        } ?: return
        val rawLat = attributes?.getValue("", ATTR_LAT) ?: attributes?.getValue(ATTR_LAT)
        val rawLon = attributes?.getValue("", ATTR_LON) ?: attributes?.getValue(ATTR_LON)
        pendingPoint = PendingPoint(
            kind = kind,
            element = element,
            depth = path.size,
            latitude = rawLat?.toDoubleOrNull(),
            longitude = rawLon?.toDoubleOrNull()
        )
    }

    private fun updatePendingPoint(element: String, value: String) {
        val point = pendingPoint ?: return
        if (path.size != point.depth + 1 || path[path.lastIndex - 1] != point.element) return
        when (element) {
            ELEM_ELEVATION -> point.elevationMeters = value.toDoubleOrNull()
            ELEM_TIME -> point.timestampEpochMillis = parseIsoTimestamp(value)
            ELEM_NAME -> if (point.kind == PointKind.WAYPOINT) point.name = value
            ELEM_SYMBOL -> if (point.kind == PointKind.WAYPOINT) point.symbol = value
            ELEM_DESCRIPTION -> if (point.kind == PointKind.WAYPOINT) point.note = value
        }
    }

    private fun applyElementValue(element: String, value: String) {
        when {
            isPath(ELEM_GPX, ELEM_METADATA, ELEM_NAME) -> document.metadataName = value
            isPath(ELEM_GPX, ELEM_METADATA, ELEM_DESCRIPTION) -> document.metadataDescription = value
            isPath(ELEM_GPX, ELEM_METADATA, ELEM_AUTHOR, ELEM_NAME) -> document.metadataAuthor = value
            isPath(ELEM_GPX, ELEM_METADATA, ELEM_KEYWORDS) -> document.metadataKeywords = value
            isPath(ELEM_GPX, ELEM_METADATA, ELEM_TIME) ->
                document.metadataTimeEpochMillis = parseIsoTimestamp(value)
            element == ELEM_TRACK_POINT || element == ELEM_ROUTE_POINT || element == ELEM_WAYPOINT ->
                finishPoint(element)
            else -> applyContainerWord(element, value)
        }
    }

    private fun applyContainerWord(element: String, value: String) {
        if (element != ELEM_NAME && element != ELEM_DESCRIPTION && element != ELEM_COMMENT) return
        val isTrackWord = isPath(ELEM_GPX, ELEM_TRACK, element) && currentTrackIndex >= 0
        val isRouteWord = isPath(ELEM_GPX, ELEM_ROUTE, element) && currentRouteIndex >= 0
        val (map, key) = when {
            isTrackWord -> document.trackWords to currentTrackIndex
            isRouteWord -> document.routeWords to currentRouteIndex
            else -> return
        }
        val words = map.getOrPut(key) { ContainerWords() }
        when (element) {
            ELEM_NAME -> words.name = value
            ELEM_DESCRIPTION -> words.description = value
            ELEM_COMMENT -> words.comment = value
        }
    }

    private fun finishPoint(element: String) {
        val point = pendingPoint
        if (point == null || point.element != element || path.size != point.depth) return
        pendingPoint = null
        parsedPointCount += 1
        if (parsedPointCount > maximumPointCount) return
        val raw = RawGpxPoint(
            latitude = point.latitude,
            longitude = point.longitude,
            elevationMeters = point.elevationMeters,
            timestampEpochMillis = point.timestampEpochMillis,
            name = point.name,
            symbol = point.symbol,
            note = point.note,
            linkHref = point.linkHref
        )
        when (point.kind) {
            PointKind.TRACK -> {
                if (currentTrackIndex < 0) currentTrackIndex = 0
                if (!hasOpenTrackSegment) openTrackSegment()
                document.trackSegments.lastOrNull()?.points?.add(raw)
            }
            PointKind.ROUTE -> {
                if (currentRouteIndex < 0) currentRouteIndex = 0
                if (!hasOpenRouteSegment) {
                    hasOpenRouteSegment = true
                    document.routeSegments.add(ParsedSegment(containerIndex = currentRouteIndex))
                }
                document.routeSegments.lastOrNull()?.points?.add(raw)
            }
            PointKind.WAYPOINT -> document.waypoints.add(raw)
        }
    }

    private fun openTrackSegment() {
        if (currentTrackIndex < 0) return
        hasOpenTrackSegment = true
        document.trackSegments.add(ParsedSegment(containerIndex = currentTrackIndex))
    }

    private fun isPath(first: String, second: String, third: String? = null, fourth: String? = null): Boolean {
        val expectedDepth = when {
            fourth != null -> DEPTH_FOUR
            third != null -> DEPTH_THREE
            else -> DEPTH_TWO
        }
        if (path.size != expectedDepth) return false
        return path[0] == first &&
            path[1] == second &&
            (third == null || path[DEPTH_TWO] == third) &&
            (fourth == null || path[DEPTH_THREE] == fourth)
    }

    private fun resolveElementName(localName: String?, qName: String?): String = when {
        !localName.isNullOrEmpty() -> localName
        !qName.isNullOrEmpty() -> qName.substringAfter(':')
        else -> ""
    }

    companion object {
        private const val GPX_1_1_NAMESPACE = "http://www.topografix.com/GPX/1/1"
        private const val GPX_1_0_NAMESPACE = "http://www.topografix.com/GPX/1/0"
        private const val DEPTH_TWO = 2
        private const val DEPTH_THREE = 3
        private const val DEPTH_FOUR = 4
        private const val ATTR_LAT = "lat"
        private const val ATTR_LON = "lon"
        private const val ATTR_HREF = "href"
        private const val ELEM_GPX = "gpx"
        private const val ELEM_EXTENSIONS = "extensions"
        private const val ELEM_TRACK = "trk"
        private const val ELEM_TRACK_SEGMENT = "trkseg"
        private const val ELEM_TRACK_POINT = "trkpt"
        private const val ELEM_ROUTE = "rte"
        private const val ELEM_ROUTE_POINT = "rtept"
        private const val ELEM_WAYPOINT = "wpt"
        private const val ELEM_LINK = "link"
        private const val ELEM_METADATA = "metadata"
        private const val ELEM_AUTHOR = "author"
        private const val ELEM_NAME = "name"
        private const val ELEM_DESCRIPTION = "desc"
        private const val ELEM_COMMENT = "cmt"
        private const val ELEM_KEYWORDS = "keywords"
        private const val ELEM_ELEVATION = "ele"
        private const val ELEM_TIME = "time"
        private const val ELEM_SYMBOL = "sym"

        internal fun parseIsoTimestamp(raw: String): Long? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            return runCatching { Instant.parse(trimmed).toEpochMilli() }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching {
                    LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                }.getOrNull()
        }
    }
}
