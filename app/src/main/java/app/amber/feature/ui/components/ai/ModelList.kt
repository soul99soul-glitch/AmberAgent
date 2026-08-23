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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
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
    enabled: Boolean = true,
    allowClear: Boolean = false,
    emptyLabel: String? = null,
    clearContentDescription: String? = null,
    preferredInputModality: Modality? = null,
    onClear: (() -> Unit)? = null,
    // Phase 3.5 thinking-level segment：仅 chat 主入口（ChatPage TopBar / ChatInput）传入这两个
    // 参数即可在 active model 卡片下显示 reasoning 切换段；其他调用方默认 null 即不渲染
    currentAssistant: app.amber.core.model.Assistant? = null,
    onUpdateAssistant: ((app.amber.core.model.Assistant) -> Unit)? = null,
    /** Fully custom trigger; receives the open-picker callback. Null = built-in triggers. */
    customTrigger: (@Composable (openPicker: () -> Unit) -> Unit)? = null,
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
        if (customTrigger != null) {
            customTrigger { popup = true }
        } else if (minimalText) {
            // Graphite §6.2 model-menu trigger: MONO model-id (LocalAmberType.meta) + small
            // dropdown chevron icon. Capsule ripple (.clip(CircleShape) before .clickable).
            val tokens = LocalAmberTokens.current
            Row(
                modifier = modifier
                    .heightIn(min = 48.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .clickable(enabled = enabled) { popup = true }
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
                modifier = modifier
                    .heightIn(min = 48.dp)
                    .clickable(enabled = enabled) { popup = true },
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
                        enabled = enabled,
                        modifier = Modifier.size(48.dp),
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
            val chipShape = RoundedCornerShape(12.dp)
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Surface(
                    onClick = { popup = true },
                    enabled = enabled,
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
                    modifier = modifier,
                    enabled = enabled,
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
                        },
                        enabled = enabled,
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
            enabled = enabled,
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
        val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
        val tokens = LocalAmberTokens.current
        val filteredProviderSettings = remember(visibleProviders, type) {
            visibleProviders.fastFilter {
                it.enabled && it.models.fastAny { model -> model.type == type }
            }
        }
        // Amber Redesign §2: shell 22dp top radius. §6: terracotta `//` signboard
        // (the ONE allowed terminal glyph) + cn title + mono count.
        val totalModels = remember(filteredProviderSettings) {
            filteredProviderSettings.sumOf { provider ->
                provider.models.count { it.type == type && !provider.isHiddenCodexOAuthModel(it) }
            }
        }
        ModalBottomSheet(
            onDismissRequest = { popup = false },
            sheetState = state,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(
                topStart = 22.dp,
                topEnd = 22.dp,
                bottomStart = 0.dp,
                bottomEnd = 0.dp,
            ),
            containerColor = chatTheme.surface,
            scrimColor = chatTheme.sheetBackdrop,
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(top = 10.dp, bottom = 4.dp)
                        .width(36.dp)
                        .height(4.dp)
                        .background(chatTheme.dragHandle),
                )
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight(0.8f)
                    .imePadding(),
            ) {
                // ── Title row: // 招牌 + 选择模型 + 右对齐计数 ──────────────
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp)
                        .padding(top = 6.dp, bottom = 14.dp),
                ) {
                    Text(
                        text = "//",
                        style = LocalAmberType.current.meta.copy(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = chatTheme.accent,
                    )
                    Text(
                        text = stringResource(R.string.model_list_select_model),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        ),
                        color = chatTheme.ink,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "$totalModels · ${filteredProviderSettings.size}",
                        style = LocalAmberType.current.meta.copy(
                            fontSize = 11.sp,
                            fontFeatureSettings = "tnum",
                        ),
                        color = tokens.ink3,
                    )
                }
                // ── Search field lives inside ModelList (it owns searchKeywords) ──
                ModelList(
                    currentModel = modelId,
                    providers = filteredProviderSettings,
                    modelType = type,
                    preferredInputModality = preferredInputModality,
                    currentAssistant = currentAssistant,
                    onUpdateAssistant = onUpdateAssistant,
                    onSelect = { selectedModel ->
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
                    },
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

    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    // Amber Redesign §2: search field 12dp radius, surface bg, hairline border.
    // §6: input field is a control → 12dp, NOT the 50% pill the old code used.
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = chatTheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, chatTheme.hair),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .padding(bottom = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            modifier = Modifier
                .padding(horizontal = 13.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Search01,
                contentDescription = null,
                tint = chatTheme.inkFaint,
                modifier = Modifier.size(18.dp),
            )
            BasicTextField(
                value = searchKeywords,
                onValueChange = { searchKeywords = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = chatTheme.ink),
                cursorBrush = SolidColor(chatTheme.accent),
                decorationBox = { inner ->
                    if (searchKeywords.isEmpty()) {
                        Text(
                            text = stringResource(R.string.model_list_search_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = chatTheme.inkFaint,
                        )
                    }
                    inner()
                },
                modifier = Modifier.weight(1f),
            )
            if (searchKeywords.isNotEmpty()) {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = stringResource(R.string.cancel),
                    tint = chatTheme.inkFaint,
                    modifier = Modifier
                        .size(18.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .clickable { searchKeywords = "" },
                )
            }
        }
    }

    // Amber Redesign §2/§3: list rows are 0-radius full-bleed (no cards), groups
    // separated by mono section labels + whitespace, rows by 1px hairlines.
    // Old code used spacedBy(8dp) + Card per group → double-border violation.
    LazyColumn(
        state = lazyListState,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 4.dp),
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
                // Amber Redesign §3: section label = mono uppercase + 0.15em
                // tracking + ink-3, NOT primary blue. §4: accent budget — section
                // headers are neutral, not terracotta.
                val tokens = LocalAmberTokens.current
                Text(
                    text = stringResource(R.string.model_list_favorite).uppercase(),
                    style = LocalAmberType.current.meta.copy(
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.15.sp * (stringResource(R.string.model_list_favorite).length),
                    ),
                    color = tokens.ink3,
                    modifier = Modifier
                        .padding(bottom = 6.dp, top = 12.dp)
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

        // Amber Redesign §3: provider groups are full-bleed flat sections (no
        // Card/border/16dp). Group-to-group separation = 1px hairline at the TOP
        // of each group (except the first). Row-to-row = hairline between models.
        // One layer of separation per level — no "bordered card + inner hairline".
        providers.forEachIndexed { providerIndex, providerSetting ->
            val groupModels = searchFilteredModelsByProvider[providerSetting.id].orEmpty()
            if (groupModels.isEmpty()) return@forEachIndexed

            val providerActive = groupModels.fastAny { it.id == currentModel }

            item(key = "group:${providerSetting.id}") {
                val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
                val tokens = LocalAmberTokens.current
                // Default-expand a provider while searching, or the one holding the active
                // model; collapsed otherwise. Re-keyed by search term so a query opens matches.
                var expanded by remember(providerSetting.id, searchKeywords) {
                    mutableStateOf(searchKeywords.isNotBlank() || providerActive)
                }
                Column(modifier = Modifier.animateItem()) {
                    // Group separator: hairline above each provider EXCEPT the first
                    // (the favorite section or the first provider sits flush at top).
                    if (providerIndex > 0 || favoriteModels.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(chatTheme.hair),
                        )
                    }
                    // Accordion header: cn provider name (accent when active) +
                    // mono model count + chevron. No card, no border, full-bleed.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded }
                            .padding(vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = providerSetting.name,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 14.5.sp,
                                fontWeight = if (providerActive) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                            color = if (providerActive) chatTheme.accent else chatTheme.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // mono model count
                        Text(
                            text = groupModels.size.toString(),
                            style = LocalAmberType.current.meta.copy(
                                fontSize = 11.sp,
                                fontFeatureSettings = "tnum",
                            ),
                            color = tokens.ink3,
                        )
                        // chevron: rotates -90°→0° on expand (like the HTML sample)
                        Icon(
                            imageVector = HugeIcons.ArrowDown01,
                            contentDescription = null,
                            tint = tokens.ink3,
                            modifier = Modifier
                                .size(17.dp)
                                .graphicsLayer {
                                    rotationZ = if (expanded) 0f else -90f
                                },
                        )
                    }
                    androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                        Column {
                            groupModels.fastForEach { model ->
                                val isActive = model.id == currentModel
                                // Row separator: hairline between models (full-bleed, left-aligned)
                                Box(
                                    modifier = Modifier
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
                                    startPadding = 16.dp,
                                    endPadding = 2.dp,
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
        val tokens = LocalAmberTokens.current
        // Amber Redesign §2: chips are small controls → 12dp radius (NOT 999 pill).
        // §3: no top hairline separator (that would be a double border with the
        // list's own grouping above). Chips use surface-2 fill, no stroke — the
        // fill alone distinguishes them, no need for a border.
        Column(modifier = Modifier.fillMaxWidth()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 14.dp),
                contentPadding = PaddingValues(horizontal = 22.dp),
                state = providerBadgeListState,
            ) {
                items(providers) { provider ->
                    Row(
                        modifier = Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .background(chatTheme.surfaceEdge.copy(alpha = 0.4f))
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
                        // cn provider name (chips are labels, not mono IDs)
                        Text(
                            text = provider.name,
                            fontSize = 12.5.sp,
                            color = tokens.ink2,
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
fun ProviderAccordionModelPicker(
    currentModel: Uuid?,
    providers: List<ProviderSetting>,
    modelType: ModelType,
    onSelect: (Model) -> Unit,
    modifier: Modifier = Modifier,
    preferredInputModality: Modality? = null,
    clearLabel: String? = null,
    onClear: (() -> Unit)? = null,
    dense: Boolean = false,
) {
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val tokens = LocalAmberTokens.current
    val providerFontSize = if (dense) 13.sp else 14.sp
    val modelFontSize = if (dense) 13.sp else 14.sp
    val contextFontSize = if (dense) 11.sp else 11.5.sp
    val providerVerticalPadding = if (dense) 7.dp else 11.dp
    val modelVerticalPadding = if (dense) 6.dp else 9.dp
    val trailingSlotWidth = 42.dp
    val trailingEndPadding = if (dense) 0.dp else 6.dp
    val providerStartPadding = if (dense) 30.dp else 14.dp
    val modelStartPadding = if (dense) 54.dp else 28.dp
    val typeFilteredModelsByProvider = remember(providers, modelType, preferredInputModality) {
        providers.associate { provider ->
            provider.id to provider.models.fastFilter {
                it.type == modelType && !provider.isHiddenCodexOAuthModel(it)
            }.prioritizeInputModality(preferredInputModality)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        if (clearLabel != null && onClear != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClear)
                    .padding(
                        start = providerStartPadding,
                        end = trailingEndPadding,
                        top = providerVerticalPadding,
                        bottom = providerVerticalPadding,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = clearLabel,
                    style = LocalAmberType.current.meta.copy(
                        fontSize = providerFontSize,
                        fontWeight = if (currentModel == null) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    color = if (currentModel == null) chatTheme.accent else tokens.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        providers.fastForEach { providerSetting ->
            val groupModels = typeFilteredModelsByProvider[providerSetting.id].orEmpty()
            if (groupModels.isEmpty()) return@fastForEach

            val providerActive = groupModels.fastAny { it.id == currentModel }
            var expanded by remember(providerSetting.id) {
                mutableStateOf(providerActive)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(
                        start = providerStartPadding,
                        end = trailingEndPadding,
                        top = providerVerticalPadding,
                        bottom = providerVerticalPadding,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = providerSetting.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = providerFontSize,
                        fontWeight = if (providerActive) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    color = if (providerActive) chatTheme.accent else tokens.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) "−" else "+",
                    style = LocalAmberType.current.meta.copy(
                        fontSize = if (dense) 16.sp else 18.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = tokens.ink3,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.width(trailingSlotWidth),
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = expanded,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    groupModels.fastForEach { model ->
                        ModelItemRow(
                            model = model,
                            providerSetting = providerSetting,
                            isActive = model.id == currentModel,
                            onSelect = onSelect,
                            onDismiss = {},
                            modelFontSize = modelFontSize,
                            contextFontSize = contextFontSize,
                            verticalPadding = modelVerticalPadding,
                            endPadding = trailingEndPadding,
                            startPadding = modelStartPadding,
                            contextWidth = trailingSlotWidth,
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
    val tokens = LocalAmberTokens.current
    // Amber Redesign §5: selected = accent text + weight 700, NO fill block / NO
    // border / NO check. §2: row = 0 radius full-bleed. §3: hairline between rows
    // (added by the caller's list; this row itself is borderless).
    Column(
        modifier = modifier
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
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 11.dp),
        ) {
            // §6: mono model name, accent + 700 only when selected.
            Text(
                text = model.displayName,
                style = LocalAmberType.current.meta.copy(
                    fontSize = 14.sp,
                    fontWeight = if (select) FontWeight.Bold else FontWeight.Medium,
                ),
                color = if (select) chatTheme.accent else chatTheme.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // §6: right-aligned mono context number (tabular-nums)
            model.contextWindowTokens?.let { ctx ->
                Text(
                    text = ctx.formatNumber(),
                    style = LocalAmberType.current.meta.copy(
                        fontSize = 11.5.sp,
                        fontFeatureSettings = "tnum",
                    ),
                    color = tokens.ink3,
                    maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.width(42.dp),
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
    modelFontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    contextFontSize: androidx.compose.ui.unit.TextUnit = 11.5.sp,
    verticalPadding: androidx.compose.ui.unit.Dp = 9.dp,
    endPadding: androidx.compose.ui.unit.Dp = 6.dp,
    startPadding: androidx.compose.ui.unit.Dp = 28.dp,
    contextWidth: androidx.compose.ui.unit.Dp = 42.dp,
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
            .padding(start = startPadding, end = endPadding, top = verticalPadding, bottom = verticalPadding),
    ) {
        Text(
            text = model.displayName,
            style = LocalAmberType.current.meta.copy(
                fontSize = modelFontSize,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
            ),
            color = if (isActive) chatTheme.accent else chatTheme.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        model.contextWindowTokens?.let { ctx ->
            Text(
                text = ctx.formatNumber(),
                style = LocalAmberType.current.meta.copy(
                    fontSize = contextFontSize,
                    fontFeatureSettings = "tnum",
                ),
                color = tokens.ink3,
                maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.width(contextWidth),
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
