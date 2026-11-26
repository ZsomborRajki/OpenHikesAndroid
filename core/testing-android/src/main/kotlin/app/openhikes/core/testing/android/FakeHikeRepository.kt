package app.openhikes.core.testing.android

import app.openhikes.core.common.HikeTitle
import app.openhikes.core.common.HikeTitleEdit
import app.openhikes.core.common.gpx.GpxExporter
import app.openhikes.core.common.gpx.GpxParseResult
import app.openhikes.core.common.gpx.GpxParser
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.model.EditedLine
import app.openhikes.core.model.ExportedGpx
import app.openhikes.core.model.GpxImportFailure
import app.openhikes.core.model.GpxImportResult
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeLocalState
import app.openhikes.core.model.HikeMapRoute
import app.openhikes.core.model.HikeRouteStatistics
import app.openhikes.core.model.HikeSummary
import app.openhikes.core.model.HikeWalk
import app.openhikes.core.model.PreparedRecording
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteEdit
import app.openhikes.core.model.RouteEditOutcome
import app.openhikes.core.model.RouteEditRefusal
import app.openhikes.core.model.RouteEditSources
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.RouteStyleUpdate
import app.openhikes.core.model.RouteTint
import app.openhikes.core.model.TrailPoint
import app.openhikes.core.model.TrailWalkEndReason
import app.openhikes.core.model.TrailWalkRecord
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory [HikeRepository] fake for deterministic ViewModel, Compose screen, and shell tests.
 */
@Suppress("TooManyFunctions", "LargeClass")
class FakeHikeRepository(initialHikes: List<Hike> = emptyList()) : HikeRepository {
    private val hikesState = MutableStateFlow(initialHikes)
    private val localStateMap = MutableStateFlow<Map<HikeId, HikeLocalState>>(emptyMap())
    private val walksState = MutableStateFlow<List<HikeWalk>>(emptyList())
    private val openWalksByHike = MutableStateFlow<Map<HikeId, TrailWalkRecord>>(emptyMap())
    private val uriPayloads = HashMap<String, Pair<String?, ByteArray>>()
    private val originalRoutesByHike = HashMap<HikeId, List<RouteCoordinate>>()
    private val routeEditsByHike = HashMap<HikeId, RouteEdit>()

    var nextImportFailure: GpxImportFailure? = null
    var nextRenameFailure: Throwable? = null
    var nextStyleFailure: Throwable? = null
    var nextRouteEditRefusal: RouteEditRefusal? = null
    var nextDeleteFailure: Throwable? = null
    var nextExportFailure: Throwable? = null
    var nextFinalizeFailure: Throwable? = null
    var nextDrawnTrailSaveFailure: Throwable? = null
    var nextWalkPersistFailure: Throwable? = null
    var nextWalkFinishFailure: Throwable? = null
    var seededFixtureBytes: Pair<String, ByteArray>? = null
    var lastExportedUri: Pair<String, String>? = null
        private set

    override val hikes: Flow<List<HikeSummary>> = hikesState.map { list ->
        val local = localStateMap.value
        list.filterNot { it.isRecording }
            .sortedByDescending { it.dateEpochMillis }
            .map { hike ->
                val state = local[hike.id]
                hike.toSummary(climbMeters = state?.climbMeters, descentMeters = state?.descentMeters)
            }
    }

    fun setLocalStateForTest(localState: HikeLocalState) {
        localStateMap.update { it + (localState.hikeId to localState) }
    }

    override val mapRoutes: Flow<List<HikeMapRoute>> = hikesState.map { list ->
        list.filterNot { it.isRecording }.map { hike ->
            HikeMapRoute(
                hikeId = hike.id,
                route = hike.route,
                tintHex = hike.tintHex,
                routeBorderHex = hike.routeBorderHex,
                routeWidthDp = hike.routeWidthDp,
                linePattern = hike.linePattern
            )
        }
    }

    fun registerUriPayload(uriString: String, bytes: ByteArray, fileName: String? = null) {
        uriPayloads[uriString] = fileName to bytes
    }

