package app.amber.feature.ui.pages.setting

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Package
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Trash2
import com.dokar.sonner.TextToastAction
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastFilter
import kotlinx.coroutines.CancellationException
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.providers.isCodexOAuthReviewModel
import app.amber.ai.registry.ModelRegistry
import app.amber.agent.R
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.hooks.useEditState
import app.amber.feature.ui.pages.setting.components.ProviderCapFlags
import app.amber.feature.ui.pages.setting.components.ProviderCard
import app.amber.feature.ui.pages.setting.components.ProviderCommandButton
import app.amber.feature.ui.pages.setting.components.ProviderEmptyState
import app.amber.feature.ui.pages.setting.components.ProviderFootnote
import app.amber.feature.ui.pages.setting.components.ProviderGhostButton
import app.amber.feature.ui.pages.setting.components.ProviderGroupSegment
import app.amber.feature.ui.pages.setting.components.ProviderIconButton
import app.amber.feature.ui.pages.setting.components.ProviderListRow
import app.amber.feature.ui.pages.setting.components.ProviderRowDivider
import app.amber.feature.ui.pages.setting.components.ProviderSearchField
import app.amber.feature.ui.pages.setting.components.ProviderSectionLabel
import app.amber.feature.ui.pages.setting.components.ProviderSheetGrabber
import app.amber.feature.ui.pages.setting.components.ProviderSheetHeader
import app.amber.feature.ui.pages.setting.components.ProviderSplitBar
import app.amber.feature.ui.pages.setting.components.ProviderStatusChip
import app.amber.feature.ui.pages.setting.components.ProviderTile
import app.amber.feature.ui.pages.setting.components.ProviderTone
import app.amber.feature.ui.pages.setting.components.providerCapFlags
import app.amber.feature.ui.pages.setting.components.providerDangerColor
import app.amber.feature.ui.pages.setting.components.providerSlugLabel
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.Uuid

@Composable
internal fun SettingProviderModelPage(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
    fetchedCandidates: ProviderModelCandidates? = null,
    modelListRefreshKey: Int = 0,
    onRefetch: () -> Unit,
    currentModelId: Uuid? = null,
    onSetCurrent: (Model) -> Unit = {},
    onRestoreModel: ((Model, Int) -> Unit)? = null,
) {
    ModelList(
        onRestoreModel = onRestoreModel,
        providerSetting = provider,
        onUpdateProvider = onEdit,
        fetchedCandidates = fetchedCandidates,
        modelListRefreshKey = modelListRefreshKey,
        onRefetch = onRefetch,
        currentModelId = currentModelId,
        onSetCurrent = onSetCurrent,
    )
}

