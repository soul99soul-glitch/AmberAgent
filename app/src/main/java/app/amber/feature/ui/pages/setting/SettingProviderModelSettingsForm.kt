package app.amber.feature.ui.pages.setting

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Trash2
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import app.amber.feature.ui.pages.setting.components.ProviderCommandButton
import app.amber.feature.ui.pages.setting.components.ProviderCard
import app.amber.feature.ui.pages.setting.components.ProviderGhostButton
import app.amber.feature.ui.pages.setting.components.ProviderHairline
import app.amber.feature.ui.pages.setting.components.ProviderLedgerRow
import app.amber.feature.ui.pages.setting.components.ProviderMonogram
import app.amber.feature.ui.pages.setting.components.ProviderSheetGrabber
import app.amber.feature.ui.pages.setting.components.ProviderSmallIconButton
import app.amber.feature.ui.pages.setting.components.ProviderSplitBar
import app.amber.feature.ui.pages.setting.components.ProviderSquareTag
import app.amber.feature.ui.pages.setting.components.ProviderTextField
import app.amber.feature.ui.pages.setting.components.ProviderToggle
import app.amber.feature.ui.pages.setting.components.providerSlugLabel
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.pages.setting.components.ProviderUnderlineTabs
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

// Editable identification templates, not a full client fingerprint.
private val modelUserAgentPresets = listOf(
    "Cursor" to "Cursor",
    "OpenCode" to "opencode/1.2.27",
)

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

