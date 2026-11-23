package app.openhikes.core.common.pmtiles

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Random-access byte source underlying a [PMTilesArchive] (local `FileChannel`, in-memory buffer,
 * or HTTP Range reader).
 */
interface PMTilesByteReader : Closeable {
    val size: Long

    /**
     * Reads exactly [length] bytes starting at [offset], throwing [PMTilesException.Truncated] if
     * the range exceeds [size] or cannot be satisfied completely.
     */
    fun read(offset: Long, length: Int): ByteArray
}

/**
 * Thread-safe positional [PMTilesByteReader] backed by a local read-only [FileChannel].
 */
class FileChannelPMTilesByteReader(file: File) : PMTilesByteReader {
    private val channel: FileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
    override val size: Long = channel.size()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0L || length < 0 || !fitsInSize(offset, length.toLong(), size)) {
            throw PMTilesException.Truncated()
        }
        val result = ByteArray(length)
        val buffer = ByteBuffer.wrap(result)
        var position = offset
        while (buffer.hasRemaining()) {
            val readCount = readChunk(buffer, position)
            if (readCount <= 0) throw PMTilesException.Truncated()
            position += readCount
        }
        return result
    }

    private fun readChunk(buffer: ByteBuffer, position: Long): Int = try {
        channel.read(buffer, position)
    } catch (ex: IOException) {
        throw PMTilesException.Truncated("Failed reading PMTiles file: ${ex.message}", ex)
    }

    override fun close() {
        channel.close()
    }
}

/**
 * Pure in-memory [PMTilesByteReader] used for pure-JVM tests and preloaded buffers.
 */
class InMemoryPMTilesByteReader(private val bytes: ByteArray) : PMTilesByteReader {
    override val size: Long = bytes.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0L || length < 0 || !fitsInSize(offset, length.toLong(), size)) {
            throw PMTilesException.Truncated()
        }
        return bytes.copyOfRange(offset.toInt(), (offset + length).toInt())
    }

    override fun close() = Unit
}

/**
 * Random-access PMTiles v3 reader safe for concurrent map render threads.
 *
 * Reads the 127-byte header and root directory on open (in a single initial read when they fit in
 * 16 KiB), caches up to [MAX_KEPT_LEAF_DIRECTORIES] decoded leaf directories in FIFO order, and
 * validates every section and entry range before reading bytes.
 */
