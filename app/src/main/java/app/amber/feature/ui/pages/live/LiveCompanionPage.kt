package app.amber.feature.ui.pages.live

import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.composables.icons.lucide.X
import app.amber.agent.Screen
import app.amber.agent.R
import app.amber.agent.data.db.entity.LiveCardEntity
import app.amber.ai.provider.ModelType
import app.amber.core.settings.findModelById
import app.amber.core.settings.getCurrentChatModel
import app.amber.core.utils.appLocale
import app.amber.core.utils.base64Encode
import app.amber.feature.live.FILL_CONFIRM_WINDOW_MS
import app.amber.feature.live.LiveAnalysisMode
import app.amber.feature.live.LiveFillResult
import app.amber.feature.live.LiveModeCard
import app.amber.feature.live.LiveModeUiState
import app.amber.feature.live.LiveMotion
import app.amber.feature.live.LiveScene
import app.amber.feature.ui.components.ai.ModelSelector
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.components.ds.LiveDot
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ds.StreamingTextWithCursor
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.dokar.sonner.TextToastAction
import org.koin.androidx.compose.koinViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

/**
 * AI 伴随 — Terminal × Modern graphite reskin. Layout follows the design handoff:
 * eyebrow header (// COMPANION ● + 标题), a master toggle card (主开关 + 暂停),
 * the last-analysis result section, and a CONFIG card (气泡 / 模型).
 * P1 语义（蓝图 v3 §7.3）：仅手动触发分析；截图分析可选（文字/截屏双模式，
 * 图树同次观察校验，截图即用即删）；草稿仅复制（填入白名单见 P1-C）。
 */
