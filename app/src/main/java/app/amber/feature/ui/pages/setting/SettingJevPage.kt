package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import app.amber.agent.R
import app.amber.core.jev.JevConnectionTestResult
import app.amber.core.jev.JevDataScope
import app.amber.core.jev.JevDecisionCoordinator
import app.amber.core.jev.JevLimits
import app.amber.core.jev.JevMetricEntry
import app.amber.core.jev.JevMode
import app.amber.core.jev.JevPurpose
import app.amber.core.jev.JevSetting
import app.amber.core.settings.secret.SecretStore
import app.amber.core.di.JevApiKeyDescriptor
import app.amber.core.utils.plus
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.Switch
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

private sealed interface ConnectionUi {
    data object Running : ConnectionUi

    data class Done(val result: JevConnectionTestResult) : ConnectionUi
}

private fun maskKey(key: String): String? =
    key.trim().takeIf { it.isNotEmpty() }?.let { plain ->
        if (plain.length <= 8) "••••" else plain.take(3) + "…" + plain.takeLast(4)
    }

@Composable
fun SettingJevPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val lazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val secretStore = koinInject<SecretStore>()
    val coordinator = koinInject<JevDecisionCoordinator>()
    var editingKey by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var connection by remember { mutableStateOf<ConnectionUi?>(null) }
    val jev = settings.jev

    fun updateJev(transform: (JevSetting) -> JevSetting) {
        vm.updateSettings { current -> current.copy(jev = transform(current.jev)) }
    }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.setting_jev_title),
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .amberCanvas(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = contentPadding + PaddingValues(horizontal = SettingPageHorizontalInset, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            state = lazyListState,
        ) {
            item("service") {
                SettingCardGroup(title = stringResource(R.string.setting_jev_service_section)) {
                    item(
                        modifier = Modifier.settingTwoLine(),
                        headlineContent = { Text(stringResource(R.string.setting_jev_master_title)) },
                        supportingContent = {
                            Text(stringResource(R.string.setting_jev_master_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = jev.enabled,
                                onCheckedChange = { enabled -> updateJev { it.copy(enabled = enabled) } },
                            )
                        },
                    )
                    item(
                        modifier = Modifier.settingTwoLine(),
                        onClick = { editingKey = true },
                        headlineContent = { Text(stringResource(R.string.setting_jev_key_title)) },
                        supportingContent = {
                            Text(jev.apiKeyMask ?: stringResource(R.string.setting_jev_key_unset))
                        },
                    )
                    item(
                        modifier = Modifier.settingTwoLine(),
                        headlineContent = { Text(stringResource(R.string.setting_jev_connection_title)) },
                        supportingContent = {
                            Text(
                                when (val state = connection) {
                                    ConnectionUi.Running -> stringResource(R.string.setting_jev_connection_running)
                                    is ConnectionUi.Done -> with(state.result) {
                                        when {
                                            ok -> stringResource(
                                                R.string.setting_jev_connection_ok,
                                                model ?: JevLimits.CONNECTION_TEST_MODEL,
                                                latencyMs,
                                            )
                                            keyMissing -> stringResource(R.string.setting_jev_connection_key_missing)
                                            budgetExhausted -> stringResource(R.string.setting_jev_connection_budget)
                                            else -> stringResource(
                                                R.string.setting_jev_connection_failed,
                                                error ?: "",
                                            )
                                        }
                                    }
                                    null -> stringResource(R.string.setting_jev_connection_desc)
                                }
                            )
                        },
                        trailingContent = {
                            TextButton(
                                onClick = {
                                    if (connection != ConnectionUi.Running) {
                                        connection = ConnectionUi.Running
                                        scope.launch(Dispatchers.IO) {
                                            connection = ConnectionUi.Done(coordinator.connectionTest())
                                        }
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.setting_jev_connection_run))
                            }
                        },
                    )
                    item(
                        headlineContent = {
                            Text(
                                stringResource(R.string.setting_jev_shadow_notice),
                                style = MaterialTheme.typography.bodySmall,
                                color = workspaceColors().muted,
                            )
                        },
                    )
                }
            }

            item("purposes") {
                SettingCardGroup(title = stringResource(R.string.setting_jev_purposes_section)) {
                    JevPurpose.entries.forEach { purpose ->
                        // supportingContent 槽模式（对照 SettingAgentExecutionPage）：
                        // 由 CardGroup 行提供统一内边距，避免自绘 padding 造成组内错位。
                        item(
                            headlineContent = { Text(purpose.title()) },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        purpose.description(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = workspaceColors().muted,
                                    )
                                    SettingSegmentedChoice(
                                        options = JevMode.entries,
                                        selected = jev.modeFor(purpose),
                                        onSelected = { mode ->
                                            updateJev { current ->
                                                current.copy(
                                                    purposes = if (mode == JevMode.OFF) {
                                                        current.purposes - purpose
                                                    } else {
                                                        current.purposes + (purpose to mode)
                                                    },
                                                )
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { mode ->
                                            Text(
                                                mode.label(),
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
            }

            item("scopes") {
                SettingCardGroup(title = stringResource(R.string.setting_jev_scopes_section)) {
                    JevDataScope.entries.forEach { dataScope ->
                        item(
                            modifier = Modifier.settingTwoLine(),
                            headlineContent = { Text(dataScope.title()) },
                            supportingContent = { Text(dataScope.description()) },
                            trailingContent = {
                                Switch(
                                    checked = dataScope in jev.dataScopes,
                                    onCheckedChange = { allowed ->
                                        updateJev { current ->
                                            current.copy(
                                                dataScopes = if (allowed) {
                                                    current.dataScopes + dataScope
                                                } else {
                                                    current.dataScopes - dataScope
                                                },
                                            )
                                        }
                                        // Scope tightening/re expansion invalidates caches and unblocks auth pause.
                                        coordinator.onCredentialOrScopeChanged()
                                    },
                                )
                            },
                        )
                    }
                }
            }

            item("status") {
                val daily = coordinator.dailyUsage()
                val summary = coordinator.metrics.summary()
                SettingCardGroup(title = stringResource(R.string.setting_jev_status_section)) {
                    item(
                        headlineContent = {
                            Text(
                                stringResource(
                                    R.string.setting_jev_status_daily,
                                    daily.requests,
                                    JevLimits.DAILY_MAX_REQUESTS,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            Text(
                                stringResource(
                                    R.string.setting_jev_status_metrics,
                                    summary.total,
                                    summary.applied,
                                    summary.shadow,
                                    summary.cacheHits,
                                    summary.fallbacks,
                                    summary.failures,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            Text(
                                summary.lastOutcome?.let { last ->
                                    stringResource(R.string.setting_jev_status_last, outcomeLabel(last))
                                } ?: stringResource(R.string.setting_jev_status_none),
                                style = MaterialTheme.typography.bodyMedium,
                                color = workspaceColors().muted,
                            )
                        },
                    )
                }
            }
        }

        if (editingKey) {
            AlertDialog(
                onDismissRequest = { editingKey = false },
                title = { Text(stringResource(R.string.setting_jev_key_dialog_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = keyInput,
                            onValueChange = { keyInput = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.setting_jev_key_title)) },
                            supportingText = {
                                Text(stringResource(R.string.setting_jev_key_hint))
                            },
                        )
                        Text(
                            stringResource(R.string.setting_jev_key_hint2),
                            style = MaterialTheme.typography.bodySmall,
                            color = workspaceColors().muted,
                        )
                        if (jev.apiKeyMask != null) {
                            TextButton(
                                onClick = {
                                    secretStore.delete(JevApiKeyDescriptor)
                                    updateJev { it.copy(apiKeyMask = null) }
                                    coordinator.onCredentialOrScopeChanged()
                                    keyInput = ""
                                    editingKey = false
                                },
                            ) {
                                Text(stringResource(R.string.setting_jev_key_clear))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val trimmed = keyInput.trim()
                            if (trimmed.isNotEmpty()) {
                                secretStore.update(JevApiKeyDescriptor, trimmed)
                                val mask = maskKey(trimmed)
                                updateJev { it.copy(apiKeyMask = mask) }
                                coordinator.onCredentialOrScopeChanged()
                            }
                            keyInput = ""
                            editingKey = false
                        },
                    ) {
                        Text(stringResource(R.string.setting_jev_key_save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { editingKey = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun JevPurpose.title(): String = stringResource(
    when (this) {
        JevPurpose.TOOL_DISCOVERY -> R.string.setting_jev_purpose_tool_discovery
        JevPurpose.MEMORY_RECALL -> R.string.setting_jev_purpose_memory_recall
        JevPurpose.CONTEXT_SELECTION -> R.string.setting_jev_purpose_context_selection
        JevPurpose.MODEL_ROUTING -> R.string.setting_jev_purpose_model_routing
        JevPurpose.WEB_AUTOMATION -> R.string.setting_jev_purpose_web_automation
        JevPurpose.SCREEN_AUTOMATION -> R.string.setting_jev_purpose_screen_automation
    },
)

@Composable
private fun JevPurpose.description(): String = stringResource(
    when (this) {
        JevPurpose.TOOL_DISCOVERY -> R.string.setting_jev_purpose_tool_discovery_desc
        JevPurpose.MEMORY_RECALL -> R.string.setting_jev_purpose_memory_recall_desc
        JevPurpose.CONTEXT_SELECTION -> R.string.setting_jev_purpose_context_selection_desc
        JevPurpose.MODEL_ROUTING -> R.string.setting_jev_purpose_model_routing_desc
        JevPurpose.WEB_AUTOMATION -> R.string.setting_jev_purpose_web_automation_desc
        JevPurpose.SCREEN_AUTOMATION -> R.string.setting_jev_purpose_screen_automation_desc
    },
)

@Composable
private fun JevDataScope.title(): String = stringResource(
    when (this) {
        JevDataScope.TOOL_METADATA -> R.string.setting_jev_scope_tool_metadata
        JevDataScope.TASK_TEXT -> R.string.setting_jev_scope_task_text
        JevDataScope.PERSONAL_MEMORY -> R.string.setting_jev_scope_personal_memory
        JevDataScope.TOOL_OUTPUT -> R.string.setting_jev_scope_tool_output
        JevDataScope.WEB_CONTENT -> R.string.setting_jev_scope_web_content
        JevDataScope.SCREEN_CONTENT -> R.string.setting_jev_scope_screen_content
    },
)

@Composable
private fun JevDataScope.description(): String = stringResource(
    when (this) {
        JevDataScope.TOOL_METADATA -> R.string.setting_jev_scope_tool_metadata_desc
        JevDataScope.TASK_TEXT -> R.string.setting_jev_scope_task_text_desc
        JevDataScope.PERSONAL_MEMORY -> R.string.setting_jev_scope_personal_memory_desc
        JevDataScope.TOOL_OUTPUT -> R.string.setting_jev_scope_tool_output_desc
        JevDataScope.WEB_CONTENT -> R.string.setting_jev_scope_web_content_desc
        JevDataScope.SCREEN_CONTENT -> R.string.setting_jev_scope_screen_content_desc
    },
)

@Composable
private fun outcomeLabel(outcome: String): String = when {
    outcome == JevMetricEntry.OUTCOME_APPLIED -> stringResource(R.string.setting_jev_outcome_applied)
    outcome == JevMetricEntry.OUTCOME_SHADOW -> stringResource(R.string.setting_jev_outcome_shadow)
    outcome == JevMetricEntry.OUTCOME_CACHE_HIT -> stringResource(R.string.setting_jev_outcome_cache)
    outcome == JevMetricEntry.OUTCOME_FAILED -> stringResource(R.string.setting_jev_outcome_failed)
    outcome.startsWith("fallback") -> stringResource(R.string.setting_jev_outcome_fallback)
    else -> outcome
}

@Composable
private fun JevMode.label(): String = stringResource(
    when (this) {
        JevMode.OFF -> R.string.setting_jev_mode_off
        JevMode.SHADOW -> R.string.setting_jev_mode_shadow
        JevMode.ACTIVE -> R.string.setting_jev_mode_active
    },
)
