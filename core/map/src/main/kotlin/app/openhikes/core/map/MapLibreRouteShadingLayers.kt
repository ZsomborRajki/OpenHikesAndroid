package app.openhikes.core.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.RouteGeometry
import app.openhikes.core.model.RouteShade
import app.openhikes.core.model.RouteShadeBlend
import app.openhikes.core.model.RouteShadeStretch
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.convertToString
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.value.ImageValue
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Position

internal const val SHADE_COLOR_PROPERTY = "color"
internal const val SHADE_STEP_PROPERTY = "shade"
internal const val BASE_SHADE_STEP = "base"

private const val MIN_POLYLINE_POINTS = 2
private const val MATCH_TOLERANCE_METERS = 2.0
private const val ENDPOINT_TOLERANCE_METERS = 3.0
private const val SLIVER_EPSILON_METERS = 0.5
private const val HALF_FACTOR = 0.5
private const val COLOR_CHANNEL_MAX = 255

/**
 * One route-ordered, non-overlapping segment of a shaded route carrying either a [RouteShade]
 * (covered stretch) or `null` (uncovered stretch rendered in the route's base tint or a blended
 * transition span).
 */
internal data class ShadedRouteSpan(val shade: RouteShade?, val colorHex: String, val coordinates: List<GeoCoordinate>)

/**
 * Builds a single MapLibre `iconImage` expression that selects the shade-tinted arrowhead painter
 * per feature (`"0".."5"`) and falls back to the base route-tint painter for uncovered stretches,
 * so shaded arrowhead routes use one symbol placement pass with zero overlap.
 */
@Composable
internal fun rememberShadedArrowheadImageExpression(
    route: MapRouteOverlay,
    plan: WalkedRouteRenderPlan,
    baseChevron: RouteChevronRenderSpec
): Expression<ImageValue> {
    val iconSize = DpSize(baseChevron.iconWidthDp.dpCompat(), baseChevron.iconHeightDp.dpCompat())
    val basePainter = remember(baseChevron) { RouteChevronPainter(baseChevron) }
    val shadePainters = remember(route.linePattern, plan.lineColor, plan.borderColor, plan.lineWidthDp) {
        RouteShade.scale.mapNotNull { shade ->
            val tinted = parseHexColorOrDefault(shade.hexColor, plan.lineColor).copy(alpha = plan.lineColor.alpha)
            val spec = buildChevronRenderSpec(route.linePattern, tinted, plan.borderColor, plan.lineWidthDp)
            spec?.let { shade to RouteChevronPainter(it) }
        }
    }
    val cases = shadePainters.map { (shade, painter) ->
        case(
            label = shade.step.toString(),
            output = image(value = painter, size = iconSize)
        )
    }
    return switch(
        input = feature[SHADE_STEP_PROPERTY].convertToString(),
        cases = cases,
        fallback = image(value = basePainter, size = iconSize)
    )
}

/**
 * Formats an Android Compose [Color] as a 6-character `#RRGGBB` hex string compatible with
 * MapLibre's `to-color` expression (alpha is applied uniformly via layer opacity).
 */
internal fun colorToRgbHex(color: Color): String {
    val red = (color.red * COLOR_CHANNEL_MAX).roundToInt().coerceIn(0, COLOR_CHANNEL_MAX)
    val green = (color.green * COLOR_CHANNEL_MAX).roundToInt().coerceIn(0, COLOR_CHANNEL_MAX)
    val blue = (color.blue * COLOR_CHANNEL_MAX).roundToInt().coerceIn(0, COLOR_CHANNEL_MAX)
    return String.format(Locale.US, "#%02X%02X%02X", red, green, blue)
}

/**
 * Partitions [walkedSegments] into a single, non-overlapping, route-ordered GeoJSON
 * [FeatureCollection] where covered stretches carry their [RouteShade] color (blended when
 * [blendTransitions] is `true`, unblended when `false` for dashed/dotted/arrowhead patterns) and
 * uncovered stretches carry [baseTintHex].
 */
internal fun buildPartitionedShadeFeatureCollection(
    walkedSegments: List<List<GeoCoordinate>>,
    shadedStretches: List<RouteShadeStretch>,
    baseTintHex: String,
    blendTransitions: Boolean
): GeoJsonData = GeoJsonData.Features(
    buildPartitionedShadeFeatures(
        walkedSegments = walkedSegments,
        shadedStretches = shadedStretches,
        baseTintHex = baseTintHex,
        blendTransitions = blendTransitions
    )
)

