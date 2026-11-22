package app.openhikes.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Outdoor-legible fallback palette: deep pine primary, slate moss secondary, trail-blaze amber tertiary.
private val PineLightPrimary = Color(0xFF1B5E3A)
private val PineLightOnPrimary = Color(0xFFFFFFFF)
private val PineLightPrimaryContainer = Color(0xFFA7F2C0)
private val PineLightOnPrimaryContainer = Color(0xFF00210F)

private val MossLightSecondary = Color(0xFF4B6353)
private val MossLightOnSecondary = Color(0xFFFFFFFF)
private val MossLightSecondaryContainer = Color(0xFFCDE9D4)
private val MossLightOnSecondaryContainer = Color(0xFF082013)

private val BlazeLightTertiary = Color(0xFF9C4410)
private val BlazeLightOnTertiary = Color(0xFFFFFFFF)
private val BlazeLightTertiaryContainer = Color(0xFFFFDBCA)
private val BlazeLightOnTertiaryContainer = Color(0xFF341100)

private val StoneLightBackground = Color(0xFFF6FAF4)
private val StoneLightOnBackground = Color(0xFF171D19)
private val StoneLightSurface = Color(0xFFF6FAF4)
private val StoneLightOnSurface = Color(0xFF171D19)
private val StoneLightSurfaceVariant = Color(0xFFDCE5DC)
private val StoneLightOnSurfaceVariant = Color(0xFF404942)
private val StoneLightOutline = Color(0xFF707971)
private val StoneLightOutlineVariant = Color(0xFFC0C9C0)
private val StoneLightSurfaceContainerLowest = Color(0xFFFFFFFF)
private val StoneLightSurfaceContainerLow = Color(0xFFF0F5EE)
private val StoneLightSurfaceContainer = Color(0xFFEAEFE8)
private val StoneLightSurfaceContainerHigh = Color(0xFFE5EAE3)
private val StoneLightSurfaceContainerHighest = Color(0xFFDFE4DD)

private val PineDarkPrimary = Color(0xFF8BD5A5)
private val PineDarkOnPrimary = Color(0xFF00391D)
private val PineDarkPrimaryContainer = Color(0xFF00522C)
private val PineDarkOnPrimaryContainer = Color(0xFFA7F2C0)

private val MossDarkSecondary = Color(0xFFB2CCB8)
private val MossDarkOnSecondary = Color(0xFF1D3527)
private val MossDarkSecondaryContainer = Color(0xFF344C3C)
private val MossDarkOnSecondaryContainer = Color(0xFFCDE9D4)

private val BlazeDarkTertiary = Color(0xFFFFB690)
private val BlazeDarkOnTertiary = Color(0xFF552100)
private val BlazeDarkTertiaryContainer = Color(0xFF783100)
private val BlazeDarkOnTertiaryContainer = Color(0xFFFFDBCA)

private val StoneDarkBackground = Color(0xFF0F1511)
private val StoneDarkOnBackground = Color(0xFFDFE4DD)
private val StoneDarkSurface = Color(0xFF0F1511)
private val StoneDarkOnSurface = Color(0xFFDFE4DD)
private val StoneDarkSurfaceVariant = Color(0xFF404942)
private val StoneDarkOnSurfaceVariant = Color(0xFFC0C9C0)
private val StoneDarkOutline = Color(0xFF8A938B)
private val StoneDarkOutlineVariant = Color(0xFF404942)
private val StoneDarkSurfaceContainerLowest = Color(0xFF0A0F0C)
private val StoneDarkSurfaceContainerLow = Color(0xFF171D19)
private val StoneDarkSurfaceContainer = Color(0xFF1B211D)
private val StoneDarkSurfaceContainerHigh = Color(0xFF252B27)
private val StoneDarkSurfaceContainerHighest = Color(0xFF303632)

val OpenHikesLightColorScheme: ColorScheme = lightColorScheme(
    primary = PineLightPrimary,
    onPrimary = PineLightOnPrimary,
    primaryContainer = PineLightPrimaryContainer,
    onPrimaryContainer = PineLightOnPrimaryContainer,
    secondary = MossLightSecondary,
    onSecondary = MossLightOnSecondary,
    secondaryContainer = MossLightSecondaryContainer,
    onSecondaryContainer = MossLightOnSecondaryContainer,
    tertiary = BlazeLightTertiary,
    onTertiary = BlazeLightOnTertiary,
    tertiaryContainer = BlazeLightTertiaryContainer,
    onTertiaryContainer = BlazeLightOnTertiaryContainer,
    background = StoneLightBackground,
    onBackground = StoneLightOnBackground,
    surface = StoneLightSurface,
    onSurface = StoneLightOnSurface,
    surfaceVariant = StoneLightSurfaceVariant,
    onSurfaceVariant = StoneLightOnSurfaceVariant,
    outline = StoneLightOutline,
    outlineVariant = StoneLightOutlineVariant,
    surfaceContainerLowest = StoneLightSurfaceContainerLowest,
    surfaceContainerLow = StoneLightSurfaceContainerLow,
    surfaceContainer = StoneLightSurfaceContainer,
    surfaceContainerHigh = StoneLightSurfaceContainerHigh,
    surfaceContainerHighest = StoneLightSurfaceContainerHighest
)

val OpenHikesDarkColorScheme: ColorScheme = darkColorScheme(
    primary = PineDarkPrimary,
    onPrimary = PineDarkOnPrimary,
    primaryContainer = PineDarkPrimaryContainer,
    onPrimaryContainer = PineDarkOnPrimaryContainer,
    secondary = MossDarkSecondary,
    onSecondary = MossDarkOnSecondary,
    secondaryContainer = MossDarkSecondaryContainer,
    onSecondaryContainer = MossDarkOnSecondaryContainer,
    tertiary = BlazeDarkTertiary,
    onTertiary = BlazeDarkOnTertiary,
    tertiaryContainer = BlazeDarkTertiaryContainer,
    onTertiaryContainer = BlazeDarkOnTertiaryContainer,
    background = StoneDarkBackground,
    onBackground = StoneDarkOnBackground,
    surface = StoneDarkSurface,
    onSurface = StoneDarkOnSurface,
    surfaceVariant = StoneDarkSurfaceVariant,
    onSurfaceVariant = StoneDarkOnSurfaceVariant,
    outline = StoneDarkOutline,
    outlineVariant = StoneDarkOutlineVariant,
    surfaceContainerLowest = StoneDarkSurfaceContainerLowest,
    surfaceContainerLow = StoneDarkSurfaceContainerLow,
    surfaceContainer = StoneDarkSurfaceContainer,
    surfaceContainerHigh = StoneDarkSurfaceContainerHigh,
    surfaceContainerHighest = StoneDarkSurfaceContainerHighest
)
