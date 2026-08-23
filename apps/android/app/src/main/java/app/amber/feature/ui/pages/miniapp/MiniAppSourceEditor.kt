package app.amber.feature.ui.pages.miniapp

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import app.amber.agent.data.db.entity.MiniAppEntity
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.miniapp.MiniAppPermission
import app.amber.feature.miniapp.MiniAppRepository
import app.amber.feature.miniapp.MiniAppSandbox
import app.amber.feature.miniapp.MiniAppShell
import app.amber.feature.miniapp.MiniAppSourceChecks
import app.amber.feature.ui.theme.JetbrainsMono
import org.koin.compose.koinInject
import java.io.ByteArrayInputStream
import kotlin.uuid.Uuid

/**
 * P3-05: MiniApp source editor — 只读源码查看 → 可编辑模式 + 未保存状态 →
 * HTML/JS/CSS 基础校验 → sandbox 预览 → 保存新版本（保留 previous）→ 明确的
 * 放弃更改 (revert)。权限声明不在编辑范围（HTML 内不含权限声明），保存路径
 * 保持 permissionsJson 不变，因此不需要重新审批；若未来允许编辑权限声明，
 * 必须接入现有审批流。
 */
@Composable
fun MiniAppSourceEditorDialog(
    app: MiniAppEntity,
    onDismiss: () -> Unit,
    repository: MiniAppRepository = koinInject(),
    settingsStore: SettingsAggregator = koinInject(),
) {
    var mode by remember(app.id) { mutableIntStateOf(MODE_VIEW) }
    var editorText by remember(app.id) { mutableStateOf(app.htmlContent) }
    var issues by remember { mutableStateOf<List<MiniAppSourceChecks.Issue>?>(null) }
    var saving by remember { mutableStateOf(false) }
    val unsaved = MiniAppSourceChecks.hasUnsavedChanges(app.htmlContent, editorText)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = buildString {
                        append("源码 · ${app.title}")
                        append(" v${app.version}")
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (unsaved) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // 未保存状态指示
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.error, CircleShape),
                        )
                        Text(
                            text = "未保存",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (mode) {
                    MODE_VIEW -> {
                        SelectionContainer {
                            Text(
                                text = editorText,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 380.dp)
                                    .verticalScroll(rememberScrollState()),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                            )
                        }
                        Text(
                            text = "仅查看模式。编辑源码、校验、预览并保存为新的版本。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    MODE_EDIT -> {
                        OutlinedTextField(
                            value = editorText,
                            onValueChange = {
                                editorText = it
                                issues = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                            minLines = 14,
                        )
                        issues?.let { found ->
                            Text(
                                text = found.joinToString("\n") { "• ${it.message}" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    else -> MiniAppSourcePreview(
                        html = editorText,
                        app = app,
                        repository = repository,
                        settingsStore = settingsStore,
                    )
                }
            }
        },
        confirmButton = {
            when (mode) {
                MODE_VIEW -> TextButton(
                    onClick = { mode = MODE_EDIT },
                    enabled = !saving,
                ) { Text("编辑") }

                MODE_EDIT -> Row {
                    TextButton(
                        enabled = !saving,
                        onClick = {
                            scope.launch {
                                val found = MiniAppSourceChecks.issues(editorText)
                                if (found.isNotEmpty()) {
                                    issues = found
                                    return@launch
                                }
                                saving = true
                                runCatching {
                                    repository.saveNewVersion(
                                        app = app,
                                        htmlContent = editorText,
                                        changeNote = "Edited in source editor",
                                    )
                                }.onSuccess {
                                    saving = false
                                    onDismiss()
                                }.onFailure { error ->
                                    saving = false
                                    issues = listOf(
                                        MiniAppSourceChecks.Issue(error.message ?: "保存失败")
                                    )
                                }
                            }
                        },
                    ) {
                        if (saving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("保存")
                        }
                    }
                }

                else -> TextButton(onClick = { mode = MODE_EDIT }) { Text("返回编辑") }
            }
        },
        dismissButton = {
            when (mode) {
                MODE_VIEW -> TextButton(onClick = onDismiss) { Text("关闭") }
                MODE_EDIT -> Row {
                    TextButton(
                        onClick = {
                            if (unsaved) {
                                editorText = app.htmlContent
                                issues = null
                                Toast.makeText(context, "已放弃更改", Toast.LENGTH_SHORT).show()
                            }
                            mode = MODE_VIEW
                        },
                    ) { Text("放弃更改") }
                    TextButton(
                        onClick = { mode = MODE_PREVIEW },
                    ) { Text("预览") }
                }
                else -> Row {
                    TextButton(
                        onClick = {
                            if (unsaved) {
                                editorText = app.htmlContent
                                issues = null
                                Toast.makeText(context, "已放弃更改", Toast.LENGTH_SHORT).show()
                            }
                            mode = MODE_EDIT
                        },
                    ) { Text("放弃更改") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        },
    )

}

private const val MODE_VIEW = 0
private const val MODE_EDIT = 1
private const val MODE_PREVIEW = 2

/**
 * Sandboxed preview of the (possibly unsaved) source: same MiniAppShell CSP
 * injection and the same WebView restrictions as the runner, without the JS
 * bridge — the preview cannot call host APIs. https images only pass when the
 * app declared the externalImages permission (same gate as the runner).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MiniAppSourcePreview(
    html: String,
    app: MiniAppEntity,
    repository: MiniAppRepository,
    settingsStore: SettingsAggregator,
) {
    val context = LocalContext.current
    val json = remember { kotlinx.serialization.json.Json { ignoreUnknownKeys = true } }
    val permissions = remember(app.id, app.permissionsJson) {
        runCatching { json.decodeFromString<List<String>>(app.permissionsJson) }.getOrDefault(emptyList()).toSet()
    }
    val previewToken = remember { Uuid.random().toString() }
    val shellHtml = remember(html, previewToken) {
        MiniAppShell.inject(html, bridgeScript = "", sessionToken = previewToken)
    }
    var webView by remember { mutableStateOf<WebView?>(null) }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 320.dp, max = 420.dp),
        factory = { ctx ->
            WebView(ctx).apply {
                webView = this
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.databaseEnabled = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.blockNetworkLoads = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        return when (request.url.scheme?.lowercase()) {
                            "https" -> {
                                val allowed = runCatching {
                                    MiniAppSandbox(
                                        appId = app.id,
                                        declaredPermissions = permissions,
                                        settingProvider = { settingsStore.settingsFlow.value.agentRuntime.miniApp },
                                        grantDecision = { permission ->
                                            runBlocking { repository.grantDecision(app.id, permission) }
                                        },
                                    ).require(MiniAppPermission.ExternalImages)
                                }.isSuccess
                                if (allowed) null else blockedPreviewResponse()
                            }
                            "http", "file", "content", "android_asset", "jar", "blob" -> blockedPreviewResponse()
                            "data", "about" -> null
                            else -> blockedPreviewResponse()
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) = Unit
                }
            }
        },
    )

    LaunchedEffect(webView, shellHtml) {
        webView?.loadDataWithBaseURL(MiniAppShell.BASE_URL, shellHtml, "text/html", "utf-8", null)
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
            webView = null
        }
    }
}

private fun blockedPreviewResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
