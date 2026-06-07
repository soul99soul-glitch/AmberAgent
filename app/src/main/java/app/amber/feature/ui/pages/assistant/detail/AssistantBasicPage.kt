package app.amber.feature.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import app.amber.feature.ui.components.ui.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.ai.provider.ModelType
import app.amber.agent.R
import app.amber.core.model.MainAgentToolProfile
import app.amber.core.model.Assistant
import app.amber.core.model.withChatModelReasoningMemory
import app.amber.core.settings.Settings
import app.amber.core.settings.defaultReasoningLevelForModel
import app.amber.core.settings.findModelById
import app.amber.feature.ui.components.ai.ModelSelector
import app.amber.feature.ui.components.ai.ReasoningButton
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.FormItem
import app.amber.feature.ui.components.ui.Select
import app.amber.feature.ui.components.ui.Tag
import app.amber.feature.ui.components.ui.TagType
import app.amber.feature.ui.components.ui.TagsInput
import app.amber.feature.ui.components.ui.UIAvatar
import app.amber.feature.ui.hooks.heroAnimation
import app.amber.feature.ui.theme.CustomColors
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.core.utils.toFixed
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.roundToInt
import app.amber.core.model.Tag as DataTag

@Composable
fun AssistantBasicPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_basic))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantBasicContent(
            modifier = Modifier.padding(innerPadding),
            assistant = assistant,
            settings = settings,
            providers = providers,
            tags = tags,
            onUpdate = { vm.update(it) },
            vm = vm
        )
    }
}

private fun MainAgentToolProfile.label(): String = when (this) {
    MainAgentToolProfile.FULL -> "完整"
    MainAgentToolProfile.MINIMAL -> "最小"
    MainAgentToolProfile.WEB_READ -> "网页只读"
    MainAgentToolProfile.WORKSPACE_READ -> "文件只读"
    MainAgentToolProfile.CODING -> "编程"
    MainAgentToolProfile.MOBILE_CONTROL -> "手机控制"
}

