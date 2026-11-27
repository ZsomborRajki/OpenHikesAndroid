package app.openhikes.core.data

import app.openhikes.core.common.IoDispatcher
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Startup recovery, orphan sweep, and Extra Detail reconciliation helper for
 * [DefaultOfflineCountryRepository].
 */
@Singleton
internal class OfflineCountryReconciler @Inject constructor(
    private val store: OfflineCountryStore,
    private val archives: OfflineCountryArchives,
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

    suspend fun loadInitialSnapshot(): RecoverySnapshot = withContext(ioDispatcher) {
        val indexList = store.readIndex()
        val pendingList = store.readPending()
        if (indexList is OfflineStoreList.Unreadable || pendingList is OfflineStoreList.Unreadable) {
            return@withContext unreadableSnapshot()
        }
        val records = (indexList as OfflineStoreList.Holding).items
        val pending = (pendingList as OfflineStoreList.Holding).items
        val unopenable = archives.reopen(records, store)
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
        RecoverySnapshot(
            holdings = OfflineHoldingsState.READY,
            installed = records.associateBy { it.country.id },
            unopenable = unopenable,
            cachedOffered = cachedOffered,
            pending = pending,
            recoveredDownloads = recoveredDownloads,
            readyToFinishIds = readyToFinishIds
        )
    }

    sealed interface InstallOutcome {
        data class Installed(
            val nextInstalled: Map<String, InstalledCountryRecord>,
            val unopenable: Map<String, Set<OfflineFileRole>>
        ) : InstallOutcome

        data object FailedSpace : InstallOutcome
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
        val newRecord = InstalledCountryRecord(
            country = download.country,
            installedAtEpochMillis = installedAtEpochMillis,
            hasDetail = mergedHasDetail
        )
        if (runCatching { store.install(files) }.isFailure) {
            return@withContext InstallOutcome.FailedSpace
        }
        val nextInstalled = currentInstalled + (countryId to newRecord)
        if (runCatching { store.writeInstalled(nextInstalled.values.toList()) }.isFailure) {
            store.unstage(files, keeping = currentInstalled.values.toList())
            return@withContext InstallOutcome.FailedSpace
        }
        val reopenedUnopenable = archives.reopen(nextInstalled.values.toList(), store)
        if (previous != null) {
            store.remove(previous.files, keeping = nextInstalled.values.toList())
        }
        InstallOutcome.Installed(nextInstalled = nextInstalled, unopenable = reopenedUnopenable)
    }

    suspend fun reopenAndRemove(
        keeping: List<InstalledCountryRecord>,
        removedFiles: List<OfflineCountryFile>
    ): Map<String, Set<OfflineFileRole>> = withContext(ioDispatcher) {
        val broken = archives.reopen(keeping, store)
        store.remove(removedFiles, keeping = keeping)
        broken
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

    /**
     * Strips zoom-15 detail files from installed records when Extra Detail is turned OFF, committing
     * `index.json` atomically before reopening archives and deleting disk files.
     */
    suspend fun stripInstalledDetail(
        state: OfflineCountriesState
    ): Pair<Map<String, InstalledCountryRecord>, Map<String, Set<OfflineFileRole>>> = withContext(ioDispatcher) {
        val updatedRecords = LinkedHashMap<String, InstalledCountryRecord>(state.installed.size)
        val removedFiles = ArrayList<app.openhikes.core.model.OfflineCountryFile>()
        var changed = false
        for ((id, record) in state.installed) {
            if (record.hasDetail && id !in state.downloads) {
                removedFiles.addAll(record.country.files.filter { it.role.isDetail })
                updatedRecords[id] = record.copy(hasDetail = false)
                changed = true
            } else {
                updatedRecords[id] = record
            }
        }
        if (!changed) {
            return@withContext state.installed to state.unopenable
        }
        val written = runCatching { store.writeInstalled(updatedRecords.values.toList()) }.isSuccess
        if (!written) {
            return@withContext state.installed to state.unopenable
        }
        val reopenedUnopenable = archives.reopen(updatedRecords.values.toList(), store)
        store.remove(removedFiles, keeping = updatedRecords.values.toList())
        updatedRecords to reopenedUnopenable
    }

    /**
     * Returns ids of healthy installed areas that should fetch additional files for their current
     * build — either newly listed companion archives (such as elevation squares) or zoom-15 detail
     * files when Extra Detail is turned ON.
     */
    fun findInstalledMissingFiles(state: OfflineCountriesState): List<String> {
        if (state.holdings != OfflineHoldingsState.READY) return emptyList()
        return state.installed.values
            .filter { record ->
                val id = record.country.id
                if (id in state.downloads || state.needsRepair(id)) return@filter false
                val offeredMatch = state.offered.firstOrNull { it.id == id }
                val sameBuildOffered = offeredMatch?.takeIf { it.build == record.country.build }
                when {
                    sameBuildOffered != null -> state.filesToFetch(sameBuildOffered).isNotEmpty()
                    state.includesDetail && !record.hasDetail ->
                        (offeredMatch == null || !state.hasUpdate(offeredMatch)) &&
                            state.filesToFetch(record.country).isNotEmpty()
                    else -> false
                }
            }
            .map { it.country.id }
    }
}