class PMTilesArchive private constructor(
    private val reader: PMTilesByteReader,
    val header: PMTilesHeader,
    private val rootEntries: List<PMTilesEntry>
) : Closeable {
    private val lock = Any()
    private val leafCache = LinkedHashMap<Long, List<PMTilesEntry>>()

    /**
     * Returns the unpacked tile bytes at `(z, x, y)`, or `null` when the archive does not contain it.
     */
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        val entry = findEntry(z, x, y) ?: return null
        val raw = readEntryBytes(header.tileDataOffset, entry)
        return PMTilesGzip.unpack(raw, header.tileCompression)
    }

    /**
     * Checks directory membership at `(z, x, y)` without reading or decompressing tile bytes.
     */
    fun contains(z: Int, x: Int, y: Int): Boolean = findEntry(z, x, y) != null

    override fun close() {
        synchronized(lock) {
            leafCache.clear()
            reader.close()
        }
    }

    private fun findEntry(z: Int, x: Int, y: Int): PMTilesEntry? = if (!isValidCoordinate(z, x, y)) {
        null
    } else {
        searchDirectories(PMTilesTileId.id(z = z, x = x, y = y))
    }

    private fun isValidCoordinate(z: Int, x: Int, y: Int): Boolean {
        val zoomInRange = z in header.minimumZoom..header.maximumZoom && z <= PMTilesTileId.MAXIMUM_ZOOM
        if (!zoomInRange) return false
        val limit = 1L shl z
        return x.toLong() in 0L until limit && y.toLong() in 0L until limit
    }

    private fun searchDirectories(tileId: Long): PMTilesEntry? {
        var entries = rootEntries
        repeat(MAX_DIRECTORY_DEPTH) {
            val found = PMTilesDirectory.entryHolding(tileId, entries) ?: return null
            if (!found.isLeaf) return found
            entries = loadLeafDirectory(found)
        }
        throw PMTilesException.TooDeep()
    }

    private fun loadLeafDirectory(entry: PMTilesEntry): List<PMTilesEntry> {
        val absoluteOffset = checkedAdd(header.leafDirectoryOffset, entry.offset)
        synchronized(lock) {
            leafCache[absoluteOffset]?.let { return it }
        }
        val raw = readEntryBytes(header.leafDirectoryOffset, entry)
        val unpacked = PMTilesGzip.unpack(raw, header.internalCompression)
        val decoded = PMTilesDirectory.decode(unpacked)
        synchronized(lock) {
            leafCache[absoluteOffset] = decoded
            if (leafCache.size > MAX_KEPT_LEAF_DIRECTORIES) {
                val oldestKey = leafCache.keys.first()
                leafCache.remove(oldestKey)
            }
        }
        return decoded
    }

    private fun readEntryBytes(sectionBase: Long, entry: PMTilesEntry): ByteArray {
        if (entry.offset < 0L || entry.length < 0L || entry.length > PMTilesGzip.MAX_BODY_BYTES.toLong()) {
            throw PMTilesException.Corrupt("PMTiles entry length out of bounds: ${entry.length}")
        }
        val absoluteOffset = checkedAdd(sectionBase, entry.offset)
        if (!fitsInSize(absoluteOffset, entry.length, reader.size)) {
            throw PMTilesException.Truncated()
        }
        return reader.read(absoluteOffset, entry.length.toInt())
    }

    companion object {
        const val MAX_KEPT_LEAF_DIRECTORIES = 64
        const val MAX_DIRECTORY_DEPTH = 4

        fun open(file: File): PMTilesArchive {
            val reader = FileChannelPMTilesByteReader(file)
            var opened: PMTilesArchive? = null
            try {
                opened = open(reader)
                return opened
            } finally {
                if (opened == null) runCatching { reader.close() }
            }
        }

        fun open(reader: PMTilesByteReader): PMTilesArchive {
            val initialLength = minOf(reader.size, PMTilesHeader.ROOT_READ_BYTES.toLong()).toInt()
            if (initialLength < PMTilesHeader.BYTE_COUNT) throw PMTilesException.Truncated()
            val initialBytes = reader.read(0L, initialLength)
            val header = PMTilesHeader.parse(initialBytes)
            validateHeaderSections(header, reader.size)

            val rootEnd = checkedAdd(header.rootDirectoryOffset, header.rootDirectoryLength)
            val rootBytes = if (rootEnd <= initialBytes.size.toLong()) {
                initialBytes.copyOfRange(header.rootDirectoryOffset.toInt(), rootEnd.toInt())
            } else {
                if (header.rootDirectoryLength > PMTilesGzip.MAX_BODY_BYTES.toLong()) {
                    throw PMTilesException.Corrupt("Root directory exceeds maximum size")
                }
                reader.read(header.rootDirectoryOffset, header.rootDirectoryLength.toInt())
            }
            val unpackedRoot = PMTilesGzip.unpack(rootBytes, header.internalCompression)
            val rootEntries = PMTilesDirectory.decode(unpackedRoot)
            return PMTilesArchive(reader = reader, header = header, rootEntries = rootEntries)
        }

        private fun validateHeaderSections(header: PMTilesHeader, fileSize: Long) {
            val valid = fitsInSize(header.rootDirectoryOffset, header.rootDirectoryLength, fileSize) &&
                fitsInSize(header.leafDirectoryOffset, header.leafDirectoryLength, fileSize) &&
                fitsInSize(header.tileDataOffset, header.tileDataLength, fileSize) &&
                fitsInSize(header.metadataOffset, header.metadataLength, fileSize)
            if (!valid) throw PMTilesException.Truncated()
        }
    }
}

/**
 * Deterministic PMTiles v3 writer used for generating test archives and verifying round-trips.
 */
object PMTilesWriter {
    private const val INITIAL_LEAF_ENTRIES = 4_096

    data class TileSpec(val z: Int, val x: Int, val y: Int, val payload: ByteArray, val runLength: Long = 1L)

    fun buildArchive(
        tiles: List<TileSpec>,
        metadataJson: ByteArray = "{}".encodeToByteArray(),
        compressInternal: Boolean = false,
        compressTiles: Boolean = true,
        forceLeafSize: Int? = null
    ): ByteArray {
        val packed = encodeTiles(tiles, compressTiles)
        val (rootBytes, leafBytes) = layoutDirectories(packed.entries, compressInternal, forceLeafSize)
        val encodedMeta = if (compressInternal) PMTilesGzip.compress(metadataJson) else metadataJson
        val rootOffset = PMTilesHeader.BYTE_COUNT.toLong()
        val metaOffset = rootOffset + rootBytes.size
        val leafOffset = metaOffset + encodedMeta.size
        val dataOffset = leafOffset + leafBytes.size
        val header = PMTilesHeader(
            rootDirectoryOffset = rootOffset,
            rootDirectoryLength = rootBytes.size.toLong(),
            metadataOffset = metaOffset,
            metadataLength = encodedMeta.size.toLong(),
            leafDirectoryOffset = leafOffset,
            leafDirectoryLength = leafBytes.size.toLong(),
            tileDataOffset = dataOffset,
            tileDataLength = packed.dataBytes.size.toLong(),
            addressedTiles = packed.entries.sumOf { it.runLength },
            tileEntries = packed.entries.size.toLong(),
            tileContents = packed.entries.size.toLong(),
            isClustered = true,
            internalCompression = compressionCode(compressInternal),
            tileCompression = compressionCode(compressTiles),
            tileType = PMTilesHeader.TILE_TYPE_MVT,
            minimumZoom = packed.minZoom,
            maximumZoom = packed.maxZoom
        )
        return ByteArrayOutputStream().apply {
            write(header.toByteArray())
            write(rootBytes)
            write(encodedMeta)
            write(leafBytes)
            write(packed.dataBytes)
        }.toByteArray()
    }

