package app.openhikes.core.data

import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.common.offline.OfflineCountryStore
import app.openhikes.core.model.InstalledCountryRecord
import app.openhikes.core.model.OfflineCountriesState
import app.openhikes.core.model.OfflineCountry
import app.openhikes.core.model.OfflineCountryDownload
import app.openhikes.core.model.OfflineDownloadFailure
import app.openhikes.core.model.OfflineDownloadPhase
import app.openhikes.core.model.OfflineFileRole
import app.openhikes.core.model.OfflineHoldingsState
import app.openhikes.core.model.OfflineListPhase
import app.openhikes.core.model.PendingCountryDownload
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
 * and Extra Detail reconciliation.
 */
@Singleton
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
    private val mutationMutex = Mutex()
    private val refreshMutex = Mutex()
    private val catalogRevisionCounter = AtomicLong(0L)
    private val stateFlow = MutableStateFlow(OfflineCountriesState())

    override val state: StateFlow<OfflineCountriesState> = stateFlow.asStateFlow()

    private val executorCallbacks = object : OfflineCountryDownloadExecutor.Callbacks {
        override fun currentDownload(countryId: String): OfflineCountryDownload? = stateFlow.value.downloads[countryId]

        override fun currentInstalled(): Map<String, InstalledCountryRecord> = stateFlow.value.installed

        override fun updateDownload(countryId: String, transform: (OfflineCountryDownload) -> OfflineCountryDownload) {
            stateFlow.update { current ->
                val existing = current.downloads[countryId] ?: return@update current
                current.copy(downloads = current.downloads + (countryId to transform(existing)))
            }
        }

        override fun failDownload(countryId: String, failure: OfflineDownloadFailure) {
            stateFlow.update { current ->
                val existing = current.downloads[countryId] ?: return@update current
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

        override suspend fun commitInstalledAndFinish(
            countryId: String,
            download: OfflineCountryDownload,
            newRecord: InstalledCountryRecord,
            previousRecord: InstalledCountryRecord?,
            reopenedUnopenable: Map<String, Set<OfflineFileRole>>
        ): Boolean {
            mutationMutex.withLock {
                stateFlow.update { current ->
                    current.copy(
                        installed = current.installed + (countryId to newRecord),
                        downloads = current.downloads - countryId,
                        unopenable = reopenedUnopenable
                    )
                }
                persistPendingLocked()
            }
            reconcileAfterStateChange()
            return true
        }
    }

    override suspend fun start() {
        val includesDetail = runCatching { userSettingsRepository.userSettings.first().offlineCountriesExtraDetail }
            .getOrDefault(UserSettings.DEFAULT_OFFLINE_COUNTRIES_EXTRA_DETAIL)
        val snapshot = reconciler.loadInitialSnapshot()
        mutationMutex.withLock {
            if (snapshot.holdings == OfflineHoldingsState.UNREADABLE) {
                stateFlow.update { current ->
                    current.copy(
                        isAvailable = false,
                        isPrepared = true,
                        holdings = OfflineHoldingsState.UNREADABLE,
                        includesDetail = includesDetail
                    )
                }
                return
            }
            stateFlow.update { current ->
                current.copy(
                    isAvailable = true,
                    isPrepared = true,
                    holdings = OfflineHoldingsState.READY,
                    offered = if (current.offered.isEmpty()) snapshot.cachedOffered else current.offered,
                    installed = snapshot.installed,
                    downloads = snapshot.recoveredDownloads,
                    unopenable = snapshot.unopenable,
                    includesDetail = includesDetail
                )
            }
        }
        for (readyId in snapshot.readyToFinishIds) {
            val download = stateFlow.value.downloads[readyId] ?: continue
            executor.verifyAndInstall(readyId, download, executorCallbacks)
        }
        reconcileAfterStateChange()
    }

    override suspend fun refreshCatalog() {
        if (!stateFlow.value.isPrepared) {
            start()
        }
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
            } catch (_: IOException) {
                stateFlow.update { current ->
                    current.copy(listPhase = OfflineListPhase.FAILED)
                }
            }
        }
    }

    override suspend fun downloadCountry(countryId: String) {
        mutationMutex.withLock {
            val current = stateFlow.value
            val ready = current.isAvailable && current.holdings == OfflineHoldingsState.READY
            val country = if (ready && countryId !in current.downloads) {
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

    override suspend fun retryDownload(countryId: String) {
        val failed = stateFlow.value.downloads[countryId] ?: return
        refreshCatalog()
        mutationMutex.withLock {
            val current = stateFlow.value
            if (!current.isAvailable || current.holdings != OfflineHoldingsState.READY) return
            val latestOffered = current.offered.firstOrNull { it.id == countryId }
            if (latestOffered != null && latestOffered.build > failed.country.build) {
                cancelDownloadLocked(countryId)
                val freshFiles = stateFlow.value.filesToFetch(latestOffered)
                if (freshFiles.isNotEmpty()) {
                    startDownloadLocked(country = latestOffered, roles = freshFiles.map { it.role })
                }
            } else {
                startDownloadLocked(
                    country = failed.country,
                    roles = failed.roles,
                    existingStaged = failed.stagedRoles,
                    existingWrittenBytes = failed.writtenBytes
                )
            }
        }
    }

    override suspend fun repairCountry(countryId: String) {
        mutationMutex.withLock {
            val current = stateFlow.value
            val record = if (current.isAvailable && current.holdings == OfflineHoldingsState.READY) {
                current.installed[countryId]
            } else {
                null
            }
            val brokenRoles = current.unopenable[countryId].orEmpty()
            val rolesToRepair = record?.files?.map { it.role }?.filter { it in brokenRoles }.orEmpty()
            if (record != null && rolesToRepair.isNotEmpty()) {
                startDownloadLocked(country = record.country, roles = rolesToRepair)
            }
        }
    }

    override suspend fun cancelDownload(countryId: String) {
        mutationMutex.withLock {
            cancelDownloadLocked(countryId)
        }
    }

    override suspend fun deleteCountry(countryId: String) {
        mutationMutex.withLock {
            val current = stateFlow.value
            val existing = current.installed[countryId] ?: return
            val nextInstalled = current.installed - countryId
            val persisted = withContext(ioDispatcher) {
                runCatching { store.writeInstalled(nextInstalled.values.toList()) }.isSuccess
            }
            if (!persisted) return
            cancelDownloadLocked(countryId)
            val reopenedUnopenable = reconciler.reopenAndRemove(
                keeping = nextInstalled.values.toList(),
                removedFiles = existing.files
            )
            stateFlow.update { latest ->
                latest.copy(
                    installed = nextInstalled,
                    unopenable = reopenedUnopenable
                )
            }
        }
    }

    override suspend fun setIncludesDetail(enabled: Boolean) {
        runCatching { userSettingsRepository.setOfflineCountriesExtraDetail(enabled) }
        stateFlow.update { it.copy(includesDetail = enabled) }
        reconcileAfterStateChange()
    }

    override suspend fun runScheduledDownload(countryId: String): OfflineDownloadExecutionResult =
        executor.execute(countryId, executorCallbacks)

    private suspend fun reconcileAfterStateChange() {
        val current = stateFlow.value
        if (current.holdings != OfflineHoldingsState.READY) return
        if (!current.includesDetail) {
            val detailOnlyIds = current.downloads.filterValues { it.isDetailOnly }.keys.toList()
            mutationMutex.withLock {
                detailOnlyIds.forEach { cancelDownloadLocked(it) }
                val (updatedInstalled, updatedUnopenable) = reconciler.stripInstalledDetail(stateFlow.value)
                stateFlow.update { it.copy(installed = updatedInstalled, unopenable = updatedUnopenable) }
            }
        } else {
            val missingDetailIds = reconciler.findInstalledMissingDetail(current)
            for (id in missingDetailIds) {
                downloadCountry(id)
            }
        }
    }

    private suspend fun startDownloadLocked(
        country: OfflineCountry,
        roles: List<OfflineFileRole>,
        existingStaged: Set<OfflineFileRole> = emptySet(),
        existingWrittenBytes: Long = 0L
    ) {
        val totalBytes = country.files.filter { it.role in roles }.sumOf { it.byteCount }
        val baseDownload = OfflineCountryDownload(
            country = country,
            roles = roles,
            phase = OfflineDownloadPhase.WAITING_FOR_WIFI,
            writtenBytes = existingWrittenBytes.coerceAtMost(totalBytes),
            totalBytes = totalBytes,
            stagedRoles = existingStaged
        )
        val decision = tileNetworkPolicy.decide(TileNetworkPurpose.SPECULATIVE)
        val preflightFailure = checkPreflightFailure(country.id, baseDownload, decision)
        if (preflightFailure != null) {
            recordFailureLocked(country.id, baseDownload, preflightFailure)
            return
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
        if (persistPendingLocked()) {
            scheduler.enqueue(country.id)
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
        val available = withContext(ioDispatcher) { store.availableBytes() }
        val owedElsewhere = stateFlow.value.downloads
            .filterKeys { it != countryId }
            .values
            .sumOf { it.owedBytes }
        val requiredSpace = baseDownload.remainingBytes + owedElsewhere + OfflineCountryStore.SPARE_BYTES
        return if (available != null && available < requiredSpace) OfflineDownloadFailure.SPACE else null
    }

    private fun recordFailureLocked(
        countryId: String,
        baseDownload: OfflineCountryDownload,
        failure: OfflineDownloadFailure
    ) {
        stateFlow.update { current ->
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

    private suspend fun cancelDownloadLocked(countryId: String) {
        val existing = stateFlow.value.downloads[countryId] ?: return
        scheduler.cancel(countryId)
        stateFlow.update { it.copy(downloads = it.downloads - countryId) }
        persistPendingLocked()
        withContext(ioDispatcher) {
            existing.files.forEach { file ->
                store.stagedFileFor(file).delete()
                store.partialFileFor(file).delete()
            }
        }
    }

    private suspend fun persistPendingLocked(): Boolean {
        val pending = stateFlow.value.downloads.values.map { PendingCountryDownload(it.country, it.roles) }
        return withContext(ioDispatcher) {
            runCatching { store.writePending(pending) }.isSuccess
        }
    }
}

private fun isConservingReason(reason: TileNetworkDenialReason): Boolean =
    reason == TileNetworkDenialReason.LOW_POWER_MODE || reason == TileNetworkDenialReason.THERMAL