@Composable
private fun ModelList(
    onRestoreModel: ((Model, Int) -> Unit)?,
    providerSetting: ProviderSetting,
    onUpdateProvider: (ProviderSetting) -> Unit,
    fetchedCandidates: ProviderModelCandidates?,
    modelListRefreshKey: Int,
    onRefetch: () -> Unit,
    currentModelId: Uuid?,
    onSetCurrent: (Model) -> Unit,
) {
    val providerCatalog = koinInject<ProviderCatalog>()
    val requestKey = remember(providerSetting) { providerSetting.modelListRequestKey() }
    val externalCandidates = fetchedCandidates
        ?.takeIf { it.requestKey == requestKey }
        ?.models
    val autoFetchState by produceState<ModelListLoadState>(
        ModelListLoadState.Loading,
        requestKey,
        externalCandidates != null,
        modelListRefreshKey,
    ) {
        // Reset before every provider/configuration change and explicit retry so
        // an old catalog cannot look current while the new request is pending.
        value = ModelListLoadState.Loading
        if (externalCandidates != null) {
            value = ModelListLoadState.Loaded(externalCandidates)
            return@produceState
        }
        try {
            val models = if (providerSetting is ProviderSetting.OpenAI) {
                providerCatalog.listModels(providerSetting)
            } else {
                providerCatalog.text(providerSetting).listModels(providerSetting)
            }
            val keepServerOrder = providerSetting is ProviderSetting.OpenAI && providerSetting.authMode == OpenAIAuthMode.CHATGPT_SIWC
            value = ModelListLoadState.Loaded(if (keepServerOrder) models else models.sortedBy { it.modelId })
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            value = ModelListLoadState.Failed(error.message?.takeIf { it.isNotBlank() } ?: error.toString())
        }
    }
    val modelList = externalCandidates ?: when (val state = autoFetchState) {
        ModelListLoadState.Loading -> emptyList()
        is ModelListLoadState.Loaded -> state.models
        is ModelListLoadState.Failed -> emptyList()
    }
    val loadState = if (externalCandidates != null) {
        ModelListLoadState.Loaded(externalCandidates)
    } else {
        autoFetchState
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
    // Drag reorders a local overlay only; the store is written once on drag stop
    // instead of on every onMove frame (each write re-persisted all models).
    // The overlay is kept until the store write publishes the new models —
    // clearing it here would flash the stale order for a few frames.
    var dragOrder by remember(providerSetting.models) { mutableStateOf<List<Model>?>(null) }
    val displayModels = dragOrder ?: providerSetting.models
    fun commitDragOrder() {
        val order = dragOrder
        if (order != null) {
            onUpdateProvider(providerSetting.copyProvider(models = order))
        }
    }
    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromModelIndex = from.index - modelItemOffset
        val toModelIndex = to.index - modelItemOffset
        val base = dragOrder ?: providerSetting.models
        if (fromModelIndex in base.indices && toModelIndex in base.indices) {
            dragOrder = base.toMutableList().apply {
                add(toModelIndex, removeAt(fromModelIndex))
            }
        }
    }
    val t = LocalAmberTokens.current
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val undoLabel = stringResource(R.string.history_page_undo)
    // The undo action fires after later edits may have landed; always re-insert into the latest provider.
    val latestProvider by rememberUpdatedState(providerSetting)
    val latestOnUpdateProvider by rememberUpdatedState(onUpdateProvider)
    val latestOnRestoreModel by rememberUpdatedState(onRestoreModel)
    fun deleteWithUndo(model: Model) {
        val index = providerSetting.models.indexOfFirst { it.id == model.id }
        onUpdateProvider(providerSetting.delModel(model))
        toaster.show(
            message = context.getString(R.string.provider_model_deleted, model.modelId),
            action = TextToastAction(undoLabel) {
                val restore = latestOnRestoreModel
                if (restore != null) {
                    restore(model, index)
                } else {
                    val current = latestProvider
                    if (current.models.none { it.id == model.id }) {
                        val restored = current.models.toMutableList().apply {
                            add(index.coerceIn(0, size), model)
                        }
                        latestOnUpdateProvider(current.copyProvider(models = restored))
                    }
                }
            },
        )
    }
    val fabExpanded by remember { derivedStateOf { lazyListState.firstVisibleItemIndex == 0 } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 96.dp),
            horizontalAlignment = Alignment.Start,
            state = lazyListState
        ) {
            // Exactly one item precedes the models: keeps modelItemOffset = 1 valid for reorder.
            item("enabled_models_label") {
                ProviderSectionLabel(stringResource(R.string.setting_provider_page_enabled_models))
            }
            if (providerSetting.models.isEmpty()) {
                item("models_empty") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ProviderCard(modifier = Modifier.fillMaxWidth()) {
                            ProviderEmptyState(
                                icon = Lucide.Package,
                                title = stringResource(R.string.provider_model_empty_title),
                                body = stringResource(R.string.provider_model_empty_body),
                            )
                        }
                        ProviderCommandButton(
                            text = stringResource(R.string.provider_model_fetch_available),
                            imageVector = Lucide.RefreshCw,
                            onClick = {
                                if (loadState is ModelListLoadState.Failed) onRefetch()
                                showPicker = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        )
                    }
                }
            } else {
                val lastIndex = displayModels.lastIndex
                itemsIndexed(displayModels, key = { _, model -> model.id }) { index, item ->
                    ReorderableItem(
                        state = reorderableLazyListState,
                        key = item.id
                    ) { isDragging ->
                        ProviderGroupSegment(
                            first = index == 0,
                            last = index == lastIndex,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (index > 0) ProviderRowDivider(inset = 62.dp)
                            ProviderModelRow(
                                model = item,
                                isCurrent = item.type == ModelType.CHAT && item.id == currentModelId,
                                onSetCurrent = { onSetCurrent(item) },
                                onDelete = { deleteWithUndo(item) },
                                onOpenEditor = { editState.open(item.copy()) },
                                modifier = Modifier
                                    .longPressDraggableHandle(
                                        onDragStopped = { commitDragOrder() },
                                    )
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
                item("models_hint") {
                    ProviderFootnote(stringResource(R.string.provider_model_hint_gestures))
                }
            }
            when (loadState) {
                ModelListLoadState.Loading -> item("models_loading") {
                    ModelListStatusRow(
                        message = stringResource(R.string.setting_provider_page_models_fetching),
                    )
                }
                is ModelListLoadState.Failed -> item("models_load_error") {
                    ModelListStatusRow(
                        message = stringResource(
                            R.string.setting_provider_page_models_fetch_failed,
                            loadState.message,
                        ),
                        onRetry = onRefetch,
                    )
                }
                is ModelListLoadState.Loaded -> Unit
            }
        }

        ExtendedFloatingActionButton(
            text = { Text(stringResource(R.string.setting_provider_page_add_model), color = t.accentInk, style = LocalAmberType.current.body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)) },
            icon = { Icon(Lucide.Plus, contentDescription = null) },
            onClick = { showPicker = true },
            expanded = fabExpanded,
            shape = RoundedCornerShape(18.dp),
            containerColor = t.accent,
            contentColor = t.accentInk,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }

    if (showPicker) {
        ModelPickerSheet(
            models = modelList,
            selectedModels = providerSetting.models,
            parentProvider = providerSetting,
            onApplyModels = { models -> onUpdateProvider(providerSetting.copyProvider(models = models)) },
            onCreateBlank = {
                showPicker = false
                blankState.open(Model())
            },
            onDismiss = { showPicker = false },
            onRefetch = onRefetch,
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

internal fun ProviderSetting.modelListRequestKey(): ProviderModelListRequestKey {
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

internal data class ProviderModelListRequestKey(
    val type: String,
    val id: String,
    val credentialsHash: Int,
    val baseUrl: String,
    val authMode: String,
    val extra: String,
)

internal data class ProviderModelCandidates(
    val requestKey: ProviderModelListRequestKey,
    val models: List<Model>,
)

private sealed interface ModelListLoadState {
    data object Loading : ModelListLoadState
    data class Loaded(val models: List<Model>) : ModelListLoadState
    data class Failed(val message: String) : ModelListLoadState
}

@Composable
private fun ModelListStatusRow(
    message: String,
    onRetry: (() -> Unit)? = null,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = message,
            style = type.secondary.copy(fontSize = 12.5.sp, lineHeight = 19.sp),
            color = t.ink3,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) {
            ProviderCommandButton(
                text = stringResource(R.string.setting_provider_page_models_retry),
                onClick = onRetry,
                plain = true,
                modifier = Modifier.width(88.dp),
            )
        }
    }
}

internal fun ProviderSetting.OpenAI.codexOAuthLoginSelection(fetchedModels: List<Model>): List<Model> {
    val existing = models.withoutCodexReviewModels()
    return if (existing.isEmpty()) listOfNotNull(fetchedModels.firstOrNull()) else existing
}

internal fun codexOAuthResultIsCurrent(
    requestGeneration: Int,
    currentGeneration: Int,
    requestKey: ProviderModelListRequestKey,
    currentProvider: ProviderSetting.OpenAI,
): Boolean = requestGeneration == currentGeneration &&
    currentProvider.modelListRequestKey() == requestKey

internal fun geminiOAuthResultIsCurrent(
    requestKey: ProviderModelListRequestKey,
    currentProvider: ProviderSetting.Google,
): Boolean = currentProvider.modelListRequestKey() == requestKey

/* enrich a picked model with the static registry knowledge base */
private fun Model.withRegistryMetadata(): Model = copy(
    inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(modelId),
    outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(modelId),
    abilities = ModelRegistry.MODEL_ABILITIES.getData(modelId),
    contextWindowTokens = ModelRegistry.MODEL_CONTEXT_WINDOW.getData(modelId),
)

/* Model row inside a card slice: swipe left past the threshold deletes (undo lives in the caller's toast). */
@Composable
internal fun ProviderModelRow(
    model: Model,
    modifier: Modifier = Modifier,
    isCurrent: Boolean,
    onSetCurrent: () -> Unit,
    onDelete: () -> Unit,
    onOpenEditor: () -> Unit,
) {
    val swipeToDismissBoxState = rememberSwipeToDismissBoxState()
    LaunchedEffect(swipeToDismissBoxState.currentValue) {
        if (swipeToDismissBoxState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete()
            swipeToDismissBoxState.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }

    SwipeToDismissBox(
        state = swipeToDismissBoxState,
        backgroundContent = {
            // 2026-09-13 fix: only compose the delete zone while actually swiping, so it is
            // never visible / hit-testable at rest.
            if (swipeToDismissBoxState.dismissDirection != SwipeToDismissBoxValue.Settled) {
                val type = LocalAmberType.current
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(providerDangerColor())
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Lucide.Trash2,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.delete),
                        style = type.body.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
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
        var menuOpen by remember { mutableStateOf(false) }
        // Opaque surface so the moving foreground covers the delete zone.
        ProviderListRow(
            title = model.modelId,
            titleMono = true,
            minHeight = 66.dp,
            onClick = onOpenEditor,
            modifier = Modifier.background(t.surface),
            leading = {
                ProviderTile(text = model.modelId.toModelMonogram(), tint = t.accent)
            },
            titleTrailing = if (isCurrent) {
                {
                    Text(
                        text = stringResource(R.string.setting_provider_page_model_current),
                        style = type.meta.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                        color = if (t.isDark) Color(0xFF6CC489) else Color(0xFF2F8A4C),
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF3DA35D).copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            } else {
                null
            },
            subtitle = { ProviderCapFlags(model.providerCapFlags()) },
            trailing = {
                Box {
                    ProviderIconButton(
                        imageVector = Lucide.EllipsisVertical,
                        contentDescription = stringResource(R.string.more_options),
                        onClick = { menuOpen = true },
                        size = 40.dp,
                        tint = t.ink3,
                    )
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.edit)) },
                            onClick = {
                                menuOpen = false
                                onOpenEditor()
                            },
                        )
                        if (model.type == ModelType.CHAT && !isCurrent) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.setting_provider_page_model_set_current)) },
                                onClick = {
                                    menuOpen = false
                                    onSetCurrent()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.delete),
                                    color = providerDangerColor(),
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            },
        )
    }
}

