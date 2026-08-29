package app.amber.feature.ui.components.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Archive
import app.amber.agent.R
import app.amber.agent.data.workspace.Artifact
import app.amber.agent.data.workspace.ArtifactSourceKind
import app.amber.agent.data.workspace.ArtifactParseStatus

/**
 * "Artifacts" tab of the Workspace sheet (P3-01). Reuses the sheet's list/row
 * structure; artifact detail reuses the existing content preview dialog via
 * [onOpenContent]. Mutations (reparse / delete) are hidden when the
 * `workspace_artifacts_v2` flag is off — rows stay visible read-only
 * (rollback rules §17.2).
 */
@Composable
fun WorkspaceArtifactsTab(
    vm: ArtifactsVM,
    artifactsEnabled: Boolean,
    onOpenContent: (String) -> Unit,
    onOpenSourceConversation: (String) -> Unit,
) {
    val artifacts by vm.artifacts.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Artifact?>(null) }

    if (artifacts.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Text(
                text = stringResource(R.string.workspace_no_artifacts),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 48.dp),
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(artifacts, key = { it.artifactId }) { artifact ->
            ArtifactRow(
                artifact = artifact,
                onClick = { selected = artifact },
            )
        }
    }

    selected?.let { artifact ->
        ArtifactDetailDialog(
            artifact = artifact,
            vm = vm,
            artifactsEnabled = artifactsEnabled,
            onOpenContent = {
                selected = null
                onOpenContent(artifact.contentLocator)
            },
            onOpenSourceConversation = {
                selected = null
                artifact.sourceId?.let(onOpenSourceConversation)
            },
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun ArtifactRow(artifact: Artifact, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Lucide.Archive,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artifact.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildArtifactMeta(artifact),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        ParseStatusBadge(artifact.parseStatus)
    }
}

@Composable
private fun ParseStatusBadge(status: ArtifactParseStatus) {
    val color = when (status) {
        ArtifactParseStatus.PARSED -> MaterialTheme.colorScheme.primary
        ArtifactParseStatus.FAILED -> MaterialTheme.colorScheme.error
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text = when (status) {
                ArtifactParseStatus.PARSED -> stringResource(R.string.workspace_parse_status_parsed)
                ArtifactParseStatus.FAILED -> stringResource(R.string.workspace_parse_status_failed)
            },
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = color,
        )
    }
}

@Composable
private fun ArtifactDetailDialog(
    artifact: Artifact,
    vm: ArtifactsVM,
    artifactsEnabled: Boolean,
    onOpenContent: () -> Unit,
    onOpenSourceConversation: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var referenceCount by remember { mutableStateOf<Int?>(null) }
    var sourceAvailable by remember { mutableStateOf(false) }
    var reparseNote by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val reparseCompleted = stringResource(R.string.workspace_reparse_completed)
    val sourceUnavailable = stringResource(R.string.workspace_source_unavailable)
    val reparseFailed = stringResource(R.string.workspace_reparse_failed)

    LaunchedEffect(artifact.artifactId) {
        referenceCount = vm.referenceCount(artifact.artifactId)
        sourceAvailable = vm.sourceAvailable(artifact)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(artifact.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailLine(stringResource(R.string.workspace_detail_type), artifact.type)
                DetailLine("Workspace", artifact.workspaceId)
                DetailLine(stringResource(R.string.workspace_detail_size), formatSize(artifact.sizeBytes))
                DetailLine(stringResource(R.string.workspace_detail_summary), artifact.contentDigest.take(16) + "…")
                val parseStatus = when (artifact.parseStatus) {
                    ArtifactParseStatus.PARSED -> stringResource(R.string.workspace_parse_status_parsed)
                    ArtifactParseStatus.FAILED -> stringResource(R.string.workspace_parse_status_failed_short)
                }
                DetailLine(
                    stringResource(R.string.workspace_detail_parse),
                    buildString {
                        append(parseStatus)
                        artifact.parserVersion?.let { append(" · $it") }
                        artifact.parseError?.let { append(" · $it") }
                    }
                )
                DetailLine(stringResource(R.string.workspace_detail_source), buildSourceLabel(artifact, sourceAvailable))
                referenceCount?.let {
                    DetailLine(
                        stringResource(R.string.workspace_detail_references),
                        stringResource(R.string.workspace_reference_count, it),
                    )
                }
                reparseNote?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (artifact.sourceKind == ArtifactSourceKind.CHAT && sourceAvailable) {
                        TextButton(onClick = onOpenSourceConversation) {
                            Text(stringResource(R.string.workspace_open_source_conversation))
                        }
                    }
                    if (artifactsEnabled && artifact.sourceKind == ArtifactSourceKind.CHAT) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val result = vm.reparse(artifact.artifactId)
                                    reparseNote = when (result) {
                                        is app.amber.agent.data.workspace.ReparseResult.Success ->
                                            reparseCompleted
                                        is app.amber.agent.data.workspace.ReparseResult.Failed ->
                                            if (result.reason == "source_unavailable") {
                                                sourceUnavailable
                                            } else {
                                                reparseFailed
                                            }
                                    }
                                }
                            }
                        ) { Text(stringResource(R.string.workspace_reparse)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenContent) { Text(stringResource(R.string.workspace_open_content)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_card_close)) }
                if (artifactsEnabled) {
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                    ) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
    )

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.workspace_delete_artifact_title)) },
            text = {
                val count = referenceCount ?: 0
                val deleteMessage = stringResource(R.string.workspace_delete_artifact_message, artifact.title)
                val referenceMessage = if (count > 0) {
                    stringResource(R.string.workspace_delete_artifact_references, count)
                } else {
                    null
                }
                Text(
                    buildString {
                        append(deleteMessage)
                        referenceMessage?.let {
                            append("\n\n")
                            append(it)
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDismiss()
                        vm.delete(artifact.artifactId)
                    }
                ) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun buildArtifactMeta(artifact: Artifact): String {
    val parts = mutableListOf(artifact.workspaceId)
    artifact.sizeBytes.let { parts.add(formatSize(it)) }
    parts.add(formatTime(artifact.updatedAtMs))
    return parts.joinToString(" · ")
}

@Composable
private fun buildSourceLabel(artifact: Artifact, available: Boolean): String {
    val kind = when (artifact.sourceKind) {
        ArtifactSourceKind.CHAT -> stringResource(R.string.workspace_source_chat)
        ArtifactSourceKind.MINIAPP -> "MiniApp"
        ArtifactSourceKind.DEEPREAD -> "DeepRead"
        ArtifactSourceKind.UNKNOWN -> stringResource(R.string.workspace_source_unknown)
    }
    val id = artifact.sourceId ?: return stringResource(R.string.workspace_source_missing_id, kind)
    return when {
        artifact.sourceKind != ArtifactSourceKind.CHAT -> "$kind · $id"
        available -> "$kind · $id"
        else -> stringResource(R.string.workspace_source_deleted, kind, id)
    }
}
