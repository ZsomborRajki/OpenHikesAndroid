package app.openhikes.core.model

import kotlinx.serialization.Serializable

/**
 * Which end of a route or stretch a trim handle controls.
 */
enum class RouteTrimHandle {
    START,
    END;

    val endPause: RouteTrim.EndPauses
        get() = when (this) {
            START -> RouteTrim.EndPauses.START
            END -> RouteTrim.EndPauses.END
        }
}

/**
 * Portable edit history stored beside a hike so edits can be undone across visits and
 * Revert to Original restores the exact pre-edit route, distance, date, and recorded walk.
 */
@Serializable
data class RouteEdit(
    val original: Original? = null,
    val trimStartMeters: Double? = null,
    val trimEndMeters: Double? = null,
    val trimEndPauses: Int? = null,
    val steps: List<RouteStep> = emptyList(),
    val notOnFoot: NotOnFoot? = null,
    val lineRevision: String? = null
) {
    /**
     * Snapshot of the recording's own [HikeWalk] before the first edit.
     */
    @Serializable
    data class RecordedWalk(
        val startedAtEpochMillis: Long,
        val endedAtEpochMillis: Long,
        val durationSeconds: Double,
        val coveredIntervals: List<Double>,
        val furthestDistanceMeters: Double,
        val routeDistanceMeters: Double,
        val routeRevision: String? = null
    ) {
        companion object {
            fun from(walk: HikeWalk): RecordedWalk = RecordedWalk(
                startedAtEpochMillis = walk.startedAtEpochMillis,
                endedAtEpochMillis = walk.endedAtEpochMillis,
                durationSeconds = walk.durationSeconds,
                coveredIntervals = walk.coveredIntervals,
                furthestDistanceMeters = walk.furthestDistanceMeters,
                routeDistanceMeters = walk.routeDistanceMeters,
                routeRevision = walk.routeRevision
            )
        }
    }

    /**
     * Non-walked ends recorded for display without decoding the full route.
     */
    @Serializable
    data class NotOnFoot(
        val leadingMeters: Double? = null,
        val trailingMeters: Double? = null,
        val isChecked: Boolean = false
    ) {
        val totalMeters: Double
            get() = (leadingMeters ?: 0.0) + (trailingMeters ?: 0.0)

        companion object {
            fun from(ends: RouteCarriedEnds, isChecked: Boolean = false): NotOnFoot? {
                if (ends.isEmpty) return null
                return NotOnFoot(
                    leadingMeters = ends.leading?.carriedMeters,
                    trailingMeters = ends.trailing?.carriedMeters,
                    isChecked = isChecked
                )
            }
        }
    }

    /**
     * Pre-edit figures restored alongside the hike's stored original route.
     */
    @Serializable
    data class Original(
        val distanceMeters: Double,
        val dateEpochMillis: Long,
        val durationSeconds: Double? = null,
        val recordedWalk: RecordedWalk? = null,
        val notOnFoot: NotOnFoot? = null
    )

    val trimRange: ClosedFloatingPointRange<Double>?
        get() {
            val start = trimStartMeters ?: return null
            val end = trimEndMeters ?: return null
            return if (start.isFinite() && end.isFinite() && end >= start) start..end else null
        }

    val endPausesTakenOff: RouteTrim.EndPauses
        get() = RouteTrim.EndPauses(trimEndPauses ?: 0)

    val isEmpty: Boolean
        get() = original == null && trimRange == null && notOnFoot == null && steps.isEmpty()

    /**
     * Whether this edit record still describes [line] rather than an older version replaced externally.
     */
    fun describes(line: List<RouteCoordinate>): Boolean =
        lineRevision == null || lineRevision == TrailWalkRecord.routeRevision(line)

    /**
     * Discards stale line-specific edit state when another writer replaced the route externally.
     */
    val rebased: RouteEdit
        get() = RouteEdit()
}

/**
 * Inputs loaded off the main thread for one Edit Route visit.
 */
data class RouteEditSources(
    val current: List<RouteCoordinate>,
    val saved: List<RouteCoordinate> = current,
    val edit: RouteEdit? = null,
    val isMissingOriginal: Boolean = false,
    val isUnreadable: Boolean = false
)

/**
 * Why a route edit operation could not complete.
 */
enum class RouteEditRefusal {
    LINE_CHANGED,
    NO_MAPPED_WAY,
    NO_ORIGINAL,
    NOT_HERE_YET,
    NOT_SAVED,
    TOO_MUCH_TO_KEEP
}

/**
 * Result of persisting an edit or revert to storage.
 */
sealed interface RouteEditOutcome {
    data class Refused(val refusal: RouteEditRefusal) : RouteEditOutcome

    data class Saved(val route: List<RouteCoordinate>) : RouteEditOutcome
}

