package app.openhikes.core.location

import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.model.RecordingPauseInterval
import app.openhikes.core.model.RecordingPoint
import app.openhikes.core.model.TrackJournalMetadata
import app.openhikes.core.model.TrackJournalSession
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Append-only, crash-safe track journal writing fixed-width binary track points (`recording.otrk`)
 * alongside an atomic JSON metadata file (`recording.json`).
 */
@Suppress("TooManyFunctions")
class TrackJournal(
    val directory: File,
    private val clock: OpenHikesClock,
    private val ioDispatcher: CoroutineDispatcher,
    private val handleFactory: TrackJournalHandleFactory = TrackJournalHandleFactory { file ->
        FileTrackJournalHandle(file)
    },
    private val flushScope: CoroutineScope? = null
) {
    val journalFile: File = File(directory, JOURNAL_FILE_NAME)
    val metadataFile: File = File(directory, METADATA_FILE_NAME)

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val flushFailureFlow = MutableSharedFlow<TrackJournalException>(
        extraBufferCapacity = FLUSH_FAILURE_BUFFER_CAPACITY
    )
    val backgroundFlushFailures: Flow<TrackJournalException> = flushFailureFlow.asSharedFlow()

    private var fileHandle: TrackJournalHandle? = null
    private var committedByteCount: Long = 0L
    private var hasTornTail: Boolean = false
    private val pending = ArrayList<RecordingPoint>()
    private var metadata: TrackJournalMetadata? = null
    private var lastFlushAtMillis: Long? = null
    private var scheduledFlushJob: Job? = null

    suspend fun start(
        sessionId: String,
        startedAtEpochMillis: Long,
        title: String? = null,
        carryingPoints: List<RecordingPoint> = emptyList()
    ) = mutex.withLock {
        withContext(ioDispatcher) {
            runIo {
                closeHandleQuietly()
                directory.mkdirs()
                if (journalFile.exists()) journalFile.delete()
                if (metadataFile.exists()) metadataFile.delete()

                writeAtomicFile(journalFile, TrackJournalCodec.encodeHeader(sessionId, startedAtEpochMillis))
                val newHandle = handleFactory.open(journalFile)
                fileHandle = newHandle
                committedByteCount = newHandle.seekToEnd()
                hasTornTail = false

                val nowMillis = clock.now().toEpochMilli()
                val newMetadata = TrackJournalMetadata(
                    sessionId = sessionId,
                    startedAtEpochMillis = startedAtEpochMillis,
                    endedAtEpochMillis = null,
                    lastUpdatedAtEpochMillis = nowMillis,
                    pausedIntervals = emptyList(),
                    title = title
                )
                metadata = newMetadata
                pending.clear()
                pending.addAll(carryingPoints)
                lastFlushAtMillis = nowMillis
                cancelScheduledFlush()
                writeMetadata(newMetadata)
                flushInternal()
            }
        }
    }

    suspend fun append(point: RecordingPoint) = mutex.withLock {
        withContext(ioDispatcher) {
            runIo {
                val wasEmpty = pending.isEmpty()
                pending.add(point)
                val nowMillis = clock.now().toEpochMilli()
                val elapsedMillis = lastFlushAtMillis?.let { nowMillis - it } ?: Long.MAX_VALUE
                when {
                    pending.size >= BATCH_SIZE || elapsedMillis >= MAX_FLUSH_INTERVAL_MILLIS -> flushInternal()
                    wasEmpty || scheduledFlushJob?.isActive != true -> {
                        val remainingMillis = (MAX_FLUSH_INTERVAL_MILLIS - elapsedMillis).coerceAtLeast(1L)
                        scheduleFlush(remainingMillis)
                    }
                }
            }
        }
    }

    suspend fun pause(atEpochMillis: Long) = mutex.withLock {
        withContext(ioDispatcher) {
            runIo { pauseInternal(atEpochMillis) }
        }
    }

    suspend fun pauseDiscardingPointsAfter(atEpochMillis: Long, cutoffEpochMillis: Long) = mutex.withLock {
        withContext(ioDispatcher) {
            runIo {
                flushInternal()
                discardRecordsAfter(cutoffEpochMillis)
                pauseInternal(atEpochMillis)
            }
        }
    }

    suspend fun resume(atEpochMillis: Long) = mutex.withLock {
        withContext(ioDispatcher) {
            runIo {
                flushInternal()
                val current = metadata ?: throw TrackJournalException.Io("No recording session is open.")
                val intervals = current.pausedIntervals.toMutableList()
                val lastIdx = intervals.lastIndex
                if (lastIdx >= 0 && intervals[lastIdx].endedAtEpochMillis == null) {
                    intervals[lastIdx] = intervals[lastIdx].copy(endedAtEpochMillis = atEpochMillis)
                }
                val updated = current.copy(
                    pausedIntervals = intervals,
                    endedAtEpochMillis = null,
                    customName = null,
                    lastUpdatedAtEpochMillis = clock.now().toEpochMilli()
                )
                metadata = updated
                writeMetadata(updated)
            }
        }
    }

    suspend fun finish(atEpochMillis: Long, title: String? = null, customName: String? = null) = mutex.withLock {
        withContext(ioDispatcher) {
            runIo {
                flushInternal()
                val current = metadata ?: throw TrackJournalException.Io("No recording session is open.")
                val intervals = current.pausedIntervals.toMutableList()
                val lastIdx = intervals.lastIndex
                if (lastIdx >= 0 && intervals[lastIdx].endedAtEpochMillis == null) {
                    intervals[lastIdx] = intervals[lastIdx].copy(endedAtEpochMillis = atEpochMillis)
                }
                val updated = current.copy(
                    pausedIntervals = intervals,
                    endedAtEpochMillis = atEpochMillis,
                    title = title ?: current.title,
                    customName = customName,
                    lastUpdatedAtEpochMillis = clock.now().toEpochMilli()
                )
                metadata = updated
                writeMetadata(updated)
                closeHandle()
            }
        }
    }

    suspend fun flush() = mutex.withLock {
        withContext(ioDispatcher) {
            runIo { flushInternal() }
        }
    }

    suspend fun loadSession(): TrackJournalSession? = mutex.withLock {
        withContext(ioDispatcher) {
            runIo {
                if (fileHandle != null) {
                    flushInternal()
                }
                if (!journalFile.exists() || !metadataFile.exists()) return@runIo null
                val loadedMeta = json.decodeFromString(TrackJournalMetadata.serializer(), metadataFile.readText())
                val journalBytes = journalFile.readBytes()
                val header = TrackJournalCodec.decodeHeader(journalBytes)
                if (!TrackJournalCodec.headerMatchesMetadata(
                        header,
                        loadedMeta.sessionId,
                        loadedMeta.startedAtEpochMillis
                    )
                ) {
                    throw TrackJournalException.InvalidHeader("Journal header does not match metadata")
                }
                TrackJournalSession(
                    metadata = loadedMeta,
                    points = TrackJournalCodec.decodeRecords(journalBytes)
                )
            }
        }
    }

    suspend fun reopenForAppending() = mutex.withLock {
        withContext(ioDispatcher) {
            runIo {
                if (fileHandle != null) return@runIo
                if (!journalFile.exists() || !metadataFile.exists()) {
                    throw TrackJournalException.Io("Missing journal files for recovery")
                }
                val reopenedMeta = json.decodeFromString(TrackJournalMetadata.serializer(), metadataFile.readText())
                val journalBytes = journalFile.readBytes()
                val header = TrackJournalCodec.decodeHeader(journalBytes)
                if (!TrackJournalCodec.headerMatchesMetadata(
                        header,
                        reopenedMeta.sessionId,
                        reopenedMeta.startedAtEpochMillis
                    )
                ) {
                    throw TrackJournalException.InvalidHeader("Journal header does not match metadata")
                }

                val handle = handleFactory.open(journalFile)
                try {
                    val fileSize = handle.seekToEnd()
                    val completeSize = TrackJournalCodec.HEADER_BYTE_COUNT +
                        TrackJournalCodec.pointCount(fileSize).toLong() * TrackJournalCodec.RECORD_BYTE_COUNT
                    if (fileSize != completeSize) {
                        handle.truncate(completeSize)
                    }
                    committedByteCount = handle.seekToEnd()
                    hasTornTail = false
                    fileHandle = handle
                    metadata = reopenedMeta
                    lastFlushAtMillis = clock.now().toEpochMilli()
                } catch (error: IOException) {
                    runCatching { handle.close() }
                    throw error
                } catch (error: SecurityException) {
                    runCatching { handle.close() }
                    throw error
                }
            }
        }
    }

    suspend fun discard() = mutex.withLock {
        withContext(ioDispatcher) {
            runIo { discardInternal() }
        }
    }

    suspend fun discardIfSessionIs(sessionId: String) = mutex.withLock {
        withContext(ioDispatcher) {
            runIo {
                if (metadata?.sessionId == sessionId) {
                    discardInternal()
                }
            }
        }
    }

    suspend fun close() = mutex.withLock {
        withContext(ioDispatcher) {
            runIo {
                flushInternal()
                closeHandle()
            }
        }
    }

    private fun pauseInternal(atEpochMillis: Long) {
        flushInternal()
        val current = metadata ?: throw TrackJournalException.Io("No recording session is open.")
        val intervals = current.pausedIntervals.toMutableList()
        if (intervals.isEmpty() || intervals.last().endedAtEpochMillis != null) {
            intervals.add(RecordingPauseInterval(startedAtEpochMillis = atEpochMillis, endedAtEpochMillis = null))
        }
        val updated = current.copy(
            pausedIntervals = intervals,
            lastUpdatedAtEpochMillis = clock.now().toEpochMilli()
        )
        metadata = updated
        writeMetadata(updated)
    }

    private fun flushInternal(fromScheduledFlush: Boolean = false) {
        if (fromScheduledFlush) {
            scheduledFlushJob = null
        } else {
            cancelScheduledFlush()
        }
        if (pending.isEmpty()) return
        val handle = fileHandle ?: throw TrackJournalException.Io("The recording journal is closed.")
        val bytes = TrackJournalCodec.encodePoints(pending)
        appendRecords(bytes, handle)
        pending.clear()
        val nowMillis = clock.now().toEpochMilli()
        lastFlushAtMillis = nowMillis
        metadata?.let { current ->
            val updated = current.copy(lastUpdatedAtEpochMillis = nowMillis)
            metadata = updated
            writeMetadata(updated)
        }
    }

    private fun scheduleFlush(delayMillis: Long) {
        val targetScope = flushScope ?: return
        scheduledFlushJob?.cancel()
        scheduledFlushJob = targetScope.launch {
            delay(delayMillis)
            runScheduledFlush()
        }
    }

    private suspend fun runScheduledFlush() {
        try {
            mutex.withLock {
                withContext(ioDispatcher) {
                    currentCoroutineContext().ensureActive()
                    if (pending.isNotEmpty() && fileHandle != null) {
                        runIo { flushInternal(fromScheduledFlush = true) }
                    } else {
                        scheduledFlushJob = null
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: TrackJournalException) {
            scheduledFlushJob = null
            flushFailureFlow.tryEmit(error)
        }
    }

    private fun cancelScheduledFlush() {
        scheduledFlushJob?.cancel()
        scheduledFlushJob = null
    }

    private fun appendRecords(bytes: ByteArray, handle: TrackJournalHandle) {
        if (hasTornTail) {
            handle.truncate(committedByteCount)
            hasTornTail = false
        }
        try {
            handle.write(bytes)
            handle.synchronize()
        } catch (error: IOException) {
            try {
                handle.truncate(committedByteCount)
            } catch (_: IOException) {
                hasTornTail = true
            }
            throw error
        } catch (error: SecurityException) {
            try {
                handle.truncate(committedByteCount)
            } catch (_: IOException) {
                hasTornTail = true
            }
            throw error
        }
        committedByteCount += bytes.size
    }

    private fun discardRecordsAfter(cutoffEpochMillis: Long) {
        val handle = fileHandle ?: throw TrackJournalException.Io("The recording journal is closed.")
        val bytes = journalFile.readBytes()
        val points = TrackJournalCodec.decodeRecords(bytes)
        val firstDiscardedIndex = points.indexOfFirst { it.timestampEpochMillis > cutoffEpochMillis }
        if (firstDiscardedIndex < 0) return

        val kept = ByteArrayOutputStream()
        for (index in firstDiscardedIndex until points.size) {
            if (points[index].timestampEpochMillis <= cutoffEpochMillis) {
                val offset = TrackJournalCodec.HEADER_BYTE_COUNT + index * TrackJournalCodec.RECORD_BYTE_COUNT
                kept.write(bytes, offset, TrackJournalCodec.RECORD_BYTE_COUNT)
            }
        }
        val cutOffset = TrackJournalCodec.HEADER_BYTE_COUNT +
            firstDiscardedIndex.toLong() * TrackJournalCodec.RECORD_BYTE_COUNT
        handle.truncate(cutOffset)
        committedByteCount = cutOffset
        hasTornTail = false
        val keptBytes = kept.toByteArray()
        if (keptBytes.isEmpty()) {
            handle.synchronize()
        } else {
            appendRecords(keptBytes, handle)
        }
    }

    private fun discardInternal() {
        cancelScheduledFlush()
        closeHandle()
        if (journalFile.exists() && !journalFile.delete()) {
            throw TrackJournalException.Io("Failed to delete track journal file")
        }
        if (metadataFile.exists() && !metadataFile.delete()) {
            throw TrackJournalException.Io("Failed to delete journal metadata file")
        }
        pending.clear()
        metadata = null
        lastFlushAtMillis = null
        committedByteCount = 0L
        hasTornTail = false
    }

    private fun writeMetadata(targetMetadata: TrackJournalMetadata) {
        val encoded = json.encodeToString(TrackJournalMetadata.serializer(), targetMetadata)
        writeAtomicFile(metadataFile, encoded.toByteArray(Charsets.UTF_8))
    }

    private fun writeAtomicFile(target: File, content: ByteArray) {
        val tempFile = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tempFile).use { stream ->
            stream.write(content)
            stream.flush()
            stream.fd.sync()
        }
        try {
            Files.move(
                tempFile.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Throwable) {
            Files.move(tempFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun closeHandle() {
        cancelScheduledFlush()
        fileHandle?.close()
        fileHandle = null
    }

    private fun closeHandleQuietly() {
        cancelScheduledFlush()
        runCatching { fileHandle?.close() }
        fileHandle = null
    }

    private inline fun <T> runIo(block: () -> T): T = try {
        block()
    } catch (journalError: TrackJournalException) {
        throw journalError
    } catch (error: IOException) {
        throw TrackJournalException.Io(error.message ?: "Journal IO failed", error)
    } catch (error: SecurityException) {
        throw TrackJournalException.Io(error.message ?: "Journal access denied", error)
    } catch (error: SerializationException) {
        throw TrackJournalException.Io(error.message ?: "Corrupt journal metadata", error)
    } catch (error: IllegalArgumentException) {
        throw TrackJournalException.Io(error.message ?: "Invalid journal data", error)
    }

    companion object {
        const val JOURNAL_FILE_NAME = "recording.otrk"
        const val METADATA_FILE_NAME = "recording.json"
        const val BATCH_SIZE = 10
        const val MAX_FLUSH_INTERVAL_MILLIS = 5_000L
        private const val FLUSH_FAILURE_BUFFER_CAPACITY = 4
    }
}
