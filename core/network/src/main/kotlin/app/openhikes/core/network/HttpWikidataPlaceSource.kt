package app.openhikes.core.network

import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.common.places.CommonsApiException
import app.openhikes.core.common.places.CommonsPhotoQuery
import app.openhikes.core.common.places.WikidataApiException
import app.openhikes.core.common.places.WikidataEntity
import app.openhikes.core.common.places.WikidataQuery
import app.openhikes.core.model.TileNetworkPurpose
import app.openhikes.core.model.WikidataFetchResult
import app.openhikes.core.model.WikidataPhoto
import app.openhikes.core.model.WikidataPlace
import app.openhikes.core.model.WikidataPlaceSource
import app.openhikes.core.model.WikipediaArticle
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches Wikidata entity links, Wikipedia REST summaries, and Wikimedia Commons thumbnails on
 * demand while honoring interactive network / Data Saver policy and Wikimedia User-Agent rules.
 */
@Singleton
internal class HttpWikidataPlaceSource @Inject constructor(
    @TileOkHttpClient private val httpClient: OkHttpClient,
    private val tileNetworkPolicy: TileNetworkPolicy,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : WikidataPlaceSource {
    override suspend fun place(item: String): WikidataFetchResult = withContext(ioDispatcher) {
        if (!tileNetworkPolicy.decide(TileNetworkPurpose.INTERACTIVE).isAllowed) {
            return@withContext WikidataFetchResult.Failed("offline_or_data_saver")
        }
        val languages = WikidataQuery.languages(listOf(Locale.getDefault().toLanguageTag()))
        val entity = fetchEntity(item, languages) ?: return@withContext WikidataFetchResult.Failed("entity_failed")
        resolveDetails(item, entity, languages)
    }

    private suspend fun resolveDetails(
        item: String,
        entity: WikidataEntity,
        languages: List<String>
    ): WikidataFetchResult = coroutineScope {
        val photoDeferred = async { fetchPhoto(entity.image) }
        val articleOutcome = fetchArticle(entity, languages)
        val photoOutcome = photoDeferred.await()
        when (articleOutcome) {
            is ArticleOutcome.Failed -> WikidataFetchResult.Failed("article_failed")
            is ArticleOutcome.Resolved -> combineOutcomes(item, articleOutcome.article, photoOutcome)
        }
    }

    private fun combineOutcomes(
        item: String,
        article: WikipediaArticle?,
        photoOutcome: PhotoOutcome
    ): WikidataFetchResult = when (photoOutcome) {
        is PhotoOutcome.Resolved -> WikidataFetchResult.Found(
            WikidataPlace.createOrNull(item = item, article = article, photo = photoOutcome.photo)
        )
        PhotoOutcome.Failed -> if (article != null) {
            WikidataFetchResult.Found(
                WikidataPlace(item = item, article = article, photo = null, isComplete = false)
            )
        } else {
            WikidataFetchResult.Failed("photo_failed")
        }
    }

    private fun fetchEntity(item: String, languages: List<String>): WikidataEntity? {
        val url = WikidataQuery.entityUrl(item, languages)
        val bytes = fetchBytesOrNull(url) ?: return null
        return try {
            WikidataQuery.decodeEntity(bytes)
        } catch (_: WikidataApiException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun fetchArticle(entity: WikidataEntity, languages: List<String>): ArticleOutcome {
        val resolved = entity.articleIn(languages)
        val url = resolved?.let { (lang, title) -> WikidataQuery.summaryUrl(title, lang) }
        return if (resolved == null || url == null) {
            ArticleOutcome.Resolved(null)
        } else {
            executeArticleRequest(url, resolved.first)
        }
    }

    private fun executeArticleRequest(url: String, language: String): ArticleOutcome = try {
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        httpClient.newCall(request).execute().use { response ->
            when (response.code) {
                HTTP_NOT_FOUND -> ArticleOutcome.Resolved(null)
                in HTTP_OK_RANGE -> ArticleOutcome.Resolved(
                    runCatching { WikidataQuery.decodeArticle(response.body.bytes(), language) }.getOrNull()
                )
                else -> ArticleOutcome.Failed
            }
        }
    } catch (_: IOException) {
        ArticleOutcome.Failed
    }

    private fun fetchPhoto(fileName: String?): PhotoOutcome = if (fileName == null) {
        PhotoOutcome.Resolved(null)
    } else {
        fetchNamedPhoto(fileName)
    }

    private fun fetchNamedPhoto(fileName: String): PhotoOutcome {
        val infoBytes = fetchBytesOrNull(CommonsPhotoQuery.imageInfoUrl(fileName))
        val parsed = infoBytes?.let(::decodeCommonsFileInfo)
        return when {
            infoBytes == null || parsed == null -> PhotoOutcome.Failed
            parsed.info == null -> PhotoOutcome.Resolved(null)
            else -> fetchThumbnail(parsed.info)
        }
    }

    private fun decodeCommonsFileInfo(bytes: ByteArray): CommonsInfoParse? = try {
        CommonsInfoParse(CommonsPhotoQuery.decodeFiles(bytes).firstOrNull())
    } catch (_: CommonsApiException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun fetchThumbnail(fileInfo: app.openhikes.core.common.places.CommonsFileInfo): PhotoOutcome {
        val imageBytes = fetchBytesOrNull(fileInfo.thumbnailUrl)
        return when {
            imageBytes == null -> PhotoOutcome.Failed
            CommonsPhotoQuery.isValidImageBytes(imageBytes) ->
                PhotoOutcome.Resolved(WikidataPhoto(credit = fileInfo.credit, imageBytes = imageBytes))
            else -> PhotoOutcome.Resolved(null)
        }
    }

    private data class CommonsInfoParse(val info: app.openhikes.core.common.places.CommonsFileInfo?)

    private fun fetchBytesOrNull(url: String): ByteArray? = try {
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        httpClient.newCall(request).execute().use { response ->
            if (response.code in HTTP_OK_RANGE) response.body.bytes() else null
        }
    } catch (_: IOException) {
        null
    }

    private sealed interface ArticleOutcome {
        data class Resolved(val article: WikipediaArticle?) : ArticleOutcome

        data object Failed : ArticleOutcome
    }

    private sealed interface PhotoOutcome {
        data class Resolved(val photo: WikidataPhoto?) : PhotoOutcome

        data object Failed : PhotoOutcome
    }

    private companion object {
        val HTTP_OK_RANGE = 200..299
        const val HTTP_NOT_FOUND = 404
    }
}
