package app.openhikes.core.model

import java.time.Instant
import java.time.Year
import java.time.ZoneId
import kotlin.math.max

/**
 * Single outing counted toward [LibraryTotals]: either a clocked hike or an unrecorded share
 * of a followed walk along a saved trail.
 */
data class LibraryOuting(
    val hikeId: HikeId,
    val dateEpochMillis: Long,
    /** Distance summed into totals and compared for Longest (ground covered once for walks). */
    val distanceMeters: Double,
    /** Distance walked across each direction, used as Steepest's denominator. */
    val walkedMeters: Double,
    /** `null` when a walk's trail was redrawn since it was walked. */
    val climbMeters: Double?,
    val movingSeconds: Double,
    val isWalk: Boolean
)

/**
 * Outing candidate eligible for the Longest, Highest, and Steepest records.
 */
data class LibraryRecordCandidate(
    val hikeId: HikeId,
    val title: String,
    val distanceMeters: Double,
    val walkedMeters: Double,
    val climbMeters: Double?,
    val highestMeters: Double?
) {
    val gradient: Double
        get() = if (walkedMeters > 0.0) (climbMeters ?: 0.0) / walkedMeters else 0.0
}

/**
 * Hike metadata extracted off-main for summing [LibraryTotals].
 */
data class LibraryHikeFacts(
    val hikeId: HikeId,
    val title: String,
    val dateEpochMillis: Long,
    val distanceMeters: Double,
    val climbMeters: Double?,
    val highestMeters: Double?,
    val movingSeconds: Double?,
    val clockStartEpochMillis: Long?,
    val clockEndEpochMillis: Long?,
    val isFromCommunity: Boolean = false,
    val footprint: LibraryFootprint? = null
) {
    val hasClock: Boolean
        get() = clockStartEpochMillis != null && clockEndEpochMillis != null
}

/**
 * Walk metadata extracted off-main for summing [LibraryTotals].
 */
data class LibraryWalkFacts(
    val hikeId: HikeId,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val coveredMeters: Double,
    val walkedMeters: Double,
    val activeSeconds: Double,
    val climbMeters: Double,
    val isRecordingsOwn: Boolean,
    val trailHasChanged: Boolean,
    val footprint: LibraryFootprint? = null
)

/**
 * Yearly, prior-year, monthly, and all-time hiking totals plus personal records computed
 * from portable hikes and walk history with same-ground overlap deduplication.
 */
