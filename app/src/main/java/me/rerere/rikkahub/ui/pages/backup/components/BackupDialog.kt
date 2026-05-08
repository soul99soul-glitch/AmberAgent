package me.rerere.rikkahub.ui.pages.backup.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import kotlin.system.exitProcess

@Composable
fun BackupDialog() {
    RikkaConfirmDialog(
        show = true,
        title = stringResource(R.string.backup_page_restart_app),
        confirmText = stringResource(R.string.backup_page_restart_app),
        dismissText = stringResource(R.string.cancel),
        onConfirm = { exitProcess(0) },
        onDismiss = {},
    ) {
        Text(stringResource(R.string.backup_page_restart_desc))
    }
}
