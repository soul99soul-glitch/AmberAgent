package app.amber.feature.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ProviderManager
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.agent.R
import app.amber.core.utils.UiState
import app.amber.feature.ui.components.ai.ModelSelector
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Connect
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun ProviderConnectionTester(
    internalProvider: ProviderSetting,
) {
    var showTestDialog by remember { mutableStateOf(false) }
    val providerManager = koinInject<ProviderManager>()
    val scope = rememberCoroutineScope()

    ProviderIconButton(
        imageVector = HugeIcons.Connect,
        contentDescription = null,
        tint = LocalAmberTokens.current.ink3,
        onClick = { showTestDialog = true },
    )

    if (showTestDialog) {
        var model by remember(internalProvider) {
            mutableStateOf(internalProvider.models.firstOrNull { it.type == ModelType.CHAT })
        }
        var nonStreamingState: UiState<String> by remember { mutableStateOf(UiState.Idle) }
        var streamingState: UiState<String> by remember { mutableStateOf(UiState.Idle) }
        var toolsState: UiState<String> by remember { mutableStateOf(UiState.Idle) }
        var streamingText by remember { mutableStateOf("") }

        fun resetStates() {
            nonStreamingState = UiState.Idle
            streamingState = UiState.Idle
            toolsState = UiState.Idle
            streamingText = ""
        }

        fun runTests() {
            val selectedModel = model ?: return
            val provider = providerManager.getProviderByType(internalProvider)
            resetStates()
            scope.launch {
                launch {
                    runCatching {
                        nonStreamingState = UiState.Loading
                        val chunk = provider.generateText(
                            providerSetting = internalProvider,
                            messages = listOf(UIMessage.system("You are a helpful assistant"), UIMessage.user("hello")),
                            params = TextGenerationParams(
                                model = selectedModel,
                                customHeaders = selectedModel.customHeaders,
                                customBody = selectedModel.customBodies,
                            ),
                        )
                        val text = chunk.choices.firstOrNull()?.message?.parts
                            ?.filterIsInstance<UIMessagePart.Text>()
                            ?.joinToString("") { it.text } ?: ""
                        nonStreamingState = UiState.Success(text)
                    }.onFailure { nonStreamingState = UiState.Error(it) }
                }
                launch {
                    runCatching {
                        streamingState = UiState.Loading
                        val flow = provider.streamText(
                            providerSetting = internalProvider,
                            messages = listOf(UIMessage.system("You are a helpful assistant"), UIMessage.user("hello")),
                            params = TextGenerationParams(
                                model = selectedModel,
                                customHeaders = selectedModel.customHeaders,
                                customBody = selectedModel.customBodies,
                            ),
                        )
                        flow.collect { chunk ->
                            chunk.choices.firstOrNull()?.delta?.parts
                                ?.filterIsInstance<UIMessagePart.Text>()
                                ?.forEach { streamingText += it.text }
                        }
                        streamingState = UiState.Success("")
                    }.onFailure { streamingState = UiState.Error(it) }
                }
                launch {
                    runCatching {
                        toolsState = UiState.Loading
                        val testTool = Tool(
                            name = "get_current_time",
                            description = "Get the current date and time.",
                            execute = { emptyList() },
                        )
                        val chunk = provider.generateText(
                            providerSetting = internalProvider,
                            messages = listOf(
                                UIMessage.system("You are a helpful assistant"),
                                UIMessage.user("Use the get_current_time tool."),
                            ),
                            params = TextGenerationParams(
                                model = selectedModel,
                                tools = listOf(testTool),
                                customHeaders = selectedModel.customHeaders,
                                customBody = selectedModel.customBodies,
                            ),
                        )
                        val message = chunk.choices.firstOrNull()?.message
                        val toolCall = message?.parts
                            ?.filterIsInstance<UIMessagePart.Tool>()
                            ?.firstOrNull()
                        val result = if (toolCall != null) {
                            "调用: ${toolCall.toolName}  入参: ${toolCall.input}"
                        } else {
                            val text = message?.parts
                                ?.filterIsInstance<UIMessagePart.Text>()
                                ?.joinToString("") { it.text } ?: ""
                            "未调用工具，响应: $text"
                        }
                        toolsState = UiState.Success(result)
                    }.onFailure { toolsState = UiState.Error(it) }
                }
            }
        }

        ProviderConnectionDialog(
            modelId = model?.id,
            provider = internalProvider,
            nonStreamingState = nonStreamingState,
            streamingState = streamingState,
            toolsState = toolsState,
            streamingText = streamingText,
            onModelChange = { model = it },
            onDismiss = { showTestDialog = false },
            onTest = ::runTests,
        )
    }
}

