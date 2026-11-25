package app.openhikes.feature.map

import android.icu.text.ListFormatter
import androidx.compose.runtime.Immutable
import app.openhikes.core.model.MapAttribution
import app.openhikes.core.model.MapProviderKeys
import app.openhikes.core.model.MapStyle
import app.openhikes.core.model.MapStyleCatalog
import app.openhikes.core.model.UserSettings
import app.openhikes.core.ui.format.StorageSizeFormatter
import java.util.Locale

/**
 * Presentation model for a single map style card inside [MapModesScreen].
 */
@Immutable
data class MapStyleCardUiModel(val style: MapStyle, val isSelected: Boolean, val isUsable: Boolean)

/**
 * Immutable UI state rendered by [MapModesScreen].
 */
@Immutable
data class MapModesUiState(
    val styles: List<MapStyleCardUiModel> = defaultStyleCards(),
    val selectedStyle: MapStyle = MapStyleCatalog.default,
    val showsHikingRoutes: Boolean = UserSettings.DEFAULT_SHOWS_HIKING_ROUTES,
    val installedCountryNames: List<String> = emptyList(),
    val installedByteCount: Long = 0L,
    val hasMissingApiKeyStyles: Boolean = MapProviderKeys.Empty.hasMissingKeys(),
    val attribution: MapAttribution = MapAttribution.forMapModes(
        selected = MapStyleCatalog.default,
        showsHikingRoutes = UserSettings.DEFAULT_SHOWS_HIKING_ROUTES,
        picturedStyles = MapStyleCatalog.usableStyles(MapProviderKeys.Empty)
    ),
    val saveError: Boolean = false
) {
    val selectedStyleIndex: Int
        get() = styles.indexOfFirst { it.isSelected }.coerceAtLeast(0)
}

internal fun defaultStyleCards(
    selectedStyle: MapStyle = MapStyleCatalog.default,
    keys: MapProviderKeys = MapProviderKeys.Empty
): List<MapStyleCardUiModel> = MapStyleCatalog.all.map { style ->
    MapStyleCardUiModel(
        style = style,
        isSelected = style.id == selectedStyle.id,
        isUsable = style.isUsable(keys)
    )
}

internal fun formatInstalledCountriesSummary(
    names: List<String>,
    installedByteCount: Long,
    emptySummary: String,
    installedTemplate: (joinedNames: String, formattedSize: String) -> String,
    locale: Locale = Locale.getDefault()
): String {
    if (names.isEmpty()) return emptySummary
    val joined = ListFormatter.getInstance(locale).format(names)
    val size = StorageSizeFormatter.formatBytes(installedByteCount, locale)
    return installedTemplate(joined, size)
}
