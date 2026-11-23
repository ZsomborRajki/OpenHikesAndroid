package app.openhikes.core.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Background [CoroutineWorker] executing a queued offline country PMTiles download, SHA-256
 * verification, and atomic index installation via [OfflineCountryRepository].
 */
@HiltWorker
class OfflineCountryDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val offlineCountryRepository: OfflineCountryRepository
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val countryId = inputData.getString(KEY_COUNTRY_ID)?.takeIf { it.isNotBlank() }
            ?: return Result.failure()
        return when (offlineCountryRepository.runScheduledDownload(countryId)) {
            OfflineDownloadExecutionResult.RETRY_LATER -> Result.retry()
            OfflineDownloadExecutionResult.COMPLETED,
            OfflineDownloadExecutionResult.FAILED,
            OfflineDownloadExecutionResult.CANCELLED -> Result.success()
        }
    }

    companion object {
        const val KEY_COUNTRY_ID = "country_id"
    }
}
