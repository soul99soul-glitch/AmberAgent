package app.amber.feature.ui.pages.phoneuse

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.BuildConfig
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.core.automation.AmberAccessibilityService
import app.amber.core.automation.display.AmberDisplayUserService
import app.amber.core.automation.display.EmbeddedAdbDisplayBridge
import app.amber.core.automation.display.IsolationProbeMainActivity
import app.amber.core.automation.display.PHONE_USE_VIRTUAL_DISPLAY_ENABLED_PREF
import app.amber.core.automation.display.ShizukuDisplayBridge
import app.amber.core.automation.display.VirtualDisplayCompatibility
import app.amber.core.automation.display.VirtualDisplayProbeStore
import app.amber.core.automation.display.VirtualDisplayRunRegistry
import app.amber.core.automation.display.adb.LocalAdbKeyStore
import app.amber.core.automation.display.adb.LocalAdbTlsClient
import app.amber.core.automation.display.adb.WirelessAdbPairingNotification
import app.amber.core.utils.plus
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.Switch
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.hooks.rememberSharedPreferenceBoolean
import app.amber.feature.ui.pages.setting.SettingCardGroup
import app.amber.feature.ui.pages.setting.SettingPageHorizontalInset
import app.amber.feature.ui.pages.setting.settingTwoLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import org.koin.compose.koinInject

private const val SHIZUKU_PERMISSION_REQUEST_CODE = 49_201

