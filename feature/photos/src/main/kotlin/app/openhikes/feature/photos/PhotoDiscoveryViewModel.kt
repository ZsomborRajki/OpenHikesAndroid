package app.openhikes.feature.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.common.DefaultDispatcher
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.data.PhotoLibraryAccess
import app.openhikes.core.data.PhotoLibrarySource
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikePhotoSearchPlan
import app.openhikes.core.model.HikeWalk
import app.openhikes.core.model.LibraryPhotoMatch
import app.openhikes.core.model.PhotoLibraryAsset
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestrates *Find Photos of This Hike*: verifies route/walk time availability before requesting
 * library permissions, queries merged search windows off-main, preserves selection across rotations
 * and live route-fingerprint refreshes, and imports selected photos sequentially.
 */
@Suppress("TooManyFunctions")
@HiltViewModel(assistedFactory = PhotoDiscoveryViewModel.Factory::class)
class PhotoDiscoveryViewModel @AssistedInject constructor(
    @Assisted rawHikeId: String,
    private val hikeRepository: HikeRepository,
    private val photoLibrarySource: PhotoLibrarySource,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(rawHikeId: String): PhotoDiscoveryViewModel
    }

    val hikeId: HikeId = HikeId(rawHikeId)

    private val stateFlow = MutableStateFlow(PhotoDiscoveryUiState())
    private val tokenGenerator = AtomicLong(0L)
    private var searchJob: Job? = null
    private var importJob: Job? = null
    private var activeFingerprint: Long? = null
    private var lastObservedAccess: PhotoLibraryAccess? = null
    private var hasPromptedPermissions = false
    private var untickedIds: Set<String> = emptySet()

    val uiState: StateFlow<PhotoDiscoveryUiState> = stateFlow.asStateFlow()

    init {
        startSearch(promptIfDenied = true)
        observeLiveFingerprintChanges()
    }

    fun onAction(action: PhotoDiscoveryAction) {
        when (action) {
            is PhotoDiscoveryAction.ToggleSelection -> toggleSelection(action.assetId)
            PhotoDiscoveryAction.ToggleSelectAll -> toggleSelectAll()
            PhotoDiscoveryAction.AddSelected -> importSelected()
            PhotoDiscoveryAction.RetrySearch -> startSearch(promptIfDenied = true)
            PhotoDiscoveryAction.SelectMorePhotos ->
                stateFlow.update { it.copy(shouldRequestLimitedMoreToken = tokenGenerator.incrementAndGet()) }
            PhotoDiscoveryAction.PermissionResultReceived -> startSearch(promptIfDenied = false)
            PhotoDiscoveryAction.LifecycleResumed -> onLifecycleResumed()
            PhotoDiscoveryAction.ConsumePermissionRequest ->
                stateFlow.update { it.copy(shouldRequestPermissionsToken = 0L) }
            PhotoDiscoveryAction.ConsumeLimitedMoreRequest ->
                stateFlow.update { it.copy(shouldRequestLimitedMoreToken = 0L) }
        }
    }

    suspend fun loadDiscoveryThumbnail(localIdentifier: String): ByteArray? =
        photoLibrarySource.readThumbnailBytes(localIdentifier)

    override fun onCleared() {
        searchJob?.cancel()
        importJob?.cancel()
    }

    private fun observeLiveFingerprintChanges() {
        viewModelScope.launch {
            combine(
                hikeRepository.observeHike(hikeId),
                hikeRepository.observeWalks(hikeId)
            ) { hike, walks -> hike to walks }.collect { (hike, walks) ->
                val expected = activeFingerprint ?: return@collect
                if (hike == null) {
                    stateFlow.update { it.copy(shouldClose = true) }
                    return@collect
                }
                if (stateFlow.value.phase is PhotoDiscoveryPhase.Importing) return@collect
                val updatedFingerprint = withContext(defaultDispatcher) {
                    HikePhotoSearchPlan.fingerprint(hike.route, walks)
                }
                if (updatedFingerprint != expected) {
                    captureUntickedIds()
                    startSearch(promptIfDenied = false)
                }
            }
        }
    }

    private fun onLifecycleResumed() {
        val phase = stateFlow.value.phase
        if (phase is PhotoDiscoveryPhase.Searching ||
            phase is PhotoDiscoveryPhase.Importing ||
            phase is PhotoDiscoveryPhase.Unsupported
        ) {
            return
        }
        viewModelScope.launch {
            val currentAccess = photoLibrarySource.currentAccess()
            if (currentAccess != lastObservedAccess) {
                captureUntickedIds()
                startSearch(promptIfDenied = false)
            }
        }
    }

    private fun startSearch(promptIfDenied: Boolean) {
        searchJob?.cancel()
        stateFlow.update { it.copy(phase = PhotoDiscoveryPhase.Searching) }
        searchJob = viewModelScope.launch {
            runSearch(promptIfDenied = promptIfDenied)
        }
    }

    private suspend fun runSearch(promptIfDenied: Boolean) {
        val loaded = loadHikeAndWalks()
        when (loaded) {
            HikeLoadOutcome.Missing -> stateFlow.update { it.copy(shouldClose = true) }
            HikeLoadOutcome.ReadFailed -> stateFlow.update { it.copy(phase = PhotoDiscoveryPhase.ReadFailed) }
            is HikeLoadOutcome.Ready -> executePlanSearch(loaded.hike, loaded.walks, promptIfDenied)
        }
    }

    private suspend fun executePlanSearch(hike: Hike, walks: List<HikeWalk>, promptIfDenied: Boolean) {
        val plan = withContext(defaultDispatcher) { HikePhotoSearchPlan.build(hike.route, walks) }
        activeFingerprint = plan.fingerprint
        if (plan.isEmpty) {
            stateFlow.update { it.copy(phase = PhotoDiscoveryPhase.Unsupported) }
            return
        }
        val access = photoLibrarySource.currentAccess()
        lastObservedAccess = access
        when (access) {
            PhotoLibraryAccess.RESTRICTED ->
                stateFlow.update { it.copy(phase = PhotoDiscoveryPhase.AccessRestricted) }
            PhotoLibraryAccess.DENIED -> {
                if (promptIfDenied && !hasPromptedPermissions) {
                    hasPromptedPermissions = true
                    stateFlow.update {
                        it.copy(
                            phase = PhotoDiscoveryPhase.Searching,
                            shouldRequestPermissionsToken = tokenGenerator.incrementAndGet()
                        )
                    }
                } else {
                    stateFlow.update { it.copy(phase = PhotoDiscoveryPhase.AccessDenied) }
                }
            }
            PhotoLibraryAccess.GRANTED,
            PhotoLibraryAccess.LIMITED -> queryAndMatchLibrary(hike, plan)
        }
    }

    private suspend fun queryAndMatchLibrary(hike: Hike, plan: HikePhotoSearchPlan) {
        val uniqueAssets = LinkedHashMap<String, PhotoLibraryAsset>()
        for (window in plan.searchWindows) {
            for (asset in photoLibrarySource.queryAssets(window)) {
                uniqueAssets.putIfAbsent(asset.localIdentifier, asset)
            }
        }
        val postQueryAccess = photoLibrarySource.currentAccess()
        lastObservedAccess = postQueryAccess
        when (postQueryAccess) {
            PhotoLibraryAccess.DENIED -> {
                stateFlow.update { it.copy(phase = PhotoDiscoveryPhase.AccessDenied) }
            }
            PhotoLibraryAccess.RESTRICTED -> {
                stateFlow.update { it.copy(phase = PhotoDiscoveryPhase.AccessRestricted) }
            }
            PhotoLibraryAccess.GRANTED,
            PhotoLibraryAccess.LIMITED -> {
                val matches = withContext(defaultDispatcher) {
                    plan.matches(
                        assets = uniqueAssets.values.toList(),
                        alreadyImported = hike.importedPhotoAssetIdentifiers
                    )
                }
                val latest = loadHikeAndWalks()
                val latestFingerprint = (latest as? HikeLoadOutcome.Ready)?.let {
                    withContext(defaultDispatcher) { HikePhotoSearchPlan.fingerprint(it.hike.route, it.walks) }
                }
                if (latestFingerprint != null && latestFingerprint != plan.fingerprint) {
                    executePlanSearch(latest.hike, latest.walks, promptIfDenied = false)
                } else {
                    publishMatches(matches, isLimited = postQueryAccess == PhotoLibraryAccess.LIMITED)
                }
            }
        }
    }

    private fun publishMatches(matches: List<LibraryPhotoMatch>, isLimited: Boolean) {
        if (matches.isEmpty()) {
            stateFlow.update { it.copy(phase = PhotoDiscoveryPhase.Empty(isLimitedAccess = isLimited)) }
        } else {
            val selected = matches.map { it.id }.filterNot { it in untickedIds }.toSet()
            stateFlow.update {
                it.copy(
                    phase = PhotoDiscoveryPhase.Results(
                        matches = matches,
                        selectedIds = selected
                    )
                )
            }
        }
    }

    private fun toggleSelection(assetId: String) {
        val current = stateFlow.value.phase as? PhotoDiscoveryPhase.Results ?: return
        val next = if (assetId in current.selectedIds) {
            current.selectedIds - assetId
        } else {
            current.selectedIds + assetId
        }
        stateFlow.update { it.copy(phase = current.copy(selectedIds = next)) }
    }

    private fun toggleSelectAll() {
        val current = stateFlow.value.phase as? PhotoDiscoveryPhase.Results ?: return
        val next = if (current.allSelected) emptySet() else current.matches.mapTo(HashSet()) { it.id }
        stateFlow.update { it.copy(phase = current.copy(selectedIds = next)) }
    }

    private fun importSelected() {
        val current = stateFlow.value.phase as? PhotoDiscoveryPhase.Results
        val expectedFingerprint = activeFingerprint
        val targets = current?.matches?.filter { it.id in current.selectedIds }.orEmpty()
        if (current != null && expectedFingerprint != null && targets.isNotEmpty()) {
            importJob?.cancel()
            importJob = viewModelScope.launch {
                executeSequentialImport(
                    initialMatches = current.matches,
                    targets = targets,
                    expectedFingerprint = expectedFingerprint
                )
            }
        }
    }

    private suspend fun executeSequentialImport(
        initialMatches: List<LibraryPhotoMatch>,
        targets: List<LibraryPhotoMatch>,
        expectedFingerprint: Long
    ) {
        val importedIds = HashSet<String>()
        val failedIds = HashSet<String>()
        var invalidated = false

        targets.forEachIndexed { index, match ->
            stateFlow.update { it.copy(phase = PhotoDiscoveryPhase.Importing(index, targets.size)) }
            val outcome = importSingleMatch(match, expectedFingerprint)
            when (outcome) {
                SingleImportOutcome.IMPORTED -> importedIds.add(match.id)
                SingleImportOutcome.FAILED -> failedIds.add(match.id)
                SingleImportOutcome.ROUTE_CHANGED -> invalidated = true
            }
            if (invalidated) {
                val remaining = initialMatches.filterNot { it.id in importedIds }
                untickedIds = remaining.map { it.id }.filterNot { it in failedIds }.toSet()
                startSearch(promptIfDenied = false)
                return
            }
        }
        val remaining = initialMatches.filterNot { it.id in importedIds }
        if (importedIds.isNotEmpty() && remaining.isEmpty()) {
            stateFlow.update { it.copy(shouldClose = true) }
        } else if (remaining.isEmpty()) {
            val isLimited = photoLibrarySource.currentAccess() == PhotoLibraryAccess.LIMITED
            stateFlow.update { it.copy(phase = PhotoDiscoveryPhase.Empty(isLimitedAccess = isLimited)) }
        } else {
            val keepSelected = remaining.map { it.id }.filter { it in failedIds }.toSet()
            stateFlow.update {
                it.copy(
                    phase = PhotoDiscoveryPhase.Results(
                        matches = remaining,
                        selectedIds = keepSelected,
                        importFailed = failedIds.isNotEmpty()
                    )
                )
            }
        }
    }

    private suspend fun importSingleMatch(match: LibraryPhotoMatch, expectedFingerprint: Long): SingleImportOutcome {
        val bytes = photoLibrarySource.readImageBytes(match.asset.localIdentifier)
        return if (bytes == null) {
            SingleImportOutcome.FAILED
        } else {
            hikeRepository.addDiscoveredPhoto(
                hikeId = hikeId,
                bytes = bytes,
                match = match,
                expectedPlanFingerprint = expectedFingerprint
            ).fold(
                onSuccess = { saved ->
                    if (saved == null) SingleImportOutcome.ROUTE_CHANGED else SingleImportOutcome.IMPORTED
                },
                onFailure = { SingleImportOutcome.FAILED }
            )
        }
    }

    private suspend fun loadHikeAndWalks(): HikeLoadOutcome = try {
        val hike = hikeRepository.observeHike(hikeId).first()
        if (hike == null) {
            HikeLoadOutcome.Missing
        } else {
            val walks = hikeRepository.observeWalks(hikeId).first()
            HikeLoadOutcome.Ready(hike, walks)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        HikeLoadOutcome.ReadFailed
    } catch (_: IllegalStateException) {
        HikeLoadOutcome.ReadFailed
    }

    private fun captureUntickedIds() {
        val current = stateFlow.value.phase as? PhotoDiscoveryPhase.Results ?: return
        untickedIds = current.matches.map { it.id }.filterNot { it in current.selectedIds }.toSet()
    }

    private sealed interface HikeLoadOutcome {
        data object Missing : HikeLoadOutcome

        data object ReadFailed : HikeLoadOutcome

        data class Ready(val hike: Hike, val walks: List<HikeWalk>) : HikeLoadOutcome
    }

    private enum class SingleImportOutcome {
        IMPORTED,
        FAILED,
        ROUTE_CHANGED
    }
}
