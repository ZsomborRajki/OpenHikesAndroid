package app.openhikes.core.model

/**
 * A category a stretch of route can be attributed to by reading one OpenStreetMap tag.
 *
 * Kept free of UI concepts (colors, display strings) so pure-JVM trail analysis remains decoupled
 * from Android resources and Compose.
 */
interface TrailCategory {
    val rawValue: String
    val isSurveyed: Boolean
    val displayOrder: Int
}

/**
 * Hiking surface categories distilled from OpenStreetMap `surface` (and fallback `tracktype`) tags.
 */
enum class TrailSurface(override val rawValue: String) : TrailCategory {
    PAVED("paved"),
    GRAVEL("gravel"),
    GROUND("ground"),
    ROCK("rock"),
    UNKNOWN("unknown"),
    UNMAPPED("unmapped");

    override val isSurveyed: Boolean
        get() = this != UNKNOWN && this != UNMAPPED

    override val displayOrder: Int
        get() = displayOrdering.indexOf(this)

    companion object {
        /**
         * Hardest surface first, with the two unsurveyed categories last; also serves as the
         * deterministic tie-breaker when two surfaces cover equal distance.
         */
        val displayOrdering: List<TrailSurface> = listOf(
            PAVED,
            GRAVEL,
            GROUND,
            ROCK,
            UNKNOWN,
            UNMAPPED
        )

        private val pavedValues: Set<String> = setOf(
            "paved",
            "asphalt",
            "chipseal",
            "concrete",
            "concrete:lanes",
            "concrete:plates",
            "paving_stones",
            "paving_stones:lanes",
            "grass_paver",
            "sett",
            "cobblestone",
            "unhewn_cobblestone",
            "bricks",
            "brick",
            "metal",
            "metal_grid",
            "wood",
            "stone",
            "rubber",
            "tartan",
            "acrylic"
        )

        private val gravelValues: Set<String> = setOf(
            "gravel",
            "fine_gravel",
            "compacted",
            "pebblestone",
            "shells",
            "gravel_turf"
        )

        private val groundValues: Set<String> = setOf(
            "unpaved",
            "ground",
            "dirt",
            "earth",
            "soil",
            "grass",
            "mud",
            "sand",
            "woodchips",
            "snow",
            "ice",
            "salt",
            "clay"
        )

        private val rockValues: Set<String> = setOf(
            "rock",
            "bare_rock",
            "scree",
            "stepping_stones"
        )

        /**
         * Classifies an OpenStreetMap way from its `surface` tag, falling back to `tracktype` only
         * when `surface` is missing or unrecognized.
         */
        fun fromOsm(surface: String?, tracktype: String? = null): TrailSurface {
            val fromSurface = surface?.let(::categoryForSurface)
            if (fromSurface != null) return fromSurface
            val fromTracktype = tracktype?.let(::categoryForTracktype)
            return fromTracktype ?: UNKNOWN
        }

        fun fromEdge(edge: TrailGraphEdge): TrailSurface = fromOsm(surface = edge.surface, tracktype = edge.tracktype)

        fun fromStoredId(id: String?): TrailSurface? {
            if (id.isNullOrBlank()) return null
            return entries.firstOrNull { it.rawValue == id }
        }

        private fun categoryForSurface(value: String): TrailSurface? {
            val leading = value.substringBefore(';').trim().lowercase()
            return when {
                leading.isEmpty() -> null
                pavedValues.contains(leading) -> PAVED
                gravelValues.contains(leading) -> GRAVEL
                groundValues.contains(leading) -> GROUND
                rockValues.contains(leading) -> ROCK
                else -> null
            }
        }

        private fun categoryForTracktype(value: String): TrailSurface? = when (value.trim().lowercase()) {
            "grade1" -> PAVED
            "grade2", "grade3" -> GRAVEL
            "grade4", "grade5" -> GROUND
            else -> null
        }
    }
}

/**
 * Swiss Alpine Club (SAC) hiking difficulty grades distilled from OpenStreetMap's `sac_scale` tag.
 */
enum class TrailDifficulty(override val rawValue: String, val shade: RouteShade?) : TrailCategory {
    HIKING("hiking", RouteShade.EASIEST),
    MOUNTAIN_HIKING("mountain_hiking", RouteShade.EASY),
    DEMANDING_MOUNTAIN_HIKING("demanding_mountain_hiking", RouteShade.MODERATE),
    ALPINE_HIKING("alpine_hiking", RouteShade.HARD),
    DEMANDING_ALPINE_HIKING("demanding_alpine_hiking", RouteShade.HARDER),
    DIFFICULT_ALPINE_HIKING("difficult_alpine_hiking", RouteShade.HARDEST),
    UNKNOWN("unknown", null),
    UNMAPPED("unmapped", null);

    override val isSurveyed: Boolean
        get() = shade != null

    override val displayOrder: Int
        get() = displayOrdering.indexOf(this)

