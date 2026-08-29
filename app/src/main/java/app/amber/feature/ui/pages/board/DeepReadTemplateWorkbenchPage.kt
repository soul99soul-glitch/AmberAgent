package app.amber.feature.ui.pages.board

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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import app.amber.feature.board.DeepReadTemplateIds
import app.amber.agent.R
import app.amber.feature.board.hotlist.deepread.DeepReadAgentRunManager
import app.amber.feature.board.hotlist.deepread.DeepReadOutput
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateAgent
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateDraftGuard
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplatePackage
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateRenderer
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateRepository
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.font.SlidesFontRepository
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.pages.setting.SettingVM
import app.amber.feature.ui.theme.LocalDarkMode
import app.amber.core.utils.appLocale
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
    val appLocale = LocalView.current.context.appLocale()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val board = settings.agentRuntime.todayBoard
    val fontStates by fontRepository.fontsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val sampleTitle = stringResource(R.string.deep_read_sample_title)
    val defaultTemplateName = stringResource(R.string.deep_read_workbench_default_name)
    val defaultInstruction = stringResource(R.string.deep_read_workbench_default_instruction)
    val customTemplateName = stringResource(R.string.deep_read_template_custom)
    val demoFailureMessage = stringResource(R.string.deep_read_demo_failed)
    val templateFailureMessage = stringResource(R.string.deep_read_template_generation_failed)
    val templateSaveFailureMessage = stringResource(R.string.deep_read_template_save_failed)
    val demoTitleFallback = stringResource(R.string.deep_read_demo_title_fallback)
    val sampleOutput = remember(appLocale) { DeepReadTemplateRenderer.sampleOutput(appLocale) }
    val darkTheme = LocalDarkMode.current
    val fontCss = rememberDeepReadTemplateFontCss(
        mode = board.boardReadingFontMode,
        fontPackId = board.boardReadingFontPackId,
        fontStates = fontStates,
        fontScale = board.deepReadFontScale,
    )

    var templateName by rememberSaveable { mutableStateOf(defaultTemplateName) }
    var instruction by rememberSaveable {
        mutableStateOf(defaultInstruction)
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

    val rendered = remember(validDraft, previewTitle, previewOutput, fontCss, darkTheme, appLocale) {
        runCatching {
            validDraft?.let { draft ->
                DeepReadTemplateRenderer.renderCustom(
                    title = previewTitle,
                    output = previewOutput,
                    templateHtml = draft.html,
                    fontCss = fontCss,
                    darkTheme = darkTheme,
                    locale = appLocale,
                )
            } ?: DeepReadTemplateRenderer.renderEditorialSlant(
                title = previewTitle,
                output = previewOutput,
                fontCss = fontCss,
                darkTheme = darkTheme,
                locale = appLocale,
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
        val named = draft.copy(name = templateName.ifBlank { draft.name }.ifBlank { customTemplateName })
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
                    val title = previewUrl.toTemplateDemoTitle(demoTitleFallback)
                    previewTitle = title
                    previewOutput = sampleOutput
                    demoPreviewUrl = null
                    val result = deepReadAgent.runPreview(
                        topicTitle = title,
                        seedUrl = previewUrl,
                        locale = appLocale,
                    )
                    result.onSuccess { output ->
                        previewTitle = output.bestPreviewTitle(previewUrl) ?: title
                        previewOutput = output
                        demoPreviewUrl = previewUrl
                    }.onFailure { error ->
                        runError = error.message ?: demoFailureMessage
                    }
                } else {
                    val result = validDraft?.let { draft ->
                        templateAgent.reviseDraft(draft.copy(name = templateName), text, locale = appLocale)
                    } ?: templateAgent.generateDraft(templateName, text, locale = appLocale)
                    result.onSuccess(::applyDraft).onFailure { error ->
                        runError = error.message ?: templateFailureMessage
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                runError = error.message ?: templateFailureMessage
            } finally {
                busy = false
            }
        }
    }

    BackHandler { requestExit() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.deep_read_workbench_title)) },
                navigationIcon = {
                    TextButton(onClick = ::requestExit) {
                        Text(stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(
                        enabled = validDraft != null && !busy && !saving && validationError == null,
                        onClick = { showSaveDialog = true },
                    ) {
                        Text(
                            if (saving) stringResource(R.string.deep_read_workbench_saving)
                            else stringResource(R.string.common_save)
                        )
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
                    stringResource(R.string.deep_read_template_preview_unavailable),
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
                    val result = DeepReadTemplateDraftGuard.applySourceEdit(
                        currentDraft = validDraft,
                        name = templateName,
                        html = html,
                        locale = appLocale,
                    )
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
                        runError = error.message ?: templateSaveFailureMessage
                    }
                }
            },
        )
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text(stringResource(R.string.deep_read_workbench_discard_title)) },
            text = { Text(stringResource(R.string.deep_read_workbench_discard_message)) },
            confirmButton = {
                TextButton(onClick = { navController.popBackStack() }) {
                    Text(stringResource(R.string.deep_read_workbench_discard))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { showExitDialog = false }) {
                        Text(stringResource(R.string.deep_read_workbench_continue_editing))
                    }
                    TextButton(
                        enabled = validDraft != null && validationError == null && !busy && !saving,
                        onClick = {
                            showExitDialog = false
                            showSaveDialog = true
                        },
                    ) {
                        Text(stringResource(R.string.common_save))
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
                                previewUrl != null -> stringResource(R.string.deep_read_workbench_demo_label)
                                hasDraft -> stringResource(R.string.deep_read_workbench_edit_label)
                                else -> stringResource(R.string.deep_read_workbench_describe_label)
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
                            busy -> stringResource(R.string.deep_read_workbench_processing)
                            previewUrl != null -> stringResource(R.string.deep_read_workbench_preview)
                            hasDraft -> stringResource(R.string.deep_read_workbench_modify)
                            else -> stringResource(R.string.deep_read_workbench_generate)
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
                Text(stringResource(R.string.deep_read_workbench_source), style = MaterialTheme.typography.titleSmall)
                TextButton(enabled = editorText.isNotBlank(), onClick = onToggle) {
                    Text(
                        if (expanded) stringResource(R.string.deep_read_workbench_collapse)
                        else stringResource(R.string.deep_read_workbench_view_tune)
                    )
                }
            }
            if (expanded) {
                validationError?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } ?: Text(
                    stringResource(R.string.deep_read_workbench_validation_hint),
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
        title = { Text(stringResource(R.string.deep_read_workbench_save_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.deep_read_template_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = name.trim().isNotEmpty(), onClick = onSave) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
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
            remaining.contains("preview", ignoreCase = true) ||
            remaining.contains("demo", ignoreCase = true) ||
            remaining.contains("样稿") ||
            remaining.contains("sample", ignoreCase = true)
    return raw.takeIf { hasDemoIntent && it.isHttpOrHttpsUrl() }
}

private fun String.toTemplateDemoTitle(fallback: String = "News link demo"): String {
    val uri = runCatching { URI(this) }.getOrNull()
    val host = uri?.host?.removePrefix("www.") ?: return fallback
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
        .ifBlank { fallback }
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
