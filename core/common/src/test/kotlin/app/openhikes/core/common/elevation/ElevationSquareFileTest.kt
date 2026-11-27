package app.openhikes.core.common.elevation

import app.openhikes.core.common.routing.PackedFileException
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ElevationSquareFileTest {
    @Test
    fun encodesAndDecodesSlopedTerrainWrappingExtremesAndUnknownPosts() {
        val rows = 6
        val columns = 5
        val heights = ShortArray(rows * columns) { idx ->
            when (idx) {
                0 -> 0
                1 -> ElevationSquareFile.UNKNOWN
                2 -> Short.MAX_VALUE
                3 -> (Short.MIN_VALUE + 1).toShort()
                4 -> (-420).toShort()
                else -> (500 + (idx / columns) * 35 - (idx % columns) * 19).toShort()
            }
        }
        val original = ElevationSquareFile(rows = rows, columns = columns, heights = heights)
        val encoded = original.encode()
        val decoded = ElevationSquareFile.decode(encoded)

        assertThat(decoded).isEqualTo(original)
        assertThat(decoded.isSeaLevel).isFalse()
        assertThat(decoded.heightAt(0)).isEqualTo(0.0)
        assertThat(decoded.heightAt(1)).isNull()
        assertThat(decoded.heightAt(2)).isEqualTo(Short.MAX_VALUE.toDouble())
        assertThat(decoded.heightAt(4)).isEqualTo(-420.0)
    }

    @Test
    fun detectsSeaLevelSquareAndRejectsCorruptOrMismatchedEnvelopes() {
        val sea = ElevationSquareFile(rows = 4, columns = 4, heights = ShortArray(16))
        assertThat(sea.isSeaLevel).isTrue()
        val encoded = sea.encode()
        assertThat(ElevationSquareFile.decode(encoded).isSeaLevel).isTrue()

        val wrongMagic = encoded.copyOf().apply { this[0] = 'X'.code.toByte() }
        assertThrows(PackedFileException.WrongFormat::class.java) {
            ElevationSquareFile.decode(wrongMagic)
        }

        val wrongVersion = encoded.copyOf().apply { this[4] = 99 }
        assertThrows(PackedFileException.UnsupportedVersion::class.java) {
            ElevationSquareFile.decode(wrongVersion)
        }

        val truncated = encoded.copyOf(encoded.size - 3)
        assertThrows(PackedFileException::class.java) {
            ElevationSquareFile.decode(truncated)
        }
    }
}
