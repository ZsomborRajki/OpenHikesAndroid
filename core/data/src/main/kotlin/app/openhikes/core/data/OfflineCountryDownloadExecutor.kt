package app.openhikes.core.data

import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.common.offline.OfflineCountryDigest
import app.openhikes.core.common.offline.OfflineCountryStore
import app.openhikes.core.model.OfflineCountryDownload
import app.openhikes.core.model.OfflineCountryFile
import app.openhikes.core.model.OfflineDownloadFailure
import app.openhikes.core.model.OfflineDownloadPhase
import app.openhikes.core.model.OfflineFileRole
import app.openhikes.core.model.TileNetworkDecision
import app.openhikes.core.model.TileNetworkDenialReason
import app.openhikes.core.model.TileNetworkPurpose
import app.openhikes.core.network.OfflineCountryServer
import app.openhikes.core.network.OfflineFileTransferStatus
import app.openhikes.core.network.TileNetworkPolicy
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Executes resumable, policy-aware file transfers and SHA-256 verification for a single queued
 * offline country download, delegating atomic disk installation back to the repository lock.
 */
@Singleton
internal class OfflineCountryDownloadExecutor @Inject constructor(
    private val store: OfflineCountryStore,
    private val server: OfflineCountryServer,
    private val tileNetworkPolicy: TileNetworkPolicy,
    private val clock: OpenHikesClock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    internal var maxAttemptBytes: Long = DEFAULT_MAX_ATTEMPT_BYTES
    internal var maxAttemptDurationMillis: Long = DEFAULT_MAX_ATTEMPT_DURATION_MILLIS
    internal var verifyFile: suspend (OfflineCountryFile, File) -> Boolean = { file, staged ->
        OfflineCountryDigest.isWhole(file, staged)
    }

    internal interface Callbacks {
        fun currentDownload(countryId: String): OfflineCountryDownload?
        fun updateDownload(
            countryId: String,
            attemptId: Long,
            transform: (OfflineCountryDownload) -> OfflineCountryDownload
        )
        fun failDownload(countryId: String, attemptId: Long, failure: OfflineDownloadFailure)
        suspend fun failVerificationDamaged(
            countryId: String,
            download: OfflineCountryDownload,
            damagedFiles: List<OfflineCountryFile>
        ): OfflineDownloadExecutionResult
        suspend fun installAndCommit(
            countryId: String,
            download: OfflineCountryDownload,
            files: List<OfflineCountryFile>,
            installedAtEpochMillis: Long
        ): OfflineDownloadExecutionResult
    }

    suspend fun execute(countryId: String, callbacks: Callbacks): OfflineDownloadExecutionResult {
        val download = callbacks.currentDownload(countryId)
        val preflight = if (download != null) {
            checkSpeculativePolicy(countryId, download.attemptId, callbacks)
        } else {
            null
        }
        return when {
            download == null -> OfflineDownloadExecutionResult.CANCELLED
            preflight != null -> preflight
            download.files.isEmpty() -> {
                callbacks.failDownload(countryId, download.attemptId, OfflineDownloadFailure.DAMAGED)
                OfflineDownloadExecutionResult.FAILED
            }

            else -> executeTransfersAndInstall(countryId, download, callbacks)
        }
    }

    private suspend fun executeTransfersAndInstall(
        countryId: String,
        download: OfflineCountryDownload,
        callbacks: Callbacks
    ): OfflineDownloadExecutionResult {
        val transferResult = downloadAllFiles(countryId, download, callbacks)
        if (transferResult != null) return transferResult
        val latest = callbacks.currentDownload(countryId)
        return if (latest != null && latest.attemptId == download.attemptId) {
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
        callbacks.updateDownload(countryId, download.attemptId) { current ->
            current.copy(
                phase = OfflineDownloadPhase.CHECKING,
                failure = null,
                writtenBytes = current.totalBytes,
                stagedRoles = current.roles.toSet(),
                inFlightRoles = emptySet()
            )
        }
        val files = download.files
        val damagedFiles = withContext(ioDispatcher) { findDamagedStagedFiles(files) }
        val liveDownload = callbacks.currentDownload(countryId)
        val isCurrent = liveDownload != null &&
            liveDownload.attemptId == download.attemptId &&
            liveDownload.country.build == download.country.build &&
            liveDownload.roles == download.roles
        return when {
            !isCurrent -> OfflineDownloadExecutionResult.CANCELLED
            damagedFiles.isNotEmpty() -> callbacks.failVerificationDamaged(
                countryId = countryId,
                download = download,
                damagedFiles = damagedFiles
            )

            else -> callbacks.installAndCommit(
                countryId = countryId,
                download = download,
                files = files,
                installedAtEpochMillis = clock.nowEpochMillis()
            )
        }
    }

    private fun checkSpeculativePolicy(
        countryId: String,
        attemptId: Long,
        callbacks: Callbacks
    ): OfflineDownloadExecutionResult? {
        val decision = tileNetworkPolicy.decide(TileNetworkPurpose.SPECULATIVE)
        if (decision is TileNetworkDecision.Allowed) return null
        val reason = (decision as TileNetworkDecision.Denied).reason
        return when (reason) {
            TileNetworkDenialReason.LOW_POWER_MODE,
            TileNetworkDenialReason.THERMAL -> {
                callbacks.failDownload(countryId, attemptId, OfflineDownloadFailure.CONSERVING)
                OfflineDownloadExecutionResult.FAILED
            }

            TileNetworkDenialReason.OFFLINE,
            TileNetworkDenialReason.LOW_DATA_MODE,
            TileNetworkDenialReason.CELLULAR_SPECULATIVE -> {
                callbacks.updateDownload(countryId, attemptId) {
                    it.copy(phase = OfflineDownloadPhase.WAITING_FOR_WIFI)
                }
                OfflineDownloadExecutionResult.RETRY_LATER
            }
        }
    }

    private class TransferProgress(
        val attemptId: Long,
        val bytesByRole: MutableMap<OfflineFileRole, Long>,
        val stagedRoles: MutableSet<OfflineFileRole>,
        val attemptStartMonotonicMillis: Long,
        var bytesTransferredInAttempt: Long = 0L,
        var lastEmitAt: Long = 0L
    )

    private data class TransferAttemptOutcome(val status: OfflineFileTransferStatus, val policyStopped: Boolean)

    private suspend fun downloadAllFiles(
        countryId: String,
        download: OfflineCountryDownload,
        callbacks: Callbacks
    ): OfflineDownloadExecutionResult? {
        val files = download.files
        val progress = initializeTransferProgress(download.attemptId, files)
        var interruption: OfflineDownloadExecutionResult? = null
        for (file in files.filterNot { it.role in progress.stagedRoles }) {
            interruption = transferOneFile(countryId, file, progress, callbacks)
            if (interruption != null) break
            progress.stagedRoles.add(file.role)
            progress.bytesByRole[file.role] = file.byteCount
        }
        return interruption
    }

    private fun initializeTransferProgress(attemptId: Long, files: List<OfflineCountryFile>): TransferProgress {
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
        return TransferProgress(
            attemptId = attemptId,
            bytesByRole = bytesByRole,
            stagedRoles = stagedRoles,
            attemptStartMonotonicMillis = clock.monotonicMillis()
        )
    }

    private suspend fun transferOneFile(
        countryId: String,
        file: OfflineCountryFile,
        progress: TransferProgress,
        callbacks: Callbacks
    ): OfflineDownloadExecutionResult? {
        val live = callbacks.currentDownload(countryId)
        val preflight = when {
            live == null || live.attemptId != progress.attemptId -> OfflineDownloadExecutionResult.CANCELLED
            !hasAttemptBudget(progress) -> OfflineDownloadExecutionResult.CONTINUE_SLICE
            else -> checkSpeculativePolicy(countryId, progress.attemptId, callbacks)
        }
        if (preflight != null) return preflight
        callbacks.updateDownload(countryId, progress.attemptId) { current ->
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
        val outcome = streamWithPolicyWatch(countryId, file, progress, callbacks)
        resolveTransferOutcome(countryId, progress, outcome, callbacks)
    } catch (ex: CancellationException) {
        throw ex
    } catch (_: IOException) {
        if (callbacks.currentDownload(countryId)?.attemptId != progress.attemptId) {
            OfflineDownloadExecutionResult.CANCELLED
        } else {
            checkSpeculativePolicy(countryId, progress.attemptId, callbacks) ?: run {
                callbacks.failDownload(countryId, progress.attemptId, OfflineDownloadFailure.CONNECTION)
                OfflineDownloadExecutionResult.FAILED
            }
        }
    }

    private suspend fun streamWithPolicyWatch(
        countryId: String,
        file: OfflineCountryFile,
        progress: TransferProgress,
        callbacks: Callbacks
    ): TransferAttemptOutcome {
        val policyStopped = AtomicBoolean(false)
        val remainingBudget = (maxAttemptBytes - progress.bytesTransferredInAttempt).coerceAtLeast(1L)
        return coroutineScope {
            val transferJob = async {
                server.downloadFile(
                    file = file,
                    partialFile = store.partialFileFor(file),
                    destinationFile = store.stagedFileFor(file),
                    maxBytesToTransfer = remainingBudget,
                    shouldContinue = {
                        !policyStopped.get() &&
                            callbacks.currentDownload(countryId)?.attemptId == progress.attemptId &&
                            hasAttemptBudget(progress) &&
                            tileNetworkPolicy.decide(TileNetworkPurpose.SPECULATIVE).isAllowed
                    }
                ) { fileWritten ->
                    recordFileProgress(countryId, file, fileWritten, progress, callbacks)
                }
            }
            val watcherJob = launch {
                tileNetworkPolicy.conditions.collect {
                    if (!tileNetworkPolicy.decide(TileNetworkPurpose.SPECULATIVE).isAllowed) {
                        policyStopped.set(true)
                        transferJob.cancel()
                    }
                }
            }
            try {
                TransferAttemptOutcome(status = transferJob.await(), policyStopped = policyStopped.get())
            } catch (ex: CancellationException) {
                if (policyStopped.get()) {
                    TransferAttemptOutcome(status = OfflineFileTransferStatus.INTERRUPTED, policyStopped = true)
                } else {
                    throw ex
                }
            } catch (ex: IOException) {
                if (policyStopped.get() || !tileNetworkPolicy.decide(TileNetworkPurpose.SPECULATIVE).isAllowed) {
                    TransferAttemptOutcome(status = OfflineFileTransferStatus.INTERRUPTED, policyStopped = true)
                } else {
                    throw ex
                }
            } finally {
                watcherJob.cancel()
            }
        }
    }

    private fun resolveTransferOutcome(
        countryId: String,
        progress: TransferProgress,
        outcome: TransferAttemptOutcome,
        callbacks: Callbacks
    ): OfflineDownloadExecutionResult? = when {
        callbacks.currentDownload(countryId)?.attemptId != progress.attemptId ->
            OfflineDownloadExecutionResult.CANCELLED

        outcome.policyStopped -> checkSpeculativePolicy(countryId, progress.attemptId, callbacks) ?: run {
            callbacks.updateDownload(countryId, progress.attemptId) {
                it.copy(
                    phase = OfflineDownloadPhase.DOWNLOADING,
                    writtenBytes = progress.bytesByRole.values.sum(),
                    inFlightRoles = emptySet()
                )
            }
            OfflineDownloadExecutionResult.CONTINUE_SLICE
        }

        outcome.status != OfflineFileTransferStatus.COMPLETE -> {
            callbacks.updateDownload(countryId, progress.attemptId) {
                it.copy(
                    phase = OfflineDownloadPhase.DOWNLOADING,
                    writtenBytes = progress.bytesByRole.values.sum(),
                    inFlightRoles = emptySet()
                )
            }
            OfflineDownloadExecutionResult.CONTINUE_SLICE
        }

        else -> null
    }

    private fun recordFileProgress(
        countryId: String,
        file: OfflineCountryFile,
        fileWritten: Long,
        progress: TransferProgress,
        callbacks: Callbacks
    ) {
        val clamped = fileWritten.coerceAtMost(file.byteCount)
        val previous = progress.bytesByRole[file.role] ?: 0L
        progress.bytesTransferredInAttempt += (clamped - previous).coerceAtLeast(0L)
        progress.bytesByRole[file.role] = clamped
        val now = clock.nowEpochMillis()
        if (now - progress.lastEmitAt >= PROGRESS_THROTTLE_MILLIS || clamped >= file.byteCount) {
            progress.lastEmitAt = now
            callbacks.updateDownload(countryId, progress.attemptId) { current ->
                current.copy(
                    phase = OfflineDownloadPhase.DOWNLOADING,
                    writtenBytes = progress.bytesByRole.values.sum(),
                    stagedRoles = progress.stagedRoles.toSet(),
                    inFlightRoles = setOf(file.role)
                )
            }
        }
    }

    private fun hasAttemptBudget(progress: TransferProgress): Boolean {
        val elapsedMillis = (clock.monotonicMillis() - progress.attemptStartMonotonicMillis).coerceAtLeast(0L)
        return elapsedMillis < maxAttemptDurationMillis && progress.bytesTransferredInAttempt < maxAttemptBytes
    }

    private suspend fun findDamagedStagedFiles(files: List<OfflineCountryFile>): List<OfflineCountryFile> =
        files.filter { file -> !verifyFile(file, store.stagedFileFor(file)) }

    companion object {
        const val PROGRESS_THROTTLE_MILLIS = 500L
        const val DEFAULT_MAX_ATTEMPT_BYTES = 256L * 1024L * 1024L
        const val DEFAULT_MAX_ATTEMPT_DURATION_MILLIS = 8L * 60L * 1_000L
    }
}
