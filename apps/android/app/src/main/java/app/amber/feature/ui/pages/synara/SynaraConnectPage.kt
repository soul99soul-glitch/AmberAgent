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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.Screen
import app.amber.core.utils.openUrl
import app.amber.core.utils.plus
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.QrCodeScan
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

    fun normalizedConnection(): SynaraConnection? {
        val error = draft.validationError()
        if (error != null) {
            vm.reportError(error)
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
            vm.reportError("二维码不是 Synara 配对链接（应为 http://IP:3773/?token=…）")
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
            QRResult.QRMissingPermission -> vm.reportError("需要相机权限才能扫码")
            is QRResult.QRError -> vm.reportError("扫码失败：${result.exception.message ?: "未知错误"}")
            QRResult.QRUserCanceled -> Unit
        }
    }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = "Synara",
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
                text = "遥控 Mac 上的 Synara 工作台",
                style = MaterialTheme.typography.titleMedium,
                color = workspace.ink,
            )
            Text(
                text = "手机与 Mac 需在同一局域网（或 Tailscale）。\n" +
                    "桌面版默认只监听 127.0.0.1，请先在 Mac 上运行 LAN bridge。\n" +
                    "默认在 Amber 内全屏 WebView 打开；若异常可用 Chrome 兜底。",
                style = MaterialTheme.typography.bodyMedium,
                color = workspace.muted,
            )

            Button(
                onClick = { scanQrLauncher.launch(null) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !ui.checking,
            ) {
                Icon(
                    imageVector = HugeIcons.QrCodeScan,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(if (ui.checking) "正在验证配对…" else "扫码一键配对")
            }
            Text(
                text = "Mac 终端运行 LAN bridge 后会直接打印配对二维码，扫码即自动填充并进入工作台。",
                style = MaterialTheme.typography.bodySmall,
                color = workspace.muted,
            )

            OutlinedTextField(
                value = draft.host,
                onValueChange = { host -> vm.updateDraft { it.copy(host = host) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Mac 地址") },
                placeholder = { Text("例如 192.168.100.107") },
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
                label = { Text("端口") },
                placeholder = { Text("${SynaraConnection.DEFAULT_PORT}") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = draft.token,
                onValueChange = { token -> vm.updateDraft { it.copy(token = token) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Auth Token") },
                placeholder = { Text("SYNARA_AUTH_TOKEN") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = {
                    Text("来自 Mac 桌面进程环境变量 SYNARA_AUTH_TOKEN，等同密码")
                },
            )

            Text(
                text = "目标：${runCatching { draft.httpBaseUrl() }.getOrDefault("—")}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = workspace.muted,
            )

            ui.lastCheckMessage?.let { message ->
                Text(
                    text = message,
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
                    Text("测试连接")
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
                    Text("进入工作台")
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
                Text("用 Chrome 打开（备用）")
            }

            Text(
                text = "Mac 侧快速启动（桌面已打开时）：\n" +
                    "  python3 scripts/synara-lan-bridge.py\n" +
                    "终端会打印带 token 的完整 URL。",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = workspace.muted,
            )
        }
    }
}
