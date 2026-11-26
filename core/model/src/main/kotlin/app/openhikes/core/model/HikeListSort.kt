package app.openhikes.core.model

import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * Built-in sort orders for the hike library.
 *
 * Keys are computed once per hike (`O(n)`) before sorting (`O(n log n)`) so locale folding and
 * derived figures never run inside comparators. Missing derived figures (such as unmeasured
 * relief on [HILLIEST]) always sort last rather than as zero.
 */
@Serializable
enum class HikeListSort(val rawValue: String) {
    ALPHABETICAL("alphabetical"),
    HILLIEST("hilliest"),
    LONGEST("longest"),
    MOST_WALKED("mostWalked"),
    NEWEST("newest");

    /**
     * Only [HILLIEST] depends on cached route climb + descent figures.
     */
    val needsElevation: Boolean
        get() = this == HILLIEST

    /**
     * Precomputed sort key read once per hike before sorting.
     */
    data class Key(val figure: Double?, val foldedName: String, val dateEpochMillis: Long, val idValue: String)

    fun key(hike: HikeSummary, locale: Locale = Locale.getDefault()): Key = when (this) {
        NEWEST -> Key(
            figure = null,
            foldedName = "",
            dateEpochMillis = hike.dateEpochMillis,
            idValue = hike.id.value
        )

        ALPHABETICAL -> Key(
            figure = null,
            foldedName = foldTitle(hike.displayTitle, locale),
            dateEpochMillis = hike.dateEpochMillis,
            idValue = hike.id.value
        )

        LONGEST -> Key(
            figure = hike.distanceMeters,
            foldedName = "",
            dateEpochMillis = hike.dateEpochMillis,
            idValue = hike.id.value
        )

        MOST_WALKED -> Key(
            figure = hike.walkCount.toDouble(),
            foldedName = "",
            dateEpochMillis = hike.dateEpochMillis,
            idValue = hike.id.value
        )

        HILLIEST -> Key(
            figure = hike.verticalMeters,
            foldedName = "",
            dateEpochMillis = hike.dateEpochMillis,
            idValue = hike.id.value
        )
    }

    fun compareKeys(first: Key, second: Key): Int = if (this == ALPHABETICAL) {
        val nameOrder = first.foldedName.compareTo(second.foldedName)
        if (nameOrder != 0) nameOrder else compareByDateDescThenId(first, second)
    } else {
        compareFigureDesc(first, second)
    }

    private fun compareFigureDesc(first: Key, second: Key): Int {
        val left = first.figure
        val right = second.figure
        return when {
            left != null && right != null -> {
                val valueOrder = right.compareTo(left)
                if (valueOrder != 0) valueOrder else compareByDateDescThenId(first, second)
            }
            left != null -> -1
            right != null -> 1
            else -> compareByDateDescThenId(first, second)
        }
    }

    private fun compareByDateDescThenId(first: Key, second: Key): Int {
        val dateOrder = second.dateEpochMillis.compareTo(first.dateEpochMillis)
        return if (dateOrder != 0) dateOrder else first.idValue.compareTo(second.idValue)
    }

    companion object {
        private val COMBINING_MARKS_REGEX = Regex("\\p{M}+")

        val Default: HikeListSort = NEWEST

        val menuOrder: List<HikeListSort> = listOf(
            NEWEST,
            ALPHABETICAL,
            LONGEST,
            MOST_WALKED,
            HILLIEST
        )

        fun fromStoredId(rawValue: String?): HikeListSort = entries.firstOrNull { it.rawValue == rawValue } ?: Default

        internal fun foldTitle(title: String, locale: Locale): String {
            val decomposed = Normalizer.normalize(title, Normalizer.Form.NFKD)
            return COMBINING_MARKS_REGEX.replace(decomposed, "").lowercase(locale)
        }
    }
}

/**
 * Pure arrangement and custom-reordering rules for the hike library.
 *
 * A library is either in a built-in [HikeListSort] order or in the hiker's own manual order
 * (indicated by any listed hike carrying a non-null [HikeSummary.listOrder]). The active hike
 * (recording or being walked) is always pinned at the top without altering its stored position.
 */
object HikeListOrder {
    fun isCustom(hikes: List<HikeSummary>): Boolean = hikes.any { it.listOrder != null }

