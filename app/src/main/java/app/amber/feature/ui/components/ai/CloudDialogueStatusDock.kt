package app.amber.feature.ui.components.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.macgateway.CloudDialogueSubmission
import app.amber.feature.macgateway.CloudDialogueUiState
import app.amber.feature.macgateway.CloudInputDisposition
import app.amber.feature.macgateway.CloudSubmissionKind
import app.amber.feature.macgateway.CloudTaskSession
import app.amber.feature.macgateway.GatewayEndpointRole
import app.amber.feature.ui.components.ui.workspaceColors
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldAlert

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
    endpointRole: GatewayEndpointRole? = null,
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
                        cloudActive -> stringResource(
                            if (endpointRole == GatewayEndpointRole.MAC) R.string.amber_multi_device_mac_dialogue
                            else R.string.mac_gateway_cloud_mode_active,
                        ) + " · " + stringResource(R.string.mac_gateway_cloud_mode_new_message)
                        else -> stringResource(R.string.mac_gateway_cloud_mode_new_message)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (targetTask != null) MaterialTheme.colorScheme.primary else workspace.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (targetTask != null) {
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .widthIn(min = 48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { onSelectFollowupTarget(null) }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.mac_gateway_cloud_mode_clear_target),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                }
            }

            activeTasks.forEach { task ->
                CloudActiveTaskRow(
                    task = task,
                    endpointRole = endpointRole,
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
    endpointRole: GatewayEndpointRole?,
    selectedForFollowup: Boolean,
    onToggleFollowup: () -> Unit,
    onStop: () -> Unit,
) {
    val workspace = workspaceColors()
    val progressLine = task.progressText?.takeIf { it.isNotBlank() }
        ?: stringResource(
            if (endpointRole == GatewayEndpointRole.MAC) R.string.amber_multi_device_mac_running
            else R.string.mac_gateway_cloud_task_running,
        )

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
        CloudDockActionChip(
            label = stringResource(
                if (selectedForFollowup) R.string.mac_gateway_cloud_action_following_up
                else R.string.mac_gateway_cloud_action_followup,
            ),
            primary = selectedForFollowup,
            onClick = onToggleFollowup,
        )
        CloudDockActionChip(
            label = stringResource(R.string.mac_gateway_cloud_action_stop),
            danger = true,
            onClick = onStop,
        )
    }
}

@Composable
private fun CloudOutcomeUnknownRow(
    task: CloudTaskSession,
    onRecheck: () -> Unit,
    onRetry: () -> Unit,
    onAbandon: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val sources = (task.sourceRefs + task.sourcePaths)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = scheme.errorContainer,
        contentColor = scheme.onErrorContainer,
        border = BorderStroke(1.dp, scheme.error.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Lucide.ShieldAlert,
                    contentDescription = null,
                    tint = scheme.onErrorContainer,
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(15.dp),
                )
                Text(
                    text = "${task.title} · ${stringResource(R.string.mac_gateway_cloud_outcome_unknown_hint)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onErrorContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (sources.isNotEmpty()) {
                Text(
                    text = sources.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = scheme.onErrorContainer.copy(alpha = 0.80f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CloudDockActionChip(
                    label = stringResource(R.string.mac_gateway_cloud_action_recheck),
                    primary = true,
                    onClick = onRecheck,
                )
                CloudDockActionChip(
                    label = stringResource(R.string.mac_gateway_cloud_action_retry),
                    primary = false,
                    onClick = onRetry,
                )
                CloudDockActionChip(
                    label = stringResource(R.string.mac_gateway_cloud_action_abandon),
                    danger = true,
                    onClick = onAbandon,
                )
            }
        }
    }
}

@Composable
private fun CloudDockActionChip(
    label: String,
    primary: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val workspace = workspaceColors()
    val scheme = MaterialTheme.colorScheme
    val container = when {
        danger -> scheme.errorContainer
        primary -> scheme.primaryContainer
        else -> workspace.paper
    }
    val border = when {
        danger -> scheme.error.copy(alpha = 0.45f)
        primary -> scheme.primary.copy(alpha = 0.45f)
        else -> workspace.hairline
    }
    val textColor = when {
        danger -> scheme.onErrorContainer
        primary -> scheme.onPrimaryContainer
        else -> workspace.ink
    }
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = container,
            border = BorderStroke(1.dp, border),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun CloudSubmissionReceiptRow(
    submission: CloudDialogueSubmission,
) {
    val workspace = workspaceColors()
    val scheme = MaterialTheme.colorScheme
    val badgeText = when {
        submission.stopTooLate -> stringResource(R.string.mac_gateway_cloud_receipt_stop_too_late)
        submission.state == CloudInputDisposition.RECEIVED -> stringResource(R.string.mac_gateway_cloud_receipt_received)
        submission.state == CloudInputDisposition.CONSUMED -> stringResource(R.string.mac_gateway_cloud_receipt_consumed)
        else -> stringResource(R.string.mac_gateway_cloud_receipt_cancelled)
    }
    val (badgeContainer, badgeColor) = when {
        submission.stopTooLate -> scheme.errorContainer to scheme.onErrorContainer
        submission.state == CloudInputDisposition.RECEIVED -> workspace.row to workspace.muted
        submission.state == CloudInputDisposition.CONSUMED -> scheme.primaryContainer to scheme.onPrimaryContainer
        else -> scheme.errorContainer to scheme.onErrorContainer
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
            color = workspace.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = badgeContainer,
            contentColor = badgeColor,
        ) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = badgeColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
