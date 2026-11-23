package app.openhikes.core.model

import kotlinx.serialization.Serializable

/**
 * Installed offline country record persisted in the atomic device index (`index.json`).
 */
@Serializable
data class InstalledCountryRecord(
    val country: OfflineCountry,
    val installedAtEpochMillis: Long,
    val hasDetail: Boolean = false
) {
    val files: List<OfflineCountryFile>
        get() = country.files(withDetail = hasDetail)

    val byteCount: Long
        get() = files.sumOf { it.byteCount }
}

/**
 * Download in progress persisted in `pending.json` so interrupted transfers recover cleanly across launches.
 */
@Serializable
data class PendingCountryDownload(val country: OfflineCountry, val roles: List<OfflineFileRole>) {
    val files: List<OfflineCountryFile>
        get() = country.files.filter { it.role in roles }
}

/**
 * Lifecycle stage of an active or failed offline country download.
 */
enum class OfflineDownloadPhase {
    WAITING_FOR_WIFI,
    DOWNLOADING,
    CHECKING,
    FAILED
}

/**
 * Categorized failure reason shown on an offline country row and Hike Detail offline dialog.
 */
enum class OfflineDownloadFailure {
    CONNECTION,
    SPACE,
    DAMAGED,
    CONSERVING
}

/**
 * Live state of a single offline country download.
 */
data class OfflineCountryDownload(
    val country: OfflineCountry,
    val roles: List<OfflineFileRole>,
    val phase: OfflineDownloadPhase,
    val failure: OfflineDownloadFailure? = null,
    val writtenBytes: Long = 0L,
    val totalBytes: Long = country.files.filter { it.role in roles }.sumOf { it.byteCount },
    val stagedRoles: Set<OfflineFileRole> = emptySet(),
    val inFlightRoles: Set<OfflineFileRole> = emptySet()
) {
    val files: List<OfflineCountryFile>
        get() = country.files.filter { it.role in roles }

    val hasDetail: Boolean
        get() = roles.any { it.isDetail }

    val isDetailOnly: Boolean
        get() = roles.isNotEmpty() && roles.all { it.isDetail }

    val isStaged: Boolean
        get() = roles.isNotEmpty() && roles.all { it in stagedRoles }

    val progressFraction: Float
        get() = if (totalBytes <= 0L) {
            0f
        } else {
            (writtenBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        }

    val remainingBytes: Long
        get() = (totalBytes - writtenBytes).coerceAtLeast(0L)

    /**
     * Bytes this download still expects to write to disk, used when checking free storage before
     * starting or retrying a sibling country download.
     */
    val owedBytes: Long
        get() = if (phase == OfflineDownloadPhase.FAILED) {
            files.filter { it.role in inFlightRoles }.sumOf { it.byteCount }
        } else {
            remainingBytes
        }
}

/**
 * Sort order options for the Offline Maps list.
 */
enum class OfflineCountrySort {
    NEAREST,
    NAME,
    LARGEST
}

/**
 * Catalog fetch lifecycle state for `countries/manifest.json`.
 */
enum class OfflineListPhase {
    IDLE,
    LOADING,
    LOADED,
    FAILED
}

/**
 * Readability status of the local atomic `index.json` / `pending.json` store.
 * When [UNREADABLE], nothing is swept or overwritten and new downloads are refused.
 */
enum class OfflineHoldingsState {
    READY,
    UNREADABLE
}

/**
 * Immutable snapshot of all offline country catalog, installed, and download state.
 */
data class OfflineCountriesState(
    val isAvailable: Boolean = true,
    val isPrepared: Boolean = false,
    val holdings: OfflineHoldingsState = OfflineHoldingsState.READY,
    val listPhase: OfflineListPhase = OfflineListPhase.IDLE,
    val catalogRevision: Long = 0L,
    val offered: List<OfflineCountry> = emptyList(),
    val installed: Map<String, InstalledCountryRecord> = emptyMap(),
    val downloads: Map<String, OfflineCountryDownload> = emptyMap(),
    val unopenable: Map<String, Set<OfflineFileRole>> = emptyMap(),
    val includesDetail: Boolean = UserSettings.DEFAULT_OFFLINE_COUNTRIES_EXTRA_DETAIL
) {
    /**
     * All areas displayed in Offline Maps: offered areas merged with any installed areas no longer listed.
     */
    val listedCountries: List<OfflineCountry>
        get() {
            val byId = LinkedHashMap<String, OfflineCountry>(offered.size + installed.size)
            for (country in offered) {
                byId[country.id] = country
            }
            for ((id, record) in installed) {
                if (id !in byId) {
                    byId[id] = record.country
                }
            }
            return byId.values.toList()
        }

    val installedByteCount: Long
        get() = installed.values.sumOf { it.byteCount }

    fun isDrawn(countryId: String): Boolean {
        if (countryId !in installed) return false
        return OfflineFileRole.BASEMAP !in unopenable[countryId].orEmpty()
    }

    fun needsRepair(countryId: String): Boolean = countryId in installed && unopenable[countryId].orEmpty().isNotEmpty()

    fun hasUpdate(country: OfflineCountry): Boolean {
        val current = installed[country.id] ?: return false
        return country.build > current.country.build
    }

    fun filesToFetch(country: OfflineCountry): List<OfflineCountryFile> {
        val current = installed[country.id]
        val needsDetailTopUp = includesDetail && current != null && current.needsDetailFor(country)
        return if (needsDetailTopUp) {
            country.files.filter { it.role.isDetail }
        } else {
            country.files(withDetail = includesDetail)
        }
    }

    private fun InstalledCountryRecord.needsDetailFor(candidate: OfflineCountry): Boolean =
        country.build == candidate.build && !hasDetail

    fun listedByteCount(country: OfflineCountry): Long {
        val current = installed[country.id]
        return if (current != null && !hasUpdate(country)) {
            current.byteCount
        } else {
            country.byteCount(withDetail = includesDetail)
        }
    }
}
