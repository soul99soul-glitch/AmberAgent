package app.amber.feature.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import app.amber.ai.provider.BuiltInTools
import app.amber.ai.provider.CustomBody
import app.amber.ai.provider.CustomHeader
import app.amber.ai.provider.Modality
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.registry.ModelRegistry
import app.amber.agent.R
import app.amber.feature.ui.pages.setting.components.ProviderCard
import app.amber.feature.ui.pages.setting.components.ProviderCommandButton
import app.amber.feature.ui.pages.setting.components.ProviderMonogram
import app.amber.feature.ui.pages.setting.components.ProviderPillSeg
import app.amber.feature.ui.pages.setting.components.ProviderSegOption
import app.amber.feature.ui.pages.setting.components.ProviderSmallIconButton
import app.amber.feature.ui.pages.setting.components.ProviderTextField
import app.amber.feature.ui.pages.setting.components.ProviderToggle
import app.amber.feature.ui.pages.setting.components.toProviderMonogram
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid

private val providerModelJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
}

private fun parseContextWindowInput(input: String): Int? {
    val compact = input.trim()
        .replace(",", "")
        .replace("_", "")
        .replace(" ", "")
    if (compact.isBlank()) return null

    val multiplier = when {
        compact.endsWith("k", ignoreCase = true) -> 1_000.0
        compact.endsWith("m", ignoreCase = true) -> 1_000_000.0
        else -> 1.0
    }
    val number = compact
        .removeSuffix("K")
        .removeSuffix("k")
        .removeSuffix("M")
        .removeSuffix("m")
        .toDoubleOrNull()
        ?: return null
    return (number * multiplier)
        .coerceIn(1.0, Int.MAX_VALUE.toDouble())
        .toInt()
}

private fun Int.formatContextWindowInput(): String = when {
    this % 1_000_000 == 0 -> "${this / 1_000_000}M"
    this % 1_000 == 0 -> "${this / 1_000}K"
    else -> toString()
}

