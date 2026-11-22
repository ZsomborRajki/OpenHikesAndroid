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
    var latitude: Double?,
    var longitude: Double?,
    var elevationMeters: Double? = null,
    var timestampEpochMillis: Long? = null,
    var name: String? = null,
    var symbol: String? = null,
    var note: String? = null
) {
    fun toRawPoint(): RawGpxPoint = RawGpxPoint(
        latitude = latitude,
        longitude = longitude,
        elevationMeters = elevationMeters,
        timestampEpochMillis = timestampEpochMillis,
        name = name,
        symbol = symbol,
        note = note
    )
}

internal class GpxSaxHandler(private val maximumPointCount: Int) : DefaultHandler() {
    val document = ParsedGpxDocument()

    private val path = ArrayList<String>()
    private val textBuffer = StringBuilder()
    private var currentTrackIndex = -1
    private var currentRouteIndex = -1
    private var hasOpenTrackSegment = false
    private var hasOpenRouteSegment = false
    private var pendingPoint: PendingPoint? = null
    private var parsedPointCount = 0

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
        val element = resolveElementName(localName, qName)
        path.add(element)
        textBuffer.setLength(0)

        when (element) {
            ELEM_TRACK -> {
                currentTrackIndex += 1
                hasOpenTrackSegment = false
            }
            ELEM_TRACK_SEGMENT -> openTrackSegment()
            ELEM_ROUTE -> {
                currentRouteIndex += 1
                hasOpenRouteSegment = false
                openRouteSegment()
            }
            ELEM_TRACK_POINT -> startPoint(PointKind.TRACK, element, attributes)
            ELEM_ROUTE_POINT -> startPoint(PointKind.ROUTE, element, attributes)
            ELEM_WAYPOINT -> startPoint(PointKind.WAYPOINT, element, attributes)
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        textBuffer.append(ch, start, length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        val element = resolveElementName(localName, qName)
        val value = textBuffer.toString().trim()
        updatePendingPoint(element, value)
        applyElementValue(element, value)
        when (element) {
            ELEM_TRACK_SEGMENT, ELEM_TRACK -> hasOpenTrackSegment = false
            ELEM_ROUTE -> hasOpenRouteSegment = false
        }
        if (path.isNotEmpty()) {
            path.removeAt(path.lastIndex)
        }
        textBuffer.setLength(0)
        if (parsedPointCount > maximumPointCount) {
            throw PointLimitExceededException()
        }
    }

    private fun startPoint(kind: PointKind, element: String, attributes: Attributes?) {
        pendingPoint = PendingPoint(
            kind = kind,
            element = element,
            latitude = attributes?.getValue(ATTR_LAT)?.toDoubleOrNull(),
            longitude = attributes?.getValue(ATTR_LON)?.toDoubleOrNull()
        )
    }

    private fun updatePendingPoint(element: String, value: String) {
        val point = pendingPoint ?: return
        val parent = path.getOrNull(path.lastIndex - 1)
        if (parent != point.element) return
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
            element == ELEM_NAME && isDirectChildOf(ELEM_METADATA) -> document.metadataName = value
            element == ELEM_DESCRIPTION && isDirectChildOf(ELEM_METADATA) -> document.metadataDescription = value
            element == ELEM_NAME && isMetadataAuthorChild() -> document.metadataAuthor = value
            element == ELEM_KEYWORDS && isDirectChildOf(ELEM_METADATA) -> document.metadataKeywords = value
            element == ELEM_TIME && isDirectChildOf(ELEM_METADATA) ->
                document.metadataTimeEpochMillis = parseIsoTimestamp(value)
            element == ELEM_TRACK_POINT || element == ELEM_ROUTE_POINT || element == ELEM_WAYPOINT ->
                finishPoint(element)
            else -> applyContainerWord(element, value)
        }
    }

    private fun applyContainerWord(element: String, value: String) {
        if (element != ELEM_NAME && element != ELEM_DESCRIPTION && element != ELEM_COMMENT) return
        val map = when {
            isDirectChildOf(ELEM_TRACK) && currentTrackIndex >= 0 -> document.trackWords
            isDirectChildOf(ELEM_ROUTE) && currentRouteIndex >= 0 -> document.routeWords
            else -> return
        }
        val key = if (isDirectChildOf(ELEM_TRACK)) currentTrackIndex else currentRouteIndex
        val words = map.getOrPut(key) { ContainerWords() }
        when (element) {
            ELEM_NAME -> words.name = value
            ELEM_DESCRIPTION -> words.description = value
            ELEM_COMMENT -> words.comment = value
        }
    }

    private fun finishPoint(element: String) {
        val point = pendingPoint
        if (point != null && point.element == element) {
            pendingPoint = null
            parsedPointCount += 1
            if (parsedPointCount <= maximumPointCount) {
                val raw = point.toRawPoint()
                when (point.kind) {
                    PointKind.TRACK -> appendTrackPoint(raw)
                    PointKind.ROUTE -> appendRoutePoint(raw)
                    PointKind.WAYPOINT -> document.waypoints.add(raw)
                }
            }
        }
    }

    private fun appendTrackPoint(raw: RawGpxPoint) {
        if (currentTrackIndex < 0) currentTrackIndex = 0
        if (!hasOpenTrackSegment) openTrackSegment()
        document.trackSegments.lastOrNull()?.points?.add(raw)
    }

    private fun appendRoutePoint(raw: RawGpxPoint) {
        if (currentRouteIndex < 0) currentRouteIndex = 0
        if (!hasOpenRouteSegment) openRouteSegment()
        document.routeSegments.lastOrNull()?.points?.add(raw)
    }

    private fun openTrackSegment() {
        if (currentTrackIndex < 0) return
        hasOpenTrackSegment = true
        document.trackSegments.add(ParsedSegment(containerIndex = currentTrackIndex))
    }

    private fun openRouteSegment() {
        if (currentRouteIndex < 0) return
        hasOpenRouteSegment = true
        document.routeSegments.add(ParsedSegment(containerIndex = currentRouteIndex))
    }

    private fun isDirectChildOf(parentElement: String): Boolean =
        path.size >= 2 && path[path.lastIndex - 1] == parentElement

    private fun isMetadataAuthorChild(): Boolean = path.size >= AUTHOR_PATH_DEPTH &&
        path[path.lastIndex - 2] == ELEM_METADATA &&
        path[path.lastIndex - 1] == ELEM_AUTHOR

    private fun resolveElementName(localName: String?, qName: String?): String = when {
        !localName.isNullOrEmpty() -> localName
        !qName.isNullOrEmpty() -> qName.substringAfter(':')
        else -> ""
    }

    companion object {
        private const val AUTHOR_PATH_DEPTH = 3
        private const val ATTR_LAT = "lat"
        private const val ATTR_LON = "lon"
        private const val ELEM_TRACK = "trk"
        private const val ELEM_TRACK_SEGMENT = "trkseg"
        private const val ELEM_TRACK_POINT = "trkpt"
        private const val ELEM_ROUTE = "rte"
        private const val ELEM_ROUTE_POINT = "rtept"
        private const val ELEM_WAYPOINT = "wpt"
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
