package app.openhikes.core.model

/**
 * Wikipedia REST summary metadata shown on a place card.
 * Never persisted to hike storage; displayed with attribution and CC BY-SA 4.0 license link.
 */
data class WikipediaArticle(val title: String, val extract: String, val url: String, val language: String)

/**
 * License metadata for a Wikimedia Commons photograph.
 */
data class CommonsPhotoLicense(
    val name: String,
    val url: String? = null,
    val sourceUrl: String,
    val fileSha1: String? = null
)

/**
 * Attribution credit for a Wikimedia Commons photograph.
 */
data class CommonsPhotoCredit(val author: String? = null, val license: CommonsPhotoLicense)

/**
 * Encoded thumbnail bytes and attribution for a Wikidata item's Commons photograph.
 */
data class WikidataPhoto(val credit: CommonsPhotoCredit, val imageBytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WikidataPhoto) return false
        return credit == other.credit && imageBytes.contentEquals(other.imageBytes)
    }

    override fun hashCode(): Int {
        var result = credit.hashCode()
        result = 31 * result + imageBytes.contentHashCode()
        return result
    }
}

/**
 * Wikipedia summary and/or Wikimedia Commons photo resolved from an OpenStreetMap `wikidata` tag.
 */
data class WikidataPlace(
    val item: String,
    val article: WikipediaArticle?,
    val photo: WikidataPhoto?,
    val isComplete: Boolean = true
) {
    init {
        require(article != null || photo != null) {
            "WikidataPlace must carry at least an article or a photo"
        }
    }

    val hasWikipediaArticle: Boolean
        get() = article != null

    companion object {
        const val TEXT_LICENSE_NAME = "CC BY-SA 4.0"
        const val TEXT_LICENSE_URL = "https://creativecommons.org/licenses/by-sa/4.0/"

        fun createOrNull(
            item: String,
            article: WikipediaArticle?,
            photo: WikidataPhoto?,
            isComplete: Boolean = true
        ): WikidataPlace? = if (article == null && photo == null) {
            null
        } else {
            WikidataPlace(item = item, article = article, photo = photo, isComplete = isComplete)
        }
    }
}

/**
 * Outcome of querying Wikidata, Wikipedia, and Wikimedia Commons for a Q-item.
 */
sealed interface WikidataFetchResult {
    data class Found(val place: WikidataPlace?) : WikidataFetchResult

    data class Failed(val reason: String) : WikidataFetchResult
}

/**
 * Seam resolving a Wikidata Q-item into its Wikipedia article summary and Commons photograph.
 */
interface WikidataPlaceSource {
    suspend fun place(item: String): WikidataFetchResult
}
