package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Test

class LibraryTotalsTest {
    private val utc = ZoneOffset.UTC
    private val alps = LibraryFootprint(47.55, 47.60, 12.95, 0.05)
    private val zurich = LibraryFootprint(47.36, 47.40, 8.50, 0.05)

    private fun dateMillis(year: Int, month: Int, day: Int = 10, hour: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, utc).toInstant().toEpochMilli()

    private fun hike(
        dateEpochMillis: Long,
        meters: Double,
        id: HikeId = HikeId("h-$dateEpochMillis-$meters"),
        title: String = "Walk",
        climb: Double? = 100.0,
        highest: Double? = 1_000.0,
        hasClock: Boolean = true,
        clockDurationMillis: Long = 3L * 3_600_000L,
        isFromCommunity: Boolean = false,
        footprint: LibraryFootprint? = null
    ): LibraryHikeFacts = LibraryHikeFacts(
        hikeId = id,
        title = title,
        dateEpochMillis = dateEpochMillis,
        distanceMeters = meters,
        climbMeters = climb,
        highestMeters = highest,
        movingSeconds = if (hasClock) 3_600.0 else null,
        clockStartEpochMillis = if (hasClock) dateEpochMillis else null,
        clockEndEpochMillis = if (hasClock) dateEpochMillis + clockDurationMillis else null,
        isFromCommunity = isFromCommunity,
        footprint = footprint
    )

    private fun walk(
        hikeId: HikeId,
        startedAtEpochMillis: Long,
        covered: Double,
        walked: Double = covered,
        climb: Double = 0.0,
        durationMillis: Long = 1_800_000L,
        isRecordingsOwn: Boolean = false,
        trailHasChanged: Boolean = false,
        footprint: LibraryFootprint? = null
    ): LibraryWalkFacts = LibraryWalkFacts(
        hikeId = hikeId,
        startedAtEpochMillis = startedAtEpochMillis,
        endedAtEpochMillis = startedAtEpochMillis + durationMillis,
        coveredMeters = covered,
        walkedMeters = walked,
        activeSeconds = durationMillis / 1000.0,
        climbMeters = climb,
        isRecordingsOwn = isRecordingsOwn,
        trailHasChanged = trailHasChanged,
        footprint = footprint
    )

    private fun totals(hikes: List<LibraryHikeFacts>, walks: List<LibraryWalkFacts> = emptyList()): LibraryTotals =
        LibraryTotals.compute(
            hikes = hikes,
            walks = walks,
            asOfEpochMillis = dateMillis(2026, 6),
            zoneId = utc
        )

    @Test
    fun countsOnlyClockedNonCommunityHikesAndEachFollowedWalkCoverage() {
        val trail = HikeId("saved-trail")
        val communityTrail = HikeId("community-trail")
        val recorded = HikeId("recorded")
        val result = totals(
            hikes = listOf(
                hike(dateMillis(2026, 5), 10_000.0, id = recorded),
                hike(dateMillis(2026, 6), 25_000.0, id = trail, hasClock = false),
                hike(dateMillis(2025, 1), 12_000.0, id = communityTrail, isFromCommunity = true)
            ),
            walks = listOf(
                walk(recorded, dateMillis(2026, 5), covered = 10_000.0, isRecordingsOwn = true),
                walk(trail, dateMillis(2026, 2), covered = 4_000.0, climb = 200.0),
                walk(trail, dateMillis(2026, 3), covered = 4_000.0, climb = 200.0),
                walk(communityTrail, dateMillis(2026, 4), covered = 5_000.0, climb = 150.0)
            )
        )
        assertThat(result.thisYear.distanceMeters).isWithin(1e-6).of(23_000.0)
        assertThat(result.thisYear.climbMeters).isWithin(1e-6).of(650.0)
        assertThat(result.thisYear.outings).isEqualTo(4)
        assertThat(result.thisYear.trails).isEqualTo(3)
    }

