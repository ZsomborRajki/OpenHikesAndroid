package app.openhikes.core.common.gpx

import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.Mercator
import app.openhikes.core.model.PlausibleElevation
import app.openhikes.core.model.RouteGeometry

internal data class RawGpxPoint(
    val latitude: Double?,
    val longitude: Double?,
    val elevationMeters: Double?,
    val timestampEpochMillis: Long?,
    val name: String? = null,
    val symbol: String? = null,
    val note: String? = null
)

internal data class ValidatedGpxPoint(
    val coordinate: GeoCoordinate,
    val elevationMeters: Double?,
    val timestampEpochMillis: Long?
)

/**
 * Drops unprojectable coordinates, `(0, 0)` placeholders, isolated GPS teleports,
 * and single-sample elevation spikes while leaving real terrain intact.
 */
internal object GpxPlaceholderFilter {
    private const val TELEPORT_DISTANCE_METERS = 100_000.0
    private const val TELEPORT_NEIGHBOR_SPAN_METERS = 5_000.0

    fun toValidatedPoint(raw: RawGpxPoint): ValidatedGpxPoint? {
        val lat = raw.latitude
        val lon = raw.longitude
        return if (lat != null && lon != null && Mercator.isRepresentable(lat, lon)) {
            ValidatedGpxPoint(
                coordinate = GeoCoordinate(latitude = lat, longitude = lon),
                elevationMeters = PlausibleElevation.validated(raw.elevationMeters),
                timestampEpochMillis = raw.timestampEpochMillis
            )
        } else {
            null
        }
    }

    fun dropPlaceholders(points: List<ValidatedGpxPoint>, isRecordedTrack: Boolean): List<ValidatedGpxPoint> {
        val located = dropZeroOriginFixes(points)
        val filteredFixes = dropIsolatedTeleports(located, isRecordedTrack)
        return dropSpikeHeights(filteredFixes)
    }

    private fun dropZeroOriginFixes(points: List<ValidatedGpxPoint>): List<ValidatedGpxPoint> = points.filter { point ->
        point.coordinate.latitude != 0.0 || point.coordinate.longitude != 0.0
    }

    private fun dropIsolatedTeleports(
        points: List<ValidatedGpxPoint>,
        isRecordedTrack: Boolean
    ): List<ValidatedGpxPoint> {
        if (!isRecordedTrack || points.size <= 2) return points
        val kept = ArrayList<ValidatedGpxPoint>(points.size)
        kept.add(points.first())
        for (index in 1 until points.lastIndex) {
            val before = points[index - 1].coordinate
            val here = points[index].coordinate
            val after = points[index + 1].coordinate
            val isolated = RouteGeometry.distanceMeters(before, here) > TELEPORT_DISTANCE_METERS &&
                RouteGeometry.distanceMeters(here, after) > TELEPORT_DISTANCE_METERS &&
                RouteGeometry.distanceMeters(before, after) < TELEPORT_NEIGHBOR_SPAN_METERS
            if (!isolated) {
                kept.add(points[index])
            }
        }
        kept.add(points.last())
        return kept
    }

    private fun dropSpikeHeights(points: List<ValidatedGpxPoint>): List<ValidatedGpxPoint> {
        val spikes = if (points.size > 2) {
            PlausibleElevation.spikes(points.map { it.coordinate to it.elevationMeters })
        } else {
            emptySet()
        }
        return if (spikes.isEmpty()) {
            points
        } else {
            points.mapIndexed { index, point ->
                if (spikes.contains(index)) point.copy(elevationMeters = null) else point
            }
        }
    }
}
