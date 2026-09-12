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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import app.amber.agent.R
import app.amber.agent.data.db.entity.MiniAppEntity
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.miniapp.MiniAppPermission
import app.amber.feature.miniapp.MiniAppRepository
import app.amber.feature.miniapp.MiniAppSandbox
import app.amber.feature.miniapp.MiniAppShell
import app.amber.feature.miniapp.MiniAppSourceChecks
import app.amber.feature.ui.theme.JetbrainsMono
import app.amber.feature.ui.theme.AmberTokens
import app.amber.feature.ui.theme.LocalAmberTokens
import org.koin.compose.koinInject
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt
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
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    val saveFailedMessage = stringResource(R.string.miniapp_save_failed)
    val tokens = LocalAmberTokens.current
    val codeScrollState = remember(app.id) { ScrollState(0) }

    fun requestDismiss() {
        when (miniAppEditorDismissAction(unsaved = unsaved, saving = saving)) {
            MiniAppEditorDismissAction.IGNORE -> Unit
            MiniAppEditorDismissAction.CONFIRM -> showDiscardConfirmation = true
            MiniAppEditorDismissAction.DISMISS -> onDismiss()
        }
    }

    BackHandler {
        if (showDiscardConfirmation) {
            showDiscardConfirmation = false
        } else {
            requestDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = ::requestDismiss,
        shape = RoundedCornerShape(18.dp),
        containerColor = tokens.raised,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.miniapp_source_title, app.title, app.version),
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
                            text = stringResource(R.string.miniapp_unsaved),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (mode) {
                    MODE_VIEW -> {
                        MiniAppCodeEditor(
                            source = editorText,
                            editable = false,
                            onSourceChange = {},
                            enabled = false,
                            scrollState = codeScrollState,
                        )
                        Text(
                            text = stringResource(R.string.miniapp_source_view_only_description),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    MODE_EDIT -> {
                        MiniAppCodeEditor(
                            source = editorText,
                            editable = true,
                            onSourceChange = {
                                editorText = it
                                issues = null
                            },
                            enabled = !saving,
                            scrollState = codeScrollState,
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
                ) { Text(stringResource(R.string.edit)) }

                MODE_EDIT -> Row {
                    TextButton(
                        enabled = !saving,
                        onClick = {
                            scope.launch {
                                val found = MiniAppSourceChecks.issues(editorText, context)
                                if (found.isNotEmpty()) {
                                    issues = found
                                    return@launch
                                }
                                saving = true
                                try {
                                    repository.saveNewVersion(
                                        app = app,
                                        htmlContent = editorText,
                                        changeNote = "Edited in source editor",
                                    )
                                    saving = false
                                    onDismiss()
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Throwable) {
                                    saving = false
                                    issues = listOf(
                                        MiniAppSourceChecks.Issue(error.message ?: saveFailedMessage)
                                    )
                                }
                            }
                        },
                    ) {
                        if (saving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.common_save))
                        }
                    }
                }

                else -> TextButton(
                    onClick = { mode = MODE_EDIT },
                    enabled = !saving,
                ) { Text(stringResource(R.string.miniapp_back_to_edit)) }
            }
        },
        dismissButton = {
            when (mode) {
                MODE_VIEW -> TextButton(
                    onClick = ::requestDismiss,
                    enabled = !saving,
                ) { Text(stringResource(R.string.update_card_close)) }
                MODE_EDIT -> Row {
                    TextButton(
                        enabled = !saving,
                        onClick = {
                            if (unsaved) {
                                editorText = app.htmlContent
                                issues = null
                                Toast.makeText(context, R.string.miniapp_discarded, Toast.LENGTH_SHORT).show()
                            }
                            mode = MODE_VIEW
                        },
                    ) { Text(stringResource(R.string.miniapp_discard_changes)) }
                    TextButton(
                        enabled = !saving,
                        onClick = { mode = MODE_PREVIEW },
                    ) { Text(stringResource(R.string.code_block_preview)) }
                }
                else -> Row {
                    TextButton(
                        enabled = !saving,
                        onClick = {
                            if (unsaved) {
                                editorText = app.htmlContent
                                issues = null
                                Toast.makeText(context, R.string.miniapp_discarded, Toast.LENGTH_SHORT).show()
                            }
                            mode = MODE_EDIT
                        },
                    ) { Text(stringResource(R.string.miniapp_discard_changes)) }
                    TextButton(
                        onClick = ::requestDismiss,
                        enabled = !saving,
                    ) { Text(stringResource(R.string.update_card_close)) }
                }
            }
        },
    )

    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = {
                Text(stringResource(R.string.parity_miniapp_source_unsaved_title))
            },
            text = {
                Text(stringResource(R.string.parity_miniapp_source_unsaved_message))
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirmation = false }) {
                    Text(stringResource(R.string.parity_miniapp_source_keep_editing))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmation = false
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.parity_miniapp_source_discard))
                }
            },
        )
    }

}

private const val MODE_VIEW = 0
private const val MODE_EDIT = 1
private const val MODE_PREVIEW = 2

internal enum class MiniAppEditorDismissAction {
    IGNORE,
    CONFIRM,
    DISMISS,
}

