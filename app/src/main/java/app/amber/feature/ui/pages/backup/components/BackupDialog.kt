package app.amber.feature.ui.pages.backup.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import app.amber.agent.R
import kotlin.system.exitProcess

@Composable
fun BackupDialog() {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.backup_page_restart_app)) },
        text = { Text(stringResource(R.string.backup_page_restart_desc)) },
        confirmButton = {
            Button(
                onClick = {
                    exitProcess(0)
                },
                modifier = androidx.compose.ui.Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(15.dp),
            ) {
                Text(stringResource(R.string.backup_page_restart_app))
            }
        },
    )
}
