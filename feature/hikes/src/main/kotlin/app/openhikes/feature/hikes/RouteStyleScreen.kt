package app.openhikes.feature.hikes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.HikeId
import app.openhikes.core.model.RouteBorder
import app.openhikes.core.model.RouteLinePattern
import app.openhikes.core.model.RouteStyleUpdate
import app.openhikes.core.model.RouteTint
import app.openhikes.core.ui.components.RoutePatternSwatch
import app.openhikes.core.ui.testing.OpenHikesTestTags
import app.openhikes.core.ui.theme.parseHexColorOrDefault
import kotlin.math.roundToInt

private val StyleHorizontalPadding = 16.dp
private val StyleSectionSpacing = 16.dp
private val TouchTargetMinSize = 48.dp
private val SwatchSize = 40.dp
private val SwatchSelectedBorder = 2.5.dp
private val PatternChipCorner = 14.dp
private const val WIDTH_SLIDER_STEPS = 10
private const val LIGHT_COLOR_LUMINANCE = 0.55f

/**
 * Stateful route styling destination scoped to [hikeId].
 */
@Composable
fun RouteStyleRoute(
    hikeId: HikeId,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RouteStyleViewModel = hiltViewModel<RouteStyleViewModel, RouteStyleViewModel.Factory>(
        key = hikeId.value
    ) { factory ->
        factory.create(hikeId.value)
    }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    RouteStyleScreen(
        uiState = uiState,
        onBack = onBack,
        onTintSelected = viewModel::onTintSelected,
        onBorderSelected = viewModel::onBorderSelected,
        onWidthChanged = viewModel::onWidthChanged,
        onPatternSelected = viewModel::onPatternSelected,
        onDismissError = viewModel::onDismissError,
        modifier = modifier
    )
}

/**
 * Stateless route styling screen for picking color, border, width, and dash/chevron pattern.
 */
@Composable
fun RouteStyleScreen(
    uiState: RouteStyleUiState,
    onBack: () -> Unit,
    onTintSelected: (String) -> Unit,
    onBorderSelected: (String) -> Unit,
    onWidthChanged: (Double) -> Unit,
    onPatternSelected: (RouteLinePattern) -> Unit,
    modifier: Modifier = Modifier,
    onDismissError: () -> Unit = {}
) {
    Column(modifier = modifier.fillMaxSize()) {
        RouteStyleTopBar(onBack = onBack)
        if (uiState.hasSaveError) {
            RouteStyleErrorBanner(onDismiss = onDismissError)
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .testTag(OpenHikesTestTags.ROUTE_STYLE_SCREEN),
            contentPadding = PaddingValues(
                start = StyleHorizontalPadding,
                end = StyleHorizontalPadding,
                bottom = StyleSectionSpacing
            ),
            verticalArrangement = Arrangement.spacedBy(StyleSectionSpacing)
        ) {
            item(key = "colors") {
                ColorsSection(
                    selectedTintHex = uiState.tintHex,
                    selectedBorderHex = uiState.borderHex,
                    onTintSelected = onTintSelected,
                    onBorderSelected = onBorderSelected
                )
            }
            item(key = "line") {
                LineSection(
                    widthDp = uiState.widthDp,
                    selectedPattern = uiState.linePattern,
                    tintHex = uiState.tintHex,
                    borderHex = uiState.borderHex,
                    onWidthChanged = onWidthChanged,
                    onPatternSelected = onPatternSelected
                )
            }
        }
    }
}

