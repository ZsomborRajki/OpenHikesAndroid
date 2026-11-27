package app.openhikes.feature.hikes

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import app.openhikes.core.common.LaunchConfig
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.common.gpx.GpxParseResult
import app.openhikes.core.common.gpx.GpxParser
import app.openhikes.core.location.TrailWalkEnd
import app.openhikes.core.location.TrailWalkPermissionIssue
import app.openhikes.core.location.TrailWalkProgressState
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.PhotoCaptureController
import app.openhikes.core.map.RouteHighlightState
import app.openhikes.core.map.TrailPhotosOverlayState
import app.openhikes.core.map.TrailPlacesOverlayState
import app.openhikes.core.model.ElevationFailure
import app.openhikes.core.model.ElevationFetchResult
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.InstalledCountryRecord
import app.openhikes.core.model.MapProviderKeys
import app.openhikes.core.model.MapStyleId
import app.openhikes.core.model.OfflineBounds
import app.openhikes.core.model.OfflineCountriesState
import app.openhikes.core.model.OfflineCountry
import app.openhikes.core.model.OfflineCountryFile
import app.openhikes.core.model.OfflineFileRole
import app.openhikes.core.model.RouteCoordinate
import app.openhikes.core.model.RouteElevationSource
import app.openhikes.core.model.TrailGraphProvider
import app.openhikes.core.model.TrailPointSource
import app.openhikes.core.model.TrailWalkPhase
import app.openhikes.core.model.UserSettings
import app.openhikes.core.testing.FakeRouteElevationSource
import app.openhikes.core.testing.FakeTrailGraphProvider
import app.openhikes.core.testing.FakeTrailPointSource
import app.openhikes.core.testing.MainDispatcherRule
import app.openhikes.core.testing.TestFixtures
import app.openhikes.core.testing.android.FakeHikeRepository
import app.openhikes.core.testing.android.FakeOfflineCountryRepository
import app.openhikes.core.testing.android.FakeRecordingController
import app.openhikes.core.testing.android.FakeTrailWalkController
import app.openhikes.core.testing.android.FakeUserSettingsRepository
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HikeDetailViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun preparesProfileDispatchesFitBoundsUpdatesScrubRenameExportAndReleasesOnClear() = runTest {
        val hike = sampleDetailHike()
        val repo = FakeHikeRepository(listOf(hike))
        val coordinator = MapClaimCoordinator()
        val highlightState = RouteHighlightState()
        val viewModel = createViewModel(
            hike = hike,
            repo = repo,
            coordinator = coordinator,
            highlightState = highlightState
        )
        val store = ViewModelStore().apply { put("detail", viewModel) }

        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()
            val loaded = expectMostRecentItem()
            assertThat(loaded.isLoaded).isTrue()
            assertThat(loaded.profile?.samples).hasSize(2)
            assertThat(loaded.statistics?.elevationGainMeters).isWithin(1e-6).of(30.0)
            assertThat(coordinator.activeClaim.value?.selectedHikeId).isEqualTo(hike.id)
            assertThat(coordinator.pendingCameraCommand.value).isInstanceOf(MapCameraCommand.FitBounds::class.java)

            coordinator.consumeCameraCommand(coordinator.pendingCameraCommand.value!!.id)
            viewModel.onZoomToRoute()
            assertThat(coordinator.pendingCameraCommand.value).isInstanceOf(MapCameraCommand.FitBounds::class.java)

            viewModel.onScrubDistance(loaded.profile!!.totalDistanceMeters)
            assertThat(viewModel.scrubDistanceMeters.value).isWithin(1e-6).of(loaded.profile.totalDistanceMeters)
            assertThat(highlightState.highlight.value?.hikeId).isEqualTo(hike.id)

            repo.nextRenameFailure = IOException("Disk error")
            viewModel.onStartRename()
            viewModel.onTitleDraftChange("Sunny Loop")
            viewModel.onCommitRename()
            advanceUntilIdle()
            assertThat(expectMostRecentItem().actionError).isEqualTo(HikeDetailError.RENAME_FAILED)

            viewModel.onCommitRename()
            advanceUntilIdle()
            assertThat(expectMostRecentItem().hike?.displayTitle).isEqualTo("Sunny Loop")

            viewModel.onRequestExportGpx()
            advanceUntilIdle()
            assertThat(expectMostRecentItem().pendingExport?.xml).contains("Sunny Loop")
            viewModel.onExportDestinationPicked("content://exports/out.gpx")
            advanceUntilIdle()
            assertThat(expectMostRecentItem().exportSuccess).isTrue()
            assertThat(repo.lastExportedUri?.first).isEqualTo("content://exports/out.gpx")

            cancelAndIgnoreRemainingEvents()
        }

        store.clear()
        assertThat(coordinator.activeClaim.value).isNull()
        assertThat(highlightState.highlight.value).isNull()
    }

    @Test
    fun controlsWalkLifecycleSyncsFollowScrubAndHandlesEndOutcomes() = runTest {
        val hike = sampleDetailHike()
        val walkController = FakeTrailWalkController()
        val viewModel = createViewModel(hike = hike, walkController = walkController)
        val openedSummaries = mutableListOf<Pair<HikeId, String>>()

        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()

            viewModel.onWalkAction(HikeWalkAction.ToggleWalk) { id, walkId -> openedSummaries.add(id to walkId) }
            advanceUntilIdle()
            assertThat(expectMostRecentItem().walkSession.phase).isEqualTo(TrailWalkPhase.FOLLOWING)

            walkController.setProgress(followMarkerProgress(hike.id, 120.0))
            advanceUntilIdle()
            assertThat(viewModel.scrubDistanceMeters.value).isWithin(1e-3).of(120.0)

            viewModel.onWalkAction(HikeWalkAction.SetAutoFollow(false)) { _, _ -> }
            advanceUntilIdle()
            walkController.setProgress(followMarkerProgress(hike.id, 200.0))
            advanceUntilIdle()
            assertThat(viewModel.scrubDistanceMeters.value).isWithin(1e-3).of(120.0)
            assertThat(walkController.lastAutoFollowChange).isEqualTo(hike.id to false)

            viewModel.onWalkAction(HikeWalkAction.ToggleWalk) { _, _ -> }
            advanceUntilIdle()
            assertThat(expectMostRecentItem().walkSession.phase).isEqualTo(TrailWalkPhase.PAUSED)

            walkController.nextEndResult = TrailWalkEnd.Refused
            viewModel.onWalkAction(HikeWalkAction.RequestEndWalk) { _, _ -> }
            advanceUntilIdle()
            assertThat(expectMostRecentItem().showEndWalkDialog).isTrue()

            viewModel.onWalkAction(HikeWalkAction.ConfirmEndWalk) { id, walkId -> openedSummaries.add(id to walkId) }
            advanceUntilIdle()
            assertThat(expectMostRecentItem().showEndWalkRefusedDialog).isTrue()
            assertThat(openedSummaries).isEmpty()

            viewModel.onWalkAction(HikeWalkAction.DismissEndRefused) { _, _ -> }
            viewModel.onWalkAction(HikeWalkAction.ConfirmEndWalk) { id, walkId -> openedSummaries.add(id to walkId) }
            advanceUntilIdle()
            assertThat(openedSummaries).hasSize(1)
            assertThat(openedSummaries.single().first).isEqualTo(hike.id)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun requestsPreciseLocationBeforeStartingOrResumingWalkWhenNotSimulated() = runTest {
        val hike = sampleDetailHike()
        val walkController = FakeTrailWalkController()
        val viewModel = createViewModel(
            hike = hike,
            walkController = walkController,
            launchConfigHolder = LaunchConfigHolder(LaunchConfig.Production)
        )

        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()

            // Start Hike requests permission first instead of starting immediately.
            viewModel.onWalkAction(HikeWalkAction.ToggleWalk) { _, _ -> }
            advanceUntilIdle()
            assertThat(expectMostRecentItem().requestLocationPermission).isTrue()
            assertThat(walkController.startByHandCalls).isEqualTo(0)

            // Coarse-only grant reports precise-location error and never starts the walk.
            viewModel.onWalkAction(
                HikeWalkAction.PermissionResult(grantedFine = false, grantedCoarse = true)
            ) { _, _ -> }
            advanceUntilIdle()
            val coarseState = expectMostRecentItem()
            assertThat(coarseState.requestLocationPermission).isFalse()
            assertThat(walkController.startByHandCalls).isEqualTo(0)
            assertThat(coarseState.walkSession.permissionIssue)
                .isEqualTo(TrailWalkPermissionIssue.PRECISE_LOCATION_REQUIRED)

            // Fine grant starts the walk.
            viewModel.onWalkAction(HikeWalkAction.ToggleWalk) { _, _ -> }
            viewModel.onWalkAction(
                HikeWalkAction.PermissionResult(grantedFine = true, grantedCoarse = true)
            ) { _, _ -> }
            advanceUntilIdle()
            assertThat(walkController.startByHandCalls).isEqualTo(1)
            assertThat(expectMostRecentItem().walkSession.phase).isEqualTo(TrailWalkPhase.FOLLOWING)

            // Pause then Resume also checks permission before calling resume().
            viewModel.onWalkAction(HikeWalkAction.ToggleWalk) { _, _ -> }
            advanceUntilIdle()
            assertThat(expectMostRecentItem().walkSession.phase).isEqualTo(TrailWalkPhase.PAUSED)

            viewModel.onWalkAction(HikeWalkAction.ToggleWalk) { _, _ -> }
            advanceUntilIdle()
            assertThat(expectMostRecentItem().requestLocationPermission).isTrue()
            assertThat(walkController.resumeCalls).isEqualTo(0)

            viewModel.onWalkAction(
                HikeWalkAction.PermissionResult(grantedFine = true, grantedCoarse = true)
            ) { _, _ -> }
            advanceUntilIdle()
            assertThat(walkController.resumeCalls).isEqualTo(1)
            assertThat(expectMostRecentItem().walkSession.phase).isEqualTo(TrailWalkPhase.FOLLOWING)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun missingApiKeyFallbackKeepsOfflineCoverageEnabledOnHikeDetail() = runTest {
        val hike = sampleDetailHike()
        val keylessViewModel = createViewModel(
            hike = hike,
            userSettingsRepository = FakeUserSettingsRepository(
                initialSettings = UserSettings(mapStyleId = MapStyleId.STADIA_OUTDOORS)
            ),
            mapProviderKeys = MapProviderKeys.Empty
        )
        keylessViewModel.uiState.test {
            advanceUntilIdle()
            assertThat(expectMostRecentItem().offlineCoverage.isOpenHikesMap).isTrue()
            cancelAndIgnoreRemainingEvents()
        }

        val keyedViewModel = createViewModel(
            hike = hike,
            userSettingsRepository = FakeUserSettingsRepository(
                initialSettings = UserSettings(mapStyleId = MapStyleId.STADIA_OUTDOORS)
            ),
            mapProviderKeys = MapProviderKeys(stadiaApiKey = "demo-key")
        )
        keyedViewModel.uiState.test {
            advanceUntilIdle()
            assertThat(expectMostRecentItem().offlineCoverage.isOpenHikesMap).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun exportArchiveComputesFilenameFirstAndStreamsOnlyAfterDestinationIsPicked() = runTest {
        val hike = sampleDetailHike()
        val repo = FakeHikeRepository(listOf(hike))
        val viewModel = createViewModel(hike = hike, repo = repo)

        viewModel.uiState.test {
            awaitItem()
            advanceUntilIdle()

            viewModel.onRequestExportArchive()
            advanceUntilIdle()
            val requested = expectMostRecentItem()
            assertThat(requested.pendingArchiveFileName).isNotNull()
            assertThat(requested.pendingArchiveFileName).startsWith("Thumsee Loop-")
            assertThat(requested.pendingArchiveFileName).endsWith(".zip")
            assertThat(repo.exportHikeArchiveCalls).isEqualTo(0)
            assertThat(repo.exportHikeArchiveToUriCalls).isEqualTo(0)

            // Cancelling the document picker never builds the ZIP archive.
            viewModel.onArchiveExportDestinationPicked(null)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().pendingArchiveFileName).isNull()
            assertThat(repo.exportHikeArchiveCalls).isEqualTo(0)
            assertThat(repo.exportHikeArchiveToUriCalls).isEqualTo(0)

            // Picking a destination streams the archive exactly once.
            viewModel.onRequestExportArchive()
            advanceUntilIdle()
            viewModel.onArchiveExportDestinationPicked("content://exports/thumsee.zip")
            advanceUntilIdle()
            assertThat(expectMostRecentItem().exportSuccess).isTrue()
            assertThat(repo.exportHikeArchiveCalls).isEqualTo(0)
            assertThat(repo.exportHikeArchiveToUriCalls).isEqualTo(1)
            assertThat(repo.lastExportedArchiveUri?.first).isEqualTo("content://exports/thumsee.zip")

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun analyzesAndPersistsSurfaceAndDifficultyBreakdownsOnceOnOpen() = runTest {
        val parsed = GpxParser.parseSingle(TestFixtures.thumseeLoopFastGpxBytes()) as GpxParseResult.Success
        val route = parsed.value.route
        val hike = sampleDetailHike().copy(route = route)
        val repo = FakeHikeRepository(listOf(hike))
        val provider = FakeTrailGraphProvider.fromThumseeLoopFixture()
        val viewModel = createViewModel(hike = hike, repo = repo, trailGraphProvider = provider)

        viewModel.uiState.test {
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertThat(state.surfaceBreakdown).isNotNull()
            assertThat(state.difficultyBreakdown).isNotNull()
            assertThat(state.surfaceBreakdown!!.surveyedFraction).isAtLeast(0.95)
            assertThat(state.difficultyBreakdown!!.surveyedFraction).isAtLeast(0.95)
            assertThat(provider.assembledGraphCalls).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }

        // Re-opening after breakdowns were persisted does not ask the graph provider again.
        val secondViewModel = createViewModel(hike = hike, repo = repo, trailGraphProvider = provider)
        secondViewModel.uiState.test {
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertThat(state.surfaceBreakdown).isNotNull()
            assertThat(state.difficultyBreakdown).isNotNull()
            assertThat(provider.assembledGraphCalls).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun reanalyzesRevertedRouteWithinSameDetailSession() = runTest {
        val parsed = GpxParser.parseSingle(TestFixtures.thumseeLoopFastGpxBytes()) as GpxParseResult.Success
        val originalRoute = parsed.value.route
        val distances = app.openhikes.core.model.RouteTrim.distances(originalRoute)
        val cutIndex = (originalRoute.size * 2) / 3
        val editedLine = requireNotNull(
            app.openhikes.core.model.EditedLine.build(
                whole = originalRoute,
                distances = distances,
                cuts = app.openhikes.core.model.EditedLine.Cuts(range = 0.0..distances[cutIndex]),
                replacing = originalRoute
            )
        )
        val hike = sampleDetailHike().copy(route = originalRoute)
        val repo = FakeHikeRepository(listOf(hike))
        val provider = FakeTrailGraphProvider.fromThumseeLoopFixture()
        val viewModel = createViewModel(hike = hike, repo = repo, trailGraphProvider = provider)

        viewModel.uiState.test {
            advanceUntilIdle()
            val initialState = expectMostRecentItem()
            assertThat(initialState.surfaceBreakdown).isNotNull()
            assertThat(initialState.difficultyBreakdown).isNotNull()
            assertThat(provider.assembledGraphCalls).isEqualTo(1)

            // Save a route trim while keeping the same detail ViewModel alive.
            assertThat(repo.applyRouteEdit(hike.id, originalRoute, editedLine))
                .isEqualTo(app.openhikes.core.model.RouteEditOutcome.Saved(editedLine.route))
            advanceUntilIdle()
            assertThat(expectMostRecentItem().surfaceBreakdown).isNotNull()
            assertThat(provider.assembledGraphCalls).isEqualTo(2)

            // Reverting restores the original route revision and clears stored breakdowns;
            // the same ViewModel must re-analyze the restored revision and repopulate both cards.
            assertThat(repo.revertRouteEdit(hike.id, editedLine.route))
                .isEqualTo(app.openhikes.core.model.RouteEditOutcome.Saved(originalRoute))
            advanceUntilIdle()
            val revertedState = expectMostRecentItem()
            assertThat(revertedState.surfaceBreakdown).isNotNull()
            assertThat(revertedState.difficultyBreakdown).isNotNull()
            assertThat(provider.assembledGraphCalls).isEqualTo(3)

            // Unrelated edits (e.g. rename) do not trigger redundant re-analysis.
            repo.renameHike(hike.id, "Renamed Thumsee")
            advanceUntilIdle()
            assertThat(provider.assembledGraphCalls).isEqualTo(3)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun fillsFlatHikeElevationOnceShowsReadingStateAndLeavesMeasuredHikesUntouched() = runTest {
        val flatHike = sampleFlatDetailHike()
        val repo = FakeHikeRepository(listOf(flatHike))
        val gate = CompletableDeferred<Unit>()
        val elevationSource = FakeRouteElevationSource(cannedHeights = listOf(510.0, 590.0)).apply {
            this.gate = gate
        }
        val viewModel = createViewModel(hike = flatHike, repo = repo, routeElevationSource = elevationSource)

        viewModel.uiState.test {
            advanceUntilIdle()
            val reading = expectMostRecentItem()
            assertThat(reading.isReadingElevation).isTrue()
            assertThat(reading.profile?.samples.orEmpty()).isEmpty()
            assertThat(elevationSource.requestCount).isEqualTo(1)

            gate.complete(Unit)
            advanceUntilIdle()
            val filled = expectMostRecentItem()
            assertThat(filled.isReadingElevation).isFalse()
            assertThat(filled.profile?.samples).hasSize(2)
            assertThat(filled.statistics?.elevationGainMeters).isWithin(1e-6).of(80.0)

            // Renaming an already-filled hike does not request elevation again.
            repo.renameHike(flatHike.id, "Renamed Flat")
            advanceUntilIdle()
            assertThat(elevationSource.requestCount).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }

        // Measured hike never asks the elevation source.
        val measuredSource = FakeRouteElevationSource(cannedHeights = listOf(900.0, 950.0))
        val measuredVm = createViewModel(hike = sampleDetailHike(), routeElevationSource = measuredSource)
        measuredVm.uiState.test {
            advanceUntilIdle()
            assertThat(expectMostRecentItem().statistics?.elevationGainMeters).isWithin(1e-6).of(30.0)
            assertThat(measuredSource.requestCount).isEqualTo(0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun handlesRefusedAndWithheldElevationGracefullyWithoutRetryingSameRevision() = runTest {
        val flatHike = sampleFlatDetailHike()
        val repo = FakeHikeRepository(listOf(flatHike))
        val refusedSource = FakeRouteElevationSource(failure = ElevationFailure.UNREACHABLE)
        val viewModel = createViewModel(hike = flatHike, repo = repo, routeElevationSource = refusedSource)

        viewModel.uiState.test {
            advanceUntilIdle()
            val afterRefusal = expectMostRecentItem()
            assertThat(afterRefusal.isReadingElevation).isFalse()
            assertThat(afterRefusal.profile?.samples.orEmpty()).isEmpty()
            assertThat(refusedSource.requestCount).isEqualTo(1)

            // Unrelated rename does not retry the failed elevation lookup on the same route revision.
            repo.renameHike(flatHike.id, "Still Flat")
            advanceUntilIdle()
            assertThat(refusedSource.requestCount).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }

        val withheldSource = FakeRouteElevationSource(
            handler = { coords -> ElevationFetchResult.Heights(List(coords.size) { Double.NaN }) }
        )
        val withheldVm = createViewModel(hike = flatHike, routeElevationSource = withheldSource)
        withheldVm.uiState.test {
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertThat(state.isReadingElevation).isFalse()
            assertThat(state.profile?.samples.orEmpty()).isEmpty()
            assertThat(withheldSource.requestCount).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun retriesElevationFillWhenOfflineCountryArchiveInstallsWhileFlatHikeIsOpen() = runTest {
        val flatHike = sampleFlatDetailHike()
        val repo = FakeHikeRepository(listOf(flatHike))
        val offlineRepo = FakeOfflineCountryRepository()
        val elevationSource = FakeRouteElevationSource(failure = ElevationFailure.UNREACHABLE)
        val viewModel = createViewModel(
            hike = flatHike,
            repo = repo,
            offlineCountryRepository = offlineRepo,
            routeElevationSource = elevationSource
        )

        viewModel.uiState.test {
            advanceUntilIdle()
            assertThat(expectMostRecentItem().profile?.samples.orEmpty()).isEmpty()
            assertThat(elevationSource.requestCount).isEqualTo(1)

            // Installing an offline country with an elevation file triggers a fresh lookup on the still-flat hike.
            elevationSource.failure = null
            elevationSource.cannedHeights = listOf(520.0, 610.0)
            val elevationFile = app.openhikes.core.model.OfflineCountryFile(
                role = app.openhikes.core.model.OfflineFileRole.ELEVATION,
                path = "countries/AT-elevation-20261126.pmtiles",
                byteCount = 1_000L,
                sha256 = "0".repeat(64),
                minimumZoom = 12,
                maximumZoom = 12
            )
            val country = app.openhikes.core.model.OfflineCountry(
                id = "AT",
                build = "20261126",
                schema = 4,
                bounds = app.openhikes.core.model.OfflineBounds(9.0, 46.0, 17.0, 49.0),
                outline = emptyList(),
                files = listOf(elevationFile)
            )
            offlineRepo.setState(
                app.openhikes.core.model.OfflineCountriesState(
                    isPrepared = true,
                    installed = mapOf(
                        "AT" to app.openhikes.core.model.InstalledCountryRecord(
                            country = country,
                            installedAtEpochMillis = 1_000L
                        )
                    )
                )
            )
            advanceUntilIdle()

            val filled = expectMostRecentItem()
            assertThat(filled.profile?.samples).hasSize(2)
            assertThat(filled.statistics?.elevationGainMeters).isWithin(1e-6).of(90.0)
            assertThat(elevationSource.requestCount).isEqualTo(2)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun offlineCoverageQuotesActualRepairFilesWhenBrokenOptionalArchiveIgnoredBySwitches() = runTest {
        val hike = sampleDetailHike()
        val offlineRepo = FakeOfflineCountryRepository()
        val austria = OfflineCountry(
            id = "AT",
            build = "20261126",
            schema = 4,
            bounds = OfflineBounds(9.0, 46.0, 17.0, 49.0),
            outline = listOf(
                listOf(listOf(9.0, 46.0), listOf(17.0, 46.0), listOf(17.0, 49.0), listOf(9.0, 49.0), listOf(9.0, 46.0))
            ),
            files = listOf(
                OfflineCountryFile(
                    OfflineFileRole.BASEMAP,
                    "countries/AT-20261126.pmtiles",
                    100_000_000L,
                    "0".repeat(64),
                    0,
                    14
                ),
                OfflineCountryFile(
                    OfflineFileRole.DETAIL,
                    "countries/AT-detail-20261126.pmtiles",
                    50_000_000L,
                    "1".repeat(64),
                    15,
                    15
                ),
                OfflineCountryFile(
                    OfflineFileRole.ELEVATION,
                    "countries/AT-elevation-20261126.pmtiles",
                    30_000_000L,
                    "2".repeat(64),
                    12,
                    12
                )
            )
        )
        offlineRepo.setState(
            OfflineCountriesState(
                isPrepared = true,
                offered = listOf(austria),
                installed = mapOf(
                    "AT" to InstalledCountryRecord(austria, 1_000L, hasDetail = false, hasElevation = true)
                ),
                unopenable = mapOf("AT" to setOf(OfflineFileRole.BASEMAP, OfflineFileRole.ELEVATION)),
                includesDetail = true,
                includesElevation = false
            )
        )
        val viewModel = createViewModel(hike = hike, offlineCountryRepository = offlineRepo)

        viewModel.uiState.test {
            advanceUntilIdle()
            val coverage = expectMostRecentItem().offlineCoverage
            assertThat(coverage.missing.map { it.id }).containsExactly("AT")
            assertThat(coverage.missingByteCounts["AT"]).isEqualTo(130_000_000L)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun createViewModel(
        hike: Hike,
        repo: FakeHikeRepository = FakeHikeRepository(listOf(hike)),
        userSettingsRepository: FakeUserSettingsRepository = FakeUserSettingsRepository(),
        offlineCountryRepository: FakeOfflineCountryRepository = FakeOfflineCountryRepository(),
        mapProviderKeys: MapProviderKeys = MapProviderKeys.Empty,
        walkController: FakeTrailWalkController = FakeTrailWalkController(),
        coordinator: MapClaimCoordinator = MapClaimCoordinator(),
        highlightState: RouteHighlightState = RouteHighlightState(),
        trailPlacesOverlayState: TrailPlacesOverlayState = TrailPlacesOverlayState(),
        trailPhotosOverlayState: TrailPhotosOverlayState = TrailPhotosOverlayState(),
        photoCaptureController: PhotoCaptureController = PhotoCaptureController(),
        trailPointSource: TrailPointSource = FakeTrailPointSource(),
        trailGraphProvider: TrailGraphProvider = FakeTrailGraphProvider(isAvailable = false),
        routeElevationSource: RouteElevationSource = FakeRouteElevationSource(isAvailable = false),
        launchConfigHolder: LaunchConfigHolder = LaunchConfigHolder(LaunchConfig(enableSimulatedLocation = true))
    ): HikeDetailViewModel = HikeDetailViewModel(
        rawHikeId = hike.id.value,
        hikeRepository = repo,
        userSettingsRepository = userSettingsRepository,
        offlineCountryRepository = offlineCountryRepository,
        trailWalkController = walkController,
        recordingController = FakeRecordingController(),
        mapClaimCoordinator = coordinator,
        routeHighlightState = highlightState,
        trailPlacesOverlayState = trailPlacesOverlayState,
        trailPhotosOverlayState = trailPhotosOverlayState,
        photoCaptureController = photoCaptureController,
        trailPointSource = trailPointSource,
        trailGraphProvider = trailGraphProvider,
        routeElevationSource = routeElevationSource,
        mapProviderKeys = mapProviderKeys,
        launchConfigHolder = launchConfigHolder,
        defaultDispatcher = mainDispatcherRule.testDispatcher
    )

    private fun sampleFlatDetailHike(): Hike = Hike(
        id = HikeId("flat-1"),
        title = "Drawn Flat Trail",
        distanceMeters = 1500.0,
        dateEpochMillis = 20_000L,
        route = listOf(
            RouteCoordinate(47.7180, 12.8300),
            RouteCoordinate(47.7200, 12.8320)
        )
    )

    private fun followMarkerProgress(hikeId: HikeId, distanceMeters: Double): TrailWalkProgressState =
        TrailWalkProgressState(
            hikeId = hikeId,
            followMarkerHikeId = hikeId,
            followMarkerDistanceMeters = distanceMeters
        )

    private fun sampleDetailHike(): Hike = Hike(
        id = HikeId("h1"),
        title = "Thumsee Loop",
        distanceMeters = 1500.0,
        dateEpochMillis = 20_000L,
        route = listOf(
            RouteCoordinate(47.7180, 12.8300, elevationMeters = 530.0, timestampEpochMillis = 0L),
            RouteCoordinate(47.7200, 12.8320, elevationMeters = 560.0, timestampEpochMillis = 600_000L)
        )
    )
}
