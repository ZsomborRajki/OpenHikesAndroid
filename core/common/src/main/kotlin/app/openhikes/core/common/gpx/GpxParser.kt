package app.openhikes.core.common.gpx

import app.openhikes.core.model.GpxImportFailure
import app.openhikes.core.model.GpxImportLimits
import app.openhikes.core.model.GpxTrack
import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource

/**
 * Outcome of parsing a GPX 1.1 document into domain tracks.
 */
sealed interface GpxParseResult<out T> {
    data class Success<T>(val value: T) : GpxParseResult<T>

    data class Failure(val reason: GpxImportFailure) : GpxParseResult<Nothing>
}

/**
 * Pure-JVM SAX-based GPX 1.1 parser matching OpenHikes's iOS import guarantees:
 * bounded file/point/track/place limits, `<trkseg>` pause boundaries, placeholder filtering,
 * and grapheme-safe metadata bounding.
 */
object GpxParser {
    fun parseSingle(bytes: ByteArray, limits: GpxImportLimits = GpxImportLimits()): GpxParseResult<GpxTrack> =
        when (val result = parseAll(bytes, limits)) {
            is GpxParseResult.Failure -> result
            is GpxParseResult.Success -> {
                if (result.value.size == 1) {
                    GpxParseResult.Success(result.value.first())
                } else {
                    GpxParseResult.Failure(GpxImportFailure.MULTIPLE_TRACKS)
                }
            }
        }

    fun parseAll(bytes: ByteArray, limits: GpxImportLimits = GpxImportLimits()): GpxParseResult<List<GpxTrack>> {
        val failureReason = when {
            bytes.isEmpty() -> GpxImportFailure.UNREADABLE
            bytes.size > limits.maxFileSizeBytes -> GpxImportFailure.TOO_LARGE
            else -> null
        }
        if (failureReason != null) {
            return GpxParseResult.Failure(failureReason)
        }

        val handler = GpxSaxHandler(maximumPointCount = limits.maxPointCount)
        val parseOutcome = runCatching {
            val saxParser = createSafeSaxFactory().newSAXParser()
            val reader = saxParser.xmlReader
            reader.entityResolver = EntityResolver { _, _ -> InputSource(StringReader("")) }
            reader.contentHandler = handler
            reader.parse(InputSource(ByteArrayInputStream(bytes)))
        }
        return parseOutcome.fold(
            onSuccess = { extractTracks(handler.document, limits) },
            onFailure = { cause ->
                val failure = if (cause is PointLimitExceededException) {
                    GpxImportFailure.TOO_LARGE
                } else {
                    GpxImportFailure.UNREADABLE
                }
                GpxParseResult.Failure(failure)
            }
        )
    }

    private fun extractTracks(document: ParsedGpxDocument, limits: GpxImportLimits): GpxParseResult<List<GpxTrack>> {
        val hasTrackPoints = document.trackSegments.any { it.points.isNotEmpty() }
        val hasRoutePoints = document.routeSegments.any { it.points.isNotEmpty() }
        val hasGeometryOfItsOwn = hasTrackPoints || hasRoutePoints

        val (sourceSegments, wordsMap) = when {
            hasTrackPoints -> document.trackSegments to document.trackWords
            hasRoutePoints -> document.routeSegments to document.routeWords
            else -> listOf(ParsedSegment(0, document.waypoints)) to document.trackWords
        }

        val usableSegments = sourceSegments.mapNotNull { segment ->
            val validated = segment.points.mapNotNull(GpxPlaceholderFilter::toValidatedPoint)
            val cleaned = GpxPlaceholderFilter.dropPlaceholders(validated, isRecordedTrack = hasTrackPoints)
            if (cleaned.isEmpty()) null else segment.containerIndex to cleaned
        }
        val grouped = GpxTrackAssembler.groupSegmentsByContainer(usableSegments)
        return when {
            usableSegments.isEmpty() -> GpxParseResult.Failure(GpxImportFailure.NO_USABLE_POINTS)
            grouped.size > limits.maxTrackCount -> GpxParseResult.Failure(GpxImportFailure.TOO_LARGE)
            else -> {
                val parsedPlaces = if (hasGeometryOfItsOwn) {
                    document.waypoints.mapNotNull(GpxTrackAssembler::parsePlace)
                } else {
                    emptyList()
                }
                val parsedPhotos = if (hasGeometryOfItsOwn) {
                    document.waypoints.mapNotNull(GpxTrackAssembler::parsePhoto)
                } else {
                    emptyList()
                }
                val tracks = if (grouped.size == 1) {
                    val (containerIndex, segments) = grouped.first()
                    listOf(
                        GpxTrackAssembler.buildSingleTrack(
                            document = document,
                            words = wordsMap[containerIndex] ?: ContainerWords(),
                            segments = segments,
                            places = parsedPlaces.take(limits.maxPlaces),
                            photos = parsedPhotos.take(limits.maxPlaces)
                        )
                    )
                } else {
                    GpxTrackAssembler.buildSplitTracks(
                        document = document,
                        wordsMap = wordsMap,
                        containers = grouped,
                        places = parsedPlaces,
                        photos = parsedPhotos,
                        maxPlaces = limits.maxPlaces
                    )
                }
                GpxParseResult.Success(tracks)
            }
        }
    }

    private fun createSafeSaxFactory(): SAXParserFactory {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        return factory
    }
}
