package app.amber.feature.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.Search01
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastFilter
import kotlinx.coroutines.launch
import app.amber.ai.provider.Modality
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderManager
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.providers.isCodexOAuthReviewModel
import app.amber.ai.registry.ModelRegistry
import app.amber.agent.R
import app.amber.feature.ui.components.ai.ModelAbilityTag
import app.amber.feature.ui.components.ai.ModelModalityTag
import app.amber.feature.ui.components.ai.ModelTypeTag
import app.amber.feature.ui.components.ui.AutoAIIcon
import app.amber.feature.ui.components.ui.Tag
import app.amber.feature.ui.components.ui.TagType
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.hooks.useEditState
import app.amber.feature.ui.pages.setting.components.ProviderCapFlags
import app.amber.feature.ui.pages.setting.components.ProviderCard
import app.amber.feature.ui.pages.setting.components.ProviderCommandButton
import app.amber.feature.ui.pages.setting.components.ProviderHairline
import app.amber.feature.ui.pages.setting.components.ProviderIconButton
import app.amber.feature.ui.pages.setting.components.ProviderMonogram
import app.amber.feature.ui.pages.setting.components.ProviderSearchField
import app.amber.feature.ui.pages.setting.components.ProviderSectionLabel
import app.amber.feature.ui.pages.setting.components.toContextLabel
import app.amber.feature.ui.pages.setting.components.toProviderMonogram
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.core.utils.plus
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
internal fun SettingProviderModelPage(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit
) {
    ModelList(
        providerSetting = provider,
        onUpdateProvider = onEdit
    )
}

@Composable
private fun ModelList(
    providerSetting: ProviderSetting,
    onUpdateProvider: (ProviderSetting) -> Unit
) {
    val providerManager = koinInject<ProviderManager>()
    val requestKey = remember(providerSetting) { providerSetting.modelListRequestKey() }
    val modelList by produceState(emptyList(), requestKey) {
        runCatching {
            println("loading models...")
            value = providerManager.getProviderByType(providerSetting)
                .listModels(providerSetting)
                .sortedBy { it.modelId }
                .toList()
        }.onFailure {
            it.printStackTrace()
        }
    }
    LaunchedEffect(providerSetting, modelList) {
        if (
            providerSetting is ProviderSetting.OpenAI &&
            providerSetting.authMode == OpenAIAuthMode.CODEX_OAUTH &&
            providerSetting.models.any { it.isCodexOAuthReviewModel() }
        ) {
            // One-shot cleanup: drop review variants if any sneaked into the user's selection
            // from an old Codex response. We do NOT touch the rest — and we explicitly DO NOT
            // auto-fill on `models.isEmpty()` (an earlier version did, which made the
            // "deselect all" gesture useless because the next compose pass refilled it).
            // Keeping the user's empty selection empty is correct: the candidates live in
            // `modelList` / the available-models sheet, and the user picks from there.
            val filtered = providerSetting.models.filterNot { it.isCodexOAuthReviewModel() }
            onUpdateProvider(providerSetting.copy(models = filtered))
        }
    }
    var expanded by rememberSaveable { mutableStateOf(true) }
    val lazyListState = rememberLazyListState()
    val modelItemOffset = 1
    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromModelIndex = from.index - modelItemOffset
        val toModelIndex = to.index - modelItemOffset
        if (fromModelIndex in providerSetting.models.indices && toModelIndex in providerSetting.models.indices) {
            onUpdateProvider(providerSetting.moveMove(fromModelIndex, toModelIndex))
        }
    }
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(t.bg)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp) + PaddingValues(bottom = 112.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            state = lazyListState
        ) {
            item("enabled_models_label") {
                ProviderSectionLabel("已启用模型", count = providerSetting.models.size)
            }
            // 模型列表
            if (providerSetting.models.isEmpty()) {
                item {
                    ProviderCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.setting_provider_page_no_models),
                                style = type.body.copy(fontWeight = FontWeight.SemiBold),
                                color = t.ink2,
                            )
                            Text(
                                text = stringResource(R.string.setting_provider_page_add_models_hint),
                                style = type.secondary,
                                color = t.ink3,
                            )
                        }
                    }
                }
            } else {
                items(providerSetting.models, key = { it.id }) { item ->
                    ReorderableItem(
                        state = reorderableLazyListState,
                        key = item.id
                    ) { isDragging ->
                        ModelCard(
                            model = item,
                            onDelete = {
                                onUpdateProvider(providerSetting.delModel(item))
                            },
                            onEdit = { editedModel ->
                                onUpdateProvider(providerSetting.editModel(editedModel))
                            },
                            parentProvider = providerSetting,
                            modifier = Modifier
                                .longPressDraggableHandle()
                                .graphicsLayer {
                                    if (isDragging) {
                                        scaleX = 1.05f
                                        scaleY = 1.05f
                                    } else {
                                        scaleX = 1f
                                        scaleY = 1f
                                    }
                                },
                        )
                    }
                }
            }
        }
        ProviderCard(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            AddModelButton(
                models = modelList,
                selectedModels = providerSetting.models,
                onAddModel = {
                    onUpdateProvider(providerSetting.addModel(it))
                },
                onRemoveModel = {
                    onUpdateProvider(providerSetting.delModel(it))
                },
                expanded = expanded,
                parentProvider = providerSetting,
                onUpdateProvider = onUpdateProvider
            )
        }
    }
}

