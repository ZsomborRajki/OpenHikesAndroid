package app.openhikes.core.network

import app.openhikes.core.common.IoDispatcher
import app.openhikes.core.common.OpenHikesClock
import app.openhikes.core.model.TileNetworkPurpose
import app.openhikes.core.model.VectorTileArchiveManifest
import app.openhikes.core.model.VectorTileSource
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Manages the active `tiles.openhikes.app` [VectorTileSource] resolved from the newer of the
 * bundled `VectorTileArchiveManifest.json` and the persisted manifest kept in `noBackupFilesDir`.
 *
 * Refreshes at most once per hour on foreground transitions, and after a 60-second cooldown when
 * a tile request receives HTTP `410 Gone` for a retired archive.
 */
@Singleton
class VectorTileArchivesManager @Inject constructor(
    private val manifestDataSource: VectorTileManifestDataSource,
    private val tileNetworkPolicy: TileNetworkPolicy,
    private val clock: OpenHikesClock,
    @TileServerBaseUrl private val serverBaseUrl: String,
    @VectorArchivesFile private val stateFile: File,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()
    private val stateLock = Any()

    private val bundledManifest: VectorTileArchiveManifest = loadBundledManifest()
    private val language: String = Locale.getDefault().language.ifBlank { DEFAULT_LANGUAGE }

    private var currentManifest: VectorTileArchiveManifest
    private var sinceEpochMillis: Long
    private var lastReadAtMillis: Long? = null
    private var lastReadFailedFlag: Boolean = false

    private val activeSourceState: MutableStateFlow<VectorTileSource?>

    val activeSource: StateFlow<VectorTileSource?>
        get() = activeSourceState.asStateFlow()

    val lastReadFailed: Boolean
        get() = synchronized(stateLock) { lastReadFailedFlag }

    val archiveMovedAtMillis: Long
        get() = synchronized(stateLock) { sinceEpochMillis }

    init {
        val now = clock.nowEpochMillis()
        val kept = readKeptRecord()
        val initial = resolveInitial(
            bundled = bundledManifest,
            kept = kept,
            serverBaseUrl = serverBaseUrl,
            language = language,
            nowEpochMillis = now
        )
        currentManifest = initial?.manifest ?: bundledManifest
        sinceEpochMillis = initial?.sinceEpochMillis ?: now
        activeSourceState = MutableStateFlow(initial?.source)
        if (initial != null && initial != kept) {
            runCatching { writeKeptRecord(initial) }
        }
    }

    /**
     * Refreshes the tile server manifest if at least [FOREGROUND_REFRESH_INTERVAL_MILLIS] has elapsed.
     */
    suspend fun refresh(): Boolean = refreshIfDue(FOREGROUND_REFRESH_INTERVAL_MILLIS)

    /**
     * Checks whether the active source has moved away from [retiredSource]; if not, forces a
     * manifest refresh (throttled to [RETIRED_REFRESH_INTERVAL_MILLIS]) and returns `true` if moved.
     */
    suspend fun movedFrom(retiredSource: VectorTileSource): Boolean {
        if (activeSourceState.value != retiredSource) return true
        refreshIfDue(RETIRED_REFRESH_INTERVAL_MILLIS)
        return activeSourceState.value != retiredSource
    }

    /**
     * Adopts [manifest] if it is at least as new as the active revision and lists a compatible basemap.
     */
    suspend fun adopt(manifest: VectorTileArchiveManifest): Boolean {
        val nextSource = manifest.sourceOn(serverBaseUrl) ?: return false
        val applied = applyManifestLocked(manifest, nextSource, clock.nowEpochMillis())
        if (applied != null) {
            withContext(ioDispatcher) {
                runCatching { writeKeptRecord(applied.kept) }
            }
        }
        return applied?.moved == true
    }

    private data class AppliedManifest(val moved: Boolean, val kept: KeptVectorArchives)

    private fun applyManifestLocked(
        manifest: VectorTileArchiveManifest,
        nextSource: VectorTileSource,
        now: Long
    ): AppliedManifest? = synchronized(stateLock) {
        if (manifest.revision < currentManifest.revision) return null
        val moved = activeSourceState.value != nextSource
        if (moved) {
            sinceEpochMillis = now
            activeSourceState.value = nextSource
        }
        currentManifest = manifest
        AppliedManifest(
            moved = moved,
            kept = KeptVectorArchives(
                manifest = manifest,
                source = nextSource,
                sinceEpochMillis = sinceEpochMillis,
                language = language
            )
        )
    }

    /**
     * Returns whether a tile rendered at [storedAtEpochMillis] must be re-fetched due to an archive
     * move, language change, or backward system clock jump.
     */
    fun shouldRedraw(storedAtEpochMillis: Long): Boolean {
        val now = clock.nowEpochMillis()
        return synchronized(stateLock) {
            if (sinceEpochMillis > now) {
                sinceEpochMillis = now
            }
            sinceEpochMillis > storedAtEpochMillis || storedAtEpochMillis > now
        }
    }

    private suspend fun refreshIfDue(minIntervalMillis: Long): Boolean = refreshMutex.withLock {
        val now = clock.nowEpochMillis()
        val last = synchronized(stateLock) { lastReadAtMillis }
        if (last != null && now - last < minIntervalMillis) {
            return@withLock false
        }
        if (!tileNetworkPolicy.decide(TileNetworkPurpose.INTERACTIVE).isAllowed) {
            synchronized(stateLock) { lastReadFailedFlag = true }
            return@withLock false
        }
        val fetched = runCatching { manifestDataSource.fetchManifest() }.getOrNull()
        val completedAt = clock.nowEpochMillis()
        if (fetched == null) {
            synchronized(stateLock) {
                lastReadAtMillis = completedAt
                lastReadFailedFlag = true
            }
            return@withLock false
        }
        synchronized(stateLock) {
            lastReadAtMillis = completedAt
            lastReadFailedFlag = false
        }
        adopt(fetched)
        true
    }

    private fun readKeptRecord(): KeptVectorArchives? {
        if (!stateFile.isFile) return null
        return runCatching {
            json.decodeFromString(KeptVectorArchives.serializer(), stateFile.readText())
        }.getOrNull()
    }

    private fun writeKeptRecord(kept: KeptVectorArchives) {
        val parent = stateFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmp = File(parent, "${stateFile.name}.tmp")
        val payload = json.encodeToString(KeptVectorArchives.serializer(), kept).encodeToByteArray()
        FileOutputStream(tmp).use { out ->
            out.write(payload)
            out.flush()
            out.fd.sync()
        }
        try {
            Files.move(
                tmp.toPath(),
                stateFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun loadBundledManifest(): VectorTileArchiveManifest {
        val stream = VectorTileArchivesManager::class.java.getResourceAsStream(BUNDLED_MANIFEST_RESOURCE)
            ?: return FALLBACK_MANIFEST
        return runCatching {
            val raw = stream.use { it.readBytes().decodeToString() }
            json.decodeFromString(VectorTileArchiveManifest.serializer(), raw)
        }.getOrDefault(FALLBACK_MANIFEST)
    }

    @Serializable
    internal data class KeptVectorArchives(
        val manifest: VectorTileArchiveManifest,
        val source: VectorTileSource,
        val sinceEpochMillis: Long,
        val language: String
    )

    companion object {
        const val FOREGROUND_REFRESH_INTERVAL_MILLIS = 3_600_000L
        const val RETIRED_REFRESH_INTERVAL_MILLIS = 60_000L
        const val STATE_FILE_NAME = "VectorTileArchives.json"
        private const val DEFAULT_LANGUAGE = "en"
        private const val BUNDLED_MANIFEST_RESOURCE = "/manifests/VectorTileArchiveManifest.json"

        private val FALLBACK_MANIFEST = VectorTileArchiveManifest(
            version = VectorTileArchiveManifest.CURRENT_VERSION,
            revision = 1,
            basemaps = listOf(
                VectorTileArchiveManifest.BasemapArchive(
                    name = "planet-20261118",
                    schema = 4,
                    maximumZoom = 15
                )
            ),
            extras = listOf(
                VectorTileArchiveManifest.ExtrasArchive(
                    name = "extras-20261122-00",
                    generation = 2,
                    minimumZoom = 4
                )
            )
        )

        internal fun resolveInitial(
            bundled: VectorTileArchiveManifest,
            kept: KeptVectorArchives?,
            serverBaseUrl: String,
            language: String,
            nowEpochMillis: Long
        ): KeptVectorArchives? {
            val manifest = if (kept != null &&
                kept.manifest.revision >= bundled.revision &&
                kept.manifest.sourceOn(serverBaseUrl) != null
            ) {
                kept.manifest
            } else {
                bundled
            }
            val source = manifest.sourceOn(serverBaseUrl) ?: return null
            val since = if (kept != null && kept.matchesCurrent(source, language, nowEpochMillis)) {
                kept.sinceEpochMillis
            } else {
                nowEpochMillis
            }
            return KeptVectorArchives(
                manifest = manifest,
                source = source,
                sinceEpochMillis = since,
                language = language
            )
        }

        private fun KeptVectorArchives.matchesCurrent(
            source: VectorTileSource,
            language: String,
            nowEpochMillis: Long
        ): Boolean = this.source == source && this.language == language && sinceEpochMillis <= nowEpochMillis
    }
}