internal fun buildPartitionedShadeFeatures(
    walkedSegments: List<List<GeoCoordinate>>,
    shadedStretches: List<RouteShadeStretch>,
    baseTintHex: String,
    blendTransitions: Boolean
): FeatureCollection<LineString, JsonObject> {
    val spans = partitionWalkedSegmentsWithShades(
        walkedSegments = walkedSegments,
        shadedStretches = shadedStretches,
        baseTintHex = baseTintHex,
        blendTransitions = blendTransitions
    )
    return spansToFeatureCollection(spans)
}

internal fun partitionWalkedSegmentsWithShades(
    walkedSegments: List<List<GeoCoordinate>>,
    shadedStretches: List<RouteShadeStretch>,
    baseTintHex: String,
    blendTransitions: Boolean
): List<ShadedRouteSpan> {
    val rawPieces = ShadedRoutePartitioner.partition(walkedSegments, shadedStretches)
    return if (blendTransitions) {
        expandWithBlends(rawPieces, baseTintHex)
    } else {
        expandUnblended(rawPieces, baseTintHex)
    }
}

private fun spansToFeatureCollection(spans: List<ShadedRouteSpan>): FeatureCollection<LineString, JsonObject> {
    val features = spans.mapNotNull { span ->
        if (span.coordinates.size < MIN_POLYLINE_POINTS) {
            null
        } else {
            val positions = span.coordinates.map { Position(longitude = it.longitude, latitude = it.latitude) }
            val stepValue = span.shade?.step?.toString() ?: BASE_SHADE_STEP
            val props = JsonObject(
                mapOf(
                    SHADE_COLOR_PROPERTY to JsonPrimitive(span.colorHex),
                    SHADE_STEP_PROPERTY to JsonPrimitive(stepValue)
                )
            )
            Feature<LineString, JsonObject>(LineString(positions), props)
        }
    }
    return FeatureCollection(features)
}

private fun expandWithBlends(pieces: List<PartitionPiece>, baseTintHex: String): List<ShadedRouteSpan> {
    val result = ArrayList<ShadedRouteSpan>(pieces.size * 2)
    val pendingCovered = ArrayList<RouteShadeStretch>()

    fun flushCovered() {
        if (pendingCovered.isEmpty()) return
        for (segment in RouteShadeBlend.blend(pendingCovered)) {
            result.add(ShadedRouteSpan(shade = null, colorHex = segment.colorHex, coordinates = segment.coordinates))
        }
        pendingCovered.clear()
    }

    for (piece in pieces) {
        when (piece) {
            is PartitionPiece.Covered -> pendingCovered.add(piece.stretch)
            is PartitionPiece.Uncovered -> {
                flushCovered()
                result.add(ShadedRouteSpan(shade = null, colorHex = baseTintHex, coordinates = piece.coordinates))
            }
        }
    }
    flushCovered()
    return result
}

private fun expandUnblended(pieces: List<PartitionPiece>, baseTintHex: String): List<ShadedRouteSpan> {
    val result = ArrayList<ShadedRouteSpan>(pieces.size)
    for (piece in pieces) {
        when (piece) {
            is PartitionPiece.Uncovered -> result.add(
                ShadedRouteSpan(shade = null, colorHex = baseTintHex, coordinates = piece.coordinates)
            )
            is PartitionPiece.Covered -> appendOrMergeCovered(piece.stretch, result)
        }
    }
    return result
}

private fun appendOrMergeCovered(stretch: RouteShadeStretch, into: MutableList<ShadedRouteSpan>) {
    val previous = into.lastOrNull()
    val canMerge = stretch.joinsPrevious &&
        previous != null &&
        previous.shade == stretch.shade &&
        previous.coordinates.lastOrNull() == stretch.coordinates.firstOrNull()
    if (canMerge) {
        into[into.lastIndex] = previous.copy(coordinates = previous.coordinates + stretch.coordinates.drop(1))
    } else {
        into.add(
            ShadedRouteSpan(
                shade = stretch.shade,
                colorHex = stretch.shade.hexColor,
                coordinates = stretch.coordinates
            )
        )
    }
}

private sealed interface PartitionPiece {
    data class Uncovered(val coordinates: List<GeoCoordinate>) : PartitionPiece

    data class Covered(val stretch: RouteShadeStretch) : PartitionPiece
}

private object ShadedRoutePartitioner {
    private data class TrackMatch(val trackIndex: Int, val startMeters: Double, val endMeters: Double)