private fun ProviderSetting.modelListRequestKey(): ProviderModelListRequestKey {
    return when (this) {
        is ProviderSetting.OpenAI -> ProviderModelListRequestKey(
            type = "openai",
            id = id.toString(),
            credentialsHash = apiKey.hashCode(),
            baseUrl = baseUrl,
            authMode = authMode.name,
            extra = "$chatCompletionsPath|$useResponseApi|${brand.name}",
        )

        is ProviderSetting.Google -> ProviderModelListRequestKey(
            type = "google",
            id = id.toString(),
            credentialsHash = "$apiKey|$privateKey".hashCode(),
            baseUrl = baseUrl,
            authMode = authMode.name,
            extra = "$vertexAI|$useServiceAccount|$serviceAccountEmail|$location|$projectId",
        )

        is ProviderSetting.Claude -> ProviderModelListRequestKey(
            type = "claude",
            id = id.toString(),
            credentialsHash = apiKey.hashCode(),
            baseUrl = baseUrl,
            authMode = "",
            extra = promptCaching.toString(),
        )
    }
}

private data class ProviderModelListRequestKey(
    val type: String,
    val id: String,
    val credentialsHash: Int,
    val baseUrl: String,
    val authMode: String,
    val extra: String,
)

@Composable
private fun AddModelButton(
    models: List<Model>,
    selectedModels: List<Model>,
    expanded: Boolean,
    onAddModel: (Model) -> Unit,
    onRemoveModel: (Model) -> Unit,
    parentProvider: ProviderSetting,
    onUpdateProvider: (ProviderSetting) -> Unit
) {
    val dialogState = useEditState<Model> { onAddModel(it) }
    val scope = rememberCoroutineScope()
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModelPicker(
            models = models,
            selectedModels = selectedModels,
                onModelSelected = { model ->
                    val inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(model.modelId)
                    val outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(model.modelId)
                    val abilities = ModelRegistry.MODEL_ABILITIES.getData(model.modelId)
                    val contextWindowTokens = ModelRegistry.MODEL_CONTEXT_WINDOW.getData(model.modelId)
                    onAddModel(
                        model.copy(
                            inputModalities = inputModalities,
                            outputModalities = outputModalities,
                            abilities = abilities,
                            contextWindowTokens = contextWindowTokens,
                        )
                    )
                },
            onModelDeselected = { model ->
                onRemoveModel(model)
            },
            onAllModelSelected = {
                onUpdateProvider(
                    parentProvider.copyProvider(
                        models = parentProvider.models + it.filterNot { model ->
                            parentProvider is ProviderSetting.OpenAI &&
                                parentProvider.authMode == OpenAIAuthMode.CODEX_OAUTH &&
                                model.isCodexOAuthReviewModel()
                        }.filter { model ->
                            parentProvider.models.none { existing -> existing.modelId == model.modelId }
                        }.map { model ->
                            model.copy(
                                inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(model.modelId),
                                outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(model.modelId),
                                abilities = ModelRegistry.MODEL_ABILITIES.getData(model.modelId),
                                contextWindowTokens = ModelRegistry.MODEL_CONTEXT_WINDOW.getData(model.modelId),
                            )
                        }
                    )
                )
            },
            onAllModelDeselected = { filteredModels ->
                onUpdateProvider(
                    parentProvider.copyProvider(
                        models = parentProvider.models.filter { model ->
                            filteredModels.none { filtered -> filtered.modelId == model.modelId }
                        }
                    )
                )
            },
            modifier = Modifier.weight(1f),
        )

        ProviderCommandButton(
            text = stringResource(R.string.setting_provider_page_add_new_model),
            imageVector = HugeIcons.Add01,
            accent = true,
            onClick = {
                dialogState.open(Model())
            },
            modifier = Modifier.weight(1.25f),
        )
    }

    if (dialogState.isEditing) {
        dialogState.currentState?.let { modelState ->
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = {
                    dialogState.dismiss()
                },
                sheetState = sheetState,
                sheetGesturesEnabled = false,
                containerColor = t.bg,
                dragHandle = {
                    ProviderSheetGrabber()
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.92f)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_add_model),
                        style = type.body.copy(fontWeight = FontWeight.Bold),
                        color = t.ink,
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        ModelSettingsForm(
                            model = modelState,
                            onModelChange = { dialogState.currentState = it },
                            isEdit = false,
                            parentProvider = parentProvider
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        ProviderCommandButton(
                            text = stringResource(R.string.cancel),
                            onClick = {
                                dialogState.dismiss()
                            },
                        )
                        ProviderCommandButton(
                            text = stringResource(R.string.setting_provider_page_add),
                            accent = true,
                            onClick = {
                                if (modelState.modelId.isNotBlank() && modelState.displayName.isNotBlank()) {
                                    dialogState.confirm()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPicker(
    models: List<Model>,
    selectedModels: List<Model>,
    onModelSelected: (Model) -> Unit,
    onModelDeselected: (Model) -> Unit,
    onAllModelSelected: (List<Model>) -> Unit,
    onAllModelDeselected: (List<Model>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showModal by remember { mutableStateOf(false) }
    if (showModal) {
        val t = LocalAmberTokens.current
        val type = LocalAmberType.current
        ModalBottomSheet(
            onDismissRequest = { showModal = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = t.bg,
            dragHandle = {
                Box(
                    Modifier
                        .padding(top = 10.dp, bottom = 4.dp)
                        .width(42.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(t.line2)
                )
            },
        ) {
            var filterText by remember { mutableStateOf("") }
            val filterKeywords = filterText.split(" ").filter { it.isNotBlank() }
            val filteredModels = models.fastFilter {
                if (filterKeywords.isEmpty()) {
                    true
                } else {
                    filterKeywords.all { keyword ->
                        it.modelId.contains(keyword, ignoreCase = true) ||
                            it.displayName.contains(keyword, ignoreCase = true)
                    }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 12.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val unselectedCount = filteredModels.count { model ->
                        selectedModels.none { it.modelId == model.modelId }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = stringResource(R.string.setting_provider_page_avaliable_models),
                            style = type.body.copy(fontWeight = FontWeight.Bold),
                            color = t.ink,
                        )
                        Text(
                            text = "${filteredModels.size} 可用 · ${selectedModels.size} 已启用",
                            style = type.meta.copy(fontSize = 11.sp),
                            color = t.ink3,
                        )
                    }
                    ProviderCommandButton(
                        text = if (unselectedCount > 0) {
                            stringResource(R.string.setting_provider_page_select_all, unselectedCount)
                        } else {
                            stringResource(R.string.setting_provider_page_deselect_models)
                        },
                        onClick = {
                            if (unselectedCount > 0) {
                                onAllModelSelected(filteredModels)
                            } else {
                                onAllModelDeselected(filteredModels)
                            }
                        },
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    items(filteredModels) {
                        val selected = selectedModels.any { model -> model.modelId == it.modelId }
                        ProviderCard {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pressable(onClick = {
                                        if (selected) {
                                            onModelDeselected(selectedModels.firstOrNull { model -> model.modelId == it.modelId } ?: it)
                                        } else {
                                            onModelSelected(it)
                                        }
                                    })
                                    .padding(11.dp),
                            ) {
                                ProviderMonogram(
                                    text = it.modelId.toProviderMonogram(),
                                    size = 34.dp,
                                )
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    val modelMeta = remember(it) {
                                        it.copy(
                                            inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(it.modelId),
                                            outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(it.modelId),
                                            abilities = ModelRegistry.MODEL_ABILITIES.getData(it.modelId),
                                            contextWindowTokens = ModelRegistry.MODEL_CONTEXT_WINDOW.getData(it.modelId),
                                        )
                                    }
                                    Text(
                                        text = it.modelId,
                                        style = type.meta.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                                        color = t.ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    ProviderCapFlags(modelMeta.capFlags())
                                }
                                ProviderIconButton(
                                    imageVector = if (selected) HugeIcons.CheckmarkCircle01 else HugeIcons.Add01,
                                    contentDescription = null,
                                    tint = if (selected) t.accent else t.ink3,
                                    onClick = {
                                        if (selected) {
                                            onModelDeselected(selectedModels.firstOrNull { model -> model.modelId == it.modelId }
                                                ?: it)
                                        } else {
                                            onModelSelected(it)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                ProviderSearchField(
                    value = filterText,
                    onValueChange = { filterText = it },
                    placeholder = stringResource(R.string.setting_provider_page_filter_example),
                    imageVector = HugeIcons.Search01,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    ProviderCommandButton(
        text = "可用 ${models.size}",
        imageVector = HugeIcons.Package01,
        onClick = { showModal = true },
        modifier = modifier,
    )
}

@Composable
private fun ModelCard(
    model: Model,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit,
    onEdit: (Model) -> Unit,
    parentProvider: ProviderSetting
) {
    val dialogState = useEditState<Model> {
        onEdit(it)
    }
    val swipeToDismissBoxState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val sheetTokens = LocalAmberTokens.current
    val sheetType = LocalAmberType.current

    if (dialogState.isEditing) {
        dialogState.currentState?.let { editingModel ->
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = {
                    dialogState.dismiss()
                },
                sheetState = sheetState,
                sheetGesturesEnabled = false,
                containerColor = sheetTokens.bg,
                dragHandle = {
                    ProviderSheetGrabber()
                },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.92f)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    sheetState.hide()
                                    dialogState.dismiss()
                                }
                            },
                            modifier = Modifier.align(Alignment.CenterStart)
                        ) {
                            Icon(HugeIcons.Cancel01, null)
                        }
                        Text(
                            text = stringResource(R.string.setting_provider_page_edit_model),
                            style = sheetType.body.copy(fontWeight = FontWeight.Bold),
                            color = sheetTokens.ink,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        ModelSettingsForm(
                            model = editingModel,
                            onModelChange = { dialogState.currentState = it },
                            isEdit = true,
                            parentProvider = parentProvider
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        ProviderCommandButton(
                            text = stringResource(R.string.cancel),
                            onClick = {
                                dialogState.dismiss()
                            },
                        )
                        ProviderCommandButton(
                            text = stringResource(R.string.confirm),
                            accent = true,
                            onClick = {
                                if (editingModel.displayName.isNotBlank()) {
                                    dialogState.confirm()
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    SwipeToDismissBox(
        state = swipeToDismissBoxState,
        backgroundContent = {
            val t = LocalAmberTokens.current
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        scope.launch {
                            swipeToDismissBoxState.reset()
                        }
                    }
                ) {
                    Icon(HugeIcons.Cancel01, null, tint = t.ink3)
                }
                FilledIconButton(
                    onClick = {
                        scope.launch {
                            onDelete()
                            swipeToDismissBoxState.reset()
                        }
                    }
                ) {
                    Icon(
                        HugeIcons.Delete01,
                        contentDescription = stringResource(R.string.chat_page_delete)
                    )
                }
            }
        },
        enableDismissFromStartToEnd = false,
        gesturesEnabled = true,
        modifier = modifier
    ) {
        val t = LocalAmberTokens.current
        val type = LocalAmberType.current
        val contextLabel = model.contextWindowTokens.toContextLabel()
        val openEditor = { dialogState.open(model.copy()) }
        ProviderCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressable(onClick = openEditor)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProviderMonogram(
                    text = model.modelId.toProviderMonogram(),
                    size = 36.dp,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = model.modelId,
                        style = type.meta.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        color = t.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ProviderCapFlags(
                            flags = model.capFlags(),
                            modifier = Modifier.weight(1f),
                        )
                        if (contextLabel.isNotBlank()) {
                            Text(
                                text = contextLabel,
                                style = type.meta.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                                color = t.ink3,
                                maxLines = 1,
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(t.surface2)
                        .border(1.dp, t.line, RoundedCornerShape(9.dp))
                        .pressable(onClick = openEditor),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HugeIcons.ArrowRight01,
                        contentDescription = stringResource(R.string.setting_provider_page_edit_model),
                        tint = t.ink3,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderSheetGrabber() {
    val t = LocalAmberTokens.current
    Box(
        Modifier
            .padding(top = 10.dp, bottom = 4.dp)
            .width(42.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(t.line2)
    )
}

private fun Model.capFlags(): List<String> {
    val flags = buildList {
        add(
            when (type) {
                ModelType.CHAT -> "chat"
                ModelType.IMAGE -> "image"
                ModelType.EMBEDDING -> "embed"
            }
        )
        if (inputModalities.contains(Modality.TEXT) && outputModalities.contains(Modality.TEXT)) {
            add("t2t")
        }
        if (abilities.contains(ModelAbility.TOOL)) {
            add("tools")
        }
        if (inputModalities.contains(Modality.IMAGE) || outputModalities.contains(Modality.IMAGE)) {
            add("vision")
        }
        if (abilities.contains(ModelAbility.REASONING)) {
            add("think")
        }
    }
    return flags.distinct().take(5)
}
