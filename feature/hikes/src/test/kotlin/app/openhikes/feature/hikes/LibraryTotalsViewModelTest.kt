package app.openhikes.feature.hikes

import app.cash.turbine.test
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.TestClock
import app.openhikes.core.testing.android.FakeHikeRepository
import app.openhikes.core.testing.android.FakeUserSettingsRepository
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryTotalsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun computesTotalsReflectsUnitChangesAndGuardsDeletedRecordOpens() = runTest {
        val t0 = Instant.parse("2026-05-10T08:00:00Z").toEpochMilli()
        val hike = Hike(
            id = HikeId("h-timed"),
            title = "Spring Crest",
            distanceMeters = 6_400.0,
            dateEpochMillis = t0,
            route = listOf(
                RouteCoordinate(47.70, 12.80, elevationMeters = 600.0, timestampEpochMillis = t0),
                RouteCoordinate(47.72, 12.82, elevationMeters = 950.0, timestampEpochMillis = t0 + 3_600_000L)
            )
        )
        val repo = FakeHikeRepository(listOf(hike))
        val settingsRepo = FakeUserSettingsRepository()
        val clock = TestClock(Instant.parse("2026-06-15T12:00:00Z"))
        val viewModel = LibraryTotalsViewModel(
            hikeRepository = repo,
            userSettingsRepository = settingsRepo,
            clock = clock,
            zoneIdProvider = { ZoneOffset.UTC }
        )

        viewModel.uiState.test {
            advanceUntilIdle()
            val loaded = expectMostRecentItem()
            val state = loaded.loadState as LibraryTotalsLoadState.Loaded
            assertThat(state.totals.year).isEqualTo(2026)
            assertThat(state.totals.thisYear.distanceMeters).isWithin(0.1).of(6_400.0)
            assertThat(state.totals.longest?.hikeId).isEqualTo(hike.id)
            assertThat(loaded.distanceUnit).isEqualTo(DistanceUnit.METRIC)

            settingsRepo.setDistanceUnit(DistanceUnit.IMPERIAL)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().distanceUnit).isEqualTo(DistanceUnit.IMPERIAL)

            val opened = mutableListOf<HikeId>()
            viewModel.onOpenRecordHike(hike.id) { opened.add(it) }
            advanceUntilIdle()
            assertThat(opened).containsExactly(hike.id)

            repo.deleteHike(hike.id)
            advanceUntilIdle()
            val afterDelete = expectMostRecentItem().loadState as LibraryTotalsLoadState.Loaded
            assertThat(afterDelete.totals.thisYear.outings).isEqualTo(0)
            assertThat(afterDelete.totals.longest).isNull()

            viewModel.onOpenRecordHike(hike.id) { opened.add(it) }
            advanceUntilIdle()
            assertThat(opened).containsExactly(hike.id)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun surfacesStorageFailureAndRecoversOnRetry() = runTest {
        val repo = FakeHikeRepository().apply {
            nextTotalsFailure = IOException("Disk unavailable")
        }
        val viewModel = LibraryTotalsViewModel(
            hikeRepository = repo,
            userSettingsRepository = FakeUserSettingsRepository(),
            clock = TestClock(Instant.parse("2026-06-15T12:00:00Z")),
            zoneIdProvider = { ZoneOffset.UTC }
        )

        viewModel.uiState.test {
            advanceUntilIdle()
            assertThat(expectMostRecentItem().loadState).isEqualTo(LibraryTotalsLoadState.Error)

            viewModel.onRetry()
            advanceUntilIdle()
            assertThat(expectMostRecentItem().loadState).isInstanceOf(LibraryTotalsLoadState.Loaded::class.java)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
