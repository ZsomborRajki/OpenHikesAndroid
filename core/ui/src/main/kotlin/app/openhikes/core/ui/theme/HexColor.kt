package app.openhikes.core.ui.theme

import androidx.compose.ui.graphics.Color

private const val COLOR_RADIX = 16
private const val HEX_RGB_LENGTH = 6
private const val HEX_RGBA_LENGTH = 8
private const val SHIFT_24 = 24
private const val SHIFT_16 = 16
private const val SHIFT_8 = 8
private const val FULL_ALPHA = 255
private const val BYTE_MASK = 0xFFL

/**
 * Parses `#RRGGBB` or `#RRGGBBAA` into a Compose [Color], returning [defaultColor] if malformed.
 */
fun parseHexColorOrDefault(hex: String, defaultColor: Color): Color {
    val clean = hex.trim().removePrefix("#")
    val parsed = clean.toLongOrNull(COLOR_RADIX) ?: return defaultColor
    return when (clean.length) {
        HEX_RGB_LENGTH -> {
            val r = ((parsed ushr SHIFT_16) and BYTE_MASK).toInt()
            val g = ((parsed ushr SHIFT_8) and BYTE_MASK).toInt()
            val b = (parsed and BYTE_MASK).toInt()
            Color(red = r, green = g, blue = b, alpha = FULL_ALPHA)
        }
        HEX_RGBA_LENGTH -> {
            val r = ((parsed ushr SHIFT_24) and BYTE_MASK).toInt()
            val g = ((parsed ushr SHIFT_16) and BYTE_MASK).toInt()
            val b = ((parsed ushr SHIFT_8) and BYTE_MASK).toInt()
            val a = (parsed and BYTE_MASK).toInt()
            Color(red = r, green = g, blue = b, alpha = a)
        }
        else -> defaultColor
    }
}

fun Color.toOpaque(): Color = copy(alpha = 1f)
