package app.amber.feature.ui.pages.zcode

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.Screen
import app.amber.core.utils.plus
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalToaster
import com.dokar.sonner.ToastType
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import kotlinx.coroutines.launch
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ScanQrCode
import org.koin.compose.koinInject

/**
 * ZCode companion: paste the share URL from 智谱 ZCode and open its mobile web UI.
 * No special bridge — ZCode is already a mobile-ready HTML page.
 */
@Composable
fun ZCodePage(
    store: ZCodeUrlStore = koinInject(),
) {
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val workspace = workspaceColors()
    val scope = rememberCoroutineScope()
    val saved by store.urlFlow.collectAsStateWithLifecycle(initialValue = "")
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun openZCodeUrl(raw: String) {
        val url = normalizeZCodeUrl(raw)
        if (url == null) {
            error = "请输入有效的 http(s) 链接"
            return
        }
        scope.launch {
            store.save(url)
            navController.navigate(Screen.ZCodeSession(url = url))
        }
    }

    val scanQrCodeLauncher = rememberLauncherForActivityResult(ScanQRCode()) { result ->
        when (result) {
            is QRResult.QRSuccess -> {
                val content = result.content.rawValue.orEmpty().trim()
                if (content.isEmpty()) {
                    error = "二维码内容为空"
                    return@rememberLauncherForActivityResult
                }
                draft = content
                error = null
                val url = normalizeZCodeUrl(content)
                if (url == null) {
                    error = "扫到的内容不是有效的 http(s) 链接"
                    toaster.show("扫码成功，但不是有效链接", type = ToastType.Error)
                } else {
                    openZCodeUrl(url)
                }
            }

            QRResult.QRMissingPermission -> {
                error = "需要相机权限才能扫码"
                toaster.show("需要相机权限才能扫码", type = ToastType.Error)
            }

            is QRResult.QRError -> {
                error = "扫码失败：${result.exception.message ?: result}"
                toaster.show("扫码失败", type = ToastType.Error)
            }

            QRResult.QRUserCanceled -> Unit
        }
    }

    LaunchedEffect(saved) {
        if (draft.isBlank() && saved.isNotBlank()) {
            draft = saved
        }
    }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = "ZCode",
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
                text = "打开智谱 ZCode 移动页",
                style = MaterialTheme.typography.titleMedium,
                color = workspace.ink,
            )
            Text(
                text = "粘贴 ZCode 分享/远程链接，或点击输入框右侧扫码，Amber 用内置浏览器打开。",
                style = MaterialTheme.typography.bodyMedium,
                color = workspace.muted,
            )

            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("ZCode 链接") },
                placeholder = { Text("https://…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                trailingIcon = {
                    IconButton(onClick = { scanQrCodeLauncher.launch(null) }) {
                        Icon(
                            imageVector = Lucide.ScanQrCode,
                            contentDescription = "扫描二维码",
                        )
                    }
                },
            )

            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = { openZCodeUrl(draft) },
                enabled = draft.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开")
            }
        }
    }
}

/** Accept bare host by prefixing https; reject non-http schemes. */
internal fun normalizeZCodeUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val withScheme = when {
        trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        trimmed.startsWith("http://", ignoreCase = true) -> trimmed
        trimmed.contains("://") -> return null
        else -> "https://$trimmed"
    }
    return runCatching {
        val uri = java.net.URI(withScheme)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        withScheme
    }.getOrNull()
}
