package app.openhikes.core.common.places

import app.openhikes.core.common.BoundedText
import app.openhikes.core.common.TextBound
import app.openhikes.core.model.CommonsPhotoCredit
import app.openhikes.core.model.CommonsPhotoLicense
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Approved Wikimedia Commons photograph metadata returned by `prop=imageinfo`.
 */
data class CommonsFileInfo(val pageId: Int, val thumbnailUrl: String, val credit: CommonsPhotoCredit)

/**
 * Failure decoding or querying Wikimedia Commons.
 */
class CommonsApiException(val code: String, val isBusy: Boolean) : Exception("Commons API error: $code")

/**
 * License allowlist matching iOS OpenHikes: only Creative Commons attribution, CC0, and Public
 * Domain licenses without `-NC` or `-ND` clauses are displayed.
 */
object CommonsLicense {
    private val acceptedPrefixes = listOf("CC BY", "CC0", "Public domain")
    private val refusedClauses = listOf("-NC", "-ND")

    fun accepted(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        val allowed = acceptedPrefixes.any { trimmed.startsWith(it) } &&
            refusedClauses.none { trimmed.contains(it) }
        return if (allowed) trimmed else null
    }
}

/**
 * Strips MediaWiki HTML tags/entities and user-page namespace prefixes from Commons extmetadata.
 */
object CommonsText {
    private val userPrefixes = listOf("User:", "Benutzer:")
    private val tagRegex = Regex("<[^>]*>")
    private val numericEntityRegex = Regex("&#(x?)([0-9A-Fa-f]+);")
    private val whitespaceRegex = Regex("\\s+")
    private val namedEntities = listOf(
        "&apos;" to "'",
        "&gt;" to ">",
        "&lt;" to "<",
        "&nbsp;" to " ",
        "&quot;" to "\"",
        "&amp;" to "&"
    )

    fun author(html: String?): String? {
        if (html == null) return null
        var text = plain(html)
        for (prefix in userPrefixes) {
            if (text.startsWith(prefix)) {
                text = text.removePrefix(prefix)
            }
        }
        return BoundedText.bounded(text, TextBound.CREDIT)
    }

    fun link(raw: String?): String? {
        val trimmed = raw?.let(::plain)?.takeIf { it.isNotEmpty() }
        val absolute = trimmed?.let { if (it.startsWith("//")) "https:$it" else it }
        val scheme = absolute?.let { runCatching { URI(it) }.getOrNull()?.scheme?.lowercase(Locale.ROOT) }
        return if (scheme == "https" || scheme == "http") absolute else null
    }

    fun plain(html: String): String {
        val untagged = tagRegex.replace(html, " ")
        var decoded = numericEntityRegex.replace(untagged) { match ->
            val radix = if (match.groupValues[1].isEmpty()) DECIMAL_RADIX else HEX_RADIX
            val codePoint = match.groupValues[2].toIntOrNull(radix)
            if (codePoint != null && Character.isValidCodePoint(codePoint)) {
                String(Character.toChars(codePoint))
            } else {
                ""
            }
        }
        for ((entity, replacement) in namedEntities) {
            decoded = decoded.replace(entity, replacement)
        }
        return whitespaceRegex.replace(decoded.trim(), " ")
    }

    private const val DECIMAL_RADIX = 10
    private const val HEX_RADIX = 16
}

/**
 * Pure-JVM Wikimedia Commons `imageinfo` URL builder, metadata validator, and image magic check.
 */
object CommonsPhotoQuery {
    const val ENDPOINT = "https://commons.wikimedia.org/w/api.php"
    const val THUMBNAIL_WIDTH = 960
    const val MINIMUM_WIDTH = 640
    const val PHOTOGRAPH_MIME = "image/jpeg"

    val IMAGE_HOSTS: Set<String> = setOf("thumb.wikimedia.org", "upload.wikimedia.org")

    private val busyCodes: Set<String> = setOf("cirrussearch-too-busy-error", "maxlag", "ratelimited")
    private val json = Json { ignoreUnknownKeys = true }
    private const val EXTMETADATA_FILTER = "Artist|DateTimeOriginal|LicenseShortName|LicenseUrl|Restrictions"

