package app.openhikes.core.common.photos

import app.openhikes.core.common.gpx.GpxExporter
import app.openhikes.core.model.ExportedHikeArchive
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikePhoto
import app.openhikes.core.model.OrderedHikePhotos
import java.io.ByteArrayOutputStream
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
    fun build(
        hike: Hike,
        readPhotoBytes: (HikePhoto) -> ByteArray?,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): ExportedHikeArchive {
        val gpxFileName = GpxExporter.fileName(hike, zoneId)
        val stem = gpxFileName.removeSuffix(".gpx")
        val out = ByteArrayOutputStream()
        writeTo(hike = hike, stem = stem, readPhotoBytes = readPhotoBytes, output = out)
        return ExportedHikeArchive(fileName = "$stem.zip", zipBytes = out.toByteArray())
    }

    fun writeTo(hike: Hike, stem: String, readPhotoBytes: (HikePhoto) -> ByteArray?, output: OutputStream) {
        val orderedPhotos = OrderedHikePhotos.from(hike.photos).photos
        val photoLinksById = LinkedHashMap<String, String>()
        val photoEntries = ArrayList<Pair<String, ByteArray>>()
        val creditLines = ArrayList<String>()

        var exportedIndex = 0
        for (photo in orderedPhotos) {
            val bytes = if (photo.recordsPlaceOnly) null else readPhotoBytes(photo)
            if (bytes != null) {
                exportedIndex += 1
                val relativePath = "Photos/photo-$exportedIndex.${photo.pathExtension}"
                photoEntries.add("$stem/$relativePath" to bytes)
                photoLinksById[photo.id] = relativePath
                photo.license?.let { license ->
                    val author = photo.importedAuthorName?.takeIf { it.isNotBlank() } ?: "Unknown author"
                    val licenseDetail = license.url?.let { "${license.name} ($it)" } ?: license.name
                    creditLines.add("$relativePath — Photo by $author · $licenseDetail · ${license.sourceUrl}")
                }
            }
        }

        val gpxXml = GpxExporter.exportXml(hike = hike, photoArchivePaths = photoLinksById)
        ZipOutputStream(output).use { zip ->
            writeEntry(zip, "$stem/$stem.gpx", gpxXml.toByteArray(Charsets.UTF_8))
            for ((path, bytes) in photoEntries) {
                writeEntry(zip, path, bytes)
            }
            if (creditLines.isNotEmpty()) {
                val creditsBody = creditLines.joinToString(separator = "\n", postfix = "\n")
                writeEntry(zip, "$stem/Credits.txt", creditsBody.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun writeEntry(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
    }
}
