package app.openhikes.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BoundedTextTest {
    @Test
    fun capsPlainAsciiAtTitleCharacterLimitAndTrimsWhitespace() {
        val raw = "   " + "a".repeat(150) + "   "
        val bounded = BoundedText.bounded(raw, TextBound.TITLE)!!

        assertThat(bounded).hasLength(128)
        assertThat(bounded).isEqualTo("a".repeat(128))
        assertThat(BoundedText.bounded("   ", TextBound.TITLE)).isNull()
        assertThat(BoundedText.bounded(null, TextBound.TITLE)).isNull()
    }

    @Test
    fun neverSplitsSurrogatePairsOrCombiningSequencesAndEnforcesUtf8ByteLimit() {
        val combiningText = "e\u0301".repeat(140)
        val boundedCombining = BoundedText.bounded(combiningText, TextBound.TITLE)!!
        assertThat(boundedCombining.toByteArray(Charsets.UTF_8).size)
            .isAtMost(HikeTitle.MAXIMUM_UTF8_BYTES)
        assertThat(boundedCombining.length % 2).isEqualTo(0)

        // Each four-byte supplementary scalar fits cleanly into 512 UTF-8 bytes without orphaned surrogates.
        val bootEmojiText = "\uD83E\uDD7E".repeat(140)
        val boundedEmoji = BoundedText.bounded(bootEmojiText, TextBound.TITLE)!!
        assertThat(boundedEmoji.toByteArray(Charsets.UTF_8).size)
            .isAtMost(HikeTitle.MAXIMUM_UTF8_BYTES)
        assertThat(boundedEmoji.codePointCount(0, boundedEmoji.length)).isEqualTo(128)
        assertThat(Character.isHighSurrogate(boundedEmoji.last())).isFalse()
    }

    @Test
    fun enforcesStrictUtf8ByteCeilingOnDenseMultibyteGraphemeClusters() {
        val flagSequence = "\uD83C\uDDE9\uD83C\uDDEA" // 2 codepoints, 8 UTF-8 bytes
        val repeatedFlags = flagSequence.repeat(100) // 800 bytes > 512 bytes
        val bounded = BoundedText.bounded(repeatedFlags, TextBound.TITLE)!!

        assertThat(bounded.toByteArray(Charsets.UTF_8).size)
            .isAtMost(HikeTitle.MAXIMUM_UTF8_BYTES)
        assertThat(bounded.endsWith("\uD83C")).isFalse()
        assertThat(bounded.endsWith("\uDDE9")).isFalse()
    }
}
