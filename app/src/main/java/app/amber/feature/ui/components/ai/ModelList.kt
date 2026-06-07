package app.amber.feature.ui.components.ai

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import app.amber.ai.provider.GoogleAuthMode
import app.amber.ai.provider.Modality
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.providers.isCodexOAuthReviewModel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.DragDropHorizontal
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.hugeicons.stroke.Image03
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Text
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.Wrench01
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.core.settings.findModelById
import app.amber.core.settings.findProvider
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.ui.components.ui.AutoAIIcon
import app.amber.feature.ui.components.ui.Tag
import app.amber.feature.ui.components.ui.TagType
import app.amber.feature.ui.components.ui.icons.HeartIcon
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.feature.ui.theme.extendColors
import app.amber.core.utils.formatNumber
import app.amber.core.utils.toDp
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.Uuid

@Composable
fun ModelSelector(
    modelId: Uuid?,
    providers: List<ProviderSetting>,
    type: ModelType,
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = false,
    compact: Boolean = false,
    minimalText: Boolean = false,
    /** V3 settings-models.jsx inline 触发器：logo 块 + accent 模型名 + chevron-down。
     *  优先级排在 minimalText / compact 之后（互斥）。 */
    inline: Boolean = false,
    allowClear: Boolean = false,
    emptyLabel: String? = null,
    clearContentDescription: String? = null,
    preferredInputModality: Modality? = null,
    onClear: (() -> Unit)? = null,
    // Phase 3.5 thinking-level segment：仅 chat 主入口（ChatPage TopBar / ChatInput）传入这两个
    // 参数即可在 active model 卡片下显示 reasoning 切换段；其他调用方默认 null 即不渲染
    currentAssistant: app.amber.core.model.Assistant? = null,
    onUpdateAssistant: ((app.amber.core.model.Assistant) -> Unit)? = null,
    onSelect: (Model) -> Unit
) {
    var popup by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val visibleProviders = providers
    val modelIndex = remember(visibleProviders, type) {
        visibleProviders.buildModelProviderIndex(type)
    }
    val model = modelId?.let { modelIndex[it]?.model }

    if (!onlyIcon) {
        if (minimalText) {
            // Graphite §6.2 model-menu trigger: MONO model-id (LocalAmberType.meta) + small
            // dropdown chevron icon. Capsule ripple (.clip(CircleShape) before .clickable).
            val tokens = LocalAmberTokens.current
            Row(
                modifier = modifier
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .clickable { popup = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = model?.modelId
                        ?: emptyLabel
                        ?: stringResource(R.string.model_list_select_model),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = LocalAmberType.current.meta.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = tokens.ink,
                )
                Icon(
                    imageVector = HugeIcons.ArrowDown01,
                    contentDescription = null,
                    tint = tokens.ink3,
                    modifier = Modifier.size(14.dp),
                )
            }
        } else if (inline) {
            // settings-models.jsx 设计稿：22dp logo + 14.5sp accent W500 model name + 12dp chevron-down
            val theme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
            Row(
                modifier = modifier.clickable { popup = true },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // V3: 去掉 logo 小方框 (用户反馈"图标太小不匹配方框, 去掉"). 直接 model 名 + 下拉.
                Text(
                    text = model?.displayName
                        ?: emptyLabel
                        ?: stringResource(R.string.model_list_select_model),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.2.sp,
                    color = theme.accent,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    imageVector = HugeIcons.ArrowDown01,
                    contentDescription = null,
                    tint = theme.accent,
                    modifier = Modifier.size(12.dp),
                )
                if (allowClear && model != null) {
                    IconButton(
                        onClick = { onClear?.invoke() ?: onSelect(Model()) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = clearContentDescription ?: "Clear",
                            tint = theme.inkFaint,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        } else if (compact) {
            val workspace = workspaceColors()
            val chipShape = RoundedCornerShape(8.dp)
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Surface(
                    onClick = { popup = true },
                    modifier = modifier
                        .height(32.dp)
                        .widthIn(max = 156.dp),
                    shape = chipShape,
                    color = workspace.paper,
                    contentColor = workspace.ink,
                    border = BorderStroke(1.dp, workspace.hairline),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = model?.compactDisplayName()
                                ?: emptyLabel
                                ?: stringResource(R.string.model_list_select_model),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        )
                    }
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        popup = true
                    },
                    modifier = modifier
                ) {
                    model?.modelId?.let {
                        AutoAIIcon(
                            it, Modifier
                                .padding(end = 4.dp)
                                .size(36.dp),
                            color = Color.Transparent
                        )
                    }
                    Text(
                        text = model?.displayName
                            ?: emptyLabel
                            ?: stringResource(R.string.model_list_select_model),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (allowClear && model != null) {
                    IconButton(
                        onClick = {
                            onClear?.invoke() ?: onSelect(Model())
                        }
                    ) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = clearContentDescription ?: "Clear"
                        )
                    }
                }
            }
        }
    } else {
        IconButton(
            onClick = {
                popup = true
            },
        ) {
            if (model != null) {
                AutoAIIcon(
                    modifier = Modifier.size(36.dp),
                    name = model.modelId,
                    color = Color.Transparent
                )
            } else {
                Icon(
                    imageVector = HugeIcons.Brain02,
                    contentDescription = stringResource(R.string.setting_model_page_chat_model),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    if (popup) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        // V3 Whisper：28dp 顶圆角 + 白底 + 自定义 36dp/4dp 拖拽手柄；模型列表本身
        // 暂保留旧实现，后续 phase 再按 model-picker.jsx 重做 active card / segment。
        ModalBottomSheet(
            onDismissRequest = {
                popup = false
            },
            sheetState = state,
            shape = RoundedCornerShape(
                topStart = 28.dp,
                topEnd = 28.dp,
                bottomStart = 0.dp,
                bottomEnd = 0.dp,
            ),
            containerColor = app.amber.feature.ui.pages.chat.LocalChatTheme.current.surface,
            scrimColor = app.amber.feature.ui.pages.chat.LocalChatTheme.current.sheetBackdrop,
            dragHandle = {
                // V3 model-picker.jsx:57 drag handle 40×4dp
                Box(
                    modifier = Modifier
                        .padding(top = 10.dp, bottom = 4.dp)
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(app.amber.feature.ui.pages.chat.LocalChatTheme.current.dragHandle),
                )
            },
        ) {
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxHeight(0.8f)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val filteredProviderSettings = visibleProviders.fastFilter {
                    it.enabled && it.models.fastAny { model -> model.type == type }
                }
                ModelList(
                    currentModel = modelId,
                    providers = filteredProviderSettings,
                    modelType = type,
                    preferredInputModality = preferredInputModality,
                    currentAssistant = currentAssistant,
                    onUpdateAssistant = onUpdateAssistant,
                    onSelect = { selectedModel ->
                        // V3 改: 之前为了"切 model 顺手改 reasoning"保持 picker 不关,
                        // 但 reasoning 已搬到 slash panel footer, 这里恢复"选即关".
                        // 副作用: 生图 picker 第一次选模型不再卡住, 不会被误点 × 清成 sentinel.
                        onSelect(selectedModel)
                        scope.launch {
                            state.hide()
                            popup = false
                        }
                    },
                    onDismiss = {
                        scope.launch {
                            state.hide()
                            popup = false
                        }
                    }
                )
            }
        }
    }
}

private fun Model.compactDisplayName(): String {
    val raw = displayName.ifBlank { modelId }.trim()
    if (raw.isBlank()) return raw
    val tokens = raw.split('-', '_', ' ')
        .filter { it.isNotBlank() }
    val displayTokens = buildList {
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            val next = tokens.getOrNull(index + 1)
            if (token.all { it.isDigit() } && next?.all { it.isDigit() } == true) {
                add("$token.$next")
                index += 2
            } else {
                add(token)
                index += 1
            }
        }
    }
    return displayTokens
        .joinToString(" ") { token ->
            when {
                token.equals("deepseek", ignoreCase = true) -> "DeepSeek"
                token.equals("gpt", ignoreCase = true) -> "GPT"
                token.equals("claude", ignoreCase = true) -> "Claude"
                token.equals("gemini", ignoreCase = true) -> "Gemini"
                token.equals("qwen", ignoreCase = true) -> "Qwen"
                token.matches(Regex("(?i)v\\d+")) -> token.uppercase()
                token.length <= 2 && token.any { it.isDigit() } -> token.uppercase()
                else -> token.replaceFirstChar { char -> char.uppercaseChar() }
            }
        }
}

private fun List<Model>.prioritizeInputModality(modality: Modality?): List<Model> =
    if (modality == null) {
        this
    } else {
        sortedWith(compareByDescending<Model> { modality in it.inputModalities }.thenBy { it.displayName })
    }

private data class ModelWithProvider(
    val model: Model,
    val provider: ProviderSetting,
)

private fun List<ProviderSetting>.buildModelProviderIndex(modelType: ModelType): Map<Uuid, ModelWithProvider> =
    buildMap {
        this@buildModelProviderIndex.fastForEach { provider ->
            provider.models.fastForEach { model ->
                if (model.type == modelType && !provider.isHiddenCodexOAuthModel(model)) {
                    putIfAbsent(model.id, ModelWithProvider(model, provider))
                }
            }
        }
    }

@Composable
private fun ColumnScope.ModelList(
    currentModel: Uuid? = null,
    providers: List<ProviderSetting>,
    modelType: ModelType,
    preferredInputModality: Modality? = null,
    currentAssistant: app.amber.core.model.Assistant? = null,
    onUpdateAssistant: ((app.amber.core.model.Assistant) -> Unit)? = null,
    onSelect: (Model) -> Unit,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val settingsStore = koinInject<SettingsAggregator>()
    val settings = settingsStore.settingsFlow
        .collectAsStateWithLifecycle()

    val modelIndex = remember(providers, modelType) {
        providers.buildModelProviderIndex(modelType)
    }
    val favoriteModels = remember(settings.value.favoriteModels, modelIndex) {
        settings.value.favoriteModels.mapNotNull { modelId ->
            val entry = modelIndex[modelId] ?: return@mapNotNull null
            if (entry.provider.isCodexOAuthProvider() && entry.model.isCodexOAuthReviewModel()) return@mapNotNull null
            entry.model to entry.provider
        }
    }

    var searchKeywords by remember { mutableStateOf("") }

    val typeFilteredModelsByProvider = remember(providers, modelType, preferredInputModality) {
        providers.associate { provider ->
            provider.id to provider.models.fastFilter {
                it.type == modelType && !provider.isHiddenCodexOAuthModel(it)
            }.prioritizeInputModality(preferredInputModality)
        }
    }

    val searchFilteredModelsByProvider = remember(typeFilteredModelsByProvider, searchKeywords) {
        typeFilteredModelsByProvider.mapValues { (_, models) ->
            models.fastFilter {
                it.displayName.contains(searchKeywords, ignoreCase = true)
            }
        }
    }

    // 计算当前选中模型的位置
    val selectedModelPosition = remember(currentModel, favoriteModels, providers, typeFilteredModelsByProvider) {
        if (currentModel == null) return@remember 0

        var position = 0

        // 跳过无providers提示
        if (providers.isEmpty()) {
            position += 1
        }

        // 检查是否在收藏列表中
        val favoriteIndex = favoriteModels.indexOfFirst { it.first.id == currentModel }
        if (favoriteIndex >= 0) {
            if (favoriteModels.isNotEmpty()) {
                position += 1 // favorite header
            }
            position += favoriteIndex
            return@remember position
        }

        // 跳过收藏列表
        if (favoriteModels.isNotEmpty()) {
            position += 1 // favorite header
            position += favoriteModels.size
        }

        // 在providers中查找
        for (provider in providers) {
            position += 1 // provider header
            val models = typeFilteredModelsByProvider[provider.id].orEmpty()
            val modelIndex = models.indexOfFirst { it.id == currentModel }
            if (modelIndex >= 0) {
                position += modelIndex
                return@remember position
            }
            position += models.size
        }

        0
    }

    val lazyListState = rememberLazyListState(
        initialFirstVisibleItemIndex = selectedModelPosition
    )
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // 计算favorite models在列表中的位置偏移
        var favoriteStartIndex = 0
        if (providers.isEmpty()) {
            favoriteStartIndex = 1 // no providers item
        }
        if (favoriteModels.isNotEmpty()) {
            favoriteStartIndex += 1 // favorite header
        }

        val fromIndex = from.index - favoriteStartIndex
        val toIndex = to.index - favoriteStartIndex

        // 只处理favorite models范围内的拖拽
        if (fromIndex >= 0 && toIndex >= 0 &&
            fromIndex < favoriteModels.size && toIndex < favoriteModels.size
        ) {
            val newFavoriteModels = settings.value.favoriteModels.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
            coroutineScope.launch {
                settingsStore.update { oldSettings ->
                    oldSettings.copy(favoriteModels = newFavoriteModels)
                }
            }
        }
    }
    val haptic = LocalHapticFeedback.current

    val providerPositions = remember(providers, favoriteModels, searchFilteredModelsByProvider) {
        var currentIndex = 0
        if (providers.isEmpty()) {
            currentIndex = 1 // no providers item
        }
        if (favoriteModels.isNotEmpty()) {
            currentIndex += 1 // favorite header
            currentIndex += favoriteModels.size // favorite models
        }

        providers.map { provider ->
            val position = currentIndex
            currentIndex += 1 // provider header
            currentIndex += searchFilteredModelsByProvider[provider.id].orEmpty().size
            provider.id to position
        }.toMap()
    }

    Surface(
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        // V3: 统一 drawer Amber 下方搜索栏样式 (WorkspaceSearchField)
        app.amber.feature.ui.components.ui.WorkspaceSearchField(
            value = searchKeywords,
            onValueChange = { searchKeywords = it },
            placeholder = stringResource(R.string.model_list_search_placeholder),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    LazyColumn(
        state = lazyListState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(8.dp),
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
    ) {
        if (providers.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.model_list_no_providers),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.extendColors.gray6,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        if (favoriteModels.isNotEmpty()) {
            item(key = "favorite-header") {
                Text(
                    text = stringResource(R.string.model_list_favorite),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(bottom = 4.dp, top = 8.dp)
                )
            }

            items(
                items = favoriteModels,
                key = { "favorite:" + it.first.id.toString() }
            ) { (model, provider) ->
                // V3: 移除拖拽 handle（用户反馈实用性低）—— 不再包 ReorderableItem
                ModelItem(
                    model = model,
                    onSelect = onSelect,
                    modifier = Modifier.animateItem(),
                    providerSetting = provider,
                    select = model.id == currentModel,
                    onDismiss = { onDismiss() },
                    tail = {
                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    settingsStore.update { settings ->
                                        settings.copy(
                                            favoriteModels = settings.favoriteModels.filter { it != model.id }
                                        )
                                    }
                                }
                            }
                        ) {
                            Icon(
                                HeartIcon,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = app.amber.feature.ui.pages.chat.LocalChatTheme.current.accent,
                            )
                        }
                    },
                )
            }
        }

        // Graphite §6.2 "Top model menu": provider groups as an accordion. The header row
        // toggles expand/collapse (+/−); the ACTIVE provider name (the one holding the current
        // model) and the SELECTED model are accent-colored, everything else neutral ink. Rows
        // read "name … ctx" in mono, no check/badge. Kept inside one LazyColumn group item per
        // provider (so the chip-rail / scroll-position math is unchanged); collapsing only
        // hides rows inside that item, not whole items.
        providers.fastForEach { providerSetting ->
            val groupModels = searchFilteredModelsByProvider[providerSetting.id].orEmpty()
            if (groupModels.isEmpty()) return@fastForEach

            val providerActive = groupModels.fastAny { it.id == currentModel }

            item(key = "group:${providerSetting.id}") {
                val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
                val tokens = LocalAmberTokens.current
                // Default-expand a provider while searching, or the one holding the active
                // model; collapsed otherwise. Re-keyed by search term so a query opens matches.
                var expanded by remember(providerSetting.id, searchKeywords) {
                    mutableStateOf(searchKeywords.isNotBlank() || providerActive)
                }
                Card(
                    modifier = Modifier.animateItem(),
                    colors = CardDefaults.cardColors(
                        containerColor = chatTheme.surface,
                        contentColor = chatTheme.ink,
                    ),
                    border = BorderStroke(1.dp, chatTheme.hair),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                ) {
                    Column {
                        // Accordion header: mono provider name (accent when active) + +/− glyph.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expanded = !expanded }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = providerSetting.name,
                                style = LocalAmberType.current.meta.copy(
                                    fontSize = 14.sp,
                                    fontWeight = if (providerActive) FontWeight.SemiBold else FontWeight.Medium,
                                ),
                                color = if (providerActive) chatTheme.accent else tokens.ink2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = if (expanded) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01,
                                contentDescription = null,
                                tint = tokens.ink4,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                            Column {
                                groupModels.fastForEach { model ->
                                    val isActive = model.id == currentModel
                                    Box(
                                        modifier = Modifier
                                            .padding(start = 14.dp)
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(chatTheme.hair),
                                    )
                                    val favorite = settings.value.favoriteModels.contains(model.id)
                                    ModelItemRow(
                                        model = model,
                                        providerSetting = providerSetting,
                                        isActive = isActive,
                                        onSelect = onSelect,
                                        onDismiss = onDismiss,
                                        tail = {
                                            FavoriteToggleIcon(
                                                favorite = favorite,
                                                isActive = isActive,
                                                onToggle = {
                                                    coroutineScope.launch {
                                                        settingsStore.update { s ->
                                                            if (favorite) s.copy(favoriteModels = s.favoriteModels.filter { it != model.id })
                                                            else s.copy(favoriteModels = s.favoriteModels + model.id)
                                                        }
                                                    }
                                                },
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 供应商Badge行
    val providerBadgeListState = rememberLazyListState()
    LaunchedEffect(lazyListState) {
        // 当LazyColumn滚动时，LazyRow也跟随滚动
        snapshotFlow { lazyListState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .debounce(100) // 防抖处理
            .collect { index ->
                if (index > 0) {
                    val currentProvider = providerPositions.entries.findLast {
                        index > it.value
                    }
                    val index = providers.indexOfFirst { it.id == currentProvider?.key }
                    if (index >= 0) {
                        providerBadgeListState.animateScrollToItem(index)
                    } else {
                        providerBadgeListState.requestScrollToItem(0)
                    }
                } else {
                    providerBadgeListState.requestScrollToItem(0)
                }
            }
    }
    if (providers.isNotEmpty()) {
        val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
        // V3 model-picker.jsx ProviderChips: 12dp 圆角矩形 + 18×18 logo + 名 + hairline 边
        // 顶部 hairline 分隔 chip 区 与 model list
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(chatTheme.hair),
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 14.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                state = providerBadgeListState,
            ) {
                items(providers) { provider ->
                    // V3: 简化层级 — 999 capsule 外框 + logo (无内嵌 18dp Box + 5dp 圆角矩形).
                    Row(
                        modifier = Modifier
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(chatTheme.surface)
                            .border(
                                BorderStroke(1.dp, chatTheme.hair),
                                androidx.compose.foundation.shape.CircleShape,
                            )
                            .clickable {
                                val position = providerPositions[provider.id] ?: 0
                                coroutineScope.launch {
                                    lazyListState.animateScrollToItem(position)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // V3: 去掉 provider 图标, 胶囊只剩 provider 名 (用户偏好)
                        Text(
                            text = provider.name,
                            fontSize = 12.5.sp,
                            color = chatTheme.ink,
                            letterSpacing = 0.2.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelItem(
    model: Model,
    providerSetting: ProviderSetting,
    select: Boolean,
    onSelect: (Model) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    tail: @Composable RowScope.() -> Unit = {},
    dragHandle: @Composable (RowScope.() -> Unit)? = null,
    // V3 active 模型的 thinking-level segment（slot）
    thinkingSegment: (@Composable () -> Unit)? = null,
) {
    val navController = LocalNavController.current
    val interactionSource = remember { MutableInteractionSource() }
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    // V3 model-picker.jsx: active 卡片 accentSoft 填底 + accent 文字 + 边线 22% accent
    // 非 active 用 surface + ink + hair 边线
    // V3: clickable 移到 Card 外层 modifier — 之前在内部 Column 上, 选中 ripple 只在 padding
    //   之内, Card border 内但 padding 外的边角区域点不到, 看着"选中区分裂".
    Card(
        modifier = modifier.combinedClickable(
            enabled = true,
            onLongClick = {
                onDismiss()
                navController.navigate(
                    Screen.SettingProviderDetail(providerSetting.id.toString())
                )
            },
            onClick = { onSelect(model) },
            interactionSource = interactionSource,
            indication = LocalIndication.current,
        ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (select) chatTheme.accentSoft else chatTheme.surface,
            contentColor = if (select) chatTheme.accent else chatTheme.ink,
        ),
        border = if (select) {
            androidx.compose.foundation.BorderStroke(
                1.dp,
                chatTheme.accent.copy(alpha = 0.22f),
            )
        } else {
            androidx.compose.foundation.BorderStroke(1.dp, chatTheme.hair)
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val tokens = LocalAmberTokens.current
                // Graphite §6.2 row: mono "name … ctx", accent only when selected, no badge.
                Text(
                    text = model.displayName,
                    style = LocalAmberType.current.meta.copy(
                        fontSize = 14.sp,
                        fontWeight = if (select) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (select) chatTheme.accent else tokens.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.weight(1f))
                model.contextWindowTokens?.let { ctx ->
                    Text(
                        text = ctx.formatNumber(),
                        style = LocalAmberType.current.meta.copy(fontSize = 11.5.sp),
                        color = tokens.ink4,
                        maxLines = 1,
                    )
                }
                tail()
            }
            // ── 第二行：thinking-level segment（仅 active + REASONING 时显示）
            thinkingSegment?.let {
                Box(modifier = Modifier.padding(top = 6.dp)) {
                    it()
                }
            }
        }
    }
}

/**
 * V3 model-picker.jsx CapIcon —— 14dp monochrome stroke 风的 capability 图标行。
 * 每个模型固定显示 chat，按 abilities 加 tool/sci，按 modalities 加 T>I/I>T 等。
 */
@Composable
private fun V3CapabilityIcons(model: Model, tint: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // chat — 所有 CHAT 模型默认显示
        if (model.type == ModelType.CHAT) {
            Icon(
                imageVector = HugeIcons.Message01,
                contentDescription = "chat",
                modifier = Modifier.size(14.dp),
                tint = tint,
            )
        }
        // T>T or T>I —— 按 input/output modalities 推断
        val hasImg = model.outputModalities.fastAny { it == Modality.IMAGE } ||
            model.inputModalities.fastAny { it == Modality.IMAGE }
        Icon(
            imageVector = if (hasImg) HugeIcons.Image03 else HugeIcons.Text,
            contentDescription = if (hasImg) "image" else "text",
            modifier = Modifier.size(14.dp),
            tint = tint,
        )
        // tool wrench —— TOOL ability
        if (model.abilities.contains(app.amber.ai.provider.ModelAbility.TOOL)) {
            Icon(
                imageVector = HugeIcons.Wrench01,
                contentDescription = "tool",
                modifier = Modifier.size(14.dp),
                tint = tint,
            )
        }
        // sci/magic —— REASONING ability（设计稿是原子/sci 图标，库里最接近 AiMagic）
        if (model.abilities.contains(app.amber.ai.provider.ModelAbility.REASONING)) {
            Icon(
                imageVector = HugeIcons.AiMagic,
                contentDescription = "reasoning",
                modifier = Modifier.size(14.dp),
                tint = tint,
            )
        }
    }
}

@Composable
fun ModelTypeTag(model: Model) {
    Tag(
        type = TagType.INFO
    ) {
        Text(
            text = stringResource(
                when (model.type) {
                    ModelType.CHAT -> R.string.setting_provider_page_chat_model
                    ModelType.EMBEDDING -> R.string.setting_provider_page_embedding_model
                    ModelType.IMAGE -> R.string.setting_provider_page_image_model
                }
            )
        )
    }
}

@Composable
fun ModelModalityTag(model: Model) {
    Tag(
        type = TagType.SUCCESS
    ) {
        model.inputModalities.fastForEach { modality ->
            Icon(
                imageVector = when (modality) {
                    Modality.TEXT -> HugeIcons.Text
                    Modality.IMAGE -> HugeIcons.Image03
                    Modality.AUDIO -> HugeIcons.Text
                },
                contentDescription = null,
                modifier = Modifier
                    .size(LocalTextStyle.current.lineHeight.toDp())
                    .padding(1.dp)
            )
        }
        Icon(
            imageVector = HugeIcons.ArrowRight01,
            contentDescription = null,
            modifier = Modifier.size(LocalTextStyle.current.lineHeight.toDp())
        )
        model.outputModalities.fastForEach { modality ->
            Icon(
                imageVector = when (modality) {
                    Modality.TEXT -> HugeIcons.Text
                    Modality.IMAGE -> HugeIcons.Image03
                    Modality.AUDIO -> HugeIcons.Text
                },
                contentDescription = null,
                modifier = Modifier
                    .size(LocalTextStyle.current.lineHeight.toDp())
                    .padding(1.dp)
            )
        }
    }
}

@Composable
fun ModelAbilityTag(model: Model) {
    model.abilities.fastForEach { ability ->
        when (ability) {
            ModelAbility.TOOL -> {
                Tag(
                    type = TagType.WARNING
                ) {
                    Icon(
                        imageVector = HugeIcons.Tools,
                        contentDescription = null,
                        modifier = Modifier.size(LocalTextStyle.current.lineHeight.toDp())
                    )
                }
            }

            ModelAbility.REASONING -> {
                Tag(
                    type = TagType.INFO
                ) {
                    Icon(
                        painter = painterResource(R.drawable.deepthink),
                        contentDescription = null,
                        modifier = Modifier.size(LocalTextStyle.current.lineHeight.toDp()),
                    )
                }
            }
        }
    }
}

private fun ProviderSetting.isCodexOAuthProvider(): Boolean {
    return this is ProviderSetting.OpenAI && authMode == OpenAIAuthMode.CODEX_OAUTH
}

private fun ProviderSetting.isHiddenCodexOAuthModel(model: Model): Boolean {
    return isCodexOAuthProvider() && model.isCodexOAuthReviewModel()
}

// V3: hasUsableAuth 已迁到 app.amber.ai.provider.hasUsableAuth (ai/ProviderSetting.kt),
// picker (这里) 和 data 层 fallback 共用同一份判定 (避免 picker 显示但 fallback 漏选
// 出无 auth 的模型). 调用方直接 import app.amber.ai.provider.hasUsableAuth.

/**
 * Phase 3.5 thinking-level segment —— model-picker.jsx 的 ThinkingLevel 段控。
 *
 * 设计稿用 off/on/low/med/high/xhigh/max 等不同模型不同段集。Kotlin 这边统一用
 * [ReasoningLevel] 7 个值，但 XHIGH 跟 HIGH 视觉太接近，UI 上跳过；展示 6 段：
 *   OFF | AUTO | LOW | MEDIUM | HIGH | MAX
 *
 * 主题感知：active 段填 chatTheme.accent + onAccent 文字；非 active 文字 chatTheme.ink；
 *           容器 chatTheme.searchBarBg + hair 1dp 描边。
 */
/**
 * 按模型推断 reasoning level 段集。
 *
 * 来源：
 *  - DeepSeek: off/high/max (https://api-docs.deepseek.com/zh-cn/guides/thinking_mode)
 *  - OpenAI gpt-5: low/medium/high/xhigh
 *  - Anthropic claude: low/medium/high/xhigh/max
 *  - Kimi / GLM: off/auto (2 段)
 *  - 默认: off/auto/low/med/high/max (6 段)
 */
internal fun reasoningLevelsForModel(model: Model): List<Pair<app.amber.ai.core.ReasoningLevel, String>> {
    val id = model.modelId.lowercase()
    return when {
        id.contains("deepseek") -> listOf(
            app.amber.ai.core.ReasoningLevel.OFF to "off",
            app.amber.ai.core.ReasoningLevel.HIGH to "high",
            app.amber.ai.core.ReasoningLevel.MAX to "max",
        )
        // Claude (Anthropic) extended thinking + adaptive auto：
        // Anthropic 在 claude-sonnet-4.5 / opus-4.1 起暴露 thinking_budget=auto，模型自己
        // 决定多少 token 用来思考（无人工指定 budget）。设计稿 model-picker.jsx 标 auto
        // 作为首段，方便用户日常聊天直接选 "让 Claude 自己决定" 而不用挑 low/med/high。
        // 段集：auto / low / med / high / xhigh / max (6 段)
        id.contains("claude") -> listOf(
            app.amber.ai.core.ReasoningLevel.AUTO to "auto",
            app.amber.ai.core.ReasoningLevel.LOW to "low",
            app.amber.ai.core.ReasoningLevel.MEDIUM to "med",
            app.amber.ai.core.ReasoningLevel.HIGH to "high",
            app.amber.ai.core.ReasoningLevel.XHIGH to "xhigh",
            app.amber.ai.core.ReasoningLevel.MAX to "max",
        )
        id.contains("gpt") || id.contains("codex") || id.contains("o1") || id.contains("o3") || id.contains("o4") -> listOf(
            app.amber.ai.core.ReasoningLevel.LOW to "low",
            app.amber.ai.core.ReasoningLevel.MEDIUM to "med",
            app.amber.ai.core.ReasoningLevel.HIGH to "high",
            app.amber.ai.core.ReasoningLevel.XHIGH to "xhigh",
        )
        id.contains("kimi") || id.contains("glm") || id.contains("zhipu") -> listOf(
            app.amber.ai.core.ReasoningLevel.OFF to "off",
            app.amber.ai.core.ReasoningLevel.AUTO to "auto",
        )
        else -> listOf(
            app.amber.ai.core.ReasoningLevel.OFF to "off",
            app.amber.ai.core.ReasoningLevel.AUTO to "auto",
        )
    }
}

@Composable
internal fun ThinkingLevelSegment(
    levels: List<Pair<app.amber.ai.core.ReasoningLevel, String>>,
    current: app.amber.ai.core.ReasoningLevel,
    onChange: (app.amber.ai.core.ReasoningLevel) -> Unit,
) {
    val theme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    // V3 紧凑段控: 外 padding 1.5dp + 内段 vertical 1dp + 字号 10sp (再压扁一档)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(theme.surface)
            .border(
                BorderStroke(1.dp, theme.hair),
                shape = androidx.compose.foundation.shape.CircleShape,
            )
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        levels.forEach { (level, label) ->
            val isActive = level == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (isActive) theme.accent else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onChange(level) }
                    .padding(vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.2.sp,
                    lineHeight = 13.sp,
                    color = if (isActive) theme.onAccent else theme.ink,
                    maxLines = 1,
                )
            }
        }
    }
}

/** V3 grouped 卡内的 model 行 —— ModelItem 但去掉外层 Card 包装，仅渲染内容行。
 *  设计稿：同 provider 多 model 在一张 SubCard 内用 hairline 分隔，所以单行不需要自己的 Card。
 */
@Composable
private fun ModelItemRow(
    model: Model,
    providerSetting: ProviderSetting,
    onSelect: (Model) -> Unit,
    onDismiss: () -> Unit,
    isActive: Boolean = false,
    tail: @Composable RowScope.() -> Unit = {},
) {
    val navController = LocalNavController.current
    val interactionSource = remember { MutableInteractionSource() }
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val tokens = LocalAmberTokens.current
    // Graphite §6.2 row: mono "name … ctx", accent only when selected, no logo/check/badge.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = true,
                onLongClick = {
                    onDismiss()
                    navController.navigate(
                        Screen.SettingProviderDetail(providerSetting.id.toString())
                    )
                },
                onClick = { onSelect(model) },
                interactionSource = interactionSource,
                indication = LocalIndication.current,
            )
            .padding(start = 28.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
    ) {
        Text(
            text = model.displayName,
            style = LocalAmberType.current.meta.copy(
                fontSize = 14.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (isActive) chatTheme.accent else tokens.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(modifier = Modifier.weight(1f))
        model.contextWindowTokens?.let { ctx ->
            Text(
                text = ctx.formatNumber(),
                style = LocalAmberType.current.meta.copy(fontSize = 11.5.sp),
                color = tokens.ink4,
                maxLines = 1,
            )
        }
        tail()
    }
}

/** 收藏图标 —— 主题色感知的心形 */
@Composable
private fun FavoriteToggleIcon(
    favorite: Boolean,
    isActive: Boolean,
    onToggle: () -> Unit,
) {
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (favorite) HeartIcon else HugeIcons.Favourite,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = when {
                favorite -> chatTheme.accent
                isActive -> chatTheme.accent
                else -> chatTheme.inkSoft
            },
        )
    }
}
