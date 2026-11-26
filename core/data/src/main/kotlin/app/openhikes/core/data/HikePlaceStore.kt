package app.openhikes.core.data

import app.openhikes.core.common.BoundedText
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.common.TextBound
import app.openhikes.core.database.dao.HikeDao
import app.openhikes.core.database.dao.TrailPointDao
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.TrailPlaceHolding
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.TrailPoint
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Main-safe persistence helper for adding, editing, and removing places on a hike.
 * Enforces iOS OpenHikes invariants: duplicate OSM elements are skipped, OSM places are never
 * renamed or re-categorized, and text inputs are grapheme-cluster bounded.
 */
internal class HikePlaceStore(
    private val hikeDao: HikeDao,
    private val trailPointDao: TrailPointDao,
    private val clock: OpenHikesClock,
    private val shouldFailFirstSave: () -> Boolean,
    private val ioDispatcher: CoroutineDispatcher
) {
    suspend fun addPlace(hikeId: HikeId, place: TrailPoint): Result<Boolean> = withContext(ioDispatcher) {
        if (shouldFailFirstSave()) {
            return@withContext Result.failure(IOException("Simulated place save failure"))
        }
        if (hikeDao.getHikeById(hikeId.value) == null) {
            return@withContext Result.failure(NoSuchElementException("Hike not found: ${hikeId.value}"))
        }
        val existing = trailPointDao.getByHikeId(hikeId.value).map { it.toDomain() }
        if (TrailPlaceHolding.isSameElement(place, existing)) {
            return@withContext Result.success(false)
        }
        val nextSortOrder = trailPointDao.maxSortOrderForHike(hikeId.value) + 1
        val normalized = place.copy(
            hikeId = hikeId,
            name = BoundedText.boundedOrEmpty(place.name, TextBound.TITLE),
            note = BoundedText.boundedOrEmpty(place.note, TextBound.NOTES),
            createdAtEpochMillis = place.createdAtEpochMillis.takeIf { it > 0L } ?: clock.now().toEpochMilli()
        )
        trailPointDao.insert(normalized.toEntity(hikeIdValue = hikeId.value, sortOrder = nextSortOrder))
        Result.success(true)
    }

    suspend fun editPlace(
        hikeId: HikeId,
        placeId: String,
        name: String,
        symbol: TrailPlaceSymbol?,
        note: String
    ): Result<Boolean> = withContext(ioDispatcher) {
        if (shouldFailFirstSave()) {
            return@withContext Result.failure(IOException("Simulated place edit failure"))
        }
        val entity = trailPointDao.getById(placeId)
        val domain = entity?.takeIf { it.hikeId == hikeId.value }?.toDomain()
        if (domain == null || !domain.isHikersOwn) {
            return@withContext Result.success(false)
        }
        val updated = trailPointDao.updateDetails(
            id = placeId,
            name = BoundedText.boundedOrEmpty(name, TextBound.TITLE),
            symbol = symbol?.rawValue,
            note = BoundedText.boundedOrEmpty(note, TextBound.NOTES)
        )
        Result.success(updated > 0)
    }

    suspend fun removePlace(hikeId: HikeId, placeId: String): Result<Unit> = withContext(ioDispatcher) {
        if (shouldFailFirstSave()) {
            return@withContext Result.failure(IOException("Simulated place removal failure"))
        }
        trailPointDao.deleteById(hikeId = hikeId.value, id = placeId)
        Result.success(Unit)
    }
}