/* Two-letter tile text from the model id: "deepseek-v4-pro" -> "DE", "gpt" -> "GP". */
private fun String.toModelMonogram(): String {
    val head = trim().substringAfterLast('/').substringBefore('-').ifBlank { trim() }
    return head.filter { it.isLetterOrDigit() }.take(2).uppercase().ifBlank { "AI" }
}

/* Add-model sheet: staged draft selection, confirm applies the diff */
@Composable
internal fun ModelPickerSheet(
    models: List<Model>,
    selectedModels: List<Model>,
    parentProvider: ProviderSetting,
    onApplyModels: (List<Model>) -> Unit,
    onCreateBlank: () -> Unit,
    onDismiss: () -> Unit,
    onRefetch: (() -> Unit)? = null,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        containerColor = t.raised,
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
            ProviderSheetHeader(
                title = stringResource(R.string.setting_provider_page_add_model),
                trailing = if (onRefetch != null) {
                    {
                        ProviderStatusChip(
                            text = stringResource(R.string.provider_model_refetch),
                            tone = ProviderTone.Accent,
                            icon = Lucide.RefreshCw,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .pressable(onClick = onRefetch),
                        )
                    }
                } else {
                    null
                },
            )

            ProviderSearchField(
                value = filterText,
                onValueChange = { filterText = it },
                placeholder = stringResource(R.string.provider_model_filter_hint, models.size),
                containerColor = t.surface2,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.setting_provider_page_selected_count,
                        pickedCount,
                        models.size,
                    ),
                    style = type.secondary,
                    color = t.ink3,
                    modifier = Modifier.weight(1f),
                )
                ProviderGhostButton(
                    text = if (allSelected) {
                        stringResource(R.string.setting_provider_page_deselect_models)
                    } else {
                        stringResource(R.string.setting_provider_page_select_all, filteredModels.size)
                    },
                    onClick = {
                        val filteredIds = filteredModels.map { it.modelId }.toSet()
                        draftIds = if (allSelected) draftIds - filteredIds else draftIds + filteredIds
                    },
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (filteredModels.isEmpty()) {
                    item("no_matches") {
                        Text(
                            text = stringResource(R.string.model_list_no_matches),
                            style = type.secondary,
                            color = t.ink3,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 36.dp),
                        )
                    }
                }
                itemsIndexed(filteredModels) { index, model ->
                    val on = model.modelId in draftIds
                    val modelMeta = remember(model) { model.withRegistryMetadata() }
                    Column {
                        if (index > 0) ProviderRowDivider(inset = 20.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 60.dp)
                                .pressable(onClick = {
                                    draftIds = if (on) {
                                        draftIds - model.modelId
                                    } else {
                                        draftIds + model.modelId
                                    }
                                })
                                .padding(horizontal = 20.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = if (parentProvider is ProviderSetting.OpenAI && parentProvider.authMode == OpenAIAuthMode.CHATGPT_SIWC) {
                                        model.displayName.ifBlank { model.modelId }
                                    } else model.modelId,
                                    style = type.meta.copy(
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = if (on) t.accent else t.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                ProviderCapFlags(modelMeta.providerCapFlags())
                            }
                            if (on) {
                                ProviderStatusChip(
                                    text = stringResource(R.string.setting_provider_page_model_added),
                                    tone = ProviderTone.Neutral,
                                    icon = Lucide.Check,
                                )
                            } else {
                                ProviderStatusChip(
                                    text = stringResource(R.string.add),
                                    tone = ProviderTone.Accent,
                                    icon = Lucide.Plus,
                                )
                            }
                        }
                    }
                }
                item("manual_input") {
                    Column {
                        ProviderRowDivider(inset = 20.dp)
                        ProviderListRow(
                            title = stringResource(R.string.provider_model_manual_input),
                            onClick = onCreateBlank,
                            modifier = Modifier.padding(horizontal = 6.dp),
                            trailing = {
                                Icon(
                                    Lucide.ChevronRight,
                                    contentDescription = null,
                                    tint = t.ink4,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }
            }

            ProviderSplitBar(
                cancelText = stringResource(R.string.cancel),
                onCancel = onDismiss,
                confirmText = stringResource(R.string.confirm),
                onConfirm = {
                    val isCodex = parentProvider is ProviderSetting.OpenAI &&
                        parentProvider.authMode == OpenAIAuthMode.CODEX_OAUTH
                    val addedModels = models
                        .filter { it.modelId in draftIds && it.modelId !in initialIds }
                        .filterNot { isCodex && it.isCodexOAuthReviewModel() }
                        .map { it.withRegistryMetadata() }
                    val finalModels = buildList {
                        addAll(selectedModels.filter { it.modelId in draftIds })
                        addAll(addedModels)
                    }
                    onApplyModels(finalModels)
                    onDismiss()
                },
            )
        }
    }
}
