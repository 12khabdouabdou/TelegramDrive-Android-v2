package com.tgdrive.feature.folders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tgdrive.core.db.entity.FolderEntity
import com.tgdrive.designsystem.theme.TgTypography

@Composable
fun DeleteFolderDialog(
    folder: FolderEntity,
    onDismiss: () -> Unit,
    vm: FolderEditorViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) {
            vm.reset()
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!state.loading) {
                vm.reset()
                onDismiss()
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = {
            Text(
                stringResource(R.string.folders_delete_title),
                style = TgTypography.Display.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.folders_delete_confirm, folder.title),
                    style = TgTypography.Display.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.folders_delete_note),
                    style = TgTypography.Display.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = TgTypography.Display.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { vm.delete(folder) },
                enabled = !state.loading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    Text(stringResource(R.string.folders_delete))
                }
            }
        },
        dismissButton = {
            OutlinedButton(
                enabled = !state.loading,
                onClick = {
                    vm.reset()
                    onDismiss()
                },
            ) { Text(stringResource(R.string.folders_cancel)) }
        },
    )
}
