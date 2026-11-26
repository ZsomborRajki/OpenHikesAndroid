package app.openhikes.feature.places

import app.cash.turbine.test
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.testing.FakeTrailPointSource
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.android.FakeHikeRepository
import app.openhikes.core.testing.android.FakeUserSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingAddPlaceViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun suggestsCloseMappedPlacesAndAddsPickedSuggestionToRecordingDraft() = runTest {
        val hikeId = HikeId("rec-1")
        val here = GeoCoordinate(47.71882, 12.83115) // right beside Thumsee Car Park & Viewpoint
        val repo = FakeHikeRepository(
            listOf(
                Hike(
                    id = hikeId,
                    title = "Recording",
                    distanceMeters = 0.0,
                    dateEpochMillis = 10_000L,
                    isRecording = true
                )
            )
        )
        val viewModel = RecordingAddPlaceViewModel(
            rawHikeId = hikeId.value,
            latitude = here.latitude,
            longitude = here.longitude,
            hikeRepository = repo,
            userSettingsRepository = FakeUserSettingsRepository(),
            trailPointSource = FakeTrailPointSource()
        )

        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val loaded = expectMostRecentItem()
            assertThat(loaded.suggestions.map { it.place.name }).contains("Thumsee Car Park")
            // Thumsee Kopf (~760 m away) is outside the 150 m suggestion radius.
            assertThat(loaded.suggestions.map { it.place.name }).doesNotContain("Thumsee Kopf")

            val picked = loaded.suggestions.first { it.place.name == "Thumsee Car Park" }.place
            viewModel.onPickSuggestion(picked)
            advanceUntilIdle()

            val afterPick = expectMostRecentItem()
            val persisted = repo.observeHike(hikeId).first()!!.places.single()
            assertThat(persisted.name).isEqualTo("Thumsee Car Park")
            assertThat(persisted.id).isNotEqualTo(picked.id)
            assertThat(afterPick.openedPlaceId).isEqualTo(persisted.id)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