data class LibraryTotals(
    val year: Int,
    val previousYear: Int,
    val thisYear: Figures,
    val lastYear: Figures,
    val allTime: Figures,
    val monthsThisYear: List<MonthDistance>,
    val monthsLastYear: List<MonthDistance>,
    val longest: LibraryRecordCandidate?,
    val highest: LibraryRecordCandidate?,
    val steepest: LibraryRecordCandidate?
) {
    data class Figures(
        val distanceMeters: Double = 0.0,
        val climbMeters: Double = 0.0,
        val movingSeconds: Double = 0.0,
        val outings: Int = 0,
        val trails: Int = 0,
        val outingsWithoutClimb: Int = 0
    ) {
        companion object {
            fun from(outings: List<LibraryOuting>): Figures {
                var distance = 0.0
                var climb = 0.0
                var moving = 0.0
                var withoutClimb = 0
                val trailIds = HashSet<HikeId>()
                for (outing in outings) {
                    distance += outing.distanceMeters
                    val outingClimb = outing.climbMeters
                    if (outingClimb != null) {
                        climb += outingClimb
                    } else {
                        withoutClimb += 1
                    }
                    moving += outing.movingSeconds
                    trailIds.add(outing.hikeId)
                }
                return Figures(
                    distanceMeters = distance,
                    climbMeters = climb,
                    movingSeconds = moving,
                    outings = outings.size,
                    trails = trailIds.size,
                    outingsWithoutClimb = withoutClimb
                )
            }
        }
    }

    data class MonthDistance(
        val monthNumber: Int,
        val startEpochMillis: Long,
        val endEpochMillis: Long,
        val distanceMeters: Double
    )

    data class MonthColumn(val monthNumber: Int, val thisYear: MonthDistance?, val lastYear: MonthDistance?)

    val monthColumns: List<MonthColumn>
        get() {
            val thisByMonth = monthsThisYear.associateBy { it.monthNumber }
            val lastByMonth = monthsLastYear.associateBy { it.monthNumber }
            val allMonths = (thisByMonth.keys + lastByMonth.keys).sorted()
            return allMonths.map { month ->
                MonthColumn(
                    monthNumber = month,
                    thisYear = thisByMonth[month],
                    lastYear = lastByMonth[month]
                )
            }
        }

    companion object {
        const val SAME_GROUND_MARGIN_METERS = 200.0
        const val STEEPEST_MINIMUM_METERS = 1_000.0
        private const val MILLIS_PER_SECOND = 1000.0
        private const val MONTHS_PER_YEAR = 12

        fun compute(
            hikes: List<LibraryHikeFacts>,
            walks: List<LibraryWalkFacts>,
            asOfEpochMillis: Long,
            zoneId: ZoneId = ZoneId.systemDefault()
        ): LibraryTotals {
            val allOutings = outings(hikes = hikes, walks = walks)
            val nowZoned = Instant.ofEpochMilli(asOfEpochMillis).atZone(zoneId)
            val currentYear = nowZoned.year
            val priorYear = currentYear - 1
            val currentBounds = yearBounds(currentYear, zoneId)
            val priorBounds = yearBounds(priorYear, zoneId)

            val currentOutings = allOutings.filter {
                it.dateEpochMillis >= currentBounds.first && it.dateEpochMillis < currentBounds.second
            }
            val priorOutings = allOutings.filter {
                it.dateEpochMillis >= priorBounds.first && it.dateEpochMillis < priorBounds.second
            }
            val candidates = buildRecordCandidates(hikes, allOutings)
            return LibraryTotals(
                year = currentYear,
                previousYear = priorYear,
                thisYear = Figures.from(currentOutings),
                lastYear = Figures.from(priorOutings),
                allTime = Figures.from(allOutings),
                monthsThisYear = bucketMonths(currentYear, currentOutings, zoneId),
                monthsLastYear = bucketMonths(priorYear, priorOutings, zoneId),
                longest = candidates.maxWithOrNull(compareBy { it.distanceMeters }),
                highest = candidates.filter { it.highestMeters != null }
                    .maxWithOrNull(compareBy { it.highestMeters ?: Double.NEGATIVE_INFINITY }),
                steepest = candidates.filter {
                    it.distanceMeters >= STEEPEST_MINIMUM_METERS && (it.climbMeters ?: 0.0) > 0.0
                }.maxWithOrNull(compareBy { it.gradient })
            )
        }

        fun outings(hikes: List<LibraryHikeFacts>, walks: List<LibraryWalkFacts>): List<LibraryOuting> {
            val countedHikes = hikes.filter { it.hasClock && !it.isFromCommunity }
            val clocks = RecordedClocks(
                countedHikes.mapNotNull { hike ->
                    val start = hike.clockStartEpochMillis ?: return@mapNotNull null
                    val end = hike.clockEndEpochMillis ?: return@mapNotNull null
                    RecordedClocks.Clock(
                        startEpochMillis = start,
                        endEpochMillis = max(start, end),
                        footprint = hike.footprint
                    )
                }
            )
            val clockedOutings = countedHikes.map { hike ->
                LibraryOuting(
                    hikeId = hike.hikeId,
                    dateEpochMillis = hike.dateEpochMillis,
                    distanceMeters = hike.distanceMeters,
                    walkedMeters = hike.distanceMeters,
                    climbMeters = hike.climbMeters ?: 0.0,
                    movingSeconds = hike.movingSeconds ?: 0.0,
                    isWalk = false
                )
            }
            val followedOutings = walks.mapNotNull { walk ->
                if (walk.isRecordingsOwn) return@mapNotNull null
                val share = unrecordedShare(walk, clocks)
                if (share <= 0.0) return@mapNotNull null
                LibraryOuting(
                    hikeId = walk.hikeId,
                    dateEpochMillis = walk.startedAtEpochMillis,
                    distanceMeters = walk.coveredMeters * share,
                    walkedMeters = walk.walkedMeters * share,
                    climbMeters = if (walk.trailHasChanged) null else walk.climbMeters * share,
                    movingSeconds = walk.activeSeconds * share,
                    isWalk = true
                )
            }
            return clockedOutings + followedOutings
        }

        fun couldShareGround(clocked: LibraryFootprint?, walked: LibraryFootprint?): Boolean {
            if (clocked == null || walked == null) return true
            return clocked.meets(walked, SAME_GROUND_MARGIN_METERS)
        }

        private fun unrecordedShare(walk: LibraryWalkFacts, clocks: RecordedClocks): Double {
            val lengthSeconds = (walk.endedAtEpochMillis - walk.startedAtEpochMillis) / MILLIS_PER_SECOND
            if (lengthSeconds <= 0.0) return 1.0
            val overlapSeconds = clocks.overlapSeconds(
                startEpochMillis = walk.startedAtEpochMillis,
                endEpochMillis = walk.endedAtEpochMillis
            ) { clock ->
                couldShareGround(clock.footprint, walk.footprint)
            }
            return max(0.0, 1.0 - overlapSeconds / lengthSeconds)
        }

        private fun buildRecordCandidates(
            hikes: List<LibraryHikeFacts>,
            outings: List<LibraryOuting>
        ): List<LibraryRecordCandidate> {
            val byId = LinkedHashMap<HikeId, LibraryHikeFacts>(hikes.size)
            for (hike in hikes) {
                byId.putIfAbsent(hike.hikeId, hike)
            }
            return outings.mapNotNull { outing ->
                val hike = byId[outing.hikeId] ?: return@mapNotNull null
                LibraryRecordCandidate(
                    hikeId = hike.hikeId,
                    title = hike.title,
                    distanceMeters = outing.distanceMeters,
                    walkedMeters = outing.walkedMeters,
                    climbMeters = if (hike.climbMeters == null) null else outing.climbMeters,
                    highestMeters = if (outing.climbMeters == null) null else hike.highestMeters
                )
            }
        }

        private fun yearBounds(year: Int, zoneId: ZoneId): Pair<Long, Long> {
            val start = Year.of(year).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = Year.of(year + 1).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            return start to end
        }

        private fun bucketMonths(year: Int, outings: List<LibraryOuting>, zoneId: ZoneId): List<MonthDistance> {
            val starts = LongArray(MONTHS_PER_YEAR)
            val ends = LongArray(MONTHS_PER_YEAR)
            val sums = DoubleArray(MONTHS_PER_YEAR)
            var cursor = Year.of(year).atDay(1).atStartOfDay(zoneId)
            for (monthIdx in 0 until MONTHS_PER_YEAR) {
                val next = cursor.plusMonths(1)
                starts[monthIdx] = cursor.toInstant().toEpochMilli()
                ends[monthIdx] = next.toInstant().toEpochMilli()
                cursor = next
            }
            for (outing in outings) {
                for (monthIdx in 0 until MONTHS_PER_YEAR) {
                    if (outing.dateEpochMillis >= starts[monthIdx] && outing.dateEpochMillis < ends[monthIdx]) {
                        sums[monthIdx] += outing.distanceMeters
                        break
                    }
                }
            }
            return List(MONTHS_PER_YEAR) { monthIdx ->
                MonthDistance(
                    monthNumber = monthIdx + 1,
                    startEpochMillis = starts[monthIdx],
                    endEpochMillis = ends[monthIdx],
                    distanceMeters = sums[monthIdx]
                )
            }
        }
    }
}
