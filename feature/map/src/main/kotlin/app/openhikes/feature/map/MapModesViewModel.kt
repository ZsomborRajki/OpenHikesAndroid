package app.openhikes.feature.map

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.openhikes.core.common.DefaultDispatcher
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.data.OfflineCountryRepository
import app.openhikes.core.data.UserSettingsRepository
import app.openhikes.core.location.LocationFeed
import app.openhikes.core.map.MapStylePreviewProvider
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.MapAttribution
import app.openhikes.core.model.MapProviderKeys
import app.openhikes.core.model.MapStyleCatalog
import app.openhikes.core.model.OfflineCountriesState
import app.openhikes.core.model.TilePreviewFrame
import app.openhikes.core.model.TilePreviewSource
import app.openhikes.core.model.UserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Coordinates Map Modes style selection, Hiking Routes toggle, installed Offline Maps summary,
 * attribution credits, and asynchronous raster tile preview loading.
 */
@HiltViewModel
class MapModesViewModel @Inject constructor(
    private val userSettingsRepository: UserSettingsRepository,
    offlineCountryRepository: OfflineCountryRepository,
    private val mapProviderKeys: MapProviderKeys,
    private val previewProvider: MapStylePreviewProvider,
    locationFeed: LocationFeed,
    launchConfigHolder: LaunchConfigHolder,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher
) : ViewModel() {
    /**
     * Snapped 2×2 tile block captured once when Map Modes opens so live GPS drift never causes
     * style cards to re-fetch or recompose continuously.
     */
    val previewFrame: TilePreviewFrame = TilePreviewFrame.around(
        coordinate = resolvePreviewCoordinate(locationFeed, launchConfigHolder)
    )

    private val saveErrorState = MutableStateFlow(false)
    private val previewsState = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())

    val previews: StateFlow<Map<String, ImageBitmap>> = previewsState.asStateFlow()

    val uiState: StateFlow<MapModesUiState> = combine(
        userSettingsRepository.userSettings.catch { exception ->
            if (exception is IOException) emit(UserSettings()) else throw exception
        },
        offlineCountryRepository.state,
        saveErrorState
    ) { settings, offlineState, saveError ->
        buildMapModesUiState(
            settings = settings,
            offlineState = offlineState,
            keys = mapProviderKeys,
            saveError = saveError
        )
    }
        .flowOn(defaultDispatcher)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = buildMapModesUiState(
                settings = UserSettings(),
                offlineState = offlineCountryRepository.state.value,
                keys = mapProviderKeys,
                saveError = false
            )
        )

    init {
        loadStylePreviews()
    }

    fun onSelectStyle(styleId: String) {
        val candidate = MapStyleCatalog.all.firstOrNull { it.id == styleId && it.isUsable(mapProviderKeys) }
            ?: return
        viewModelScope.launch {
            try {
                userSettingsRepository.setMapStyleId(candidate.id)
                saveErrorState.value = false
            } catch (_: IOException) {
                saveErrorState.value = true
            }
        }
    }

    fun onSetShowsHikingRoutes(enabled: Boolean) {
        viewModelScope.launch {
            try {
                userSettingsRepository.setShowsHikingRoutes(enabled)
                saveErrorState.value = false
            } catch (_: IOException) {
                saveErrorState.value = true
            }
        }
    }

    fun onDismissSaveError() {
        saveErrorState.value = false
    }

    private fun loadStylePreviews() {
        for (style in MapStyleCatalog.all) {
            if (TilePreviewSource.from(style, mapProviderKeys) !is TilePreviewSource.RasterTiles) continue
            viewModelScope.launch {
                val bitmap = previewProvider.loadPreview(style, previewFrame) ?: return@launch
                previewsState.update { current -> current + (style.id to bitmap) }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

private fun buildMapModesUiState(
    settings: UserSettings,
    offlineState: OfflineCountriesState,
    keys: MapProviderKeys,
    saveError: Boolean
): MapModesUiState {
    val selectedStyle = MapStyleCatalog.renderable(settings.mapStyleId, keys)
    val usableStyles = MapStyleCatalog.usableStyles(keys)
    val locale = Locale.getDefault()
    val installedNames = offlineState.installed.values
        .map { record -> record.country.localizedName(locale) }
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
    return MapModesUiState(
        styles = defaultStyleCards(selectedStyle = selectedStyle, keys = keys),
        selectedStyle = selectedStyle,
        showsHikingRoutes = settings.showsHikingRoutes,
        installedCountryNames = installedNames,
        installedByteCount = offlineState.installedByteCount,
        hasMissingApiKeyStyles = keys.hasMissingKeys(),
        attribution = MapAttribution.forMapModes(
            selected = selectedStyle,
            showsHikingRoutes = settings.showsHikingRoutes,
            picturedStyles = usableStyles
        ),
        saveError = saveError
    )
}

private fun resolvePreviewCoordinate(
    locationFeed: LocationFeed,
    launchConfigHolder: LaunchConfigHolder
): GeoCoordinate? {
    val config = launchConfigHolder.current.value
    return if (config.enableSimulatedLocation && config.mapStart != null) {
        config.mapStart
    } else {
        locationFeed.lastKnownCoordinate()
    }
}
