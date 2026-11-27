package app.amber.feature.ui.pages.setting

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.macgateway.CloudDetailConfiguration
import app.amber.feature.macgateway.CloudDetailRule
import app.amber.feature.macgateway.CloudDetailTask
import app.amber.feature.macgateway.CloudEndpointDetailApi
import app.amber.feature.macgateway.CloudEndpointRequest
import app.amber.feature.macgateway.CloudRuleAction
import app.amber.feature.macgateway.MacBoardItem
import app.amber.feature.macgateway.MacGatewayException
import app.amber.feature.macgateway.MacGatewayRepository
import app.amber.feature.ui.components.richtext.MarkdownNew
import app.amber.feature.ui.components.ui.WorkspaceLeadingIcon
import app.amber.feature.ui.components.ui.WorkspaceTone
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.ListChecks
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

private enum class CloudDetailPage { Configuration, Rules, TaskBoard, ComposeTask, Task }

@Composable
internal fun CloudEndpointDetail(
    repository: MacGatewayRepository,
    state: MacGatewayRepository.State,
    transport: CloudEndpointRequest,
) {
    val connection = state.connection ?: return
    key(connection.gatewayId, connection.deviceId, state.generation) {
        val api = remember(repository, transport) { CloudEndpointDetailApi(repository, connection, state.generation, transport) }
        var page by rememberSaveable { mutableStateOf<CloudDetailPage?>(null) }
        var taskId by rememberSaveable { mutableStateOf<String?>(null) }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ExperimentSectionCard(stringResource(R.string.cloud_detail_configuration)) {
                CloudDetailLink(Lucide.Cpu, R.string.cloud_detail_model_search) { page = CloudDetailPage.Configuration }
                HorizontalDivider()
                CloudDetailLink(Lucide.ListChecks, R.string.cloud_detail_rules) { page = CloudDetailPage.Rules }
            }
            ExperimentSectionCard(stringResource(R.string.cloud_detail_tasks)) {
                CloudDetailLink(Lucide.Send, R.string.cloud_detail_submit) { page = CloudDetailPage.ComposeTask }
                HorizontalDivider()
                CloudDetailLink(Lucide.ListChecks, R.string.cloud_detail_task_board) { page = CloudDetailPage.TaskBoard }
            }
        }

        when (page) {
            CloudDetailPage.Configuration -> CloudReadDialog(
                title = R.string.cloud_detail_model_search,
                load = api::configuration,
                onClose = { page = null },
            ) { configuration, _ ->
                CloudConfigurationContent(configuration)
            }
            CloudDetailPage.Rules -> CloudRulesDialog(api) { page = null }
            CloudDetailPage.TaskBoard -> CloudReadDialog(
                title = R.string.cloud_detail_task_board,
                load = api::taskBoard,
                onClose = { page = null },
            ) { board, _ ->
                if (board.tasks.isEmpty() && board.gatewayItems.isEmpty()) {
                    CloudDetailNote(stringResource(R.string.cloud_detail_no_tasks))
                }
                board.tasks.forEach { task ->
                    val detail = listOfNotNull(
                        stringResource(cloudTaskStatus(task.state)),
                        task.progress?.takeIf(String::isNotBlank),
                    ).joinToString(" · ")
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable(role = Role.Button) {
                                taskId = task.id
                                page = CloudDetailPage.Task
                            }.padding(vertical = 6.dp),
                    ) {
                        Text(task.title, style = MaterialTheme.typography.bodyLarge)
                        CloudDetailNote(detail)
                    }
                    HorizontalDivider()
                }
                board.gatewayItems.forEach { item ->
                    CloudDetailValue(item.title, stringResource(cloudBoardStatus(item.column)))
                    if (item.detail.isNotBlank()) CloudDetailNote(item.detail)
                }
            }
            CloudDetailPage.ComposeTask -> CloudComposeTaskDialog(api, onClose = { page = null }) { submittedId ->
                taskId = submittedId
                page = CloudDetailPage.Task
            }
            CloudDetailPage.Task -> taskId?.let { id ->
                CloudTaskDialog(api, id) { page = CloudDetailPage.TaskBoard }
            }
            null -> Unit
        }
    }
}

