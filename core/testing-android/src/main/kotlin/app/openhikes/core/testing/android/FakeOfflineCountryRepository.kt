package app.openhikes.core.testing.android

import app.openhikes.core.data.OfflineCountryRepository
import app.openhikes.core.data.OfflineDownloadExecutionResult
import app.openhikes.core.model.InstalledCountryRecord
import app.openhikes.core.model.OfflineCountriesState
import app.openhikes.core.model.OfflineCountry
import app.openhikes.core.model.OfflineCountryDownload
import app.openhikes.core.model.OfflineDownloadPhase
import app.openhikes.core.model.OfflineListPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Deterministic in-memory [OfflineCountryRepository] fake for ViewModel and Compose unit tests.
 */
class FakeOfflineCountryRepository(initialState: OfflineCountriesState = OfflineCountriesState(isPrepared = true)) :
    OfflineCountryRepository {
    private val stateFlow = MutableStateFlow(initialState)
    override val state: StateFlow<OfflineCountriesState> = stateFlow.asStateFlow()

    val downloadedIds = mutableListOf<String>()
    val retriedIds = mutableListOf<String>()
    val repairedIds = mutableListOf<String>()
    val cancelledIds = mutableListOf<String>()
    val deletedIds = mutableListOf<String>()
    var refreshCount: Int = 0
        private set

    var nextCatalogRefreshOverride: List<OfflineCountry>? = null
    var failNextRefresh: Boolean = false
    var startGate: CompletableDeferred<Unit>? = null

    fun setState(newState: OfflineCountriesState) {
        stateFlow.value = newState
    }

    override suspend fun start() {
        ensurePrepared()
    }

    override suspend fun ensurePrepared() {
        startGate?.await()
        stateFlow.update { it.copy(isPrepared = true) }
    }

    override suspend fun refreshCatalog() {
        refreshCount += 1
        if (failNextRefresh) {
            stateFlow.update { it.copy(isPrepared = true, listPhase = OfflineListPhase.FAILED) }
            return
        }
        val offered = nextCatalogRefreshOverride ?: stateFlow.value.offered
        stateFlow.update { current ->
            current.copy(
                isPrepared = true,
                listPhase = OfflineListPhase.LOADED,
                catalogRevision = current.catalogRevision + 1L,
                offered = offered
            )
        }
    }

    override suspend fun downloadCountry(countryId: String) {
        downloadedIds.add(countryId)
        val current = stateFlow.value
        val country = current.offered.firstOrNull { it.id == countryId }
            ?: current.installed[countryId]?.country
            ?: return
        val files = current.filesToFetch(country)
        val download = OfflineCountryDownload(
            country = country,
            roles = files.map { it.role },
            phase = OfflineDownloadPhase.DOWNLOADING,
            writtenBytes = 0L,
            totalBytes = files.sumOf { it.byteCount }
        )
        stateFlow.update { it.copy(downloads = it.downloads + (countryId to download)) }
    }

    override suspend fun retryDownload(countryId: String) {
        retriedIds.add(countryId)
        stateFlow.update { current ->
            val existing = current.downloads[countryId] ?: return@update current
            current.copy(
                downloads = current.downloads + (
                    countryId to existing.copy(
                        phase = OfflineDownloadPhase.DOWNLOADING,
                        failure = null
                    )
                    )
            )
        }
    }

    override suspend fun repairCountry(countryId: String) {
        repairedIds.add(countryId)
        downloadCountry(countryId)
    }

    override suspend fun cancelDownload(countryId: String) {
        cancelledIds.add(countryId)
        stateFlow.update { it.copy(downloads = it.downloads - countryId) }
    }

    override suspend fun deleteCountry(countryId: String) {
        deletedIds.add(countryId)
        stateFlow.update {
            it.copy(
                installed = it.installed - countryId,
                downloads = it.downloads - countryId,
                unopenable = it.unopenable - countryId
            )
        }
    }

    override suspend fun setIncludesDetail(enabled: Boolean) {
        stateFlow.update { current ->
            val updatedInstalled = if (!enabled) {
                current.installed.mapValues { (_, record) -> record.copy(hasDetail = false) }
            } else {
                current.installed
            }
            current.copy(includesDetail = enabled, installed = updatedInstalled)
        }
    }

    override suspend fun runScheduledDownload(countryId: String): OfflineDownloadExecutionResult {
        val current = stateFlow.value
        val download = current.downloads[countryId] ?: return OfflineDownloadExecutionResult.CANCELLED
        val record = InstalledCountryRecord(
            country = download.country,
            installedAtEpochMillis = 1_000L,
            hasDetail = download.hasDetail
        )
        stateFlow.update {
            it.copy(
                installed = it.installed + (countryId to record),
                downloads = it.downloads - countryId
            )
        }
        return OfflineDownloadExecutionResult.COMPLETED
    }
}
