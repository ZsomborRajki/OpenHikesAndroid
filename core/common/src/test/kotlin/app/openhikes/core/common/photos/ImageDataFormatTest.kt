package app.openhikes.core.common.photos

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImageDataFormatTest {
    @Test
    fun detectsKnownImageMagicHeadersAndRejectsNonImages() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00)
        val webp = "RIFF1234WEBP".toByteArray(Charsets.US_ASCII)
        val heic = byteArrayOf(0, 0, 0, 24) + "ftypheic".toByteArray(Charsets.US_ASCII)
        val gif = "GIF89a1234".toByteArray(Charsets.US_ASCII)
        val text = "not an image".toByteArray(Charsets.UTF_8)

        assertThat(ImageDataFormat.detectExtension(jpeg)).isEqualTo("jpeg")
        assertThat(ImageDataFormat.detectExtension(png)).isEqualTo("png")
        assertThat(ImageDataFormat.detectExtension(webp)).isEqualTo("webp")
        assertThat(ImageDataFormat.detectExtension(heic)).isEqualTo("heic")
        assertThat(ImageDataFormat.detectExtension(gif)).isEqualTo("gif")
        assertThat(ImageDataFormat.detectExtension(text)).isNull()
        assertThat(ImageDataFormat.detectExtension(byteArrayOf())).isNull()
    }
}
