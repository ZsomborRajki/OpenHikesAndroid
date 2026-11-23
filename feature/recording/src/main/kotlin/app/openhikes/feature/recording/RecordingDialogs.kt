package app.openhikes.feature.recording

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.openhikes.core.designsystem.icon.OpenHikesIcons
import app.openhikes.core.model.RecordingStopStep
import app.openhikes.core.ui.testing.OpenHikesTestTags

private val DialogButtonMinHeight = 48.dp
private val StopDialogMinWidth = 280.dp
private val StopDialogMaxWidth = 400.dp
private val StopDialogPadding = 24.dp
private const val SCRIM_ALPHA = 0.32f

@Composable
internal fun RecordingDialogs(uiState: RecordingUiState, onAction: (RecordingAction) -> Unit) {
    when (uiState.stopDialogStep) {
        RecordingStopStep.ASK_NAME -> StopSaveDialog(
            uiState = uiState,
            onAction = onAction
        )
        RecordingStopStep.CONFIRM_DISCARD -> StopConfirmDiscardDialog(onAction = onAction)
        RecordingStopStep.END_EMPTY,
        null -> Unit
    }

    if (uiState.showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { onAction(RecordingAction.DismissDiscardDialog) },
            title = { Text(stringResource(R.string.recording_discard_dialog_title)) },
            text = { Text(stringResource(R.string.recording_discard_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = { onAction(RecordingAction.ConfirmDiscard) },
                    modifier = Modifier
                        .defaultMinSize(minHeight = DialogButtonMinHeight)
                        .testTag(OpenHikesTestTags.RECORDING_DISCARD_CONFIRM_BUTTON)
                ) {
                    Text(stringResource(R.string.recording_discard_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onAction(RecordingAction.DismissDiscardDialog) },
                    modifier = Modifier
                        .defaultMinSize(minHeight = DialogButtonMinHeight)
                        .testTag(OpenHikesTestTags.RECORDING_DISCARD_CANCEL_BUTTON)
                ) {
                    Text(stringResource(R.string.recording_discard_cancel))
                }
            },
            modifier = Modifier.testTag(OpenHikesTestTags.RECORDING_DISCARD_DIALOG)
        )
    }
}

@Composable
private fun StopSaveDialog(uiState: RecordingUiState, onAction: (RecordingAction) -> Unit) {
    BackHandler { onAction(RecordingAction.DismissStopDialog) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onAction(RecordingAction.DismissStopDialog) }
            .padding(StopDialogPadding),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            modifier = Modifier
                .widthIn(min = StopDialogMinWidth, max = StopDialogMaxWidth)
                .fillMaxWidth()
                .semantics(mergeDescendants = false) {}
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {}
                .testTag(OpenHikesTestTags.RECORDING_STOP_DIALOG)
        ) {
            StopSaveDialogContent(uiState = uiState, onAction = onAction)
        }
    }
}

@Composable
private fun StopSaveDialogContent(uiState: RecordingUiState, onAction: (RecordingAction) -> Unit) {
    Column(
        modifier = Modifier.padding(StopDialogPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.recording_stop_dialog_title),
            style = MaterialTheme.typography.headlineSmall,
            color = AlertDialogDefaults.titleContentColor
        )
        OutlinedTextField(
            value = uiState.stopNameDraft,
            onValueChange = { onAction(RecordingAction.StopNameChanged(it)) },
            label = { Text(stringResource(R.string.recording_stop_name_label)) },
            singleLine = true,
            trailingIcon = if (uiState.stopNameDraft.isNotEmpty()) {
                {
                    IconButton(
                        onClick = { onAction(RecordingAction.StopNameChanged("")) },
                        modifier = Modifier.defaultMinSize(
                            minWidth = DialogButtonMinHeight,
                            minHeight = DialogButtonMinHeight
                        )
                    ) {
                        Icon(
                            painter = painterResource(OpenHikesIcons.Close),
                            contentDescription = stringResource(R.string.recording_stop_clear_name)
                        )
                    }
                }
            } else {
                null
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(OpenHikesTestTags.RECORDING_STOP_NAME_FIELD)
        )
        StopSaveDialogActions(onAction = onAction)
    }
}

@Composable
private fun StopSaveDialogActions(onAction: (RecordingAction) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { onAction(RecordingAction.DismissStopDialog) },
                modifier = Modifier
                    .defaultMinSize(minHeight = DialogButtonMinHeight)
                    .testTag(OpenHikesTestTags.RECORDING_STOP_KEEP_BUTTON)
            ) {
                Text(stringResource(R.string.recording_stop_keep))
            }
            Button(
                onClick = { onAction(RecordingAction.ConfirmStopSave) },
                modifier = Modifier
                    .defaultMinSize(minHeight = DialogButtonMinHeight)
                    .testTag(OpenHikesTestTags.RECORDING_STOP_SAVE_BUTTON)
            ) {
                Text(stringResource(R.string.recording_stop_save))
            }
        }
        TextButton(
            onClick = { onAction(RecordingAction.ConfirmStopDiscard) },
            modifier = Modifier
                .defaultMinSize(minHeight = DialogButtonMinHeight)
                .testTag(OpenHikesTestTags.RECORDING_STOP_DISCARD_BUTTON)
        ) {
            Text(stringResource(R.string.recording_stop_discard))
        }
    }
}

@Composable
private fun StopConfirmDiscardDialog(onAction: (RecordingAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(RecordingAction.DismissStopDialog) },
        title = { Text(stringResource(R.string.recording_stop_short_title)) },
        text = { Text(stringResource(R.string.recording_stop_short_body)) },
        confirmButton = {
            TextButton(
                onClick = { onAction(RecordingAction.ConfirmStopDiscard) },
                modifier = Modifier
                    .defaultMinSize(minHeight = DialogButtonMinHeight)
                    .testTag(OpenHikesTestTags.RECORDING_STOP_DISCARD_BUTTON)
            ) {
                Text(stringResource(R.string.recording_stop_discard))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onAction(RecordingAction.DismissStopDialog) },
                modifier = Modifier
                    .defaultMinSize(minHeight = DialogButtonMinHeight)
                    .testTag(OpenHikesTestTags.RECORDING_STOP_KEEP_BUTTON)
            ) {
                Text(stringResource(R.string.recording_stop_keep))
            }
        },
        modifier = Modifier.testTag(OpenHikesTestTags.RECORDING_STOP_DIALOG)
    )
}
