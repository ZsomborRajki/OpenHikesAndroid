package app.openhikes.core.common

import java.text.BreakIterator
import java.util.Locale

/**
 * Dual ceiling on free text: human-perceived extended grapheme clusters and UTF-8 byte length.
 */
data class TextBound(val characters: Int) {
    val utf8Bytes: Int
        get() = characters * BYTES_PER_CHARACTER

    companion object {
        private const val BYTES_PER_CHARACTER = 4
        private const val TITLE_CHARACTERS = 128
        private const val CREDIT_CHARACTERS = 64
        private const val NOTES_CHARACTERS = 4096
        private const val KEYWORDS_CHARACTERS = 256

        val TITLE = TextBound(TITLE_CHARACTERS)
        val CREDIT = TextBound(CREDIT_CHARACTERS)
        val NOTES = TextBound(NOTES_CHARACTERS)
        val KEYWORDS = TextBound(KEYWORDS_CHARACTERS)
    }
}

/**
 * Trims and bounds free text strictly on extended grapheme cluster boundaries (UAX #29).
 */
object BoundedText {
    fun bounded(raw: String?, bound: TextBound): String? {
        val trimmed = raw?.trim()
        if (trimmed.isNullOrEmpty()) return null

        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(trimmed)

        var clusterCount = 0
        var utf8ByteCount = 0
        var cutEnd = 0
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val cluster = trimmed.substring(start, end)
            val clusterBytes = cluster.toByteArray(Charsets.UTF_8).size
            if (clusterCount >= bound.characters || utf8ByteCount + clusterBytes > bound.utf8Bytes) {
                break
            }
            clusterCount += 1
            utf8ByteCount += clusterBytes
            cutEnd = end
            start = end
            end = iterator.next()
        }

        val result = trimmed.substring(0, cutEnd).trim()
        return result.ifEmpty { null }
    }

    fun boundedOrEmpty(raw: String?, bound: TextBound): String = bounded(raw, bound).orEmpty()
}