    fun partition(
        walkedSegments: List<List<GeoCoordinate>>,
        shadedStretches: List<RouteShadeStretch>
    ): List<PartitionPiece> {
        val tracks = walkedSegments.mapNotNull { coords -> WalkedTrack.from(coords) }
        val validStretches = shadedStretches.filter { it.coordinates.size >= MIN_POLYLINE_POINTS }
        if (tracks.isEmpty()) {
            return validStretches.map { PartitionPiece.Covered(it) }
        }
        val pieces = ArrayList<PartitionPiece>(validStretches.size + tracks.size)
        var trackIndex = 0
        var cursorMeters = 0.0
        var previousCoveredTrack = -1

        for (stretch in validStretches) {
            val match = locateStretch(tracks, trackIndex, cursorMeters, stretch)
            if (match == null) {
                pieces.add(PartitionPiece.Covered(stretch.copy(joinsPrevious = false)))
                previousCoveredTrack = -1
                continue
            }
            while (trackIndex < match.trackIndex) {
                emitUncoveredTail(tracks[trackIndex], cursorMeters, tracks[trackIndex].totalMeters, pieces)
                trackIndex += 1
                cursorMeters = 0.0
                previousCoveredTrack = -1
            }
            val emittedGap = emitUncoveredGap(tracks[trackIndex], cursorMeters, match.startMeters, pieces)
            val joins = stretch.joinsPrevious && !emittedGap && previousCoveredTrack == trackIndex
            pieces.add(PartitionPiece.Covered(stretch.copy(joinsPrevious = joins)))
            cursorMeters = match.endMeters
            previousCoveredTrack = trackIndex
        }
        while (trackIndex < tracks.size) {
            emitUncoveredTail(tracks[trackIndex], cursorMeters, tracks[trackIndex].totalMeters, pieces)
            trackIndex += 1
            cursorMeters = 0.0
        }
        return pieces
    }

    private fun emitUncoveredGap(
        track: WalkedTrack,
        fromMeters: Double,
        toMeters: Double,
        into: MutableList<PartitionPiece>
    ): Boolean {
        if (toMeters - fromMeters <= SLIVER_EPSILON_METERS) return false
        val slice = track.slice(fromMeters, toMeters)
        return if (slice.size >= MIN_POLYLINE_POINTS) {
            into.add(PartitionPiece.Uncovered(slice))
            true
        } else {
            false
        }
    }

    private fun emitUncoveredTail(
        track: WalkedTrack,
        fromMeters: Double,
        totalMeters: Double,
        into: MutableList<PartitionPiece>
    ) {
        emitUncoveredGap(track, fromMeters, totalMeters, into)
    }

    private fun locateStretch(
        tracks: List<WalkedTrack>,
        startTrackIndex: Int,
        cursorMeters: Double,
        stretch: RouteShadeStretch
    ): TrackMatch? {
        val stretchLen = RouteGeometry.lengthMeters(stretch.coordinates)
        if (!stretchLen.isFinite() || stretchLen <= 0.0) return null
        val midDistance = stretchLen * HALF_FACTOR
        val midPoint = coordinateAtDistance(stretch.coordinates, midDistance)
        return (startTrackIndex until tracks.size).firstNotNullOfOrNull { index ->
            val minStart = if (index == startTrackIndex) cursorMeters else 0.0
            findOnTrack(tracks[index], minStart, stretch, stretchLen, midDistance, midPoint)?.let { matched ->
                TrackMatch(trackIndex = index, startMeters = matched.first, endMeters = matched.second)
            }
        }
    }

    private data class StretchProbe(
        val startCoord: GeoCoordinate,
        val endCoord: GeoCoordinate,
        val stretchLen: Double,
        val midDistance: Double,
        val midPoint: GeoCoordinate
    )

    @Suppress("LongParameterList")
    private fun findOnTrack(
        track: WalkedTrack,
        minStart: Double,
        stretch: RouteShadeStretch,
        stretchLen: Double,
        midDistance: Double,
        midPoint: GeoCoordinate
    ): Pair<Double, Double>? {
        val probe = StretchProbe(
            startCoord = stretch.coordinates.first(),
            endCoord = stretch.coordinates.last(),
            stretchLen = stretchLen,
            midDistance = midDistance,
            midPoint = midPoint
        )
        return (0 until track.coordinates.lastIndex).firstNotNullOfOrNull { leg ->
            matchOnLeg(track, leg, minStart, probe)
        }
    }

    private fun matchOnLeg(track: WalkedTrack, leg: Int, minStart: Double, probe: StretchProbe): Pair<Double, Double>? {
        if (track.cumulativeMeters[leg + 1] < minStart - MATCH_TOLERANCE_METERS) return null
        val proj = RouteGeometry.project(probe.startCoord, track.coordinates[leg], track.coordinates[leg + 1])
        val legLength = track.cumulativeMeters[leg + 1] - track.cumulativeMeters[leg]
        val rawStart = track.cumulativeMeters[leg] + proj.fraction * legLength
        val validStart = proj.offRouteMeters <= MATCH_TOLERANCE_METERS && rawStart >= minStart - MATCH_TOLERANCE_METERS
        val startMeters = rawStart.coerceAtLeast(minStart)
        val rawEnd = startMeters + probe.stretchLen
        val validRange = validStart && rawEnd <= track.totalMeters + ENDPOINT_TOLERANCE_METERS
        return if (!validRange) {
            null
        } else {
            val clampedEnd = snapEndIfTerminal(track, rawEnd.coerceAtMost(track.totalMeters), probe.endCoord)
            val midError = RouteGeometry.distanceMeters(track.pointAt(startMeters + probe.midDistance), probe.midPoint)
            val endError = RouteGeometry.distanceMeters(track.pointAt(clampedEnd), probe.endCoord)
            if (midError <= MATCH_TOLERANCE_METERS && endError <= ENDPOINT_TOLERANCE_METERS) {
                startMeters to clampedEnd
            } else {
                null
            }
        }
    }