@Composable
fun SettingPhoneUseDisplayPage() {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val probeStore = koinInject<VirtualDisplayProbeStore>()
    val shizukuBridge = koinInject<ShizukuDisplayBridge>()
    val activeRuns by VirtualDisplayRunRegistry.activeRuns.collectAsStateWithLifecycle()

    var refreshTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refreshTick++
    }

    val compatibility = remember(refreshTick) { probeStore.compatibility() }
    val lastProbeResult = remember(refreshTick) { probeStore.lastResult() }
    val activeService by AmberAccessibilityService.connectedService.collectAsStateWithLifecycle()
    val accessibilityReady = activeService != null
    val lastDetail = lastProbeResult?.let {
        val message = probeDetailMessage(it.detail, it.passed)
        if (message != null) stringResource(message) else it.detail
    }
    val shizukuRunning = remember(refreshTick) { shizukuBridge.isShizukuRunning() }
    val shizukuAuthorized = remember(refreshTick) { shizukuBridge.hasShizukuPermission() }
    var virtualDisplayEnabled by rememberSharedPreferenceBoolean(
        keyForBoolean = PHONE_USE_VIRTUAL_DISPLAY_ENABLED_PREF,
        defaultValue = false,
    )

    val canEnableVirtualDisplay = BuildConfig.PHONE_USE_VIRTUAL_DISPLAY_ALLOWED &&
        compatibility == VirtualDisplayCompatibility.VERIFIED

    val unsupportedReason = remember(refreshTick) {
        when {
            !BuildConfig.PHONE_USE_VIRTUAL_DISPLAY_ALLOWED ->
                context.getString(R.string.setting_phone_use_vd_reason_build_disabled)
            Build.VERSION.SDK_INT < AmberDisplayUserService.MIN_API_LEVEL ->
                context.getString(R.string.setting_phone_use_vd_reason_api_below_34, Build.VERSION.SDK_INT)
            !runCatching {
                context.packageManager.hasSystemFeature(
                    PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS,
                )
            }.getOrDefault(false) ->
                context.getString(R.string.setting_phone_use_vd_reason_no_secondary_activities)
            else -> ""
        }
    }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.setting_phone_use_vd_title),
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .amberCanvas(),
        containerColor = Color.Transparent,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = SettingPageHorizontalInset, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (activeRuns.isNotEmpty()) {
                item("active_run") {
                    SettingCardGroup(title = stringResource(R.string.setting_phone_use_vd_active_run_section)) {
                        activeRuns.values.forEach { activeRun ->
                            item(
                                modifier = Modifier.settingTwoLine(),
                                onClick = { navController.navigate(Screen.PhoneUseLive(activeRun.runId)) },
                                headlineContent = {
                                    Text(
                                        stringResource(
                                            R.string.setting_phone_use_vd_active_run_open,
                                            activeRun.packageName,
                                        ),
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        activeRun.goal,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = workspaceColors().muted,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            item("device_status") {
                SettingCardGroup(title = stringResource(R.string.setting_phone_use_vd_status_section)) {
                    val (statusTitle, statusDesc, isError) = when (compatibility) {
                        VirtualDisplayCompatibility.VERIFIED -> Triple(
                            stringResource(R.string.setting_phone_use_vd_status_verified),
                            stringResource(R.string.setting_phone_use_vd_status_verified_desc),
                            false,
                        )
                        VirtualDisplayCompatibility.UNVERIFIED -> Triple(
                            stringResource(R.string.setting_phone_use_vd_status_unverified),
                            stringResource(R.string.setting_phone_use_vd_status_unverified_desc),
                            false,
                        )
                        VirtualDisplayCompatibility.FAILED -> Triple(
                            stringResource(R.string.setting_phone_use_vd_status_failed),
                            stringResource(
                                R.string.setting_phone_use_vd_status_failed_desc,
                                lastDetail ?: stringResource(R.string.setting_phone_use_vd_status_failed),
                            ),
                            true,
                        )
                        VirtualDisplayCompatibility.UNSUPPORTED -> Triple(
                            stringResource(R.string.setting_phone_use_vd_status_unsupported),
                            stringResource(
                                R.string.setting_phone_use_vd_status_unsupported_desc,
                                unsupportedReason,
                            ),
                            true,
                        )
                    }
                    item(
                        modifier = Modifier.settingTwoLine(),
                        headlineContent = {
                            Text(
                                text = statusTitle,
                                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = statusDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = workspaceColors().muted,
                            )
                        },
                    )
                }
            }

            if (unsupportedReason.isEmpty()) {
                item("wireless_debugging") {
                    WirelessAdbSettingsCard(
                        refreshTick = refreshTick,
                        onRefresh = { refreshTick++ },
                        shizukuRunning = shizukuRunning,
                        shizukuAuthorized = shizukuAuthorized,
                        requestShizuku = { shizukuBridge.requestShizukuPermission(SHIZUKU_PERMISSION_REQUEST_CODE) },
                    )
                }
                item("accessibility") {
                    SettingCardGroup(title = stringResource(R.string.permission_display_accessibility_service_title)) {
                        item(
                            headlineContent = { Text(stringResource(if (accessibilityReady) R.string.vd_access_on else R.string.vd_access_off)) },
                            supportingContent = { Text(stringResource(R.string.vd_access_purpose)) },
                            trailingContent = {
                                if (!accessibilityReady) TextButton(onClick = {
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }) { Text(stringResource(R.string.vd_access_settings)) }
                            },
                        )
                    }
                }
            }

            item("isolation_probe") {
                SettingCardGroup(title = stringResource(R.string.setting_phone_use_vd_probe_section)) {
                    item(
                        modifier = Modifier.settingTwoLine(),
                        headlineContent = { Text(stringResource(R.string.setting_phone_use_vd_probe_title)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = stringResource(if (!accessibilityReady && unsupportedReason.isEmpty()) R.string.vd_probe_need_access else R.string.setting_phone_use_vd_probe_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = workspaceColors().muted,
                                )
                                if (lastDetail != null) {
                                    Text(
                                        text = stringResource(
                                            R.string.setting_phone_use_vd_probe_last_result,
                                            lastDetail,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = workspaceColors().muted,
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            TextButton(
                                modifier = Modifier.heightIn(min = 48.dp),
                                enabled = accessibilityReady && compatibility != VirtualDisplayCompatibility.UNSUPPORTED,
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(context, IsolationProbeMainActivity::class.java),
                                        )
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.setting_phone_use_vd_probe_run_btn))
                            }
                        },
                    )
                }
            }

            item("toggle") {
                SettingCardGroup(title = stringResource(R.string.setting_phone_use_vd_toggle_section)) {
                    item(
                        modifier = Modifier.settingTwoLine(),
                        headlineContent = { Text(stringResource(R.string.setting_phone_use_vd_toggle_title)) },
                        supportingContent = {
                            Text(
                                text = if (canEnableVirtualDisplay) {
                                    stringResource(R.string.setting_phone_use_vd_toggle_desc_ready)
                                } else {
                                    stringResource(R.string.setting_phone_use_vd_toggle_desc_locked)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = workspaceColors().muted,
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = virtualDisplayEnabled && canEnableVirtualDisplay,
                                enabled = canEnableVirtualDisplay || virtualDisplayEnabled,
                                onCheckedChange = { checked ->
                                    if (!checked || canEnableVirtualDisplay) {
                                        virtualDisplayEnabled = checked
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

internal fun probeDetailMessage(detail: String, passed: Boolean): Int? {
    if (detail.startsWith("window_focus_lost_at_step_")) return R.string.vd_probe_window_focus_lost
    if (detail.startsWith("edittext_focus_lost_at_step_") || detail.startsWith("initial_no_focus")) return R.string.vd_probe_input_focus_lost
    if (detail.startsWith("target_did_not_resume_on_virtual_display(")) return R.string.vd_probe_target_missing
    val reason = detail.removePrefix("open_virtual_display_failed:")
        .removePrefix("display_bridge_unavailable:")
        .removePrefix("wireless_adb: ")
        .substringBefore(", shizuku: ")
    return when (reason) {
        "accessibility_service_unavailable" -> R.string.vd_probe_access_unavailable
        "device_locked_or_screen_off" -> R.string.vd_probe_locked
        "adb_not_paired" -> R.string.vd_probe_need_pair
        "ok", "", "criteria_not_met" -> if (passed) R.string.setting_phone_use_vd_status_verified else R.string.setting_phone_use_vd_status_failed
        else -> null
    }
}

private enum class AdbDialog { PAIR, CONNECT }

@Composable
private fun WirelessAdbSettingsCard(
    refreshTick: Int,
    onRefresh: () -> Unit,
    shizukuRunning: Boolean,
    shizukuAuthorized: Boolean,
    requestShizuku: () -> Unit,
) {
    val context = LocalContext.current
    val bridge = koinInject<EmbeddedAdbDisplayBridge>()
    val ready by bridge.ready.collectAsStateWithLifecycle()
    val keys = remember(context) { LocalAdbKeyStore(context.applicationContext) }
    val pairRevision by WirelessAdbPairingNotification.pairRevision.collectAsStateWithLifecycle()
    val paired by produceState(false, refreshTick, ready, pairRevision) {
        value = withContext(Dispatchers.IO) { keys.paired }
    }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<AdbDialog?>(null) }
    // Only the operation is restorable across a permission dialog, never the pairing code.
    var requestingPair by rememberSaveable { mutableStateOf(true) }

    fun connect(port: Int?, pairingCode: String? = null) {
        if (busy) return
        busy = true
        feedback = 0
        scope.launch {
            var pairedNow = false
            try {
                if (pairingCode != null) {
                    LocalAdbTlsClient(context.applicationContext).pair(port, pairingCode)
                    pairedNow = true
                    onRefresh()
                }
                // The connection service advertises a different port from the pairing service.
                bridge.start(if (pairedNow) null else port)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                feedback = when {
                    pairedNow -> R.string.vd_adb_pair_success
                    pairingCode != null -> R.string.vd_adb_pair_error
                    else -> R.string.vd_adb_connect_error
                }
            } finally {
                busy = false
            }
        }
    }

    fun openPairingSettings() {
        try {
            if (WirelessAdbPairingNotification.show(context)) {
                val settingsIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                try {
                    context.startActivity(settingsIntent)
                } catch (_: SecurityException) {
                    // Some Settings implementations only allow the existing Settings task.
                    settingsIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(settingsIntent)
                }
            } else {
                feedback = R.string.vd_adb_permission_hint
                dialog = AdbDialog.PAIR
            }
        } catch (_: Exception) {
            context.stopService(Intent(context, WirelessAdbPairingNotification::class.java))
            feedback = R.string.vd_adb_permission_hint
            dialog = AdbDialog.PAIR
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) {
            if (requestingPair) openPairingSettings() else connect(null)
        } else {
            feedback = R.string.vd_adb_permission_hint
            dialog = if (requestingPair) AdbDialog.PAIR else AdbDialog.CONNECT
        }
    }

    fun startDiscovery(pair: Boolean) {
        requestingPair = pair
        feedback = 0
        val missing = buildList {
            if (pair) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
        else if (pair) openPairingSettings() else connect(null)
    }

    SettingCardGroup(title = stringResource(R.string.vd_adb_section)) {
        val status = when {
            ready -> R.string.vd_adb_ready
            paired -> R.string.vd_adb_paired
            else -> R.string.vd_adb_unpaired
        }
        item(
            headlineContent = { Text(stringResource(status)) },
            supportingContent = {
                Text(stringResource(when {
                    feedback != 0 -> feedback
                    busy -> R.string.vd_adb_working
                    ready -> R.string.vd_adb_ready_desc
                    paired -> R.string.vd_adb_paired_desc
                    else -> R.string.vd_adb_pair_instructions
                }))
            },
        )
        if (ready) {
            item(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { bridge.stop() }
                    feedback = 0
                }
            }) { Text(stringResource(R.string.vd_adb_disconnect)) }
        } else {
            if (paired) {
                item(onClick = if (busy) null else ({ startDiscovery(false) })) {
                    Text(stringResource(R.string.vd_adb_connect))
                }
                item(onClick = if (busy) null else ({ dialog = AdbDialog.CONNECT })) {
                    Text(stringResource(R.string.vd_adb_manual_connect))
                }
            }
            item(onClick = if (busy) null else ({ startDiscovery(true) })) {
                Text(stringResource(R.string.vd_adb_go_pair))
            }
            item(onClick = if (busy) null else ({ dialog = AdbDialog.PAIR })) {
                Text(stringResource(R.string.vd_adb_manual_pair))
            }
        }
        if (shizukuRunning) {
            item(
                headlineContent = { Text(stringResource(R.string.vd_adb_shizuku)) },
                supportingContent = { Text(stringResource(if (shizukuAuthorized) R.string.setting_phone_use_vd_shizuku_ready else R.string.setting_phone_use_vd_shizuku_need_permission)) },
                trailingContent = {
                    if (!shizukuAuthorized) TextButton(onClick = requestShizuku) {
                        Text(stringResource(R.string.setting_phone_use_vd_shizuku_request_btn))
                    }
                },
            )
        }
    }

    dialog?.let { mode ->
        AdbPortDialog(mode, onDismiss = { dialog = null }) { port, code ->
            dialog = null
            connect(port, if (mode == AdbDialog.PAIR) code else null)
        }
    }
}

private val portDigits = Regex("[0-9]{1,5}")
private val octetDigits = Regex("0|[1-9][0-9]{0,2}")

internal fun parseLocalAdbPortInput(raw: String): Int? {
    val input = raw.trim().replace('：', ':')
    val port = input.substringAfter(':').trim()
    if (!portDigits.matches(port)) return null
    if (':' in input) {
        val octets = input.substringBefore(':').trim().split('.')
        if (octets.size != 4) return null
        val bytes = ByteArray(4)
        for ((index, octet) in octets.withIndex()) {
            if (!octetDigits.matches(octet) || octet.toInt() > 255) return null
            bytes[index] = octet.toInt().toByte()
        }
        val address = InetAddress.getByAddress(bytes)
        if (!address.isSiteLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress) return null
    }
    return port.toInt().takeIf { it in 1..65535 }
}

@Composable
private fun AdbPortDialog(mode: AdbDialog, onDismiss: () -> Unit, submit: (Int?, String) -> Unit) {
    var port by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val pairing = mode == AdbDialog.PAIR
    val parsedPort = parseLocalAdbPortInput(port)
    val autoDiscover = pairing && port.isBlank()
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }
    val requestNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) submit(null, code) else permissionDenied = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (pairing) R.string.vd_adb_manual_pair else R.string.vd_adb_manual_connect)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (pairing) R.string.vd_adb_manual_pair_hint else R.string.vd_adb_connect_hint))
                if (pairing) OutlinedTextField(
                    value = code, onValueChange = { code = it.filter { c -> c in '0'..'9' }.take(6) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.vd_adb_code)) },
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    label = { Text(stringResource(if (pairing) R.string.vd_adb_pair_port else R.string.vd_adb_connect_port)) },
                    placeholder = { Text(stringResource(R.string.vd_adb_address_example)) },
                    isError = port.isNotBlank() && parsedPort == null,
                )
                if (permissionDenied && autoDiscover) Text(stringResource(R.string.vd_adb_permission_hint))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            TextButton(
                enabled = (parsedPort != null || autoDiscover) && (!pairing || code.length == 6),
                onClick = {
                    if (autoDiscover && Build.VERSION.SDK_INT >= 37 &&
                        context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestNetwork.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                    } else submit(parsedPort, code)
                },
            ) { Text(stringResource(R.string.confirm)) }
        },
    )
}
