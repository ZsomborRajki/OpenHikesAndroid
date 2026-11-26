package app.openhikes.core.model

import com.google.common.truth.Truth.assertThat
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import org.junit.Test

class RecordedClocksTest {
    private val originMillis = 1_780_000_000_000L

    private fun atMinute(minutes: Int): Long = originMillis + minutes * 60_000L

    private fun clock(startMin: Int, endMin: Int, footprint: LibraryFootprint? = null): RecordedClocks.Clock =
        RecordedClocks.Clock(atMinute(startMin), atMinute(endMin), footprint)

    @Test
    fun allOverlapShapesProduceExactUnionMinutes() {
        val cases = listOf(
            emptyList<IntRange>() to 0,
            listOf(300..360, 250..280) to 0,
            listOf(-60..0, 240..300) to 0,
            listOf(60..70) to 10,
            listOf(-30..30) to 30,
            listOf(210..270) to 30,
            listOf(0..60, 30..90) to 90,
            listOf(20..200, 50..80, 100..120) to 180,
            listOf(0..60, 60..120) to 120,
            listOf(180..200, 10..20, 100..150) to 80,
            listOf(100..100) to 0,
            listOf(-10..250) to 240,
            listOf(0..100, 90..200, 150..240) to 240,
            listOf(0..80, 80..160, 160..240) to 240
        )

        for ((ranges, expectedOverlapMinutes) in cases) {
            val clocks = RecordedClocks(ranges.map { clock(it.first, it.last) })
            val overlapSeconds = clocks.overlapSeconds(atMinute(0), atMinute(240)) { true }
            assertThat(overlapSeconds).isWithin(1e-9).of(expectedOverlapMinutes * 60.0)
        }
    }

    @Test
    fun randomizedShortAndLongClocksMatchLinearScanOracle() {
        val alps = LibraryFootprint(47.55, 47.60, 12.95, 0.05)
        val zurich = LibraryFootprint(47.36, 47.40, 8.50, 0.05)
        val grounds = listOf(alps, zurich, null)
        val month = 30 * 24 * 60

        for (seed in listOf(1L, 42L, 1118L, 1658L)) {
            val rng = Random(seed)
            val clocks = buildList {
                repeat(300) {
                    val start = rng.nextInt(-month, 2 * month)
                    val dur = rng.nextInt(0, 301)
                    add(clock(start, start + dur, grounds[rng.nextInt(grounds.size)]))
                }
                repeat(4) {
                    val start = rng.nextInt(-month, 2 * month)
                    val dur = rng.nextInt(2, 7) * 24 * 60
                    add(clock(start, start + dur, grounds[rng.nextInt(grounds.size)]))
                }
                add(clock(0, month, alps))
            }
            val tree = RecordedClocks(clocks)
            repeat(200) {
                val start = rng.nextInt(-month, 2 * month)
                val end = start + rng.nextInt(1, 601)
                val walkFootprint = grounds[rng.nextInt(grounds.size)]
                val predicate: (RecordedClocks.Clock) -> Boolean = { candidate ->
                    LibraryTotals.couldShareGround(candidate.footprint, walkFootprint)
                }
                val actual = tree.overlapSeconds(atMinute(start), atMinute(end), predicate)
                val oracle = linearOracleSeconds(clocks, atMinute(start), atMinute(end), predicate)
                assertThat(actual).isWithin(1e-9).of(oracle)
            }
        }
    }

    private fun linearOracleSeconds(
        clocks: List<RecordedClocks.Clock>,
        start: Long,
        end: Long,
        counts: (RecordedClocks.Clock) -> Boolean
    ): Double {
        val overlaps = clocks.mapNotNull { c ->
            if (!counts(c)) return@mapNotNull null
            val s = max(c.startEpochMillis, start)
            val e = min(c.endEpochMillis, end)
            if (s < e) s to e else null
        }.sortedBy { it.first }
        var total = 0L
        var reached = start
        for ((s, e) in overlaps) {
            if (e > reached) {
                total += e - max(s, reached)
                reached = e
            }
        }
        return total / 1000.0
    }
}
