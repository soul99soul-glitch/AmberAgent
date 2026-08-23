package app.amber.feature.ui.pages.live

import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Sparkles
import app.amber.agent.Screen
import app.amber.ai.provider.ModelType
import app.amber.core.settings.findModelById
import app.amber.core.settings.getCurrentChatModel
import app.amber.feature.live.LiveAnalysisMode
import app.amber.feature.live.LiveFillResult
import app.amber.feature.live.LiveModeCard
import app.amber.feature.live.LiveModeUiState
import app.amber.feature.ui.components.ai.ModelSelector
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.components.ds.LiveDot
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.uuid.Uuid

/**
 * AI 伴随 — Terminal × Modern graphite reskin. Layout follows the design handoff:
 * eyebrow header (// COMPANION ● + 标题), a master toggle card (主开关 + 自动分析 + 暂停),
 * the last-analysis result section, and a CONFIG card (模式 / 气泡 / 模型). Every functional
 * control from the source screen is preserved — reskin, not restructure (design §7.1).
 */
@Composable
fun LiveCompanionPage(vm: LiveCompanionVM = koinViewModel()) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val liveSetting = settings.agentRuntime.liveMode
    val tokens = LocalAmberTokens.current
    val scrollState = rememberScrollState()

    val companionModelId = remember(settings, liveSetting.companionModelId) {
        val id = liveSetting.companionModelId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        (id?.let { settings.findModelById(it) } ?: settings.getCurrentChatModel())?.modelId
    }

    DisposableEffect(Unit) {
        onDispose { vm.stop() }
    }

    Scaffold(
        topBar = {
            LiveHeader(
                live = state.active && !state.paused && state.error == null,
                onBack = { navController.popBackStack() },
                onSettings = { navController.navigate(Screen.SettingAgentExecution) },
            )
        },
        containerColor = tokens.bg,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(tokens.bg)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // ── 主开关卡 ──
            MasterCard(
                state = state,
                autoRefresh = liveSetting.autoRefresh,
                onMaster = { on -> if (on) vm.start() else vm.stop() },
                onToggleAutoRefresh = vm::setAutoRefresh,
                onPauseResume = vm::pauseOrResume,
            )

            // ── 阻塞态引导 / 错误 / 进度 ──
            when {
                state.needsAccessibility -> GuidanceCard(
                    title = "需要开启无障碍",
                    body = "伴随要读取另一侧应用的内容。开启 AmberAgent 无障碍服务后，再回到这里开始。",
                    action = "去开启",
                    onAction = { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) },
                )

                state.noModelConfigured -> GuidanceCard(
                    title = "需要配置模型",
                    body = "伴随会复用聊天模型做短分析。先选一个可用模型，再回来启动。",
                    action = "去设置",
                    onAction = { navController.navigate(Screen.SettingModels) },
                )
            }

            state.error?.takeIf { it.isNotBlank() }?.let { error ->
                ErrorNote(
                    title = state.statusText,
                    error = error,
                    retrying = state.nextAnalysisAfterMillis > System.currentTimeMillis(),
                )
            }

            if (state.requestedAction.isNotBlank()) {
                ActionProgressCard(state)
            }

            // ── 上次分析 ──
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("上次分析")
                val card = state.card
                if (card != null) {
                    LiveResultCard(
                        card = card,
                        state = state,
                        modelId = companionModelId,
                        actionLabel = state.resultActionLabel(),
                        stale = state.requestedAction.isNotBlank(),
                        pendingAction = state.requestedAction,
                        enabled = state.active && !state.paused && !state.analyzing,
                        onInstruction = vm::submitFocusInstruction,
                        onFillDraft = {
                            val msg = when (vm.fillDraft()) {
                                LiveFillResult.FILLED -> "已填入，发送请自己按"
                                LiveFillResult.COPIED -> "没找到输入框，草稿已复制到剪贴板"
                                LiveFillResult.NO_DRAFT -> "还没有可填入的草稿"
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        },
                    )
                } else {
                    EmptyResultCard(state)
                }
            }

            // ── CONFIG ──
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("CONFIG")
                ConfigCard(
                    aggressive = liveSetting.analysisMode == LiveAnalysisMode.AGGRESSIVE,
                    onSelectMode = { aggressive ->
                        vm.setAnalysisMode(if (aggressive) LiveAnalysisMode.AGGRESSIVE else LiveAnalysisMode.CONSERVATIVE)
                    },
                    bubbleEnabled = liveSetting.bubbleEnabled,
                    onToggleBubble = vm::setBubbleEnabled,
                    modelId = liveSetting.companionModelId?.let { runCatching { Uuid.parse(it) }.getOrNull() },
                    providers = settings.providers,
                    onClearModel = { vm.setCompanionModel(null) },
                    onSelectModel = { vm.setCompanionModel(it) },
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// ───────────────────────────── header ─────────────────────────────

@Composable
private fun LiveHeader(live: Boolean, onBack: () -> Unit, onSettings: () -> Unit) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(t.bg)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(40.dp).pressable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(HugeIcons.ArrowLeft01, contentDescription = "返回", tint = t.ink, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("//", style = type.eyebrow, color = t.accent)
                        Text(" COMPANION", style = type.eyebrow, color = t.ink3)
                    }
                    LiveDot(idle = !live, dotSize = 4.dp)
                }
                Text("AI 伴随", style = type.screenTitle, color = t.ink)
            }
            Box(
                modifier = Modifier.size(40.dp).pressable(onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) {
                Icon(HugeIcons.Settings03, contentDescription = "设置", tint = t.ink2, modifier = Modifier.size(20.dp))
            }
        }
        Hairline()
    }
}

