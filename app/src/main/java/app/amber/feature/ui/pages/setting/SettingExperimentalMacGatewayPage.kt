package app.amber.feature.ui.pages.setting

import android.app.KeyguardManager
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.LocalActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.feature.macgateway.GatewayEndpointRole
import app.amber.feature.macgateway.MacGatewayClient
import app.amber.feature.macgateway.MacGatewayException
import app.amber.feature.macgateway.MacGatewayPairingPayload
import app.amber.feature.macgateway.MacGatewayRepository
import app.amber.feature.macgateway.MacGatewaySessionOrdering
import app.amber.feature.macgateway.MacGatewayStatus
import app.amber.feature.ui.components.ui.Switch
import app.amber.feature.ui.components.ui.permission.PermissionManager
import app.amber.feature.ui.components.ui.permission.PermissionNotification
import app.amber.feature.ui.components.ui.permission.rememberPermissionState
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldAlert
import com.composables.icons.lucide.Sparkles
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private data class Notice(val text: String, val error: Boolean)

private const val REFRESH_INTERVAL_MS = 10_000L
private const val VISIBLE_TASK_LIMIT = 20

@Composable
fun SettingExperimentalMacGatewayPage(
    repository: MacGatewayRepository = koinInject(),
) {
    val cloud by repository.slot(GatewayEndpointRole.CLOUD).state.collectAsStateWithLifecycle()
    val mac by repository.slot(GatewayEndpointRole.MAC).state.collectAsStateWithLifecycle()
    val selectedHost by repository.selectedDialogueRole.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf<GatewayEndpointRole?>(null) }
    val initialTab = if (cloud.loaded && mac.loaded) {
        selectedHost ?: if (mac.connection != null && cloud.connection == null) GatewayEndpointRole.MAC else GatewayEndpointRole.CLOUD
    } else null
    LaunchedEffect(initialTab) {
        if (selectedTab == null && initialTab != null) selectedTab = initialTab
    }
    val tab = selectedTab ?: initialTab ?: GatewayEndpointRole.CLOUD
    val workspace = workspaceColors()
    ExperimentalSettingsScaffold(title = stringResource(R.string.setting_home_multi_device_title)) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            SingleChoiceSegmentedButtonRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                GatewayEndpointRole.entries.forEachIndexed { index, role ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index, GatewayEndpointRole.entries.size),
                        selected = tab == role,
                        onClick = { selectedTab = role },
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = workspace.row,
                            activeContentColor = workspace.ink,
                            activeBorderColor = workspace.hairline,
                            inactiveContainerColor = workspace.paper,
                            inactiveContentColor = workspace.muted,
                            inactiveBorderColor = workspace.hairline,
                        ),
                    ) { Text(stringResource(role.labelRes)) }
                }
            }
            Crossfade(targetState = tab, animationSpec = tween(160), label = "endpoint") { role ->
                GatewayEndpointSettings(role, repository.slot(role), active = tab == role)
            }
        }
    }
}

internal val GatewayEndpointRole.labelRes: Int
    get() = if (this == GatewayEndpointRole.CLOUD) R.string.personal_agent_host_cloud else R.string.amber_multi_device_mac