    @Test
    fun subtractsProportionalSameGroundOverlapAndIgnoresOtherValleyClocks() {
        val trail = HikeId("trail")
        val start = dateMillis(2026, 7, 10, 12)
        val fourHours = 4L * 3_600_000L
        val tenMinutes = 600_000L

        val interiorOverlap = totals(
            hikes = listOf(
                hike(dateMillis(2025, 1), 10_000.0, id = trail, climb = 800.0, hasClock = false),
                hike(start + 3_600_000L, 500.0, clockDurationMillis = tenMinutes, footprint = alps)
            ),
            walks = listOf(
                walk(trail, start, covered = 10_000.0, climb = 800.0, durationMillis = fourHours, footprint = alps)
            )
        )
        val share = 230.0 / 240.0
        assertThat(interiorOverlap.allTime.distanceMeters).isWithin(1e-6).of(500.0 + 10_000.0 * share)
        assertThat(interiorOverlap.allTime.climbMeters).isWithin(1e-6).of(100.0 + 800.0 * share)
        assertThat(interiorOverlap.allTime.outings).isEqualTo(2)

        val otherValley = totals(
            hikes = listOf(
                hike(dateMillis(2025, 1), 10_000.0, id = trail, climb = 800.0, hasClock = false),
                hike(start, 2_000.0, clockDurationMillis = fourHours, footprint = zurich)
            ),
            walks = listOf(
                walk(trail, start, covered = 10_000.0, climb = 800.0, durationMillis = fourHours, footprint = alps)
            )
        )
        assertThat(otherValley.allTime.distanceMeters).isWithin(1e-6).of(12_000.0)
        assertThat(otherValley.allTime.outings).isEqualTo(2)

        val unknownWalkGround = totals(
            hikes = listOf(
                hike(dateMillis(2025, 1), 10_000.0, id = trail, climb = 800.0, hasClock = false),
                hike(start, 2_000.0, clockDurationMillis = fourHours, footprint = zurich)
            ),
            walks = listOf(
                walk(trail, start, covered = 10_000.0, climb = 800.0, durationMillis = fourHours, footprint = null)
            )
        )
        assertThat(unknownWalkGround.allTime.distanceMeters).isWithin(1e-6).of(2_000.0)
        assertThat(unknownWalkGround.allTime.outings).isEqualTo(1)
    }

    @Test
    fun redrawnTrailWithholdsClimbHighestAndSteepestWhileKeepingDistanceAndLongest() {
        val trail = HikeId("redrawn")
        val result = totals(
            hikes = listOf(
                hike(
                    dateMillis(2025, 1),
                    8_000.0,
                    id = trail,
                    title = "Redrawn",
                    climb = 1_000.0,
                    highest = 1_600.0,
                    hasClock = false
                )
            ),
            walks = listOf(
                walk(trail, dateMillis(2026, 5), covered = 8_000.0, climb = 1_000.0, trailHasChanged = true)
            )
        )
        assertThat(result.allTime.outings).isEqualTo(1)
        assertThat(result.allTime.distanceMeters).isWithin(1e-6).of(8_000.0)
        assertThat(result.allTime.climbMeters).isWithin(1e-6).of(0.0)
        assertThat(result.allTime.outingsWithoutClimb).isEqualTo(1)
        assertThat(result.longest?.title).isEqualTo("Redrawn")
        assertThat(result.highest).isNull()
        assertThat(result.steepest).isNull()
    }

    @Test
    fun separatesYearsMonthsAndAppliesSteepestLengthAndWalkedMetersRules() {
        val trail = HikeId("out-and-back")
        val result = totals(
            hikes = listOf(
                hike(dateMillis(2026, 1), 300.0, id = HikeId("ramp"), title = "Ramp", climb = 150.0, highest = 900.0),
                hike(
                    dateMillis(2026, 3, 1),
                    3_000.0,
                    id = HikeId("steep"),
                    title = "Steep Recorded",
                    climb = 450.0,
                    highest = 2_400.0
                ),
                hike(
                    dateMillis(2026, 3, 20),
                    1_000.0,
                    id = HikeId("short"),
                    title = "Short March",
                    climb = 10.0,
                    highest = 600.0
                ),
                hike(
                    dateMillis(2025, 12),
                    7_000.0,
                    id = HikeId("last-year"),
                    title = "Last Year",
                    climb = 300.0,
                    highest = 1_400.0
                ),
                hike(
                    dateMillis(2024, 1),
                    2_000.0,
                    id = trail,
                    title = "Out & Back",
                    climb = 400.0,
                    highest = 1_500.0,
                    hasClock = false
                )
            ),
            walks = listOf(
                // 2 km covered, 4 km walked (out and back), 400 m climb -> 10% gradient (vs 15% for Steep Recorded).
                walk(trail, dateMillis(2026, 4), covered = 2_000.0, walked = 4_000.0, climb = 400.0)
            )
        )
        assertThat(result.year).isEqualTo(2026)
        assertThat(result.previousYear).isEqualTo(2025)
        assertThat(result.monthsThisYear[2].distanceMeters).isWithin(1e-6).of(4_000.0)
        assertThat(result.monthsLastYear[11].distanceMeters).isWithin(1e-6).of(7_000.0)
        assertThat(result.longest?.title).isEqualTo("Last Year")
        assertThat(result.highest?.title).isEqualTo("Steep Recorded")
        assertThat(result.steepest?.title).isEqualTo("Steep Recorded")
        assertThat(result.steepest!!.gradient).isWithin(1e-6).of(0.15)
    }

    @Test
    fun emptyLibraryHasTwelveZeroMonthsAndNoRecords() {
        val result = totals(emptyList())
        assertThat(result.allTime.outings).isEqualTo(0)
        assertThat(result.longest).isNull()
        assertThat(result.highest).isNull()
        assertThat(result.steepest).isNull()
        assertThat(result.monthsThisYear).hasSize(12)
        assertThat(result.monthsThisYear.all { it.distanceMeters == 0.0 }).isTrue()
    }
}
