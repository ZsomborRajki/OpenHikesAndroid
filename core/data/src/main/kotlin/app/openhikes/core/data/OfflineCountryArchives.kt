package app.openhikes.core.data

import app.openhikes.core.common.offline.OfflineCountryStore
import app.openhikes.core.common.pmtiles.PMTilesArchive
import app.openhikes.core.model.InstalledCountryRecord
import app.openhikes.core.model.OfflineBounds
import app.openhikes.core.model.OfflineFileRole
import app.openhikes.core.model.TileAddress
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds opened [PMTilesArchive] handles for all currently installed offline countries.
 *
 * Reopening runs off the main thread; any file that fails to open is reported back in the
 * unopenable map without modifying or deleting files on disk.
 */
@Singleton
class OfflineCountryArchives @Inject constructor() :
    OfflineTileArchiveSource,
    Closeable {
    private val lock = Any()
    private val revisionCounter = AtomicLong(0L)
    private val revisionState = MutableStateFlow(0L)
    private val maxBasemapZoomState = MutableStateFlow(0)
    private val maxExtrasZoomState = MutableStateFlow(0)
    private var openedCountries: List<OpenedCountry> = emptyList()

    override val archiveRevision: StateFlow<Long> = revisionState.asStateFlow()
    override val maximumBasemapZoom: StateFlow<Int> = maxBasemapZoomState.asStateFlow()
    override val maximumExtrasZoom: StateFlow<Int> = maxExtrasZoomState.asStateFlow()

    /**
     * Reopens archives for [records] from [store], atomically swaps the active handle list,
     * closes replaced handles, and returns any unopenable roles keyed by country id.
     */
    fun reopen(records: List<InstalledCountryRecord>, store: OfflineCountryStore): Map<String, Set<OfflineFileRole>> {
        val nextOpened = ArrayList<OpenedCountry>(records.size)
        val unopenable = LinkedHashMap<String, Set<OfflineFileRole>>()

        for (record in records) {
            val failedRoles = LinkedHashSet<OfflineFileRole>()
            val archivesByRole = HashMap<OfflineFileRole, PMTilesArchive>()
            for (file in record.files) {
                val opened = runCatching {
                    val diskFile = store.fileFor(file)
                    PMTilesArchive.open(diskFile)
                }.getOrNull()
                if (opened != null) {
                    archivesByRole[file.role] = opened
                } else {
                    failedRoles.add(file.role)
                }
            }
            if (failedRoles.isNotEmpty()) {
                unopenable[record.country.id] = failedRoles
            }
            val basemap = archivesByRole[OfflineFileRole.BASEMAP]
            if (basemap != null) {
                nextOpened.add(
                    OpenedCountry(
                        id = record.country.id,
                        bounds = record.country.bounds,
                        basemap = basemap,
                        extras = archivesByRole[OfflineFileRole.EXTRAS],
                        detail = archivesByRole[OfflineFileRole.DETAIL],
                        detailExtras = archivesByRole[OfflineFileRole.DETAIL_EXTRAS]
                    )
                )
            } else {
                archivesByRole.values.forEach { runCatching { it.close() } }
            }
        }

        val nextMaxBasemapZoom = nextOpened.maxOfOrNull { country ->
            maxOf(country.basemap.header.maximumZoom, country.detail?.header?.maximumZoom ?: 0)
        } ?: 0
        val nextMaxExtrasZoom = nextOpened.maxOfOrNull { country ->
            maxOf(country.extras?.header?.maximumZoom ?: 0, country.detailExtras?.header?.maximumZoom ?: 0)
        } ?: 0

        val previous = synchronized(lock) {
            val old = openedCountries
            openedCountries = nextOpened
            old
        }
        previous.forEach { it.close() }
        maxBasemapZoomState.value = nextMaxBasemapZoom
        maxExtrasZoomState.value = nextMaxExtrasZoom
        revisionState.value = revisionCounter.incrementAndGet()
        return unopenable
    }

    override fun readBasemapTile(address: TileAddress): ByteArray? =
        readTile(address = address, pickPrimary = { it.detail }, pickFallback = { it.basemap })

    override fun readExtrasTile(address: TileAddress): ByteArray? =
        readTile(address = address, pickPrimary = { it.detailExtras }, pickFallback = { it.extras })

    override fun containsTile(address: TileAddress): Boolean {
        if (!address.isValid) return false
        val tileBox = address.toGeoBoundingBox()
        return synchronized(lock) {
            for (country in openedCountries) {
                if (!country.bounds.intersects(tileBox)) continue
                if (archiveContains(country.detail, address) || archiveContains(country.basemap, address)) {
                    return@synchronized true
                }
            }
            false
        }
    }

    override fun close() {
        val previous = synchronized(lock) {
            val old = openedCountries
            openedCountries = emptyList()
            old
        }
        previous.forEach { it.close() }
        maxBasemapZoomState.value = 0
        maxExtrasZoomState.value = 0
    }

    private fun readTile(
        address: TileAddress,
        pickPrimary: (OpenedCountry) -> PMTilesArchive?,
        pickFallback: (OpenedCountry) -> PMTilesArchive?
    ): ByteArray? {
        if (!address.isValid) return null
        val tileBox = address.toGeoBoundingBox()
        return synchronized(lock) {
            for (country in openedCountries) {
                if (!country.bounds.intersects(tileBox)) continue
                val primaryBytes = readFromArchive(pickPrimary(country), address)
                if (primaryBytes != null) return@synchronized primaryBytes
                val fallbackBytes = readFromArchive(pickFallback(country), address)
                if (fallbackBytes != null) return@synchronized fallbackBytes
            }
            null
        }
    }

    private fun readFromArchive(archive: PMTilesArchive?, address: TileAddress): ByteArray? {
        if (archive == null || address.z !in archive.header.minimumZoom..archive.header.maximumZoom) {
            return null
        }
        return runCatching { archive.tile(address.z, address.x, address.y) }.getOrNull()
    }

    private fun archiveContains(archive: PMTilesArchive?, address: TileAddress): Boolean {
        if (archive == null || address.z !in archive.header.minimumZoom..archive.header.maximumZoom) {
            return false
        }
        return runCatching { archive.contains(address.z, address.x, address.y) }.getOrDefault(false)
    }

    private class OpenedCountry(
        val id: String,
        val bounds: OfflineBounds,
        val basemap: PMTilesArchive,
        val extras: PMTilesArchive?,
        val detail: PMTilesArchive?,
        val detailExtras: PMTilesArchive?
    ) : Closeable {
        override fun close() {
            runCatching { basemap.close() }
            runCatching { extras?.close() }
            runCatching { detail?.close() }
            runCatching { detailExtras?.close() }
        }
    }
}
