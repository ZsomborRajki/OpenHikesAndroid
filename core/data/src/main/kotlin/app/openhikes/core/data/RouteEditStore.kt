package app.openhikes.core.data

import android.database.SQLException
import androidx.room.withTransaction
import app.openhikes.core.database.OpenHikesDatabase
import app.openhikes.core.database.RouteBlobCodec
import app.openhikes.core.database.dao.HikeDao
import app.openhikes.core.database.dao.HikeLocalStateDao
import app.openhikes.core.database.dao.HikeWalkDao
import app.openhikes.core.database.entity.HikeEntity
import app.openhikes.core.database.entity.HikeOriginalRouteChunkEntity
import app.openhikes.core.database.entity.HikeRouteChunkEntity
import app.openhikes.core.database.entity.HikeWalkEntity
import app.openhikes.core.model.EditedLine
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeRouteStatistics
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteEdit
import app.openhikes.core.model.RouteEditOutcome
import app.openhikes.core.model.RouteEditRefusal
import app.openhikes.core.model.RouteEditSources
import app.openhikes.core.model.TrailWalkEndReason
import app.openhikes.core.model.TrailWalkRecord
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Owns atomic route-edit persistence and revert against the portable hike database while
 * keeping original route geometry isolated in `hike_original_route_chunks` and invalidating
 * device-local cached climb/descent metrics.
 */
