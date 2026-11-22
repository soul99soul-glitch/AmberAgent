package app.amber.feature.ui.components.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.macgateway.CloudDialogueSubmission
import app.amber.feature.macgateway.CloudDialogueUiState
import app.amber.feature.macgateway.CloudInputDisposition
import app.amber.feature.macgateway.CloudSubmissionKind
import app.amber.feature.macgateway.CloudTaskSession
import app.amber.feature.ui.components.ui.workspaceColors

/**
 * Compact status dock above the chat composer when Cloud Main Dialogue is active.
 *
 * - Updates a single-line human-readable progress text in-place per active task (`process_event`),
 *   never accumulating per-tool cards.
 * - Lets the user explicitly target an active task for `POST /v2/work-items/{id}/inputs` or switch
 *   back to sending an independent new message (`POST /v2/dialogue/messages`).
 * - Provides a direct Stop button (`POST /v2/work-items/{id}/stop`) per active task.
 * - Distinguishes `received`, `consumed`, and `cancelled` input receipts.
 */
@Composable
internal fun CloudDialogueStatusDock(
    conversationId: String,
    cloudActive: Boolean,
    uiState: CloudDialogueUiState,
    onSelectFollowupTarget: (String?) -> Unit,
    onStopTask: (String) -> Unit,
    onRecheckOutcomeUnknown: (String) -> Unit = {},
    onRetryOutcomeUnknown: (String) -> Unit = {},
    onAbandonOutcomeUnknown: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val activeTasks = uiState.activeTasksForConversation(conversationId)
    val unknownTasks = uiState.outcomeUnknownTasksForConversation(conversationId)
    val recentInputs = uiState.submissionsForConversation(conversationId)
        .filter {
            it.kind == CloudSubmissionKind.FOLLOWUP ||
                it.stopTooLate ||
                (cloudActive && it.kind == CloudSubmissionKind.MESSAGE)
        }
        .take(3)
    if (!cloudActive && activeTasks.isEmpty() && unknownTasks.isEmpty() && recentInputs.isEmpty()) return

    val workspace = workspaceColors()
    val targetTask = uiState.resolvedFollowupTarget(conversationId)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = workspace.paper.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        targetTask != null -> stringResource(R.string.mac_gateway_cloud_mode_targeting, targetTask.title)
                        cloudActive -> stringResource(R.string.mac_gateway_cloud_mode_active) + " · " +
                            stringResource(R.string.mac_gateway_cloud_mode_new_message)
                        else -> stringResource(R.string.mac_gateway_cloud_mode_new_message)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (targetTask != null) MaterialTheme.colorScheme.primary else workspace.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (targetTask != null) {
                    Text(
                        text = stringResource(R.string.mac_gateway_cloud_mode_clear_target),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onSelectFollowupTarget(null) }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            activeTasks.forEach { task ->
                CloudActiveTaskRow(
                    task = task,
                    selectedForFollowup = targetTask?.workItemId == task.workItemId,
                    onToggleFollowup = {
                        val next = if (targetTask?.workItemId == task.workItemId) null else task.workItemId
                        onSelectFollowupTarget(next)
                    },
                    onStop = { onStopTask(task.workItemId) },
                )
            }

            unknownTasks.forEach { task ->
                CloudOutcomeUnknownRow(
                    task = task,
                    onRecheck = { onRecheckOutcomeUnknown(task.workItemId) },
                    onRetry = { onRetryOutcomeUnknown(task.workItemId) },
                    onAbandon = { onAbandonOutcomeUnknown(task.workItemId) },
                )
            }

            recentInputs.forEach { submission ->
                CloudSubmissionReceiptRow(submission = submission)
            }
        }
    }
}

@Composable
private fun CloudActiveTaskRow(
    task: CloudTaskSession,
    selectedForFollowup: Boolean,
    onToggleFollowup: () -> Unit,
    onStop: () -> Unit,
) {
    val workspace = workspaceColors()
    val progressLine = task.progressText?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.mac_gateway_cloud_task_running)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodySmall,
                color = workspace.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Single-line in-place dynamic progress text updated by process_event.
            Text(
                text = progressLine,
                style = MaterialTheme.typography.labelSmall,
                color = workspace.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (selectedForFollowup) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            } else {
                workspace.paper
            },
            border = BorderStroke(
                1.dp,
                if (selectedForFollowup) MaterialTheme.colorScheme.primary else workspace.hairline,
            ),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggleFollowup),
        ) {
            Text(
                text = stringResource(
                    if (selectedForFollowup) R.string.mac_gateway_cloud_action_following_up
                    else R.string.mac_gateway_cloud_action_followup,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (selectedForFollowup) MaterialTheme.colorScheme.primary else workspace.ink,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onStop),
        ) {
            Text(
                text = stringResource(R.string.mac_gateway_cloud_action_stop),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun CloudOutcomeUnknownRow(
    task: CloudTaskSession,
    onRecheck: () -> Unit,
    onRetry: () -> Unit,
    onAbandon: () -> Unit,
) {
    val workspace = workspaceColors()
    val sources = (task.sourceRefs + task.sourcePaths)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "${task.title} · ${stringResource(R.string.mac_gateway_cloud_outcome_unknown_hint)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (sources.isNotEmpty()) {
            Text(
                text = sources.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = workspace.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CloudRecoveryChip(
                label = stringResource(R.string.mac_gateway_cloud_action_recheck),
                primary = true,
                onClick = onRecheck,
            )
            CloudRecoveryChip(
                label = stringResource(R.string.mac_gateway_cloud_action_retry),
                primary = false,
                onClick = onRetry,
            )
            CloudRecoveryChip(
                label = stringResource(R.string.mac_gateway_cloud_action_abandon),
                danger = true,
                onClick = onAbandon,
            )
        }
    }
}

@Composable
private fun CloudRecoveryChip(
    label: String,
    primary: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val workspace = workspaceColors()
    val container = when {
        danger -> MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
        primary -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else -> workspace.paper
    }
    val border = when {
        danger -> MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
        primary -> MaterialTheme.colorScheme.primary
        else -> workspace.hairline
    }
    val textColor = when {
        danger -> MaterialTheme.colorScheme.error
        primary -> MaterialTheme.colorScheme.primary
        else -> workspace.ink
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = container,
        border = BorderStroke(1.dp, border),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun CloudSubmissionReceiptRow(
    submission: CloudDialogueSubmission,
) {
    val workspace = workspaceColors()
    val badgeText = when {
        submission.stopTooLate -> stringResource(R.string.mac_gateway_cloud_receipt_stop_too_late)
        submission.state == CloudInputDisposition.RECEIVED -> stringResource(R.string.mac_gateway_cloud_receipt_received)
        submission.state == CloudInputDisposition.CONSUMED -> stringResource(R.string.mac_gateway_cloud_receipt_consumed)
        else -> stringResource(R.string.mac_gateway_cloud_receipt_cancelled)
    }
    val badgeColor = when {
        submission.stopTooLate -> MaterialTheme.colorScheme.error
        submission.state == CloudInputDisposition.RECEIVED -> workspace.muted
        submission.state == CloudInputDisposition.CONSUMED -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = submission.statusNote?.takeIf { submission.stopTooLate && it.isNotBlank() }
                ?: submission.text.lineSequence().firstOrNull().orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = workspace.faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = badgeText,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = badgeColor,
            maxLines = 1,
        )
    }
}
