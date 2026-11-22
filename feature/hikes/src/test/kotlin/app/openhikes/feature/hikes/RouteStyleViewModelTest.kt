package app.openhikes.feature.hikes

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.android.FakeHikeRepository
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouteStyleViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun updatesTintBorderWidthAndPatternAndReleasesClaimOnClear() = runTest {
        val hike = Hike(
            id = HikeId("h1"),
            title = "Style Loop",
            distanceMeters = 500.0,
            dateEpochMillis = 10_000L,
            route = listOf(RouteCoordinate(47.7, 12.8), RouteCoordinate(47.71, 12.81))
        )
        val repo = FakeHikeRepository(listOf(hike))
        val coordinator = MapClaimCoordinator()
        val viewModel = RouteStyleViewModel(
            rawHikeId = hike.id.value,
            hikeRepository = repo,
            mapClaimCoordinator = coordinator
        )
        val store = ViewModelStore().apply { put("style", viewModel) }

        viewModel.uiState.test {
            awaitItem() // initial
            advanceUntilIdle()
            val loaded = expectMostRecentItem()
            assertThat(loaded.isLoaded).isTrue()
            assertThat(coordinator.activeClaim.value?.selectedHikeId).isEqualTo(hike.id)

            viewModel.onTintSelected("#D85A17")
            advanceUntilIdle()
            assertThat(expectMostRecentItem().tintHex).isEqualTo("#D85A17")

            viewModel.onBorderSelected(RouteBorder.WHITE_HEX)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().borderHex).isEqualTo(RouteBorder.WHITE_HEX)

            viewModel.onWidthChanged(7.0)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().widthDp).isEqualTo(7.0)

            viewModel.onPatternSelected(RouteLinePattern.DASHED)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().linePattern).isEqualTo(RouteLinePattern.DASHED)

            repo.nextStyleFailure = IOException("Read-only")
            viewModel.onPatternSelected(RouteLinePattern.DOTTED)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().hasSaveError).isTrue()
            viewModel.onDismissError()
            advanceUntilIdle()
            assertThat(expectMostRecentItem().hasSaveError).isFalse()

            cancelAndIgnoreRemainingEvents()
        }

        store.clear()
        assertThat(coordinator.activeClaim.value).isNull()
    }
}
