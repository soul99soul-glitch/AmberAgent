package app.amber.feature.ui.pages.setting

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import app.amber.feature.ui.components.ui.Switch
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
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Settings
import app.amber.agent.R
import app.amber.core.localization.PermissionDisplayLocalizer
import app.amber.feature.system.AgentPermissionBroker
import app.amber.feature.system.AgentPermissionCapability
import app.amber.feature.system.AgentPermissionRisk
import app.amber.feature.system.AgentPermissionStatus
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.CardGroup
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.components.ui.CardGroupScope
import app.amber.feature.ui.theme.CustomColors
import app.amber.core.utils.plus
import org.koin.compose.koinInject

@Composable
fun SettingSystemAccessPage(
    permissionBroker: AgentPermissionBroker = koinInject(),
    settingsStore: SettingsAggregator = koinInject(),
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
            Toast.makeText(
                context,
                context.getString(R.string.setting_system_access_open_failed),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.setting_system_access_title),
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = workspaceColors().canvas,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("summary") {
                CardGroup(title = { Text(stringResource(R.string.setting_system_access_center)) }) {
                    item(
                        leadingContent = { Icon(Lucide.Settings, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.setting_system_access_core_runtime_title)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.setting_system_access_core_runtime_desc))
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
                                        Text(stringResource(R.string.setting_system_access_request_core), maxLines = 1)
                                    }
                                    OutlinedButton(
                                        onClick = { refreshToken++ },
                                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                                    ) {
                                        Text(stringResource(R.string.setting_system_access_refresh), maxLines = 1)
                                    }
                                }
                            }
                        },
                    )
                }
            }

            item("runtime") {
                CardGroup(title = { Text(stringResource(R.string.setting_system_access_runtime_section)) }) {
                    capabilities
                        .filter { (capability, _) -> capability.specialAccess == null }
                        .forEach { (capability, status) ->
                            permissionItem(
                                capability = capability,
                                status = status,
                                displayContext = context,
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
                CardGroup(title = { Text(stringResource(R.string.setting_system_access_special_section)) }) {
                    capabilities
                        .filter { (capability, _) -> capability.specialAccess != null }
                        .forEach { (capability, status) ->
                            permissionItem(
                                capability = capability,
                                status = status,
                                displayContext = context,
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
                CardGroup(title = { Text(stringResource(R.string.setting_system_access_external_section)) }) {
                    item(
                        leadingContent = { Icon(Lucide.FileText, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.setting_system_access_external_title)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.setting_system_access_external_desc))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = stringResource(
                                            if (externalAccess.enabled) {
                                                R.string.prompt_page_enabled
                                            } else {
                                                R.string.prompt_page_disabled
                                            },
                                        ),
                                        color = if (externalAccess.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    )
                                    Text(
                                        stringResource(
                                            R.string.setting_system_access_root_count,
                                            externalAccess.roots.size,
                                        )
                                    )
                                }
                                OutlinedTextField(
                                    value = externalRootInput,
                                    onValueChange = { externalRootInput = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.setting_system_access_path_label)) },
                                    supportingText = { Text(stringResource(R.string.setting_system_access_path_hint)) },
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
                                        Text(stringResource(R.string.setting_system_access_add_enable), maxLines = 1)
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
                                        Text(stringResource(R.string.clear), maxLines = 1)
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
    displayContext: android.content.Context,
    onClick: () -> Unit,
    action: @Composable () -> Unit,
) {
    item(
        onClick = onClick,
        leadingContent = {
            Icon(
                imageVector = if (capability.risk == AgentPermissionRisk.High) {
                    Lucide.TriangleAlert
                } else {
                    Lucide.Settings
                },
                contentDescription = null,
                tint = when (capability.risk) {
                    AgentPermissionRisk.High -> MaterialTheme.colorScheme.error
                    AgentPermissionRisk.Sensitive -> MaterialTheme.colorScheme.tertiary
                    AgentPermissionRisk.Normal -> MaterialTheme.colorScheme.primary
                },
            )
        },
        headlineContent = { Text(PermissionDisplayLocalizer.title(displayContext, capability)) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(PermissionDisplayLocalizer.description(displayContext, capability))
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
                        text = stringResource(
                            R.string.setting_system_access_tools,
                            capability.toolNames.joinToString(),
                        ),
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
        AgentPermissionStatus.Granted -> Text(
            stringResource(R.string.setting_system_access_status_granted),
            color = MaterialTheme.colorScheme.primary,
        )
        AgentPermissionStatus.Unsupported -> Text(
            stringResource(R.string.setting_system_access_status_unsupported),
            color = MaterialTheme.colorScheme.outline,
        )
        AgentPermissionStatus.Denied,
        AgentPermissionStatus.SpecialNeeded -> {
            OutlinedButton(onClick = onClick) {
                Text(stringResource(R.string.setting_system_access_authorize))
            }
        }
    }
}

@Composable
private fun AgentPermissionStatus.label(): String =
    when (this) {
        AgentPermissionStatus.Granted -> stringResource(R.string.setting_system_access_status_granted)
        AgentPermissionStatus.Denied -> stringResource(R.string.setting_system_access_status_denied)
        AgentPermissionStatus.SpecialNeeded -> stringResource(R.string.setting_system_access_status_special_needed)
        AgentPermissionStatus.Unsupported -> stringResource(R.string.setting_system_access_status_unsupported)
    }

@Composable
private fun AgentPermissionRisk.label(): String =
    when (this) {
        AgentPermissionRisk.Normal -> stringResource(R.string.setting_system_access_risk_normal)
        AgentPermissionRisk.Sensitive -> stringResource(R.string.setting_system_access_risk_sensitive)
        AgentPermissionRisk.High -> stringResource(R.string.setting_system_access_risk_high)
    }
