package app.openhikes.core.location

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Low-level append/sync/truncate handle seam for [TrackJournal] so write and fsync failures
 * can be deterministically injected in tests.
 */
interface TrackJournalHandle : Closeable {
    fun seekToEnd(): Long

    fun write(bytes: ByteArray)

    fun synchronize()

    fun truncate(length: Long)
}

/**
 * Opens a [TrackJournalHandle] positioned for append/truncate operations on [file].
 */
fun interface TrackJournalHandleFactory {
    fun open(file: File): TrackJournalHandle
}

/**
 * Production [TrackJournalHandle] backed by [RandomAccessFile] and [java.nio.channels.FileChannel.force].
 */
internal class FileTrackJournalHandle(file: File) : TrackJournalHandle {
    private val raf = RandomAccessFile(file, "rw")

    override fun seekToEnd(): Long {
        val length = raf.length()
        raf.seek(length)
        return length
    }

    override fun write(bytes: ByteArray) {
        raf.write(bytes)
    }

    override fun synchronize() {
        raf.channel.force(true)
    }

    override fun truncate(length: Long) {
        raf.setLength(length)
        raf.seek(length)
    }

    override fun close() {
        raf.close()
    }
}
