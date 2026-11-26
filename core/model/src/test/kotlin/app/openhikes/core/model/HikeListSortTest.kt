package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

class HikeListSortTest {
    private val dayMillis = 86_400_000L
    private val baseEpoch = 1_700_000_000_000L

    private fun sampleHikes(): List<HikeSummary> = listOf(
        HikeSummary(
            id = HikeId("short"),
            title = "Short",
            distanceMeters = 2_000.0,
            dateEpochMillis = baseEpoch - 2 * dayMillis,
            walkCount = 5
        ),
        HikeSummary(
            id = HikeId("middling"),
            title = "Middling",
            distanceMeters = 8_000.0,
            dateEpochMillis = baseEpoch,
            walkCount = 1
        ),
        HikeSummary(
            id = HikeId("long"),
            title = "Long",
            distanceMeters = 20_000.0,
            dateEpochMillis = baseEpoch - dayMillis,
            walkCount = 3
        )
    )

    @Test
    fun sortsByNewestLongestMostWalkedAndAlphabeticalWithDiacriticFolding() {
        val hikes = sampleHikes()
        assertThat(HikeListOrder.arrange(hikes, activeHikeId = null, sort = HikeListSort.NEWEST).map { it.title })
            .containsExactly("Middling", "Long", "Short").inOrder()
        assertThat(HikeListOrder.arrange(hikes, activeHikeId = null, sort = HikeListSort.LONGEST).map { it.title })
            .containsExactly("Long", "Middling", "Short").inOrder()
        assertThat(HikeListOrder.arrange(hikes, activeHikeId = null, sort = HikeListSort.MOST_WALKED).map { it.title })
            .containsExactly("Short", "Long", "Middling").inOrder()

        val accented = listOf(
            HikeSummary(id = HikeId("z"), title = "zebra trail", distanceMeters = 1_000.0, dateEpochMillis = baseEpoch),
            HikeSummary(id = HikeId("e"), title = "Écrins Loop", distanceMeters = 1_000.0, dateEpochMillis = baseEpoch),
            HikeSummary(id = HikeId("a"), title = "Alpine Way", distanceMeters = 1_000.0, dateEpochMillis = baseEpoch)
        )
        assertThat(
            HikeListOrder.arrange(accented, activeHikeId = null, sort = HikeListSort.ALPHABETICAL, locale = Locale.US)
                .map { it.title }
        ).containsExactly("Alpine Way", "Écrins Loop", "zebra trail").inOrder()
    }

    @Test
    fun hilliestSortsUnmeasuredAndHalfMeasuredLastWithDateTieBreak() {
        val hikes = sampleHikes().map { hike ->
            when (hike.id.value) {
                "long" -> hike.copy(climbMeters = 100.0, descentMeters = 100.0)
                "short" -> hike.copy(climbMeters = 5_000.0, descentMeters = null)
                else -> hike
            }
        }
        assertThat(HikeListOrder.arrange(hikes, activeHikeId = null, sort = HikeListSort.HILLIEST).map { it.title })
            .containsExactly("Long", "Middling", "Short").inOrder()
        assertThat(HikeListOrder.hikesForElevationValidation(hikes, HikeListSort.NEWEST)).isEmpty()
        assertThat(HikeListOrder.hikesForElevationValidation(hikes, HikeListSort.LONGEST)).isEmpty()
        assertThat(HikeListOrder.hikesForElevationValidation(hikes, HikeListSort.HILLIEST))
            .containsExactlyElementsIn(hikes.map { it.id }).inOrder()
    }

