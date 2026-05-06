package me.rerere.rikkahub.ui.pages.setting

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.agent.system.AgentPermissionBroker
import me.rerere.rikkahub.data.agent.system.AgentPermissionCapability
import me.rerere.rikkahub.data.agent.system.AgentPermissionRisk
import me.rerere.rikkahub.data.agent.system.AgentPermissionStatus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

@Composable
fun SettingSystemAccessPage(
    permissionBroker: AgentPermissionBroker = koinInject(),
    settingsStore: SettingsStore = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val externalAccess = settings.agentRuntime.externalFileAccess
    var externalRootInput by remember(externalAccess.roots) {
        mutableStateOf(externalAccess.roots.firstOrNull().orEmpty())
    }
    var refreshToken by remember { mutableIntStateOf(0) }
    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshToken++
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshToken++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    val capabilities = remember(refreshToken, permissionBroker) {
        permissionBroker.capabilities.map { it to permissionBroker.getStatus(it) }
    }

    fun requestRuntime(capability: AgentPermissionCapability) {
        val permissions = permissionBroker.runtimePermissionsFor(capability)
        if (permissions.isNotEmpty()) {
            runtimePermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    fun openSpecial(capability: AgentPermissionCapability) {
        val intent = permissionBroker.createSpecialAccessIntent(capability.id) ?: return
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "无法打开系统权限设置页", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_system_access_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("summary") {
                CardGroup(title = { Text("权限中心") }) {
                    item(
                        leadingContent = { Icon(HugeIcons.Settings03, contentDescription = null) },
                        headlineContent = { Text("核心运行时权限") },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("按需授权，不做后台静默采集。所有系统工具调用都会先检查权限并写入审计日志。")
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = {
                                            val permissions = permissionBroker.runtimePermissionsForCoreBatch()
                                            if (permissions.isNotEmpty()) {
                                                runtimePermissionLauncher.launch(permissions.toTypedArray())
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                                    ) {
                                        Text("申请核心权限", maxLines = 1)
                                    }
                                    OutlinedButton(
                                        onClick = { refreshToken++ },
                                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                                    ) {
                                        Text("刷新", maxLines = 1)
                                    }
                                }
                            }
                        },
                    )
                }
            }

            item("runtime") {
                CardGroup(title = { Text("运行时权限") }) {
                    capabilities
                        .filter { (capability, _) -> capability.specialAccess == null }
                        .forEach { (capability, status) ->
                            permissionItem(
                                capability = capability,
                                status = status,
                                onClick = {
                                    if (status != AgentPermissionStatus.Granted) {
                                        requestRuntime(capability)
                                    }
                                },
                                action = {
                                    PermissionAction(
                                        status = status,
                                        onClick = { requestRuntime(capability) },
                                    )
                                }
                            )
                        }
                }
            }

            item("special") {
                CardGroup(title = { Text("特殊权限") }) {
                    capabilities
                        .filter { (capability, _) -> capability.specialAccess != null }
                        .forEach { (capability, status) ->
                            permissionItem(
                                capability = capability,
                                status = status,
                                onClick = {
                                    if (status != AgentPermissionStatus.Granted) {
                                        openSpecial(capability)
                                    }
                                },
                                action = {
                                    PermissionAction(
                                        status = status,
                                        onClick = { openSpecial(capability) },
                                    )
                                }
                            )
                        }
                }
            }

            item("external_file_access") {
                CardGroup(title = { Text("外部文件访问范围") }) {
                    item(
                        leadingContent = { Icon(HugeIcons.File02, contentDescription = null) },
                        headlineContent = { Text("全文件访问 allowlist") },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("授权后也只允许 Agent 访问这里列出的绝对路径前缀。写入、删除和覆盖仍会二次确认。")
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = if (externalAccess.enabled) "已启用" else "未启用",
                                        color = if (externalAccess.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    )
                                    Text("根目录 ${externalAccess.roots.size} 个")
                                }
                                OutlinedTextField(
                                    value = externalRootInput,
                                    onValueChange = { externalRootInput = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text("允许访问的绝对路径") },
                                    supportingText = { Text("例如 /storage/emulated/0/Download 或 /sdcard/Documents") },
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = {
                                            val root = externalRootInput.trim()
                                            scope.launch {
                                                settingsStore.update { current ->
                                                    current.copy(
                                                        agentRuntime = current.agentRuntime.copy(
                                                            externalFileAccess = current.agentRuntime.externalFileAccess.copy(
                                                                enabled = true,
                                                                roots = (current.agentRuntime.externalFileAccess.roots + root)
                                                                    .map { it.trim() }
                                                                    .filter { it.isNotBlank() }
                                                                    .distinct(),
                                                            )
                                                        )
                                                    )
                                                }
                                            }
                                        },
                                        enabled = externalRootInput.isNotBlank(),
                                    ) {
                                        Text("加入并启用", maxLines = 1)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                settingsStore.update { current ->
                                                    current.copy(
                                                        agentRuntime = current.agentRuntime.copy(
                                                            externalFileAccess = current.agentRuntime.externalFileAccess.copy(
                                                                enabled = false,
                                                                roots = emptyList(),
                                                            )
                                                        )
                                                    )
                                                }
                                            }
                                        },
                                    ) {
                                        Text("清空", maxLines = 1)
                                    }
                                }
                                if (externalAccess.roots.isNotEmpty()) {
                                    Text(externalAccess.roots.joinToString("\n"))
                                }
                            }
                        },
                        trailingContent = {
                            Switch(
                                checked = externalAccess.enabled,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        settingsStore.update { current ->
                                            current.copy(
                                                agentRuntime = current.agentRuntime.copy(
                                                    externalFileAccess = current.agentRuntime.externalFileAccess.copy(enabled = enabled)
                                                )
                                            )
                                        }
                                    }
                                }
                            )
                        },
                    )
                }
            }
        }
    }
}

