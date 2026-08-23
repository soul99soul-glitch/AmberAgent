package app.amber.feature.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Cancel01
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
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
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.hooks.useEditState
import app.amber.feature.ui.pages.setting.components.ProviderCapFlags
import app.amber.feature.ui.pages.setting.components.ProviderCard
import app.amber.feature.ui.pages.setting.components.ProviderCommandButton
import app.amber.feature.ui.pages.setting.components.ProviderGhostButton
import app.amber.feature.ui.pages.setting.components.ProviderHairline
import app.amber.feature.ui.pages.setting.components.ProviderMonogram
import app.amber.feature.ui.pages.setting.components.ProviderSectionLabel
import app.amber.feature.ui.pages.setting.components.ProviderSheetGrabber
import app.amber.feature.ui.pages.setting.components.ProviderSplitBar
import app.amber.feature.ui.pages.setting.components.ProviderSquareTag
import app.amber.feature.ui.pages.setting.components.ProviderTerminalFilter
import app.amber.feature.ui.pages.setting.components.providerSlugLabel
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
            // from an old Codex response. We do NOT auto-fill on `models.isEmpty()` — the
            // candidates live in `modelList` / the available-models sheet.
            val filtered = providerSetting.models.filterNot { it.isCodexOAuthReviewModel() }
            onUpdateProvider(providerSetting.copy(models = filtered))
        }
    }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val editState = useEditState<Model> { onUpdateProvider(providerSetting.editModel(it)) }
    val blankState = useEditState<Model> { onUpdateProvider(providerSetting.addModel(it)) }
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
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp) + PaddingValues(bottom = 88.dp),
            horizontalAlignment = Alignment.Start,
            state = lazyListState
        ) {
            item("enabled_models_label") {
                ProviderSectionLabel("已启用模型", count = providerSetting.models.size)
            }
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
                        Column {
                            ModelRow(
                                model = item,
                                onDelete = {
                                    onUpdateProvider(providerSetting.delModel(item))
                                },
                                onOpenEditor = { editState.open(item.copy()) },
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
                            ProviderHairline()
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(t.bg),
        ) {
            ProviderHairline()
            ProviderCommandButton(
                text = "添加模型",
                imageVector = HugeIcons.Add01,
                accent = true,
                onClick = { showPicker = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }

    if (showPicker) {
        ModelPickerSheet(
            models = modelList,
            selectedModels = providerSetting.models,
            parentProvider = providerSetting,
            onAddModel = { onUpdateProvider(providerSetting.addModel(it)) },
            onRemoveModel = { onUpdateProvider(providerSetting.delModel(it)) },
            onCreateBlank = {
                showPicker = false
                blankState.open(Model())
            },
            onDismiss = { showPicker = false },
        )
    }

    if (editState.isEditing) {
        editState.currentState?.let { modelState ->
            ModelEditorSheet(
                model = modelState,
                onModelChange = { editState.currentState = it },
                isEdit = true,
                parentProvider = providerSetting,
                slug = providerSetting.providerSlugLabel(),
                onConfirm = { editState.confirm() },
                onDismiss = { editState.dismiss() },
            )
        }
    }

    if (blankState.isEditing) {
        blankState.currentState?.let { modelState ->
            ModelEditorSheet(
                model = modelState,
                onModelChange = { blankState.currentState = it },
                isEdit = false,
                parentProvider = providerSetting,
                slug = providerSetting.providerSlugLabel(),
                onConfirm = { blankState.confirm() },
                onDismiss = { blankState.dismiss() },
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

/* enrich a picked model with the static registry knowledge base */
private fun Model.withRegistryMetadata(): Model = copy(
    inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(modelId),
    outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(modelId),
    abilities = ModelRegistry.MODEL_ABILITIES.getData(modelId),
    contextWindowTokens = ModelRegistry.MODEL_CONTEXT_WINDOW.getData(modelId),
)

/* v5 ledger: flat model row inside SwipeToDismissBox (swipe delete + long-press drag kept) */
@Composable
private fun ModelRow(
    model: Model,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit,
    onOpenEditor: () -> Unit,
) {
    val swipeToDismissBoxState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()

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
        val openEditor = onOpenEditor
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(t.bg)
                .pressable(onClick = openEditor)
                .padding(horizontal = 4.dp, vertical = 11.dp),
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

/* v5 ledger: available-models sheet — staged draft selection, confirm applies the diff */
@Composable
private fun ModelPickerSheet(
    models: List<Model>,
    selectedModels: List<Model>,
    parentProvider: ProviderSetting,
    onAddModel: (Model) -> Unit,
    onRemoveModel: (Model) -> Unit,
    onCreateBlank: () -> Unit,
    onDismiss: () -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        containerColor = t.bg,
        dragHandle = { ProviderSheetGrabber() },
    ) {
        var filterText by remember { mutableStateOf("") }
        val initialIds = remember(selectedModels) { selectedModels.map { it.modelId }.toSet() }
        var draftIds by remember { mutableStateOf(initialIds) }
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
        val allSelected = filteredModels.isNotEmpty() && filteredModels.all { it.modelId in draftIds }
        val pickedCount = draftIds.count { id -> models.any { it.modelId == id } }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 2.dp),
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
                        Text(parentProvider.providerSlugLabel(), style = type.eyebrow, color = t.ink3)
                    }
                    Text(
                        text = stringResource(R.string.setting_provider_page_avaliable_models),
                        style = type.sessionTitle,
                        color = t.ink,
                        maxLines = 1,
                    )
                }
                ProviderGhostButton(
                    text = if (allSelected) "✓ 已全选" else "+ 全选",
                    onClick = {
                        val filteredIds = filteredModels.map { it.modelId }.toSet()
                        draftIds = if (allSelected) draftIds - filteredIds else draftIds + filteredIds
                    },
                )
                ProviderGhostButton(
                    text = "手动创建",
                    imageVector = HugeIcons.Add01,
                    onClick = onCreateBlank,
                )
            }

            ProviderTerminalFilter(
                value = filterText,
                onValueChange = { filterText = it },
                placeholder = "筛选模型_",
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(t.surface2)
                    .padding(horizontal = 18.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "模型 ID",
                    style = type.meta.copy(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold),
                    color = t.ink4,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "上下文",
                    style = type.meta.copy(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold),
                    color = t.ink4,
                    modifier = Modifier.size(width = 48.dp, height = 14.dp),
                    maxLines = 1,
                )
                Text(
                    "操作",
                    style = type.meta.copy(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold),
                    color = t.ink4,
                    modifier = Modifier.size(width = 76.dp, height = 14.dp),
                    maxLines = 1,
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (filteredModels.isEmpty()) {
                    item {
                        Text(
                            text = "// 无匹配",
                            style = type.meta.copy(fontSize = 12.sp),
                            color = t.ink4,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 36.dp),
                        )
                    }
                }
                items(filteredModels) { model ->
                    val on = model.modelId in draftIds
                    val modelMeta = remember(model) { model.withRegistryMetadata() }
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = model.modelId,
                                    style = type.meta.copy(
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = if (on) t.accent else t.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                ProviderCapFlags(modelMeta.capFlags())
                            }
                            Text(
                                text = model.contextWindowTokens.toContextLabel(),
                                style = type.meta.copy(fontSize = 11.sp),
                                color = t.ink4,
                                modifier = Modifier.width(48.dp),
                                maxLines = 1,
                            )
                            Box(
                                modifier = Modifier.size(width = 76.dp, height = 28.dp),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                ProviderSquareTag(
                                    text = if (on) "✓ 已添加" else "+ 添加",
                                    selected = on,
                                    solid = true,
                                    onClick = {
                                        draftIds = if (on) draftIds - model.modelId else draftIds + model.modelId
                                    },
                                )
                            }
                        }
                        ProviderHairline()
                    }
                }
            }

            Text(
                text = "已选 $pickedCount / ${models.size}",
                style = type.meta.copy(fontSize = 11.sp),
                color = t.ink4,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )
            ProviderSplitBar(
                cancelText = stringResource(R.string.cancel),
                onCancel = onDismiss,
                confirmText = stringResource(R.string.confirm),
                onConfirm = {
                    val isCodex = parentProvider is ProviderSetting.OpenAI &&
                        parentProvider.authMode == OpenAIAuthMode.CODEX_OAUTH
                    models
                        .filter { it.modelId in draftIds && it.modelId !in initialIds }
                        .filterNot { isCodex && it.isCodexOAuthReviewModel() }
                        .forEach { onAddModel(it.withRegistryMetadata()) }
                    selectedModels
                        .filter { it.modelId !in draftIds }
                        .forEach(onRemoveModel)
                    onDismiss()
                },
            )
        }
    }
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