    @Test
    fun customOrderOutranksSortPutsNewHikesAtTopAndPinsActiveHikeWithoutRenumbering() {
        val hikes = sampleHikes()
        val orderedByLength = HikeListOrder.arrange(hikes, activeHikeId = null, sort = HikeListSort.LONGEST)
        val movedIds = HikeListOrder.move(orderedByLength, fromIndex = 2, toIndex = 0)
        assertThat(movedIds).containsExactly(HikeId("short"), HikeId("long"), HikeId("middling")).inOrder()

        val orderMap = movedIds!!.mapIndexed { idx, id -> id to idx }.toMap()
        val customHikes = hikes.map { it.copy(listOrder = orderMap[it.id]) }
        assertThat(HikeListOrder.isCustom(customHikes)).isTrue()
        assertThat(
            HikeListOrder.arrange(customHikes, activeHikeId = null, sort = HikeListSort.LONGEST).map {
                it.title
            }
        )
            .containsExactly("Short", "Long", "Middling").inOrder()

        val withFresh = customHikes + HikeSummary(
            id = HikeId("fresh"),
            title = "Just Walked",
            distanceMeters = 500.0,
            dateEpochMillis = baseEpoch + dayMillis,
            listOrder = null
        )
        assertThat(HikeListOrder.arrange(withFresh, activeHikeId = null, sort = HikeListSort.LONGEST).first().title)
            .isEqualTo("Just Walked")

        val pinned = HikeListOrder.arrange(customHikes, activeHikeId = HikeId("middling"), sort = HikeListSort.LONGEST)
        assertThat(pinned.map { it.title }).containsExactly("Middling", "Short", "Long").inOrder()
        assertThat(HikeListOrder.move(pinned, fromIndex = 0, toIndex = 2, activeHikeId = HikeId("middling"))).isNull()
        assertThat(HikeListOrder.move(pinned, fromIndex = 2, toIndex = 0, activeHikeId = HikeId("middling")))
            .containsExactly(HikeId("middling"), HikeId("long"), HikeId("short")).inOrder()
    }

    @Test
    fun elevationRevisionChangesOnElevationOrPauseAndMatchesDeadbandAccumulator() {
        val noHeights = listOf(
            RouteCoordinate(47.7, 12.8),
            RouteCoordinate(47.701, 12.8),
            RouteCoordinate(47.702, 12.8)
        )
        val noisyFlat = listOf(
            RouteCoordinate(47.7, 12.8, elevationMeters = 500.0),
            RouteCoordinate(47.701, 12.8, elevationMeters = 501.0),
            RouteCoordinate(47.702, 12.8, elevationMeters = 500.0),
            RouteCoordinate(47.703, 12.8, elevationMeters = 501.0)
        )
        val climbed = listOf(
            RouteCoordinate(47.7, 12.8, elevationMeters = 500.0),
            RouteCoordinate(47.701, 12.8, elevationMeters = 520.0),
            RouteCoordinate(47.702, 12.8, elevationMeters = 510.0)
        )
        val paused = listOf(
            RouteCoordinate(47.7, 12.8, elevationMeters = 500.0),
            RouteCoordinate(47.701, 12.8, elevationMeters = 520.0, boundary = RouteBoundary.PAUSED),
            RouteCoordinate(47.702, 12.8, elevationMeters = 510.0)
        )
        val noHeightMeasured = RouteElevationRevision.measure(noHeights)
        assertThat(noHeightMeasured.climbMeters).isNull()
        assertThat(noHeightMeasured.descentMeters).isNull()

        val noisyMeasured = RouteElevationRevision.measure(noisyFlat)
        assertThat(noisyMeasured.climbMeters).isWithin(1e-6).of(1.0)
        assertThat(noisyMeasured.descentMeters).isWithin(1e-6).of(0.0)

        val climbMeasured = RouteElevationRevision.measure(climbed)
        assertThat(climbMeasured.climbMeters).isWithin(1e-6).of(20.0)
        assertThat(climbMeasured.descentMeters).isWithin(1e-6).of(10.0)
        assertThat(climbMeasured.revision).isNotEqualTo(noHeightMeasured.revision)
        assertThat(RouteElevationRevision.of(paused)).isNotEqualTo(climbMeasured.revision)
    }
}
