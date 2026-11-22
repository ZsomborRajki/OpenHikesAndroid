package app.openhikes.core.model

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/**
 * Visual line pattern used to draw a hike route on the map and in style swatches.
 */
@Serializable
enum class RouteLinePattern(val id: String) {
    ARROWHEADS("arrowheads"),
    DASHED("dashed"),
    DIRECTIONAL("directional"),
    DOTTED("dotted"),
    SOLID("solid");

    val drawsLine: Boolean
        get() = this != ARROWHEADS

    val drawsChevrons: Boolean
        get() = this == DIRECTIONAL || this == ARROWHEADS

    val chevronsUseRouteTint: Boolean
        get() = this == ARROWHEADS

    val usesRoundCap: Boolean
        get() = this != DASHED

    /**
     * Dash and gap lengths in screen dp for a line of [widthDp]. Empty for unbroken strokes.
     */
    fun dashLengths(widthDp: Double): List<Double> {
        val lineWidth = max(widthDp, MINIMUM_LINE_WIDTH)
        return when (this) {
            SOLID, DIRECTIONAL, ARROWHEADS -> emptyList()
            DASHED -> listOf(
                max(lineWidth * DASH_LENGTH_MULTIPLIER, MINIMUM_DASH_LENGTH),
                max(lineWidth * DASH_GAP_MULTIPLIER, MINIMUM_DASH_GAP)
            )
            DOTTED -> listOf(
                DOT_LENGTH,
                max(lineWidth * DOT_GAP_MULTIPLIER, MINIMUM_DASH_GAP)
            )
        }
    }

    companion object {
        private const val MINIMUM_LINE_WIDTH = 1.0
        private const val DASH_LENGTH_MULTIPLIER = 3.0
        private const val DASH_GAP_MULTIPLIER = 2.5
        private const val DOT_GAP_MULTIPLIER = 2.0
        private const val MINIMUM_DASH_LENGTH = 6.0
        private const val MINIMUM_DASH_GAP = 4.0
        private const val DOT_LENGTH = 0.1

        val Default: RouteLinePattern = DIRECTIONAL

        val displayOrder: List<RouteLinePattern> = listOf(
            SOLID,
            DIRECTIONAL,
            DASHED,
            DOTTED,
            ARROWHEADS
        )

        fun fromStoredId(id: String?): RouteLinePattern = entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * Outline geometry and sentinel hex values for route polylines.
 */
object RouteBorder {
    const val NONE_HEX = ""
    const val DEFAULT_HEX = "#000000FF"
    const val TRANSPARENT_HEX = "#00000000"
    const val WHITE_HEX = "#FFFFFFFF"
    const val CHARCOAL_HEX = "#2B302CFF"

    private const val WIDTH_DIVISOR = 3.0
    private const val MINIMUM_WIDTH = 1.0
    private const val ALPHA_ZERO_SUFFIX = "00"

    val borderSwatches: List<String> = listOf(
        NONE_HEX,
        TRANSPARENT_HEX,
        DEFAULT_HEX,
        WHITE_HEX,
        CHARCOAL_HEX
    )

    fun width(lineWidthDp: Double): Double = max(lineWidthDp / WIDTH_DIVISOR, MINIMUM_WIDTH)

    fun isVisible(borderHex: String): Boolean {
        val normalized = borderHex.trim()
        if (normalized.isEmpty()) return true
        return !normalized.endsWith(ALPHA_ZERO_SUFFIX, ignoreCase = true)
    }

    fun resolvedBorderHex(borderHex: String): String = borderHex.ifBlank { DEFAULT_HEX }
}

/**
 * Curated outdoor-legible route palette and deterministic FNV-1a hue assignment.
 */
object RouteTint {
    const val DEFAULT_HEX = "#1B7F3B"

    private const val SATURATION = 0.65
    private const val BRIGHTNESS = 0.85
    private const val HUE_STEPS = 3600L
    private const val FNV_OFFSET_BASIS = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L
    private const val UNSIGNED_BYTE_MASK = 0xffL
    private const val HUE_SECTORS = 6.0
    private const val SECTOR_GREEN = 1
    private const val SECTOR_TEAL = 2
    private const val SECTOR_CYAN = 3
    private const val SECTOR_BLUE = 4
    private const val MAX_COLOR_CHANNEL = 255.0
    private const val SECTOR_MOD = 2.0

    val palette: List<String> = listOf(
        DEFAULT_HEX,
        "#D85A17",
        "#1E64B4",
        "#B92B47",
        "#C4820E",
        "#7845A8",
        "#0E7C7B",
        "#3A5199"
    )

    fun forIndex(index: Int): String = palette[Math.floorMod(index, palette.size)]

    fun stableForKey(key: String): String {
        val hash = fnv1a64(key.encodeToByteArray())
        val step = Math.floorMod(hash, HUE_STEPS)
        val hue = step.toDouble() / HUE_STEPS.toDouble()
        return hsvToHex(hue = hue, saturation = SATURATION, value = BRIGHTNESS)
    }

    private fun fnv1a64(bytes: ByteArray): Long {
        var hash = FNV_OFFSET_BASIS
        for (byte in bytes) {
            hash = hash xor (byte.toLong() and UNSIGNED_BYTE_MASK)
            hash *= FNV_PRIME
        }
        return hash
    }

    private fun hsvToHex(hue: Double, saturation: Double, value: Double): String {
        val scaledHue = (hue * HUE_SECTORS)
        val sector = floor(scaledHue).toInt() % HUE_SECTORS.toInt()
        val chroma = value * saturation
        val second = chroma * (1.0 - abs((scaledHue % SECTOR_MOD) - 1.0))
        val match = value - chroma
        val (rPrime, gPrime, bPrime) = when (sector) {
            0 -> Triple(chroma, second, 0.0)
            SECTOR_GREEN -> Triple(second, chroma, 0.0)
            SECTOR_TEAL -> Triple(0.0, chroma, second)
            SECTOR_CYAN -> Triple(0.0, second, chroma)
            SECTOR_BLUE -> Triple(second, 0.0, chroma)
            else -> Triple(chroma, 0.0, second)
        }
        val red = ((rPrime + match) * MAX_COLOR_CHANNEL).roundToInt().coerceIn(0, MAX_COLOR_CHANNEL.toInt())
        val green = ((gPrime + match) * MAX_COLOR_CHANNEL).roundToInt().coerceIn(0, MAX_COLOR_CHANNEL.toInt())
        val blue = ((bPrime + match) * MAX_COLOR_CHANNEL).roundToInt().coerceIn(0, MAX_COLOR_CHANNEL.toInt())
        return "#%02X%02X%02X".format(Locale.ROOT, red, green, blue)
    }
}

/**
 * Validated user update for how a hike's polyline renders on the map.
 */
data class RouteStyleUpdate(
    val tintHex: String,
    val routeBorderHex: String,
    val routeWidthDp: Double,
    val linePattern: RouteLinePattern
) {
    val clampedWidthDp: Double
        get() = routeWidthDp.coerceIn(MIN_ROUTE_WIDTH_DP, MAX_ROUTE_WIDTH_DP)

    companion object {
        const val MIN_ROUTE_WIDTH_DP = 1.0
        const val MAX_ROUTE_WIDTH_DP = 12.0
    }
}