@Composable
fun LiveCompanionPage(vm: LiveCompanionVM = koinViewModel()) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val savedCards by vm.savedCards.collectAsStateWithLifecycle()
    val liveSetting = settings.agentRuntime.liveMode
    val tokens = LocalAmberTokens.current
    val scrollState = rememberScrollState()
    val companionModelId = remember(settings, liveSetting.companionModelId) {
        val id = liveSetting.companionModelId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        (id?.let { settings.findModelById(it) } ?: settings.getCurrentChatModel())?.modelId
    }

    // 伴随会话由页面主开关/气泡长按显式启停（蓝图 v3 §7.2 P0-1/P0-3）；
    // 离开页面不停止——气泡仍在屏上，Manager 归进程级域 owner。

    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val savedDeletedMessage = stringResource(R.string.live_saved_deleted)
    val savedUndoMessage = stringResource(R.string.live_saved_undo)

    // 深链定位：通知进入时滚动到结果卡锚点并做一次边框脉冲（ChatInput suggestionFillPulse 同款）。
    // 卡片可能还在冷恢复路上，最多等 1.5s。
    var resultTopPx by remember { mutableFloatStateOf(0f) }
    val resultPulse = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        LiveCompanionDeepLink.requests.collect { version ->
            if (!LiveCompanionDeepLink.claim(version)) return@collect
            // 卡片可能还在冷恢复路上，最多等 1.5s。
            withTimeoutOrNull(1_500L) { vm.state.first { it.card != null } } ?: return@collect
            // 等结果卡完成一次布局，锚点 onGloballyPositioned 才有真实 y。
            delay(200)
            val topOffset = with(density) { 16.dp.toPx() }
            scrollState.animateScrollTo((resultTopPx - topOffset).roundToInt().coerceAtLeast(0))
        resultPulse.snapTo(1f)
        resultPulse.animateTo(0f, tween(LiveMotion.PulseDecayMs, easing = LiveMotion.EaseOut))
        }
    }

    // 已保存行的延迟删除账本：先播离场动画，SAVED_DELETE_COMMIT_MS 后落库；
    // toast Undo 可取消在途删除；已落库则按原 createdAt 重插（位置不变）。
    var pendingDeletes by remember { mutableStateOf(setOf<Long>()) }
    val deleteJobs = remember { mutableStateMapOf<Long, Job>() }
    val onDeleteSaved: (LiveCardEntity) -> Unit = { saved ->
        pendingDeletes = pendingDeletes + saved.id
        deleteJobs.remove(saved.id)?.cancel()
        deleteJobs[saved.id] = scope.launch {
            delay(SAVED_DELETE_COMMIT_MS)
            vm.deleteSavedCard(saved.id)
            // 提交后清账：防 SQLite rowid 复用把"待删 id"错套到之后新保存的卡上（checker P1）。
            pendingDeletes = pendingDeletes - saved.id
        }
        toaster.show(
            message = savedDeletedMessage,
            action = TextToastAction(savedUndoMessage) {
                deleteJobs.remove(saved.id)?.cancel()
                pendingDeletes = pendingDeletes - saved.id
                vm.undoDeleteSavedCard(saved)
            },
        )
    }

    Scaffold(
        topBar = {
            LiveHeader(
                live = state.active && !state.paused && state.error == null,
                onBack = { navController.popBackStack() },
                onSettings = { navController.navigate(Screen.SettingAgentExecution) },
            )
        },
        modifier = Modifier.amberCanvas(),
        containerColor = Color.Transparent,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            // 区间距由各 section 自带 padding(top) 提供：条件区动画收起后可折叠到 0，不留双倍空隙。
        ) {
            // ── 主开关卡 ──
            MasterCard(
                state = state,
                onMaster = { on -> if (on) vm.start() else vm.stop() },
                onPauseResume = vm::pauseOrResume,
            )

            // ── 阻塞态引导 / 错误 / 进度：互斥优先级单槽（各自非空时成立，实践互斥），
            // 切换走 fade+slide 转场；空槽高度为 0。
            val alert = when {
                state.needsAccessibility -> LiveAlertKind.ACCESSIBILITY
                state.noModelConfigured -> LiveAlertKind.MODEL
                state.error?.isNotBlank() == true -> LiveAlertKind.ERROR
                state.requestedAction.isNotBlank() -> LiveAlertKind.ACTION
                !state.streamingText.isNullOrBlank() -> LiveAlertKind.STREAM
                else -> LiveAlertKind.NONE
            }
            AnimatedContent(
                targetState = alert,
                transitionSpec = {
                    (fadeIn(tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut)) +
                        slideInVertically(tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut)) { it / 8 }) togetherWith
                        fadeOut(tween(LiveMotion.FastMs))
                },
                label = "liveAlertZone",
            ) { kind ->
                if (kind != LiveAlertKind.NONE) {
                    Box(modifier = Modifier.padding(top = 20.dp)) {
                        when (kind) {
                            LiveAlertKind.ACCESSIBILITY -> GuidanceCard(
                                title = stringResource(R.string.live_accessibility_required_title),
                                body = stringResource(R.string.live_accessibility_required_body),
                                action = stringResource(R.string.live_open_accessibility_settings),
                                onAction = { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) },
                            )

                            LiveAlertKind.MODEL -> GuidanceCard(
                                title = stringResource(R.string.live_model_required_title),
                                body = stringResource(R.string.live_model_required_body),
                                action = stringResource(R.string.live_open_model_settings),
                                onAction = { navController.navigate(Screen.SettingModels) },
                            )

                            LiveAlertKind.ERROR -> Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                ErrorNote(
                                    title = state.statusText,
                                    error = state.error.orEmpty(),
                                    retrying = state.nextAnalysisAfterMillis > System.currentTimeMillis(),
                                )
                                // 保留合并前的原语义：错误（如 backoff）与"新动作已排队"可同时出现。
                                if (state.requestedAction.isNotBlank()) {
                                    ActionProgressCard(state)
                                }
                            }

                            LiveAlertKind.ACTION -> ActionProgressCard(state)

                            LiveAlertKind.STREAM -> StreamingPreviewCard(state.streamingText.orEmpty())

                            LiveAlertKind.NONE -> {}
                        }
                    }
                }
            }

            // ── 上次分析 ──
            Column(
                modifier = Modifier
                    .padding(top = 20.dp)
                    .onGloballyPositioned { resultTopPx = it.positionInParent().y },
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionLabel(stringResource(R.string.live_last_analysis))
                val card = state.card
                if (card != null) {
                    val actionKey = state.resultActionKey()
                    LiveResultCard(
                        card = card,
                        state = state,
                        modelId = companionModelId,
                        actionKey = actionKey,
                        stale = state.requestedAction.isNotBlank() || state.cardStale,
                        pendingAction = state.requestedAction,
                        enabled = state.active && !state.paused && !state.analyzing,
                        highlight = resultPulse.value,
                        onInstruction = vm::submitFocusInstruction,
                        onFillDraft = vm::fillDraft,
                        onSaveCard = {
                            vm.saveCard { saved ->
                                Toast.makeText(
                                    context,
                                    if (saved) R.string.live_saved_toast_saved else R.string.live_saved_toast_nothing,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onSendToChat = {
                            // Screen.Chat.text 的仓内契约是 base64 编码（ChatPage 无条件
                            // base64Decode；shortcuts/通知路径均先编码），原文会直接抛异常。
                            vm.exportCurrentCard()?.let { text ->
                                navController.navigate(
                                    Screen.Chat(
                                        kotlin.uuid.Uuid.random().toString(),
                                        text = text.base64Encode(),
                                    )
                                )
                            }
                        },
                        onRemember = {
                            vm.rememberCurrentCard()
                            Toast.makeText(context, R.string.live_remember_toast, Toast.LENGTH_SHORT).show()
                        },
                        onCopyCard = {
                            vm.copyCurrentCard { copied ->
                                toaster.show(
                                    context.getString(
                                        if (copied) R.string.live_card_copied else R.string.live_saved_toast_nothing,
                                    ),
                                )
                            }
                        },
                    )
                } else {
                    EmptyResultCard(state)
                }
            }

            // ── 已保存（历史区，蓝图 §7.3 P1-3）：整区显隐 + 行出入场动画，删除可撤销 ──
            AnimatedVisibility(
                visible = savedCards.isNotEmpty(),
                enter = fadeIn(tween(LiveMotion.MediumMs)) + expandVertically(tween(LiveMotion.SlowMs, easing = LiveMotion.EaseOut)),
                exit = fadeOut(tween(LiveMotion.FastMs)) + shrinkVertically(tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut)),
            ) {
                Column(
                    modifier = Modifier.padding(top = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SectionLabel(stringResource(R.string.live_saved_section))
                    AmberCard {
                        savedCards.take(5).forEachIndexed { index, saved ->
                            var shown by remember(saved.id) { mutableStateOf(false) }
                            LaunchedEffect(saved.id) { shown = true }
                            AnimatedVisibility(
                                visible = shown && saved.id !in pendingDeletes,
                                enter = fadeIn(tween(LiveMotion.FastMs)) + expandVertically(tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut)),
                                exit = fadeOut(tween(LiveMotion.FastMs)) + shrinkVertically(tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut)),
                            ) {
                                Column {
                                    // 上方行正在离场（待删）时隐藏本行 hairline，避免悬空线残留在卡片顶缘。
                                    if (index > 0 && savedCards[index - 1].id !in pendingDeletes) Hairline()
                                    SavedCardRow(card = saved, onDelete = { onDeleteSaved(saved) })
                                }
                            }
                        }
                    }
                }
            }

            // ── CONFIG ──
            Column(
                modifier = Modifier.padding(top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
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
                    currentAppLabel = state.currentAppLabel.ifBlank { state.currentPackage },
                    sceneOverride = liveSetting.sceneOverrides[state.currentPackage],
                    onSelectScene = { scene ->
                        vm.setSceneOverride(state.currentPackage, scene)
                    },
                    autoSuggestEnabled = liveSetting.autoSuggestPackages.contains(state.currentPackage),
                    onToggleAutoSuggest = { vm.setAutoSuggestForCurrentApp(it) },
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
                    SectionLabel("COMPANION")
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
    onMaster: (Boolean) -> Unit,
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
            val eyeTint by animateColorAsState(
                targetValue = if (live) t.accent else t.ink3,
                animationSpec = tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut),
                label = "eyeTint",
            )
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(t.surface2),
                contentAlignment = Alignment.Center,
            ) {
                if (state.analyzing) {
                    // 分析中 halo：LiveDot 的呼吸光环挂到图标块上，强调"伴随正在阅读"。
                    val halo = rememberInfiniteTransition(label = "masterHalo")
                    val haloP by halo.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
                        label = "masterHaloP",
                    )
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .graphicsLayer {
                                val s = 1f + haloP * 1.3f
                                scaleX = s
                                scaleY = s
                                alpha = 0.35f * (1f - haloP)
                            }
                            .background(t.accent, CircleShape),
                    )
                }
                Icon(
                    Lucide.Eye,
                    contentDescription = null,
                    tint = eyeTint,
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
                    text = state.masterSubtitle(),
                    style = type.secondary,
                    color = t.ink3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AmberToggle(checked = state.active, onCheckedChange = onMaster)
        }

        AnimatedVisibility(
            visible = state.active,
            enter = fadeIn(tween(LiveMotion.MediumMs)) + expandVertically(tween(LiveMotion.SlowMs, easing = LiveMotion.EaseOut)),
            exit = fadeOut(tween(LiveMotion.FastMs)) + shrinkVertically(tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut)),
        ) {
            Column {
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
    currentAppLabel: String,
    sceneOverride: String?,
    onSelectScene: (LiveScene?) -> Unit,
    autoSuggestEnabled: Boolean,
    onToggleAutoSuggest: (Boolean) -> Unit,
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
                .padding(horizontal = 14.dp, vertical = 13.dp),
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
                minimalText = true,
                allowClear = true,
                emptyLabel = stringResource(R.string.live_follow_chat_model),
                onClear = onClearModel,
                onSelect = { model -> onSelectModel(model.id.toString()) },
            )
        }

        // 当前应用场景覆盖（蓝图 §7.3 P1-9）：仅在有观察现场时可配。
        if (currentAppLabel.isNotBlank()) {
            Hairline()
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.live_scene_override_label),
                        style = type.body.copy(fontWeight = FontWeight.Medium),
                        color = t.ink,
                        modifier = Modifier.weight(1f),
                    )
                    Text(currentAppLabel, style = type.meta, color = t.ink3, maxLines = 1)
                }
                val sceneOptions = listOf(
                    null to stringResource(R.string.live_scene_default),
                    LiveScene.CHAT to stringResource(R.string.live_scene_chat),
                    LiveScene.READING to stringResource(R.string.live_scene_reading),
                    LiveScene.OTHER to stringResource(R.string.live_scene_other),
                )
                val selectedIndex = sceneOptions.indexOfFirst {
                    it.first?.name?.lowercase() == sceneOverride || (it.first == null && sceneOverride == null)
                }.coerceAtLeast(0)
                AmberSeg(
                    options = sceneOptions.map { it.second },
                    selectedIndex = selectedIndex,
                    onSelect = { index -> onSelectScene(sceneOptions[index].first) },
                )
            }
            Hairline()

            // 有限自动建议（蓝图 §7.4 P2）：逐 App 开启，默认关。
            ToggleRow(
                label = stringResource(R.string.live_auto_suggest_label),
                hint = stringResource(R.string.live_auto_suggest_hint),
                checked = autoSuggestEnabled,
                onCheckedChange = onToggleAutoSuggest,
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
    highlight: Float,
    onInstruction: (String) -> Unit,
    onFillDraft: () -> LiveFillResult,
    onSaveCard: () -> Unit,
    onSendToChat: () -> Unit,
    onRemember: () -> Unit,
    onCopyCard: () -> Unit,
) {
    val appLocale = LocalContext.current.appLocale()
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val currentAppFallback = stringResource(R.string.live_current_app)
    val uncertainResultText = stringResource(R.string.live_result_uncertain)
    val screenUnclearText = stringResource(R.string.live_result_screen_unclear)
    val noClearRiskText = stringResource(R.string.live_result_no_clear_risk)
    // stale = 卡片脱离现场：整卡淡去 + hairline 变暗；highlight = 深链进入的 accent 边框脉冲。
    val staleAlpha by animateFloatAsState(
        targetValue = if (stale) 0.78f else 1f,
        animationSpec = tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut),
        label = "resultStaleAlpha",
    )
    val staleBorder by animateColorAsState(
        targetValue = if (stale) t.ink4 else t.line,
        animationSpec = tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut),
        label = "resultStaleBorder",
    )
    // 覆盖确认态提升到结果卡层（AnimatedContent 外）：新结果到达切换内容时
    // 不得静默丢弃确认窗口——Manager 侧 pendingFillConfirm 仍存活 5s。
    var fillConfirmOverwrite by remember { mutableStateOf(false) }
    var fillConfirmRound by remember { mutableStateOf(0) }
    AmberCard(
        modifier = Modifier.graphicsLayer { alpha = staleAlpha },
        borderColor = lerp(staleBorder, t.accent, highlight.coerceIn(0f, 1f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // source pill + timestamp
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .weight(1f, fill = false)
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

            AnimatedVisibility(
                visible = stale,
                enter = fadeIn(tween(LiveMotion.MediumMs)) + expandVertically(tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut)),
                exit = fadeOut(tween(LiveMotion.FastMs)) + shrinkVertically(tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut)),
            ) {
                Text(
                    text = if (pendingAction.isNotBlank()) {
                        stringResource(
                            R.string.live_new_action_pending,
                            pendingAction.localizedActionLabel(),
                        )
                    } else {
                        stringResource(R.string.live_result_screen_changed)
                    },
                    style = type.secondary,
                    color = t.ink3,
                )
            }

            // 新结果到达 / 动作版本切换：内容交叉淡化，不再硬切文本。
            AnimatedContent(
                targetState = card to actionKey,
                transitionSpec = {
                    fadeIn(tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut)) togetherWith
                        fadeOut(tween(LiveMotion.FastMs))
                },
                label = "liveResultBody",
            ) { (targetCard, targetActionKey) ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (targetActionKey) {
                        "找重点" -> {
                            LiveSection(
                                title = stringResource(R.string.live_result_conclusion),
                                content = targetCard.watching.ifBlank { uncertainResultText },
                                prominent = true,
                            )
                            LiveSection(
                                title = stringResource(R.string.live_result_key_points),
                                items = targetCard.keyPoints,
                                emptyText = stringResource(R.string.live_result_no_key_points),
                            )
                        }
                        "总结" -> {
                            LiveSection(
                                title = stringResource(R.string.live_result_summary),
                                content = targetCard.watching.ifBlank { screenUnclearText },
                                prominent = true,
                            )
                            LiveSection(title = stringResource(R.string.live_result_key_information), items = targetCard.keyPoints)
                        }
                        "找下一步" -> {
                            LiveSection(
                                title = stringResource(R.string.live_result_next_steps),
                                items = targetCard.suggestions,
                                emptyText = stringResource(R.string.live_result_no_next_step_info),
                            )
                            LiveSection(title = stringResource(R.string.live_result_basis), items = targetCard.keyPoints)
                            LiveSection(
                                title = stringResource(R.string.live_result_what_is_visible),
                                content = targetCard.watching.ifBlank { screenUnclearText },
                            )
                        }
                        "查风险" -> {
                            LiveSection(
                                title = stringResource(R.string.live_result_conclusion),
                                content = targetCard.watching.ifBlank { noClearRiskText },
                                prominent = true,
                            )
                            LiveSection(
                                title = stringResource(R.string.live_result_risks),
                                items = targetCard.keyPoints,
                                emptyText = stringResource(R.string.live_result_no_risk_points),
                            )
                        }
                        "写回复" -> {
                            LiveSection(
                                title = stringResource(R.string.live_result_reply_draft),
                                content = targetCard.suggestions.firstOrNull() ?: targetCard.watching,
                                prominent = true,
                            )
                            LiveSection(title = stringResource(R.string.live_result_tone), items = targetCard.keyPoints)
                            FillDraftButton(
                                state = state,
                                onFillDraft = onFillDraft,
                                confirmOverwrite = fillConfirmOverwrite,
                                confirmRound = fillConfirmRound,
                                onConfirmOverwrite = { overwrite, replay ->
                                    fillConfirmOverwrite = overwrite
                                    if (replay) fillConfirmRound++
                                },
                            )
                        }
                        else -> {
                            LiveSection(
                                title = stringResource(R.string.live_result_what_is_visible),
                                content = targetCard.watching.ifBlank { screenUnclearText },
                                prominent = true,
                            )
                            LiveSection(title = stringResource(R.string.live_result_key_content), items = targetCard.keyPoints)
                            LiveSection(title = stringResource(R.string.live_result_what_to_do), items = targetCard.suggestions)
                        }
                    }
                }
            }

            DynamicActionChips(
                // stale 且无 pending 时传空串：不匹配任何 chip 命令，同名动作保持可见
                // （UI 总检查 #1：stale 文案提示"点下方动作重新分析"，不得藏起同名 chip）。
                currentAction = if (stale) pendingAction else actionKey,
                enabled = enabled,
                onInstruction = onInstruction,
            )

            // 卡片资产化动作（蓝图 §7.3 P1-3/4/5）：保存 / 发到聊天 / 记住。
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CardActionChip(text = stringResource(R.string.live_action_save), onClick = onSaveCard)
                CardActionChip(text = stringResource(R.string.live_action_send_to_chat), onClick = onSendToChat)
                CardActionChip(text = stringResource(R.string.live_action_remember), onClick = onRemember)
                CardActionChip(text = stringResource(R.string.live_action_copy), onClick = onCopyCard)
            }

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
            modifier = Modifier.padding(14.dp),
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
            // chip 入场：动作版本变化时 fade+缩放 pop-in（ChatInput chip 先例）。
            var shown by remember(label) { mutableStateOf(false) }
            LaunchedEffect(label) { shown = true }
            AnimatedVisibility(
                visible = shown,
                enter = fadeIn(tween(LiveMotion.FastMs)) +
                    scaleIn(initialScale = 0.92f, animationSpec = tween(LiveMotion.MediumMs, easing = LiveMotion.EaseOut)),
            ) {
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
            // 流式预览（蓝图 §7.3 P1-1）：仅分析中显示生成中的文本（截断两行），行尾带闪烁光标。
            // analyzing 门控保留：退避期 requestedAction 挂起时残文不得冒充排队进度（Phase 2 复审 P2）。
            state.streamingText?.takeIf { it.isNotBlank() && state.analyzing }?.let { streaming ->
                StreamingTextWithCursor(
                    text = streaming,
                    style = type.meta,
                    color = t.ink3,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StreamingPreviewCard(text: String) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(t.surface2)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = t.accent)
        StreamingTextWithCursor(text = text, style = type.meta, color = t.ink3)
    }
}