// ───────────────────────────── master card ─────────────────────────────

@Composable
private fun MasterCard(
    state: LiveModeUiState,
    autoRefresh: Boolean,
    onMaster: (Boolean) -> Unit,
    onToggleAutoRefresh: (Boolean) -> Unit,
    onPauseResume: () -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    AmberCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val live = state.active && !state.paused && state.error == null
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(t.surface2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    HugeIcons.Eye,
                    contentDescription = null,
                    tint = if (live) t.accent else t.ink3,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = state.masterTitle(),
                        style = type.sessionTitle,
                        color = t.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.analyzing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 2.dp,
                            color = t.accent,
                        )
                    }
                }
                Text(
                    text = state.masterSubtitle(autoRefresh),
                    style = type.secondary,
                    color = t.ink3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AmberToggle(checked = state.active, onCheckedChange = onMaster)
        }

        Hairline()
        ToggleRow(
            label = "自动分析",
            hint = "页面稳定后触发",
            checked = autoRefresh,
            onCheckedChange = onToggleAutoRefresh,
        )

        if (state.active) {
            Hairline()
            ToggleRow(
                label = "暂停伴随",
                hint = if (state.paused) "已暂停读取" else "临时停止分析",
                checked = state.paused,
                onCheckedChange = { onPauseResume() },
            )
        }
    }
}

// ───────────────────────────── config card ─────────────────────────────

@Composable
private fun ConfigCard(
    aggressive: Boolean,
    onSelectMode: (Boolean) -> Unit,
    bubbleEnabled: Boolean,
    onToggleBubble: (Boolean) -> Unit,
    modelId: Uuid?,
    providers: List<app.amber.ai.provider.ProviderSetting>,
    onClearModel: () -> Unit,
    onSelectModel: (String) -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    AmberCard {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("分析模式", style = type.body.copy(fontWeight = FontWeight.Medium), color = t.ink, modifier = Modifier.weight(1f))
                Text(if (aggressive) "screenshot" else "text-only", style = type.meta, color = t.ink3)
            }
            AmberSeg(
                options = listOf("保守 · 文字", "激进 · 截屏"),
                selectedIndex = if (aggressive) 1 else 0,
                onSelect = { onSelectMode(it == 1) },
            )
        }

        Hairline()
        ToggleRow(
            label = "悬浮气泡",
            hint = "其它应用上显示",
            checked = bubbleEnabled,
            onCheckedChange = onToggleBubble,
        )

        Hairline()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(HugeIcons.Settings03, contentDescription = null, tint = t.ink3, modifier = Modifier.size(17.dp))
            Text("分析模型", style = type.body.copy(fontWeight = FontWeight.Medium), color = t.ink, modifier = Modifier.weight(1f))
            ModelSelector(
                modelId = modelId,
                providers = providers,
                type = ModelType.CHAT,
                allowClear = true,
                emptyLabel = "跟随聊天模型",
                onClear = onClearModel,
                onSelect = { model -> onSelectModel(model.id.toString()) },
            )
        }
    }
}

