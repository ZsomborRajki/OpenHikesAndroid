package app.openhikes.core.data

import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.common.offline.OfflineCountryStore
import app.openhikes.core.model.OfflineCountriesState
import app.openhikes.core.model.OfflineCountry
import app.openhikes.core.model.OfflineCountryDownload
import app.openhikes.core.model.OfflineCountryFile
import app.openhikes.core.model.OfflineDownloadFailure
import app.openhikes.core.model.OfflineDownloadPhase
import app.openhikes.core.model.OfflineFileRole
import app.openhikes.core.model.OfflineHoldingsState
import app.openhikes.core.model.OfflineListPhase
import app.openhikes.core.model.TileNetworkDecision
import app.openhikes.core.model.TileNetworkDenialReason
import app.openhikes.core.model.TileNetworkPurpose
import app.openhikes.core.model.UserSettings
import app.openhikes.core.network.OfflineCountryServer
import app.openhikes.core.network.TileNetworkPolicy
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Production [OfflineCountryRepository] coordinating `countries/manifest.json` catalog refreshes,
 * WorkManager-scheduled PMTiles downloads, SHA-256 verification, atomic `index.json` persistence,
 * and *Extra Detail* / *Elevation Data* per-country offers.
 */
@Singleton
@Suppress("TooManyFunctions")
internal class DefaultOfflineCountryRepository @Inject constructor(
    private val store: OfflineCountryStore,
    private val server: OfflineCountryServer,
    private val scheduler: OfflineCountryDownloadScheduler,
    private val tileNetworkPolicy: TileNetworkPolicy,
    private val userSettingsRepository: UserSettingsRepository,
    private val executor: OfflineCountryDownloadExecutor,
    private val reconciler: OfflineCountryReconciler,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : OfflineCountryRepository {
    private val prepareMutex = Mutex()
    private val mutationMutex = Mutex()
    private val refreshMutex = Mutex()
    private val catalogRevisionCounter = AtomicLong(0L)
    private val attemptIdCounter = AtomicLong(0L)
    private val stateFlow = MutableStateFlow(OfflineCountriesState())
    private var pendingReadyToFinishIds: List<String> = emptyList()

    override val state: StateFlow<OfflineCountriesState> = stateFlow.asStateFlow()

    private val executorCallbacks = object : OfflineCountryDownloadExecutor.Callbacks {
        override fun currentDownload(countryId: String): OfflineCountryDownload? = stateFlow.value.downloads[countryId]

        override fun updateDownload(
            countryId: String,
            attemptId: Long,
            transform: (OfflineCountryDownload) -> OfflineCountryDownload
        ) {
            stateFlow.update { current ->
                val existing = current.downloads[countryId]
                if (existing == null || existing.attemptId != attemptId) {
                    current
                } else {
                    current.copy(downloads = current.downloads + (countryId to transform(existing)))
                }
            }
        }

        override fun failDownload(countryId: String, attemptId: Long, failure: OfflineDownloadFailure) {
            stateFlow.update { current ->
                val existing = current.downloads[countryId]
                if (existing == null || existing.attemptId != attemptId) {
                    current
                } else {
                    current.copy(
                        downloads = current.downloads + (
                            countryId to existing.copy(
                                phase = OfflineDownloadPhase.FAILED,
                                failure = failure,
                                inFlightRoles = emptySet()
                            )
                            )
                    )
                }
            }
        }

        override suspend fun failVerificationDamaged(
            countryId: String,
            download: OfflineCountryDownload,
            damagedFiles: List<OfflineCountryFile>
        ): OfflineDownloadExecutionResult = mutationMutex.withLock {
            withContext(NonCancellable) {
                if (!isCurrentAttempt(countryId, download)) {
                    OfflineDownloadExecutionResult.CANCELLED
                } else {
                    reconciler.deleteStagedFiles(damagedFiles)
                    val damagedRoles = damagedFiles.map { it.role }.toSet()
                    updateDownload(countryId, download.attemptId) { existing ->
                        val remainingStaged = existing.stagedRoles - damagedRoles
                        val remainingWritten = existing.files
                            .filter { it.role in remainingStaged }
                            .sumOf { it.byteCount }
                        existing.copy(
                            phase = OfflineDownloadPhase.FAILED,
                            failure = OfflineDownloadFailure.DAMAGED,
                            stagedRoles = remainingStaged,
                            writtenBytes = remainingWritten,
                            inFlightRoles = emptySet()
                        )
                    }
                    OfflineDownloadExecutionResult.FAILED
                }
            }
        }

        override suspend fun installAndCommit(
            countryId: String,
            download: OfflineCountryDownload,
            files: List<OfflineCountryFile>,
            installedAtEpochMillis: Long
        ): OfflineDownloadExecutionResult {
            val result = mutationMutex.withLock {
                withContext(NonCancellable) {
                    if (!isCurrentAttempt(countryId, download)) {
                        OfflineDownloadExecutionResult.CANCELLED
                    } else {
                        commitVerifiedCountryLocked(countryId, download, files, installedAtEpochMillis)
                    }
                }
            }
            if (result == OfflineDownloadExecutionResult.COMPLETED) {
                bringGainedFiles()
            }
            return result
        }
    }

    private fun isCurrentAttempt(countryId: String, download: OfflineCountryDownload): Boolean {
        val liveDownload = stateFlow.value.downloads[countryId]
        return liveDownload != null &&
            liveDownload.attemptId == download.attemptId &&
            liveDownload.country.build == download.country.build &&
            liveDownload.roles == download.roles
    }

    private suspend fun commitVerifiedCountryLocked(
        countryId: String,
        download: OfflineCountryDownload,
        files: List<OfflineCountryFile>,
        installedAtEpochMillis: Long
    ): OfflineDownloadExecutionResult {
        val outcome = reconciler.installVerifiedCountry(
            countryId = countryId,
            download = download,
            files = files,
            currentInstalled = stateFlow.value.installed,
            installedAtEpochMillis = installedAtEpochMillis
        )
        return when (outcome) {
            OfflineCountryReconciler.InstallOutcome.FailedSpace -> {
                executorCallbacks.failDownload(countryId, download.attemptId, OfflineDownloadFailure.SPACE)
                OfflineDownloadExecutionResult.FAILED
            }

            is OfflineCountryReconciler.InstallOutcome.Installed -> {
                stateFlow.update { current ->
                    current.copy(
                        installed = outcome.nextInstalled,
                        downloads = current.downloads - countryId,
                        recovering = current.recovering - countryId,
                        unopenable = outcome.unopenable
                    )
                }
                reconciler.persistPending(stateFlow.value.downloads.values)
                OfflineDownloadExecutionResult.COMPLETED
            }
        }
    }

    override suspend fun start() {
        ensurePrepared()
        val readyIds = prepareMutex.withLock {
            val ids = pendingReadyToFinishIds
            pendingReadyToFinishIds = emptyList()
            ids
        }
        for (readyId in readyIds) {
            val download = stateFlow.value.downloads[readyId]
            try {
                if (download != null) {
                    executor.verifyAndInstall(readyId, download, executorCallbacks)
                }
            } finally {
                stateFlow.update { current ->
                    val live = current.downloads[readyId]
                    if (live == null || download == null || live.attemptId == download.attemptId) {
                        current.copy(recovering = current.recovering - readyId)
                    } else {
                        current
                    }
                }
            }
        }
        bringGainedFiles()
    }

    override suspend fun refreshCatalog() {
        ensurePrepared()
        var loaded = false
        refreshMutex.withLock {
            if (stateFlow.value.holdings == OfflineHoldingsState.UNREADABLE) return
            stateFlow.update { it.copy(listPhase = OfflineListPhase.LOADING) }
            try {
                val manifest = server.fetchManifest()
                val readable = manifest.readableCountries
                withContext(ioDispatcher) {
                    runCatching { store.writeCachedManifest(manifest) }
                }
                stateFlow.update { current ->
                    current.copy(
                        listPhase = OfflineListPhase.LOADED,
                        catalogRevision = catalogRevisionCounter.incrementAndGet(),
                        offered = readable
                    )
                }
                loaded = true
            } catch (_: IOException) {
                stateFlow.update { current -> current.copy(listPhase = OfflineListPhase.FAILED) }
            } catch (_: IllegalArgumentException) {
                stateFlow.update { current -> current.copy(listPhase = OfflineListPhase.FAILED) }
            }
        }
        if (loaded) {
            bringGainedFiles()
        }
    }

    override suspend fun downloadCountry(countryId: String) {
        ensurePrepared()
        mutationMutex.withLock {
            withContext(NonCancellable) {
                val current = stateFlow.value
                val ready = current.isAvailable && current.holdings == OfflineHoldingsState.READY
                val country = if (ready && current.isSettled(countryId)) {
                    current.offered.firstOrNull { it.id == countryId } ?: current.installed[countryId]?.country
                } else {
                    null
                }
                val files = country?.let { current.filesToFetch(it) }.orEmpty()
                if (country != null && files.isNotEmpty()) {
                    startDownloadLocked(country = country, roles = files.map { it.role })
                }
            }
        }
    }

    override suspend fun retryDownload(countryId: String) {
        ensurePrepared()
        val initial = stateFlow.value.downloads[countryId]
        if (initial == null || initial.phase != OfflineDownloadPhase.FAILED) return
        refreshCatalog()
        mutationMutex.withLock {
            withContext(NonCancellable) {
                val current = stateFlow.value
                val failed = current.downloads[countryId]
                if (canRetryDownload(current, countryId, failed)) {
                    val failedDownload = requireNotNull(failed)
                    val latestOffered = current.offered.firstOrNull { it.id == countryId }
                    if (latestOffered != null && latestOffered.build > failedDownload.country.build) {
                        retryAgainstNewerBuildLocked(current, countryId, failedDownload, latestOffered)
                    } else {
                        retrySameBuildLocked(current, countryId, failedDownload)
                    }
                }
            }
        }
    }

    override suspend fun repairCountry(countryId: String) {
        ensurePrepared()
        mutationMutex.withLock {
            withContext(NonCancellable) {
                val current = stateFlow.value
                val ready = current.isAvailable &&
                    current.holdings == OfflineHoldingsState.READY &&
                    current.isSettled(countryId)
                val record = if (ready) current.installed[countryId] else null
                val targetCountry = record?.let { current.offeredBuild(it) ?: it.country }
                val files = targetCountry?.let { current.filesToFetch(it) }.orEmpty()
                if (targetCountry != null && files.isNotEmpty()) {
                    startDownloadLocked(country = targetCountry, roles = files.map { it.role })
                }
            }
        }
    }

    override suspend fun cancelDownload(countryId: String) {
        ensurePrepared()
        mutationMutex.withLock {
            withContext(NonCancellable) {
                cancelDownloadLocked(countryId)
            }
        }
    }

    override suspend fun deleteCountry(countryId: String) {
        ensurePrepared()
        mutationMutex.withLock {
            withContext(NonCancellable) {
                val existing = stateFlow.value.installed[countryId]
                if (existing != null && cancelDownloadLocked(countryId)) {
                    val outcome = reconciler.deleteInstalledCountry(
                        countryId = countryId,
                        currentInstalled = stateFlow.value.installed,
                        removedFiles = existing.files
                    )
                    if (outcome != null) {
                        stateFlow.update { latest ->
                            latest.copy(
                                installed = outcome.first,
                                unopenable = outcome.second
                            )
                        }
                    }
                }
            }
        }
    }

    override suspend fun setIncludesDetail(enabled: Boolean) {
        ensurePrepared()
        if (stateFlow.value.includesDetail == enabled) return
        withContext(NonCancellable) {
            runCatching { userSettingsRepository.setOfflineCountriesExtraDetail(enabled) }
            stateFlow.update { it.copy(includesDetail = enabled) }
        }
    }

    override suspend fun setIncludesElevation(enabled: Boolean) {
        ensurePrepared()
        if (stateFlow.value.includesElevation == enabled) return
        withContext(NonCancellable) {
            runCatching { userSettingsRepository.setOfflineCountriesElevationData(enabled) }
            stateFlow.update { it.copy(includesElevation = enabled) }
        }
    }

    override suspend fun extendCountry(countryId: String) {
        ensurePrepared()
        mutationMutex.withLock {
            withContext(NonCancellable) {
                val current = stateFlow.value
                val ready = current.isAvailable && current.holdings == OfflineHoldingsState.READY
                val record = if (ready) current.installed[countryId] else null
                val listed = record?.let { current.offeredBuild(it) }
                val offer = if (listed != null) current.addition(countryId) else null
                if (listed != null && offer != null) {
                    startDownloadLocked(country = listed, roles = offer.files.map { it.role })
                }
            }
        }
    }

    override suspend fun trimCountry(countryId: String) {
        ensurePrepared()
        mutationMutex.withLock {
            withContext(NonCancellable) {
                val current = stateFlow.value
                val record = current.installed[countryId]
                val offer = if (record != null) current.removal(countryId) else null
                if (record != null && offer != null) {
                    val stripped = record.copy(
                        hasDetail = if (offer.files.any { it.role.isDetail }) false else record.hasDetail,
                        hasElevation = if (offer.files.any { it.role.isElevation }) false else record.hasElevation
                    )
                    val outcome = reconciler.trimInstalledCountry(
                        countryId = countryId,
                        strippedRecord = stripped,
                        currentInstalled = current.installed,
                        removedFiles = offer.files
                    )
                    if (outcome != null) {
                        stateFlow.update { latest ->
                            latest.copy(
                                installed = outcome.first,
                                unopenable = outcome.second
                            )
                        }
                    }
                }
            }
        }
    }

    override suspend fun runScheduledDownload(countryId: String): OfflineDownloadExecutionResult {
        ensurePrepared()
        return executor.execute(countryId, executorCallbacks)
    }

    override suspend fun ensurePrepared() {
        if (stateFlow.value.isPrepared) return
        prepareMutex.withLock {
            if (stateFlow.value.isPrepared) return
            val settings = runCatching { userSettingsRepository.userSettings.first() }.getOrDefault(UserSettings())
            val snapshot = reconciler.loadInitialSnapshot(server)
            mutationMutex.withLock {
                if (snapshot.holdings == OfflineHoldingsState.UNREADABLE) {
                    stateFlow.update { current ->
                        current.copy(
                            isAvailable = false,
                            isPrepared = true,
                            holdings = OfflineHoldingsState.UNREADABLE,
                            includesDetail = settings.offlineCountriesExtraDetail,
                            includesElevation = settings.offlineCountriesElevationData
                        )
                    }
                    pendingReadyToFinishIds = emptyList()
                } else {
                    val recoveredWithIds = snapshot.recoveredDownloads.mapValues { (_, download) ->
                        download.copy(attemptId = attemptIdCounter.incrementAndGet())
                    }
                    stateFlow.update { current ->
                        current.copy(
                            isAvailable = true,
                            isPrepared = true,
                            holdings = OfflineHoldingsState.READY,
                            offered = if (current.offered.isEmpty()) snapshot.cachedOffered else current.offered,
                            installed = snapshot.installed,
                            downloads = recoveredWithIds,
                            recovering = snapshot.readyToFinishIds.toSet(),
                            unopenable = snapshot.unopenable,
                            includesDetail = settings.offlineCountriesExtraDetail,
                            includesElevation = settings.offlineCountriesElevationData
                        )
                    }
                    pendingReadyToFinishIds = snapshot.readyToFinishIds
                }
            }
        }
    }

    private suspend fun bringGainedFiles() {
        val candidates = reconciler.findGainedMandatoryDownloads(stateFlow.value)
        for ((listed, gained) in candidates) {
            mutationMutex.withLock {
                withContext(NonCancellable) {
                    val current = stateFlow.value
                    if (current.isSettled(listed.id) && !current.needsRepair(listed.id)) {
                        startDownloadLocked(country = listed, roles = gained.map { it.role })
                    }
                }
            }
        }
    }

    private suspend fun retryAgainstNewerBuildLocked(
        current: OfflineCountriesState,
        countryId: String,
        failed: OfflineCountryDownload,
        latestOffered: OfflineCountry
    ) {
        val wasSameInstalledBuild = current.installed[countryId]?.country?.build == failed.country.build
        if (!cancelDownloadLocked(countryId) || wasSameInstalledBuild) return
        val freshFiles = stateFlow.value.filesToFetch(latestOffered)
        if (freshFiles.isNotEmpty()) {
            startDownloadLocked(country = latestOffered, roles = freshFiles.map { it.role })
        }
    }

    private suspend fun retrySameBuildLocked(
        current: OfflineCountriesState,
        countryId: String,
        failed: OfflineCountryDownload
    ) {
        val retryFiles = current.filesToRetry(failed)
        if (retryFiles.isEmpty()) {
            cancelDownloadLocked(countryId)
            return
        }
        val persistedFiles = reconciler.pendingFilesFor(countryId)
        val previousFiles = (failed.files + persistedFiles).distinctBy { it.fileName }
        val keptRoles = retryFiles.map { it.role }
        val keptNames = retryFiles.map { it.fileName }.toSet()
        val droppedFiles = previousFiles.filter { it.fileName !in keptNames }
        val keptStaged = failed.stagedRoles.filter { it in keptRoles }.toSet()
        val keptWritten = reconciler.measureStagedBytes(retryFiles)
        if (droppedFiles.isNotEmpty()) {
            scheduler.cancel(countryId)
        }
        val started = startDownloadLocked(
            country = failed.country,
            roles = keptRoles,
            existingStaged = keptStaged,
            existingWrittenBytes = keptWritten
        )
        if (started && droppedFiles.isNotEmpty()) {
            reconciler.deleteStagedFiles(droppedFiles)
        }
    }

    private suspend fun startDownloadLocked(
        country: OfflineCountry,
        roles: List<OfflineFileRole>,
        existingStaged: Set<OfflineFileRole> = emptySet(),
        existingWrittenBytes: Long = 0L
    ): Boolean {
        val totalBytes = country.files.filter { it.role in roles }.sumOf { it.byteCount }
        val baseDownload = OfflineCountryDownload(
            country = country,
            roles = roles,
            phase = OfflineDownloadPhase.WAITING_FOR_WIFI,
            writtenBytes = existingWrittenBytes.coerceAtMost(totalBytes),
            totalBytes = totalBytes,
            stagedRoles = existingStaged,
            attemptId = attemptIdCounter.incrementAndGet()
        )
        val decision = tileNetworkPolicy.decide(TileNetworkPurpose.SPECULATIVE)
        val preflightFailure = checkPreflightFailure(country.id, baseDownload, decision)
        if (preflightFailure != null) {
            stateFlow.recordFailure(country.id, baseDownload, preflightFailure)
            return false
        }
        val initialPhase = if (decision is TileNetworkDecision.Allowed) {
            OfflineDownloadPhase.DOWNLOADING
        } else {
            OfflineDownloadPhase.WAITING_FOR_WIFI
        }
        val previousDownloads = stateFlow.value.downloads
        stateFlow.update {
            it.copy(downloads = it.downloads + (country.id to baseDownload.copy(phase = initialPhase, failure = null)))
        }
        return if (reconciler.persistPending(stateFlow.value.downloads.values)) {
            scheduler.enqueue(country.id)
            true
        } else {
            stateFlow.update {
                it.copy(
                    downloads = previousDownloads + (
                        country.id to baseDownload.copy(
                            phase = OfflineDownloadPhase.FAILED,
                            failure = OfflineDownloadFailure.SPACE
                        )
                        )
                )
            }
            false
        }
    }

    private suspend fun checkPreflightFailure(
        countryId: String,
        baseDownload: OfflineCountryDownload,
        decision: TileNetworkDecision
    ): OfflineDownloadFailure? {
        if (decision is TileNetworkDecision.Denied && isConservingReason(decision.reason)) {
            return OfflineDownloadFailure.CONSERVING
        }
        val available = reconciler.availableBytes()
        val owedElsewhere = stateFlow.value.downloads
            .filterKeys { it != countryId }
            .values
            .sumOf { it.owedBytes }
        val requiredSpace = baseDownload.remainingBytes + owedElsewhere + OfflineCountryStore.SPARE_BYTES
        return if (available != null && available < requiredSpace) OfflineDownloadFailure.SPACE else null
    }

    private suspend fun cancelDownloadLocked(countryId: String): Boolean {
        val existing = stateFlow.value.downloads[countryId]
        val persistedFiles = reconciler.pendingFilesFor(countryId)
        val wasRecovering = countryId in stateFlow.value.recovering
        val stopped = if (existing == null && persistedFiles.isEmpty() && !wasRecovering) {
            true
        } else {
            val persisted = reconciler.persistPending((stateFlow.value.downloads - countryId).values)
            if (persisted) {
                scheduler.cancel(countryId)
                stateFlow.update { current ->
                    current.copy(
                        downloads = current.downloads - countryId,
                        recovering = current.recovering - countryId
                    )
                }
                val filesToDelete = (existing?.files.orEmpty() + persistedFiles).distinctBy { it.fileName }
                if (filesToDelete.isNotEmpty()) {
                    reconciler.deleteStagedFiles(filesToDelete)
                }
            }
            persisted
        }
        return stopped
    }
}

private fun canRetryDownload(
    current: OfflineCountriesState,
    countryId: String,
    failed: OfflineCountryDownload?
): Boolean = current.isAvailable &&
    current.holdings == OfflineHoldingsState.READY &&
    countryId !in current.recovering &&
    failed != null &&
    failed.phase == OfflineDownloadPhase.FAILED

private fun MutableStateFlow<OfflineCountriesState>.recordFailure(
    countryId: String,
    baseDownload: OfflineCountryDownload,
    failure: OfflineDownloadFailure
) {
    update { current ->
        current.copy(
            downloads = current.downloads + (
                countryId to baseDownload.copy(
                    phase = OfflineDownloadPhase.FAILED,
                    failure = failure
                )
                )
        )
    }
}

private fun isConservingReason(reason: TileNetworkDenialReason): Boolean =
    reason == TileNetworkDenialReason.LOW_POWER_MODE || reason == TileNetworkDenialReason.THERMAL
