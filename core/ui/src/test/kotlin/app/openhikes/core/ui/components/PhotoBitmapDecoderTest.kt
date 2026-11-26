package app.openhikes.core.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhotoBitmapDecoderTest {
    @Test
    fun decodeUprightBitmapAppliesExifOrientation90() {
        val tempFile = File.createTempFile("rotated-photo-", ".jpg")
        try {
            val landscape = createBitmap(40, 20).apply { eraseColor(Color.BLUE) }
            FileOutputStream(tempFile).use { out ->
                landscape.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            landscape.recycle()

            val exif = ExifInterface(tempFile)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            exif.saveAttributes()

            val decoded = PhotoBitmapDecoder.decodeUprightBitmap(tempFile.readBytes(), maxEdgePx = 2688)
            assertThat(decoded).isNotNull()
            assertThat(decoded!!.width).isEqualTo(20)
            assertThat(decoded.height).isEqualTo(40)
            decoded.recycle()
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun decodeUprightBitmapDownsamplesLargeImageToMaxEdge() {
        val tempFile = File.createTempFile("large-photo-", ".jpg")
        try {
            val large = createBitmap(800, 400).apply { eraseColor(Color.GREEN) }
            FileOutputStream(tempFile).use { out ->
                large.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            large.recycle()

            val decoded = PhotoBitmapDecoder.decodeUprightBitmap(tempFile.readBytes(), maxEdgePx = 200)
            assertThat(decoded).isNotNull()
            assertThat(max(decoded!!.width, decoded.height)).isAtMost(200)
            decoded.recycle()
        } finally {
            tempFile.delete()
        }
    }
}