@Suppress("LongParameterList")
internal class RouteEditStore(
    private val database: OpenHikesDatabase,
    private val hikeDao: HikeDao,
    private val hikeWalkDao: HikeWalkDao,
    private val hikeLocalStateDao: HikeLocalStateDao,
    private val localStateRepairer: HikeLocalStateRepairer,
    private val shouldFailFirstSave: () -> Boolean,
    private val ioDispatcher: CoroutineDispatcher,
    private val defaultDispatcher: CoroutineDispatcher
) {
    private val json = Json {
        ignoreUnknownKeys = true
        allowSpecialFloatingPointValues = true
    }

    suspend fun loadRouteEditSources(hikeId: HikeId): RouteEditSources? = withContext(ioDispatcher) {
        val hike = hikeDao.getHikeById(hikeId.value) ?: return@withContext null
        val routeChunks = hikeDao.getRouteChunks(hikeId.value)
        val current = withContext(defaultDispatcher) {
            RouteBlobCodec.decodeChunksOrEmpty(routeChunks.sortedBy { it.chunkIndex }.map { it.chunkBlob })
        }
        val rawEdit = hike.routeEditJson
        val decodedEdit = decodeRouteEditOrNull(rawEdit)
        when {
            rawEdit != null && decodedEdit == null ->
                RouteEditSources(current = current, saved = current, edit = null, isUnreadable = true)
            decodedEdit == null -> RouteEditSources(current = current, saved = current, edit = null)
            !withContext(defaultDispatcher) { decodedEdit.describes(current) } ->
                RouteEditSources(current = current, saved = current, edit = decodedEdit.rebased)
            decodedEdit.original == null ->
                RouteEditSources(current = current, saved = current, edit = decodedEdit)
            else -> loadOriginalSources(hikeId.value, current, decodedEdit)
        }
    }

    suspend fun applyRouteEdit(
        hikeId: HikeId,
        expectedCurrentRoute: List<RouteCoordinate>,
        edited: EditedLine
    ): RouteEditOutcome = withContext(ioDispatcher) {
        if (edited.route.size <= 1 || shouldFailFirstSave()) {
            return@withContext RouteEditOutcome.Refused(RouteEditRefusal.NOT_SAVED)
        }

        val (newStats, newChunks) = withContext(defaultDispatcher) {
            HikeRouteStatistics.from(edited.lengthMeters, edited.route) to encodeRouteChunks(hikeId.value, edited.route)
        }
        runOutcomeCatching {
            val outcome = database.withTransaction {
                commitRouteEditInTransaction(
                    hikeId = hikeId.value,
                    expectedCurrentRoute = expectedCurrentRoute,
                    edited = edited,
                    newStats = newStats,
                    newChunks = newChunks
                )
            }
            if (outcome is RouteEditOutcome.Saved) {
                invalidateLocalElevationCache(hikeId.value, newStats.elevationGainMeters, newStats.elevationLossMeters)
            }
            outcome
        }
    }

    private suspend fun commitRouteEditInTransaction(
        hikeId: String,
        expectedCurrentRoute: List<RouteCoordinate>,
        edited: EditedLine,
        newStats: HikeRouteStatistics,
        newChunks: List<HikeRouteChunkEntity>
    ): RouteEditOutcome {
        val hike = hikeDao.getHikeById(hikeId)
        val currentChunks = if (hike != null) hikeDao.getRouteChunks(hikeId) else emptyList()
        val currentRoute = if (hike != null) decodeSortedChunks(currentChunks) else emptyList()
        if (hike == null || currentRoute != expectedCurrentRoute) {
            return RouteEditOutcome.Refused(RouteEditRefusal.LINE_CHANGED)
        }
        val rawEdit = hike.routeEditJson
        val decoded = decodeRouteEditOrNull(rawEdit)
        return if (rawEdit != null && decoded == null) {
            RouteEditOutcome.Refused(RouteEditRefusal.NOT_HERE_YET)
        } else {
            writeEditedHikeInTransaction(hike, currentRoute, currentChunks, decoded, edited, newStats, newChunks)
        }
    }

    @Suppress("LongParameterList")
    private suspend fun writeEditedHikeInTransaction(
        hike: HikeEntity,
        currentRoute: List<RouteCoordinate>,
        currentChunks: List<HikeRouteChunkEntity>,
        decoded: RouteEdit?,
        edited: EditedLine,
        newStats: HikeRouteStatistics,
        newChunks: List<HikeRouteChunkEntity>
    ): RouteEditOutcome {
        val baseEdit = when {
            decoded == null -> RouteEdit()
            !decoded.describes(currentRoute) -> decoded.rebased
            else -> decoded
        }
        val recordedWalkEntity = hikeWalkDao.getByHikeId(hike.id)
            .firstOrNull { it.endReasonId == TrailWalkEndReason.RECORDED.storedId }
        val original = baseEdit.original ?: RouteEdit.Original(
            distanceMeters = hike.distanceMeters,
            dateEpochMillis = hike.dateEpochMillis,
            durationSeconds = hike.durationSeconds,
            recordedWalk = recordedWalkEntity?.let { RouteEdit.RecordedWalk.from(it.toDomain()) },
            notOnFoot = baseEdit.notOnFoot
        )
        val updatedHike = RouteEditEntityMapper.updatedHike(hike, baseEdit, original, edited, newStats, json)
        val updatedWalk = recordedWalkEntity?.let {
            RouteEditEntityMapper.updateRecordedWalk(it, edited, original, json)
        }
        if (baseEdit.original == null) {
            hikeDao.replaceOriginalRouteChunks(
                hike.id,
                RouteEditEntityMapper.copyAsOriginalChunks(hike.id, currentChunks)
            )
        }
        val updated = hikeDao.updateExistingHikeWithRoute(updatedHike, newChunks)
        if (updated && updatedWalk != null) hikeWalkDao.insert(updatedWalk)
        return if (updated) {
            RouteEditOutcome.Saved(edited.route)
        } else {
            RouteEditOutcome.Refused(RouteEditRefusal.LINE_CHANGED)
        }
    }

    suspend fun revertRouteEdit(hikeId: HikeId, expectedCurrentRoute: List<RouteCoordinate>): RouteEditOutcome =
        withContext(ioDispatcher) {
            if (shouldFailFirstSave()) return@withContext RouteEditOutcome.Refused(RouteEditRefusal.NOT_SAVED)

            runOutcomeCatching {
                val (outcome, restoredStats) = database.withTransaction {
                    commitRouteRevertInTransaction(hikeId.value, expectedCurrentRoute)
                }
                if (outcome is RouteEditOutcome.Saved && restoredStats != null) {
                    invalidateLocalElevationCache(
                        hikeId.value,
                        restoredStats.elevationGainMeters,
                        restoredStats.elevationLossMeters
                    )
                }
                outcome
            }
        }

    private suspend fun commitRouteRevertInTransaction(
        hikeId: String,
        expectedCurrentRoute: List<RouteCoordinate>
    ): Pair<RouteEditOutcome, HikeRouteStatistics?> {
        val hike = hikeDao.getHikeById(hikeId)
        val currentRoute = if (hike != null) decodeSortedChunks(hikeDao.getRouteChunks(hikeId)) else emptyList()
        if (hike == null || currentRoute != expectedCurrentRoute) {
            return RouteEditOutcome.Refused(RouteEditRefusal.LINE_CHANGED) to null
        }
        val edit = decodeRouteEditOrNull(hike.routeEditJson)
        val original = edit?.original?.takeIf { edit.describes(currentRoute) }
        val originalChunks = if (original != null) hikeDao.getOriginalRouteChunks(hikeId) else emptyList()
        val originalRoute = if (original != null) decodeOriginalChunks(originalChunks) else emptyList()
        return if (original == null || originalRoute.size <= 1) {
            RouteEditOutcome.Refused(RouteEditRefusal.NO_ORIGINAL) to null
        } else {
            writeRevertedHikeInTransaction(hike, original, originalRoute, originalChunks)
        }
    }

    private suspend fun writeRevertedHikeInTransaction(
        hike: HikeEntity,
        original: RouteEdit.Original,
        originalRoute: List<RouteCoordinate>,
        originalChunks: List<HikeOriginalRouteChunkEntity>
    ): Pair<RouteEditOutcome, HikeRouteStatistics?> {
        val restoredEdit = RouteEdit(
            notOnFoot = original.notOnFoot?.copy(isChecked = true),
            lineRevision = TrailWalkRecord.routeRevision(originalRoute)
        )
        val restoredStats = HikeRouteStatistics.from(original.distanceMeters, originalRoute)
        val restoredHike = hike.copy(
            distanceMeters = original.distanceMeters,
            durationSeconds = original.durationSeconds ?: restoredStats.durationSeconds,
            dateEpochMillis = original.dateEpochMillis,
            routePointCount = originalRoute.size,
            routeEditJson = if (restoredEdit.isEmpty) null else json.encodeToString(restoredEdit)
        )
        val recordedWalkEntity = hikeWalkDao.getByHikeId(hike.id)
            .firstOrNull { it.endReasonId == TrailWalkEndReason.RECORDED.storedId }
        val restoredWalk = recordedWalkEntity?.let { walk ->
            original.recordedWalk?.let { savedWalk -> RouteEditEntityMapper.restoreRecordedWalk(walk, savedWalk, json) }
        }
        val restoredChunks = originalChunks.map { chunk ->
            HikeRouteChunkEntity(hikeId = hike.id, chunkIndex = chunk.chunkIndex, chunkBlob = chunk.chunkBlob)
        }
        val updated = hikeDao.updateExistingHikeWithRoute(restoredHike, restoredChunks)
        if (updated) {
            hikeDao.deleteOriginalRouteChunks(hike.id)
            if (restoredWalk != null) hikeWalkDao.insert(restoredWalk)
        }
        return if (updated) {
            RouteEditOutcome.Saved(originalRoute) to restoredStats
        } else {
            RouteEditOutcome.Refused(RouteEditRefusal.LINE_CHANGED) to null
        }
    }

    private fun decodeSortedChunks(chunks: List<HikeRouteChunkEntity>): List<RouteCoordinate> =
        RouteBlobCodec.decodeChunksOrEmpty(chunks.sortedBy { it.chunkIndex }.map { it.chunkBlob })

    private fun decodeOriginalChunks(chunks: List<HikeOriginalRouteChunkEntity>): List<RouteCoordinate> =
        RouteBlobCodec.decodeChunksOrEmpty(chunks.sortedBy { it.chunkIndex }.map { it.chunkBlob })

    private suspend fun loadOriginalSources(
        hikeId: String,
        current: List<RouteCoordinate>,
        edit: RouteEdit
    ): RouteEditSources {
        val originalChunks = hikeDao.getOriginalRouteChunks(hikeId)
        val original = withContext(defaultDispatcher) {
            RouteBlobCodec.decodeChunksOrEmpty(originalChunks.sortedBy { it.chunkIndex }.map { it.chunkBlob })
        }
        val hasOriginal = original.size > 1
        return RouteEditSources(
            current = current,
            saved = if (hasOriginal) original else current,
            edit = edit,
            isMissingOriginal = !hasOriginal
        )
    }

    private suspend fun invalidateLocalElevationCache(hikeId: String, climbMeters: Double?, descentMeters: Double?) {
        val existing = hikeLocalStateDao.getByHikeId(hikeId) ?: return
        localStateRepairer.upsertBestEffort(
            existing.copy(
                climbMeters = climbMeters,
                descentMeters = descentMeters,
                elevationRouteRevision = null
            )
        )
    }

    private fun encodeRouteChunks(hikeId: String, route: List<RouteCoordinate>): List<HikeRouteChunkEntity> =
        RouteBlobCodec.encodeChunked(route).mapIndexed { index, blob ->
            HikeRouteChunkEntity(hikeId = hikeId, chunkIndex = index, chunkBlob = blob)
        }

    private fun decodeRouteEditOrNull(raw: String?): RouteEdit? =
        raw?.let { runCatching { json.decodeFromString<RouteEdit>(it) }.getOrNull() }

    private suspend fun runOutcomeCatching(block: suspend () -> RouteEditOutcome): RouteEditOutcome = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SQLException) {
        RouteEditOutcome.Refused(RouteEditRefusal.NOT_SAVED)
    } catch (_: IOException) {
        RouteEditOutcome.Refused(RouteEditRefusal.NOT_SAVED)
    } catch (_: IllegalStateException) {
        RouteEditOutcome.Refused(RouteEditRefusal.NOT_SAVED)
    }
}

