package app.amber.feature.ui.pages.setting

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import app.amber.core.utils.plus
import app.amber.feature.macgateway.MacGatewayException
import app.amber.feature.macgateway.MacGatewayPairingPayload
import app.amber.feature.macgateway.MacGatewayRepository
import app.amber.feature.macgateway.MacGatewayStatus
import app.amber.feature.ui.components.ui.Switch
import app.amber.feature.ui.components.ui.permission.PermissionManager
import app.amber.feature.ui.components.ui.permission.PermissionNotification
import app.amber.feature.ui.components.ui.permission.rememberPermissionState
import app.amber.feature.ui.components.ui.workspaceColors
import com.composables.icons.lucide.Laptop
import com.composables.icons.lucide.Lucide
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
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
    val state by repository.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<MacGatewayPairingPayload?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var confirmsUnpair by remember { mutableStateOf(false) }

    val notificationPermission = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setOf(PermissionNotification) else emptySet(),
    )
    PermissionManager(permissionState = notificationPermission)

    fun perform(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                block()
            } catch (error: Exception) {
                notice = Notice(error.describe(context), error = true)
            } finally {
                busy = false
            }
        }
    }

    fun receiveLink(text: String) {
        val payload = MacGatewayPairingPayload.parse(text)
        pending = payload
        notice = if (payload == null) Notice(context.getString(R.string.mac_gateway_invalid_link), error = true) else null
    }

    fun enablePush() {
        if (!notificationPermission.allPermissionsGranted) notificationPermission.requestPermissions()
        perform { repository.uploadPushToken() }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanQRCode()) { result ->
        when (result) {
            is QRResult.QRSuccess -> receiveLink(result.content.rawValue.orEmpty())
            QRResult.QRMissingPermission ->
                notice = Notice(context.getString(R.string.synara_camera_permission_required), error = true)
            is QRResult.QRError ->
                notice = Notice(result.exception.message ?: context.getString(R.string.mac_gateway_invalid_link), error = true)
            QRResult.QRUserCanceled -> Unit
        }
    }

    val connection = state.connection
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(connection?.gatewayId, lifecycleOwner) {
        if (connection == null) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                repository.refresh()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }
    val errorText = state.error?.takeIf { it !is MacGatewayException.Unreachable }?.describe(context)
    val shownNotice = notice ?: errorText?.let { Notice(it, error = true) }

    ExperimentalSettingsScaffold(title = "Mac Gateway") { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ExperimentHeroCard(
                    icon = { Icon(Lucide.Laptop, contentDescription = null) },
                    title = "Mac Gateway",
                    description = stringResource(R.string.mac_gateway_desc),
                    trailing = {},
                )
            }
            shownNotice?.let { item { ExperimentNote(text = it.text, error = it.error) } }
            if (!state.loaded) return@LazyColumn

            val payload = pending
            when {
                payload != null -> item {
                    ExperimentSectionCard(title = stringResource(R.string.mac_gateway_section_confirm)) {
                        ExperimentStatusRow(stringResource(R.string.mac_gateway_name), payload.name)
                        StackedValueRow(stringResource(R.string.mac_gateway_addresses), payload.addrs.joinToString("\n"))
                        StackedValueRow(stringResource(R.string.mac_gateway_fingerprint), payload.fp, monospace = true)
                        ExperimentNote(stringResource(R.string.mac_gateway_fingerprint_hint))
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
                        ExperimentNote(stringResource(R.string.mac_gateway_pair_hint))
                        ExperimentActionRow {
                            ExperimentActionButton(stringResource(R.string.mac_gateway_scan), enabled = true, primary = true) {
                                scanLauncher.launch(null)
                            }
                            ExperimentActionButton(stringResource(R.string.mac_gateway_paste), enabled = true) {
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
                        ExperimentSectionCard(title = stringResource(R.string.mac_gateway_section_mac)) {
                            ExperimentStatusRow(stringResource(R.string.mac_gateway_name), connection.name)
                            ExperimentStatusRow(stringResource(R.string.mac_gateway_connection), reachabilityText(state))
                            ExperimentStatusRow(
                                stringResource(R.string.mac_gateway_notifications),
                                // Without a status (Mac asleep) the push state is unknown, not "off".
                                if (status == null) "—" else stringResource(
                                    when {
                                        pushOn -> R.string.mac_gateway_push_on
                                        pushRegistered -> R.string.mac_gateway_push_blocked
                                        else -> R.string.mac_gateway_push_off
                                    },
                                ),
                            )
                            if (state.reachable == false) ExperimentNote(stringResource(R.string.mac_gateway_offline_hint))
                            ExperimentActionRow {
                                ExperimentActionButton(stringResource(R.string.mac_gateway_refresh), enabled = !busy) {
                                    perform { repository.refresh() }
                                }
                                if (pushOn) {
                                    ExperimentActionButton(stringResource(R.string.mac_gateway_test_push), enabled = !busy) {
                                        perform {
                                            repository.testPush()
                                            notice = Notice(context.getString(R.string.mac_gateway_test_push_sent), error = false)
                                        }
                                    }
                                } else if (status != null) {
                                    ExperimentActionButton(stringResource(R.string.mac_gateway_enable_push), enabled = !busy, primary = true) {
                                        enablePush()
                                    }
                                }
                                ExperimentActionButton(stringResource(R.string.mac_gateway_unpair), enabled = !busy) {
                                    confirmsUnpair = true
                                }
                            }
                        }
                    }
                    status?.health?.let { health -> item { HealthSection(health) } }
                    item {
                        TasksSection(status?.sessions.orEmpty().take(VISIBLE_TASK_LIMIT), status?.synaraURL) { session, monitored ->
                            scope.launch {
                                runCatching { repository.setMonitored(session.key, monitored) }
                                    .onFailure { notice = Notice(it.describe(context), error = true) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmsUnpair) {
        AlertDialog(
            onDismissRequest = { confirmsUnpair = false },
            title = { Text(stringResource(R.string.mac_gateway_unpair_title)) },
            text = { Text(stringResource(R.string.mac_gateway_unpair_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmsUnpair = false
                    perform {
                        val acknowledged = repository.unpair()
                        notice = if (acknowledged) null else Notice(context.getString(R.string.mac_gateway_unpaired_offline), error = false)
                    }
                }) {
                    Text(stringResource(R.string.mac_gateway_unpair), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmsUnpair = false }) { Text(stringResource(R.string.mac_gateway_keep)) }
            },
        )
    }
}

@Composable
private fun HealthSection(health: MacGatewayStatus.Health) {
    val context = LocalContext.current
    ExperimentSectionCard(title = stringResource(R.string.mac_gateway_section_health)) {
        health.diskFreeBytes?.let {
            ExperimentStatusRow(stringResource(R.string.mac_gateway_disk_free), Formatter.formatShortFileSize(context, it))
        }
        health.batteryPercent?.let {
            ExperimentStatusRow(
                stringResource(R.string.mac_gateway_battery),
                if (health.onBattery == true) stringResource(R.string.mac_gateway_battery_value, it)
                else stringResource(R.string.mac_gateway_battery_value_charging, it),
            )
        }
        ExperimentStatusRow(stringResource(R.string.mac_gateway_memory), levelLabel(health.memoryPressure))
        ExperimentStatusRow(stringResource(R.string.mac_gateway_thermal), levelLabel(health.thermal))
    }
}

@Composable
private fun TasksSection(
    sessions: List<MacGatewayStatus.Session>,
    synaraURL: String?,
    onMonitoredChange: (MacGatewayStatus.Session, Boolean) -> Unit,
) {
    val workspace = workspaceColors()
    val context = LocalContext.current
    ExperimentSectionCard(title = stringResource(R.string.mac_gateway_section_tasks)) {
        if (synaraURL != null) {
            ExperimentActionRow {
                ExperimentActionButton(stringResource(R.string.mac_gateway_open_synara), enabled = true, primary = true) {
                    // Straight to the browser: the app's openUrl helper logs the URL, which carries the Synara gate secret.
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(synaraURL))) }
                }
            }
        }
        if (sessions.isEmpty()) {
            ExperimentNote(stringResource(R.string.mac_gateway_no_tasks))
            return@ExperimentSectionCard
        }
        sessions.forEach { session ->
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
                }
                Switch(checked = session.monitored, onCheckedChange = { onMonitoredChange(session, it) })
            }
        }
        ExperimentNote(stringResource(R.string.mac_gateway_tasks_hint))
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

private fun Context.clipboardText(): String =
    getSystemService(ClipboardManager::class.java)?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()

private fun Throwable.describe(context: Context): String = when (this) {
    is MacGatewayException.Unreachable -> context.getString(R.string.mac_gateway_error_unreachable)
    is MacGatewayException.CertificateMismatch -> context.getString(R.string.mac_gateway_error_certificate)
    is MacGatewayException.Unauthorized -> context.getString(R.string.mac_gateway_error_unauthorized)
    is MacGatewayException.InvalidResponse -> context.getString(R.string.mac_gateway_error_invalid_response)
    is MacGatewayException.Server -> message.orEmpty()
    else -> context.getString(R.string.mac_gateway_error_generic, message ?: javaClass.simpleName)
}