@Composable
private fun ProviderConnectionDialog(
    modelId: Uuid?,
    provider: ProviderSetting,
    nonStreamingState: UiState<String>,
    streamingState: UiState<String>,
    toolsState: UiState<String>,
    streamingText: String,
    onModelChange: (Model) -> Unit,
    onDismiss: () -> Unit,
    onTest: () -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Dialog(onDismissRequest = onDismiss) {
        ProviderCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_provider_page_test_connection),
                    style = type.body.copy(fontWeight = FontWeight.Bold),
                    color = t.ink,
                )
                ModelSelector(
                    modelId = modelId,
                    providers = listOf(provider),
                    type = ModelType.CHAT,
                    modifier = Modifier.fillMaxWidth(),
                    onSelect = onModelChange,
                )
                TestResultItem(
                    label = "非流式",
                    state = nonStreamingState,
                    resultText = (nonStreamingState as? UiState.Success)?.data ?: "",
                )
                TestResultItem(
                    label = "流式",
                    state = streamingState,
                    resultText = streamingText,
                )
                TestResultItem(
                    label = "工具调用",
                    state = toolsState,
                    resultText = (toolsState as? UiState.Success)?.data ?: "",
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    ProviderCommandButton(
                        text = stringResource(R.string.cancel),
                        onClick = onDismiss,
                    )
                    ProviderCommandButton(
                        text = stringResource(R.string.setting_provider_page_test),
                        onClick = onTest,
                        accent = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun TestResultItem(
    label: String,
    state: UiState<String>,
    resultText: String,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    var showErrorSheet by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = type.meta.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = t.ink3,
            modifier = Modifier.width(64.dp),
        )
        when (state) {
            is UiState.Idle -> Text(
                text = "—",
                style = type.meta,
                color = t.ink4,
            )
            is UiState.Loading -> LinearProgressIndicator(
                modifier = Modifier.weight(1f),
                color = t.accent,
                trackColor = t.line2,
            )
            is UiState.Success -> Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "ok",
                    style = type.meta.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    color = t.signal,
                )
                if (resultText.isNotBlank()) {
                    Text(
                        text = resultText,
                        style = type.meta.copy(fontSize = 11.sp),
                        color = t.ink3,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            is UiState.Error -> Text(
                text = state.error.message ?: "Error",
                style = type.meta.copy(fontSize = 11.sp),
                color = t.accent,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .pressable(onClick = { showErrorSheet = true }),
            )
        }
    }

    if (showErrorSheet && state is UiState.Error) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val stackTrace = remember(state.error) { state.error.stackTraceToString() }
        ModalBottomSheet(
            onDismissRequest = { showErrorSheet = false },
            sheetState = sheetState,
            containerColor = t.bg,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.8f)
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(label, style = type.body.copy(fontWeight = FontWeight.Bold), color = t.ink)
                Text(
                    text = state.error.message ?: "Error",
                    style = type.secondary,
                    color = t.accent,
                )
                Text(
                    text = stackTrace,
                    style = type.meta.copy(fontSize = 10.5.sp),
                    color = t.ink3,
                )
            }
        }
    }
}
