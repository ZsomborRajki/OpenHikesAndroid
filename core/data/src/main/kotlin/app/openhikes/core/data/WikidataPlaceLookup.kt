package app.openhikes.core.data

import app.openhikes.core.model.WikidataFetchResult
import app.openhikes.core.model.WikidataPlace
import app.openhikes.core.model.WikidataPlaceSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory launch-scoped cache for Wikipedia/Wikidata place lookups.
 *
 * Wikipedia prose and Wikimedia Commons thumbnails stay strictly in memory for the current launch
 * (never persisted to Room or disk) so attribution and share-alike terms remain tied to the card
 * displaying them. Complete results and `null` ("nothing to show") are cached up to [CAPACITY];
 * failures and incomplete results (e.g. photo failed beside an article) are re-requested next open.
 */
@Singleton
class WikidataPlaceLookup @Inject constructor(private val source: WikidataPlaceSource) {
    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, CacheEntry>()

    private data class CacheEntry(val place: WikidataPlace?)

    suspend fun cached(item: String): WikidataPlace? = mutex.withLock { cache[item]?.place }

    suspend fun lookup(item: String): WikidataPlace? {
        val existing = mutex.withLock { cache[item] }
        if (existing != null && (existing.place == null || existing.place.isComplete)) {
            return existing.place
        }
        return when (val result = source.place(item)) {
            is WikidataFetchResult.Found -> {
                mutex.withLock {
                    putBounded(item, CacheEntry(result.place))
                }
                result.place
            }
            is WikidataFetchResult.Failed -> existing?.place
        }
    }

    private fun putBounded(item: String, entry: CacheEntry) {
        if (!cache.containsKey(item) && cache.size >= CAPACITY) {
            val oldest = cache.entries.firstOrNull()?.key
            if (oldest != null) {
                cache.remove(oldest)
            }
        }
        cache[item] = entry
    }

    companion object {
        const val CAPACITY = 32
    }
}