/** 卡片资产化动作 chip（与 DynamicActionChips 的 chip 同风格，surface2 pill）。 */
@Composable
private fun CardActionChip(text: String, onClick: () -> Unit) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(t.surface2)
            .pressable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(text = text, style = type.secondary, color = t.ink2, maxLines = 1)
    }
}

/** 已保存卡片行（历史区）：时间 · 应用 · 结论一行截断 + 删除；跨天显示日期。 */
@Composable
private fun SavedCardRow(card: LiveCardEntity, onDelete: () -> Unit) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val appLocale = LocalContext.current.appLocale()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = formatSavedTime(card.createdAt, appLocale),
            style = type.meta,
            color = t.ink4,
        )
        Text(
            text = card.appLabel.ifBlank { card.packageName },
            style = type.meta,
            color = t.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 72.dp),
        )
        Text(
            text = card.watching,
            style = type.secondary,
            color = t.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier.size(40.dp).pressable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.X, contentDescription = stringResource(R.string.delete), tint = t.ink4, modifier = Modifier.size(15.dp))
        }
    }
}

private fun formatSavedTime(timestampMs: Long, locale: Locale): String {
    val zone = java.time.ZoneId.systemDefault()
    val sameDay = java.time.Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate() ==
        java.time.Instant.now().atZone(zone).toLocalDate()
    val pattern = if (sameDay) "HH:mm" else "MM-dd HH:mm"
    return SimpleDateFormat(pattern, locale).format(Date(timestampMs))
}

