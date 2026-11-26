package app.openhikes.core.common.gpx

import app.openhikes.core.model.ExportedGpx
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikePhoto
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.TrailPoint
import app.openhikes.core.model.toTrailRuns
import java.text.BreakIterator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Serializes a [Hike] into GPX 1.1 XML preserving `<trkseg>` pause boundaries, places,
 * XML 1.0 character safety, and grapheme/byte-safe export filenames.
 */
object GpxExporter {
    private const val CREATOR = "OpenHikes"
    private const val GPX_NAMESPACE = "http://www.topografix.com/GPX/1/1"
    private const val XSI_NAMESPACE = "http://www.w3.org/2001/XMLSchema-instance"
    private const val SCHEMA_LOCATION =
        "http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd"

    private const val FALLBACK_FILE_STEM = "Hike"
    private const val MAX_FILE_STEM_CHARS = 64
    private const val MAX_FILENAME_UTF8_BYTES = 255
    private const val FIRST_PRINTABLE_SCALAR = 0x20
    private const val MAX_BMP_BEFORE_SURROGATES = 0xD7FF
    private const val MIN_BMP_AFTER_SURROGATES = 0xE000
    private const val MAX_VALID_BMP_SCALAR = 0xFFFD
    private const val MIN_SUPPLEMENTARY_SCALAR = 0x10000
    private const val MAX_UNICODE_SCALAR = 0x10FFFF
    private const val CHARS_PER_POINT_ESTIMATE = 96
    private const val PREAMBLE_BYTES_ESTIMATE = 512
    private const val RESERVED_FILENAME_CHARS = "/\\:?%*|\"<>"

    private val FILE_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun export(hike: Hike, zoneId: ZoneId = ZoneId.systemDefault()): ExportedGpx = ExportedGpx(
        fileName = fileName(hike, zoneId),
        xml = exportXml(hike)
    )

