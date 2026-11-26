package app.openhikes.feature.trailmaker

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.openhikes.core.model.TrailDraftRefusal
import app.openhikes.core.ui.testing.OpenHikesTestTags

private val DialogTouchTarget = 48.dp
private val DialogContentSpacing = 12.dp
private val OverlayPadding = 20.dp
private val OverlayMinWidth = 260.dp
private val OverlayMaxWidth = 400.dp
private val OverlayButtonSpacing = 8.dp
private const val SCRIM_ALPHA = 0.32f

@Composable
internal fun TrailMakerDialogs(
    uiState: TrailMakerUiState,
    onDiscardTrail: () -> Unit,
    onKeepForLater: () -> Unit,
    onConfirmSave: () -> Unit,
    onAction: (TrailMakerAction) -> Unit
) {
    if (uiState.showCloseDialog) {
        CloseTrailConfirmDialog(
            onDiscard = onDiscardTrail,
            onKeepLater = onKeepForLater,
            onDismiss = { onAction(TrailMakerAction.DismissCloseDialog) }
        )
    }
    if (uiState.showNameDialog) {
        NameTrailOverlay(
            nameDraft = uiState.nameDraft,
            placeholder = uiState.namePlaceholder,
            onValueChange = { onAction(TrailMakerAction.UpdateNameDraft(it)) },
            onSave = onConfirmSave,
            onDismiss = { onAction(TrailMakerAction.DismissNameDialog) }
        )
    }
    uiState.refusal?.let { reason ->
        TrailRefusalDialog(
            refusal = reason,
            onDismiss = { onAction(TrailMakerAction.DismissRefusal) }
        )
    }
}

@Composable
private fun CloseTrailConfirmDialog(onDiscard: () -> Unit, onKeepLater: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(OpenHikesTestTags.TRAIL_DRAFT_CLOSE_DIALOG),
        title = { Text(text = stringResource(R.string.trail_close_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DialogContentSpacing)) {
                Text(text = stringResource(R.string.trail_close_dialog_message))
                TextButton(
                    onClick = onDiscard,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = DialogTouchTarget)
                        .testTag(OpenHikesTestTags.TRAIL_DRAFT_DISCARD)
                ) {
                    Text(text = stringResource(R.string.trail_close_discard))
                }
                TextButton(
                    onClick = onKeepLater,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = DialogTouchTarget)
                        .testTag(OpenHikesTestTags.TRAIL_DRAFT_KEEP_LATER)
                ) {
                    Text(text = stringResource(R.string.trail_close_keep_later))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = DialogTouchTarget)
            ) {
                Text(text = stringResource(R.string.trail_close_keep_drawing))
            }
        }
    )
}

@Composable
private fun NameTrailOverlay(
    nameDraft: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss
            )
            .padding(OverlayPadding),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            modifier = Modifier
                .widthIn(min = OverlayMinWidth, max = OverlayMaxWidth)
                .fillMaxWidth()
                .semantics(mergeDescendants = false) {}
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {}
        ) {
            NameTrailOverlayContent(
                nameDraft = nameDraft,
                placeholder = placeholder,
                onValueChange = onValueChange,
                onSave = onSave,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun NameTrailOverlayContent(
    nameDraft: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier.padding(OverlayPadding),
        verticalArrangement = Arrangement.spacedBy(DialogContentSpacing)
    ) {
        Text(
            text = stringResource(R.string.trail_name_dialog_title),
            style = MaterialTheme.typography.headlineSmall,
            color = AlertDialogDefaults.titleContentColor
        )
        Text(
            text = stringResource(R.string.trail_name_dialog_message),
            style = MaterialTheme.typography.bodyMedium,
            color = AlertDialogDefaults.textContentColor
        )
        OutlinedTextField(
            value = nameDraft,
            onValueChange = onValueChange,
            singleLine = true,
            label = { Text(text = stringResource(R.string.trail_name_dialog_label)) },
            placeholder = { Text(text = placeholder) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(OpenHikesTestTags.TRAIL_DRAFT_NAME)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OverlayButtonSpacing, Alignment.End)
        ) {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = DialogTouchTarget)
            ) {
                Text(text = stringResource(R.string.trail_name_dialog_cancel))
            }
            Button(
                onClick = onSave,
                modifier = Modifier
                    .defaultMinSize(minHeight = DialogTouchTarget)
                    .testTag(OpenHikesTestTags.TRAIL_DRAFT_NAME_SAVE)
            ) {
                Text(text = stringResource(R.string.trail_name_dialog_save))
            }
        }
    }
}

@Composable
private fun TrailRefusalDialog(refusal: TrailDraftRefusal, onDismiss: () -> Unit) {
    val titleRes = when (refusal) {
        TrailDraftRefusal.TOO_SHORT -> R.string.trail_refusal_too_short_title
        TrailDraftRefusal.NOT_SAVED -> R.string.trail_refusal_not_saved_title
    }
    val messageRes = when (refusal) {
        TrailDraftRefusal.TOO_SHORT -> R.string.trail_refusal_too_short_message
        TrailDraftRefusal.NOT_SAVED -> R.string.trail_refusal_not_saved_message
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(OpenHikesTestTags.TRAIL_DRAFT_REFUSAL_DIALOG),
        title = { Text(text = stringResource(titleRes)) },
        text = { Text(text = stringResource(messageRes)) },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = DialogTouchTarget)
            ) {
                Text(text = stringResource(R.string.trail_refusal_ok))
            }
        }
    )
}
