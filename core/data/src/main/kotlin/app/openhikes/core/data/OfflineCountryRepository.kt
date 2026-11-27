package app.openhikes.core.data

import app.openhikes.core.model.OfflineCountriesState
import kotlinx.coroutines.flow.StateFlow

/**
 * Result returned to [OfflineCountryDownloadWorker] after executing a scheduled download.
 */
enum class OfflineDownloadExecutionResult {
    COMPLETED,
    CONTINUE_SLICE,
    FAILED,
    CANCELLED,
    RETRY_LATER
}

/**
 * Main-safe repository owning the offline country catalog, WorkManager downloads, SHA-256
 * verification, atomic country index, and *Extra Detail* / *Elevation Data* offers.
 */
@Suppress("TooManyFunctions")
interface OfflineCountryRepository {
    val state: StateFlow<OfflineCountriesState>

    suspend fun start()

    /**
     * Ensures the local offline country index and installed PMTiles archives have been opened from
     * disk without triggering network activity. Safe to call concurrently with [start].
     */
    suspend fun ensurePrepared() = Unit

    suspend fun refreshCatalog()

    suspend fun downloadCountry(countryId: String)

    suspend fun retryDownload(countryId: String)

    suspend fun repairCountry(countryId: String)

    suspend fun cancelDownload(countryId: String)

    suspend fun deleteCountry(countryId: String)

    suspend fun setIncludesDetail(enabled: Boolean)

    suspend fun setIncludesElevation(enabled: Boolean)

    /**
     * Starts downloading the optional files currently offered by [OfflineCountriesState.addition]
     * for an already-installed country.
     */
    suspend fun extendCountry(countryId: String)

    /**
     * Deletes the optional files currently offered by [OfflineCountriesState.removal] from an
     * already-installed country without deleting the country itself.
     */
    suspend fun trimCountry(countryId: String)

    suspend fun runScheduledDownload(countryId: String): OfflineDownloadExecutionResult
}