    fun exportXml(hike: Hike, photoArchivePaths: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder(PREAMBLE_BYTES_ESTIMATE + hike.route.size * CHARS_PER_POINT_ESTIMATE)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"${escaped(CREATOR)}\"")
        sb.append(" xmlns=\"$GPX_NAMESPACE\"")
        sb.append(" xmlns:xsi=\"$XSI_NAMESPACE\"")
        sb.append(" xsi:schemaLocation=\"$SCHEMA_LOCATION\">\n")
        appendMetadata(sb, hike)
        appendPlaces(sb, hike.places)
        appendPhotos(sb, hike.orderedPhotos, photoArchivePaths)
        appendTrack(sb, hike)
        sb.append("</gpx>\n")
        return sb.toString()
    }

    fun fileName(hike: Hike, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val dateText = Instant.ofEpochMilli(hike.dateEpochMillis).atZone(zoneId).toLocalDate()
            .format(FILE_DATE_FORMATTER)
        val suffix = "-$dateText.gpx"
        val availableBytes = MAX_FILENAME_UTF8_BYTES - suffix.toByteArray(Charsets.UTF_8).size
        return fileStem(hike.displayTitle, availableBytes) + suffix
    }

    internal fun escaped(text: String): String {
        val out = StringBuilder(text.length)
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            offset += Character.charCount(codePoint)
            when (codePoint) {
                '&'.code -> out.append("&amp;")
                '<'.code -> out.append("&lt;")
                '>'.code -> out.append("&gt;")
                '"'.code -> out.append("&quot;")
                '\''.code -> out.append("&apos;")
                else -> if (isValidXmlScalar(codePoint)) out.appendCodePoint(codePoint)
            }
        }
        return out.toString()
    }

    private fun appendMetadata(sb: StringBuilder, hike: Hike) {
        sb.append("  <metadata>\n")
        sb.append("    <name>${escaped(hike.displayTitle)}</name>\n")
        hike.trackDescription?.let { desc ->
            sb.append("    <desc>${escaped(desc)}</desc>\n")
        }
        hike.author?.let { author ->
            sb.append("    <author>\n")
            sb.append("      <name>${escaped(author)}</name>\n")
            sb.append("    </author>\n")
        }
        val dateIso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(hike.dateEpochMillis))
        sb.append("    <time>$dateIso</time>\n")
        hike.keywords?.let { keywords ->
            sb.append("    <keywords>${escaped(keywords)}</keywords>\n")
        }
        sb.append("  </metadata>\n")
    }

    private fun appendPlaces(sb: StringBuilder, places: List<TrailPoint>) {
        for (place in places) {
            val lat = String.format(Locale.US, "%.7f", place.coordinate.latitude)
            val lon = String.format(Locale.US, "%.7f", place.coordinate.longitude)
            sb.append("  <wpt lat=\"$lat\" lon=\"$lon\">\n")
            place.elevationMeters?.takeIf { it.isFinite() }?.let { ele ->
                sb.append("    <ele>${String.format(Locale.US, "%.2f", ele)}</ele>\n")
            }
            val placeName = place.name.ifBlank { place.symbol ?: "Place" }
            sb.append("    <name>${escaped(placeName)}</name>\n")
            if (place.note.isNotBlank()) {
                sb.append("    <desc>${escaped(place.note)}</desc>\n")
            }
            place.osm?.let { osm ->
                sb.append("    <link href=\"${escaped(osm.url)}\"/>\n")
            }
            place.symbol?.takeIf { it.isNotBlank() }?.let { sym ->
                sb.append("    <sym>${escaped(sym)}</sym>\n")
            }
            sb.append("  </wpt>\n")
        }
    }

    private fun appendPhotos(sb: StringBuilder, photos: List<HikePhoto>, photoArchivePaths: Map<String, String>) {
        for (photo in photos) {
            val coordinate = photo.coordinate ?: continue
            val lat = String.format(Locale.US, "%.7f", coordinate.latitude)
            val lon = String.format(Locale.US, "%.7f", coordinate.longitude)
            val iso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(photo.capturedAtEpochMillis))
            sb.append("  <wpt lat=\"$lat\" lon=\"$lon\">\n")
            sb.append("    <time>$iso</time>\n")
            sb.append("    <name>Photo</name>\n")
            val author = photo.importedAuthorName?.trim()?.takeIf { it.isNotEmpty() }
            val licenseName = photo.license?.name?.trim()?.takeIf { it.isNotEmpty() }
            val credit = when {
                author != null && licenseName != null -> "Photo by $author · $licenseName"
                author != null -> "Photo by $author"
                licenseName != null -> "Photo · $licenseName"
                else -> null
            }
            credit?.let { sb.append("    <desc>${escaped(it)}</desc>\n") }
            photoArchivePaths[photo.id]?.let { relativePath ->
                sb.append("    <link href=\"${escaped(relativePath)}\"/>\n")
            }
            photo.license?.sourceUrl?.takeIf { it.isNotBlank() }?.let { sourceUrl ->
                sb.append("    <link href=\"${escaped(sourceUrl)}\"/>\n")
            }
            sb.append("    <sym>Photo</sym>\n")
            sb.append("  </wpt>\n")
        }
    }

    private fun appendTrack(sb: StringBuilder, hike: Hike) {
        sb.append("  <trk>\n")
        sb.append("    <name>${escaped(hike.displayTitle)}</name>\n")
        hike.trackDescription?.let { desc ->
            sb.append("    <desc>${escaped(desc)}</desc>\n")
        }
        sb.append("    <trkseg>\n")
        val runs = hike.route.toTrailRuns()
        runs.pieces.forEachIndexed { index, range ->
            if (index > 0) {
                sb.append("    </trkseg>\n")
                sb.append("    <trkseg>\n")
            }
            for (pointIdx in range) {
                appendPoint(sb, hike.route[pointIdx])
            }
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
    }

    private fun appendPoint(sb: StringBuilder, point: RouteCoordinate) {
        val lat = String.format(Locale.US, "%.7f", point.latitude)
        val lon = String.format(Locale.US, "%.7f", point.longitude)
        val attrs = "lat=\"$lat\" lon=\"$lon\""
        val elevation = point.elevationMeters?.takeIf { it.isFinite() }
        val timestamp = point.timestampEpochMillis
        if (elevation == null && timestamp == null) {
            sb.append("      <trkpt $attrs/>\n")
            return
        }
        sb.append("      <trkpt $attrs>\n")
        if (elevation != null) {
            sb.append("        <ele>${String.format(Locale.US, "%.2f", elevation)}</ele>\n")
        }
        if (timestamp != null) {
            val iso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(timestamp))
            sb.append("        <time>$iso</time>\n")
        }
        sb.append("      </trkpt>\n")
    }

    private fun fileStem(name: String, availableUtf8Bytes: Int): String {
        val sanitized = buildString(name.length) {
            var offset = 0
            while (offset < name.length) {
                val cp = name.codePointAt(offset)
                offset += Character.charCount(cp)
                val isReserved = cp in 0..Char.MAX_VALUE.code &&
                    (RESERVED_FILENAME_CHARS.indexOf(cp.toChar()) >= 0 || Character.isISOControl(cp))
                if (isReserved) append('-') else appendCodePoint(cp)
            }
        }
        val trimmed = sanitized.trim { it.isWhitespace() || it == '.' }
        if (trimmed.isEmpty()) return FALLBACK_FILE_STEM

        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(trimmed)
        var clusterCount = 0
        var utf8Bytes = 0
        var cutEnd = 0
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val cluster = trimmed.substring(start, end)
            val clusterBytes = cluster.toByteArray(Charsets.UTF_8).size
            if (clusterCount >= MAX_FILE_STEM_CHARS || utf8Bytes + clusterBytes > availableUtf8Bytes) {
                break
            }
            clusterCount += 1
            utf8Bytes += clusterBytes
            cutEnd = end
            start = end
            end = iterator.next()
        }
        val bounded = trimmed.substring(0, cutEnd).trim { it.isWhitespace() || it == '.' }
        return bounded.ifEmpty { FALLBACK_FILE_STEM }
    }

    private fun isValidXmlScalar(codePoint: Int): Boolean = codePoint == '\t'.code ||
        codePoint == '\n'.code ||
        codePoint == '\r'.code ||
        codePoint in FIRST_PRINTABLE_SCALAR..MAX_BMP_BEFORE_SURROGATES ||
        codePoint in MIN_BMP_AFTER_SURROGATES..MAX_VALID_BMP_SCALAR ||
        codePoint in MIN_SUPPLEMENTARY_SCALAR..MAX_UNICODE_SCALAR
}
