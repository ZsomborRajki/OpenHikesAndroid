package app.openhikes.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.openhikes.core.model.OfflineCountriesState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OfflineCountryDownloadWorkerTest {
    @Test
    fun workRequestEnforcesUnmeteredNetworkAndBatteryNotLowConstraints() {
        val request = WorkManagerOfflineCountryDownloadScheduler.buildWorkRequest("AT")
        assertThat(request.workSpec.constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        assertThat(request.workSpec.constraints.requiresBatteryNotLow()).isTrue()
        assertThat(request.workSpec.input.getString(OfflineCountryDownloadWorker.KEY_COUNTRY_ID)).isEqualTo("AT")
    }

    @Test
    fun workerMapsRepositoryExecutionOutcomesToWorkManagerResults() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stubRepo = StubOfflineRepository(OfflineDownloadExecutionResult.RETRY_LATER)
        val worker = buildWorker(context, "AT", stubRepo)
        assertThat(worker.doWork()).isEqualTo(ListenableWorker.Result.retry())
        assertThat(stubRepo.executedIds).containsExactly("AT")

        stubRepo.nextResult = OfflineDownloadExecutionResult.COMPLETED
        val secondWorker = buildWorker(context, "DE", stubRepo)
        assertThat(secondWorker.doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(stubRepo.executedIds).containsExactly("AT", "DE").inOrder()
    }

    private fun buildWorker(
        context: Context,
        countryId: String,
        repo: OfflineCountryRepository
    ): OfflineCountryDownloadWorker = TestListenableWorkerBuilder<OfflineCountryDownloadWorker>(context)
        .setInputData(workDataOf(OfflineCountryDownloadWorker.KEY_COUNTRY_ID to countryId))
        .setWorkerFactory(
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker = OfflineCountryDownloadWorker(appContext, workerParameters, repo)
            }
        )
        .build()

    private class StubOfflineRepository(var nextResult: OfflineDownloadExecutionResult) : OfflineCountryRepository {
        override val state: StateFlow<OfflineCountriesState> = MutableStateFlow(OfflineCountriesState())
        val executedIds = mutableListOf<String>()
        override suspend fun start() = Unit
        override suspend fun refreshCatalog() = Unit
        override suspend fun downloadCountry(countryId: String) = Unit
        override suspend fun retryDownload(countryId: String) = Unit
        override suspend fun repairCountry(countryId: String) = Unit
        override suspend fun cancelDownload(countryId: String) = Unit
        override suspend fun deleteCountry(countryId: String) = Unit
        override suspend fun setIncludesDetail(enabled: Boolean) = Unit
        override suspend fun runScheduledDownload(countryId: String): OfflineDownloadExecutionResult {
            executedIds.add(countryId)
            return nextResult
        }
    }
}
