package com.BalancedLight.dynamicdriving.shared.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.BalancedLight.dynamicdriving.shared.R
import com.BalancedLight.dynamicdriving.shared.playlist.PlaylistOperations

/** The playlist dialog currently open, if any. */
sealed interface PlaylistDialog {
    data class Create(val seedSongId: String? = null) : PlaylistDialog
    data class Rename(val playlistId: String, val currentName: String) : PlaylistDialog
    data class Delete(val playlistId: String, val currentName: String) : PlaylistDialog
}

@Composable
fun PlaylistDialogHost(
    dialog: PlaylistDialog?,
    onDismiss: () -> Unit,
    onCreate: (name: String, seedSongId: String?) -> Unit,
    onRename: (playlistId: String, name: String) -> Unit,
    onDelete: (playlistId: String) -> Unit
) {
    when (dialog) {
        null -> Unit

        is PlaylistDialog.Create -> NameDialog(
            titleRes = R.string.playlists_create_title,
            confirmRes = R.string.action_create,
            initialName = "",
            onDismiss = onDismiss,
            onConfirm = { name -> onCreate(name, dialog.seedSongId) }
        )

        is PlaylistDialog.Rename -> NameDialog(
            titleRes = R.string.playlists_rename_title,
            confirmRes = R.string.action_save,
            initialName = dialog.currentName,
            onDismiss = onDismiss,
            onConfirm = { name -> onRename(dialog.playlistId, name) }
        )

        is PlaylistDialog.Delete -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.playlists_delete_title)) },
            text = { Text(stringResource(R.string.playlists_delete_body, dialog.currentName)) },
            confirmButton = {
                TextButton(onClick = { onDelete(dialog.playlistId) }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun NameDialog(
    titleRes: Int,
    confirmRes: Int,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(PlaylistOperations.MAX_NAME_LENGTH) },
                label = { Text(stringResource(R.string.playlists_name_label)) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(confirmRes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