@Composable
internal fun AssistantBasicContent(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    settings: Settings,
    providers: List<app.amber.ai.provider.ProviderSetting>,
    tags: List<DataTag>,
    onUpdate: (Assistant) -> Unit,
    vm: AssistantDetailVM
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            UIAvatar(
                value = assistant.avatar,
                name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                onUpdate = { avatar ->
                    onUpdate(
                        assistant.copy(
                            avatar = avatar
                        )
                    )
                },
                modifier = Modifier
                    .size(80.dp)
                    .heroAnimation("assistant_${assistant.id}")
            )
        }

        AmberCard {
            FormItem(
                label = {
                    Text(stringResource(R.string.assistant_page_name))
                },
                modifier = Modifier.padding(8.dp),

            ) {
                OutlinedTextField(
                    value = assistant.name,
                    onValueChange = {
                        onUpdate(
                            assistant.copy(
                                name = it
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Hairline()

            FormItem(
                label = {
                    Text(stringResource(R.string.assistant_page_tags))
                },
                modifier = Modifier.padding(8.dp),
            ) {
                TagsInput(
                    value = assistant.tags,
                    tags = tags,
                    onValueChange = { tagIds, tagList ->
                        vm.updateTags(tagIds, tagList)
                    },
                )
            }

            Hairline()

            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_use_assistant_avatar))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_use_assistant_avatar_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.useAssistantAvatar,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    useAssistantAvatar = it
                                )
                            )
                        }
                    )
                }
            )
        }

        AmberCard {
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_chat_model))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_chat_model_desc))
                },
                content = {
                    ModelSelector(
                        modelId = assistant.chatModelId,
                        providers = providers,
                        type = ModelType.CHAT,
                        onSelect = {
                            val currentModelId = assistant.chatModelId ?: settings.chatModelId
                            val currentModel = settings.findModelById(currentModelId)
                            onUpdate(
                                assistant.withChatModelReasoningMemory(
                                    currentModelId = currentModelId,
                                    currentDefaultReasoningLevel = currentModel
                                        ?.let { model -> settings.defaultReasoningLevelForModel(model) }
                                        ?: settings.defaultReasoningLevelForModel(it),
                                    selectedModelId = it.id,
                                    selectedDefaultReasoningLevel = settings.defaultReasoningLevelForModel(it),
                                )
                            )
                        },
                    )
                }
            )
            Hairline()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text("工具范围")
                },
                description = {
                    Text("限制此助手可使用的工具类别；不会自动启用未开启的本地工具。")
                },
                tail = {
                    Select(
                        options = MainAgentToolProfile.entries.toList(),
                        selectedOption = assistant.toolProfile,
                        onOptionSelected = { profile ->
                            onUpdate(assistant.copy(toolProfile = profile))
                        },
                        optionToString = { it.label() },
                    )
                }
            )
            Hairline()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_image_model))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_image_model_desc))
                },
                content = {
                    ModelSelector(
                        modelId = assistant.imageGenerationModelId,
                        providers = providers,
                        type = ModelType.IMAGE,
                        allowClear = true,
                        emptyLabel = stringResource(R.string.assistant_page_image_model_empty),
                        clearContentDescription = stringResource(R.string.assistant_page_image_model_clear),
                        onClear = {
                            onUpdate(assistant.copy(imageGenerationModelId = null))
                        },
                        onSelect = {
                            onUpdate(
                                assistant.copy(
                                    imageGenerationModelId = it.id
                                )
                            )
                        },
                    )
                }
            )
            Hairline()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_temperature))
                },
                tail = {
                    Switch(
                        checked = assistant.temperature != null,
                        onCheckedChange = { enabled ->
                            onUpdate(
                                assistant.copy(
                                    temperature = if (enabled) 1.0f else null
                                )
                            )
                        }
                    )
                }
            ) {
                val temperatureValue = assistant.temperature
                if (temperatureValue != null) {
                    Slider(
                        value = temperatureValue,
                        onValueChange = {
                            onUpdate(
                                assistant.copy(
                                    temperature = it.toFixed(2).toFloatOrNull() ?: 0.6f
                                )
                            )
                        },
                        valueRange = 0f..2f,
                        steps = 19,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val currentTemperature = assistant.temperature
                        val tagType = when (currentTemperature ?: 0f) {
                            in 0.0f..0.3f -> TagType.INFO
                            in 0.3f..1.0f -> TagType.SUCCESS
                            in 1.0f..1.5f -> TagType.WARNING
                            in 1.5f..2.0f -> TagType.ERROR
                            else -> TagType.ERROR
                        }
                        Tag(
                            type = TagType.INFO
                        ) {
                            Text(
                                text = "$currentTemperature",
                                style = LocalAmberType.current.meta,
                            )
                        }

                        Tag(
                            type = tagType
                        ) {
                            val temp = currentTemperature ?: 0f
                            Text(
                                text = when (temp) {
                                    in 0.0f..0.3f -> stringResource(R.string.assistant_page_strict)
                                    in 0.3f..1.0f -> stringResource(R.string.assistant_page_balanced)
                                    in 1.0f..1.5f -> stringResource(R.string.assistant_page_creative)
                                    in 1.5f..2.0f -> stringResource(R.string.assistant_page_chaotic)
                                    else -> "?"
                                }
                            )
                        }
                    }
                }
            }
            Hairline()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_top_p))
                },
                description = {
                    Text(
                        text = buildAnnotatedString {
                            append(stringResource(R.string.assistant_page_top_p_warning))
                        }
                    )
                },
                tail = {
                    Switch(
                        checked = assistant.topP != null,
                        onCheckedChange = { enabled ->
                            onUpdate(
                                assistant.copy(
                                    topP = if (enabled) 1.0f else null
                                )
                            )
                        }
                    )
                }
            ) {
                assistant.topP?.let { topP ->
                    Slider(
                        value = topP,
                        onValueChange = {
                            onUpdate(
                                assistant.copy(
                                    topP = it.toFixed(2).toFloatOrNull() ?: 1.0f
                                )
                            )
                        },
                        valueRange = 0f..1f,
                        steps = 0,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(
                            R.string.assistant_page_top_p_value,
                            topP.toString()
                        ),
                        style = LocalAmberType.current.meta,
                    )
                }
            }
            Hairline()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_context_message_size))
                },
                description = {
                    Text(
                        text = stringResource(R.string.assistant_page_context_message_desc),
                    )
                }
            ) {
                Slider(
                    value = assistant.contextMessageSize.toFloat(),
                    onValueChange = {
                        onUpdate(
                            assistant.copy(
                                contextMessageSize = it.roundToInt()
                            )
                        )
                    },
                    valueRange = 0f..512f,
                    steps = 0,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = if (assistant.contextMessageSize > 0) stringResource(
                        R.string.assistant_page_context_message_count,
                        assistant.contextMessageSize
                    ) else stringResource(R.string.assistant_page_context_message_unlimited),
                    style = LocalAmberType.current.meta,
                )
            }
            Hairline()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_stream_output))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_stream_output_desc))
                },
                tail = {
                    Switch(
                        checked = assistant.streamOutput,
                        onCheckedChange = {
                            onUpdate(
                                assistant.copy(
                                    streamOutput = it
                                )
                            )
                        }
                    )
                }
            )
            Hairline()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_thinking_budget))
                },
            ) {
                ReasoningButton(
                    reasoningLevel = assistant.reasoningLevel,
                    onUpdateReasoningLevel = { level ->
                        onUpdate(assistant.copy(reasoningLevel = level))
                    }
                )
            }
            Hairline()
            FormItem(
                modifier = Modifier.padding(8.dp),
                label = {
                    Text(stringResource(R.string.assistant_page_max_tokens))
                },
                description = {
                    Text(stringResource(R.string.assistant_page_max_tokens_desc))
                }
            ) {
                OutlinedTextField(
                    value = assistant.maxTokens?.toString() ?: "",
                    onValueChange = { text ->
                        val tokens = if (text.isBlank()) {
                            null
                        } else {
                            text.toIntOrNull()?.takeIf { it > 0 }
                        }
                        onUpdate(
                            assistant.copy(
                                maxTokens = tokens
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(stringResource(R.string.assistant_page_max_tokens_no_limit))
                    },
                    supportingText = {
                        val maxTokensValue = assistant.maxTokens
                        if (maxTokensValue != null) {
                            Text(stringResource(R.string.assistant_page_max_tokens_limit, maxTokensValue))
                        } else {
                            Text(stringResource(R.string.assistant_page_max_tokens_no_token_limit))
                        }
                    }
                )
            }
        }

        AmberCard {
            BackgroundPicker(
                modifier = Modifier.padding(8.dp),
                background = assistant.background,
                backgroundOpacity = assistant.backgroundOpacity,
                onUpdate = { background ->
                    onUpdate(
                        assistant.copy(
                            background = background
                        )
                    )
                }
            )

            if (assistant.background != null) {
                val backgroundOpacity = assistant.backgroundOpacity.coerceIn(0f, 1f)
                Hairline()
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text(stringResource(R.string.assistant_page_background_opacity))
                    },
                    description = {
                        Text(stringResource(R.string.assistant_page_background_opacity_desc))
                    }
                ) {
                    Slider(
                        value = backgroundOpacity,
                        onValueChange = {
                            onUpdate(
                                assistant.copy(
                                    backgroundOpacity = it.toFixed(2).toFloatOrNull()?.coerceIn(0f, 1f) ?: 1.0f
                                )
                            )
                        },
                        valueRange = 0f..1f,
                        steps = 19,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(
                            R.string.assistant_page_background_opacity_value,
                            (backgroundOpacity * 100).roundToInt()
                        ),
                        style = LocalAmberType.current.meta,
                    )
                }
            }
        }
    }
}
