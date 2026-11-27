package app.openhikes.core.model

import kotlinx.serialization.Serializable

/**
 * Installed offline country record persisted in the atomic device index (`index.json`).
 */
@Serializable(with = InstalledCountryRecordSerializer::class)
data class InstalledCountryRecord(
    val country: OfflineCountry,
    val installedAtEpochMillis: Long,
    val hasDetail: Boolean = false,
    val hasElevation: Boolean = false
) {
    val files: List<OfflineCountryFile>
        get() = country.files(withDetail = hasDetail, withElevation = hasElevation)

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

    val hasElevation: Boolean
        get() = roles.any { it.isElevation }

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
    val recovering: Set<String> = emptySet(),
    val unopenable: Map<String, Set<OfflineFileRole>> = emptyMap(),
    val includesDetail: Boolean = UserSettings.DEFAULT_OFFLINE_COUNTRIES_EXTRA_DETAIL,
    val includesElevation: Boolean = UserSettings.DEFAULT_OFFLINE_COUNTRIES_ELEVATION_DATA
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

    /**
     * Whether no download is running, failed, or still being recovered at launch for [countryId].
     */
    fun isSettled(countryId: String): Boolean = countryId !in downloads && countryId !in recovering

    /**
     * Whether the current *Extra Detail* and *Elevation Data* switches ask for [role]; mandatory
     * roles are always wanted.
     */
    fun wants(role: OfflineFileRole): Boolean =
        (includesDetail || !role.isDetail) && (includesElevation || !role.isElevation)

    fun hasUpdate(country: OfflineCountry): Boolean {
        val current = installed[country.id] ?: return false
        return country.build > current.country.build
    }

    /**
     * Returns the catalog entry for [record] when the server still offers that exact build, or
     * `null` if the server moved to a newer build or removed the area.
     */
    fun offeredBuild(record: InstalledCountryRecord): OfflineCountry? =
        offered.firstOrNull { it.id == record.country.id && it.build == record.country.build }

    /**
     * Files a fresh download or update of [country] brings under the current switches, or — for an
     * area already installed at that exact build — only files that failed to open plus any newly
     * listed mandatory files. Switch-controlled extras for already-installed areas come solely via
     * [addition].
     */
    fun filesToFetch(country: OfflineCountry): List<OfflineCountryFile> {
        val current = installed[country.id]
        if (current == null || current.country.build != country.build) {
            return country.files(withDetail = includesDetail, withElevation = includesElevation)
        }
        val broken = unopenable[country.id].orEmpty()
        val repairFiles = current.files.filter { it.role in broken }
        val gained = gainedFiles(listed = country, record = current)
        return (repairFiles + gained).distinctBy { it.fileName }
    }

    /**
     * Files to fetch when retrying [failed] at its same build: a fresh download/update follows the
     * current switches; an addition or repair for an installed build asks only for what [failed]
     * requested that is still wanted (or still unopenable if already held).
     */
    fun filesToRetry(failed: OfflineCountryDownload): List<OfflineCountryFile> {
        val current = installed[failed.country.id]
        if (current == null || current.country.build != failed.country.build) {
            return filesToFetch(failed.country)
        }
        val broken = unopenable[failed.country.id].orEmpty()
        val heldNames = current.files.map { it.fileName }.toSet()
        return failed.files.filter { file ->
            if (file.fileName in heldNames) file.role in broken else wants(file.role)
        }
    }

    /**
     * Optional files the switches ask for that installed [countryId] currently lacks, together with
     * any mandatory files gained by its same build since installation.
     */
    fun addition(countryId: String): OfflineCountryOffer? {
        val record = installed[countryId]
        val listed = if (record != null && isSettled(countryId) && !needsRepair(countryId)) {
            offeredBuild(record)
        } else {
            null
        }
        val asked = if (record != null && listed != null) {
            val heldNames = record.files.map { it.fileName }.toSet()
            listed.files.filter { it.role.isOptional && wants(it.role) && it.fileName !in heldNames }
        } else {
            emptyList()
        }
        return if (record != null && listed != null && asked.isNotEmpty()) {
            val files = gainedFiles(listed = listed, record = record) + asked
            OfflineCountryOffer(extras = OfflineCountryExtra.fromFiles(asked), files = files)
        } else {
            null
        }
    }

    /**
     * Optional files installed [countryId] currently holds that the switches no longer ask for.
     * Offered regardless of server availability because deleting requires no network.
     */
    fun removal(countryId: String): OfflineCountryOffer? {
        val record = installed[countryId]
        val unasked = if (record != null && isSettled(countryId)) {
            record.files.filter { !wants(it.role) }
        } else {
            emptyList()
        }
        return if (unasked.isNotEmpty()) {
            OfflineCountryOffer(extras = OfflineCountryExtra.fromFiles(unasked), files = unasked)
        } else {
            null
        }
    }

    fun byteCount(country: OfflineCountry): Long = filesToFetch(country).sumOf { it.byteCount }

    fun listedByteCount(country: OfflineCountry): Long {
        val current = installed[country.id]
        return if (current != null && !hasUpdate(country)) {
            current.byteCount
        } else {
            country.byteCount(withDetail = includesDetail, withElevation = includesElevation)
        }
    }

    companion object {
        /**
         * Mandatory (non-optional) files that [listed] now advertises for the same build as
         * [record] which [record] does not yet hold.
         */
        fun gainedFiles(listed: OfflineCountry, record: InstalledCountryRecord): List<OfflineCountryFile> {
            val heldNames = record.files.map { it.fileName }.toSet()
            return listed.files.filter { !it.role.isOptional && it.fileName !in heldNames }
        }
    }
}
