package app.openhikes.core.network

import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.model.VectorTileArchiveManifest
import java.io.IOException
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Source fetching the OpenHikes vector tile server manifest (`manifest.json`).
 */
interface VectorTileManifestDataSource {
    suspend fun fetchManifest(): VectorTileArchiveManifest
}

@Singleton
internal class HttpVectorTileManifestDataSource @Inject constructor(
    @ManifestOkHttpClient private val okHttpClient: OkHttpClient,
    @TileServerBaseUrl private val serverBaseUrl: String,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : VectorTileManifestDataSource {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetchManifest(): VectorTileArchiveManifest = withContext(ioDispatcher) {
        val url = "${serverBaseUrl.trimEnd('/')}/$MANIFEST_PATH"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (response.code != HttpURLConnection.HTTP_OK) {
                throw IOException("Unexpected manifest status ${response.code} from $url")
            }
            val body = response.body.string()
            json.decodeFromString(VectorTileArchiveManifest.serializer(), body)
        }
    }

    private companion object {
        const val MANIFEST_PATH = "manifest.json"
    }
}