private object RouteEditEntityMapper {
    @Suppress("LongParameterList")
    fun updatedHike(
        hike: HikeEntity,
        baseEdit: RouteEdit,
        original: RouteEdit.Original,
        edited: EditedLine,
        newStats: HikeRouteStatistics,
        json: Json
    ): HikeEntity {
        val updatedEdit = baseEdit.copy(
            original = original,
            trimStartMeters = edited.cuts.range?.start,
            trimEndMeters = edited.cuts.range?.endInclusive,
            trimEndPauses = if (edited.cuts.range == null || edited.cuts.takingOff.isEmpty) {
                null
            } else {
                edited.cuts.takingOff.rawValue
            },
            steps = edited.steps,
            notOnFoot = edited.notOnFoot,
            lineRevision = edited.revision
        )
        val newDistance = max(0.0, min(edited.lengthMeters, hike.distanceMeters - edited.removedMeters))
        val updatedDuration = newStats.durationSeconds
            ?: hike.durationSeconds?.let { prior -> max(0.0, prior - edited.removedSeconds).takeIf { it > 0.0 } }
        return hike.copy(
            distanceMeters = newDistance,
            durationSeconds = updatedDuration,
            dateEpochMillis = resolveStartDate(hike.dateEpochMillis, edited.start, original),
            routePointCount = edited.route.size,
            routeEditJson = if (updatedEdit.isEmpty) null else json.encodeToString(updatedEdit)
        )
    }