@Composable
private fun GuidanceCard(title: String, body: String, action: String, onAction: () -> Unit) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    AmberCard {
        Column(
            modifier = Modifier.padding(14.dp),
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
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = type.body.copy(fontWeight = FontWeight.Medium), color = t.ink)
            if (hint != null) Text(hint, style = type.meta, color = t.ink3)
        }
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
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 填入/复制按钮（蓝图 §7.2 P0-5 仲裁 + §7.3 P1-2 白名单）：CHAT 白名单内显示
 *  "填入"，其余显示"复制草稿"；Manager 仲裁返回 NEEDS_CONFIRM 时切"覆盖"确认态
 *  （5s 无操作自动复位）。Toast 反馈集中在这里。 */
@Composable
private fun FillDraftButton(
    state: LiveModeUiState,
    onFillDraft: () -> LiveFillResult,
    confirmOverwrite: Boolean,
    confirmRound: Int,
    onConfirmOverwrite: (Boolean, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val t = LocalAmberTokens.current
    val fillAllowed = state.fillAllowed
    // 轮次号由持有方递增：窗口内二次确认时 confirmOverwrite 不变位，下划线据此重新起算。
    val confirmProgress = remember { Animatable(1f) }
    LaunchedEffect(confirmOverwrite, confirmRound) {
        if (confirmOverwrite) {
            val progressJob = launch {
                confirmProgress.snapTo(1f)
                confirmProgress.animateTo(0f, tween(FILL_CONFIRM_WINDOW_MS.toInt(), easing = LinearEasing))
            }
            kotlinx.coroutines.delay(FILL_CONFIRM_WINDOW_MS)
            progressJob.cancel()
            onConfirmOverwrite(false, false)
        }
    }
    val label = when {
        confirmOverwrite -> stringResource(R.string.live_fill_confirm_overwrite)
        fillAllowed -> stringResource(R.string.live_fill_action_fill)
        else -> stringResource(R.string.live_fill_other_input)
    }
    Column(modifier = Modifier.width(IntrinsicSize.Min), horizontalAlignment = Alignment.Start) {
        PillButton(
            text = label,
            accent = true,
            onClick = {
                val message = when (onFillDraft()) {
                    LiveFillResult.FILLED -> {
                        onConfirmOverwrite(false, false)
                        R.string.live_fill_result_filled
                    }
                    LiveFillResult.NEEDS_CONFIRM -> {
                        onConfirmOverwrite(true, true)
                        R.string.live_fill_toast_need_confirm
                    }
                    LiveFillResult.REJECTED_STALE -> {
                        onConfirmOverwrite(false, false)
                        R.string.live_fill_toast_stale_copied
                    }
                    LiveFillResult.COPIED -> {
                        onConfirmOverwrite(false, false)
                        R.string.live_fill_result_copied
                    }
                    LiveFillResult.NO_DRAFT -> R.string.live_fill_result_missing
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            },
        )
        // 覆盖确认 5s 倒计时：下划线线性收窄，替代原先的静默复位。
        AnimatedVisibility(
            visible = confirmOverwrite,
            enter = fadeIn(tween(LiveMotion.MicroMs)),
            exit = fadeOut(tween(LiveMotion.MicroMs)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(confirmProgress.value.coerceIn(0f, 1f))
                    .height(2.dp)
                    .background(t.accent, RoundedCornerShape(1.dp)),
            )
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

/** 通知深链请求流：RouteActivity request() 自增版本号 → 本页 collect 消费
 *  （滚动定位到结果卡 + accent 边框脉冲）。已处理水位由单例持有——NavDisplay 只组合
 *  栈顶，页面往返会重建 LaunchedEffect，局部水位会把旧请求重放成"莫名滚动"。 */
object LiveCompanionDeepLink {
    private val _requests = MutableStateFlow(0)
    val requests: StateFlow<Int> = _requests.asStateFlow()

    @Volatile
    private var lastHandled = 0

    fun request() {
        _requests.value++
    }

    /** version 未处理过则标记并返回 true（at-most-once：等待超时也算已处理，不重放）。 */
    fun claim(version: Int): Boolean {
        if (version <= lastHandled) return false
        lastHandled = version
        return true
    }
}

/** 条件区（引导/错误/进度/流式）的互斥槽位；NONE 时该区高度为 0。 */
private enum class LiveAlertKind { NONE, ACCESSIBILITY, MODEL, ERROR, ACTION, STREAM }

/** 已保存行离场动画时长，延迟到点后才真正落库删除（给 Undo 留取消窗口）。 */
private const val SAVED_DELETE_COMMIT_MS = 240L

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
private fun LiveModeUiState.masterSubtitle(): String {
    val target = listOf(currentAppLabel, currentTitle).filter { it.isNotBlank() }.joinToString(" · ")
    val mode = stringResource(R.string.live_manual_analysis)
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