/**
 * Prepared edited route ready for atomic persistence into storage.
 */
data class EditedLine(
    val route: List<RouteCoordinate>,
    val lengthMeters: Double,
    val removedMeters: Double,
    val removedSeconds: Double,
    val revision: String,
    val start: ClockEnd,
    val end: ClockEnd,
    val cuts: Cuts,
    val steps: List<RouteStep>,
    val notOnFoot: RouteEdit.NotOnFoot?
) {
    data class Cuts(
        val range: ClosedFloatingPointRange<Double>? = null,
        val takingOff: RouteTrim.EndPauses = RouteTrim.EndPauses.NONE
    ) {
        fun ends(alongWholeOfMeters: Double): Set<RouteTrimHandle> {
            val activeRange = range ?: return emptySet()
            return buildSet {
                if (activeRange.start > 0.0 || RouteTrim.EndPauses.START in takingOff) {
                    add(RouteTrimHandle.START)
                }
                if (activeRange.endInclusive < alongWholeOfMeters || RouteTrim.EndPauses.END in takingOff) {
                    add(RouteTrimHandle.END)
                }
            }
        }
    }

    data class Steps(
        val made: List<RouteStep> = emptyList(),
        val moving: Set<RouteTrimHandle> = emptySet(),
        val recordedSeconds: Double? = null,
        val clockedRoute: List<RouteCoordinate> = emptyList()
    )

    sealed interface ClockEnd {
        data object Unchanged : ClockEnd

        data class Cut(val epochMillis: Long) : ClockEnd

        data object Uncut : ClockEnd
    }

    companion object {
        fun build(
            whole: List<RouteCoordinate>,
            distances: List<Double>,
            cuts: Cuts,
            replacing: List<RouteCoordinate>,
            cutBefore: Set<RouteTrimHandle> = emptySet(),
            steps: Steps = Steps(),
            recordedBefore: Double? = null
        ): EditedLine? {
            val kept = cuts.range?.let { range ->
                RouteTrim.trimmed(
                    route = whole,
                    startMeters = range.start,
                    endMeters = range.endInclusive,
                    distances = distances,
                    takingOff = cuts.takingOff
                )
            } ?: whole
            val length = if (kept.size > 1) RouteGeometry.routeLengthMeters(kept) else 0.0
            if (length <= 0.0) return null

            val beforeSeconds = recordedBefore ?: RouteTrim.recordedSeconds(replacing)
            val nowSeconds = recordedSeconds(whole = whole, cutTo = kept, steps = steps)
            val removedSeconds = if (beforeSeconds > 0.0 && nowSeconds > 0.0) beforeSeconds - nowSeconds else 0.0
            val wholeLength = distances.lastOrNull() ?: 0.0
            val cutNow = cuts.ends(wholeLength) + steps.moving

            return EditedLine(
                route = kept,
                lengthMeters = length,
                removedMeters = RouteGeometry.routeLengthMeters(replacing) - length,
                removedSeconds = removedSeconds,
                revision = TrailWalkRecord.routeRevision(kept),
                start = resolveClock(
                    isCut = RouteTrimHandle.START in cutNow,
                    wasCut = RouteTrimHandle.START in cutBefore,
                    timeMillis = kept.firstOrNull()?.timestampEpochMillis,
                    beforeMillis = replacing.firstOrNull()?.timestampEpochMillis
                ),
                end = resolveClock(
                    isCut = RouteTrimHandle.END in cutNow,
                    wasCut = RouteTrimHandle.END in cutBefore,
                    timeMillis = kept.lastOrNull()?.timestampEpochMillis,
                    beforeMillis = replacing.lastOrNull()?.timestampEpochMillis
                ),
                cuts = cuts,
                steps = steps.made,
                notOnFoot = RouteEdit.NotOnFoot.from(
                    ends = RouteCarriedEnds.found(kept),
                    isChecked = true
                )
            )
        }

        fun recordedSeconds(
            whole: List<RouteCoordinate>,
            cutTo: List<RouteCoordinate>,
            steps: Steps = Steps()
        ): Double {
            val own = RouteTrim.recordedSeconds(whole)
            return (steps.recordedSeconds ?: own) + RouteTrim.recordedSeconds(cutTo) - own
        }

        private fun resolveClock(isCut: Boolean, wasCut: Boolean, timeMillis: Long?, beforeMillis: Long?): ClockEnd =
            when {
                !isCut -> if (wasCut) ClockEnd.Uncut else ClockEnd.Unchanged
                timeMillis == null -> ClockEnd.Unchanged
                wasCut && timeMillis == beforeMillis -> ClockEnd.Unchanged
                else -> ClockEnd.Cut(timeMillis)
            }
    }
}
