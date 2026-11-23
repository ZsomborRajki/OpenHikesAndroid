package app.openhikes.core.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules and cancels background offline country downloads.
 */
interface OfflineCountryDownloadScheduler {
    fun enqueue(countryId: String)

    fun enqueueContinuation(countryId: String)

    fun cancel(countryId: String)
}

/**
 * Production [OfflineCountryDownloadScheduler] backed by AndroidX [WorkManager] with
 * unmetered Wi-Fi and non-low-battery constraints.
 */
@Singleton
internal class WorkManagerOfflineCountryDownloadScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) : OfflineCountryDownloadScheduler {
    override fun enqueue(countryId: String) {
        val request = buildWorkRequest(countryId)
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueWorkName(countryId),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    override fun enqueueContinuation(countryId: String) {
        val request = buildWorkRequest(countryId)
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueWorkName(countryId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    override fun cancel(countryId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(countryId))
    }

    companion object {
        const val WORK_TAG = "offline-country-download"

        fun uniqueWorkName(countryId: String): String = "offline-country-$countryId"

        fun buildWorkRequest(countryId: String): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()
            return OneTimeWorkRequestBuilder<OfflineCountryDownloadWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(OfflineCountryDownloadWorker.KEY_COUNTRY_ID to countryId))
                .addTag(WORK_TAG)
                .addTag(uniqueWorkName(countryId))
                .build()
        }
    }
}