    fun arrange(
        hikes: List<HikeSummary>,
        activeHikeId: HikeId?,
        sort: HikeListSort = HikeListSort.Default,
        locale: Locale = Locale.getDefault()
    ): List<HikeSummary> {
        if (hikes.isEmpty()) return emptyList()
        val active = activeHikeId?.let { id -> hikes.firstOrNull { it.id == id } }
        val unpinned = if (active == null) hikes else hikes.filterNot { it.id == active.id }
        val orderedRest = if (isCustom(hikes)) {
            unpinned.sortedWith(
                compareBy<HikeSummary> { it.listOrder ?: Int.MIN_VALUE }
                    .thenByDescending { it.dateEpochMillis }
                    .thenBy { it.id.value }
            )
        } else {
            val keyed = unpinned.map { hike -> sort.key(hike, locale) to hike }
            keyed.sortedWith { left, right -> sort.compareKeys(left.first, right.first) }
                .map { it.second }
        }
        return if (active != null) listOf(active) + orderedRest else orderedRest
    }

    /**
     * Moves the row at [fromIndex] in [displayed] to [toIndex] and returns the complete dense
     * `0..n-1` position assignment for all displayed hikes, or `null` if the move is invalid
     * (such as attempting to move the pinned [activeHikeId]).
     */
    fun move(displayed: List<HikeSummary>, fromIndex: Int, toIndex: Int, activeHikeId: HikeId? = null): List<HikeId>? {
        val pinnedCount = if (activeHikeId != null && displayed.firstOrNull()?.id == activeHikeId) 1 else 0
        val clampedTo = toIndex.coerceIn(pinnedCount, displayed.lastIndex)
        val valid = fromIndex in pinnedCount..displayed.lastIndex &&
            clampedTo in pinnedCount..displayed.lastIndex &&
            fromIndex != clampedTo
        if (!valid) return null
        val mutable = displayed.map { it.id }.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(clampedTo, moved)
        return mutable
    }

    /**
     * Returns hike IDs whose cached elevation figures must be validated for [sort].
     */
    fun hikesForElevationValidation(hikes: List<HikeSummary>, sort: HikeListSort): List<HikeId> =
        if (sort.needsElevation) hikes.map { it.id } else emptyList()
}

/**
 * Result of measuring a route's deadband-filtered climb, descent, and revision fingerprint.
 */
data class RouteElevationMeasurement(val climbMeters: Double?, val descentMeters: Double?, val revision: String)

/**
 * Stable cross-launch FNV-1a fingerprint over route coordinates, elevations, and pause boundaries
 * used to detect stale cached [HikeLocalState] climb/descent figures.
 */
object RouteElevationRevision {
    private const val FNV_OFFSET_BASIS = -3750763034362895579L // 0xcbf29ce484222325
    private const val FNV_PRIME = 1099511628211L // 0x100000001b3
    private const val REVISION_RADIX = 16
    private const val BYTE_MASK = 0xFFL
    private const val TAG_HEIGHT = 1L
    private const val TAG_MISSING = 2L
    private const val TAG_PAUSE = 3L
    private const val TAG_WALK = 4L

    fun of(route: List<RouteCoordinate>): String {
        var hash = FNV_OFFSET_BASIS
        for (point in route) {
            hash = mixLong(hash, point.latitude.toRawBits())
            hash = mixLong(hash, point.longitude.toRawBits())
            val elevation = point.elevationMeters
            if (elevation != null && elevation.isFinite()) {
                hash = mixByte(hash, TAG_HEIGHT)
                hash = mixLong(hash, elevation.toRawBits())
            } else {
                hash = mixByte(hash, TAG_MISSING)
            }
            hash = mixByte(hash, if (point.isPauseBoundary) TAG_PAUSE else TAG_WALK)
        }
        return hash.toULong().toString(REVISION_RADIX)
    }

    fun measure(route: List<RouteCoordinate>): RouteElevationMeasurement {
        val accumulator = ElevationAccumulator()
        for (point in route) {
            accumulator.record(point)
        }
        val hasChange = accumulator.hasChange
        return RouteElevationMeasurement(
            climbMeters = if (hasChange) accumulator.gainMeters else null,
            descentMeters = if (hasChange) accumulator.lossMeters else null,
            revision = of(route)
        )
    }

    private fun mixByte(seed: Long, value: Long): Long = (seed xor value) * FNV_PRIME

    private fun mixLong(seed: Long, value: Long): Long {
        var hash = seed
        for (shift in 0 until Long.SIZE_BITS step Byte.SIZE_BITS) {
            hash = (hash xor ((value ushr shift) and BYTE_MASK)) * FNV_PRIME
        }
        return hash
    }
}
