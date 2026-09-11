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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Sparkles
import app.amber.agent.Screen
import app.amber.agent.R
import app.amber.ai.provider.ModelType
import app.amber.core.settings.findModelById
import app.amber.core.settings.getCurrentChatModel
import app.amber.core.utils.appLocale
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
import java.util.Date
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
    val fillDraftFilledMessage = stringResource(R.string.live_fill_result_filled)
    val fillDraftCopiedMessage = stringResource(R.string.live_fill_result_copied)
    val fillDraftMissingMessage = stringResource(R.string.live_fill_result_missing)

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
                    title = stringResource(R.string.live_accessibility_required_title),
                    body = stringResource(R.string.live_accessibility_required_body),
                    action = stringResource(R.string.live_open_accessibility_settings),
                    onAction = { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) },
                )

                state.noModelConfigured -> GuidanceCard(
                    title = stringResource(R.string.live_model_required_title),
                    body = stringResource(R.string.live_model_required_body),
                    action = stringResource(R.string.live_open_model_settings),
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
                SectionLabel(stringResource(R.string.live_last_analysis))
                val card = state.card
                if (card != null) {
                    val actionKey = state.resultActionKey()
                    LiveResultCard(
                        card = card,
                        state = state,
                        modelId = companionModelId,
                        actionKey = actionKey,
                        stale = state.requestedAction.isNotBlank(),
                        pendingAction = state.requestedAction,
                        enabled = state.active && !state.paused && !state.analyzing,
                        onInstruction = vm::submitFocusInstruction,
                        onFillDraft = {
                            val msg = when (vm.fillDraft()) {
                                LiveFillResult.FILLED -> fillDraftFilledMessage
                                LiveFillResult.COPIED -> fillDraftCopiedMessage
                                LiveFillResult.NO_DRAFT -> fillDraftMissingMessage
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
                SectionLabel(stringResource(R.string.live_config))
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
                Icon(
                    Lucide.ArrowLeft,
                    contentDescription = stringResource(R.string.back),
                    tint = t.ink,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("//", style = type.eyebrow, color = t.accent)
                        Text(" COMPANION", style = type.eyebrow, color = t.ink3)
                    }
                    LiveDot(idle = !live, dotSize = 4.dp)
                }
                Text(stringResource(R.string.live_companion_title), style = type.screenTitle, color = t.ink)
            }
            Box(
                modifier = Modifier.size(40.dp).pressable(onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.Settings,
                    contentDescription = stringResource(R.string.settings),
                    tint = t.ink2,
                    modifier = Modifier.size(20.dp),
                )
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
                    Lucide.Eye,
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
            label = stringResource(R.string.live_auto_analysis),
            hint = stringResource(R.string.live_auto_analysis_hint),
            checked = autoRefresh,
            onCheckedChange = onToggleAutoRefresh,
        )

        if (state.active) {
            Hairline()
            ToggleRow(
                label = stringResource(R.string.live_pause_companion),
                hint = if (state.paused) {
                    stringResource(R.string.live_paused_reading)
                } else {
                    stringResource(R.string.live_pause_analysis_hint)
                },
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
                Text(
                    stringResource(R.string.live_analysis_mode),
                    style = type.body.copy(fontWeight = FontWeight.Medium),
                    color = t.ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (aggressive) {
                        stringResource(R.string.live_analysis_mode_screenshot)
                    } else {
                        stringResource(R.string.live_analysis_mode_text_only)
                    },
                    style = type.meta,
                    color = t.ink3,
                )
            }
            AmberSeg(
                options = listOf(
                    stringResource(R.string.live_mode_conservative),
                    stringResource(R.string.live_mode_aggressive),
                ),
                selectedIndex = if (aggressive) 1 else 0,
                onSelect = { onSelectMode(it == 1) },
            )
        }

        Hairline()
        ToggleRow(
            label = stringResource(R.string.live_bubble),
            hint = stringResource(R.string.live_bubble_hint),
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
            Icon(Lucide.Settings, contentDescription = null, tint = t.ink3, modifier = Modifier.size(17.dp))
            Text(
                stringResource(R.string.live_analysis_model),
                style = type.body.copy(fontWeight = FontWeight.Medium),
                color = t.ink,
                modifier = Modifier.weight(1f),
            )
            ModelSelector(
                modelId = modelId,
                providers = providers,
                type = ModelType.CHAT,
                allowClear = true,
                emptyLabel = stringResource(R.string.live_follow_chat_model),
                onClear = onClearModel,
                onSelect = { model -> onSelectModel(model.id.toString()) },
            )
        }
    }
}

// ───────────────────────────── result card ─────────────────────────────

private fun formatLiveTime(timestampMs: Long, locale: Locale): String =
    SimpleDateFormat("HH:mm", locale).format(Date(timestampMs))

@Composable
private fun LiveResultCard(
    card: LiveModeCard,
    state: LiveModeUiState,
    modelId: String?,
    actionKey: String,
    stale: Boolean,
    pendingAction: String,
    enabled: Boolean,
    onInstruction: (String) -> Unit,
    onFillDraft: () -> Unit,
) {
    val appLocale = LocalContext.current.appLocale()
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val currentAppFallback = stringResource(R.string.live_current_app)
    val uncertainResultText = stringResource(R.string.live_result_uncertain)
    val screenUnclearText = stringResource(R.string.live_result_screen_unclear)
    val noClearRiskText = stringResource(R.string.live_result_no_clear_risk)
    val actionLabel = actionKey.localizedActionLabel()
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
                    Icon(Lucide.Eye, contentDescription = null, tint = t.ink3, modifier = Modifier.size(13.dp))
                    Text(
                        text = state.currentAppLabel.ifBlank { currentAppFallback },
                        style = type.tinyTag,
                        color = t.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (state.lastUpdatedAtMillis > 0L) {
                    Text(
                        text = formatLiveTime(state.lastUpdatedAtMillis, appLocale),
                        style = type.meta,
                        color = t.ink4,
                    )
                }
            }

            if (stale && pendingAction.isNotBlank()) {
                Text(
                    text = stringResource(
                        R.string.live_new_action_pending,
                        pendingAction.localizedActionLabel(),
                    ),
                    style = type.secondary,
                    color = t.ink3,
                )
            }

            when (actionKey) {
                "找重点" -> {
                    LiveSection(
                        title = stringResource(R.string.live_result_conclusion),
                        content = card.watching.ifBlank { uncertainResultText },
                        prominent = true,
                    )
                    LiveSection(
                        title = stringResource(R.string.live_result_key_points),
                        items = card.keyPoints,
                        emptyText = stringResource(R.string.live_result_no_key_points),
                    )
                }
                "总结" -> {
                    LiveSection(
                        title = stringResource(R.string.live_result_summary),
                        content = card.watching.ifBlank { screenUnclearText },
                        prominent = true,
                    )
                    LiveSection(title = stringResource(R.string.live_result_key_information), items = card.keyPoints)
                }
                "找下一步" -> {
                    LiveSection(
                        title = stringResource(R.string.live_result_next_steps),
                        items = card.suggestions,
                        emptyText = stringResource(R.string.live_result_no_next_step_info),
                    )
                    LiveSection(title = stringResource(R.string.live_result_basis), items = card.keyPoints)
                    LiveSection(
                        title = stringResource(R.string.live_result_what_is_visible),
                        content = card.watching.ifBlank { screenUnclearText },
                    )
                }
                "查风险" -> {
                    LiveSection(
                        title = stringResource(R.string.live_result_conclusion),
                        content = card.watching.ifBlank { noClearRiskText },
                        prominent = true,
                    )
                    LiveSection(
                        title = stringResource(R.string.live_result_risks),
                        items = card.keyPoints,
                        emptyText = stringResource(R.string.live_result_no_risk_points),
                    )
                }
                "写回复" -> {
                    LiveSection(
                        title = stringResource(R.string.live_result_reply_draft),
                        content = card.suggestions.firstOrNull() ?: card.watching,
                        prominent = true,
                    )
                    LiveSection(title = stringResource(R.string.live_result_tone), items = card.keyPoints)
                    PillButton(
                        text = stringResource(R.string.live_fill_other_input),
                        accent = true,
                        onClick = onFillDraft,
                    )
                }
                else -> {
                    LiveSection(
                        title = stringResource(R.string.live_result_what_is_visible),
                        content = card.watching.ifBlank { screenUnclearText },
                        prominent = true,
                    )
                    LiveSection(title = stringResource(R.string.live_result_key_content), items = card.keyPoints)
                    LiveSection(title = stringResource(R.string.live_result_what_to_do), items = card.suggestions)
                }
            }

            DynamicActionChips(
                currentAction = if (stale && pendingAction.isNotBlank()) pendingAction else actionKey,
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
                text = if (state.active) {
                    stringResource(R.string.live_empty_waiting_card)
                } else {
                    stringResource(R.string.live_empty_not_started)
                },
                style = type.sessionTitle,
                color = t.ink,
            )
            Text(
                text = when {
                    !state.active -> stringResource(R.string.live_empty_inactive_hint)
                    state.currentAppLabel.isNotBlank() -> stringResource(
                        R.string.live_empty_current_app,
                        listOf(state.currentAppLabel, state.currentTitle)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                    )
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
    val actions = listOf(
        "找重点" to stringResource(R.string.live_action_find_focus),
        "帮我写回复" to stringResource(R.string.live_action_write_reply),
        "有什么风险" to stringResource(R.string.live_action_check_risks),
        "下一步" to stringResource(R.string.live_action_find_next_step),
        "总结一下" to stringResource(R.string.live_action_summarize),
    ).filterNot { (command, _) -> command.liveActionKey() == currentAction.liveActionKey() }
        .take(3)
    if (actions.isEmpty()) return
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { (command, label) ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(t.surface2)
                    .then(
                        Modifier.pressable(onClick = { if (enabled) onInstruction(command) }, enabled = enabled),
                    )
                    .padding(horizontal = 13.dp, vertical = 7.dp),
            ) {
                Text(
                    text = label,
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
    val actionLabel = action.localizedActionLabel()
    val resultTitle = action.localizedResultTitle()
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
            Icon(Lucide.Sparkles, contentDescription = null, tint = t.accent, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = when {
                    retrying -> stringResource(R.string.live_action_queued, actionLabel)
                    state.analyzing -> stringResource(R.string.live_action_running, actionLabel)
                    state.paused -> stringResource(R.string.live_action_paused, actionLabel)
                    else -> stringResource(R.string.live_action_received, actionLabel)
                },
                style = type.body.copy(fontWeight = FontWeight.Medium),
                color = t.ink,
            )
            Text(
                text = when {
                    retrying -> stringResource(R.string.live_action_retry_hint, resultTitle)
                    state.analyzing -> stringResource(R.string.live_action_running_hint)
                    state.paused -> stringResource(R.string.live_action_paused_hint)
                    else -> stringResource(R.string.live_action_received_hint, resultTitle)
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
                text = stringResource(R.string.live_retry_unavailable_hint),
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

@Composable
private fun LiveModeUiState.masterTitle(): String = when {
    !active -> stringResource(R.string.live_master_not_enabled)
    paused -> stringResource(R.string.live_master_paused)
    error != null && nextAnalysisAfterMillis > System.currentTimeMillis() -> stringResource(R.string.live_master_model_busy)
    error != null -> stringResource(R.string.live_master_analysis_failed)
    analyzing -> stringResource(R.string.live_master_analyzing)
    card != null -> stringResource(R.string.live_master_companion_active)
    currentAppLabel.isNotBlank() -> stringResource(R.string.live_master_reading)
    else -> stringResource(R.string.live_master_enabled)
}

@Composable
private fun LiveModeUiState.masterSubtitle(autoRefresh: Boolean): String {
    val target = listOf(currentAppLabel, currentTitle).filter { it.isNotBlank() }.joinToString(" · ")
    val mode = if (autoRefresh) {
        stringResource(R.string.live_auto_analysis)
    } else {
        stringResource(R.string.live_manual_analysis)
    }
    val analyzingHint = stringResource(R.string.live_master_analyzing_hint)
    return when {
        !active -> stringResource(R.string.live_master_disabled_hint)
        paused -> stringResource(R.string.live_master_paused_hint)
        error != null && nextAnalysisAfterMillis > System.currentTimeMillis() -> stringResource(R.string.live_master_busy_hint)
        requestedAction.isNotBlank() -> stringResource(
            R.string.live_master_result_hint,
            requestedAction.localizedResultTitle(),
        )
        analyzing -> target.ifBlank { analyzingHint }
        target.isNotBlank() -> stringResource(R.string.live_master_target_mode, target, mode)
        else -> stringResource(R.string.live_master_status_mode, statusText, mode)
    }
}

private fun LiveModeUiState.resultActionKey(): String = completedAction.ifBlank {
    currentFocus.liveActionKey().takeUnless { it == "屏幕分析" } ?: "屏幕分析"
}

private fun String.liveActionKey(): String {
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

@Composable
private fun String.localizedActionLabel(): String = when (this) {
    "屏幕分析" -> stringResource(R.string.live_action_screen_analysis)
    "找重点" -> stringResource(R.string.live_action_find_focus)
    "总结" -> stringResource(R.string.live_action_summarize)
    "找下一步" -> stringResource(R.string.live_action_find_next_step)
    "查风险" -> stringResource(R.string.live_action_check_risks)
    "写回复" -> stringResource(R.string.live_action_write_reply)
    else -> this
}

@Composable
private fun String.localizedResultTitle(): String = when (this) {
    "屏幕分析" -> stringResource(R.string.live_result_title_companion)
    "找重点" -> stringResource(R.string.live_result_title_focus)
    "总结" -> stringResource(R.string.live_result_title_summary)
    "找下一步" -> stringResource(R.string.live_result_title_next_step)
    "查风险" -> stringResource(R.string.live_result_title_risks)
    "写回复" -> stringResource(R.string.live_result_title_reply)
    else -> stringResource(R.string.live_result_title_custom, this)
}