/* v5 ledger: model editor bottom sheet — header (mono id + eyebrow) + form + split bar */
@Composable
internal fun ModelEditorSheet(
    model: Model,
    onModelChange: (Model) -> Unit,
    isEdit: Boolean,
    parentProvider: ProviderSetting?,
    slug: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val canConfirm = if (isEdit) {
        model.displayName.isNotBlank()
    } else {
        model.modelId.isNotBlank() && model.displayName.isNotBlank()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        containerColor = t.raised,
        dragHandle = { ProviderSheetGrabber() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text("//", style = type.eyebrow, color = t.accent)
                        Text(
                            text = "$slug / ${if (isEdit) "编辑" else "新建"}",
                            style = type.eyebrow,
                            color = t.ink3,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = model.modelId.ifBlank { "new-model" },
                        style = type.meta.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                        color = t.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ProviderGhostButton(
                    text = stringResource(R.string.setting_provider_page_save),
                    onClick = { if (canConfirm) onConfirm() },
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
            ) {
                ModelSettingsForm(
                    model = model,
                    onModelChange = onModelChange,
                    isEdit = isEdit,
                    parentProvider = parentProvider,
                )
            }

            ProviderSplitBar(
                cancelText = stringResource(R.string.cancel),
                onCancel = onDismiss,
                confirmText = stringResource(
                    if (isEdit) R.string.confirm else R.string.setting_provider_page_add
                ),
                onConfirm = onConfirm,
                confirmEnabled = canConfirm,
            )
        }
    }
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
    var contextWindowInput by remember(model.id, model.modelId) {
        mutableStateOf(model.contextWindowTokens?.formatContextWindowInput().orEmpty())
    }

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
        ProviderUnderlineTabs(
            tabs = listOf(
                stringResource(R.string.setting_provider_page_basic_settings),
                stringResource(R.string.setting_provider_page_advanced_settings),
                stringResource(R.string.setting_page_built_in_tools),
            ),
            selected = pagerState.currentPage,
            onSelect = { page ->
                scope.launch {
                    pagerState.animateScrollToPage(page)
                }
            },
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            when (page) {
                0 -> {
                    // 基本设置：身份、能力、上下文与模型类型/模态仍全部可编辑。
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(top = 8.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        ModelEditorIdentityCard(
                            model = model,
                            isEdit = isEdit,
                            parentProvider = parentProvider,
                            onModelIdChange = { setModelId(it.trim()) },
                            onDisplayNameChange = { onModelChange(model.copy(displayName = it)) },
                        )

                        ModelEditorSection("能力") {
                            ProviderCard(modifier = Modifier.fillMaxWidth()) {
                                ModelCapabilitySwitchRow(
                                    title = "视觉",
                                    machineLabel = "vision",
                                    checked = Modality.IMAGE in model.inputModalities,
                                    onCheckedChange = { enabled ->
                                        onModelChange(
                                            model.copy(
                                                inputModalities = model.inputModalities.withModality(
                                                    Modality.IMAGE,
                                                    enabled,
                                                )
                                            )
                                        )
                                    },
                                )
                                ProviderHairline()
                                ModelCapabilitySwitchRow(
                                    title = "工具",
                                    machineLabel = "tools",
                                    checked = ModelAbility.TOOL in model.abilities,
                                    onCheckedChange = { enabled ->
                                        onModelChange(
                                            model.copy(
                                                abilities = model.abilities.withAbility(
                                                    ModelAbility.TOOL,
                                                    enabled,
                                                )
                                            )
                                        )
                                    },
                                )
                                ProviderHairline()
                                ModelCapabilitySwitchRow(
                                    title = "推理",
                                    machineLabel = "reasoning",
                                    checked = ModelAbility.REASONING in model.abilities,
                                    onCheckedChange = { enabled ->
                                        onModelChange(
                                            model.copy(
                                                abilities = model.abilities.withAbility(
                                                    ModelAbility.REASONING,
                                                    enabled,
                                                )
                                            )
                                        )
                                    },
                                )
                                ProviderHairline()
                                ModelCapabilitySwitchRow(
                                    title = "图像",
                                    machineLabel = "image",
                                    checked = Modality.IMAGE in model.outputModalities,
                                    onCheckedChange = { enabled ->
                                        onModelChange(
                                            model.copy(
                                                outputModalities = model.outputModalities.withModality(
                                                    Modality.IMAGE,
                                                    enabled,
                                                )
                                            )
                                        )
                                    },
                                )
                            }
                        }

                        if (model.type == ModelType.CHAT) {
                            ModelEditorSection(stringResource(R.string.setting_provider_page_model_context_window)) {
                                ProviderCard(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.setting_provider_page_model_context_window),
                                            style = LocalAmberType.current.body,
                                            color = LocalAmberTokens.current.ink,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Box(Modifier.width(132.dp)) {
                                            ProviderTextField(
                                                value = contextWindowInput,
                                                onValueChange = {
                                                    contextWindowInput = it
                                                    onModelChange(model.copy(contextWindowTokens = parseContextWindowInput(it)))
                                                },
                                                placeholder = stringResource(R.string.setting_provider_page_model_context_window_placeholder),
                                                mono = true,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        ModelEditorSection(stringResource(R.string.setting_provider_page_basic_settings)) {
                            ProviderCard(modifier = Modifier.fillMaxWidth()) {
                                ProviderLedgerRow("model_type") {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        ModelType.entries.forEach { type ->
                                            ProviderSquareTag(
                                                text = type.name.lowercase(),
                                                selected = model.type == type,
                                                onClick = { onModelChange(model.copy(type = type)) },
                                            )
                                        }
                                    }
                                }
                                if (model.type == ModelType.CHAT) {
                                    ProviderLedgerRow("input_modalities") {
                                        ModalityTagRow(
                                            selected = model.inputModalities,
                                            onToggle = { modality ->
                                                onModelChange(model.copy(inputModalities = model.inputModalities.withModality(modality, modality !in model.inputModalities)))
                                            },
                                        )
                                    }
                                    ProviderLedgerRow("output_modalities") {
                                        ModalityTagRow(
                                            selected = model.outputModalities,
                                            onToggle = { modality ->
                                                onModelChange(model.copy(outputModalities = model.outputModalities.withModality(modality, modality !in model.outputModalities)))
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    // 高级设置页面
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(top = 8.dp, bottom = 24.dp),
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
private fun ModelEditorIdentityCard(
    model: Model,
    isEdit: Boolean,
    parentProvider: ProviderSetting? = null,
    onModelIdChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    ProviderCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = model.modelId.ifBlank { "new-model" },
                style = type.meta.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
                color = t.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                parentProvider?.let { provider ->
                    Text(
                        text = provider.providerSlugLabel(),
                        style = type.meta.copy(fontSize = 12.sp),
                        color = t.ink2,
                        maxLines = 1,
                    )
                    Text("·", style = type.meta, color = t.ink4)
                }
                parentProvider?.let { provider ->
                    Text(
                        text = if (provider.enabled) "已启用" else "未启用",
                        style = type.meta.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
                        color = if (provider.enabled) t.signal else t.ink3,
                    )
                }
            }
            ModelEditorFieldLabel("model_id")
            ProviderTextField(
                value = model.modelId,
                onValueChange = { if (!isEdit) onModelIdChange(it) },
                placeholder = stringResource(R.string.setting_provider_page_model_id_placeholder),
                mono = true,
                readOnly = isEdit,
            )
            ModelEditorFieldLabel("display_name")
            ProviderTextField(
                value = model.displayName,
                onValueChange = onDisplayNameChange,
                placeholder = stringResource(R.string.setting_provider_page_model_display_name_placeholder),
            )
        }
    }
}

@Composable
private fun ModelEditorFieldLabel(text: String) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Text(
        text = text.uppercase(),
        style = type.meta.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
        color = t.ink3,
    )
}

@Composable
private fun ModelEditorSection(
    title: String,
    content: @Composable () -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("//", style = type.eyebrow, color = t.accent)
            Text(
                text = title.uppercase(),
                style = type.eyebrow,
                color = t.ink2,
                maxLines = 1,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(t.line),
            )
        }
        content()
    }
}

@Composable
private fun ModelCapabilitySwitchRow(
    title: String,
    machineLabel: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .pressable(onClick = { onCheckedChange(!checked) })
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = type.body,
                color = t.ink,
                maxLines = 1,
            )
            Text(
                text = machineLabel,
                style = type.meta.copy(fontSize = 12.sp),
                color = t.ink3,
                maxLines = 1,
            )
        }
        ProviderToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun List<Modality>.withModality(modality: Modality, enabled: Boolean): List<Modality> =
    if (enabled) {
        if (modality in this) this else this + modality
    } else {
        filterNot { it == modality }
    }

private fun List<ModelAbility>.withAbility(ability: ModelAbility, enabled: Boolean): List<ModelAbility> =
    if (enabled) {
        if (ability in this) this else this + ability
    } else {
        filterNot { it == ability }
    }

@Composable
private fun ModalityTagRow(
    selected: List<Modality>,
    onToggle: (Modality) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Modality.entries.forEach { modality ->
            ProviderSquareTag(
                text = modality.name.lowercase(),
                selected = modality in selected,
                onClick = { onToggle(modality) },
            )
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
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ModelCustomHeaders(
    headers: List<CustomHeader>,
    onUpdate: (List<CustomHeader>) -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ModelAdvancedSectionLabel(
            text = stringResource(R.string.provider_custom_headers),
            count = headers.size,
        )

        Text(
            text = stringResource(R.string.provider_user_agent_presets),
            style = type.meta,
            color = t.ink3,
            modifier = Modifier.padding(top = 8.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            modelUserAgentPresets.forEach { (label, value) ->
                ProviderSquareTag(
                    text = label,
                    selected = headers.any {
                        it.name.equals("User-Agent", ignoreCase = true) && it.value == value
                    },
                    onClick = {
                        onUpdate(
                            headers.filterNot { it.name.equals("User-Agent", ignoreCase = true) } +
                                CustomHeader("User-Agent", value)
                        )
                    },
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }

        headers.forEachIndexed { index, header ->
            var headerName by remember(header.name) { mutableStateOf(header.name) }
            var headerValue by remember(header.value) { mutableStateOf(header.value) }

            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "HEADER ${index + 1}",
                        style = type.meta.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                        color = t.ink3,
                    )
                    ProviderSmallIconButton(
                        imageVector = Lucide.Trash2,
                        contentDescription = stringResource(R.string.provider_delete_header),
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
                    placeholder = stringResource(R.string.provider_header_name),
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
                    placeholder = stringResource(R.string.provider_header_value),
                    mono = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
                ProviderHairline(Modifier.padding(top = 12.dp))
            }
        }

        ProviderGhostButton(
            text = stringResource(R.string.provider_add_header),
            imageVector = Lucide.Plus,
            onClick = {
                val updatedHeaders = headers.toMutableList()
                updatedHeaders.add(CustomHeader("", ""))
                onUpdate(updatedHeaders)
            },
            modifier = Modifier.padding(top = 8.dp),
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ModelAdvancedSectionLabel(
            text = stringResource(R.string.provider_custom_bodies),
            count = customBodies.size,
        )

        customBodies.forEachIndexed { index, body ->
            var bodyKey by remember(body.key) { mutableStateOf(body.key) }
            var bodyValueString by remember(body.value) {
                mutableStateOf(providerModelJson.encodeToString(JsonElement.serializer(), body.value))
            }
            var jsonParseError by remember { mutableStateOf<String?>(null) }

            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "BODY ${index + 1}",
                        style = type.meta.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                        color = t.ink3,
                    )
                    ProviderSmallIconButton(
                        imageVector = Lucide.Trash2,
                        contentDescription = stringResource(R.string.provider_delete_body),
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
                    placeholder = stringResource(R.string.provider_body_key),
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
                                R.string.provider_invalid_json,
                                e.message?.take(100) ?: ""
                            )
                        }
                    },
                    placeholder = stringResource(R.string.provider_body_value),
                    mono = true,
                    singleLine = false,
                    minHeight = 98.dp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (jsonParseError != null) {
                    Text(
                        text = jsonParseError!!,
                        style = type.meta.copy(fontSize = 11.sp),
                        color = t.accent,
                    )
                }
                ProviderHairline(Modifier.padding(top = 12.dp))
            }
        }

        ProviderGhostButton(
            text = stringResource(R.string.provider_add_body),
            imageVector = Lucide.Plus,
            onClick = {
                val updatedBodies = customBodies.toMutableList()
                updatedBodies.add(CustomBody("", JsonPrimitive("")))
                onUpdate(updatedBodies)
            },
            modifier = Modifier.padding(top = 8.dp),
        )
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
            .padding(top = 8.dp, bottom = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.setting_page_built_in_tools_desc),
            style = type.secondary,
            color = t.ink3,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        val availableTools = listOf(
            BuiltInTools.Search to Pair(
                "search",
                stringResource(R.string.setting_page_built_in_tools_search_desc)
            ),
            BuiltInTools.UrlContext to Pair(
                "url_context",
                stringResource(R.string.setting_page_built_in_tools_url_context_desc)
            ),
            BuiltInTools.ImageGeneration to Pair(
                "image_generation",
                stringResource(R.string.setting_page_built_in_tools_image_generation_desc)
            )
        )

        availableTools.forEachIndexed { index, (tool, info) ->
            val (flag, description) = info
            if (index > 0) ProviderHairline()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(
                            when (tool) {
                                BuiltInTools.Search -> R.string.setting_page_built_in_tools_search
                                BuiltInTools.UrlContext -> R.string.setting_page_built_in_tools_url_context
                                BuiltInTools.ImageGeneration -> R.string.setting_page_built_in_tools_image_generation
                            }
                        ),
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
                ProviderSquareTag(
                    text = flag,
                    selected = tool in tools,
                    onClick = {
                        onUpdateTools(
                            if (tool in tools) tools - tool else tools + tool
                        )
                    },
                )
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

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
        }

        if (providerOverride != null) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
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
                        imageVector = Lucide.Pencil,
                        contentDescription = "Edit override",
                        onClick = {
                            editingProvider = providerOverride
                            showProviderConfig = true
                        },
                    )
                    ProviderSmallIconButton(
                        imageVector = Lucide.X,
                        contentDescription = "Remove override",
                        onClick = { onUpdateProviderOverride(null) },
                    )
                }
                ProviderHairline()
            }
        } else {
            ProviderGhostButton(
                text = stringResource(R.string.setting_provider_page_add_provider_override),
                imageVector = Lucide.Plus,
                accent = false,
                onClick = {
                    editingProvider = parentProvider?.copyProvider(
                        id = Uuid.random(),
                        builtIn = false,
                        models = emptyList(),
                        description = {},
                    )
                    showProviderConfig = true
                },
            )
            ProviderLedgerRow("base_url") {
                Text(
                    text = "› — (${stringResource(R.string.setting_subagent_value_inherit)})",
                    style = type.meta.copy(fontSize = 12.sp),
                    color = t.ink3,
                )
            }
            ProviderLedgerRow("api_key") {
                Text(
                    text = "› — (${stringResource(R.string.setting_subagent_value_inherit)})",
                    style = type.meta.copy(fontSize = 12.sp),
                    color = t.ink3,
                )
            }
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
                        style = type.screenTitle,
                        color = t.ink,
                    )

                    ProviderConsole(
                        provider = internalProvider,
                        onEdit = { internalProvider = it },
                        onCommit = { internalProvider = it },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 12.dp),
                    ) { currentProvider ->
                        ProviderSplitBar(
                            cancelText = stringResource(R.string.cancel),
                            onCancel = {
                                showProviderConfig = false
                                editingProvider = null
                            },
                            confirmText = stringResource(R.string.setting_provider_page_save),
                            onConfirm = {
                                onUpdateProviderOverride(currentProvider)
                                showProviderConfig = false
                                editingProvider = null
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
