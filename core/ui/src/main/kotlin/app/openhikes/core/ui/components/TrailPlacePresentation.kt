package app.openhikes.core.ui.components

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.GeoCoordinate
import app.openhikes.core.model.TrailPlaceRow
import app.openhikes.core.model.TrailPlaceSymbol
import app.openhikes.core.model.TrailPoint
import app.openhikes.core.ui.R
import app.openhikes.core.ui.format.HikeMetricsFormatter
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs

private val BadgeDefaultSize = 44.dp
private val BadgeIconSize = 22.dp
private val RowIconSize = 22.dp
private val RowSpacing = 12.dp
private val RowTextSpacing = 2.dp

private val CampColor = Color(0xFF2E7D32)
private val CautionColor = Color(0xFFD97706)
private val JunctionColor = Color(0xFF5C6BC0)
private val ParkingColor = Color(0xFF1565C0)
private val ShelterColor = Color(0xFF8D4E2B)
private val SummitColor = Color(0xFF455A64)
private val ViewpointColor = Color(0xFF00796B)
private val WaterColor = Color(0xFF0288D1)
private val DefaultPinColor = Color(0xFF1B6E43)

@DrawableRes
fun TrailPlaceSymbol?.iconRes(): Int = when (this) {
    TrailPlaceSymbol.CAMP -> OpenHikesIcons.PoiCamp
    TrailPlaceSymbol.CAUTION -> OpenHikesIcons.Warning
    TrailPlaceSymbol.JUNCTION -> OpenHikesIcons.PoiJunction
    TrailPlaceSymbol.PARKING -> OpenHikesIcons.PoiParking
    TrailPlaceSymbol.SHELTER -> OpenHikesIcons.PoiShelter
    TrailPlaceSymbol.SUMMIT -> OpenHikesIcons.Landscape
    TrailPlaceSymbol.VIEWPOINT -> OpenHikesIcons.PoiViewpoint
    TrailPlaceSymbol.WATER -> OpenHikesIcons.PoiWater
    null -> OpenHikesIcons.Place
}

fun TrailPlaceSymbol?.tintColor(): Color = when (this) {
    TrailPlaceSymbol.CAMP -> CampColor
    TrailPlaceSymbol.CAUTION -> CautionColor
    TrailPlaceSymbol.JUNCTION -> JunctionColor
    TrailPlaceSymbol.PARKING -> ParkingColor
    TrailPlaceSymbol.SHELTER -> ShelterColor
    TrailPlaceSymbol.SUMMIT -> SummitColor
    TrailPlaceSymbol.VIEWPOINT -> ViewpointColor
    TrailPlaceSymbol.WATER -> WaterColor
    null -> DefaultPinColor
}

@StringRes
fun TrailPlaceSymbol.labelRes(): Int = when (this) {
    TrailPlaceSymbol.CAMP -> R.string.place_kind_camp
    TrailPlaceSymbol.CAUTION -> R.string.place_kind_caution
    TrailPlaceSymbol.JUNCTION -> R.string.place_kind_junction
    TrailPlaceSymbol.PARKING -> R.string.place_kind_parking
    TrailPlaceSymbol.SHELTER -> R.string.place_kind_shelter
    TrailPlaceSymbol.SUMMIT -> R.string.place_kind_summit
    TrailPlaceSymbol.VIEWPOINT -> R.string.place_kind_viewpoint
    TrailPlaceSymbol.WATER -> R.string.place_kind_water
}

@StringRes
fun TrailPlaceSymbol.chipLabelRes(): Int = when (this) {
    TrailPlaceSymbol.CAMP -> R.string.place_chip_camp
    TrailPlaceSymbol.PARKING -> R.string.place_chip_parking
    TrailPlaceSymbol.SHELTER -> R.string.place_chip_shelter
    TrailPlaceSymbol.SUMMIT -> R.string.place_chip_summit
    TrailPlaceSymbol.VIEWPOINT -> R.string.place_chip_viewpoint
    TrailPlaceSymbol.WATER -> R.string.place_chip_water
    TrailPlaceSymbol.CAUTION,
    TrailPlaceSymbol.JUNCTION -> labelRes()
}