@Composable
private fun GatewayEndpointSettings(
    role: GatewayEndpointRole,
    repository: MacGatewayRepository,
    active: Boolean,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val context = LocalContext.current
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<MacGatewayPairingPayload?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var unpairGeneration by remember { mutableStateOf<Long?>(null) }

    val notificationPermission = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setOf(PermissionNotification) else emptySet(),
    )
    PermissionManager(permissionState = notificationPermission)

    fun perform(block: suspend () -> Unit) {
        if (!active || busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                notice = Notice(error.describe(context), error = true)
            } finally {
                busy = false
            }
        }
    }

    fun decideApproval(sessionKey: String, approval: MacGatewayStatus.PendingApproval, allow: Boolean, fromMacBoard: Boolean) {
        notice = null
        perform {
            var biometricCancelled = false
            try {
                repository.decide(
                    sessionKey = sessionKey,
                    toolCallId = approval.toolCallId,
                    allow = allow,
                    highRisk = approval.highRisk,
                    requiresLocalApproval = approval.effectiveRequiresLocalApproval,
                    riskLevel = approval.riskLevel,
                    expectedGeneration = state.generation,
                    fromMacBoard = fromMacBoard,
                    verifyForeground = activity?.let { host ->
                        {
                            val verified = host.verifyGatewayApproval(approval)
                            if (!verified) biometricCancelled = true
                            verified
                        }
                    },
                )
                notice = null
            } catch (forbidden: MacGatewayException.Forbidden) {
                if (!forbidden.requiresLocalApproval && biometricCancelled) {
                    notice = Notice(
                        context.getString(R.string.mac_gateway_approval_biometric_cancelled),
                        error = false,
                    )
                } else {
                    throw forbidden
                }
            }
        }
    }

    fun receiveLink(text: String) {
        val payload = MacGatewayPairingPayload.parse(text)
        pending = payload?.takeIf { it.role == null || it.role == role }
        notice = when {
            payload == null -> Notice(context.getString(R.string.mac_gateway_invalid_link), error = true)
            pending == null -> Notice(context.getString(R.string.amber_multi_device_wrong_role), error = true)
            else -> null
        }
    }

    fun enablePush() {
        if (!notificationPermission.allPermissionsGranted) notificationPermission.requestPermissions()
        perform { repository.uploadPushToken(state.generation) }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanQRCode()) { result ->
        when (result) {
            is QRResult.QRSuccess -> receiveLink(result.content.rawValue.orEmpty())
            QRResult.QRMissingPermission ->
                notice = Notice(context.getString(R.string.amber_multi_device_camera_permission), error = true)
            is QRResult.QRError ->
                notice = Notice(result.exception.message ?: context.getString(R.string.mac_gateway_invalid_link), error = true)
            QRResult.QRUserCanceled -> Unit
        }
    }

    val connection = state.connection
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(connection, state.generation, lifecycleOwner, active) {
        if (!active || connection == null) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                repository.refresh(state.generation)
                if (role == GatewayEndpointRole.MAC) repository.refreshMacTasks(state.generation)
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }
    val errorText = state.error?.describe(context)
    val shownNotice = notice ?: errorText?.let { Notice(it, error = true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        shownNotice?.let { item { ExperimentNote(text = it.text, error = it.error) } }
        if (!state.loaded) {
            item { ExperimentNote(stringResource(R.string.mac_gateway_connecting)) }
            return@LazyColumn
        }

        val payload = pending
        when {
            payload != null -> item {
                ExperimentSectionCard(title = stringResource(R.string.mac_gateway_section_confirm)) {
                    ExperimentStatusRow(stringResource(R.string.mac_gateway_name), payload.name)
                    StackedValueRow(stringResource(R.string.mac_gateway_addresses), payload.addrs.joinToString("\n") { "$it:${payload.port}" })
                    StackedValueRow(stringResource(R.string.mac_gateway_fingerprint), payload.fp, monospace = true)
                    ExperimentNote(stringResource(R.string.amber_multi_device_fingerprint_hint))
                    ExperimentActionRow {
                        ExperimentActionButton(stringResource(R.string.mac_gateway_pair), enabled = !busy, primary = true) {
                            perform {
                                repository.pair(payload)
                                pending = null
                                notice = null
                                if (!notificationPermission.allPermissionsGranted) notificationPermission.requestPermissions()
                            }
                        }
                        ExperimentActionButton(stringResource(R.string.mac_gateway_cancel), enabled = !busy) {
                            pending = null
                            notice = null
                        }
                    }
                }
            }

            connection == null -> item {
                ExperimentSectionCard(title = stringResource(R.string.mac_gateway_section_pair)) {
                    ExperimentNote(stringResource(
                        if (role == GatewayEndpointRole.MAC) R.string.mac_gateway_pair_hint
                        else R.string.amber_multi_device_cloud_pair_hint,
                    ))
                    ExperimentActionRow {
                        ExperimentActionButton(stringResource(R.string.mac_gateway_scan), enabled = !busy, primary = true) {
                            scanLauncher.launch(null)
                        }
                        ExperimentActionButton(stringResource(R.string.mac_gateway_paste), enabled = !busy) {
                            receiveLink(context.clipboardText())
                        }
                    }
                }
            }

            else -> {
                val status = state.status
                val pushRegistered = status?.device?.pushRegistered == true
                val pushOn = pushRegistered && notificationPermission.allPermissionsGranted
                item {
                    ExperimentSectionCard(title = stringResource(role.labelRes)) {
                        ExperimentStatusRow(stringResource(R.string.mac_gateway_name), connection.name)
                        ExperimentStatusRow(stringResource(R.string.mac_gateway_connection), reachabilityText(state))
                        ExperimentStatusRow(
                            stringResource(R.string.mac_gateway_notifications),
                            if (state.reachable != true || status == null) "—" else stringResource(
                                when {
                                    pushOn -> R.string.mac_gateway_push_on
                                    pushRegistered -> R.string.mac_gateway_push_blocked
                                    else -> R.string.mac_gateway_push_off
                                },
                            ),
                        )
                        StackedValueRow(
                            stringResource(R.string.mac_gateway_addresses),
                            connection.addrs.joinToString("\n") { "$it:${connection.port}" },
                        )
                        StackedValueRow(stringResource(R.string.mac_gateway_fingerprint), connection.fingerprint, monospace = true)
                        ExperimentActionRow {
                            ExperimentActionButton(stringResource(R.string.mac_gateway_refresh), enabled = !busy) {
                                perform {
                                    notice = null
                                    repository.refresh(state.generation)
                                    if (role == GatewayEndpointRole.MAC) repository.refreshMacTasks(state.generation)
                                }
                            }
                            if (pushOn && state.reachable == true) {
                                ExperimentActionButton(stringResource(R.string.mac_gateway_test_push), enabled = !busy) {
                                    perform {
                                        repository.testPush(state.generation)
                                        notice = Notice(context.getString(R.string.mac_gateway_test_push_sent), error = false)
                                    }
                                }
                            } else if (state.reachable == true) {
                                ExperimentActionButton(stringResource(R.string.mac_gateway_enable_push), enabled = !busy, primary = true) {
                                    enablePush()
                                }
                            }
                            ExperimentActionButton(stringResource(R.string.mac_gateway_unpair), enabled = !busy, danger = true) {
                                unpairGeneration = state.generation
                            }
                        }
                    }
                }
                item {
                    CloudDialogueSection(
                        enabled = state.cloudDialogueEnabled,
                        paired = true,
                        role = role,
                        busy = busy,
                        supported = state.cloudDialogueSupported != false,
                        onEnabledChange = { checked ->
                            perform {
                                notice = null
                                if (!repository.setCloudDialogueEnabled(checked, state.generation)) {
                                    notice = Notice(context.getString(R.string.amber_multi_device_dialogue_unavailable), error = true)
                                }
                            }
                        },
                        onOpenProactiveCenter = if (role == GatewayEndpointRole.CLOUD) {
                            { navController.navigate(Screen.CloudProactiveCenter) }
                        } else null,
                    )
                }
                if (role == GatewayEndpointRole.CLOUD && active) {
                    item { CloudEndpointDetail(repository, state, MacGatewayClient::requestCloudDetail) }
                }
                if (state.reachable == true) {
                    status?.health?.let { health ->
                        val hasMacMetrics = role == GatewayEndpointRole.MAC &&
                            listOf(health.batteryPercent, health.memoryPressure, health.thermal).any { it != null }
                        if (health.diskFreeBytes != null || hasMacMetrics) {
                            item { HealthSection(health, role) }
                        }
                    }
                }
                if (role == GatewayEndpointRole.MAC) {
                    item {
                        MacRemoteTasksSettings(
                            repository = repository,
                            state = state,
                            active = active,
                            approvalBusy = busy,
                            approvalError = notice?.takeIf { it.error }?.text,
                            onDecision = { approval, allow ->
                                decideApproval(approval.sessionKey, approval.asPendingApproval(), allow, fromMacBoard = true)
                            },
                        )
                    }
                }
                if (state.reachable == true && (role == GatewayEndpointRole.MAC || !status?.sessions.isNullOrEmpty())) item {
                    TasksSection(
                        sessions = MacGatewaySessionOrdering.visible(status?.sessions.orEmpty(), VISIBLE_TASK_LIMIT),
                        busy = busy,
                        onMonitoredChange = { session, monitored ->
                            perform { repository.setMonitored(session.key, monitored, state.generation) }
                        },
                        onDecision = { session, approval, allow ->
                            decideApproval(session.key, approval, allow, fromMacBoard = false)
                        },
                    )
                }
            }
        }
    }

    unpairGeneration?.let { generation ->
        AlertDialog(
            onDismissRequest = { unpairGeneration = null },
            title = { Text(stringResource(R.string.amber_multi_device_unpair_title, stringResource(role.labelRes))) },
            text = { Text(stringResource(R.string.amber_multi_device_unpair_message)) },
            confirmButton = {
                TextButton(onClick = {
                    unpairGeneration = null
                    perform {
                        val acknowledged = repository.unpair(generation)
                        notice = if (acknowledged) null else Notice(context.getString(R.string.amber_multi_device_unpaired_offline), error = false)
                    }
                }) {
                    Text(stringResource(R.string.mac_gateway_unpair), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { unpairGeneration = null }) { Text(stringResource(R.string.mac_gateway_keep)) }
            },
        )
    }
}

@Composable
internal fun CloudDialogueSection(
    enabled: Boolean,
    paired: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onOpenProactiveCenter: (() -> Unit)? = null,
    supported: Boolean = true,
    role: GatewayEndpointRole = GatewayEndpointRole.CLOUD,
    busy: Boolean = false,
) {
    val workspace = workspaceColors()
    ExperimentSectionCard(title = stringResource(R.string.amber_multi_device_dialogue_section)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = stringResource(R.string.amber_multi_device_dialogue_select, stringResource(role.labelRes)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = workspace.ink,
                )
                Text(
                    text = stringResource(
                        when {
                            !paired -> R.string.mac_gateway_cloud_dialogue_unpaired_hint
                            !supported -> R.string.amber_multi_device_dialogue_unavailable
                            else -> R.string.amber_multi_device_dialogue_hint
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.muted,
                )
            }
            Switch(
                checked = enabled && paired && supported,
                enabled = paired && supported && !busy,
                onCheckedChange = onEnabledChange,
            )
        }
        if (onOpenProactiveCenter != null) {
            ExperimentDivider(startPadding = 0.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onOpenProactiveCenter)
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Lucide.Sparkles,
                    contentDescription = null,
                    tint = workspace.muted,
                    modifier = Modifier.size(18.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.proactive_center_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = workspace.ink,
                    )
                    Text(
                        text = stringResource(
                            if (paired) R.string.proactive_center_entry_desc
                            else R.string.proactive_center_entry_unpaired_desc,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = workspace.muted,
                    )
                }
                Icon(
                    imageVector = Lucide.ChevronRight,
                    contentDescription = null,
                    tint = workspace.faint,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun HealthSection(health: MacGatewayStatus.Health, role: GatewayEndpointRole) {
    val context = LocalContext.current
    ExperimentSectionCard(title = stringResource(
        if (role == GatewayEndpointRole.MAC) R.string.mac_gateway_section_health else R.string.amber_multi_device_host_health,
    )) {
        health.diskFreeBytes?.let {
            ExperimentStatusRow(stringResource(R.string.mac_gateway_disk_free), Formatter.formatShortFileSize(context, it))
        }
        if (role == GatewayEndpointRole.MAC) {
            health.batteryPercent?.let {
                ExperimentStatusRow(
                    stringResource(R.string.mac_gateway_battery),
                    if (health.onBattery == false) stringResource(R.string.mac_gateway_battery_value_charging, it)
                    else stringResource(R.string.mac_gateway_battery_value, it),
                )
            }
            health.memoryPressure?.let { ExperimentStatusRow(stringResource(R.string.mac_gateway_memory), levelLabel(it)) }
            health.thermal?.let { ExperimentStatusRow(stringResource(R.string.mac_gateway_thermal), levelLabel(it)) }
        }
    }
}

@Composable
internal fun TasksSection(
    sessions: List<MacGatewayStatus.Session>,
    busy: Boolean = false,
    onMonitoredChange: (MacGatewayStatus.Session, Boolean) -> Unit,
    onDecision: (MacGatewayStatus.Session, MacGatewayStatus.PendingApproval, Boolean) -> Unit = { _, _, _ -> },
) {
    val workspace = workspaceColors()
    ExperimentSectionCard(title = stringResource(R.string.mac_gateway_section_tasks)) {
        if (sessions.isEmpty()) {
            ExperimentNote(stringResource(R.string.mac_gateway_no_tasks))
            return@ExperimentSectionCard
        }
        sessions.forEach { session ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = session.subject,
                            style = MaterialTheme.typography.bodyLarge,
                            color = workspace.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = taskDetail(session),
                            style = MaterialTheme.typography.bodySmall,
                            color = workspace.muted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        session.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                            Text(
                                text = summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = workspace.faint,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Switch(checked = session.monitored, enabled = !busy, onCheckedChange = { onMonitoredChange(session, it) })
                }
                session.pendingApproval?.let { approval ->
                    PendingApprovalCard(
                        approval = approval,
                        busy = busy,
                        onDecision = { allow -> onDecision(session, approval, allow) },
                    )
                }
            }
        }
        ExperimentNote(stringResource(R.string.mac_gateway_tasks_hint))
    }
}

@Composable
internal fun PendingApprovalCard(
    approval: MacGatewayStatus.PendingApproval,
    busy: Boolean,
    onDecision: (Boolean) -> Unit,
) {
    val workspace = workspaceColors()
    val scheme = MaterialTheme.colorScheme
    val localApprovalOnly = approval.effectiveRequiresLocalApproval
    val isL2Biometric = !localApprovalOnly && approval.riskLevel.equals("L2", ignoreCase = true)
    val riskTag = approval.riskLevel?.takeIf { it.isNotBlank() }
        ?: if (localApprovalOnly) "L3" else null
    val deviceAndTool = listOfNotNull(
        approval.targetDevice?.takeIf { it.isNotBlank() },
        approval.tool.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    val detailSummary = approval.targetSummary?.takeIf { it.isNotBlank() }
        ?: approval.detail?.takeIf { it.isNotBlank() }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (localApprovalOnly) scheme.errorContainer.copy(alpha = 0.45f) else workspace.row,
        contentColor = workspace.ink,
        border = BorderStroke(
            width = 1.dp,
            color = if (localApprovalOnly) scheme.error.copy(alpha = 0.45f) else workspace.hairline,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (localApprovalOnly) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = scheme.errorContainer,
                    contentColor = scheme.onErrorContainer,
                    border = BorderStroke(1.dp, scheme.error.copy(alpha = 0.4f)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                            text = stringResource(R.string.mac_gateway_approval_high_risk_hint),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (riskTag != null) {
                    val (badgeBg, badgeFg) = when {
                        localApprovalOnly -> scheme.errorContainer to scheme.onErrorContainer
                        isL2Biometric -> scheme.primaryContainer to scheme.onPrimaryContainer
                        else -> workspace.paper to workspace.muted
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeBg,
                        contentColor = badgeFg,
                    ) {
                        Text(
                            text = riskTag,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = badgeFg,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                if (deviceAndTool.isNotBlank()) {
                    Text(
                        text = deviceAndTool,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = workspace.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (detailSummary != null) {
                Text(
                    text = detailSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isL2Biometric) {
                Text(
                    text = stringResource(R.string.mac_gateway_approval_biometric_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = workspace.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            approval.diffPreview?.takeIf { it.isNotBlank() }?.let { diff ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.mac_gateway_approval_diff_preview),
                        style = MaterialTheme.typography.labelSmall,
                        color = workspace.muted,
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = workspace.paper,
                        border = BorderStroke(1.dp, workspace.hairline),
                    ) {
                        Text(
                            text = diff,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = workspace.ink,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            ExperimentActionRow {
                ExperimentActionButton(
                    text = stringResource(R.string.mac_gateway_approval_deny),
                    enabled = !busy,
                    primary = false,
                    danger = localApprovalOnly,
                ) {
                    onDecision(false)
                }
                if (!localApprovalOnly) {
                    ExperimentActionButton(
                        text = stringResource(R.string.mac_gateway_approval_approve),
                        enabled = !busy,
                        primary = true,
                    ) {
                        onDecision(true)
                    }
                }
            }
        }
    }
}

@Composable
private fun reachabilityText(state: MacGatewayRepository.State): String = when (state.reachable) {
    null -> stringResource(R.string.mac_gateway_connecting)
    false -> stringResource(R.string.mac_gateway_offline)
    true -> state.status?.health?.sampledAt
        ?.let { stringResource(R.string.mac_gateway_online_heartbeat, relativeTime(it)) }
        ?: stringResource(R.string.mac_gateway_online)
}

@Composable
private fun taskDetail(session: MacGatewayStatus.Session): String {
    val agent = when (session.agent) {
        "claude" -> "Claude Code"
        "codex" -> "Codex"
        else -> stringResource(R.string.mac_gateway_agent_task)
    }
    val state = stringResource(
        when (session.state) {
            "running" -> R.string.mac_gateway_state_running
            "waiting" -> if (session.waitReason == "permission") R.string.mac_gateway_state_waiting_permission else R.string.mac_gateway_state_waiting
            "stalled" -> R.string.mac_gateway_state_stalled
            "completed" -> R.string.mac_gateway_state_completed
            else -> if (session.abnormal) R.string.mac_gateway_state_abnormal else R.string.mac_gateway_state_stopped
        },
    )
    return listOfNotNull(agent, session.host, state, relativeTime(session.updatedAt)).joinToString(" · ")
}

@Composable
private fun levelLabel(level: String): String = stringResource(
    when (level) {
        "normal", "nominal" -> R.string.mac_gateway_level_normal
        "warning", "fair" -> R.string.mac_gateway_level_elevated
        "serious" -> R.string.mac_gateway_level_high
        else -> R.string.mac_gateway_level_critical
    },
)

/** Label above an unclipped value: the fingerprint must be readable in full to compare with the terminal. */
@Composable
private fun StackedValueRow(label: String, value: String, monospace: Boolean = false) {
    val workspace = workspaceColors()
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = workspace.faint)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = workspace.muted,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }
}

@Composable
private fun relativeTime(secondsSince1970: Double): String {
    val now = System.currentTimeMillis()
    // Heartbeats land every 60 s and clocks drift slightly: avoid "0 minutes ago" and "in 1 minute".
    val then = minOf((secondsSince1970 * 1000).toLong(), now)
    if (now - then < DateUtils.MINUTE_IN_MILLIS) return stringResource(R.string.workspace_time_just_now)
    return DateUtils.getRelativeTimeSpanString(then, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

private suspend fun FragmentActivity.verifyGatewayApproval(approval: MacGatewayStatus.PendingApproval): Boolean {
    val keyguard = getSystemService(KeyguardManager::class.java)
    fun isUnlockedForeground() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        keyguard != null && !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked
    if (!isUnlockedForeground()) return false
    val subtitle = listOfNotNull(
        approval.riskLevel?.takeIf { it.isNotBlank() },
        approval.tool.takeIf { it.isNotBlank() }?.take(48),
    ).joinToString(" · ").ifBlank { getString(R.string.mac_gateway_approval_biometric_hint) }
    return suspendCancellableCoroutine { continuation ->
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (continuation.isActive) continuation.resume(isUnlockedForeground())
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (!continuation.isActive) return
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_CANCELED) continuation.resume(false)
                else continuation.resumeWithException(IllegalStateException(errString.toString()))
            }
        })
        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.mac_gateway_approval_approve))
            .setSubtitle(subtitle)
            .setNegativeButtonText(getString(R.string.cancel))
            .build())
    }
}

private fun Context.clipboardText(): String =
    getSystemService(ClipboardManager::class.java)?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()

internal fun Throwable.describe(context: Context): String = when (this) {
    is MacGatewayException.Unreachable -> context.getString(R.string.amber_multi_device_unreachable)
    is MacGatewayException.CertificateMismatch -> context.getString(R.string.amber_multi_device_certificate)
    is MacGatewayException.Unauthorized -> context.getString(R.string.amber_multi_device_unauthorized)
    is MacGatewayException.Conflict -> when (code) {
        "wrong_endpoint_role" -> context.getString(R.string.amber_multi_device_wrong_role)
        "gateway_in_other_slot" -> context.getString(R.string.amber_multi_device_already_paired)
        "gateway_pairing_changed" -> context.getString(R.string.amber_multi_device_pairing_changed)
        "schedule_changed", "gateway_approval_changed" -> context.getString(R.string.mac_gateway_task_changed)
        else -> code
    }
    is MacGatewayException.Forbidden -> if (requiresLocalApproval) {
        context.getString(R.string.mac_gateway_approval_high_risk_hint)
    } else {
        code
    }
    is MacGatewayException.InvalidResponse -> context.getString(R.string.mac_gateway_error_invalid_response)
    is MacGatewayException.MutationUnconfirmed -> context.getString(R.string.mac_gateway_schedule_unconfirmed)
    is MacGatewayException.Server -> message.orEmpty()
    else -> context.getString(R.string.mac_gateway_error_generic, message ?: javaClass.simpleName)
}
