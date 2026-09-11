package app.amber.feature.ui.components.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.ai.ui.UIMessage
import app.amber.agent.data.workspace.Artifact
import app.amber.agent.data.workspace.ArtifactRepository
import app.amber.agent.data.workspace.ArtifactSourceKind
import app.amber.agent.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * "保存到 Workspace" dialog (P3-02). Lets the user pick an existing workspace
 * or quickly create a new one, optionally include reasoning, and saves the
 * message body + attachment references with chat source tracking.
 *
 * Duplicate saves (same sourceMessageId) prompt 更新 / 创建副本 / 取消.
 */
@Composable
fun SaveMessageToWorkspaceDialog(
    conversationId: String,
    message: UIMessage,
    repository: ArtifactRepository,
    onDismiss: () -> Unit,
    onSaved: (Artifact) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var workspaceIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedWorkspaceId by remember { mutableStateOf(ArtifactRepository.DEFAULT_WORKSPACE_ID) }
    var creatingNew by remember { mutableStateOf(false) }
    var newWorkspaceName by remember { mutableStateOf("") }
    var includeReasoning by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var duplicate by remember { mutableStateOf<Artifact?>(null) }
    var pendingWorkspaceId by remember { mutableStateOf<String?>(null) }
    val saveFailedMessage = stringResource(R.string.workspace_save_failed)

    LaunchedEffect(Unit) {
        workspaceIds = repository.workspaceIds()
    }

    val effectiveWorkspaceId: String = when {
        creatingNew -> newWorkspaceName.trim()
        else -> selectedWorkspaceId
    }
    val canSave = !saving && effectiveWorkspaceId.isNotBlank() && !effectiveWorkspaceId.contains('/')

    fun performSave(workspaceId: String, existingArtifactId: String?) {
        scope.launch {
            saving = true
            errorMessage = null
            try {
                val artifact = repository.saveChatMessage(
                    message = message,
                    conversationId = conversationId,
                    workspaceId = workspaceId,
                    includeReasoning = includeReasoning,
                    existingArtifactId = existingArtifactId,
                )
                onSaved(artifact)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                errorMessage = error.message?.trim()?.takeIf { it.isNotBlank() }?.take(160)
                    ?: saveFailedMessage
            } finally {
                saving = false
            }
        }
    }

    fun startSave() {
        if (duplicate != null) return
        scope.launch {
            saving = true
            errorMessage = null
            try {
                val existing = repository.findBySourceMessage(ArtifactSourceKind.CHAT, message.id.toString())
                if (existing != null) {
                    pendingWorkspaceId = effectiveWorkspaceId
                    duplicate = existing
                } else {
                    val artifact = repository.saveChatMessage(
                        message = message,
                        conversationId = conversationId,
                        workspaceId = effectiveWorkspaceId,
                        includeReasoning = includeReasoning,
                    )
                    onSaved(artifact)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                errorMessage = error.message?.trim()?.takeIf { it.isNotBlank() }?.take(160)
                    ?: saveFailedMessage
            } finally {
                saving = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.workspace_save_to_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = message.toText().lineSequence().firstOrNull { it.isNotBlank() }?.take(80)
                        ?: stringResource(R.string.workspace_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                Text(
                    text = stringResource(R.string.workspace_save_location),
                    style = MaterialTheme.typography.labelLarge,
                )
                workspaceIds.forEach { id ->
                    WorkspaceOptionRow(
                        label = workspaceLabel(id),
                        selected = !creatingNew && selectedWorkspaceId == id,
                        onClick = {
                            creatingNew = false
                            selectedWorkspaceId = id
                        },
                    )
                }
                if (workspaceIds.isEmpty()) {
                    Text(
                        text = stringResource(R.string.workspace_no_workspaces),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                WorkspaceOptionRow(
                    label = stringResource(R.string.workspace_create_new),
                    selected = creatingNew,
                    onClick = { creatingNew = true },
                )
                if (creatingNew) {
                    OutlinedTextField(
                        value = newWorkspaceName,
                        onValueChange = { newWorkspaceName = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.workspace_name_placeholder)) },
                        singleLine = true,
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { includeReasoning = !includeReasoning }
                        .padding(vertical = 2.dp),
                ) {
                    Checkbox(
                        checked = includeReasoning,
                        onCheckedChange = { includeReasoning = it },
                    )
                    Text(stringResource(R.string.workspace_include_reasoning))
                }
                errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = { startSave() },
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(
                enabled = !saving,
                onClick = onDismiss,
            ) { Text(stringResource(R.string.cancel)) }
        },
    )

    duplicate?.let { existing ->
        AlertDialog(
            onDismissRequest = { duplicate = null },
            title = { Text(stringResource(R.string.workspace_already_saved_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.workspace_duplicate_message,
                        existing.title,
                        formatTime(existing.updatedAtMs),
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val wsId = pendingWorkspaceId ?: existing.workspaceId
                        duplicate = null
                        performSave(wsId, existing.artifactId)
                    }
                ) { Text(stringResource(R.string.workspace_update)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { duplicate = null }) { Text(stringResource(R.string.cancel)) }
                    TextButton(
                        onClick = {
                            val wsId = pendingWorkspaceId ?: existing.workspaceId
                            duplicate = null
                            performSave(wsId, null)
                        }
                    ) { Text(stringResource(R.string.workspace_create_copy)) }
                }
            },
        )
    }
}

@Composable
private fun WorkspaceOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun workspaceLabel(workspaceId: String): String =
    if (workspaceId == ArtifactRepository.DEFAULT_WORKSPACE_ID) {
        stringResource(R.string.workspace_default)
    } else {
        workspaceId
    }
