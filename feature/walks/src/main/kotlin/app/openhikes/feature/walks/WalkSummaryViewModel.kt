package app.openhikes.feature.walks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.common.DefaultDispatcher
import app.openhikes.core.data.HikeRepository
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.map.MapCameraCommand
import app.openhikes.core.map.MapClaim
import app.openhikes.core.map.MapClaimCoordinator
import app.openhikes.core.map.MapWalkCoverageOverlay
import app.openhikes.core.map.WalkOverlayState
import app.openhikes.core.model.GeoBoundingBox
import app.openhikes.core.model.Hike
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.HikeWalk
import app.openhikes.core.model.RouteProfile
import app.openhikes.core.model.RouteTint
import app.openhikes.core.model.TrailWalkRecord
import app.openhikes.core.model.UserSettings
import app.openhikes.core.model.climb
import app.openhikes.core.model.coveredPolylineSegments
import app.openhikes.core.ui.navigation.WalkSummaryRoute
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Scoped ViewModel for [WalkSummaryRoute] loading the finished walk, verifying route revision
 * alignment, and projecting covered stretches onto the live map when "Show on Map" is tapped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = WalkSummaryViewModel.Factory::class)
class WalkSummaryViewModel @AssistedInject constructor(
    @Assisted(ASSISTED_HIKE_ID) rawHikeId: String,
    @Assisted(ASSISTED_WALK_ID) val walkId: String,
    hikeRepository: HikeRepository,
    userSettingsRepository: UserSettingsRepository,
    private val mapClaimCoordinator: MapClaimCoordinator,
    private val walkOverlayState: WalkOverlayState,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted(ASSISTED_HIKE_ID) rawHikeId: String,
            @Assisted(ASSISTED_WALK_ID) walkId: String
        ): WalkSummaryViewModel
    }

    val hikeId: HikeId = HikeId(rawHikeId)
    private val routeKey = WalkSummaryRoute(hikeId = hikeId, walkId = walkId)

    @Volatile
    private var latestPrepared: PreparedWalkRoute? = null

    @Volatile
    private var latestWalk: HikeWalk? = null

    @Volatile
    private var latestTintHex: String = RouteTint.DEFAULT_HEX

    private val hikeFlow = hikeRepository.observeHike(hikeId).onEach { hike ->
        if (hike != null) {
            latestTintHex = hike.tintHex
        }
    }

    private val preparedFlow = hikeRepository.observeHike(hikeId)
        .map { it?.route }
        .distinctUntilChanged()
        .mapLatest { route ->
            if (route.isNullOrEmpty()) {
                null
            } else {
                val profile = RouteProfile.from(route)
                val revision = TrailWalkRecord.routeRevision(route)
                val ascent = profile.climb(0.0, profile.totalDistanceMeters)?.gainMeters
                PreparedWalkRoute(profile = profile, revision = revision, ascentMeters = ascent)
            }
        }
        .flowOn(defaultDispatcher)
        .onEach { latestPrepared = it }

    private val walkFlow = hikeRepository.observeWalk(walkId).onEach { latestWalk = it }

    val uiState: StateFlow<WalkSummaryUiState> = combine(
        hikeFlow,
        walkFlow,
        preparedFlow,
        userSettingsRepository.userSettings.catch { exception ->
            if (exception is IOException) {
                emit(UserSettings())
            } else {
                throw exception
            }
        }
    ) { hike: Hike?, walk: HikeWalk?, prepared: PreparedWalkRoute?, settings: UserSettings ->
        val routeChanged = walk != null && prepared != null && !walk.isAlong(prepared.revision)
        val canShow = walk != null && prepared != null && !routeChanged && walk.coverage.ranges.isNotEmpty()
        WalkSummaryUiState(
            hikeId = hikeId,
            walkId = walkId,
            hikeTitle = hike?.displayTitle.orEmpty(),
            tintHex = hike?.tintHex ?: RouteTint.DEFAULT_HEX,
            walk = walk,
            trailAscentMeters = prepared?.ascentMeters,
            distanceUnit = settings.distanceUnit,
            isRouteChanged = routeChanged,
            canShowOnMap = canShow,
            isLoaded = true
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = WalkSummaryUiState(hikeId = hikeId, walkId = walkId)
    )

    init {
        mapClaimCoordinator.registerClaim(
            MapClaim(
                routeKey = routeKey,
                depth = SUMMARY_CLAIM_DEPTH,
                selectedHikeId = hikeId
            )
        )
    }

    override fun onCleared() {
        walkOverlayState.clearIfHike(hikeId)
        mapClaimCoordinator.releaseClaim(routeKey)
    }

    fun onShowOnMap(onCollapseSheet: () -> Unit) {
        val walk = latestWalk
        val prepared = latestPrepared
        val ranges = if (walk != null && prepared != null && walk.isAlong(prepared.revision)) {
            walk.coverage.ranges
        } else {
            emptyList()
        }
        if (prepared == null || ranges.isEmpty()) return
        val tint = latestTintHex.ifBlank { RouteTint.DEFAULT_HEX }

        viewModelScope.launch {
            val (segments, bounds) = withContext(defaultDispatcher) {
                val polylines = prepared.profile.coveredPolylineSegments(ranges)
                val box = GeoBoundingBox.from(polylines.flatten()) ?: prepared.profile.boundingBox
                polylines to box
            }
            if (segments.isNotEmpty()) {
                walkOverlayState.show(
                    MapWalkCoverageOverlay(
                        hikeId = hikeId,
                        segments = segments,
                        tintHex = tint
                    )
                )
                if (bounds != null) {
                    mapClaimCoordinator.requestCameraCommand(
                        routeKey = routeKey,
                        command = MapCameraCommand.FitBounds(
                            id = mapClaimCoordinator.nextCommandId(),
                            bounds = bounds
                        )
                    )
                }
                onCollapseSheet()
            }
        }
    }

    private data class PreparedWalkRoute(val profile: RouteProfile, val revision: String, val ascentMeters: Double?)

    companion object {
        const val ASSISTED_HIKE_ID = "hikeId"
        const val ASSISTED_WALK_ID = "walkId"
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val SUMMARY_CLAIM_DEPTH = 2
    }
}
