package app.openhikes.core.common.places

import app.openhikes.core.common.BoundedText
import app.openhikes.core.common.TextBound
import app.openhikes.core.model.WikipediaArticle
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Failure decoding or querying Wikidata/Wikipedia APIs.
 */
class WikidataApiException(val code: String) : Exception("Wikidata API error: $code")

/**
 * Decoded Wikidata entity links and primary P18 Commons image filename.
 */
data class WikidataEntity(val articles: Map<String, String> = emptyMap(), val image: String? = null) {
    fun articleIn(languages: List<String>): Pair<String, String>? {
        for (language in languages) {
            val title = articles[WikidataQuery.siteFor(language)]
            if (title != null) return language to title
        }
        return null
    }
}

/**
 * Pure-JVM Wikidata (`wbgetentities`) and Wikipedia REST summary URL builders and JSON parsers.
 */
@Suppress("TooManyFunctions")
object WikidataQuery {
    const val ENDPOINT = "https://www.wikidata.org/w/api.php"
    const val FALLBACK_LANGUAGE = "en"
    const val PREFERRED_LANGUAGE_LIMIT = 2
    const val MISSING_ITEM_CODE = "no-such-entity"

    private const val ARTICLE_TYPE = "standard"
    private const val MAX_FILE_NAME_BYTES = 240
    private const val MAX_TITLE_BYTES = 255
    private const val MIN_CODE_LENGTH = 2
    private const val MAX_CODE_LENGTH = 3

    private val json = Json { ignoreUnknownKeys = true }

    private val renamedLanguages = mapOf(
        "bho" to "bh",
        "gsw" to "als",
        "lzh" to "zh-classical",
        "nan" to "zh-min-nan",
        "nb" to "no",
        "rup" to "roa-rup",
        "sgs" to "bat-smg",
        "vro" to "fiu-vro",
        "yue" to "zh-yue"
    )

    fun languages(preferredTags: List<String>): List<String> {
        val result = ArrayList<String>(PREFERRED_LANGUAGE_LIMIT + 1)
        for (tag in preferredTags) {
            if (result.size >= PREFERRED_LANGUAGE_LIMIT) break
            val code = wikipediaCode(tag)
            if (code != null && code !in result) {
                result.add(code)
            }
        }
        if (FALLBACK_LANGUAGE !in result) {
            result.add(FALLBACK_LANGUAGE)
        }
        return result
    }

    fun siteFor(language: String): String = "${language.replace('-', '_')}wiki"

    fun isWikipediaLanguage(code: String): Boolean = code in renamedLanguages.values ||
        (code.length in MIN_CODE_LENGTH..MAX_CODE_LENGTH && code.all { it in 'a'..'z' })

    fun entityUrl(item: String, languages: List<String>): String {
        val siteFilter = languages.joinToString("|", transform = ::siteFor)
        val encodedFilter = URLEncoder.encode(siteFilter, Charsets.UTF_8.name())
        val encodedItem = URLEncoder.encode(item, Charsets.UTF_8.name())
        return "$ENDPOINT?action=wbgetentities&format=json&ids=$encodedItem" +
            "&props=sitelinks%7Cclaims&sitefilter=$encodedFilter"
    }

    fun summaryUrl(title: String, language: String): String? {
        val normalized = title.replace(' ', '_')
        return if (isWikipediaLanguage(language) && normalized.isNotEmpty()) {
            "https://$language.wikipedia.org/api/rest_v1/page/summary/${encodePathSegment(normalized)}"
        } else {
            null
        }
    }

    fun decodeEntity(bytes: ByteArray): WikidataEntity {
        val root = json.parseToJsonElement(bytes.decodeToString()).jsonObject
        val errorCode = (root["error"] as? JsonObject)?.get("code")?.asStringOrNull()
        if (errorCode != null && errorCode != MISSING_ITEM_CODE) {
            throw WikidataApiException(errorCode)
        }
        val entities = if (errorCode == null) root["entities"] as? JsonObject else null
        val firstEntity = entities?.values?.firstOrNull() as? JsonObject
        return if (firstEntity != null) {
            WikidataEntity(
                articles = parseSitelinks(firstEntity["sitelinks"]),
                image = parseImageClaim(firstEntity["claims"])
            )
        } else {
            WikidataEntity()
        }
    }

    fun decodeArticle(bytes: ByteArray, language: String): WikipediaArticle? {
        val summary = json.decodeFromString<SummaryAnswer>(bytes.decodeToString())
        val rawTitle = summary.titles?.normalized ?: summary.title
        val title = BoundedText.bounded(rawTitle, TextBound.TITLE)
        val extract = BoundedText.bounded(summary.extract, TextBound.NOTES)
        val pageUrl = summary.contentUrls?.desktop?.page?.takeIf(::isHttpsWikipediaUrl)
        val hasContent = title != null && extract != null
        return if (summary.type == ARTICLE_TYPE && hasContent && pageUrl != null) {
            WikipediaArticle(title = title, extract = extract, url = pageUrl, language = language)
        } else {
            null
        }
    }

    private fun parseSitelinks(element: JsonElement?): Map<String, String> {
        val obj = element as? JsonObject ?: return emptyMap()
        val result = LinkedHashMap<String, String>(obj.size)
        for ((site, entry) in obj) {
            val title = (entry as? JsonObject)?.get("title")?.asStringOrNull()
            if (title != null && title.isNotEmpty() && title.toByteArray(Charsets.UTF_8).size <= MAX_TITLE_BYTES) {
                result[site] = title
            }
        }
        return result
    }

    private fun parseImageClaim(claimsElement: JsonElement?): String? {
        val statements = (claimsElement as? JsonObject)?.get("P18") as? JsonArray
        val preferred = ArrayList<String>()
        val normal = ArrayList<String>()
        for (raw in statements.orEmpty()) {
            val stmt = raw as? JsonObject
            val rank = stmt?.get("rank")?.asStringOrNull()
            val fileName = if (rank == "preferred" || rank == "normal") extractFileName(stmt) else null
            if (fileName != null) {
                if (rank == "preferred") preferred.add(fileName) else normal.add(fileName)
            }
        }
        return (preferred + normal).firstOrNull()
    }

    private fun extractFileName(statement: JsonObject): String? {
        val datavalue = (statement["mainsnak"] as? JsonObject)?.get("datavalue") as? JsonObject
        val raw = datavalue?.get("value")?.asStringOrNull()
        val bounded = BoundedText.bounded(raw, TextBound.NOTES)
        return bounded?.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_FILE_NAME_BYTES }
    }

    private fun wikipediaCode(identifier: String): String? {
        val rawLang = Locale.forLanguageTag(identifier.replace('_', '-')).language.lowercase(Locale.ROOT)
        if (rawLang.isEmpty()) return null
        val mapped = renamedLanguages[rawLang] ?: rawLang
        return mapped.takeIf(::isWikipediaLanguage)
    }

    private fun isHttpsWikipediaUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull()
        val host = uri?.host?.lowercase(Locale.ROOT)
        return uri?.scheme?.lowercase(Locale.ROOT) == "https" && host?.endsWith(".wikipedia.org") == true
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun JsonElement.asStringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    @Serializable
    private data class SummaryAnswer(
        val type: String? = null,
        val title: String? = null,
        val titles: SummaryTitles? = null,
        val extract: String? = null,
        @SerialName("content_urls") val contentUrls: ContentUrls? = null
    )

    @Serializable
    private data class SummaryTitles(val normalized: String? = null)

    @Serializable
    private data class ContentUrls(val desktop: DesktopPage? = null)

    @Serializable
    private data class DesktopPage(val page: String? = null)
}