// ───────────────────────────── result card ─────────────────────────────

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
private fun LiveResultCard(
    card: LiveModeCard,
    state: LiveModeUiState,
    modelId: String?,
    actionLabel: String,
    stale: Boolean,
    pendingAction: String,
    enabled: Boolean,
    onInstruction: (String) -> Unit,
    onFillDraft: () -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    AmberCard {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // source pill + timestamp
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(t.surface2)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(HugeIcons.Eye, contentDescription = null, tint = t.ink3, modifier = Modifier.size(13.dp))
                    Text(
                        text = state.currentAppLabel.ifBlank { "当前应用" },
                        style = type.tinyTag,
                        color = t.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (state.lastUpdatedAtMillis > 0L) {
                    Text(text = timeFormat.format(state.lastUpdatedAtMillis), style = type.meta, color = t.ink4)
                }
            }

            if (stale && pendingAction.isNotBlank()) {
                Text(
                    text = "新的“$pendingAction”还在处理，下面先保留上一张卡片。",
                    style = type.secondary,
                    color = t.ink3,
                )
            }

            when (actionLabel) {
                "找重点" -> {
                    LiveSection(title = "结论", content = card.watching.ifBlank { "不确定" }, prominent = true)
                    LiveSection(title = "重点", items = card.keyPoints, emptyText = "暂时没有提取到明确重点。")
                }
                "总结" -> {
                    LiveSection(title = "总结", content = card.watching.ifBlank { "这块屏幕信息还不够明确。" }, prominent = true)
                    LiveSection(title = "关键信息", items = card.keyPoints)
                }
                "找下一步" -> {
                    LiveSection(title = "下一步建议", items = card.suggestions, emptyText = "暂时没有足够信息判断下一步。")
                    LiveSection(title = "判断依据", items = card.keyPoints)
                    LiveSection(title = "正在看什么", content = card.watching.ifBlank { "这块屏幕信息还不够明确。" })
                }
                "查风险" -> {
                    LiveSection(title = "结论", content = card.watching.ifBlank { "暂未发现明确风险" }, prominent = true)
                    LiveSection(title = "风险点", items = card.keyPoints, emptyText = "暂时没有发现明确风险。")
                }
                "写回复" -> {
                    LiveSection(title = "回复草稿", content = card.suggestions.firstOrNull() ?: card.watching, prominent = true)
                    LiveSection(title = "语气", items = card.keyPoints)
                    PillButton(text = "填入对方输入框", accent = true, onClick = onFillDraft)
                }
                else -> {
                    LiveSection(title = "正在看什么", content = card.watching.ifBlank { "这块屏幕信息还不够明确。" }, prominent = true)
                    LiveSection(title = "重点内容", items = card.keyPoints)
                    LiveSection(title = "可以怎么做", items = card.suggestions)
                }
            }

            DynamicActionChips(
                currentAction = if (stale && pendingAction.isNotBlank()) pendingAction else actionLabel,
                enabled = enabled,
                onInstruction = onInstruction,
            )

            if (!modelId.isNullOrBlank()) {
                Hairline()
                Text(text = modelId, style = type.meta, color = t.ink3)
            }
        }
    }
}

