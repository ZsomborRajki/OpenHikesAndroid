package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HikePhotoModelsTest {
    private val hikeId = HikeId("hike-1")

    @Test
    fun orderedPhotosSortChronologicallyWithIdTieBreakAndSupportNextAfterDelete() {
        val late = HikePhoto(id = "p-late", hikeId = hikeId, capturedAtEpochMillis = 3_000L)
        val tieB = HikePhoto(id = "p-b", hikeId = hikeId, capturedAtEpochMillis = 1_000L)
        val tieA = HikePhoto(id = "p-a", hikeId = hikeId, capturedAtEpochMillis = 1_000L)

        val ordered = OrderedHikePhotos.from(listOf(late, tieB, tieA))

        assertThat(ordered.photos.map { it.id }).containsExactly("p-a", "p-b", "p-late").inOrder()
        assertThat(ordered.indexOf("p-b")).isEqualTo(1)
        assertThat(ordered.nextIdAfterDeleting("p-a")).isEqualTo("p-b")
        assertThat(ordered.nextIdAfterDeleting("p-b")).isEqualTo("p-late")
        assertThat(ordered.nextIdAfterDeleting("p-late")).isEqualTo("p-b")
        assertThat(OrderedHikePhotos.from(listOf(tieA)).nextIdAfterDeleting("p-a")).isNull()
    }

    @Test
    fun photoMapPinsGroupByExactCoordinatePreservingGalleryOrderAndDropUnanchored() {
        val spotA = GeoCoordinate(47.7185, 12.8318)
        val spotB = GeoCoordinate(47.7210, 12.8350)
        val ordered = OrderedHikePhotos.from(
            listOf(
                HikePhoto(id = "p1", hikeId = hikeId, capturedAtEpochMillis = 1_000L, coordinate = spotA),
                HikePhoto(id = "p-unanchored", hikeId = hikeId, capturedAtEpochMillis = 2_000L, coordinate = null),
                HikePhoto(id = "p2", hikeId = hikeId, capturedAtEpochMillis = 3_000L, coordinate = spotB),
                HikePhoto(id = "p3", hikeId = hikeId, capturedAtEpochMillis = 4_000L, coordinate = spotA)
            )
        )

        val pins = PhotoMapPin.pinsFor(ordered.photos)

        assertThat(pins).hasSize(2)
        assertThat(pins[0].photo.id).isEqualTo("p1")
        assertThat(pins[0].photoIds).containsExactly("p1", "p3").inOrder()
        assertThat(pins[0].count).isEqualTo(2)
        assertThat(pins[0].contains("p3")).isTrue()
        assertThat(pins[1].photo.id).isEqualTo("p2")
        assertThat(pins[1].count).isEqualTo(1)
    }

    @Test
    fun photoFlagsEvidenceAndCreditPlacementMatchContracts() {
        val own = HikePhoto(id = "own", hikeId = hikeId, capturedAtEpochMillis = 1_000L)
        val commons = HikePhoto(
            id = "commons",
            hikeId = hikeId,
            capturedAtEpochMillis = 2_000L,
            importedAuthorName = "Jane Doe",
            license = HikePhotoLicense(
                name = "CC BY-SA 4.0",
                url = "https://creativecommons.org/licenses/by-sa/4.0/",
                sourceUrl = "https://commons.wikimedia.org/wiki/File:Trail.jpg"
            )
        )
        val placeOnly = HikePhoto(id = "gpx", hikeId = hikeId, capturedAtEpochMillis = 3_000L, isPlaceOnly = true)

        assertThat(own.isOwn).isTrue()
        assertThat(own.fileName).isEqualTo("own.jpeg")
        assertThat(own.thumbnailFileName).isEqualTo("own.jpeg")
        assertThat(commons.isOwn).isFalse()
        assertThat(placeOnly.recordsPlaceOnly).isTrue()
        assertThat(PhotoMatchEvidence.fromStoredId("timeAndPlace")).isEqualTo(PhotoMatchEvidence.TIME_AND_PLACE)
        assertThat(PhotoMatchEvidence.fromStoredId("unknown")).isNull()

        assertThat(PhotoCreditPlacement.resolve(viewportWidth = 800f, viewportHeight = 400f))
            .isEqualTo(PhotoCreditPlacement.BESIDE)
        assertThat(PhotoCreditPlacement.resolve(viewportWidth = 320f, viewportHeight = 400f))
            .isEqualTo(PhotoCreditPlacement.UNDERNEATH)
        assertThat(PhotoCreditPlacement.resolve(viewportWidth = 600f, viewportHeight = 400f))
            .isEqualTo(PhotoCreditPlacement.UNDERNEATH)
    }
}