    private fun snapEndIfTerminal(track: WalkedTrack, endMeters: Double, endCoord: GeoCoordinate): Double {
        val nearEnd = track.totalMeters - endMeters <= SLIVER_EPSILON_METERS &&
            RouteGeometry.distanceMeters(track.coordinates.last(), endCoord) <= MATCH_TOLERANCE_METERS
        return if (nearEnd) track.totalMeters else endMeters
    }

    private fun coordinateAtDistance(coords: List<GeoCoordinate>, targetMeters: Double): GeoCoordinate {
        var walked = 0.0
        for (i in 0 until coords.lastIndex) {
            val segLen = RouteGeometry.distanceMeters(coords[i], coords[i + 1])
            if (walked + segLen >= targetMeters && segLen > 0.0) {
                val fraction = ((targetMeters - walked) / segLen).coerceIn(0.0, 1.0)
                return RouteGeometry.interpolate(coords[i], coords[i + 1], fraction)
            }
            walked += segLen
        }
        return coords.last()
    }
}

private class WalkedTrack(val coordinates: List<GeoCoordinate>, val cumulativeMeters: DoubleArray) {
    val totalMeters: Double
        get() = cumulativeMeters[cumulativeMeters.lastIndex]

    fun pointAt(distanceMeters: Double): GeoCoordinate {
        val target = distanceMeters.coerceIn(0.0, totalMeters)
        val next = upperBound(cumulativeMeters, target)
        return when {
            next <= 0 -> coordinates.first()
            next >= coordinates.size -> coordinates.last()
            else -> {
                val prev = next - 1
                val span = cumulativeMeters[next] - cumulativeMeters[prev]
                if (span <= 0.0) {
                    coordinates[prev]
                } else {
                    RouteGeometry.interpolate(
                        start = coordinates[prev],
                        end = coordinates[next],
                        fraction = ((target - cumulativeMeters[prev]) / span).coerceIn(0.0, 1.0)
                    )
                }
            }
        }
    }

    fun slice(startMeters: Double, endMeters: Double): List<GeoCoordinate> {
        val from = startMeters.coerceIn(0.0, totalMeters)
        val to = endMeters.coerceIn(from, totalMeters)
        if (to <= from) return emptyList()
        val sliced = ArrayList<GeoCoordinate>()
        for (i in 0 until coordinates.lastIndex) {
            val segStart = cumulativeMeters[i]
            val segEnd = cumulativeMeters[i + 1]
            val segLen = segEnd - segStart
            if (segLen > 0.0 && segEnd >= from && segStart <= to) {
                val localStart = ((from - segStart) / segLen).coerceIn(0.0, 1.0)
                val localEnd = ((to - segStart) / segLen).coerceIn(0.0, 1.0)
                val p0 = if (localStart == 0.0) {
                    coordinates[i]
                } else {
                    RouteGeometry.interpolate(coordinates[i], coordinates[i + 1], localStart)
                }
                val p1 = if (localEnd == 1.0) {
                    coordinates[i + 1]
                } else {
                    RouteGeometry.interpolate(coordinates[i], coordinates[i + 1], localEnd)
                }
                if (sliced.isEmpty() || sliced.last() != p0) sliced.add(p0)
                if (sliced.last() != p1) sliced.add(p1)
            }
            if (segEnd >= to) break
        }
        return sliced
    }

    companion object {
        fun from(coordinates: List<GeoCoordinate>): WalkedTrack? {
            if (coordinates.size < MIN_POLYLINE_POINTS) return null
            val cum = DoubleArray(coordinates.size)
            for (i in 1 until coordinates.size) {
                cum[i] = cum[i - 1] + RouteGeometry.distanceMeters(coordinates[i - 1], coordinates[i])
            }
            return if (cum.last() > 0.0) WalkedTrack(coordinates, cum) else null
        }

        private fun upperBound(values: DoubleArray, target: Double): Int {
            var low = 0
            var high = values.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (values[mid] <= target) low = mid + 1 else high = mid
            }
            return low
        }
    }
}

private fun Float.dpCompat() = androidx.compose.ui.unit.Dp(this)
