package app.openhikes.core.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.openhikes.core.model.MapStyleId
import app.openhikes.core.ui.R

/**
 * Localized display name for a [MapStyleId] shared across Map Modes and Offline Maps.
 */
@Composable
fun mapStyleDisplayName(styleId: String): String = when (styleId) {
    MapStyleId.OPENHIKES -> stringResource(R.string.map_style_openhikes_name)
    MapStyleId.OPENFREEMAP -> stringResource(R.string.map_style_openfreemap_name)
    MapStyleId.OSM_RASTER -> stringResource(R.string.map_style_osm_raster_name)
    MapStyleId.STADIA_OUTDOORS -> stringResource(R.string.map_style_stadia_outdoors_name)
    MapStyleId.STAMEN_TERRAIN -> stringResource(R.string.map_style_stamen_terrain_name)
    MapStyleId.THUNDERFOREST_OUTDOORS -> stringResource(R.string.map_style_thunderforest_outdoors_name)
    else -> stringResource(R.string.map_style_other_name)
}
