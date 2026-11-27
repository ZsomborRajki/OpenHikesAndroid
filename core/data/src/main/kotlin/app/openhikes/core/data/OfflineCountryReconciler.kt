package app.openhikes.core.data

import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.common.offline.OfflineCountryStore
import app.openhikes.core.common.offline.OfflineStoreList
import app.openhikes.core.model.InstalledCountryRecord
import app.openhikes.core.model.OfflineCountriesState
import app.openhikes.core.model.OfflineCountry
import app.openhikes.core.model.OfflineCountryDownload
import app.openhikes.core.model.OfflineCountryFile
import app.openhikes.core.model.OfflineDownloadFailure
import app.openhikes.core.model.OfflineDownloadPhase
import app.openhikes.core.model.OfflineFileRole
import app.openhikes.core.model.OfflineHoldingsState
import app.openhikes.core.model.PendingCountryDownload
import app.openhikes.core.network.OfflineCountryServer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Startup recovery, orphan sweep, disk persistence, and optional-extra trimming helper for
 * [DefaultOfflineCountryRepository].
 */
@Singleton
internal class OfflineCountryReconciler @Inject constructor(
    private val store: OfflineCountryStore,
    private val archives: OfflineCountryArchives,
    private val launchConfigHolder: LaunchConfigHolder,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    data class RecoverySnapshot(
        val holdings: OfflineHoldingsState,
        val installed: Map<String, InstalledCountryRecord>,
        val unopenable: Map<String, Set<OfflineFileRole>>,
        val cachedOffered: List<OfflineCountry>,
        val pending: List<PendingCountryDownload>,
        val recoveredDownloads: Map<String, OfflineCountryDownload>,
        val readyToFinishIds: List<String>
    )

    sealed interface InstallOutcome {
        data class Installed(
            val nextInstalled: Map<String, InstalledCountryRecord>,
            val unopenable: Map<String, Set<OfflineFileRole>>
        ) : InstallOutcome

        data object FailedSpace : InstallOutcome
    }

    suspend fun loadInitialSnapshot(server: OfflineCountryServer): RecoverySnapshot = withContext(ioDispatcher) {
        if (launchConfigHolder.current.value.offlineCountriesScenarioName == SCENARIO_DOWNLOADED) {
            loadDownloadedScenarioSnapshot(server)
        } else {
            loadDiskSnapshot()
        }
    }

    suspend fun installVerifiedCountry(
        countryId: String,
        download: OfflineCountryDownload,
        files: List<OfflineCountryFile>,
        currentInstalled: Map<String, InstalledCountryRecord>,
        installedAtEpochMillis: Long
    ): InstallOutcome = withContext(ioDispatcher) {
        val previous = currentInstalled[countryId]
        val sameBuild = previous != null && previous.country.build == download.country.build
        val mergedHasDetail = download.hasDetail || (sameBuild && previous.hasDetail)
        val mergedHasElevation = download.hasElevation || (sameBuild && previous.hasElevation)
        val newRecord = InstalledCountryRecord(
            country = download.country,
            installedAtEpochMillis = installedAtEpochMillis,
            hasDetail = mergedHasDetail,
            hasElevation = mergedHasElevation
        )
        if (runCatching { store.install(files) }.isFailure) {
            return@withContext InstallOutcome.FailedSpace
        }
        val nextInstalled = currentInstalled + (countryId to newRecord)
        if (runCatching { store.writeInstalled(nextInstalled.values.toList()) }.isFailure) {
            store.unstage(files, keeping = currentInstalled.values.toList())
            return@withContext InstallOutcome.FailedSpace
        }
        val reopenedUnopenable = reopenArchives(nextInstalled.values.toList())
        if (previous != null) {
            store.remove(previous.files, keeping = nextInstalled.values.toList())
        }
        InstallOutcome.Installed(nextInstalled = nextInstalled, unopenable = reopenedUnopenable)
    }

    suspend fun trimInstalledCountry(
        countryId: String,
        strippedRecord: InstalledCountryRecord,
        currentInstalled: Map<String, InstalledCountryRecord>,
        removedFiles: List<OfflineCountryFile>
    ): Pair<Map<String, InstalledCountryRecord>, Map<String, Set<OfflineFileRole>>>? = withContext(ioDispatcher) {
        val nextInstalled = currentInstalled + (countryId to strippedRecord)
        val persisted = runCatching { store.writeInstalled(nextInstalled.values.toList()) }.isSuccess
        if (!persisted) return@withContext null
        val reopenedUnopenable = reopenArchives(nextInstalled.values.toList())
        store.remove(removedFiles, keeping = nextInstalled.values.toList())
        nextInstalled to reopenedUnopenable
    }

    suspend fun deleteInstalledCountry(
        countryId: String,
        currentInstalled: Map<String, InstalledCountryRecord>,
        removedFiles: List<OfflineCountryFile>
    ): Pair<Map<String, InstalledCountryRecord>, Map<String, Set<OfflineFileRole>>>? = withContext(ioDispatcher) {
        val nextInstalled = currentInstalled - countryId
        val persisted = runCatching { store.writeInstalled(nextInstalled.values.toList()) }.isSuccess
        if (!persisted) return@withContext null
        val reopenedUnopenable = reopenArchives(nextInstalled.values.toList())
        store.remove(removedFiles, keeping = nextInstalled.values.toList())
        nextInstalled to reopenedUnopenable
    }

    suspend fun deleteStagedFiles(files: List<OfflineCountryFile>) = withContext(ioDispatcher) {
        files.forEach { file ->
            store.stagedFileFor(file).delete()
            store.partialFileFor(file).delete()
        }
    }

    suspend fun measureStagedBytes(files: List<OfflineCountryFile>): Long = withContext(ioDispatcher) {
        files.sumOf { file ->
            val disk = store.stagedFileFor(file)
            val part = store.partialFileFor(file)
            when {
                disk.isFile -> disk.length().coerceAtMost(file.byteCount)
                part.isFile -> part.length().coerceAtMost(file.byteCount)
                else -> 0L
            }
        }
    }

    suspend fun persistPending(downloads: Collection<OfflineCountryDownload>): Boolean {
        val pending = downloads.map { PendingCountryDownload(it.country, it.roles) }
        return withContext(ioDispatcher) {
            runCatching { store.writePending(pending) }.isSuccess
        }
    }

    suspend fun availableBytes(): Long? = withContext(ioDispatcher) { store.availableBytes() }

    /**
     * Returns same-build offered countries and their newly listed mandatory (non-optional) files
     * that healthy, settled installed areas do not yet hold. Switch-controlled optional files
     * (*Extra Detail*, *Elevation Data*) are never returned here; they wait for the hiker's tap on
     * the country's Add offer.
     */
    fun findGainedMandatoryDownloads(
        state: OfflineCountriesState
    ): List<Pair<OfflineCountry, List<OfflineCountryFile>>> {
        if (state.holdings != OfflineHoldingsState.READY || state.recovering.isNotEmpty()) return emptyList()
        return state.installed.values.mapNotNull { record ->
            val id = record.country.id
            val listed = if (state.isSettled(id) && !state.needsRepair(id)) state.offeredBuild(record) else null
            val gained = listed?.let { OfflineCountriesState.gainedFiles(listed = it, record = record) }.orEmpty()
            if (listed != null && gained.isNotEmpty()) listed to gained else null
        }
    }

    private fun loadDiskSnapshot(): RecoverySnapshot {
        val indexList = store.readIndex()
        val pendingList = store.readPending()
        if (indexList is OfflineStoreList.Unreadable || pendingList is OfflineStoreList.Unreadable) {
            return unreadableSnapshot()
        }
        val records = (indexList as OfflineStoreList.Holding).items
        val rawPending = (pendingList as OfflineStoreList.Holding).items
        val unopenable = reopenArchives(records)
        val installedById = records.associateBy { it.country.id }
        val pending = rawPending.filterNot { isAlreadyInstalled(it, installedById, unopenable) }
        if (pending.size != rawPending.size) {
            runCatching { store.writePending(pending) }
        }
        val cachedOffered = store.readCachedManifest()?.readableCountries.orEmpty()

        val recoveredDownloads = LinkedHashMap<String, OfflineCountryDownload>()
        val readyToFinishIds = ArrayList<String>()
        for (item in pending) {
            val (download, isReady) = recoverPendingItem(item)
            recoveredDownloads[item.country.id] = download
            if (isReady) {
                readyToFinishIds.add(item.country.id)
            }
        }
        store.sweep(keeping = records, pending = pending)
        return RecoverySnapshot(
            holdings = OfflineHoldingsState.READY,
            installed = installedById,
            unopenable = unopenable,
            cachedOffered = cachedOffered,
            pending = pending,
            recoveredDownloads = recoveredDownloads,
            readyToFinishIds = readyToFinishIds
        )
    }

    private suspend fun loadDownloadedScenarioSnapshot(server: OfflineCountryServer): RecoverySnapshot {
        val existingIndex = store.readIndex()
        val manifest = runCatching { server.fetchManifest() }.getOrNull()
        val readable = manifest?.readableCountries.orEmpty()
        val existingRecords = (existingIndex as? OfflineStoreList.Holding)?.items.orEmpty()
        val records = if (existingRecords.isNotEmpty()) {
            existingRecords
        } else {
            val slovenia = readable.firstOrNull { it.id == SEEDED_DOWNLOADED_COUNTRY_ID }
            val seeded = if (slovenia != null) {
                listOf(
                    InstalledCountryRecord(
                        country = slovenia,
                        installedAtEpochMillis = 0L,
                        hasDetail = true,
                        hasElevation = slovenia.files.any { it.role.isElevation }
                    )
                )
            } else {
                emptyList()
            }
            if (seeded.isNotEmpty()) {
                runCatching { store.writeInstalled(seeded) }
            }
            seeded
        }
        return RecoverySnapshot(
            holdings = OfflineHoldingsState.READY,
            installed = records.associateBy { it.country.id },
            unopenable = emptyMap(),
            cachedOffered = readable,
            pending = emptyList(),
            recoveredDownloads = emptyMap(),
            readyToFinishIds = emptyList()
        )
    }

    private fun reopenArchives(records: List<InstalledCountryRecord>): Map<String, Set<OfflineFileRole>> =
        if (launchConfigHolder.current.value.offlineCountriesScenarioName != null) {
            emptyMap()
        } else {
            archives.reopen(records, store)
        }

    private fun isAlreadyInstalled(
        item: PendingCountryDownload,
        installedById: Map<String, InstalledCountryRecord>,
        unopenable: Map<String, Set<OfflineFileRole>>
    ): Boolean {
        val record = installedById[item.country.id]
        val sameBuild = record != null && record.country.build == item.country.build
        val heldNames = record?.files?.map { it.fileName }?.toSet().orEmpty()
        val failed = unopenable[item.country.id].orEmpty()
        return sameBuild &&
            item.files.all { file ->
                file.fileName in heldNames &&
                    file.role !in failed &&
                    !store.stagedFileFor(file).exists() &&
                    !store.partialFileFor(file).exists()
            }
    }

    private fun unreadableSnapshot(): RecoverySnapshot = RecoverySnapshot(
        holdings = OfflineHoldingsState.UNREADABLE,
        installed = emptyMap(),
        unopenable = emptyMap(),
        cachedOffered = emptyList(),
        pending = emptyList(),
        recoveredDownloads = emptyMap(),
        readyToFinishIds = emptyList()
    )

    private fun recoverPendingItem(item: PendingCountryDownload): Pair<OfflineCountryDownload, Boolean> {
        val files = item.files
        val staged = files.filter { file ->
            val disk = store.stagedFileFor(file)
            disk.isFile && disk.length() == file.byteCount
        }.map { it.role }.toSet()
        val written = files.sumOf { file ->
            val disk = store.stagedFileFor(file)
            val part = store.partialFileFor(file)
            when {
                disk.isFile -> disk.length().coerceAtMost(file.byteCount)
                part.isFile -> part.length().coerceAtMost(file.byteCount)
                else -> 0L
            }
        }
        val isStaged = files.isNotEmpty() && files.all { it.role in staged }
        val download = OfflineCountryDownload(
            country = item.country,
            roles = item.roles,
            phase = if (isStaged) OfflineDownloadPhase.CHECKING else OfflineDownloadPhase.FAILED,
            failure = if (isStaged) null else OfflineDownloadFailure.CONNECTION,
            writtenBytes = written,
            totalBytes = files.sumOf { it.byteCount },
            stagedRoles = staged,
            inFlightRoles = emptySet()
        )
        return download to isStaged
    }

    companion object {
        const val SCENARIO_DOWNLOADED = "downloaded"
        const val SEEDED_DOWNLOADED_COUNTRY_ID = "SI"
    }
}