    fun seedWalkForTest(walk: HikeWalk) {
        walksState.update { list -> list.filterNot { it.id == walk.id } + walk }
    }

    fun seedOpenWalkForTest(record: TrailWalkRecord) {
        openWalksByHike.update { it + (HikeId(record.hikeId) to record) }
    }

    override fun observeHike(hikeId: HikeId): Flow<Hike?> =
        hikesState.map { list -> list.firstOrNull { it.id == hikeId } }

    override fun observeLocalState(hikeId: HikeId): Flow<HikeLocalState?> = localStateMap.map { map -> map[hikeId] }

    override fun observeWalks(hikeId: HikeId): Flow<List<HikeWalk>> = walksState.map { list ->
        list.filter { it.hikeId == hikeId }.sortedByDescending { it.startedAtEpochMillis }
    }

    override fun observeWalk(walkId: String): Flow<HikeWalk?> =
        walksState.map { list -> list.firstOrNull { it.id == walkId } }

    override suspend fun importGpxBytes(bytes: ByteArray, fileName: String?): GpxImportResult {
        val failure = nextImportFailure?.also { nextImportFailure = null }
        val result = if (failure != null) {
            GpxImportResult.Failure(failure)
        } else {
            when (val parse = GpxParser.parseAll(bytes)) {
                is GpxParseResult.Failure -> GpxImportResult.Failure(parse.reason)
                is GpxParseResult.Success -> {
                    val tracks = parse.value.filter { it.route.size >= 2 }
                    if (tracks.isEmpty()) {
                        GpxImportResult.Failure(GpxImportFailure.TOO_SHORT)
                    } else {
                        saveTracks(tracks, fileName)
                    }
                }
            }
        }
        return result
    }

    private fun saveTracks(tracks: List<app.openhikes.core.model.GpxTrack>, fileName: String?): GpxImportResult {
        val baseIndex = hikesState.value.size
        val imported = tracks.mapIndexed { index, track ->
            val id = HikeId(UUID.randomUUID().toString())
            val title = HikeTitle.imported(track.name, fileName).ifBlank { "Imported Hike" }
            val stats = HikeRouteStatistics.from(track.distanceMeters, track.route)
            val hike = Hike(
                id = id,
                title = title,
                distanceMeters = track.distanceMeters,
                durationSeconds = stats.durationSeconds,
                dateEpochMillis = track.startTimeEpochMillis ?: 0L,
                tintHex = RouteTint.forIndex(baseIndex + index),
                routeWidthDp = HikeSummary.DEFAULT_ROUTE_WIDTH_DP,
                linePattern = RouteLinePattern.Default,
                routeBorderHex = RouteBorder.NONE_HEX,
                trackDescription = track.trackDescription,
                author = track.author,
                keywords = track.keywords,
                route = track.route,
                places = track.places.mapIndexed { idx, wpt ->
                    TrailPoint(
                        id = "${id.value}-wpt-$idx",
                        hikeId = id,
                        coordinate = wpt.coordinate,
                        elevationMeters = wpt.elevationMeters,
                        name = wpt.name,
                        symbol = wpt.symbol,
                        note = wpt.note
                    )
                }
            )
            localStateMap.update { current ->
                current + (
                    id to HikeLocalState(
                        hikeId = id,
                        climbMeters = stats.elevationGainMeters,
                        descentMeters = stats.elevationLossMeters
                    )
                    )
            }
            hike
        }
        hikesState.update { current -> imported + current }
        return GpxImportResult.Success(
            imported.map { hike ->
                val state = localStateMap.value[hike.id]
                hike.toSummary(state?.climbMeters, state?.descentMeters)
            }
        )
    }

    override suspend fun importGpxUri(uriString: String): GpxImportResult {
        val (fileName, bytes) = uriPayloads[uriString]
            ?: return GpxImportResult.Failure(GpxImportFailure.UNREADABLE)
        return importGpxBytes(bytes, fileName)
    }

