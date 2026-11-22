package app.openhikes.core.common

/**
 * Single source of truth for hike title length and GPX track/filename fallback rules.
 *
 * Caps titles at 128 extended grapheme clusters and 512 UTF-8 bytes without splitting
 * surrogate pairs, flags, combining marks, or ZWJ sequences.
 */
object HikeTitle {
    val MAXIMUM_CHARACTERS: Int = TextBound.TITLE.characters
    val MAXIMUM_UTF8_BYTES: Int = TextBound.TITLE.utf8Bytes

    fun bounded(raw: String?): String? = BoundedText.bounded(raw, TextBound.TITLE)

    fun imported(trackName: String?, fileName: String?): String =
        bounded(trackName) ?: bounded(extractFileStem(fileName)).orEmpty()

    private fun extractFileStem(fileName: String?): String? {
        val lastSegment = fileName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(".gpx", ignoreCase = true) }
        return lastSegment?.let { segment ->
            val dotIndex = segment.lastIndexOf('.')
            if (dotIndex > 0) segment.substring(0, dotIndex) else segment
        }
    }
}

/**
 * Result of committing a title edit against an existing hike's [displayTitle].
 *
 * Distinguishes unchanged text from an explicit clear (which resets `customName` to `null`
 * so the hike falls back to its original imported/recorded title).
 */
sealed interface HikeTitleEdit {
    data class Renamed(val customName: String?) : HikeTitleEdit

    data object Unchanged : HikeTitleEdit

    companion object {
        fun of(draft: String, displayTitle: String): HikeTitleEdit {
            val bounded = HikeTitle.bounded(draft)
            return if (bounded.orEmpty() == displayTitle) {
                Unchanged
            } else {
                Renamed(bounded)
            }
        }
    }
}