@Composable
private fun RouteStyleTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
                .testTag(OpenHikesTestTags.ROUTE_STYLE_BACK)
        ) {
            Icon(
                painter = painterResource(OpenHikesIcons.ArrowBack),
                contentDescription = stringResource(R.string.route_style_back_description)
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.route_style_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorsSection(
    selectedTintHex: String,
    selectedBorderHex: String,
    onTintSelected: (String) -> Unit,
    onBorderSelected: (String) -> Unit
) {
    HikeSectionCard(title = stringResource(R.string.route_style_section_colors)) {
        Column(
            modifier = Modifier.padding(StyleHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.route_style_line_color),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RouteTint.palette.forEach { hex ->
                    ColorSwatchButton(
                        color = parseHexColorOrDefault(hex, Color.Green),
                        isSelected = selectedTintHex.equals(hex, ignoreCase = true),
                        description = stringResource(R.string.route_style_color_swatch_description, hex),
                        onClick = { onTintSelected(hex) },
                        testTag = OpenHikesTestTags.routeColorSwatch(hex)
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = stringResource(R.string.route_style_border_color),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RouteBorder.borderSwatches.forEach { borderHex ->
                    val swatchColor = when (borderHex) {
                        RouteBorder.NONE_HEX -> Color.Black
                        RouteBorder.TRANSPARENT_HEX -> MaterialTheme.colorScheme.surfaceVariant
                        else -> parseHexColorOrDefault(borderHex, Color.Black)
                    }
                    ColorSwatchButton(
                        color = swatchColor,
                        isSelected = selectedBorderHex.equals(borderHex, ignoreCase = true),
                        description = borderLabel(borderHex),
                        onClick = { onBorderSelected(borderHex) },
                        testTag = OpenHikesTestTags.routeBorderSwatch(borderHex)
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorSwatchButton(
    color: Color,
    isSelected: Boolean,
    description: String,
    onClick: () -> Unit,
    testTag: String
) {
    val borderStroke = if (isSelected) {
        BorderStroke(SwatchSelectedBorder, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    val checkTint = if (color.luminance() > LIGHT_COLOR_LUMINANCE) Color.Black else Color.White

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(TouchTargetMinSize)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                selected = isSelected
            }
            .testTag(testTag)
    ) {
        Surface(
            color = color,
            shape = CircleShape,
            border = borderStroke,
            modifier = Modifier.size(SwatchSize)
        ) {
            if (isSelected) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(OpenHikesIcons.Check),
                        contentDescription = null,
                        tint = checkTint,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LineSection(
    widthDp: Double,
    selectedPattern: RouteLinePattern,
    tintHex: String,
    borderHex: String,
    onWidthChanged: (Double) -> Unit,
    onPatternSelected: (RouteLinePattern) -> Unit
) {
    HikeSectionCard(title = stringResource(R.string.route_style_section_line)) {
        Column(
            modifier = Modifier.padding(StyleHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LineWidthBlock(widthDp = widthDp, onWidthChanged = onWidthChanged)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            PatternSelectorBlock(
                selectedPattern = selectedPattern,
                tintHex = tintHex,
                borderHex = borderHex,
                onPatternSelected = onPatternSelected
            )
        }
    }
}

@Composable
private fun LineWidthBlock(widthDp: Double, onWidthChanged: (Double) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.route_style_line_width),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.route_style_width_value, widthDp.roundToInt()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val minWidth = RouteStyleUpdate.MIN_ROUTE_WIDTH_DP.toFloat()
        val maxWidth = RouteStyleUpdate.MAX_ROUTE_WIDTH_DP.toFloat()
        val widthRange = minWidth..maxWidth
        val sliderState = remember(widthRange) {
            SliderState(
                value = widthDp.toFloat(),
                steps = WIDTH_SLIDER_STEPS,
                trackRange = widthRange
            )
        }
        sliderState.value = widthDp.toFloat()
        Slider(
            state = sliderState,
            onValueChange = { onWidthChanged(it.roundToInt().toDouble()) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(OpenHikesTestTags.ROUTE_WIDTH_SLIDER)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatternSelectorBlock(
    selectedPattern: RouteLinePattern,
    tintHex: String,
    borderHex: String,
    onPatternSelected: (RouteLinePattern) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.route_style_pattern_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = patternLabel(selectedPattern),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(OpenHikesTestTags.ROUTE_PATTERN_CAPTION)
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RouteLinePattern.displayOrder.forEach { pattern ->
                PatternChip(
                    pattern = pattern,
                    isSelected = pattern == selectedPattern,
                    tintHex = tintHex,
                    borderHex = borderHex,
                    onClick = { onPatternSelected(pattern) }
                )
            }
        }
    }
}

@Composable
private fun PatternChip(
    pattern: RouteLinePattern,
    isSelected: Boolean,
    tintHex: String,
    borderHex: String,
    onClick: () -> Unit
) {
    val label = patternLabel(pattern)
    Surface(
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        shape = RoundedCornerShape(PatternChipCorner),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .defaultMinSize(minWidth = TouchTargetMinSize, minHeight = TouchTargetMinSize)
            .clip(RoundedCornerShape(PatternChipCorner))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                selected = isSelected
            }
            .testTag(OpenHikesTestTags.routePattern(pattern.id))
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp)
        ) {
            RoutePatternSwatch(
                pattern = pattern,
                tintHex = tintHex,
                borderHex = borderHex
            )
        }
    }
}

@Composable
private fun RouteStyleErrorBanner(onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = StyleHorizontalPadding, vertical = 4.dp)
            .testTag(OpenHikesTestTags.HIKE_ACTION_ERROR)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.hikes_save_failed_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = TouchTargetMinSize)
            ) {
                Text(
                    text = stringResource(R.string.hikes_error_dismiss),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun borderLabel(borderHex: String): String = when (borderHex) {
    RouteBorder.NONE_HEX -> stringResource(R.string.route_style_border_default)
    RouteBorder.TRANSPARENT_HEX -> stringResource(R.string.route_style_border_none)
    RouteBorder.DEFAULT_HEX -> stringResource(R.string.route_style_border_black)
    RouteBorder.WHITE_HEX -> stringResource(R.string.route_style_border_white)
    else -> stringResource(R.string.route_style_border_charcoal)
}

@Composable
private fun patternLabel(pattern: RouteLinePattern): String = when (pattern) {
    RouteLinePattern.SOLID -> stringResource(R.string.route_pattern_solid)
    RouteLinePattern.DIRECTIONAL -> stringResource(R.string.route_pattern_directional)
    RouteLinePattern.DASHED -> stringResource(R.string.route_pattern_dashed)
    RouteLinePattern.DOTTED -> stringResource(R.string.route_pattern_dotted)
    RouteLinePattern.ARROWHEADS -> stringResource(R.string.route_pattern_arrowheads)
}