@Composable
internal fun ModelSettingsForm(
    model: Model,
    onModelChange: (Model) -> Unit,
    isEdit: Boolean,
    parentProvider: ProviderSetting? = null
) {
    val pagerState = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()

    fun setModelId(id: String) {
        val inputModality = ModelRegistry.MODEL_INPUT_MODALITIES.getData(id)
        val outputModality = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(id)
        val abilities = ModelRegistry.MODEL_ABILITIES.getData(id)
        val contextWindowTokens = ModelRegistry.MODEL_CONTEXT_WINDOW.getData(id)
        onModelChange(
            model.copy(
                modelId = id,
                displayName = id,
                inputModalities = inputModality,
                outputModalities = outputModality,
                abilities = abilities,
                contextWindowTokens = contextWindowTokens,
            )
        )
    }

    Column {
        ProviderPillSeg(
            options = listOf(
                ProviderSegOption(0, stringResource(R.string.setting_provider_page_basic_settings)),
                ProviderSegOption(1, stringResource(R.string.setting_provider_page_advanced_settings)),
                ProviderSegOption(2, stringResource(R.string.setting_page_built_in_tools)),
            ),
            selected = pagerState.currentPage,
            onSelected = { page ->
                scope.launch {
                    pagerState.animateScrollToPage(page)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            when (page) {
                0 -> {
                    // 基本设置页面
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        OutlinedTextField(
                            value = model.modelId,
                            onValueChange = {
                                if (!isEdit) {
                                    setModelId(it.trim())
                                }
                            },
                            label = { Text(stringResource(R.string.setting_provider_page_model_id)) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                if (!isEdit) {
                                    Text(stringResource(R.string.setting_provider_page_model_id_placeholder))
                                }
                            },
                            enabled = !isEdit
                        )

                        OutlinedTextField(
                            value = model.displayName,
                            onValueChange = {
                                onModelChange(model.copy(displayName = it.trim()))
                            },
                            label = { Text(stringResource(if (isEdit) R.string.setting_provider_page_model_name else R.string.setting_provider_page_model_display_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                if (!isEdit) {
                                    Text(stringResource(R.string.setting_provider_page_model_display_name_placeholder))
                                }
                            }
                        )

                        if (model.type == ModelType.CHAT) {
                            OutlinedTextField(
                                value = model.contextWindowTokens?.formatContextWindowInput().orEmpty(),
                                onValueChange = {
                                    onModelChange(model.copy(contextWindowTokens = parseContextWindowInput(it)))
                                },
                                label = { Text(stringResource(R.string.setting_provider_page_model_context_window)) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = {
                                    Text(stringResource(R.string.setting_provider_page_model_context_window_placeholder))
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                singleLine = true,
                            )
                        }

                        ModelTypeSelector(
                            selectedType = model.type,
                            onTypeSelected = {
                                onModelChange(model.copy(type = it))
                            }
                        )

                        ModelModalitySelector(
                            model = model,
                            inputModalities = model.inputModalities,
                            onUpdateInputModalities = {
                                onModelChange(model.copy(inputModalities = it))
                            },
                            outputModalities = model.outputModalities,
                            onUpdateOutputModalities = {
                                onModelChange(model.copy(outputModalities = it))
                            }
                        )

                        if (model.type == ModelType.CHAT) {
                            ModalAbilitySelector(
                                abilities = model.abilities,
                                onUpdateAbilities = {
                                    onModelChange(model.copy(abilities = it))
                                }
                            )
                        }
                    }
                }

                1 -> {
                    // 高级设置页面
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(top = 16.dp, bottom = 80.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ProviderOverrideSettings(
                            providerOverride = model.providerOverwrite,
                            onUpdateProviderOverride = { providerOverride ->
                                onModelChange(model.copy(providerOverwrite = providerOverride))
                            },
                            parentProvider = parentProvider
                        )

                        ModelCustomHeaders(
                            headers = model.customHeaders,
                            onUpdate = { headers ->
                                onModelChange(model.copy(customHeaders = headers))
                            }
                        )

                        ModelCustomBodies(
                            customBodies = model.customBodies,
                            onUpdate = { bodies ->
                                onModelChange(model.copy(customBodies = bodies))
                            }
                        )
                    }
                }

                2 -> {
                    // 内置工具页面
                    BuiltInToolsSettings(
                        tools = model.tools,
                        onUpdateTools = { tools ->
                            onModelChange(model.copy(tools = tools))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelAdvancedSectionLabel(
    text: String,
    count: Int,
    modifier: Modifier = Modifier,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text("//", style = type.eyebrow, color = t.accent)
        Text(
            text = text.uppercase(),
            style = type.eyebrow,
            color = t.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text("· $count", style = type.meta.copy(fontSize = 11.sp), color = t.ink4)
    }
}

@Composable
private fun ModelCustomHeaders(
    headers: List<CustomHeader>,
    onUpdate: (List<CustomHeader>) -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ModelAdvancedSectionLabel(
            text = stringResource(R.string.assistant_page_custom_headers),
            count = headers.size,
        )

        headers.forEachIndexed { index, header ->
            var headerName by remember(header.name) { mutableStateOf(header.name) }
            var headerValue by remember(header.value) { mutableStateOf(header.value) }

            ProviderCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "HEADER ${index + 1}",
                            style = type.meta.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                            color = t.ink3,
                        )
                        ProviderSmallIconButton(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.assistant_page_delete_header),
                            onClick = {
                                val updatedHeaders = headers.toMutableList()
                                updatedHeaders.removeAt(index)
                                onUpdate(updatedHeaders)
                            },
                        )
                    }
                    ProviderTextField(
                        value = headerName,
                        onValueChange = {
                            headerName = it
                            val updatedHeaders = headers.toMutableList()
                            updatedHeaders[index] = updatedHeaders[index].copy(name = it.trim())
                            onUpdate(updatedHeaders)
                        },
                        placeholder = stringResource(R.string.assistant_page_header_name),
                        mono = true,
                    )
                    ProviderTextField(
                        value = headerValue,
                        onValueChange = {
                            headerValue = it
                            val updatedHeaders = headers.toMutableList()
                            updatedHeaders[index] = updatedHeaders[index].copy(value = it.trim())
                            onUpdate(updatedHeaders)
                        },
                        placeholder = stringResource(R.string.assistant_page_header_value),
                        mono = true,
                    )
                }
            }
        }

        ProviderCommandButton(
            text = stringResource(R.string.assistant_page_add_header),
            imageVector = HugeIcons.Add01,
            onClick = {
                val updatedHeaders = headers.toMutableList()
                updatedHeaders.add(CustomHeader("", ""))
                onUpdate(updatedHeaders)
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ModelCustomBodies(
    customBodies: List<CustomBody>,
    onUpdate: (List<CustomBody>) -> Unit,
) {
    val context = LocalContext.current
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ModelAdvancedSectionLabel(
            text = stringResource(R.string.assistant_page_custom_bodies),
            count = customBodies.size,
        )

        customBodies.forEachIndexed { index, body ->
            var bodyKey by remember(body.key) { mutableStateOf(body.key) }
            var bodyValueString by remember(body.value) {
                mutableStateOf(providerModelJson.encodeToString(JsonElement.serializer(), body.value))
            }
            var jsonParseError by remember { mutableStateOf<String?>(null) }

            ProviderCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "BODY ${index + 1}",
                            style = type.meta.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                            color = t.ink3,
                        )
                        ProviderSmallIconButton(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.assistant_page_delete_body),
                            onClick = {
                                val updatedBodies = customBodies.toMutableList()
                                updatedBodies.removeAt(index)
                                onUpdate(updatedBodies)
                            },
                        )
                    }
                    ProviderTextField(
                        value = bodyKey,
                        onValueChange = {
                            bodyKey = it
                            val updatedBodies = customBodies.toMutableList()
                            updatedBodies[index] = updatedBodies[index].copy(key = it.trim())
                            onUpdate(updatedBodies)
                        },
                        placeholder = stringResource(R.string.assistant_page_body_key),
                        mono = true,
                    )
                    ProviderTextField(
                        value = bodyValueString,
                        onValueChange = { newString ->
                            bodyValueString = newString
                            try {
                                val newJsonValue = providerModelJson.parseToJsonElement(newString)
                                val updatedBodies = customBodies.toMutableList()
                                updatedBodies[index] = updatedBodies[index].copy(value = newJsonValue)
                                onUpdate(updatedBodies)
                                jsonParseError = null
                            } catch (e: Exception) {
                                jsonParseError = context.getString(
                                    R.string.assistant_page_invalid_json,
                                    e.message?.take(100) ?: ""
                                )
                            }
                        },
                        placeholder = stringResource(R.string.assistant_page_body_value),
                        mono = true,
                        singleLine = false,
                        minHeight = 98.dp,
                    )
                    if (jsonParseError != null) {
                        Text(
                            text = jsonParseError!!,
                            style = type.meta.copy(fontSize = 11.sp),
                            color = t.accent,
                        )
                    }
                }
            }
        }

        ProviderCommandButton(
            text = stringResource(R.string.assistant_page_add_body),
            imageVector = HugeIcons.Add01,
            onClick = {
                val updatedBodies = customBodies.toMutableList()
                updatedBodies.add(CustomBody("", JsonPrimitive("")))
                onUpdate(updatedBodies)
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ModelTypeSelector(
    selectedType: ModelType,
    onTypeSelected: (ModelType) -> Unit
) {
    Text(
        stringResource(R.string.setting_provider_page_model_type),
        style = MaterialTheme.typography.titleSmall
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth()
    ) {
        ModelType.entries.forEachIndexed { index, type ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index, ModelType.entries.size),
                label = {
                    Text(
                        text = stringResource(
                            when (type) {
                                ModelType.CHAT -> R.string.setting_provider_page_chat_model
                                ModelType.EMBEDDING -> R.string.setting_provider_page_embedding_model
                                ModelType.IMAGE -> R.string.setting_provider_page_image_model
                            }
                        )
                    )
                },
                selected = selectedType == type,
                onClick = { onTypeSelected(type) }
            )
        }
    }
}

@Composable
private fun ModelModalitySelector(
    model: Model,
    inputModalities: List<Modality>,
    onUpdateInputModalities: (List<Modality>) -> Unit,
    outputModalities: List<Modality>,
    onUpdateOutputModalities: (List<Modality>) -> Unit
) {
    if (model.type == ModelType.CHAT) {
        Text(
            stringResource(R.string.setting_provider_page_input_modality),
            style = MaterialTheme.typography.titleSmall
        )
        MultiChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Modality.entries.forEachIndexed { index, modality ->
                SegmentedButton(
                    checked = modality in inputModalities,
                    shape = SegmentedButtonDefaults.itemShape(index, Modality.entries.size),
                    onCheckedChange = {
                        if (it) {
                            onUpdateInputModalities(inputModalities + modality)
                        } else {
                            onUpdateInputModalities(inputModalities - modality)
                        }
                    }
                ) {
                    Text(
                        text = stringResource(
                            when (modality) {
                                Modality.TEXT -> R.string.setting_provider_page_text
                                Modality.IMAGE -> R.string.setting_provider_page_image
                                Modality.AUDIO -> R.string.setting_provider_page_audio
                            }
                        )
                    )
                }
            }
        }

        Text(
            stringResource(R.string.setting_provider_page_output_modality),
            style = MaterialTheme.typography.titleSmall
        )
        MultiChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Modality.entries.forEachIndexed { index, modality ->
                SegmentedButton(
                    checked = modality in outputModalities,
                    shape = SegmentedButtonDefaults.itemShape(index, Modality.entries.size),
                    onCheckedChange = {
                        if (it) {
                            onUpdateOutputModalities(outputModalities + modality)
                        } else {
                            onUpdateOutputModalities(outputModalities - modality)
                        }
                    }
                ) {
                    Text(
                        text = stringResource(
                            when (modality) {
                                Modality.TEXT -> R.string.setting_provider_page_text
                                Modality.IMAGE -> R.string.setting_provider_page_image
                                Modality.AUDIO -> R.string.setting_provider_page_audio
                            }
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun ModalAbilitySelector(
    abilities: List<ModelAbility>,
    onUpdateAbilities: (List<ModelAbility>) -> Unit
) {
    Text(
        stringResource(R.string.setting_provider_page_abilities),
        style = MaterialTheme.typography.titleSmall
    )
    MultiChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth(),
    ) {
        ModelAbility.entries.forEachIndexed { index, ability ->
            SegmentedButton(
                checked = ability in abilities,
                shape = SegmentedButtonDefaults.itemShape(index, ModelAbility.entries.size),
                onCheckedChange = {
                    if (it) {
                        onUpdateAbilities(abilities + ability)
                    } else {
                        onUpdateAbilities(abilities - ability)
                    }
                },
                label = {
                    Text(
                        text = stringResource(
                            when (ability) {
                                ModelAbility.TOOL -> R.string.setting_provider_page_tool
                                ModelAbility.REASONING -> R.string.setting_provider_page_reasoning
                            }
                        )
                    )
                }
            )
        }
    }
}

@Composable
private fun BuiltInToolsSettings(
    tools: Set<BuiltInTools>,
    onUpdateTools: (Set<BuiltInTools>) -> Unit
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.setting_page_built_in_tools),
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = stringResource(R.string.setting_page_built_in_tools_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val availableTools = listOf(
            BuiltInTools.Search to Pair(
                stringResource(R.string.setting_page_built_in_tools_search),
                stringResource(R.string.setting_page_built_in_tools_search_desc)
            ),
            BuiltInTools.UrlContext to Pair(
                stringResource(R.string.setting_page_built_in_tools_url_context),
                stringResource(R.string.setting_page_built_in_tools_url_context_desc)
            ),
            BuiltInTools.ImageGeneration to Pair(
                stringResource(R.string.setting_page_built_in_tools_image_generation),
                stringResource(R.string.setting_page_built_in_tools_image_generation_desc)
            )
        )

        availableTools.forEach { (tool, info) ->
            val (title, description) = info
            ProviderCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(13.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = title,
                            style = type.body.copy(fontWeight = FontWeight.SemiBold),
                            color = t.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = description,
                            style = type.secondary.copy(fontSize = 12.sp),
                            color = t.ink3,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ProviderToggle(
                        checked = tool in tools,
                        onCheckedChange = { checked ->
                            if (checked) {
                                onUpdateTools(tools + tool)
                            } else {
                                onUpdateTools(tools - tool)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderOverrideSettings(
    providerOverride: ProviderSetting?,
    onUpdateProviderOverride: (ProviderSetting?) -> Unit,
    parentProvider: ProviderSetting?
) {
    var showProviderConfig by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderSetting?>(null) }
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.setting_provider_page_provider_override),
            style = type.body.copy(fontWeight = FontWeight.SemiBold),
            color = t.ink,
        )

        Text(
            text = stringResource(R.string.setting_provider_page_provider_override_desc),
            style = type.secondary,
            color = t.ink3,
        )

        if (providerOverride != null) {
            ProviderCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProviderMonogram(
                        text = providerOverride.name.toProviderMonogram(),
                        size = 34.dp,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = providerOverride.name,
                            style = type.body.copy(fontWeight = FontWeight.SemiBold),
                            color = t.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "override",
                            style = type.meta.copy(fontSize = 11.sp),
                            color = t.ink4,
                        )
                    }
                    ProviderSmallIconButton(
                        imageVector = HugeIcons.Edit01,
                        contentDescription = "Edit override",
                        onClick = {
                            editingProvider = providerOverride
                            showProviderConfig = true
                        },
                    )
                    ProviderSmallIconButton(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = "Remove override",
                        onClick = { onUpdateProviderOverride(null) },
                    )
                }
            }
        } else {
            ProviderCommandButton(
                text = stringResource(R.string.setting_provider_page_add_provider_override),
                imageVector = HugeIcons.Add01,
                onClick = {
                    editingProvider = parentProvider?.copyProvider(
                        id = Uuid.random(),
                        builtIn = false,
                        models = emptyList(),
                        description = {},
                    )
                    showProviderConfig = true
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (showProviderConfig && editingProvider != null) {
            ModalBottomSheet(
                onDismissRequest = {
                    showProviderConfig = false
                    editingProvider = null
                },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = t.bg,
            ) {
                var internalProvider by remember(editingProvider) { mutableStateOf(editingProvider!!) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.92f)
                        .padding(horizontal = 18.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_configure_provider_override),
                        style = type.screenTitle.copy(fontSize = 22.sp),
                        color = t.ink,
                    )

                    ProviderConsole(
                        provider = internalProvider,
                        onEdit = { internalProvider = it },
                        onCommit = { internalProvider = it },
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
                    ) { currentProvider ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ProviderCommandButton(
                                text = stringResource(R.string.cancel),
                                onClick = {
                                    showProviderConfig = false
                                    editingProvider = null
                                },
                                modifier = Modifier.weight(1f),
                            )
                            ProviderCommandButton(
                                text = stringResource(R.string.setting_provider_page_save),
                                onClick = {
                                    onUpdateProviderOverride(currentProvider)
                                    showProviderConfig = false
                                    editingProvider = null
                                },
                                accent = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}
