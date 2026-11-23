package app.openhikes.core.ui.format

import java.text.NumberFormat
import java.util.Locale

/**
 * Locale-aware decimal file-size formatter matching SI units (KB, MB, GB) for offline country downloads.
 */
object StorageSizeFormatter {
    private const val KILOBYTE = 1_000.0
    private const val MEGABYTE = 1_000_000.0
    private const val GIGABYTE = 1_000_000_000.0
    private const val HIGH_VALUE_THRESHOLD = 100.0
    private const val TEN_THRESHOLD = 10.0

    fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
        val safe = bytes.coerceAtLeast(0L).toDouble()
        return when {
            safe >= GIGABYTE -> {
                val gb = safe / GIGABYTE
                val decimals = when {
                    gb >= HIGH_VALUE_THRESHOLD -> 0
                    gb >= TEN_THRESHOLD -> 1
                    else -> 2
                }
                "${formatNumber(gb, decimals, locale)} GB"
            }

            safe >= MEGABYTE -> {
                val mb = safe / MEGABYTE
                val decimals = if (mb >= HIGH_VALUE_THRESHOLD) 0 else 1
                "${formatNumber(mb, decimals, locale)} MB"
            }

            safe >= KILOBYTE -> "${formatNumber(safe / KILOBYTE, 0, locale)} KB"
            else -> "${formatNumber(safe, 0, locale)} B"
        }
    }

    private fun formatNumber(value: Double, fractionDigits: Int, locale: Locale): String {
        val formatter = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = fractionDigits
            maximumFractionDigits = fractionDigits
        }
        return formatter.format(value)
    }
}