@Composable
private fun CloudDetailLink(icon: ImageVector, @StringRes title: Int, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WorkspaceLeadingIcon(icon = icon, tone = WorkspaceTone.Accent)
        Text(stringResource(title), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Icon(Lucide.ChevronRight, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
    }
}

@Composable
private fun CloudConfigurationContent(configuration: CloudDetailConfiguration) {
    val model = configuration.model?.takeIf { it.modelId.isNotBlank() }
    CloudDetailValue(
        stringResource(R.string.cloud_detail_model),
        model?.let { "${it.provider} · ${it.modelId}" } ?: stringResource(R.string.cloud_detail_not_configured),
    )
    if (model != null) {
        val credentialStatus = when (model.authMode) {
            "chatgptSiwc" -> {
                val signIn = stringResource(R.string.cloud_detail_siwc)
                if (model.siwcConfigured) signIn else "$signIn · ${stringResource(R.string.cloud_detail_not_configured)}"
            }
            "plan" -> stringResource(R.string.cloud_detail_plan)
            "apiKey" -> cloudCredentialStatus(configuration.credentials.modelApiKey.configured)
            else -> model.authMode
        }
        CloudDetailValue(stringResource(R.string.cloud_detail_auth), credentialStatus)
    }
    HorizontalDivider()
    val search = configuration.search
    val searchMode = when (search.mode) {
        "aggregate" -> stringResource(R.string.cloud_detail_search_aggregate)
        "fallback" -> stringResource(R.string.cloud_detail_search_fallback)
        else -> search.mode
    }
    CloudDetailValue(
        stringResource(R.string.cloud_detail_search),
        if (search.sources.isEmpty()) stringResource(R.string.cloud_detail_not_configured) else searchMode,
    )
    search.sources.forEach { source ->
        when (source) {
            "bing" -> Text("Bing")
            "duckduckgo" -> Text("DuckDuckGo")
            "exa" -> CloudDetailValue("Exa", cloudCredentialStatus(configuration.credentials.exaApiKey.configured))
            "brave" -> CloudDetailValue("Brave", cloudCredentialStatus(configuration.credentials.braveApiKey.configured))
            else -> Text(source)
        }
    }
}

@Composable
private fun CloudDetailValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        CloudDetailNote(value)
    }
}

@Composable
private fun cloudCredentialStatus(configured: Boolean): String = stringResource(
    if (configured) R.string.cloud_detail_key_configured else R.string.cloud_detail_not_configured,
)

@Composable
private fun CloudRulesDialog(api: CloudEndpointDetailApi, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Exception?>(null) }
    var pendingRevocation by remember { mutableStateOf<CloudDetailRule?>(null) }

    fun updateRule(rule: CloudDetailRule, action: CloudRuleAction, refresh: () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                api.updateRule(rule.id, action)
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure
            } finally {
                busy = false
            }
        }
    }

    CloudReadDialog(
        title = R.string.cloud_detail_rules,
        load = api::rules,
        onClose = onClose,
        busy = busy,
    ) { list, refresh ->
        if (list.rules.isEmpty()) CloudDetailNote(stringResource(R.string.cloud_detail_no_rules))
        error?.let { CloudDetailError(it) }
        list.rules.forEach { rule ->
            Text(rule.title, style = MaterialTheme.typography.titleSmall)
            CloudDetailNote(stringResource(cloudRuleStatus(rule.status)))
            Text(rule.instruction, style = MaterialTheme.typography.bodyMedium)
            val actions = when (rule.status) {
                "proposed" -> listOf(CloudRuleAction.Confirm, CloudRuleAction.Archive)
                "active" -> listOf(CloudRuleAction.Revoke)
                else -> emptyList()
            }
            actions.forEach { action ->
                TextButton(enabled = !busy, onClick = {
                    if (action == CloudRuleAction.Revoke) pendingRevocation = rule
                    else updateRule(rule, action, refresh)
                }) {
                    Text(
                        stringResource(
                            when (action) {
                                CloudRuleAction.Confirm -> R.string.confirm
                                CloudRuleAction.Archive -> R.string.cloud_detail_archive_rule
                                CloudRuleAction.Revoke -> R.string.cloud_detail_revoke_rule
                            },
                        ),
                    )
                }
            }
            HorizontalDivider()
        }
        pendingRevocation?.let { rule ->
            CloudDestructiveConfirmation(
                title = R.string.cloud_detail_revoke_rule,
                subject = rule.title,
                onClose = { pendingRevocation = null },
                onConfirm = { updateRule(rule, CloudRuleAction.Revoke, refresh) },
            )
        }
    }
}