@Composable
private fun EmptyResultCard(state: LiveModeUiState) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    AmberCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (state.active) "等待第一张伴随卡片" else "伴随尚未开启",
                style = type.sessionTitle,
                color = t.ink,
            )
            Text(
                text = when {
                    !state.active -> "开启后读取另一侧应用内容，页面稳定后调用模型分析。"
                    state.currentAppLabel.isNotBlank() ->
                        "已读取：${state.currentAppLabel}${state.currentTitle.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}"
                    else -> state.statusText
                },
                style = type.body,
                color = t.ink2,
            )
        }
    }
}

@Composable
private fun LiveSection(
    title: String,
    content: String? = null,
    items: List<String> = emptyList(),
    emptyText: String? = null,
    prominent: Boolean = false,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        SectionLabel(title)
        if (!content.isNullOrBlank()) {
            Text(
                text = content,
                style = if (prominent) type.sessionTitle else type.body,
                color = t.ink,
            )
        }
        items.take(4).forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("·", color = t.accent, style = type.body)
                Text(text = item, style = type.body, color = t.ink, modifier = Modifier.weight(1f))
            }
        }
        if (content.isNullOrBlank() && items.isEmpty() && !emptyText.isNullOrBlank()) {
            Text(text = emptyText, style = type.secondary, color = t.ink3)
        }
    }
}

@Composable
private fun DynamicActionChips(
    currentAction: String,
    enabled: Boolean,
    onInstruction: (String) -> Unit,
) {
    val actions = remember(currentAction) {
        listOf("找重点", "帮我写回复", "有什么风险", "下一步", "总结一下")
            .filterNot { it.liveActionLabel() == currentAction }
            .take(3)
    }
    if (actions.isEmpty()) return
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { action ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(t.surface2)
                    .then(
                        Modifier.pressable(onClick = { if (enabled) onInstruction(action) }, enabled = enabled),
                    )
                    .padding(horizontal = 13.dp, vertical = 7.dp),
            ) {
                Text(
                    text = action,
                    style = type.secondary,
                    color = if (enabled) t.ink2 else t.ink4,
                    maxLines = 1,
                )
            }
        }
    }
}

// ───────────────────────────── alerts ─────────────────────────────

@Composable
private fun ActionProgressCard(state: LiveModeUiState) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val action = state.requestedAction
    val retrying = state.nextAnalysisAfterMillis > System.currentTimeMillis()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(t.surface2)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.analyzing) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = t.accent)
        } else {
            Icon(HugeIcons.Sparkles, contentDescription = null, tint = t.accent, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = when {
                    retrying -> "${action}排队中"
                    state.analyzing -> "正在$action"
                    state.paused -> "${action}已暂停"
                    else -> "已收到：$action"
                },
                style = type.body.copy(fontWeight = FontWeight.Medium),
                color = t.ink,
            )
            Text(
                text = when {
                    retrying -> "模型服务忙，恢复后结果会显示在“${action.resultTitle()}”里。"
                    state.analyzing -> "完成后直接覆盖下面的结果卡片。"
                    state.paused -> "点击继续后再读取屏幕并生成结果。"
                    else -> "等待屏幕稳定后开始，结果会显示在“${action.resultTitle()}”里。"
                },
                style = type.secondary,
                color = t.ink3,
            )
        }
    }
}

@Composable
private fun GuidanceCard(title: String, body: String, action: String, onAction: () -> Unit) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    AmberCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(t.accent))
                Text(title, style = type.sessionTitle, color = t.ink)
            }
            Text(body, style = type.body, color = t.ink2)
            PillButton(text = action, accent = true, onClick = onAction)
        }
    }
}

@Composable
private fun ErrorNote(title: String, error: String, retrying: Boolean) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(t.surface2)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = title, style = type.body.copy(fontWeight = FontWeight.Medium), color = t.ink)
        Text(text = error, style = type.secondary, color = t.ink2)
        if (retrying) {
            Text(
                text = "这不是读取频率导致的错误，而是模型服务暂时不可用。",
                style = type.secondary,
                color = t.ink3,
            )
        }
    }
}

// ───────────────────────────── DS controls ─────────────────────────────

