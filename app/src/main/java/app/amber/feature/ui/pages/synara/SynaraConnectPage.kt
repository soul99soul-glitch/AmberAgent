package app.amber.feature.ui.pages.synara

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.core.utils.openUrl
import app.amber.core.utils.plus
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ScanQrCode
import org.koin.androidx.compose.koinViewModel

/**
 * Configure LAN endpoint and open the Synara desktop web UI.
 *
 * Default: in-app full-screen WebView (Amber chrome-free).
 * Fallback: Chrome/Custom Tabs if WebView misbehaves on a device.
 *
 * Mac side: keep Synara desktop open and run
 * `python3 scripts/synara-lan-bridge.py` (rewrites Host/Origin + injects WS token).
 */
@Composable
fun SynaraConnectPage(vm: SynaraVM = koinViewModel()) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val workspace = workspaceColors()
    val draft = ui.draft
    val unknownQrError = stringResource(R.string.synara_unknown_error)

    fun normalizedConnection(): SynaraConnection? {
        val error = draft.validationError()
        if (error != null) {
            vm.reportError(error.resourceId())
            return null
        }
        return draft.copy(
            host = draft.host.trim(),
            token = draft.token.trim(),
        )
    }

    fun pairFromScan(raw: String) {
        val parsed = SynaraConnection.fromQrPayload(raw)
        if (parsed == null) {
            vm.reportError(R.string.synara_pairing_invalid)
            return
        }
        vm.updateDraft { parsed }
        vm.testConnection { conn ->
            navController.navigate(
                Screen.SynaraWorkspace(
                    host = conn.host,
                    port = conn.port,
                    token = conn.token,
                    useHttps = conn.useHttps,
                ),
            )
        }
    }

    val scanQrLauncher = rememberLauncherForActivityResult(ScanQRCode()) { result ->
        when (result) {
            is QRResult.QRSuccess -> pairFromScan(result.content.rawValue.orEmpty())
            QRResult.QRMissingPermission -> vm.reportError(R.string.synara_camera_permission_required)
            is QRResult.QRError -> vm.reportError(
                R.string.synara_qr_scan_failed,
                result.exception.message ?: unknownQrError,
            )
            QRResult.QRUserCanceled -> Unit
        }
    }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.synara_title),
                navigationIcon = { BackButton() },
            )
        },
        containerColor = workspace.canvas,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding + PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.synara_page_title),
                style = MaterialTheme.typography.titleMedium,
                color = workspace.ink,
            )
            Text(
                text = stringResource(R.string.synara_page_description),
                style = MaterialTheme.typography.bodyMedium,
                color = workspace.muted,
            )

            Button(
                onClick = { scanQrLauncher.launch(null) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !ui.checking,
            ) {
                Icon(
                    imageVector = Lucide.ScanQrCode,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (ui.checking) {
                        stringResource(R.string.synara_pairing_verifying)
                    } else {
                        stringResource(R.string.synara_pairing_scan)
                    },
                )
            }
            Text(
                text = stringResource(R.string.synara_pairing_help),
                style = MaterialTheme.typography.bodySmall,
                color = workspace.muted,
            )

            OutlinedTextField(
                value = draft.host,
                onValueChange = { host -> vm.updateDraft { it.copy(host = host) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.synara_mac_address_label)) },
                placeholder = { Text(stringResource(R.string.synara_mac_address_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = draft.port.toString(),
                onValueChange = { text ->
                    val port = text.filter { it.isDigit() }.toIntOrNull() ?: 0
                    vm.updateDraft { it.copy(port = port) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.synara_port_label)) },
                placeholder = { Text("${SynaraConnection.DEFAULT_PORT}") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = draft.token,
                onValueChange = { token -> vm.updateDraft { it.copy(token = token) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.synara_auth_token_label)) },
                placeholder = { Text(stringResource(R.string.synara_auth_token_hint)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = {
                    Text(stringResource(R.string.synara_auth_token_supporting))
                },
            )

            Text(
                text = stringResource(
                    R.string.synara_target,
                    runCatching { draft.httpBaseUrl() }.getOrDefault("—"),
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = workspace.muted,
            )

            ui.lastCheckMessage?.let { message ->
                Text(
                    text = stringResource(message.resourceId, *message.formatArgs.toTypedArray()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ui.lastCheckOk == true) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { vm.testConnection() },
                    enabled = !ui.checking,
                    modifier = Modifier.weight(1f),
                ) {
                    if (ui.checking) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(stringResource(R.string.synara_test_connection))
                }
                Button(
                    onClick = {
                        val conn = normalizedConnection() ?: return@Button
                        vm.save {
                            navController.navigate(
                                Screen.SynaraWorkspace(
                                    host = conn.host,
                                    port = conn.port,
                                    token = conn.token,
                                    useHttps = conn.useHttps,
                                ),
                            )
                        }
                    },
                    enabled = !ui.checking && draft.isConfigured,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.synara_open_workbench))
                }
            }

            TextButton(
                onClick = {
                    val conn = normalizedConnection() ?: return@TextButton
                    vm.save()
                    context.openUrl(conn.workspaceUrl())
                },
                enabled = !ui.checking && draft.isConfigured,
            ) {
                Text(stringResource(R.string.synara_open_chrome))
            }

            Text(
                text = stringResource(R.string.synara_mac_quick_start),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = workspace.muted,
            )
        }
    }
}
