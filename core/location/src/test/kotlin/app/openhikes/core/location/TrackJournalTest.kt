package app.openhikes.core.location

import app.openhikes.core.model.RecordingPoint
import app.openhikes.core.model.RecordingPointFlags
import app.openhikes.core.testing.TestClock
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TrackJournalTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val clock = TestClock()

    @Test
    fun startAndFlushRoundTripHeaderMetadataAndPoints() = runTest {
        val journal = newJournal(UnconfinedTestDispatcher(testScheduler))
        val sessionId = UUID.randomUUID().toString()
        val startedAt = clock.now().toEpochMilli()

        journal.start(
            sessionId = sessionId,
            startedAtEpochMillis = startedAt,
            title = "Morning Ridge",
            carryingPoints = listOf(
                pointAt(offsetSeconds = 0L, elevation = Double.NaN, speed = Double.POSITIVE_INFINITY)
            )
        )
        journal.append(pointAt(offsetSeconds = 2L, elevation = 540.5, speed = 1.4, flags = RecordingPointFlags.RESUMED))
        journal.flush()

        val loaded = checkNotNull(journal.loadSession())
        assertThat(journal.journalFile.length()).isEqualTo(
            (TrackJournalCodec.HEADER_BYTE_COUNT + 2 * TrackJournalCodec.RECORD_BYTE_COUNT).toLong()
        )
        assertThat(loaded.metadata.sessionId).isEqualTo(sessionId)
        assertThat(loaded.metadata.title).isEqualTo("Morning Ridge")
        assertThat(loaded.points).hasSize(2)
        assertThat(loaded.points[0].elevationMeters).isNull()
        assertThat(loaded.points[0].speedMps).isNull()
        assertThat(loaded.points[1].elevationMeters).isWithin(0.01).of(540.5)
        assertThat(loaded.points[1].hasFlag(RecordingPointFlags.RESUMED)).isTrue()
    }

    @Test
    fun batchAndTimedFlushPersistRecordsWithoutExplicitFlushCall() = runTest {
        val journal = newJournal(UnconfinedTestDispatcher(testScheduler))
        journal.start(sessionId = UUID.randomUUID().toString(), startedAtEpochMillis = clock.now().toEpochMilli())

        repeat(9) { index ->
            journal.append(pointAt(offsetSeconds = index.toLong()))
        }
        assertThat(journal.journalFile.length()).isEqualTo(TrackJournalCodec.HEADER_BYTE_COUNT.toLong())

        journal.append(pointAt(offsetSeconds = 9L))
        assertThat(journal.journalFile.length()).isEqualTo(
            (TrackJournalCodec.HEADER_BYTE_COUNT + 10 * TrackJournalCodec.RECORD_BYTE_COUNT).toLong()
        )

        clock.advanceBy(Duration.ofSeconds(6))
        journal.append(pointAt(offsetSeconds = 15L))
        assertThat(journal.journalFile.length()).isEqualTo(
            (TrackJournalCodec.HEADER_BYTE_COUNT + 11 * TrackJournalCodec.RECORD_BYTE_COUNT).toLong()
        )
    }

    @Test
    fun scheduledFlushPersistsSmallBatchWhenGpsGoesQuiet() = runTest {
        val dir = tempFolder.newFolder("journal-quiet-flush")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val journal = TrackJournal(
            directory = dir,
            clock = clock,
            ioDispatcher = dispatcher,
            flushScope = backgroundScope
        )
        journal.start(sessionId = UUID.randomUUID().toString(), startedAtEpochMillis = clock.now().toEpochMilli())
        journal.append(pointAt(offsetSeconds = 1L))
        journal.append(pointAt(offsetSeconds = 2L))
        runCurrent()
        assertThat(journal.journalFile.length()).isEqualTo(TrackJournalCodec.HEADER_BYTE_COUNT.toLong())

        clock.advanceBy(Duration.ofSeconds(45))
        advanceTimeBy(45_000L)
        runCurrent()

        val readerJournal = TrackJournal(directory = dir, clock = clock, ioDispatcher = dispatcher)
        val loaded = checkNotNull(readerJournal.loadSession())
        assertThat(loaded.points).hasSize(2)
    }

    @Test
    fun writeFailureRollsBackTruncatingPartialBatchAndAllowsRetry() = runTest {
        val failingHandleFactory = FaultyHandleFactory(failNextSynchronize = false)
        val journal = TrackJournal(
            directory = tempFolder.newFolder("journal-rollback"),
            clock = clock,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            handleFactory = failingHandleFactory
        )
        journal.start(sessionId = UUID.randomUUID().toString(), startedAtEpochMillis = clock.now().toEpochMilli())
        journal.append(pointAt(offsetSeconds = 1L))
        journal.flush()

        val committedSize = TrackJournalCodec.HEADER_BYTE_COUNT + TrackJournalCodec.RECORD_BYTE_COUNT
        assertThat(journal.journalFile.length()).isEqualTo(committedSize.toLong())

        failingHandleFactory.failNextSynchronize = true
        journal.append(pointAt(offsetSeconds = 5L))
        assertThrows(TrackJournalException.Io::class.java) {
            kotlinx.coroutines.runBlocking { journal.flush() }
        }
        assertThat(journal.journalFile.length()).isEqualTo(committedSize.toLong())

        journal.flush()
        val loaded = checkNotNull(journal.loadSession())
        assertThat(loaded.points).hasSize(2)
    }

    @Test
    fun reopenForAppendingTruncatesTornTrailingTailBeforeAppending() = runTest {
        val dir = tempFolder.newFolder("journal-torn")
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val firstJournal = TrackJournal(dir, clock, dispatcher)
        val sessionId = UUID.randomUUID().toString()
        val startedAt = clock.now().toEpochMilli()
        firstJournal.start(sessionId = sessionId, startedAtEpochMillis = startedAt)
        firstJournal.append(pointAt(offsetSeconds = 1L))
        firstJournal.flush()
        firstJournal.close()

        RandomAccessFile(firstJournal.journalFile, "rw").use { raf ->
            raf.seek(raf.length())
            raf.write(byteArrayOf(1, 2, 3, 4, 5))
        }

        val recovered = TrackJournal(dir, clock, dispatcher)
        recovered.reopenForAppending()
        recovered.append(pointAt(offsetSeconds = 10L))
        recovered.flush()

        val session = checkNotNull(recovered.loadSession())
        assertThat(session.points).hasSize(2)
        assertThat(recovered.journalFile.length()).isEqualTo(
            (TrackJournalCodec.HEADER_BYTE_COUNT + 2 * TrackJournalCodec.RECORD_BYTE_COUNT).toLong()
        )
    }

    @Test
    fun pauseDiscardingPointsAfterTrimsBackdatedTailAndRecordsPauseInterval() = runTest {
        val journal = newJournal(UnconfinedTestDispatcher(testScheduler))
        val startMillis = clock.now().toEpochMilli()
        journal.start(sessionId = UUID.randomUUID().toString(), startedAtEpochMillis = startMillis)
        journal.append(pointAt(offsetSeconds = 1L))
        journal.append(pointAt(offsetSeconds = 5L))
        journal.append(pointAt(offsetSeconds = 12L))
        journal.append(pointAt(offsetSeconds = 20L))

        val cutoff = startMillis + 6_000L
        journal.pauseDiscardingPointsAfter(atEpochMillis = cutoff, cutoffEpochMillis = cutoff)

        val loaded = checkNotNull(journal.loadSession())
        assertThat(loaded.points).hasSize(2)
        assertThat(
            loaded.points.map {
                it.timestampEpochMillis - startMillis
            }
        ).containsExactly(1_000L, 5_000L).inOrder()
        assertThat(loaded.metadata.pausedIntervals).hasSize(1)
        assertThat(loaded.metadata.pausedIntervals.single().startedAtEpochMillis).isEqualTo(cutoff)
        assertThat(loaded.metadata.pausedIntervals.single().endedAtEpochMillis).isNull()
    }

    @Test
    fun discardAndDiscardIfSessionIsRespectSessionIdentity() = runTest {
        val journal = newJournal(UnconfinedTestDispatcher(testScheduler))
        val sessionId = UUID.randomUUID().toString()
        journal.start(sessionId = sessionId, startedAtEpochMillis = clock.now().toEpochMilli())

        journal.discardIfSessionIs("other-session")
        assertThat(journal.journalFile.exists()).isTrue()

        journal.discardIfSessionIs(sessionId)
        assertThat(journal.journalFile.exists()).isFalse()
        assertThat(journal.metadataFile.exists()).isFalse()
    }

    @Test
    fun loadSessionRejectsInvalidHeaderAndUnsupportedVersion() = runTest {
        val journal = newJournal(UnconfinedTestDispatcher(testScheduler))
        journal.start(sessionId = UUID.randomUUID().toString(), startedAtEpochMillis = clock.now().toEpochMilli())
        journal.close()

        val bytes = journal.journalFile.readBytes()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(4, 99)
        journal.journalFile.writeBytes(bytes)
        assertThrows(TrackJournalException.UnsupportedVersion::class.java) {
            kotlinx.coroutines.runBlocking { journal.loadSession() }
        }

        bytes[0] = 0x00
        journal.journalFile.writeBytes(bytes)
        assertThrows(TrackJournalException.InvalidHeader::class.java) {
            kotlinx.coroutines.runBlocking { journal.loadSession() }
        }
    }

    private fun newJournal(dispatcher: kotlinx.coroutines.CoroutineDispatcher): TrackJournal = TrackJournal(
        directory = tempFolder.newFolder(),
        clock = clock,
        ioDispatcher = dispatcher
    )

    private fun pointAt(
        offsetSeconds: Long,
        elevation: Double? = 533.0,
        speed: Double? = 1.2,
        flags: Int = RecordingPointFlags.NONE
    ): RecordingPoint = RecordingPoint(
        latitude = 47.71842 + offsetSeconds * 0.0001,
        longitude = 12.83177,
        timestampEpochMillis = clock.now().toEpochMilli() + offsetSeconds * 1_000L,
        horizontalAccuracyMeters = 4.0,
        elevationMeters = elevation,
        courseDegrees = 45.0,
        speedMps = speed,
        flags = flags
    )

    private class FaultyHandleFactory(var failNextSynchronize: Boolean) : TrackJournalHandleFactory {
        override fun open(file: File): TrackJournalHandle {
            val delegate = FileTrackJournalHandle(file)
            return object : TrackJournalHandle {
                override fun seekToEnd(): Long = delegate.seekToEnd()

                override fun write(bytes: ByteArray) = delegate.write(bytes)

                override fun synchronize() {
                    if (failNextSynchronize) {
                        failNextSynchronize = false
                        throw IOException("Injected fsync failure")
                    }
                    delegate.synchronize()
                }

                override fun truncate(length: Long) = delegate.truncate(length)

                override fun close() = delegate.close()
            }
        }
    }
}