    override suspend fun exportHike(hikeId: HikeId): Result<ExportedGpx> {
        val failure = nextExportFailure?.also { nextExportFailure = null }
        val hike = if (failure == null) hikesState.value.firstOrNull { it.id == hikeId } else null
        return when {
            failure != null -> Result.failure(failure)
            hike != null -> Result.success(GpxExporter.export(hike))
            else -> Result.failure(NoSuchElementException("Hike not found"))
        }
    }

    override suspend fun exportHikeToUri(hikeId: HikeId, uriString: String): Result<Unit> {
        val exported = exportHike(hikeId).getOrElse { return Result.failure(it) }
        lastExportedUri = uriString to exported.xml
        return Result.success(Unit)
    }

    override suspend fun renameHike(hikeId: HikeId, draftTitle: String): Result<Unit> {
        nextRenameFailure?.let {
            nextRenameFailure = null
            return Result.failure(it)
        }
        var found = false
        hikesState.update { list ->
            list.map { hike ->
                if (hike.id == hikeId) {
                    found = true
                    when (val edit = HikeTitleEdit.of(draftTitle, hike.displayTitle)) {
                        HikeTitleEdit.Unchanged -> hike
                        is HikeTitleEdit.Renamed -> hike.copy(customName = edit.customName)
                    }
                } else {
                    hike
                }
            }
        }
        return if (found) Result.success(Unit) else Result.failure(NoSuchElementException("Hike not found"))
    }

    override suspend fun updateRouteStyle(hikeId: HikeId, style: RouteStyleUpdate): Result<Unit> {
        nextStyleFailure?.let {
            nextStyleFailure = null
            return Result.failure(it)
        }
        var found = false
        hikesState.update { list ->
            list.map { hike ->
                if (hike.id == hikeId) {
                    found = true
                    hike.copy(
                        tintHex = style.tintHex,
                        routeBorderHex = style.routeBorderHex,
                        routeWidthDp = style.clampedWidthDp,
                        linePattern = style.linePattern
                    )
                } else {
                    hike
                }
            }
        }
        return if (found) Result.success(Unit) else Result.failure(NoSuchElementException("Hike not found"))
    }

    override suspend fun loadRouteEditSources(hikeId: HikeId): RouteEditSources? {
        val hike = hikesState.value.firstOrNull { it.id == hikeId } ?: return null
        val edit = routeEditsByHike[hikeId]
        val current = hike.route
        return when {
            edit == null -> RouteEditSources(current = current, saved = current, edit = null)
            !edit.describes(current) -> RouteEditSources(current = current, saved = current, edit = edit.rebased)
            edit.original == null -> RouteEditSources(current = current, saved = current, edit = edit)
            else -> {
                val original = originalRoutesByHike[hikeId].orEmpty()
                RouteEditSources(
                    current = current,
                    saved = if (original.size > 1) original else current,
                    edit = edit,
                    isMissingOriginal = original.size <= 1
                )
            }
        }
    }

    override suspend fun applyRouteEdit(
        hikeId: HikeId,
        expectedCurrentRoute: List<RouteCoordinate>,
        edited: EditedLine
    ): RouteEditOutcome {
        val forcedRefusal = nextRouteEditRefusal?.also { nextRouteEditRefusal = null }
        val hike = hikesState.value.firstOrNull { it.id == hikeId }
        return when {
            forcedRefusal != null -> RouteEditOutcome.Refused(forcedRefusal)
            hike == null || hike.route != expectedCurrentRoute -> RouteEditOutcome.Refused(
                RouteEditRefusal.LINE_CHANGED
            )
            else -> commitFakeRouteEdit(hikeId, hike, edited)
        }
    }