fun TrailPlaceSymbol?.unnamedDisplayName(context: Context): String =
    context.getString(this?.labelRes() ?: R.string.place_unnamed_fallback)

fun TrailPoint.displayName(context: Context): String = name.trim().ifEmpty { symbolKind.unnamedDisplayName(context) }

@Composable
fun TrailPoint.rememberDisplayName(): String {
    val context = LocalContext.current
    return displayName(context)
}

/**
 * Formats coordinates consistently across place detail, add-place, and share flows.
 */
object TrailPlaceCoordinates {
    fun format(coordinate: GeoCoordinate): String {
        val latDir = if (coordinate.latitude >= 0.0) "N" else "S"
        val lonDir = if (coordinate.longitude >= 0.0) "E" else "W"
        return String.format(
            Locale.US,
            "%.5f° %s, %.5f° %s",
            abs(coordinate.latitude),
            latDir,
            abs(coordinate.longitude),
            lonDir
        )
    }

    fun geoUri(coordinate: GeoCoordinate, title: String): String {
        val lat = String.format(Locale.US, "%.6f", coordinate.latitude)
        val lon = String.format(Locale.US, "%.6f", coordinate.longitude)
        val encodedTitle = URLEncoder.encode(title, Charsets.UTF_8.name())
        return "geo:$lat,$lon?q=$lat,$lon($encodedTitle)"
    }
}

/**
 * Circular colored category badge heading a place screen or card.
 */
@Composable
fun TrailPlaceBadge(
    symbol: TrailPlaceSymbol?,
    modifier: Modifier = Modifier,
    size: Dp = BadgeDefaultSize,
    iconSize: Dp = BadgeIconSize,
    alpha: Float = 1f
) {
    Surface(
        shape = CircleShape,
        color = symbol.tintColor().copy(alpha = alpha),
        contentColor = Color.White,
        modifier = modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(symbol.iconRes()),
                contentDescription = null,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

/**
 * Shared row body for a [TrailPlaceRow] used by hike detail, recording, Places Around Trail,
 * and Places Nearby lists.
 */
@Composable
fun TrailPlaceRowContent(
    row: TrailPlaceRow,
    distanceUnit: DistanceUnit,
    modifier: Modifier = Modifier,
    trailingDistanceMeters: Double? = row.anchor?.distanceAlongRouteMeters,
    noteMaxLines: Int = 2
) {
    val place = row.place
    val title = place.rememberDisplayName()
    val offTrailText = row.offRouteMeters?.takeIf { !row.isOnTheLine }?.let { meters ->
        stringResource(
            R.string.place_off_trail_distance,
            HikeMetricsFormatter.formatDistance(meters, distanceUnit)
        )
    }
    val iconTint = if (place.symbolKind == TrailPlaceSymbol.CAUTION) {
        CautionColor
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RowSpacing),
        modifier = modifier
    ) {
        Icon(
            painter = painterResource(place.symbolKind.iconRes()),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(RowIconSize)
        )
        TrailPlaceRowTextColumn(
            title = title,
            offTrailText = offTrailText,
            note = place.note,
            noteMaxLines = noteMaxLines,
            modifier = Modifier.weight(1f)
        )
        if (trailingDistanceMeters != null) {
            Text(
                text = HikeMetricsFormatter.formatDistance(trailingDistanceMeters, distanceUnit),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun TrailPlaceRowTextColumn(
    title: String,
    offTrailText: String?,
    note: String,
    noteMaxLines: Int,
    modifier: Modifier = Modifier
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(RowTextSpacing),
        modifier = modifier
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (offTrailText != null) {
            Text(
                text = offTrailText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (note.isNotBlank()) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = noteMaxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
