package app.openhikes.core.common.offline

import app.openhikes.core.model.InstalledCountryRecord
import app.openhikes.core.model.OfflineCountryFile
import app.openhikes.core.model.OfflineCountryManifest
import app.openhikes.core.model.PendingCountryDownload
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Tri-state read result for device-owned `index.json` and `pending.json` lists.
 *
 * When a file exists on disk but cannot be read or decoded strictly, [Unreadable] signals callers
 * to sweep nothing, overwrite nothing, and refuse new downloads until a later read succeeds.
 */
sealed interface OfflineStoreList<out T> {
    data class Holding<T>(val items: List<T>) : OfflineStoreList<T>
    data object Unreadable : OfflineStoreList<Nothing>
}

/**
 * Atomic file store managing downloaded country PMTiles archives, staging files, and the device's
 * atomic ownership index (`index.json`), pending transfer log (`pending.json`), and cached catalog
 * (`manifest.json`) under `noBackupFilesDir/OfflineCountries`.
 */
class OfflineCountryStore(val directory: File) {
    val stagingDirectory: File = File(directory, STAGING_DIR_NAME)
    val indexFile: File = File(directory, INDEX_FILE_NAME)
    val pendingFile: File = File(directory, PENDING_FILE_NAME)
    val manifestFile: File = File(directory, MANIFEST_FILE_NAME)

    fun prepare() {
        ensureDirectory(directory)
        ensureDirectory(stagingDirectory)
    }

    fun readIndex(): OfflineStoreList<InstalledCountryRecord> {
        if (!indexFile.exists()) return OfflineStoreList.Holding(emptyList())
        return runCatching {
            OfflineStoreList.Holding(OfflineCountryManifestParser.parseStrictRecords(indexFile.readText()))
        }.getOrElse { OfflineStoreList.Unreadable }
    }

    fun writeInstalled(records: List<InstalledCountryRecord>) {
        prepare()
        val sorted = records.sortedBy { it.country.id }
        writeAtomically(indexFile, OfflineCountryManifestParser.encodeRecords(sorted))
    }

    fun readPending(): OfflineStoreList<PendingCountryDownload> {
        if (!pendingFile.exists()) return OfflineStoreList.Holding(emptyList())
        return runCatching {
            OfflineStoreList.Holding(OfflineCountryManifestParser.parseStrictPending(pendingFile.readText()))
        }.getOrElse { OfflineStoreList.Unreadable }
    }

    fun writePending(downloads: List<PendingCountryDownload>) {
        prepare()
        val sorted = downloads.sortedBy { it.country.id }
        writeAtomically(pendingFile, OfflineCountryManifestParser.encodePending(sorted))
    }

    fun readCachedManifest(): OfflineCountryManifest? {
        if (!manifestFile.isFile) return null
        return runCatching {
            OfflineCountryManifestParser.parseCatalog(manifestFile.readText())
        }.getOrNull()
    }

    fun writeCachedManifest(manifest: OfflineCountryManifest) {
        prepare()
        writeAtomically(manifestFile, OfflineCountryManifestParser.encodeManifest(manifest))
    }

    /**
     * Resolves an installed archive path after validating [OfflineCountryFile.hasSafeName].
     */
    fun fileFor(file: OfflineCountryFile): File {
        require(file.hasSafeName) { "Unsafe offline country file name: ${file.path}" }
        return File(directory, file.fileName)
    }

    /**
     * Resolves a staged archive path after validating [OfflineCountryFile.hasSafeName].
     */
    fun stagedFileFor(file: OfflineCountryFile): File {
        require(file.hasSafeName) { "Unsafe offline country file name: ${file.path}" }
        return File(stagingDirectory, file.fileName)
    }

    /**
     * Resolves an in-progress partial download file in [stagingDirectory].
     */
    fun partialFileFor(file: OfflineCountryFile): File {
        require(file.hasSafeName) { "Unsafe offline country file name: ${file.path}" }
        return File(stagingDirectory, "${file.fileName}$PARTIAL_SUFFIX")
    }

