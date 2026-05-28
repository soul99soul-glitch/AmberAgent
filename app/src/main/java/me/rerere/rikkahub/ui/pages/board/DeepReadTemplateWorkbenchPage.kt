package me.rerere.rikkahub.ui.pages.board

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import app.amber.feature.board.DeepReadTemplateIds
import app.amber.feature.board.hotlist.deepread.DeepReadAgentRunManager
import app.amber.feature.board.hotlist.deepread.DeepReadOutput
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateAgent
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateDraftGuard
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplatePackage
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateRenderer
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateRepository
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.data.font.SlidesFontRepository
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.net.URI
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeepReadTemplateWorkbenchPage(
    vm: SettingVM = koinViewModel(),
) {
    val templateAgent: DeepReadTemplateAgent = koinInject()
    val deepReadAgent: DeepReadAgentRunManager = koinInject()
    val templateRepository: DeepReadTemplateRepository = koinInject()
    val fontRepository: SlidesFontRepository = koinInject()
    val settingsStore: SettingsAggregator = koinInject()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val board = settings.agentRuntime.todayBoard
    val fontStates by fontRepository.fontsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val sampleTitle = "具身智能进入家庭前夜"
    val sampleOutput = remember { DeepReadTemplateRenderer.sampleOutput() }
    val darkTheme = LocalDarkMode.current
    val fontCss = rememberDeepReadTemplateFontCss(
        mode = board.boardReadingFontMode,
        fontPackId = board.boardReadingFontPackId,
        fontStates = fontStates,
        fontScale = board.deepReadFontScale,
    )

    var templateName by rememberSaveable { mutableStateOf("News 斜切杂志") }
    var instruction by rememberSaveable {
        mutableStateOf("参考高端中文 News 杂志 App：强标题排版、紧凑正文、清楚的时间轴和扩展阅读。")
    }
    var editorText by rememberSaveable { mutableStateOf("") }
    var sourceExpanded by rememberSaveable { mutableStateOf(false) }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }
    var runError by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var showExitDialog by rememberSaveable { mutableStateOf(false) }
    var previewTitle by remember { mutableStateOf(sampleTitle) }
    var demoPreviewUrl by remember { mutableStateOf<String?>(null) }
    var previewOutput by remember { mutableStateOf<DeepReadOutput>(sampleOutput) }
    var validDraft by remember { mutableStateOf<DeepReadTemplatePackage?>(null) }

    val rendered = remember(validDraft, previewTitle, previewOutput, fontCss, darkTheme) {
        runCatching {
            validDraft?.let { draft ->
                DeepReadTemplateRenderer.renderCustom(
                    title = previewTitle,
                    output = previewOutput,
                    templateHtml = draft.html,
                    fontCss = fontCss,
                    darkTheme = darkTheme,
                )
            } ?: DeepReadTemplateRenderer.renderEditorialSlant(
                title = previewTitle,
                output = previewOutput,
                fontCss = fontCss,
                darkTheme = darkTheme,
            )
        }.getOrNull()
    }

    fun requestExit() {
        when {
            busy || saving -> return
            validDraft == null -> {
                navController.popBackStack()
            }
            else -> {
                showExitDialog = true
            }
        }
    }

    suspend fun selectSavedTemplate(templateId: String) {
        settingsStore.update { current ->
            current.copy(
                agentRuntime = current.agentRuntime.copy(
                    todayBoard = current.agentRuntime.todayBoard.copy(
                        deepReadTemplateId = templateId,
                    )
                )
            )
        }
        navController.popBackStack()
    }

    fun applyDraft(draft: DeepReadTemplatePackage) {
        val named = draft.copy(name = templateName.ifBlank { draft.name }.ifBlank { "自定义模板" })
        validDraft = named
        editorText = named.html
        validationError = null
        runError = null
    }

    fun runAgent() {
        val text = instruction.trim()
        if (text.isBlank() || busy || saving) return
        val previewUrl = text.extractTemplateDemoUrl()
        busy = true
        runError = null
        scope.launch {
            try {
                if (previewUrl != null) {
                    val title = previewUrl.toTemplateDemoTitle()
                    previewTitle = title
                    previewOutput = sampleOutput
                    demoPreviewUrl = null
                    val result = deepReadAgent.runPreview(
                        topicTitle = title,
                        seedUrl = previewUrl,
                    )
                    result.onSuccess { output ->
                        previewTitle = output.bestPreviewTitle(previewUrl) ?: title
                        previewOutput = output
                        demoPreviewUrl = previewUrl
                    }.onFailure { error ->
                        runError = error.message ?: "新闻 Demo 生成失败"
                    }
                } else {
                    val result = validDraft?.let { draft ->
                        templateAgent.reviseDraft(draft.copy(name = templateName), text)
                    } ?: templateAgent.generateDraft(templateName, text)
                    result.onSuccess(::applyDraft).onFailure { error ->
                        runError = error.message ?: "模板生成失败"
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                runError = error.message ?: "模板生成失败"
            } finally {
                busy = false
            }
        }
    }

    BackHandler { requestExit() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模板工作台") },
                navigationIcon = {
                    TextButton(onClick = ::requestExit) {
                        Text("返回")
                    }
                },
                actions = {
                    TextButton(
                        enabled = validDraft != null && !busy && !saving && validationError == null,
                        onClick = { showSaveDialog = true },
                    ) {
                        Text(if (saving) "保存中..." else "保存")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            TemplateWorkbenchComposer(
                instruction = instruction,
                onInstructionChange = { instruction = it },
                busy = busy,
                hasDraft = validDraft != null,
                error = runError,
                onSend = ::runAgent,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            if (busy || saving) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                rendered?.let { template ->
                    DeepReadStaticTemplateWebView(
                        html = template.html,
                        modifier = Modifier.fillMaxSize(),
                        baseUrl = DEEP_READ_TEMPLATE_PREVIEW_BASE_URL,
                        allowedImageUrls = if (demoPreviewUrl != null) template.allowedImageUrls else emptySet(),
                        fontRepository = fontRepository,
                        textScale = board.deepReadFontScale,
                        backgroundColor = MaterialTheme.colorScheme.surface,
                    )
                } ?: Text(
                    "模板预览不可用",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            SourcePanel(
                expanded = sourceExpanded,
                editorText = editorText,
                validationError = validationError,
                enabled = !busy && !saving,
                onToggle = { sourceExpanded = !sourceExpanded },
                onTextChange = { html ->
                    editorText = html
                    val result = DeepReadTemplateDraftGuard.applySourceEdit(validDraft, templateName, html)
                    validationError = result.validationError
                    result.validDraft?.let { validDraft = it }
                },
            )
        }
    }

    if (showSaveDialog && validDraft != null) {
        SaveTemplateDialog(
            name = templateName,
            onNameChange = { templateName = it },
            onDismiss = { showSaveDialog = false },
            onSave = {
                val draft = validDraft ?: return@SaveTemplateDialog
                saving = true
                showSaveDialog = false
                scope.launch {
                    try {
                        val saved = templateRepository.saveTemplate(
                            draft.copy(
                                id = DeepReadTemplateIds.custom(Uuid.random().toString()),
                                name = templateName,
                                createdByAi = true,
                            )
                        )
                        selectSavedTemplate(saved.id)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        saving = false
                        runError = error.message ?: "模板保存失败"
                    }
                }
            },
        )
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("放弃未保存模板？") },
            text = { Text("当前草稿还没有保存，返回后会丢失。") },
            confirmButton = {
                TextButton(onClick = { navController.popBackStack() }) {
                    Text("放弃")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { showExitDialog = false }) {
                        Text("继续编辑")
                    }
                    TextButton(
                        enabled = validDraft != null && validationError == null && !busy && !saving,
                        onClick = {
                            showExitDialog = false
                            showSaveDialog = true
                        },
                    ) {
                        Text("保存")
                    }
                }
            },
        )
    }
}

@Composable
private fun TemplateWorkbenchComposer(
    instruction: String,
    onInstructionChange: (String) -> Unit,
    busy: Boolean,
    hasDraft: Boolean,
    error: String?,
    onSend: () -> Unit,
) {
    val previewUrl = remember(instruction) { instruction.extractTemplateDemoUrl() }
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            error?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = instruction,
                    onValueChange = onInstructionChange,
                    modifier = Modifier.weight(1f),
                    minLines = 2,
                    maxLines = 5,
                    enabled = !busy,
                    shape = RoundedCornerShape(28.dp),
                    label = {
                        Text(
                            when {
                                previewUrl != null -> "新闻地址 Demo 预览"
                                hasDraft -> "继续修改模板"
                                else -> "描述你想要的模板"
                            }
                        )
                    },
                )
                Button(
                    enabled = instruction.trim().isNotEmpty() && !busy,
                    onClick = onSend,
                    shape = RoundedCornerShape(28.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        when {
                            busy -> "处理中"
                            previewUrl != null -> "预览"
                            hasDraft -> "修改"
                            else -> "生成"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SourcePanel(
    expanded: Boolean,
    editorText: String,
    validationError: String?,
    enabled: Boolean,
    onToggle: () -> Unit,
    onTextChange: (String) -> Unit,
) {
    Surface(tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("源码", style = MaterialTheme.typography.titleSmall)
                TextButton(enabled = editorText.isNotBlank(), onClick = onToggle) {
                    Text(if (expanded) "收起" else "查看/微调")
                }
            }
            if (expanded) {
                validationError?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } ?: Text(
                    "校验通过后才会刷新预览和允许保存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = workspaceColors().muted,
                )
                OutlinedTextField(
                    value = editorText,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 260.dp),
                    enabled = enabled,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SaveTemplateDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存模板") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text("模板名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = name.trim().isNotEmpty(), onClick = onSave) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

private fun String.extractTemplateDemoUrl(): String? {
    val match = HTTP_URL_PATTERN.find(this) ?: return null
    val raw = match.value.trimEnd(',', '.', '，', '。', '、', ')', '）', ']', '】')
    val remaining = replace(match.value, "").trim()
    val hasDemoIntent =
        remaining.isBlank() ||
            remaining.contains("预览") ||
            remaining.contains("demo", ignoreCase = true) ||
            remaining.contains("样稿")
    return raw.takeIf { hasDemoIntent && it.isHttpOrHttpsUrl() }
}

private fun String.toTemplateDemoTitle(): String {
    val uri = runCatching { URI(this) }.getOrNull()
    val host = uri?.host?.removePrefix("www.") ?: return "新闻链接 Demo"
    val lastPath = uri.rawPath
        ?.split('/')
        ?.lastOrNull { it.isNotBlank() }
        ?.substringBefore('?')
        ?.replace('-', ' ')
        ?.replace('_', ' ')
        ?.take(36)
        .orEmpty()
    return listOf(host, lastPath)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
        .ifBlank { "新闻链接 Demo" }
}

private fun DeepReadOutput.bestPreviewTitle(seedUrl: String): String? =
    (references + extendedReading)
        .firstOrNull { it.url == seedUrl || it.url.trimEnd('/') == seedUrl.trimEnd('/') }
        ?.title
        ?.takeIf { it.isNotBlank() }

private fun String.isHttpOrHttpsUrl(): Boolean {
    val uri = runCatching { URI(this) }.getOrNull() ?: return false
    return (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
}

private val HTTP_URL_PATTERN = Regex("""https?://[^\s<>"']+""")