    fun copyAsOriginalChunks(hikeId: String, chunks: List<HikeRouteChunkEntity>): List<HikeOriginalRouteChunkEntity> =
        chunks.map { chunk ->
            HikeOriginalRouteChunkEntity(hikeId = hikeId, chunkIndex = chunk.chunkIndex, chunkBlob = chunk.chunkBlob)
        }

    fun updateRecordedWalk(
        walk: HikeWalkEntity,
        edited: EditedLine,
        original: RouteEdit.Original,
        json: Json
    ): HikeWalkEntity {
        val savedWalk = original.recordedWalk
        val startedAt = when (val startClock = edited.start) {
            EditedLine.ClockEnd.Unchanged -> walk.startedAtEpochMillis
            is EditedLine.ClockEnd.Cut -> startClock.epochMillis
            EditedLine.ClockEnd.Uncut -> savedWalk?.startedAtEpochMillis ?: walk.startedAtEpochMillis
        }
        val endedAt = when (val endClock = edited.end) {
            EditedLine.ClockEnd.Unchanged -> walk.endedAtEpochMillis
            is EditedLine.ClockEnd.Cut -> endClock.epochMillis
            EditedLine.ClockEnd.Uncut -> savedWalk?.endedAtEpochMillis ?: walk.endedAtEpochMillis
        }
        return walk.copy(
            startedAtEpochMillis = startedAt,
            endedAtEpochMillis = max(startedAt, endedAt),
            distanceMeters = edited.lengthMeters,
            durationSeconds = max(0.0, walk.durationSeconds - edited.removedSeconds),
            coveredIntervalsJson = json.encodeToString(listOf(0.0, edited.lengthMeters)),
            furthestDistanceMeters = edited.lengthMeters,
            routeDistanceMeters = edited.lengthMeters,
            routeRevision = edited.revision
        )
    }

    fun restoreRecordedWalk(walk: HikeWalkEntity, original: RouteEdit.RecordedWalk, json: Json): HikeWalkEntity =
        walk.copy(
            startedAtEpochMillis = original.startedAtEpochMillis,
            endedAtEpochMillis = original.endedAtEpochMillis,
            distanceMeters = original.routeDistanceMeters,
            durationSeconds = original.durationSeconds,
            coveredIntervalsJson = json.encodeToString(original.coveredIntervals),
            furthestDistanceMeters = original.furthestDistanceMeters,
            routeDistanceMeters = original.routeDistanceMeters,
            routeRevision = original.routeRevision
        )

    private fun resolveStartDate(
        currentDateMillis: Long,
        startClock: EditedLine.ClockEnd,
        original: RouteEdit.Original
    ): Long = when (startClock) {
        EditedLine.ClockEnd.Unchanged -> currentDateMillis
        is EditedLine.ClockEnd.Cut -> startClock.epochMillis
        EditedLine.ClockEnd.Uncut -> original.dateEpochMillis
    }
}
