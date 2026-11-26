package app.openhikes.core.common.photos

import app.openhikes.core.common.gpx.GpxExporter
import app.openhikes.core.model.ExportedHikeArchive
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikePhoto
import app.openhikes.core.model.OrderedHikePhotos
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pure-JVM builder for `<stem>.zip` hike archives aligned with iOS `HikeArchive`:
 * - `<stem>/<stem>.gpx` (linking `<link href="Photos/photo-N.<ext>"/>` only for anchored photos
 *   whose bytes exist on device)
 * - `<stem>/Photos/photo-1.<ext>`, ... (including unanchored photos whose bytes exist)
 * - `<stem>/Credits.txt` (emitted whenever any exported photo carries an open license)
 */
object HikePhotoArchiveBuilder {
    private const val STREAM_BUFFER_BYTES = 8 * 1024

    fun fileName(hike: Hike, zoneId: ZoneId = ZoneId.systemDefault()): String = "${archiveStem(hike, zoneId)}.zip"

    fun archiveStem(hike: Hike, zoneId: ZoneId = ZoneId.systemDefault()): String =
        GpxExporter.fileName(hike, zoneId).removeSuffix(".gpx")

    fun build(
        hike: Hike,
        readPhotoBytes: (HikePhoto) -> ByteArray?,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): ExportedHikeArchive {
        val stem = archiveStem(hike, zoneId)
        val out = ByteArrayOutputStream()
        writeTo(hike = hike, stem = stem, readPhotoBytes = readPhotoBytes, output = out)
        return ExportedHikeArchive(fileName = "$stem.zip", zipBytes = out.toByteArray())
    }

    fun writeTo(hike: Hike, stem: String, readPhotoBytes: (HikePhoto) -> ByteArray?, output: OutputStream) {
        val cachedBytes = LinkedHashMap<String, ByteArray>()
        writeStreaming(
            hike = hike,
            stem = stem,
            hasPhoto = { photo ->
                val bytes = readPhotoBytes(photo)
                if (bytes != null) {
                    cachedBytes[photo.id] = bytes
                    true
                } else {
                    false
                }
            },
            openPhotoStream = { photo -> cachedBytes.remove(photo.id)?.let(::ByteArrayInputStream) },
            output = output
        )
    }

    /**
     * Streams the hike GPX, each available photo original (one stream at a time), and any Commons
     * attribution credits directly into [output] without holding all photo bytes in memory.
     */
    fun writeStreaming(
        hike: Hike,
        stem: String,
        hasPhoto: (HikePhoto) -> Boolean,
        openPhotoStream: (HikePhoto) -> InputStream?,
        output: OutputStream
    ) {
        val orderedPhotos = OrderedHikePhotos.from(hike.photos).photos
        val photoLinksById = LinkedHashMap<String, String>()
        val includedPhotos = ArrayList<Pair<HikePhoto, String>>()
        val creditLines = ArrayList<String>()

        var exportedIndex = 0
        for (photo in orderedPhotos) {
            if (!photo.recordsPlaceOnly && hasPhoto(photo)) {
                exportedIndex += 1
                val relativePath = "Photos/photo-$exportedIndex.${photo.pathExtension}"
                includedPhotos.add(photo to "$stem/$relativePath")
                photoLinksById[photo.id] = relativePath
                photo.license?.let { license ->
                    val author = photo.importedAuthorName?.takeIf { it.isNotBlank() } ?: "Unknown author"
                    val licenseDetail = license.url?.let { "${license.name} ($it)" } ?: license.name
                    creditLines.add("$relativePath — Photo by $author · $licenseDetail · ${license.sourceUrl}")
                }
            }
        }

        val gpxXml = GpxExporter.exportXml(hike = hike, photoArchivePaths = photoLinksById)
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        ZipOutputStream(output).use { zip ->
            writeEntry(zip, "$stem/$stem.gpx", gpxXml.toByteArray(Charsets.UTF_8))
            for ((photo, entryPath) in includedPhotos) {
                openPhotoStream(photo)?.use { input ->
                    writeStreamEntry(zip, entryPath, input, buffer)
                }
            }
            if (creditLines.isNotEmpty()) {
                val creditsBody = creditLines.joinToString(separator = "\n", postfix = "\n")
                writeEntry(zip, "$stem/Credits.txt", creditsBody.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun writeStreamEntry(zip: ZipOutputStream, path: String, input: InputStream, buffer: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            zip.write(buffer, 0, read)
        }
        zip.closeEntry()
    }

    private fun writeEntry(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
    }
}