internal fun miniAppEditorDismissAction(
    unsaved: Boolean,
    saving: Boolean,
): MiniAppEditorDismissAction = when {
    saving -> MiniAppEditorDismissAction.IGNORE
    unsaved -> MiniAppEditorDismissAction.CONFIRM
    else -> MiniAppEditorDismissAction.DISMISS
}

/**
 * The source well keeps the gutter and source in one scroll container. That
 * makes line numbers follow the editor during long HTML files while leaving
 * the saved source as the original plain string.
 */
@Composable
internal fun MiniAppCodeEditor(
    source: String,
    editable: Boolean,
    onSourceChange: (String) -> Unit,
    enabled: Boolean,
    scrollState: ScrollState,
) {
    val tokens = LocalAmberTokens.current
    val lineHeight = 19.sp
    var sourceTextLayout by remember(source, editable) { mutableStateOf<TextLayoutResult?>(null) }
    val onSourceTextLayout: (TextLayoutResult) -> Unit = { sourceTextLayout = it }
    val horizontalScrollState = rememberScrollState()
    val editorStyle = TextStyle(
        fontFamily = JetbrainsMono,
        fontSize = 12.sp,
        lineHeight = lineHeight,
        color = tokens.ink,
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 260.dp, max = 380.dp),
        shape = RoundedCornerShape(14.dp),
        color = tokens.surface2,
        border = androidx.compose.foundation.BorderStroke(1.dp, tokens.line),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 260.dp, max = 380.dp)
                .verticalScroll(scrollState),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                MiniAppLineNumberGutter(
                    style = editorStyle,
                    color = tokens.ink4,
                    sourceLayout = sourceTextLayout,
                )

                if (editable) {
                    BasicTextField(
                        value = source,
                        onValueChange = onSourceChange,
                        enabled = enabled,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp, end = 12.dp)
                            .horizontalScroll(horizontalScrollState),
                        textStyle = editorStyle,
                        cursorBrush = SolidColor(tokens.accent),
                        visualTransformation = MiniAppSyntaxTransformation(tokens),
                        onTextLayout = onSourceTextLayout,
                    )
                } else {
                    SelectionContainer {
                        Text(
                            text = syntaxHighlightedSource(source, tokens),
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp, end = 12.dp)
                                .horizontalScroll(horizontalScrollState),
                            style = editorStyle,
                            softWrap = false,
                            onTextLayout = onSourceTextLayout,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniAppLineNumberGutter(
    style: TextStyle,
    color: androidx.compose.ui.graphics.Color,
    sourceLayout: TextLayoutResult?,
) {
    Layout(
        modifier = Modifier.width(38.dp),
        content = {
            repeat(sourceLayout?.lineCount ?: 0) { index ->
                Text(
                    text = (index + 1).toString(),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                    style = style.copy(color = color),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        },
    ) { measurables, constraints ->
        if (sourceLayout == null) {
            layout(constraints.maxWidth, 0) {}
        } else {
            val childConstraints = constraints.copy(minWidth = constraints.maxWidth, minHeight = 0)
            val placeables = measurables.map { it.measure(childConstraints) }
            layout(constraints.maxWidth, sourceLayout.size.height) {
                placeables.forEachIndexed { index, placeable ->
                    val y = sourceLayout.getLineBaseline(index).roundToInt() - placeable[FirstBaseline]
                    placeable.placeRelative(0, y)
                }
            }
        }
    }
}

internal class MiniAppSyntaxTransformation(
    private val tokens: AmberTokens,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText = TransformedText(
        text = syntaxHighlightedSource(text.text, tokens),
        offsetMapping = OffsetMapping.Identity,
    )
}

private val miniAppSyntaxPattern = Regex(
    """<!--.*?-->|</?[\w:-]+[^>]*>|"(?:\\.|[^"])*"|'(?:\\.|[^'])*'|//.*|\b(?:const|let|var|function|return|if|else|for|while|class|new|style|script)\b|\b\d+(?:\.\d+)?\b"""
)

private fun syntaxHighlightedSource(source: String, tokens: AmberTokens): AnnotatedString =
    buildAnnotatedString {
        source.split('\n').forEachIndexed { lineIndex, line ->
            var cursor = 0
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("<!--")) {
                withStyle(SpanStyle(color = tokens.ink3)) { append(line) }
            } else {
                miniAppSyntaxPattern.findAll(line).forEach { match ->
                    if (match.range.first > cursor) {
                        append(line.substring(cursor, match.range.first))
                    }
                    val token = match.value
                    val color = when {
                        token.startsWith("<") -> tokens.ink3
                        token.startsWith("\"") || token.startsWith("'") -> tokens.signal
                        token.firstOrNull()?.isDigit() == true -> tokens.ink2
                        token in setOf("const", "let", "var", "function", "return", "if", "else", "for", "while", "class", "new", "style", "script") -> tokens.accent
                        else -> tokens.ink2
                    }
                    withStyle(SpanStyle(color = color)) { append(token) }
                    cursor = match.range.last + 1
                }
                if (cursor < line.length) append(line.substring(cursor))
            }
            if (lineIndex < source.count { it == '\n' }) append('\n')
        }
    }

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
    val shellHtml = remember(context, html, previewToken) {
        MiniAppShell.inject(context, html, bridgeScript = "", sessionToken = previewToken)
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
