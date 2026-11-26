package app.openhikes.core.common.photos

import app.openhikes.core.common.gpx.GpxExporter
import app.openhikes.core.common.gpx.GpxParseResult
import app.openhikes.core.common.gpx.GpxParser
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikePhoto
import app.openhikes.core.model.HikePhotoLicense
import app.openhikes.core.model.RouteCoordinate
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import org.junit.Test

class HikePhotoArchiveBuilderTest {
    private val hikeId = HikeId("hike-archive")
    private val baseMillis = Instant.parse("2026-07-10T08:00:00Z").toEpochMilli()

    @Test
    fun buildsZipArchiveWithGpxLinksUnanchoredPhotosAndCommonsCredits() {
        val hike = sampleArchiveHike()
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val fakePng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        val archive = HikePhotoArchiveBuilder.build(
            hike = hike,
            readPhotoBytes = { photo ->
                when (photo.id) {
                    "p-1" -> fakeJpeg
                    "p-2" -> fakePng
                    else -> null
                }
            },
            zoneId = ZoneOffset.UTC
        )

        assertThat(archive.fileName).isEqualTo("Thumsee Loop-2026-07-10.zip")
        val entries = unzip(archive.zipBytes)
        assertThat(entries.keys).containsExactly(
            "Thumsee Loop-2026-07-10/Thumsee Loop-2026-07-10.gpx",
            "Thumsee Loop-2026-07-10/Photos/photo-1.jpeg",
            "Thumsee Loop-2026-07-10/Photos/photo-2.png",
            "Thumsee Loop-2026-07-10/Credits.txt"
        )
        assertThat(entries["Thumsee Loop-2026-07-10/Photos/photo-1.jpeg"]).isEqualTo(fakeJpeg)
        assertThat(entries["Thumsee Loop-2026-07-10/Photos/photo-2.png"]).isEqualTo(fakePng)

        val gpx = entries.getValue("Thumsee Loop-2026-07-10/Thumsee Loop-2026-07-10.gpx").toString(Charsets.UTF_8)
        assertThat(gpx).contains("<link href=\"Photos/photo-1.jpeg\"/>")
        assertThat(gpx).doesNotContain("Photos/photo-2.png")
        assertThat(gpx).doesNotContain("Photos/photo-3")

        val credits = entries.getValue("Thumsee Loop-2026-07-10/Credits.txt").toString(Charsets.UTF_8)
        assertThat(credits).contains("Alex Trail")
        assertThat(credits).contains("CC BY-SA 4.0")
    }

    @Test
    fun writeStreamingStreamsPhotosOneAtATimeAndMatchesBuildEntries() {
        val hike = sampleArchiveHike()
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val fakePng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val openStreams = AtomicInteger(0)
        val maxConcurrentOpen = AtomicInteger(0)
        val bytesById = mapOf("p-1" to fakeJpeg, "p-2" to fakePng)

        val expectedFileName = HikePhotoArchiveBuilder.fileName(hike, ZoneOffset.UTC)
        assertThat(expectedFileName).isEqualTo("Thumsee Loop-2026-07-10.zip")

        val output = ByteArrayOutputStream()
        HikePhotoArchiveBuilder.writeStreaming(
            hike = hike,
            stem = HikePhotoArchiveBuilder.archiveStem(hike, ZoneOffset.UTC),
            hasPhoto = { photo -> bytesById.containsKey(photo.id) },
            openPhotoStream = { photo ->
                bytesById[photo.id]?.let { bytes ->
                    val nowOpen = openStreams.incrementAndGet()
                    maxConcurrentOpen.accumulateAndGet(nowOpen, ::maxOf)
                    object : FilterInputStream(ByteArrayInputStream(bytes)) {
                        override fun close() {
                            super.close()
                            openStreams.decrementAndGet()
                        }
                    }
                }
            },
            output = output
        )

        assertThat(openStreams.get()).isEqualTo(0)
        assertThat(maxConcurrentOpen.get()).isEqualTo(1)
        val streamedEntries = unzip(output.toByteArray())
        val builtArchive = HikePhotoArchiveBuilder.build(
            hike = hike,
            readPhotoBytes = { photo -> bytesById[photo.id] },
            zoneId = ZoneOffset.UTC
        )
        assertThat(streamedEntries.keys).isEqualTo(unzip(builtArchive.zipBytes).keys)
        assertThat(streamedEntries["Thumsee Loop-2026-07-10/Photos/photo-1.jpeg"]).isEqualTo(fakeJpeg)
        assertThat(streamedEntries["Thumsee Loop-2026-07-10/Photos/photo-2.png"]).isEqualTo(fakePng)
    }

    private fun sampleArchiveHike(): Hike {
        val anchored = HikePhoto(
            id = "p-1",
            hikeId = hikeId,
            capturedAtEpochMillis = baseMillis + 60_000L,
            coordinate = GeoCoordinate(47.7185, 12.8318),
            importedAuthorName = "Alex Trail",
            license = HikePhotoLicense(
                name = "CC BY-SA 4.0",
                url = "https://creativecommons.org/licenses/by-sa/4.0/",
                sourceUrl = "https://commons.wikimedia.org/wiki/File:Thumsee.jpg"
            )
        )
        val unanchored = HikePhoto(
            id = "p-2",
            hikeId = hikeId,
            capturedAtEpochMillis = baseMillis + 120_000L,
            pathExtension = "png",
            coordinate = null
        )
        val missingFile = HikePhoto(
            id = "p-3",
            hikeId = hikeId,
            capturedAtEpochMillis = baseMillis + 180_000L,
            coordinate = GeoCoordinate(47.7190, 12.8325)
        )
        return Hike(
            id = hikeId,
            title = "Thumsee Loop",
            distanceMeters = 500.0,
            dateEpochMillis = baseMillis,
            route = listOf(
                RouteCoordinate(47.7180, 12.8310, timestampEpochMillis = baseMillis),
                RouteCoordinate(47.7195, 12.8330, timestampEpochMillis = baseMillis + 300_000L)
            ),
            photos = listOf(anchored, unanchored, missingFile)
        )
    }

    @Test
    fun gpxRoundTripImportsPhotoWaypointsWithoutCreatingPlaces() {
        val photo = HikePhoto(
            id = "p-1",
            hikeId = hikeId,
            capturedAtEpochMillis = baseMillis + 60_000L,
            coordinate = GeoCoordinate(47.7185, 12.8318),
            importedAuthorName = "Maria Alpine"
        )
        val hike = Hike(
            id = hikeId,
            title = "Photo Roundtrip",
            distanceMeters = 400.0,
            dateEpochMillis = baseMillis,
            route = listOf(
                RouteCoordinate(47.7180, 12.8310),
                RouteCoordinate(47.7195, 12.8330)
            ),
            photos = listOf(photo)
        )

        val xml = GpxExporter.exportXml(hike)
        val parsed = GpxParser.parseSingle(xml.toByteArray(Charsets.UTF_8)) as GpxParseResult.Success

        assertThat(parsed.value.places).isEmpty()
        assertThat(parsed.value.photos).hasSize(1)
        val importedPhoto = parsed.value.photos.first()
        assertThat(importedPhoto.capturedAtEpochMillis).isEqualTo(baseMillis + 60_000L)
        assertThat(importedPhoto.coordinate.latitude).isWithin(1e-5).of(47.7185)
        assertThat(importedPhoto.authorName).isEqualTo("Maria Alpine")
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                result[entry.name] = zis.readBytes()
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return result
    }
}