/** Toggle row: cn label (+ optional mono hint) · accent toggle. design §6.2. */
@Composable
private fun ToggleRow(
    label: String,
    hint: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, style = type.body.copy(fontWeight = FontWeight.Medium), color = t.ink, modifier = Modifier.weight(1f))
        if (hint != null) Text(hint, style = type.meta, color = t.ink3)
        AmberToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Accent-fill-when-on toggle, line-2 track when off, white knob (design §6.2). */
@Composable
private fun AmberToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val t = LocalAmberTokens.current
    val trackW = 44.dp
    val trackH = 26.dp
    val knob = 20.dp
    val track by animateColorAsState(if (checked) t.accent else t.line2, label = "toggleTrack")
    val knobOffset by animateDpAsState(if (checked) trackW - knob - 3.dp else 3.dp, label = "toggleKnob")
    Box(
        modifier = Modifier
            .size(trackW, trackH)
            .clip(RoundedCornerShape(999.dp))
            .background(track)
            .pressable(onClick = { if (enabled) onCheckedChange(!checked) }, enabled = enabled),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = knobOffset)
                .size(knob)
                .clip(CircleShape)
                // white knob is a design-system constant (§6.2 "white knob"), not a theme token.
                .background(Color(0xFFFFFFFF)),
        )
    }
}

/** Segmented control: surface-2 track, raised active thumb (design §6.2). */
@Composable
private fun AmberSeg(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(t.surface2)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (active) t.raised else Color.Transparent)
                    .pressable(onClick = { onSelect(index) })
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = type.secondary.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (active) t.ink else t.ink3,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Rounded pill action — accent fill or surface-2 (design §6.1 buttons, compact). */
@Composable
private fun PillButton(text: String, accent: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (accent) t.accent else t.surface2)
            .pressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = type.body.copy(fontWeight = FontWeight.SemiBold),
            color = if (accent) t.accentInk else t.ink,
        )
    }
}


// ───────────────────────────── state helpers ─────────────────────────────

private fun LiveModeUiState.masterTitle(): String =
    when {
        !active -> "未开启"
        paused -> "已暂停"
        error != null && nextAnalysisAfterMillis > System.currentTimeMillis() -> "模型繁忙"
        error != null -> "分析失败"
        analyzing -> "分析中"
        card != null -> "伴随中"
        currentAppLabel.isNotBlank() -> "读取中"
        else -> "已开启"
    }

private fun LiveModeUiState.masterSubtitle(autoRefresh: Boolean): String {
    val target = listOf(currentAppLabel, currentTitle).filter { it.isNotBlank() }.joinToString(" · ")
    val mode = if (autoRefresh) "自动分析" else "手动分析"
    return when {
        !active -> "开启后自动监听前台应用"
        paused -> "已停止读取和模型调用"
        error != null && nextAnalysisAfterMillis > System.currentTimeMillis() -> "仍会读取屏幕，模型调用退避中"
        requestedAction.isNotBlank() -> "结果会显示在“${requestedAction.resultTitle()}”里"
        analyzing -> target.ifBlank { "正在整理当前屏幕" }
        target.isNotBlank() -> "$target · $mode"
        else -> "$statusText · $mode"
    }
}

private fun LiveModeUiState.resultActionLabel(): String =
    completedAction.ifBlank {
        currentFocus.liveActionLabel().takeUnless { it == "屏幕分析" } ?: "屏幕分析"
    }

private fun String.liveActionLabel(): String {
    val text = trim()
    return when {
        text.isBlank() -> "屏幕分析"
        "重点" in text -> "找重点"
        "总结" in text || "摘要" in text -> "总结"
        "下一步" in text || "怎么做" in text -> "找下一步"
        "风险" in text || "问题" in text -> "查风险"
        "回复" in text || "回话" in text -> "写回复"
        else -> text.take(12)
    }
}

private fun String.resultTitle(): String = when (this) {
    "屏幕分析" -> "伴随结果"
    "找重点" -> "找重点结果"
    "总结" -> "总结结果"
    "找下一步" -> "下一步建议"
    "查风险" -> "风险点"
    "写回复" -> "回复建议"
    else -> "${this}结果"
}
