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
        if (edited.route.size <= 1) return@withContext RouteEditOutcome.Refused(RouteEditRefusal.NOT_SAVED)
        if (shouldFailFirstSave()) return@withContext RouteEditOutcome.Refused(RouteEditRefusal.NOT_SAVED)

        runOutcomeCatching {
            val loaded = loadVerifiedCurrentRoute(hikeId.value, expectedCurrentRoute)
                ?: return@runOutcomeCatching RouteEditOutcome.Refused(RouteEditRefusal.LINE_CHANGED)
            val (hike, currentRoute) = loaded
            val rawEdit = hike.routeEditJson
            val decoded = decodeRouteEditOrNull(rawEdit)
            if (rawEdit != null && decoded == null) {
                return@runOutcomeCatching RouteEditOutcome.Refused(RouteEditRefusal.NOT_HERE_YET)
            }
            val isRebased = decoded != null && !withContext(defaultDispatcher) { decoded.describes(currentRoute) }
            val baseEdit = when {
                decoded == null -> RouteEdit()
                isRebased -> decoded.rebased
                else -> decoded
            }
            commitRouteEdit(hike, currentRoute, baseEdit, edited)
        }
    }

    private suspend fun commitRouteEdit(
        hike: HikeEntity,
        currentRoute: List<RouteCoordinate>,
        baseEdit: RouteEdit,
        edited: EditedLine
    ): RouteEditOutcome {
        val recordedWalkEntity = hikeWalkDao.getByHikeId(hike.id)
            .firstOrNull { it.endReasonId == TrailWalkEndReason.RECORDED.storedId }
        val needsOriginal = baseEdit.original == null
        val original = baseEdit.original ?: RouteEdit.Original(
            distanceMeters = hike.distanceMeters,
            dateEpochMillis = hike.dateEpochMillis,
            durationSeconds = hike.durationSeconds,
            recordedWalk = recordedWalkEntity?.let { RouteEdit.RecordedWalk.from(it.toDomain()) },
            notOnFoot = baseEdit.notOnFoot
        )
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
        val newStats = withContext(defaultDispatcher) { HikeRouteStatistics.from(newDistance, edited.route) }
        val updatedHike = hike.copy(
            distanceMeters = newDistance,
            durationSeconds = newStats.durationSeconds,
            dateEpochMillis = resolveStartDate(hike.dateEpochMillis, edited.start, original),
            routePointCount = edited.route.size,
            routeEditJson = if (updatedEdit.isEmpty) null else json.encodeToString(updatedEdit)
        )
        val updatedWalk = recordedWalkEntity?.let { updateRecordedWalk(it, edited, original) }
        val newChunks = withContext(defaultDispatcher) { encodeRouteChunks(hike.id, edited.route) }
        val originalChunks = if (needsOriginal) {
            withContext(defaultDispatcher) { encodeOriginalChunks(hike.id, currentRoute) }
        } else {
            null
        }
        database.withTransaction {
            if (originalChunks != null) hikeDao.replaceOriginalRouteChunks(hike.id, originalChunks)
            hikeDao.replaceHikeWithRoute(updatedHike, newChunks)
            if (updatedWalk != null) hikeWalkDao.insert(updatedWalk)
        }
        invalidateLocalElevationCache(hike.id, newStats.elevationGainMeters, newStats.elevationLossMeters)
        return RouteEditOutcome.Saved(edited.route)
    }

    suspend fun revertRouteEdit(hikeId: HikeId, expectedCurrentRoute: List<RouteCoordinate>): RouteEditOutcome =
        withContext(ioDispatcher) {
            if (shouldFailFirstSave()) return@withContext RouteEditOutcome.Refused(RouteEditRefusal.NOT_SAVED)

            runOutcomeCatching {
                val loaded = loadVerifiedCurrentRoute(hikeId.value, expectedCurrentRoute)
                    ?: return@runOutcomeCatching RouteEditOutcome.Refused(RouteEditRefusal.LINE_CHANGED)
                val (hike, currentRoute) = loaded
                val edit = decodeRouteEditOrNull(hike.routeEditJson)
                val original = edit?.original
                    ?: return@runOutcomeCatching RouteEditOutcome.Refused(RouteEditRefusal.NO_ORIGINAL)
                if (!withContext(defaultDispatcher) { edit.describes(currentRoute) }) {
                    return@runOutcomeCatching RouteEditOutcome.Refused(RouteEditRefusal.NO_ORIGINAL)
                }
                val originalChunks = hikeDao.getOriginalRouteChunks(hikeId.value)
                val originalRoute = withContext(defaultDispatcher) {
                    RouteBlobCodec.decodeChunksOrEmpty(originalChunks.sortedBy { it.chunkIndex }.map { it.chunkBlob })
                }
                if (originalRoute.size <= 1) {
                    return@runOutcomeCatching RouteEditOutcome.Refused(RouteEditRefusal.NO_ORIGINAL)
                }
                commitRouteRevert(hike, original, originalRoute)
            }
        }

    private suspend fun commitRouteRevert(
        hike: HikeEntity,
        original: RouteEdit.Original,
        originalRoute: List<RouteCoordinate>
    ): RouteEditOutcome {
        val restoredEdit = RouteEdit(
            notOnFoot = original.notOnFoot?.copy(isChecked = true),
            lineRevision = TrailWalkRecord.routeRevision(originalRoute)
        )
        val restoredStats = withContext(defaultDispatcher) {
            HikeRouteStatistics.from(original.distanceMeters, originalRoute)
        }
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
            original.recordedWalk?.let { savedWalk -> restoreRecordedWalk(walk, savedWalk) }
        }
        val restoredChunks = withContext(defaultDispatcher) { encodeRouteChunks(hike.id, originalRoute) }
        database.withTransaction {
            hikeDao.replaceHikeWithRoute(restoredHike, restoredChunks)
            hikeDao.deleteOriginalRouteChunks(hike.id)
            if (restoredWalk != null) hikeWalkDao.insert(restoredWalk)
        }
        invalidateLocalElevationCache(hike.id, restoredStats.elevationGainMeters, restoredStats.elevationLossMeters)
        return RouteEditOutcome.Saved(originalRoute)
    }

    private suspend fun loadVerifiedCurrentRoute(
        hikeId: String,
        expectedCurrentRoute: List<RouteCoordinate>
    ): Pair<HikeEntity, List<RouteCoordinate>>? {
        val hike = hikeDao.getHikeById(hikeId) ?: return null
        val currentChunks = hikeDao.getRouteChunks(hikeId)
        val currentRoute = withContext(defaultDispatcher) {
            RouteBlobCodec.decodeChunksOrEmpty(currentChunks.sortedBy { it.chunkIndex }.map { it.chunkBlob })
        }
        return if (currentRoute == expectedCurrentRoute) hike to currentRoute else null
    }

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

    private fun resolveStartDate(
        currentDateMillis: Long,
        startClock: EditedLine.ClockEnd,
        original: RouteEdit.Original
    ): Long = when (startClock) {
        EditedLine.ClockEnd.Unchanged -> currentDateMillis
        is EditedLine.ClockEnd.Cut -> startClock.epochMillis
        EditedLine.ClockEnd.Uncut -> original.dateEpochMillis
    }

    private fun updateRecordedWalk(
        walk: HikeWalkEntity,
        edited: EditedLine,
        original: RouteEdit.Original
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
        val activeSeconds = max(0.0, walk.durationSeconds - edited.removedSeconds)
        return walk.copy(
            startedAtEpochMillis = startedAt,
            endedAtEpochMillis = max(startedAt, endedAt),
            distanceMeters = edited.lengthMeters,
            durationSeconds = activeSeconds,
            coveredIntervalsJson = json.encodeToString(listOf(0.0, edited.lengthMeters)),
            furthestDistanceMeters = edited.lengthMeters,
            routeDistanceMeters = edited.lengthMeters,
            routeRevision = edited.revision
        )
    }

    private fun restoreRecordedWalk(walk: HikeWalkEntity, original: RouteEdit.RecordedWalk): HikeWalkEntity = walk.copy(
        startedAtEpochMillis = original.startedAtEpochMillis,
        endedAtEpochMillis = original.endedAtEpochMillis,
        distanceMeters = original.routeDistanceMeters,
        durationSeconds = original.durationSeconds,
        coveredIntervalsJson = json.encodeToString(original.coveredIntervals),
        furthestDistanceMeters = original.furthestDistanceMeters,
        routeDistanceMeters = original.routeDistanceMeters,
        routeRevision = original.routeRevision
    )

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

    private fun encodeOriginalChunks(hikeId: String, route: List<RouteCoordinate>): List<HikeOriginalRouteChunkEntity> =
        RouteBlobCodec.encodeChunked(route).mapIndexed { index, blob ->
            HikeOriginalRouteChunkEntity(hikeId = hikeId, chunkIndex = index, chunkBlob = blob)
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