    companion object {
        /**
         * Easiest SAC grade first, unsurveyed categories last.
         */
        val displayOrdering: List<TrailDifficulty> = listOf(
            HIKING,
            MOUNTAIN_HIKING,
            DEMANDING_MOUNTAIN_HIKING,
            ALPINE_HIKING,
            DEMANDING_ALPINE_HIKING,
            DIFFICULT_ALPINE_HIKING,
            UNKNOWN,
            UNMAPPED
        )

        fun fromSacScale(sacScale: String?): TrailDifficulty = when (sacScale?.trim()?.lowercase()) {
            "hiking" -> HIKING
            "mountain_hiking" -> MOUNTAIN_HIKING
            "demanding_mountain_hiking" -> DEMANDING_MOUNTAIN_HIKING
            "alpine_hiking" -> ALPINE_HIKING
            "demanding_alpine_hiking" -> DEMANDING_ALPINE_HIKING
            "difficult_alpine_hiking" -> DIFFICULT_ALPINE_HIKING
            else -> UNKNOWN
        }

        fun fromEdge(edge: TrailGraphEdge): TrailDifficulty = fromSacScale(edge.sacScale)

        fun fromStoredId(id: String?): TrailDifficulty? {
            if (id.isNullOrBlank()) return null
            return entries.firstOrNull { it.rawValue == id }
        }
    }
}

/**
 * Distance and fractional breakdown of a hike route across categories of one OpenStreetMap tag.
 */
data class TrailBreakdown<C : TrailCategory>(val shares: List<Share<C>>, val totalMeters: Double) {
    data class Share<C : TrailCategory>(val category: C, val meters: Double, val fraction: Double)

    val isEmpty: Boolean
        get() = shares.isEmpty()

    /**
     * Leading share: longest surveyed category, or longest unsurveyed share when nothing was
     * surveyed. `null` when empty.
     */
    val dominant: Share<C>?
        get() = shares.firstOrNull()

    /**
     * Portion of [totalMeters] described by actual OpenStreetMap tagging, in `0.0..1.0`.
     */
    val surveyedFraction: Double
        get() = shares.sumOf { share -> if (share.category.isSurveyed) share.fraction else 0.0 }

    fun metersFor(category: C): Double = shares.firstOrNull { it.category == category }?.meters ?: 0.0

    fun toStoredMeters(): Map<String, Double> = shares.associate { it.category.rawValue to it.meters }

    companion object {
        const val FULL_COVERAGE_THRESHOLD: Double = 0.995

        fun <C : TrailCategory> empty(): TrailBreakdown<C> = TrailBreakdown(emptyList(), 0.0)

        /**
         * Builds a normalized breakdown dropping zero/non-finite entries and ordering surveyed
         * categories first, longest distance first, breaking ties by [TrailCategory.displayOrder].
         */
        fun <C : TrailCategory> fromMeters(metersByCategory: Map<C, Double>): TrailBreakdown<C> {
            val positive = metersByCategory.filterValues { it.isFinite() && it > 0.0 }
            val total = positive.values.sum()
            if (positive.isEmpty() || !total.isFinite() || total <= 0.0) return empty()
            val sortedShares = positive
                .map { (category, meters) -> Share(category = category, meters = meters, fraction = meters / total) }
                .sortedWith(
                    compareByDescending<Share<C>> { it.category.isSurveyed }
                        .thenByDescending { it.meters }
                        .thenBy { it.category.displayOrder }
                )
            return TrailBreakdown(shares = sortedShares, totalMeters = total)
        }

        /**
         * Rebuilds a surface breakdown from persisted raw-key meter totals, ignoring unrecognized
         * future keys so forward-written databases remain readable.
         */
        fun fromStoredSurfaceMeters(stored: Map<String, Double>): TrailSurfaceBreakdown? {
            val recognized = HashMap<TrailSurface, Double>()
            for ((key, meters) in stored) {
                val category = TrailSurface.fromStoredId(key) ?: continue
                if (meters.isFinite() && meters > 0.0) recognized[category] = meters
            }
            return fromMeters(recognized).takeUnless { it.isEmpty }
        }

        /**
         * Rebuilds a difficulty breakdown from persisted raw-key meter totals, ignoring
         * unrecognized future keys.
         */
        fun fromStoredDifficultyMeters(stored: Map<String, Double>): TrailDifficultyBreakdown? {
            val recognized = HashMap<TrailDifficulty, Double>()
            for ((key, meters) in stored) {
                val category = TrailDifficulty.fromStoredId(key) ?: continue
                if (meters.isFinite() && meters > 0.0) recognized[category] = meters
            }
            return fromMeters(recognized).takeUnless { it.isEmpty }
        }
    }
}

typealias TrailSurfaceBreakdown = TrailBreakdown<TrailSurface>
typealias TrailDifficultyBreakdown = TrailBreakdown<TrailDifficulty>

/**
 * One unbroken stretch of a route belonging to a single OpenStreetMap category.
 */
data class TrailCategoryRun<C : TrailCategory>(val category: C, val coordinates: List<GeoCoordinate>)

/**
 * Both surface and SAC difficulty breakdowns measured from one pass over a [TrailGraph].
 */
data class HikeTrailBreakdowns(
    val surface: TrailSurfaceBreakdown? = null,
    val difficulty: TrailDifficultyBreakdown? = null
) {
    val isEmpty: Boolean
        get() = surface == null && difficulty == null

    companion object {
        val Empty = HikeTrailBreakdowns()
    }
}
