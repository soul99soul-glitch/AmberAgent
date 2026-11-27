package app.amber.feature.ui.components.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import app.amber.ai.provider.Modality
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ProviderSetting
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.X
import app.amber.agent.R
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.ui.components.ui.AutoAIIcon
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import org.koin.compose.koinInject
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
    /** Fully custom trigger; receives the open-picker callback. Null = built-in triggers. */
    customTrigger: (@Composable (openPicker: () -> Unit) -> Unit)? = null,
    onSelect: (Model) -> Unit
) {
    var popup by remember { mutableStateOf(false) }
    val visibleProviders = providers
    val model = if ((customTrigger != null && !onlyIcon) || modelId == null) {
        null
    } else {
        val modelIndex = remember(visibleProviders, type) {
            visibleProviders.buildModelProviderIndex(type)
        }
        modelIndex[modelId]?.model
    }

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
                    imageVector = Lucide.ArrowDown,
                    contentDescription = null,
                    tint = tokens.ink3,
                    modifier = Modifier.size(14.dp),
                )
            }
        } else if (inline) {
            // settings-models.jsx 设计稿：22dp logo + 14.5sp accent W500 model name + 12dp chevron-down
            val inlineTokens = LocalAmberTokens.current
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
                    color = inlineTokens.accent,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    imageVector = Lucide.ArrowDown,
                    contentDescription = null,
                    tint = inlineTokens.accent,
                    modifier = Modifier.size(12.dp),
                )
                if (allowClear && model != null) {
                    IconButton(
                        onClick = { onClear?.invoke() ?: onSelect(Model()) },
                        enabled = enabled,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Lucide.X,
                            contentDescription = clearContentDescription ?: stringResource(R.string.clear),
                            tint = inlineTokens.ink4,
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
                            imageVector = Lucide.X,
                            contentDescription = clearContentDescription ?: stringResource(R.string.clear)
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
                    imageVector = Lucide.Brain,
                    contentDescription = stringResource(R.string.setting_model_page_chat_model),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    if (popup) {
        ModelPickerSheet(
            modelId = modelId,
            providers = visibleProviders,
            type = type,
            preferredInputModality = preferredInputModality,
            onSelect = onSelect,
            onDismiss = { popup = false },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    modelId: Uuid?,
    providers: List<ProviderSetting>,
    type: ModelType,
    onSelect: (Model) -> Unit,
    onDismiss: () -> Unit,
    preferredInputModality: Modality? = null,
    allowPartialExpansion: Boolean = false,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val settingsStore: SettingsAggregator = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = !allowPartialExpansion)
    val scope = rememberCoroutineScope()
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val tokens = LocalAmberTokens.current
    val availableProviders = remember(providers, type) {
        val enabledProviders = providers.filter { it.enabled }
        val visibleModels = enabledProviders.pickerModelsByProvider(type, preferredInputModality = null)
        enabledProviders.filter { visibleModels[it.id].orEmpty().isNotEmpty() }
    }
    var closing by remember { mutableStateOf(false) }
    fun close(selectedModel: Model? = null) {
        if (closing) return
        closing = true
        selectedModel?.let(onSelect)
        scope.launch {
            try {
                state.hide()
                onDismiss()
            } finally {
                closing = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = tokens.bg,
        contentColor = tokens.ink,
        scrimColor = Color.Black.copy(alpha = if (tokens.isDark) 0.48f else 0.22f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .background(tokens.ink3.copy(alpha = 0.36f), CircleShape),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight(if (allowPartialExpansion) 1f else 0.7f)
                .testTag("model-picker-sheet"),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.model_list_select_model),
                        style = LocalAmberType.current.screenTitle.copy(fontSize = 18.sp, lineHeight = 24.sp),
                        color = tokens.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!allowPartialExpansion) {
                        Text(
                            text = stringResource(
                                when (type) {
                                    ModelType.CHAT -> R.string.setting_model_page_chat_model
                                    ModelType.IMAGE -> R.string.setting_model_page_image_gen_model
                                    ModelType.EMBEDDING -> R.string.setting_provider_page_embedding_model
                                }
                            ),
                            style = LocalAmberType.current.meta.copy(fontSize = 12.sp, lineHeight = 16.sp),
                            color = tokens.ink3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (allowPartialExpansion) {
                    val closeA11y = stringResource(R.string.chat_page_close)
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClick = { close() })
                            .semantics { contentDescription = closeA11y },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Lucide.X,
                            contentDescription = null,
                            tint = tokens.ink3,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = tokens.line)
            header?.invoke()
            ModelList(
                currentModel = modelId,
                providers = availableProviders,
                modelType = type,
                preferredInputModality = preferredInputModality,
                favoriteModelIds = settings.favoriteModels,
                onFavoriteModelsChange = { ids ->
                    scope.launch {
                        settingsStore.update { current -> current.copy(favoriteModels = ids) }
                    }
                },
                onSelect = { close(it) },
                onDismiss = { close() },
            )
            footer?.invoke()
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
