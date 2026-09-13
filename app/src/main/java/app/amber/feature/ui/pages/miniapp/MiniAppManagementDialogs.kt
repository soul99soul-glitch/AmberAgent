package app.amber.feature.ui.pages.miniapp

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.agent.data.db.entity.MiniAppEntity
import app.amber.agent.data.db.entity.MiniAppVersionEntity
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.feature.ui.theme.JetbrainsMono

@Composable
fun MiniAppRenameDialog(
    app: MiniAppEntity,
    onDismiss: () -> Unit,
    onConfirm: (title: String, description: String) -> Unit,
) {
    var title by remember(app.id) { mutableStateOf(app.title) }
    var description by remember(app.id) { mutableStateOf(app.description) }
    val normalizedTitle = title.trim()
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        containerColor = tokens.raised,
        title = { Text(stringResource(R.string.miniapp_rename_title), style = type.sessionTitle) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(40) },
                    label = { Text(stringResource(R.string.miniapp_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    colors = miniAppFieldColors(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(120) },
                    label = { Text(stringResource(R.string.miniapp_description_label)) },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    colors = miniAppFieldColors(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = normalizedTitle.isNotBlank(),
                onClick = { onConfirm(normalizedTitle, description.trim()) },
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
fun MiniAppDeleteDialog(
    app: MiniAppEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        containerColor = tokens.raised,
        title = { Text(stringResource(R.string.miniapp_delete_title), style = type.sessionTitle) },
        text = { Text(stringResource(R.string.miniapp_delete_message, app.title), style = type.body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
fun rememberMiniAppHtmlExporter(): (MiniAppEntity) -> Unit {
    val context = LocalContext.current
    var pendingApp by remember { mutableStateOf<MiniAppEntity?>(null) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/html")
    ) { uri: Uri? ->
        val app = pendingApp
        if (uri != null && app != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(app.htmlContent.toByteArray(Charsets.UTF_8))
                } ?: error(context.getString(R.string.miniapp_export_target_unavailable))
            }.onFailure {
                val reason = it.message ?: context.getString(R.string.miniapp_unknown_error)
                Toast.makeText(
                    context,
                    context.getString(R.string.miniapp_export_failed, reason),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        pendingApp = null
    }
    return { app ->
        pendingApp = app
        launcher.launch("${app.title.safeExportName().ifBlank { "miniapp" }}.html")
    }
}

@Composable
fun MiniAppVersionHistoryDialog(
    app: MiniAppEntity,
    versions: List<MiniAppVersionEntity>,
    onDismiss: () -> Unit,
    onRestore: (MiniAppVersionEntity) -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        containerColor = tokens.raised,
        title = { Text(stringResource(R.string.miniapp_version_history), style = type.sessionTitle) },
        text = {
            AmberCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                versions.forEachIndexed { index, version ->
                    if (index > 0) Hairline()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("v${version.versionNumber}", style = type.body.copy(fontFamily = JetbrainsMono))
                            Text(
                                version.changeNote ?: stringResource(
                                    if (version.versionNumber == app.version) {
                                        R.string.miniapp_current_version
                                    } else {
                                        R.string.miniapp_historical_version
                                    },
                                ),
                                style = type.secondary,
                                color = tokens.ink2,
                            )
                        }
                        TextButton(
                            enabled = version.versionNumber != app.version,
                            onClick = { onRestore(version) },
                        ) { Text(stringResource(R.string.miniapp_restore), color = tokens.accent) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_card_close))
            }
        },
    )
}

@Composable
private fun miniAppFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = LocalAmberTokens.current.surface2,
    unfocusedContainerColor = LocalAmberTokens.current.surface2,
    focusedBorderColor = LocalAmberTokens.current.accent,
    unfocusedBorderColor = LocalAmberTokens.current.line,
    focusedLabelColor = LocalAmberTokens.current.accent,
    unfocusedLabelColor = LocalAmberTokens.current.ink3,
    focusedTextColor = LocalAmberTokens.current.ink,
    unfocusedTextColor = LocalAmberTokens.current.ink,
    cursorColor = LocalAmberTokens.current.accent,
)

private fun String.safeExportName(): String {
    return trim()
        .replace(Regex("""[\\/:*?"<>|]+"""), "_")
        .take(48)
}