private fun CardGroupScope.permissionItem(
    capability: AgentPermissionCapability,
    status: AgentPermissionStatus,
    onClick: () -> Unit,
    action: @Composable () -> Unit,
) {
    item(
        onClick = onClick,
        leadingContent = {
            Icon(
                imageVector = if (capability.risk == AgentPermissionRisk.High) {
                    HugeIcons.Alert01
                } else {
                    HugeIcons.Settings03
                },
                contentDescription = null,
                tint = when (capability.risk) {
                    AgentPermissionRisk.High -> MaterialTheme.colorScheme.error
                    AgentPermissionRisk.Sensitive -> MaterialTheme.colorScheme.tertiary
                    AgentPermissionRisk.Normal -> MaterialTheme.colorScheme.primary
                },
            )
        },
        headlineContent = { Text(capability.title) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(capability.description)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = status.label(),
                        color = when (status) {
                            AgentPermissionStatus.Granted -> MaterialTheme.colorScheme.primary
                            AgentPermissionStatus.Unsupported -> MaterialTheme.colorScheme.outline
                            else -> MaterialTheme.colorScheme.error
                        },
                    )
                    Text(
                        text = capability.risk.label(),
                        color = when (capability.risk) {
                            AgentPermissionRisk.High -> MaterialTheme.colorScheme.error
                            AgentPermissionRisk.Sensitive -> MaterialTheme.colorScheme.tertiary
                            AgentPermissionRisk.Normal -> MaterialTheme.colorScheme.outline
                        },
                    )
                }
                if (capability.toolNames.isNotEmpty()) {
                    Text(
                        text = "工具: ${capability.toolNames.joinToString()}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        trailingContent = action,
    )
}

@Composable
private fun PermissionAction(
    status: AgentPermissionStatus,
    onClick: () -> Unit,
) {
    when (status) {
        AgentPermissionStatus.Granted -> Text("已授权", color = MaterialTheme.colorScheme.primary)
        AgentPermissionStatus.Unsupported -> Text("不支持", color = MaterialTheme.colorScheme.outline)
        AgentPermissionStatus.Denied,
        AgentPermissionStatus.SpecialNeeded -> {
            OutlinedButton(onClick = onClick) {
                Text("授权")
            }
        }
    }
}

private fun AgentPermissionStatus.label(): String =
    when (this) {
        AgentPermissionStatus.Granted -> "已授权"
        AgentPermissionStatus.Denied -> "未授权"
        AgentPermissionStatus.SpecialNeeded -> "需系统设置"
        AgentPermissionStatus.Unsupported -> "当前构建不支持"
    }

private fun AgentPermissionRisk.label(): String =
    when (this) {
        AgentPermissionRisk.Normal -> "普通"
        AgentPermissionRisk.Sensitive -> "敏感"
        AgentPermissionRisk.High -> "高敏感"
    }
