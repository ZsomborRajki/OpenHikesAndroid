package app.openhikes.core.testing.android

import app.openhikes.core.data.StopPlaceSearchSource
import app.openhikes.core.data.TrailDraftRepository
import app.openhikes.core.model.GeoBoundingBox
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.StoredTrailDraft
import app.openhikes.core.model.TrailStopRecent
import app.openhikes.core.model.TrailStopSearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory [TrailDraftRepository] fake for deterministic Trail Maker tests.
 */
class FakeTrailDraftRepository(
    initialDraft: StoredTrailDraft = StoredTrailDraft.Empty,
    initialRecents: List<TrailStopRecent> = emptyList()
) : TrailDraftRepository {
    private var storedDraft: StoredTrailDraft = initialDraft
    private val recentsFlow = MutableStateFlow(initialRecents)

    val currentStoredDraft: StoredTrailDraft
        get() = storedDraft

    var saveCount: Int = 0
        private set

    override val recentStops: Flow<List<TrailStopRecent>> = recentsFlow.asStateFlow()

    override suspend fun loadDraft(): StoredTrailDraft = storedDraft

    override suspend fun saveDraft(draft: StoredTrailDraft) {
        saveCount += 1
        storedDraft = if (draft.isEmpty) StoredTrailDraft.Empty else draft
    }

    override suspend fun clearDraft() {
        storedDraft = StoredTrailDraft.Empty
    }

    override suspend fun recordRecentStop(recent: TrailStopRecent) {
        if (recent.name.isBlank()) return
        recentsFlow.update { current ->
            val filtered = current.filterNot { it.isSamePlaceAs(recent) }
            (listOf(recent) + filtered).take(TrailStopRecent.LIMIT)
        }
    }

    override suspend fun removeRecentStop(id: String) {
        recentsFlow.update { current -> current.filterNot { it.id == id } }
    }
}

/**
 * Deterministic [StopPlaceSearchSource] fake for Trail Maker search & reverse-geocoding tests.
 */
class FakeStopPlaceSearchSource(
    var searchResults: (String) -> Result<List<TrailStopSearchResult>> = { Result.success(emptyList()) },
    var reverseNameProvider: (GeoCoordinate) -> String? = { null },
    var reverseAddressProvider: (GeoCoordinate) -> String? = { null }
) : StopPlaceSearchSource {
    override suspend fun search(query: String, biasBounds: GeoBoundingBox?): Result<List<TrailStopSearchResult>> =
        searchResults(query)

    override suspend fun reverseName(coordinate: GeoCoordinate): String? = reverseNameProvider(coordinate)

    override suspend fun reverseAddress(coordinate: GeoCoordinate): String? = reverseAddressProvider(coordinate)
}
