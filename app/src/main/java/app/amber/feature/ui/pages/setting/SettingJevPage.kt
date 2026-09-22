package app.amber.feature.ui.pages.setting

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import app.amber.agent.R
import app.amber.core.jev.JevApiMode
import app.amber.core.jev.JevCalibrationRecord
import app.amber.core.jev.JevCalibrationStore
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
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.File

private sealed interface ConnectionUi {
    data object Running : ConnectionUi

    data class Done(val result: JevConnectionTestResult) : ConnectionUi
}

private fun maskKey(key: String): String? =
    key.trim().takeIf { it.isNotEmpty() }?.let { plain ->
        if (plain.length <= 8) "••••" else plain.take(3) + "…" + plain.takeLast(4)
    }

private val calibrationJson = Json { ignoreUnknownKeys = true }

@Composable
fun SettingJevPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val lazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val secretStore = koinInject<SecretStore>()
    val coordinator = koinInject<JevDecisionCoordinator>()
    val calibrationStore = koinInject<JevCalibrationStore>()
    val context = LocalContext.current
    var editingKey by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var editingBaseUrl by remember { mutableStateOf(false) }
    var baseUrlInput by remember { mutableStateOf("") }
    var editingModel by remember { mutableStateOf(false) }
    var modelInput by remember { mutableStateOf("") }
    var connection by remember { mutableStateOf<ConnectionUi?>(null) }
    var calibrationCount by remember { mutableStateOf(0) }
    var calibrationTick by remember { mutableStateOf(0) }
    val jev = settings.jev

    LaunchedEffect(calibrationTick) {
        calibrationCount = withContext(Dispatchers.IO) { calibrationStore.readAll().size }
    }

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
                                onCheckedChange = { enabled ->
                                    updateJev { it.copy(enabled = enabled) }
                                    // 主开关变化同样失效缓存并解除认证暂停（off→on 是显式恢复信号）。
                                    coordinator.onCredentialOrScopeChanged()
                                },
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
                        trailingContent = { SettingChevron() },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_jev_api_mode_title)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    stringResource(R.string.setting_jev_api_mode_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = workspaceColors().muted,
                                )
                                SettingSegmentedChoice(
                                    options = JevApiMode.entries,
                                    selected = jev.apiMode,
                                    onSelected = { mode ->
                                        if (mode != jev.apiMode) {
                                            updateJev { current -> current.copy(apiMode = mode) }
                                            // 方言/端点变化与凭据收紧同语义：失效缓存并解除认证暂停；
                                            // 旧端点上测出的连接结果对新方言无意义，一并清掉。
                                            coordinator.onCredentialOrScopeChanged()
                                            connection = null
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
                    if (jev.apiMode == JevApiMode.VERCEL) {
                        item(
                            modifier = Modifier.settingTwoLine(),
                            onClick = {
                                baseUrlInput = jev.baseUrl ?: JevLimits.VERCEL_DEFAULT_BASE_URL
                                editingBaseUrl = true
                            },
                            headlineContent = { Text(stringResource(R.string.setting_jev_base_url_title)) },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        jev.baseUrl ?: JevLimits.VERCEL_DEFAULT_BASE_URL,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        stringResource(R.string.setting_jev_base_url_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = workspaceColors().muted,
                                    )
                                }
                            },
                            trailingContent = { SettingChevron() },
                        )
                    }
                    item(
                        modifier = Modifier.settingTwoLine(),
                        onClick = {
                            modelInput = when (jev.apiMode) {
                                JevApiMode.TYPESAFE -> jev.model.orEmpty()
                                JevApiMode.VERCEL -> jev.vercelModel.orEmpty()
                            }
                            editingModel = true
                        },
                        headlineContent = { Text(stringResource(R.string.setting_jev_model_title)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    when (jev.apiMode) {
                                        JevApiMode.TYPESAFE -> jev.model ?: JevLimits.DEFAULT_MODEL
                                        JevApiMode.VERCEL -> jev.vercelModel?.takeIf { it.isNotBlank() }
                                            ?: JevLimits.VERCEL_DEFAULT_MODEL
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    stringResource(R.string.setting_jev_model_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = workspaceColors().muted,
                                )
                            }
                        },
                        trailingContent = { SettingChevron() },
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
                                            error == JevConnectionTestResult.ERROR_MODEL_REQUIRED -> stringResource(R.string.setting_jev_connection_model_required)
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
                                modifier = Modifier.heightIn(min = 48.dp),
                                onClick = {
                                    if (connection != ConnectionUi.Running) {
                                        connection = ConnectionUi.Running
                                        scope.launch(Dispatchers.IO) {
                                            connection = ConnectionUi.Done(
                                                coordinator.connectionTest(
                                                    apiMode = jev.apiMode,
                                                    baseUrl = jev.baseUrl,
                                                    model = when (jev.apiMode) {
                                                        JevApiMode.TYPESAFE -> jev.model
                                                        JevApiMode.VERCEL -> jev.vercelModel
                                                    },
                                                ),
                                            )
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
                                        // 显示/编辑存储值而非 modeFor 有效值：主开关关闭时
                                        // 控件仍可预配置，不是看着无响应的死控件。
                                        selected = jev.purposes[purpose] ?: JevMode.OFF,
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
                    item(
                        headlineContent = {
                            val runtime = coordinator.statusSnapshot()
                            val lastError = coordinator.metrics.lastErrorReason()
                            Text(
                                when {
                                    runtime.authPaused ->
                                        stringResource(R.string.setting_jev_status_auth_paused)
                                    runtime.cooldownRemainingMs > 0 ->
                                        stringResource(R.string.setting_jev_status_cooldown, runtime.cooldownRemainingMs)
                                    lastError != null ->
                                        stringResource(R.string.setting_jev_status_last_error, lastError)
                                    else -> stringResource(R.string.setting_jev_status_normal)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (runtime.authPaused) MaterialTheme.colorScheme.error else workspaceColors().muted,
                            )
                        },
                    )
                    item(
                        modifier = Modifier.settingTwoLine(),
                        headlineContent = {
                            Text(
                                stringResource(R.string.setting_jev_calibration_count, calibrationCount),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        supportingContent = {
                            Text(
                                stringResource(R.string.setting_jev_calibration_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = workspaceColors().muted,
                            )
                        },
                        trailingContent = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    enabled = calibrationCount > 0,
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            val exportFile = File(context.cacheDir, "export/amber-jev-calibration.jsonl")
                                            exportFile.parentFile?.mkdirs()
                                            exportFile.writeText(
                                                calibrationStore.readAll()
                                                    .joinToString("\n") {
                                                        calibrationJson.encodeToString(JevCalibrationRecord.serializer(), it)
                                                    },
                                            )
                                            withContext(Dispatchers.Main) {
                                                val uri = FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.fileprovider",
                                                    exportFile,
                                                )
                                                val intent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_SUBJECT, "amber-jev-calibration.jsonl")
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                try {
                                                    context.startActivity(Intent.createChooser(intent, null))
                                                } catch (e: Exception) {
                                                    e.printStackTrace()
                                                }
                                            }
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.setting_jev_calibration_export))
                                }
                                TextButton(
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    enabled = calibrationCount > 0,
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            calibrationStore.clear()
                                            calibrationTick++
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.setting_jev_calibration_clear))
                                }
                            }
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
                            visualTransformation = PasswordVisualTransformation(),
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
                                    connection = null
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
                                connection = null
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

        if (editingBaseUrl) {
            AlertDialog(
                onDismissRequest = { editingBaseUrl = false },
                title = { Text(stringResource(R.string.setting_jev_base_url_dialog_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = baseUrlInput,
                            onValueChange = { baseUrlInput = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.setting_jev_base_url_title)) },
                            supportingText = {
                                Text(stringResource(R.string.setting_jev_base_url_desc))
                            },
                        )
                        if (jev.baseUrl != null) {
                            TextButton(
                                onClick = {
                                    updateJev { it.copy(baseUrl = null) }
                                    coordinator.onCredentialOrScopeChanged()
                                    connection = null
                                    baseUrlInput = ""
                                    editingBaseUrl = false
                                },
                            ) {
                                Text(stringResource(R.string.setting_jev_value_clear))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val trimmed = baseUrlInput.trim()
                            // 显式填缺省值与留空同义：存 null 保持"跟随缺省"语义
                            val newValue = trimmed
                                .takeIf { it.isNotEmpty() && it != JevLimits.VERCEL_DEFAULT_BASE_URL }
                            if (newValue != jev.baseUrl) {
                                updateJev { it.copy(baseUrl = newValue) }
                                coordinator.onCredentialOrScopeChanged()
                                connection = null
                            }
                            baseUrlInput = ""
                            editingBaseUrl = false
                        },
                    ) {
                        Text(stringResource(R.string.setting_jev_key_save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { editingBaseUrl = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        if (editingModel) {
            AlertDialog(
                onDismissRequest = { editingModel = false },
                title = { Text(stringResource(R.string.setting_jev_model_dialog_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = modelInput,
                            onValueChange = { modelInput = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.setting_jev_model_title)) },
                            supportingText = {
                                Text(stringResource(R.string.setting_jev_model_desc))
                            },
                        )
                        val storedModel = when (jev.apiMode) {
                            JevApiMode.TYPESAFE -> jev.model
                            JevApiMode.VERCEL -> jev.vercelModel
                        }
                        if (storedModel != null) {
                            TextButton(
                                onClick = {
                                    updateJev { current ->
                                        when (jev.apiMode) {
                                            JevApiMode.TYPESAFE -> current.copy(model = null)
                                            JevApiMode.VERCEL -> current.copy(vercelModel = null)
                                        }
                                    }
                                    coordinator.onCredentialOrScopeChanged()
                                    connection = null
                                    modelInput = ""
                                    editingModel = false
                                },
                            ) {
                                Text(stringResource(R.string.setting_jev_value_clear))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val newValue = modelInput.trim().ifEmpty { null }
                            updateJev { current ->
                                when (jev.apiMode) {
                                    JevApiMode.TYPESAFE -> current.copy(model = newValue)
                                    JevApiMode.VERCEL -> current.copy(vercelModel = newValue)
                                }
                            }
                            coordinator.onCredentialOrScopeChanged()
                            connection = null
                            modelInput = ""
                            editingModel = false
                        },
                    ) {
                        Text(stringResource(R.string.setting_jev_key_save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { editingModel = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun SettingChevron() {
    Icon(
        imageVector = Lucide.ChevronRight,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = workspaceColors().muted,
    )
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

@Composable
private fun JevApiMode.label(): String = stringResource(
    when (this) {
        JevApiMode.TYPESAFE -> R.string.setting_jev_api_mode_typesafe
        JevApiMode.VERCEL -> R.string.setting_jev_api_mode_vercel
    },
)