@Composable
private fun CloudComposeTaskDialog(
    api: CloudEndpointDetailApi,
    onClose: () -> Unit,
    onSubmitted: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var requestId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Exception?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(stringResource(R.string.cloud_detail_submit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { next ->
                        val boundedText = next.take(24_000).trimEnd { it.isHighSurrogate() }
                        if (text.trim() != boundedText.trim()) {
                            requestId = UUID.randomUUID().toString()
                            error = null
                        }
                        text = boundedText
                    },
                    enabled = !busy,
                    label = { Text(stringResource(R.string.cloud_detail_task_prompt)) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) CircularProgressIndicator(Modifier.size(20.dp))
                error?.let {
                    CloudDetailError(it)
                    CloudDetailNote(stringResource(R.string.cloud_detail_submit_retry))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && text.isNotBlank(), onClick = {
                val submittedText = text.trim()
                val submittedRequestId = requestId
                busy = true
                error = null
                scope.launch {
                    try {
                        onSubmitted(api.submitTask(submittedRequestId, submittedText).id)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = failure
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(R.string.cloud_detail_send)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CloudTaskDialog(api: CloudEndpointDetailApi, id: String, onClose: () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Exception?>(null) }
    val scope = rememberCoroutineScope()
    CloudReadDialog(
        title = R.string.cloud_detail_task,
        load = { api.task(id) },
        onClose = onClose,
        busy = busy,
    ) { task, refresh ->
        CloudTaskContent(task)
        error?.let { CloudDetailError(it) }
        if (task.workItem.canCancel) {
            TextButton(enabled = !busy, onClick = { confirmCancel = true }) {
                Text(stringResource(R.string.cloud_detail_cancel_task), color = MaterialTheme.colorScheme.error)
            }
        }
        if (confirmCancel) {
            CloudDestructiveConfirmation(
                title = R.string.cloud_detail_cancel_task,
                subject = task.workItem.title,
                onClose = { confirmCancel = false },
                onConfirm = {
                    error = null
                    busy = true
                    scope.launch {
                        try {
                            api.cancelTask(id)
                            refresh()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure
                        } finally {
                            busy = false
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun CloudDestructiveConfirmation(
    @StringRes title: Int,
    subject: String,
    onClose: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(title)) },
        text = { Text(subject) },
        confirmButton = {
            TextButton(onClick = {
                onClose()
                onConfirm()
            }) { Text(stringResource(R.string.confirm), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.mac_gateway_keep)) } },
    )
}

@Composable
private fun CloudTaskContent(task: CloudDetailTask) {
    Text(task.workItem.title, style = MaterialTheme.typography.titleSmall)
    CloudDetailNote(stringResource(cloudTaskStatus(task.workItem.state)))
    task.workItem.progress?.takeIf(String::isNotBlank)?.let { CloudDetailNote(it) }
    Text(task.brief)
    task.failure?.takeIf(String::isNotBlank)?.let { CloudDetailNote(it) }
    task.resultMarkdown?.takeIf(String::isNotBlank)?.let { result ->
        HorizontalDivider()
        SelectionContainer { MarkdownNew(content = result, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun <T> CloudReadDialog(
    @StringRes title: Int,
    load: suspend () -> T,
    onClose: () -> Unit,
    busy: Boolean = false,
    content: @Composable (T, () -> Unit) -> Unit,
) {
    var refreshCount by remember { mutableIntStateOf(0) }
    val result by produceState<Result<T>?>(null, refreshCount) {
        value = null
        value = try {
            Result.success(load())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(stringResource(title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val current = result
                if (current == null) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                } else {
                    current.fold(
                        onSuccess = { content(it) { refreshCount++ } },
                        onFailure = { CloudDetailError(it) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = result != null && !busy, onClick = { refreshCount++ }) {
                Text(stringResource(R.string.mac_gateway_refresh))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onClose) { Text(stringResource(R.string.cloud_detail_close)) }
        },
    )
}

@Composable
private fun CloudDetailNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CloudDetailError(error: Throwable) {
    val message = when (error) {
        is MacGatewayException.Unreachable -> R.string.mac_gateway_offline
        is MacGatewayException.Forbidden, is MacGatewayException.Unauthorized -> R.string.cloud_detail_access_denied
        is MacGatewayException.Conflict -> R.string.cloud_detail_stale
        is MacGatewayException.NotFound -> R.string.cloud_detail_unavailable
        is MacGatewayException.CertificateMismatch -> R.string.cloud_detail_certificate
        else -> R.string.cloud_detail_request_failed
    }
    Text(stringResource(message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@StringRes
private fun cloudRuleStatus(status: String): Int = when (status) {
    "proposed" -> R.string.cloud_detail_rule_proposed
    "active" -> R.string.cloud_detail_rule_active
    "archived" -> R.string.cloud_detail_rule_archived
    "revoked" -> R.string.cloud_detail_rule_revoked
    else -> R.string.cloud_detail_unknown
}

@StringRes
private fun cloudTaskStatus(state: String): Int = when (state) {
    "QUEUED", "STARTING" -> R.string.cloud_detail_queued
    "WORKING" -> R.string.cloud_detail_running
    "WAITING" -> R.string.cloud_detail_waiting
    "IDLE" -> R.string.cloud_detail_idle
    "COMPLETED" -> R.string.cloud_detail_done
    "FAILED" -> R.string.cloud_detail_failed
    "CANCELLED" -> R.string.cloud_detail_cancelled
    else -> R.string.cloud_detail_unknown
}

@StringRes
private fun cloudBoardStatus(column: MacBoardItem.Column): Int = when (column) {
    MacBoardItem.Column.NEEDS_YOU -> R.string.cloud_detail_waiting
    MacBoardItem.Column.RUNNING -> R.string.cloud_detail_running
    MacBoardItem.Column.SCHEDULED -> R.string.cloud_detail_scheduled
    MacBoardItem.Column.DONE -> R.string.cloud_detail_ended
}
