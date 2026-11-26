package app.openhikes.core.data

import androidx.exifinterface.media.ExifInterface
import app.openhikes.core.common.photos.PhotoCaptureDate
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.HikePhoto
import app.openhikes.core.model.PickedPhotoMetadata
import java.io.ByteArrayInputStream
import java.io.File
import java.time.ZoneId

/**
 * Reads and losslessly stamps EXIF capture time, GPS coordinates, and attribution metadata using
 * AndroidX [ExifInterface].
 */
object PhotoMetadataCodec {
    fun readMetadata(bytes: ByteArray, fallbackZone: ZoneId = ZoneId.systemDefault()): PickedPhotoMetadata {
        val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull()
            ?: return PickedPhotoMetadata()
        val capturedAt = PhotoCaptureDate.resolveCaptureTimestamp(
            dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            offsetTimeOriginal = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
            dateTimeDigitized = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED),
            offsetTimeDigitized = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED),
            tiffDateTime = exif.getAttribute(ExifInterface.TAG_DATETIME),
            offsetTime = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME),
            fallbackZone = fallbackZone
        )
        val latLong = exif.latLong
        val coordinate = if (latLong != null) {
            runCatching { GeoCoordinate(latitude = latLong[0], longitude = latLong[1]) }.getOrNull()
        } else {
            null
        }
        return PickedPhotoMetadata(
            capturedAtEpochMillis = capturedAt,
            coordinate = coordinate
        )
    }

    /**
     * Stamps capture wall-clock time, offset, and optional GPS coordinates directly into [file]
     * without re-encoding image pixels.
     */
    fun stampCaptureMetadata(
        file: File,
        capturedAtEpochMillis: Long,
        coordinate: GeoCoordinate?,
        zone: ZoneId = ZoneId.systemDefault()
    ): Boolean = runCatching {
        val exif = ExifInterface(file)
        val wallClock = PhotoCaptureDate.formatExifWallClock(capturedAtEpochMillis, zone)
        val offset = PhotoCaptureDate.formatExifOffset(capturedAtEpochMillis, zone)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, wallClock)
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, wallClock)
        exif.setAttribute(ExifInterface.TAG_DATETIME, wallClock)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offset)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, offset)
        if (coordinate != null) {
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, formatDms(kotlin.math.abs(coordinate.latitude)))
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (coordinate.latitude >= 0.0) "N" else "S")
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, formatDms(kotlin.math.abs(coordinate.longitude)))
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (coordinate.longitude >= 0.0) "E" else "W")
            exif.setAttribute(
                ExifInterface.TAG_GPS_DATESTAMP,
                PhotoCaptureDate.formatGpsDateStamp(capturedAtEpochMillis)
            )
            exif.setAttribute(
                ExifInterface.TAG_GPS_TIMESTAMP,
                PhotoCaptureDate.formatGpsTimeStamp(capturedAtEpochMillis)
            )
        }
        exif.saveAttributes()
        true
    }.getOrDefault(false)

    /**
     * Losslessly writes Wikimedia Commons / author attribution tags into an exported copy [file].
     */
    fun stampCreditMetadata(file: File, photo: HikePhoto): Boolean {
        val license = photo.license ?: return true
        return runCatching {
            val exif = ExifInterface(file)
            val author = photo.importedAuthorName?.trim()?.takeIf { it.isNotEmpty() }
            if (author != null) {
                exif.setAttribute(ExifInterface.TAG_ARTIST, author)
            }
            val copyrightText = if (author != null) {
                "Photo by $author · ${license.name} · ${license.sourceUrl}"
            } else {
                "Photo · ${license.name} · ${license.sourceUrl}"
            }
            exif.setAttribute(ExifInterface.TAG_COPYRIGHT, copyrightText)
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, copyrightText)
            exif.saveAttributes()
            true
        }.getOrDefault(false)
    }

    private fun formatDms(absDegrees: Double): String {
        val degrees = absDegrees.toInt()
        val minutesFloat = (absDegrees - degrees) * MINUTES_PER_DEGREE
        val minutes = minutesFloat.toInt()
        val secondsScaled = kotlin.math.round((minutesFloat - minutes) * SECONDS_PER_MINUTE * DMS_SCALE).toLong()
        return "$degrees/1,$minutes/1,$secondsScaled/$DMS_SCALE"
    }

    private const val MINUTES_PER_DEGREE = 60.0
    private const val SECONDS_PER_MINUTE = 60.0
    private const val DMS_SCALE = 10_000L
}
