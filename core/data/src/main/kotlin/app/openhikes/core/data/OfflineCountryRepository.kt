package app.openhikes.core.data

import app.openhikes.core.model.OfflineCountriesState
import kotlinx.coroutines.flow.StateFlow

/**
 * Result returned to [OfflineCountryDownloadWorker] after executing a scheduled download.
 */
enum class OfflineDownloadExecutionResult {
    COMPLETED,
    FAILED,
    CANCELLED,
    RETRY_LATER
}

/**
 * Main-safe repository owning the offline country catalog, WorkManager downloads, SHA-256
 * verification, atomic country index, and Extra Detail reconciliation.
 */
interface OfflineCountryRepository {
    val state: StateFlow<OfflineCountriesState>

    suspend fun start()

    suspend fun refreshCatalog()

    suspend fun downloadCountry(countryId: String)

    suspend fun retryDownload(countryId: String)

    suspend fun repairCountry(countryId: String)

    suspend fun cancelDownload(countryId: String)

    suspend fun deleteCountry(countryId: String)

    suspend fun setIncludesDetail(enabled: Boolean)

    suspend fun runScheduledDownload(countryId: String): OfflineDownloadExecutionResult
}
