package app.openhikes.core.data

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import org.junit.Test

class PhotoFileIoTest {
    @Test
    fun readBoundedStreamReturnsBytesUpToExactCapAndRejectsOverflow() {
        val exact = ByteArray(1024) { index -> (index % 251).toByte() }
        val exactRead = AndroidPhotoFileIo.readBoundedStream(ByteArrayInputStream(exact), maxBytes = 1024)
        assertThat(exactRead).isEqualTo(exact)

        val smaller = ByteArray(512) { 0x2A }
        val smallerRead = AndroidPhotoFileIo.readBoundedStream(ByteArrayInputStream(smaller), maxBytes = 1024)
        assertThat(smallerRead).isEqualTo(smaller)

        val oversized = ByteArray(1025) { 0x7F }
        val oversizedRead = AndroidPhotoFileIo.readBoundedStream(ByteArrayInputStream(oversized), maxBytes = 1024)
        assertThat(oversizedRead).isNull()
    }
}