    /**
     * Moves verified staged [files] into place all-or-nothing. If any move fails, every file moved
     * earlier in the batch is moved back to [stagingDirectory] before rethrowing.
     */
    fun install(files: List<OfflineCountryFile>) {
        prepare()
        val pairs = files.map { file -> file to (stagedFileFor(file) to fileFor(file)) }
        val moved = ArrayList<OfflineCountryFile>(files.size)
        try {
            for ((file, paths) in pairs) {
                val (source, destination) = paths
                if (!source.isFile) {
                    throw IOException("Staged archive missing: ${file.fileName}")
                }
                moveReplacing(source, destination)
                moved.add(file)
            }
        } catch (ex: IOException) {
            unstage(moved)
            throw ex
        }
    }

    /**
     * Moves installed [files] back to [stagingDirectory] unless still referenced by [keeping].
     * Any file that cannot be returned to staging is deleted so no orphaned file remains unowned.
     */
    fun unstage(files: List<OfflineCountryFile>, keeping: List<InstalledCountryRecord> = emptyList()) {
        val keptNames = keeping.flatMap { it.files }.map { it.fileName }.toSet()
        for (file in files) {
            if (file.fileName !in keptNames) {
                val installed = fileFor(file)
                val staged = stagedFileFor(file)
                if (installed.exists()) {
                    runCatching {
                        ensureDirectory(stagingDirectory)
                        moveReplacing(installed, staged)
                    }.onFailure {
                        installed.delete()
                    }
                }
            }
        }
    }

    /**
     * Deletes installed [files] except any still referenced by [keeping].
     */
    fun remove(files: List<OfflineCountryFile>, keeping: List<InstalledCountryRecord> = emptyList()) {
        val keptNames = keeping.flatMap { it.files }.map { it.fileName }.toSet()
        for (file in files) {
            if (file.fileName !in keptNames) {
                fileFor(file).delete()
            }
        }
    }

    /**
     * Deletes every `.pmtiles` (and `.part`) file in [directory] and [stagingDirectory] that is not
     * referenced by [keeping] or [pending]. Never call when [readIndex] or [readPending] returned
     * [OfflineStoreList.Unreadable].
     */
    fun sweep(keeping: List<InstalledCountryRecord>, pending: List<PendingCountryDownload>) {
        val keptInstalled = keeping.flatMap { it.files }.map { it.fileName }.toSet()
        val keptStaged = pending.flatMap { it.files }.flatMap { listOf(it.fileName, "${it.fileName}$PARTIAL_SUFFIX") }
            .toSet()
        sweepFolder(directory, keptInstalled)
        sweepFolder(stagingDirectory, keptStaged)
    }

    fun availableBytes(): Long? {
        if (!directory.exists()) {
            runCatching { ensureDirectory(directory) }
        }
        val usable = directory.usableSpace
        return usable.takeIf { it > 0L }
    }

    companion object {
        const val DIRECTORY_NAME = "OfflineCountries"
        const val SPARE_BYTES = 500L * 1024L * 1024L
        private const val STAGING_DIR_NAME = "Staging"
        private const val INDEX_FILE_NAME = "index.json"
        private const val PENDING_FILE_NAME = "pending.json"
        private const val MANIFEST_FILE_NAME = "manifest.json"
    }
}

private const val TEMP_SUFFIX = ".tmp"
private const val PARTIAL_SUFFIX = ".part"

private fun sweepFolder(folder: File, keptNames: Set<String>) {
    val children = folder.listFiles() ?: return
    for (child in children) {
        if (!child.isFile) continue
        val name = child.name
        val isArchiveOrPart = name.endsWith(OfflineCountryFile.FILE_EXTENSION) ||
            name.endsWith("${OfflineCountryFile.FILE_EXTENSION}$PARTIAL_SUFFIX")
        if (isArchiveOrPart && name !in keptNames) {
            child.delete()
        }
    }
}

private fun writeAtomically(target: File, content: String) {
    val tmp = File(target.parentFile, "${target.name}$TEMP_SUFFIX")
    val bytes = content.encodeToByteArray()
    FileOutputStream(tmp).use { stream ->
        stream.write(bytes)
        stream.flush()
        stream.fd.sync()
    }
    moveReplacing(tmp, target)
}

private fun moveReplacing(source: File, target: File) {
    try {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun ensureDirectory(dir: File) {
    if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
        throw IOException("Failed to create offline directory: ${dir.path}")
    }
}