    fun imageInfoUrl(fileTitle: String): String {
        val prefixed = "File:$fileTitle"
        val encodedTitle = URLEncoder.encode(prefixed, Charsets.UTF_8.name())
            .replace("+", "%20")
        return "$ENDPOINT?action=query&format=json&formatversion=2" +
            "&titles=$encodedTitle" +
            "&prop=imageinfo" +
            "&iiprop=url%7Cmime%7Csize%7Ctimestamp%7Csha1%7Cextmetadata" +
            "&iiurlwidth=$THUMBNAIL_WIDTH" +
            "&iiextmetadatafilter=${URLEncoder.encode(EXTMETADATA_FILTER, Charsets.UTF_8.name())}"
    }

    fun decodeFiles(bytes: ByteArray): List<CommonsFileInfo> {
        val root = json.parseToJsonElement(bytes.decodeToString()).jsonObject
        val errorCode = (root["error"] as? JsonObject)?.get("code")?.asStringOrNull()
        if (errorCode != null) {
            throw CommonsApiException(code = errorCode, isBusy = errorCode in busyCodes)
        }
        val query = root["query"] as? JsonObject
        val pages = query?.get("pages") as? JsonArray
        return pages?.mapNotNull(::parsePage).orEmpty()
    }

    fun isValidImageBytes(bytes: ByteArray): Boolean {
        val isJpeg = bytes.size >= JPEG_MIN_BYTES &&
            bytes[0] == JPEG_MAGIC_0 &&
            bytes[1] == JPEG_MAGIC_1 &&
            bytes[2] == JPEG_MAGIC_2
        val isPng = bytes.size >= PNG_MIN_BYTES &&
            bytes[0] == PNG_MAGIC_0 &&
            bytes[1] == PNG_MAGIC_1 &&
            bytes[2] == PNG_MAGIC_2 &&
            bytes[3] == PNG_MAGIC_3
        return isJpeg || isPng
    }

    private fun parsePage(element: JsonElement): CommonsFileInfo? {
        val page = element as? JsonObject
        val pageId = page?.get("pageid")?.asIntOrNull()
        val imageInfo = (page?.get("imageinfo") as? JsonArray)?.firstOrNull() as? JsonObject
        return if (pageId != null && imageInfo != null) parseImageInfo(pageId, imageInfo) else null
    }

    private fun parseImageInfo(pageId: Int, info: JsonObject): CommonsFileInfo? {
        val mime = info["mime"]?.asStringOrNull()
        val width = info["width"]?.asIntOrNull() ?: 0
        val ext = info["extmetadata"] as? JsonObject
        val restrictions = ext.metadataValue("Restrictions")
        val thumbUrl = info["thumburl"]?.asStringOrNull()?.takeIf(::isAllowedThumbnailUrl)
        val sourceUrl = info["descriptionurl"]?.asStringOrNull()?.takeIf(::isHttpsUrl)
        val licenseName = CommonsLicense.accepted(ext.metadataValue("LicenseShortName"))
        val valid = mime == PHOTOGRAPH_MIME &&
            width >= MINIMUM_WIDTH &&
            restrictions.isNullOrEmpty() &&
            thumbUrl != null &&
            sourceUrl != null &&
            licenseName != null
        return if (valid) {
            CommonsFileInfo(
                pageId = pageId,
                thumbnailUrl = thumbUrl,
                credit = CommonsPhotoCredit(
                    author = CommonsText.author(ext.metadataValue("Artist")),
                    license = CommonsPhotoLicense(
                        name = licenseName,
                        url = CommonsText.link(ext.metadataValue("LicenseUrl")),
                        sourceUrl = sourceUrl,
                        fileSha1 = info["sha1"]?.asStringOrNull()
                    )
                )
            )
        } else {
            null
        }
    }

    private fun JsonObject?.metadataValue(key: String): String? =
        (this?.get(key) as? JsonObject)?.get("value")?.asStringOrNull()

    private fun isAllowedThumbnailUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        return scheme == "https" && host != null && host in IMAGE_HOSTS
    }

    private fun isHttpsUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        return uri.scheme?.lowercase(Locale.ROOT) == "https" && !uri.host.isNullOrEmpty()
    }

    private fun JsonElement.asStringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonElement.asIntOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull

    private const val JPEG_MIN_BYTES = 3
    private const val JPEG_MAGIC_0 = 0xFF.toByte()
    private const val JPEG_MAGIC_1 = 0xD8.toByte()
    private const val JPEG_MAGIC_2 = 0xFF.toByte()
    private const val PNG_MIN_BYTES = 8
    private const val PNG_MAGIC_0 = 0x89.toByte()
    private const val PNG_MAGIC_1 = 0x50.toByte()
    private const val PNG_MAGIC_2 = 0x4E.toByte()
    private const val PNG_MAGIC_3 = 0x47.toByte()
}
