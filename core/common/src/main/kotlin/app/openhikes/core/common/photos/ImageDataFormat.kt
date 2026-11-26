package app.openhikes.core.common.photos

/**
 * Pure-JVM magic-byte detector identifying image container formats so imported photos are stored
 * byte-for-byte with an accurate filename extension and non-image payloads are rejected safely.
 */
object ImageDataFormat {
    private const val MIN_HEADER_BYTES = 12
    private const val JPEG_HEADER_LENGTH = 3
    private const val GIF_HEADER_LENGTH = 6
    private const val BYTE_MASK = 0xFF
    private const val JPEG_MARKER_0 = 0xFF
    private const val JPEG_MARKER_1 = 0xD8
    private const val JPEG_MARKER_2 = 0xFF
    private const val FTYP_OFFSET = 4
    private const val BRAND_OFFSET = 8
    private const val BRAND_LENGTH = 4

    private val PNG_SIGNATURE = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1", "avif")

    /**
     * Returns the canonical lowercase file extension (`"jpeg"`, `"png"`, `"webp"`, `"heic"`,
     * `"gif"`) for [bytes], or `null` if [bytes] does not start with a recognized image header.
     */
    fun detectExtension(bytes: ByteArray): String? = when {
        isJpeg(bytes) -> "jpeg"
        isPng(bytes) -> "png"
        isWebp(bytes) -> "webp"
        isHeif(bytes) -> "heic"
        isGif(bytes) -> "gif"
        else -> null
    }

    private fun isJpeg(bytes: ByteArray): Boolean = bytes.size >= JPEG_HEADER_LENGTH &&
        (bytes[0].toInt() and BYTE_MASK) == JPEG_MARKER_0 &&
        (bytes[1].toInt() and BYTE_MASK) == JPEG_MARKER_1 &&
        (bytes[2].toInt() and BYTE_MASK) == JPEG_MARKER_2

    private fun isPng(bytes: ByteArray): Boolean = bytes.size >= PNG_SIGNATURE.size &&
        PNG_SIGNATURE.indices.all { index -> (bytes[index].toInt() and BYTE_MASK) == PNG_SIGNATURE[index] }

    private fun isWebp(bytes: ByteArray): Boolean = bytes.size >= MIN_HEADER_BYTES &&
        ascii(bytes, 0, BRAND_LENGTH) == "RIFF" &&
        ascii(bytes, BRAND_OFFSET, BRAND_LENGTH) == "WEBP"

    private fun isHeif(bytes: ByteArray): Boolean = bytes.size >= MIN_HEADER_BYTES &&
        ascii(bytes, FTYP_OFFSET, BRAND_LENGTH) == "ftyp" &&
        ascii(bytes, BRAND_OFFSET, BRAND_LENGTH).lowercase() in HEIF_BRANDS

    private fun isGif(bytes: ByteArray): Boolean {
        if (bytes.size < GIF_HEADER_LENGTH) return false
        val header = ascii(bytes, 0, GIF_HEADER_LENGTH)
        return header == "GIF87a" || header == "GIF89a"
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        bytes.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)
}
