package app.openhikes.core.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import kotlin.math.max

/**
 * Size-bounded, EXIF-orientation-aware bitmap decoder for full-screen viewers, discovery grids,
 * and staged place thumbnails so portrait/rotated originals always display upright without
 * exhausting the heap.
 */
object PhotoBitmapDecoder {
    private const val ROTATE_90 = 90f
    private const val ROTATE_180 = 180f
    private const val ROTATE_270 = 270f

    fun decodeUpright(bytes: ByteArray, maxEdgePx: Int): ImageBitmap? =
        decodeUprightBitmap(bytes, maxEdgePx)?.asImageBitmap()

    fun decodeUprightBitmap(bytes: ByteArray, maxEdgePx: Int): Bitmap? = runCatching {
        if (bytes.isEmpty() || maxEdgePx <= 0) return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@runCatching null
        val orientation = readExifOrientation(bytes)
        val upright = applyExifOrientation(decoded, orientation)
        if (upright !== decoded) {
            decoded.recycle()
        }
        upright
    }.getOrNull()

    private fun computeSampleSize(width: Int, height: Int, maxEdgePx: Int): Int {
        var sample = 1
        var longest = max(width, height)
        while (longest / 2 >= maxEdgePx) {
            sample *= 2
            longest /= 2
        }
        return sample
    }

    private fun readExifOrientation(bytes: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(ROTATE_90)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(ROTATE_180)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(ROTATE_270)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(ROTATE_90)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(ROTATE_270)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