    private class PackedTiles(
        val entries: List<PMTilesEntry>,
        val dataBytes: ByteArray,
        val minZoom: Int,
        val maxZoom: Int
    )

    private fun compressionCode(gzip: Boolean): Int =
        if (gzip) PMTilesCompression.GZIP.code else PMTilesCompression.UNCOMPRESSED.code

    private fun encodeTiles(tiles: List<TileSpec>, compressTiles: Boolean): PackedTiles {
        val sortedTiles = tiles.sortedBy { PMTilesTileId.id(it.z, it.x, it.y).toULong() }
        val tileData = ByteArrayOutputStream()
        val entries = ArrayList<PMTilesEntry>(sortedTiles.size)
        var minZoom = Int.MAX_VALUE
        var maxZoom = 0
        for (spec in sortedTiles) {
            if (spec.z < minZoom) minZoom = spec.z
            if (spec.z > maxZoom) maxZoom = spec.z
            val encodedPayload = if (compressTiles) PMTilesGzip.compress(spec.payload) else spec.payload
            val offset = tileData.size().toLong()
            tileData.write(encodedPayload)
            entries.add(
                PMTilesEntry(
                    tileId = PMTilesTileId.id(spec.z, spec.x, spec.y),
                    offset = offset,
                    length = encodedPayload.size.toLong(),
                    runLength = spec.runLength
                )
            )
        }
        return PackedTiles(
            entries = entries,
            dataBytes = tileData.toByteArray(),
            minZoom = if (sortedTiles.isEmpty()) 0 else minZoom,
            maxZoom = if (sortedTiles.isEmpty()) 0 else maxZoom
        )
    }

    private fun layoutDirectories(
        entries: List<PMTilesEntry>,
        compressInternal: Boolean,
        forceLeafSize: Int?
    ): Pair<ByteArray, ByteArray> {
        val rootLimit = PMTilesHeader.ROOT_READ_BYTES - PMTilesHeader.BYTE_COUNT
        val pack: (ByteArray) -> ByteArray = { raw -> if (compressInternal) PMTilesGzip.compress(raw) else raw }
        val whole = pack(PMTilesDirectory.encode(entries))
        if (forceLeafSize == null && whole.size <= rootLimit) {
            return whole to ByteArray(0)
        }
        var leafSize = forceLeafSize ?: INITIAL_LEAF_ENTRIES
        while (true) {
            val leavesStream = ByteArrayOutputStream()
            val pointers = ArrayList<PMTilesEntry>()
            var start = 0
            while (start < entries.size) {
                val end = minOf(start + leafSize, entries.size)
                val leaf = pack(PMTilesDirectory.encode(entries.subList(start, end)))
                pointers.add(
                    PMTilesEntry(
                        tileId = entries[start].tileId,
                        offset = leavesStream.size().toLong(),
                        length = leaf.size.toLong(),
                        runLength = 0L
                    )
                )
                leavesStream.write(leaf)
                start = end
            }
            val root = pack(PMTilesDirectory.encode(pointers))
            if (root.size <= rootLimit || forceLeafSize != null) {
                return root to leavesStream.toByteArray()
            }
            leafSize *= 2
        }
    }
}

private fun fitsInSize(offset: Long, length: Long, totalSize: Long): Boolean {
    if (offset < 0L || length < 0L || totalSize < 0L) return false
    val end = offset.toULong() + length.toULong()
    val overflow = end < offset.toULong()
    return !overflow && end <= totalSize.toULong()
}

private fun checkedAdd(a: Long, b: Long): Long {
    if (a < 0L || b < 0L) throw PMTilesException.Corrupt("Negative PMTiles offset")
    val sum = a.toULong() + b.toULong()
    if (sum < a.toULong() || sum > Long.MAX_VALUE.toULong()) {
        throw PMTilesException.Corrupt("PMTiles offset overflow")
    }
    return sum.toLong()
}