    private fun commitFakeRouteEdit(hikeId: HikeId, hike: Hike, edited: EditedLine): RouteEditOutcome {
        val stored = routeEditsByHike[hikeId]
        val baseEdit = when {
            stored == null -> RouteEdit()
            !stored.describes(hike.route) -> stored.rebased
            else -> stored
        }
        val recordedWalk = walksState.value
            .firstOrNull { it.hikeId == hikeId && it.endReason == TrailWalkEndReason.RECORDED }
        val original = baseEdit.original ?: RouteEdit.Original(
            distanceMeters = hike.distanceMeters,
            dateEpochMillis = hike.dateEpochMillis,
            durationSeconds = hike.durationSeconds,
            recordedWalk = recordedWalk?.let { RouteEdit.RecordedWalk.from(it) },
            notOnFoot = baseEdit.notOnFoot
        ).also { originalRoutesByHike[hikeId] = hike.route }
        routeEditsByHike[hikeId] = buildNextRouteEdit(baseEdit, original, edited)
        applyEditedHikeState(hikeId, hike, original, edited)
        if (recordedWalk != null) {
            applyEditedRecordedWalk(recordedWalk, original, edited)
        }
        return RouteEditOutcome.Saved(edited.route)
    }

    private fun buildNextRouteEdit(baseEdit: RouteEdit, original: RouteEdit.Original, edited: EditedLine): RouteEdit =
        baseEdit.copy(
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

    private fun applyEditedHikeState(hikeId: HikeId, hike: Hike, original: RouteEdit.Original, edited: EditedLine) {
        val newDistance = max(0.0, min(edited.lengthMeters, hike.distanceMeters - edited.removedMeters))
        val newStats = HikeRouteStatistics.from(newDistance, edited.route)
        val updatedDuration = newStats.durationSeconds
            ?: hike.durationSeconds?.let { prior -> max(0.0, prior - edited.removedSeconds).takeIf { it > 0.0 } }
        val newDate = when (val s = edited.start) {
            EditedLine.ClockEnd.Unchanged -> hike.dateEpochMillis
            is EditedLine.ClockEnd.Cut -> s.epochMillis
            EditedLine.ClockEnd.Uncut -> original.dateEpochMillis
        }
        hikesState.update { list ->
            list.map { item ->
                if (item.id == hikeId) {
                    item.copy(
                        distanceMeters = newDistance,
                        durationSeconds = updatedDuration,
                        dateEpochMillis = newDate,
                        route = edited.route
                    )
                } else {
                    item
                }
            }
        }
    }

    private fun applyEditedRecordedWalk(recordedWalk: HikeWalk, original: RouteEdit.Original, edited: EditedLine) {
        val savedWalk = original.recordedWalk
        val startMillis = when (val s = edited.start) {
            EditedLine.ClockEnd.Unchanged -> recordedWalk.startedAtEpochMillis
            is EditedLine.ClockEnd.Cut -> s.epochMillis
            EditedLine.ClockEnd.Uncut -> savedWalk?.startedAtEpochMillis ?: recordedWalk.startedAtEpochMillis
        }
        val endMillis = when (val e = edited.end) {
            EditedLine.ClockEnd.Unchanged -> recordedWalk.endedAtEpochMillis
            is EditedLine.ClockEnd.Cut -> e.epochMillis
            EditedLine.ClockEnd.Uncut -> savedWalk?.endedAtEpochMillis ?: recordedWalk.endedAtEpochMillis
        }
        walksState.update { list ->
            list.map { walk ->
                if (walk.id == recordedWalk.id) {
                    walk.copy(
                        startedAtEpochMillis = startMillis,
                        endedAtEpochMillis = max(startMillis, endMillis),
                        distanceMeters = edited.lengthMeters,
                        durationSeconds = max(0.0, walk.durationSeconds - edited.removedSeconds),
                        coveredIntervals = listOf(0.0, edited.lengthMeters),
                        furthestDistanceMeters = edited.lengthMeters,
                        routeDistanceMeters = edited.lengthMeters,
                        routeRevision = edited.revision
                    )
                } else {
                    walk
                }
            }
        }
    }

    override suspend fun revertRouteEdit(
        hikeId: HikeId,
        expectedCurrentRoute: List<RouteCoordinate>
    ): RouteEditOutcome {
        val forcedRefusal = nextRouteEditRefusal?.also { nextRouteEditRefusal = null }
        val hike = hikesState.value.firstOrNull { it.id == hikeId }
        val original = routeEditsByHike[hikeId]?.original
        val originalRoute = originalRoutesByHike[hikeId]
        return when {
            forcedRefusal != null -> RouteEditOutcome.Refused(forcedRefusal)
            hike == null || hike.route != expectedCurrentRoute -> RouteEditOutcome.Refused(
                RouteEditRefusal.LINE_CHANGED
            )
            original == null || originalRoute == null -> RouteEditOutcome.Refused(RouteEditRefusal.NO_ORIGINAL)
            else -> {
                originalRoutesByHike.remove(hikeId)
                val restoredEdit = RouteEdit(
                    notOnFoot = original.notOnFoot?.copy(isChecked = true),
                    lineRevision = TrailWalkRecord.routeRevision(originalRoute)
                )
                if (restoredEdit.isEmpty) routeEditsByHike.remove(hikeId) else routeEditsByHike[hikeId] = restoredEdit
                restoreRevertedHikeAndWalk(hikeId, original, originalRoute)
                RouteEditOutcome.Saved(originalRoute)
            }
        }
    }

    private fun restoreRevertedHikeAndWalk(
        hikeId: HikeId,
        original: RouteEdit.Original,
        originalRoute: List<RouteCoordinate>
    ) {
        val stats = HikeRouteStatistics.from(original.distanceMeters, originalRoute)
        hikesState.update { list ->
            list.map { item ->
                if (item.id == hikeId) {
                    item.copy(
                        distanceMeters = original.distanceMeters,
                        durationSeconds = original.durationSeconds ?: stats.durationSeconds,
                        dateEpochMillis = original.dateEpochMillis,
                        route = originalRoute
                    )
                } else {
                    item
                }
            }
        }
        original.recordedWalk?.let { savedWalk ->
            walksState.update { list ->
                list.map { walk ->
                    if (walk.hikeId == hikeId && walk.endReason == TrailWalkEndReason.RECORDED) {
                        walk.copy(
                            startedAtEpochMillis = savedWalk.startedAtEpochMillis,
                            endedAtEpochMillis = savedWalk.endedAtEpochMillis,
                            distanceMeters = savedWalk.routeDistanceMeters,
                            durationSeconds = savedWalk.durationSeconds,
                            coveredIntervals = savedWalk.coveredIntervals,
                            furthestDistanceMeters = savedWalk.furthestDistanceMeters,
                            routeDistanceMeters = savedWalk.routeDistanceMeters,
                            routeRevision = savedWalk.routeRevision
                        )
                    } else {
                        walk
                    }
                }
            }
        }
    }

    override suspend fun setAutoFollowEnabled(hikeId: HikeId, enabled: Boolean): Result<Unit> {
        var found = false
        hikesState.update { list ->
            list.map { hike ->
                if (hike.id == hikeId) {
                    found = true
                    hike.copy(autoFollowEnabled = enabled)
                } else {
                    hike
                }
            }
        }
        return if (found) Result.success(Unit) else Result.failure(NoSuchElementException("Hike not found"))
    }

    override suspend fun saveWalkInProgress(hikeId: HikeId, record: TrailWalkRecord?): Result<Unit> {
        val failure = nextWalkPersistFailure?.also { nextWalkPersistFailure = null }
        return if (failure != null) {
            Result.failure(failure)
        } else {
            openWalksByHike.update { map ->
                if (record == null) map - hikeId else map + (hikeId to record)
            }
            Result.success(Unit)
        }
    }

    override suspend fun getOpenWalksInProgress(): Result<List<TrailWalkRecord>> =
        Result.success(openWalksByHike.value.values.toList())

    override suspend fun finishWalk(
        record: TrailWalkRecord,
        endedAtEpochMillis: Long,
        reason: TrailWalkEndReason
    ): Result<HikeWalk?> {
        val failure = nextWalkFinishFailure?.also { nextWalkFinishFailure = null }
        return if (failure != null) {
            Result.failure(failure)
        } else {
            val hikeId = HikeId(record.hikeId)
            val hikeExists = hikesState.value.any { it.id == hikeId }
            val created = if (hikeExists && record.coverage.meetsMinimum) {
                HikeWalk(
                    id = UUID.randomUUID().toString(),
                    hikeId = hikeId,
                    startedAtEpochMillis = record.startedAtEpochMillis,
                    endedAtEpochMillis = maxOf(record.startedAtEpochMillis, endedAtEpochMillis),
                    distanceMeters = record.coverage.coveredMeters,
                    durationSeconds = record.activeSeconds(endedAtEpochMillis),
                    coveredIntervals = record.coverage.intervals,
                    furthestDistanceMeters = record.coverage.furthestDistanceMeters,
                    routeDistanceMeters = record.routeDistanceMeters,
                    endReason = reason,
                    routeRevision = record.routeRevision
                )
            } else {
                null
            }
            if (created != null) {
                walksState.update { list -> list + created }
            }
            openWalksByHike.update { it - hikeId }
            Result.success(created)
        }
    }

    override suspend fun deleteHike(hikeId: HikeId): Result<Unit> {
        nextDeleteFailure?.let {
            nextDeleteFailure = null
            return Result.failure(it)
        }
        val before = hikesState.value.size
        hikesState.update { list -> list.filterNot { it.id == hikeId } }
        localStateMap.update { map -> map - hikeId }
        walksState.update { list -> list.filterNot { it.hikeId == hikeId } }
        openWalksByHike.update { map -> map - hikeId }
        return if (hikesState.value.size < before) {
            Result.success(Unit)
        } else {
            Result.failure(IOException("Hike not found"))
        }
    }

    override suspend fun createOrClaimRecordingDraft(
        hikeId: HikeId,
        startedAtEpochMillis: Long,
        title: String
    ): Result<Hike> {
        val existing = hikesState.value.firstOrNull { it.id == hikeId }
        if (existing != null) {
            if (existing.isRecording) {
                localStateMap.update { map ->
                    val current = map[hikeId] ?: HikeLocalState(hikeId = hikeId)
                    map + (hikeId to current.copy(ownsRecordingDraft = true))
                }
            }
            return Result.success(existing)
        }
        val draft = Hike(
            id = hikeId,
            title = HikeTitle.bounded(title).orEmpty().ifBlank { "Hike" },
            distanceMeters = 0.0,
            dateEpochMillis = startedAtEpochMillis,
            tintHex = RouteTint.forIndex(hikesState.value.size),
            isRecording = true
        )
        hikesState.update { list -> list + draft }
        localStateMap.update { map -> map + (hikeId to HikeLocalState(hikeId = hikeId, ownsRecordingDraft = true)) }
        return Result.success(draft)
    }

    override suspend fun finalizeRecordingDraft(
        hikeId: HikeId,
        title: String,
        customName: String?,
        startedAtEpochMillis: Long,
        endedAtEpochMillis: Long,
        prepared: PreparedRecording
    ): Result<Hike> {
        val injectedFailure = nextFinalizeFailure?.also { nextFinalizeFailure = null }
        val existing = hikesState.value.firstOrNull { it.id == hikeId }
        return when {
            injectedFailure != null -> Result.failure(injectedFailure)
            existing != null && !existing.isRecording -> Result.success(existing)
            else -> Result.success(
                commitFinalizedDraft(hikeId, title, customName, startedAtEpochMillis, prepared, existing)
            )
        }
    }

    private fun commitFinalizedDraft(
        hikeId: HikeId,
        title: String,
        customName: String?,
        startedAtEpochMillis: Long,
        prepared: PreparedRecording,
        existing: Hike?
    ): Hike {
        val finalized = Hike(
            id = hikeId,
            title = HikeTitle.bounded(title).orEmpty().ifBlank { "Hike" },
            customName = HikeTitle.bounded(customName),
            distanceMeters = prepared.distanceMeters,
            durationSeconds = prepared.recordedSeconds,
            dateEpochMillis = startedAtEpochMillis,
            tintHex = existing?.tintHex ?: RouteTint.forIndex(hikesState.value.size),
            routeWidthDp = existing?.routeWidthDp ?: HikeSummary.DEFAULT_ROUTE_WIDTH_DP,
            linePattern = existing?.linePattern ?: RouteLinePattern.Default,
            routeBorderHex = existing?.routeBorderHex ?: RouteBorder.NONE_HEX,
            isRecording = false,
            route = prepared.route,
            places = existing?.places.orEmpty()
        )
        hikesState.update { list -> list.filterNot { it.id == hikeId } + finalized }
        localStateMap.update { map ->
            val current = map[hikeId] ?: HikeLocalState(hikeId = hikeId)
            map + (
                hikeId to current.copy(
                    ownsRecordingDraft = false,
                    climbMeters = prepared.elevationGainMeters,
                    descentMeters = prepared.elevationLossMeters
                )
                )
        }
        return finalized
    }

    override suspend fun deleteRecordingDraft(hikeId: HikeId): Result<Unit> {
        val existing = hikesState.value.firstOrNull { it.id == hikeId }
        if (existing != null && existing.isRecording) {
            hikesState.update { list -> list.filterNot { it.id == hikeId } }
            localStateMap.update { map -> map - hikeId }
        }
        return Result.success(Unit)
    }

    override suspend fun deleteOwnedOrphanedRecordingDrafts(exceptHikeId: HikeId?): Result<Int> {
        val owned = localStateMap.value.filterValues { it.ownsRecordingDraft }.keys
        val toRemove = hikesState.value
            .filter { it.isRecording && it.id != exceptHikeId && owned.contains(it.id) }
            .map { it.id }
            .toSet()
        hikesState.update { list -> list.filterNot { toRemove.contains(it.id) } }
        localStateMap.update { map -> map - toRemove }
        return Result.success(toRemove.size)
    }

    override suspend fun saveDrawnTrail(
        title: String,
        distanceMeters: Double,
        route: List<RouteCoordinate>,
        madeOnEpochMillis: Long
    ): Result<Hike> {
        val failure = nextDrawnTrailSaveFailure?.also { nextDrawnTrailSaveFailure = null }
        return when {
            failure != null -> Result.failure(failure)
            route.size < 2 -> Result.failure(IllegalArgumentException("A drawn trail needs at least two points"))
            else -> Result.success(commitFakeDrawnTrail(title, distanceMeters, route, madeOnEpochMillis))
        }
    }

    private fun commitFakeDrawnTrail(
        title: String,
        distanceMeters: Double,
        route: List<RouteCoordinate>,
        madeOnEpochMillis: Long
    ): Hike {
        val id = HikeId(UUID.randomUUID().toString())
        val stats = HikeRouteStatistics.from(distanceMeters, route)
        val hike = Hike(
            id = id,
            title = HikeTitle.drawn(title, madeOnEpochMillis),
            distanceMeters = distanceMeters,
            durationSeconds = null,
            dateEpochMillis = madeOnEpochMillis,
            tintHex = RouteTint.forIndex(hikesState.value.size),
            routeWidthDp = HikeSummary.DEFAULT_ROUTE_WIDTH_DP,
            linePattern = RouteLinePattern.Default,
            routeBorderHex = RouteBorder.NONE_HEX,
            route = route
        )
        hikesState.update { current -> listOf(hike) + current }
        localStateMap.update { map ->
            map + (
                id to HikeLocalState(
                    hikeId = id,
                    climbMeters = stats.elevationGainMeters,
                    descentMeters = stats.elevationLossMeters
                )
                )
        }
        return hike
    }

    override suspend fun seedFixtureIfConfigured(): HikeId? {
        val (fileName, bytes) = seededFixtureBytes ?: return null
        val result = importGpxBytes(bytes, fileName)
        return (result as? GpxImportResult.Success)?.importedHikes?.firstOrNull()?.id
    }
}
