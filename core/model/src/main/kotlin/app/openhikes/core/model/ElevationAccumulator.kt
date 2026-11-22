package app.openhikes.core.model

import kotlin.math.max
import kotlin.math.min

/**
 * Running elevation totals counted in deadband-filtered runs so sensor jitter does not inflate climb.
 */
class ElevationAccumulator(val reversalThresholdMeters: Double = REVERSAL_THRESHOLD_METERS) {
    var count: Int = 0
        private set
    var minimumMeters: Double? = null
        private set
    var maximumMeters: Double? = null
        private set

    private var committedGain = 0.0
    private var committedLoss = 0.0
    private var anchor: Double? = null
    private var extreme: Double? = null

    val gainMeters: Double
        get() {
            val runStart = anchor
            val runPeak = extreme
            return if (runStart != null && runPeak != null && runPeak > runStart) {
                committedGain + (runPeak - runStart)
            } else {
                committedGain
            }
        }

    val lossMeters: Double
        get() {
            val runStart = anchor
            val runPeak = extreme
            return if (runStart != null && runPeak != null && runPeak < runStart) {
                committedLoss + (runStart - runPeak)
            } else {
                committedLoss
            }
        }

    val hasChange: Boolean
        get() = count > 1

    fun record(point: RouteCoordinate) {
        if (point.isPauseBoundary) {
            endRun()
        }
        record(point.elevationMeters)
    }

    fun record(elevationMeters: Double?) {
        val elevation = elevationMeters?.takeIf { it.isFinite() } ?: return
        count += 1
        minimumMeters = minimumMeters?.let { min(it, elevation) } ?: elevation
        maximumMeters = maximumMeters?.let { max(it, elevation) } ?: elevation

        val runStart = anchor
        val runPeak = extreme
        if (runStart == null || runPeak == null) {
            anchor = elevation
            extreme = elevation
            return
        }

        if (runPeak >= runStart) {
            if (elevation > runPeak) {
                extreme = elevation
            } else if (runPeak - elevation >= reversalThresholdMeters) {
                committedGain += runPeak - runStart
                anchor = runPeak
                extreme = elevation
            }
        } else {
            if (elevation < runPeak) {
                extreme = elevation
            } else if (elevation - runPeak >= reversalThresholdMeters) {
                committedLoss += runStart - runPeak
                anchor = runPeak
                extreme = elevation
            }
        }
    }

    fun endRun() {
        committedGain = gainMeters
        committedLoss = lossMeters
        anchor = null
        extreme = null
    }

    companion object {
        const val REVERSAL_THRESHOLD_METERS = 3.0
    }
}
