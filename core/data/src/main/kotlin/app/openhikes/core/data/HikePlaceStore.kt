package app.openhikes.core.data

import androidx.room.withTransaction
import app.openhikes.core.common.BoundedText
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.common.TextBound
import app.openhikes.core.database.OpenHikesDatabase
import app.openhikes.core.database.dao.HikeDao
import app.openhikes.core.database.dao.HikePhotoDao
import app.openhikes.core.database.dao.TrailPointDao
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.TrailPlaceHolding
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.TrailPoint
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Main-safe persistence helper for adding, editing, and removing places on a hike.
 * Enforces iOS OpenHikes invariants: duplicate OSM elements are skipped, OSM places are never
 * renamed or re-categorized, removing a place unfiles its photos back to the hike gallery,
 * and text inputs are grapheme-cluster bounded.
 */
@Suppress("LongParameterList")
internal class HikePlaceStore(
    private val database: OpenHikesDatabase,
    private val hikeDao: HikeDao,
    private val trailPointDao: TrailPointDao,
    private val hikePhotoDao: HikePhotoDao,
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
        val osm = place.osm
        val scopedId = when {
            osm != null -> "${hikeId.value}:osm-${osm.elementType}-${osm.elementId}"
            place.id.isBlank() -> UUID.randomUUID().toString()
            trailPointDao.getById(place.id)?.let { it.hikeId != hikeId.value } == true -> UUID.randomUUID().toString()
            else -> place.id
        }
        val normalized = place.copy(
            id = scopedId,
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
        database.withTransaction {
            hikePhotoDao.unfileFromPlace(hikeId = hikeId.value, placeId = placeId)
            trailPointDao.deleteById(hikeId = hikeId.value, id = placeId)
        }
        Result.success(Unit)
    }
}
