package app.openhikes.core.common.offline

import app.openhikes.core.model.OfflineCountryFile
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Streaming SHA-256 and exact byte-length verification for staged offline country PMTiles archives.
 */
object OfflineCountryDigest {
    private const val CHUNK_BYTES = 8 * 1024 * 1024
    private const val HEX_MASK = 0xFF
    private const val HIGH_NIBBLE_SHIFT = 4
    private const val LOW_NIBBLE_MASK = 0x0F
    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK_BYTES)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return toLowerHex(digest.digest())
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return toLowerHex(digest.digest(bytes))
    }

    /**
     * Checks that [diskFile] exists, matches [file.byteCount] exactly, and has lowercase SHA-256
     * equal to [file.sha256].
     */
    fun isWhole(file: OfflineCountryFile, diskFile: File): Boolean {
        if (!diskFile.isFile || diskFile.length() != file.byteCount) return false
        return runCatching {
            sha256Hex(diskFile).equals(file.sha256, ignoreCase = false)
        }.getOrDefault(false)
    }

    private fun toLowerHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and HEX_MASK
            chars[i * 2] = HEX_CHARS[value ushr HIGH_NIBBLE_SHIFT]
            chars[i * 2 + 1] = HEX_CHARS[value and LOW_NIBBLE_MASK]
        }
        return String(chars)
    }
}
