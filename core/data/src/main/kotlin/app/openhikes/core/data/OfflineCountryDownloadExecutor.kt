package app.openhikes.core.data

import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.common.offline.OfflineCountryDigest
import app.openhikes.core.common.offline.OfflineCountryStore
import app.openhikes.core.model.InstalledCountryRecord
import app.openhikes.core.model.OfflineCountryDownload
import app.openhikes.core.model.OfflineCountryFile
import app.openhikes.core.model.OfflineDownloadFailure
import app.openhikes.core.model.OfflineDownloadPhase
import app.openhikes.core.model.OfflineFileRole
import app.openhikes.core.model.TileNetworkDecision
import app.openhikes.core.model.TileNetworkDenialReason
import app.openhikes.core.model.TileNetworkPurpose
import app.openhikes.core.network.OfflineCountryServer
import app.openhikes.core.network.TileNetworkPolicy
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Executes file transfers, SHA-256 verification, and atomic index installation for a single
 * queued offline country download.
 */
@Singleton
internal class OfflineCountryDownloadExecutor @Inject constructor(
    private val store: OfflineCountryStore,
    private val server: OfflineCountryServer,
    private val tileNetworkPolicy: TileNetworkPolicy,
    private val archives: OfflineCountryArchives,
    private val clock: OpenHikesClock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    internal interface Callbacks {
        fun currentDownload(countryId: String): OfflineCountryDownload?
        fun currentInstalled(): Map<String, InstalledCountryRecord>
        fun updateDownload(countryId: String, transform: (OfflineCountryDownload) -> OfflineCountryDownload)
        fun failDownload(countryId: String, failure: OfflineDownloadFailure)
        suspend fun commitInstalledAndFinish(
            countryId: String,
            download: OfflineCountryDownload,
            newRecord: InstalledCountryRecord,
            previousRecord: InstalledCountryRecord?,
            reopenedUnopenable: Map<String, Set<OfflineFileRole>>
        ): Boolean
    }

    suspend fun execute(countryId: String, callbacks: Callbacks): OfflineDownloadExecutionResult {
        val download = callbacks.currentDownload(countryId)
        val preflight = if (download != null) checkSpeculativePolicy(countryId, callbacks) else null
        return when {
            download == null -> OfflineDownloadExecutionResult.CANCELLED
            preflight != null -> preflight
            download.files.isEmpty() -> {
                callbacks.failDownload(countryId, OfflineDownloadFailure.DAMAGED)
                OfflineDownloadExecutionResult.FAILED
            }

            else -> executeTransfersAndInstall(countryId, download.files, callbacks)
        }
    }

    private suspend fun executeTransfersAndInstall(
        countryId: String,
        files: List<OfflineCountryFile>,
        callbacks: Callbacks
    ): OfflineDownloadExecutionResult {
        val transferResult = downloadAllFiles(countryId, files, callbacks)
        if (transferResult != null) return transferResult
        val latest = callbacks.currentDownload(countryId)
        return if (latest != null) {
            verifyAndInstall(countryId, latest, callbacks)
        } else {
            OfflineDownloadExecutionResult.CANCELLED
        }
    }

    suspend fun verifyAndInstall(
        countryId: String,
        download: OfflineCountryDownload,
        callbacks: Callbacks
    ): OfflineDownloadExecutionResult {
        callbacks.updateDownload(countryId) { current ->
            current.copy(
                phase = OfflineDownloadPhase.CHECKING,
                failure = null,
                writtenBytes = current.totalBytes,
                stagedRoles = current.roles.toSet(),
                inFlightRoles = emptySet()
            )
        }
        val files = download.files
        val allWhole = withContext(ioDispatcher) { verifyStagedFiles(files) }
        if (!allWhole) {
            withContext(ioDispatcher) {
                files.forEach { file ->
                    val staged = store.stagedFileFor(file)
                    if (!OfflineCountryDigest.isWhole(file, staged)) {
                        staged.delete()
                    }
                }
            }
            callbacks.failDownload(countryId, OfflineDownloadFailure.DAMAGED)
            return OfflineDownloadExecutionResult.FAILED
        }
        return installVerifiedFiles(countryId, download, files, callbacks)
    }

    private fun checkSpeculativePolicy(countryId: String, callbacks: Callbacks): OfflineDownloadExecutionResult? {
        val decision = tileNetworkPolicy.decide(TileNetworkPurpose.SPECULATIVE)
        if (decision is TileNetworkDecision.Allowed) return null
        val reason = (decision as TileNetworkDecision.Denied).reason
        return when (reason) {
            TileNetworkDenialReason.LOW_POWER_MODE,
            TileNetworkDenialReason.THERMAL -> {
                callbacks.failDownload(countryId, OfflineDownloadFailure.CONSERVING)
                OfflineDownloadExecutionResult.FAILED
            }

            TileNetworkDenialReason.OFFLINE,
            TileNetworkDenialReason.LOW_DATA_MODE,
            TileNetworkDenialReason.CELLULAR_SPECULATIVE -> {
                callbacks.updateDownload(countryId) { it.copy(phase = OfflineDownloadPhase.WAITING_FOR_WIFI) }
                OfflineDownloadExecutionResult.RETRY_LATER
            }
        }
    }

    private class TransferProgress(
        val bytesByRole: MutableMap<OfflineFileRole, Long>,
        val stagedRoles: MutableSet<OfflineFileRole>,
        var lastEmitAt: Long = 0L
    )

    private suspend fun downloadAllFiles(
        countryId: String,
        files: List<OfflineCountryFile>,
        callbacks: Callbacks
    ): OfflineDownloadExecutionResult? {
        val progress = initializeTransferProgress(files)
        var interruption: OfflineDownloadExecutionResult? = null
        for (file in files.filterNot { it.role in progress.stagedRoles }) {
            interruption = transferOneFile(countryId, file, progress, callbacks)
            if (interruption != null) break
            progress.stagedRoles.add(file.role)
            progress.bytesByRole[file.role] = file.byteCount
        }
        return interruption
    }

    private fun initializeTransferProgress(files: List<OfflineCountryFile>): TransferProgress {
        val bytesByRole = LinkedHashMap<OfflineFileRole, Long>(files.size)
        val stagedRoles = LinkedHashSet<OfflineFileRole>()
        for (file in files) {
            val staged = store.stagedFileFor(file)
            if (staged.isFile && staged.length() == file.byteCount) {
                bytesByRole[file.role] = file.byteCount
                stagedRoles.add(file.role)
            } else {
                val part = store.partialFileFor(file)
                bytesByRole[file.role] = if (part.isFile) part.length().coerceAtMost(file.byteCount) else 0L
            }
        }
        return TransferProgress(bytesByRole = bytesByRole, stagedRoles = stagedRoles)
    }

    private suspend fun transferOneFile(
        countryId: String,
        file: OfflineCountryFile,
        progress: TransferProgress,
        callbacks: Callbacks
    ): OfflineDownloadExecutionResult? {
        val policyBlock = if (callbacks.currentDownload(countryId) == null) {
            OfflineDownloadExecutionResult.CANCELLED
        } else {
            checkSpeculativePolicy(countryId, callbacks)
        }
        if (policyBlock != null) return policyBlock
        callbacks.updateDownload(countryId) { current ->
            current.copy(
                phase = OfflineDownloadPhase.DOWNLOADING,
                failure = null,
                writtenBytes = progress.bytesByRole.values.sum(),
                stagedRoles = progress.stagedRoles.toSet(),
                inFlightRoles = setOf(file.role)
            )
        }
        return downloadSingleFile(countryId, file, progress, callbacks)
    }

    private suspend fun downloadSingleFile(
        countryId: String,
        file: OfflineCountryFile,
        progress: TransferProgress,
        callbacks: Callbacks
    ): OfflineDownloadExecutionResult? = try {
        server.downloadFile(
            file = file,
            partialFile = store.partialFileFor(file),
            destinationFile = store.stagedFileFor(file)
        ) { fileWritten ->
            progress.bytesByRole[file.role] = fileWritten.coerceAtMost(file.byteCount)
            val now = clock.nowEpochMillis()
            if (now - progress.lastEmitAt >= PROGRESS_THROTTLE_MILLIS || fileWritten >= file.byteCount) {
                progress.lastEmitAt = now
                callbacks.updateDownload(countryId) { current ->
                    current.copy(
                        phase = OfflineDownloadPhase.DOWNLOADING,
                        writtenBytes = progress.bytesByRole.values.sum(),
                        stagedRoles = progress.stagedRoles.toSet(),
                        inFlightRoles = setOf(file.role)
                    )
                }
            }
        }
        null
    } catch (ex: CancellationException) {
        throw ex
    } catch (_: IOException) {
        callbacks.failDownload(countryId, OfflineDownloadFailure.CONNECTION)
        OfflineDownloadExecutionResult.FAILED
    }

    private fun verifyStagedFiles(files: List<OfflineCountryFile>): Boolean =
        files.all { file -> OfflineCountryDigest.isWhole(file, store.stagedFileFor(file)) }

    private suspend fun installVerifiedFiles(
        countryId: String,
        download: OfflineCountryDownload,
        files: List<OfflineCountryFile>,
        callbacks: Callbacks
    ): OfflineDownloadExecutionResult = withContext(ioDispatcher) {
        val previous = callbacks.currentInstalled()[countryId]
        val sameBuild = previous != null && previous.country.build == download.country.build
        val mergedHasDetail = download.hasDetail || (sameBuild && previous.hasDetail)
        val newRecord = InstalledCountryRecord(
            country = download.country,
            installedAtEpochMillis = clock.nowEpochMillis(),
            hasDetail = mergedHasDetail
        )
        val moved = runCatching { store.install(files) }.isSuccess
        if (!moved) {
            callbacks.failDownload(countryId, OfflineDownloadFailure.SPACE)
            return@withContext OfflineDownloadExecutionResult.FAILED
        }
        val nextInstalled = callbacks.currentInstalled().toMutableMap().apply {
            put(countryId, newRecord)
        }
        val persisted = runCatching { store.writeInstalled(nextInstalled.values.toList()) }.isSuccess
        if (!persisted) {
            store.unstage(files, keeping = callbacks.currentInstalled().values.toList())
            callbacks.failDownload(countryId, OfflineDownloadFailure.SPACE)
            return@withContext OfflineDownloadExecutionResult.FAILED
        }
        val reopenedUnopenable = archives.reopen(nextInstalled.values.toList(), store)
        if (previous != null) {
            store.remove(previous.files, keeping = nextInstalled.values.toList())
        }
        callbacks.commitInstalledAndFinish(
            countryId = countryId,
            download = download,
            newRecord = newRecord,
            previousRecord = previous,
            reopenedUnopenable = reopenedUnopenable
        )
        OfflineDownloadExecutionResult.COMPLETED
    }

    companion object {
        const val PROGRESS_THROTTLE_MILLIS = 500L
    }
}
