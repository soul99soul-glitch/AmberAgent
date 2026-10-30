package app.amber.feature.ui.pages.chat

import app.amber.core.model.Conversation
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.toInstant
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.registry.ModelRegistry
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowDown
import app.amber.agent.R
import app.amber.core.context.ContextFootprintEstimator
import app.amber.core.context.ConversationCompact
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

@Composable
internal fun ChatPageTopBar(
    conversationFlow: StateFlow<Conversation>,
    bigScreen: Boolean,
    onBack: () -> Unit,
    currentChatModel: Model?,
    generating: Boolean,
    processingStatus: String?,
    recapEligible: Boolean,
    recapOpen: Boolean,
    shelfOpen: Boolean,
    activityOpen: Boolean,
    otherActivityCount: Int,
    modelMenuOpen: Boolean,
    onToggleModelMenu: () -> Unit,
    onIslandOpenRecap: () -> Unit,
    onIslandJumpToLive: () -> Unit,
    onIslandStop: () -> Unit,
    onToggleShelf: () -> Unit,
    onToggleActivity: () -> Unit,
) {
    val headerConversationFlow = remember(conversationFlow) {
        conversationFlow
            .map { it.title to it.messageNodes.isNotEmpty() }
            .distinctUntilChanged()
    }
    val header by headerConversationFlow.collectAsStateWithLifecycle(
        initialValue = conversationFlow.value.let { it.title to it.messageNodes.isNotEmpty() },
    )
    TopBar(
        title = header.first,
        hasMessages = header.second,
        bigScreen = bigScreen,
        onBack = onBack,
        currentChatModel = currentChatModel,
        generating = generating,
        processingStatus = processingStatus,
        recapEligible = recapEligible,
        recapOpen = recapOpen,
        shelfOpen = shelfOpen,
        activityOpen = activityOpen,
        otherActivityCount = otherActivityCount,
        modelMenuOpen = modelMenuOpen,
        onToggleModelMenu = onToggleModelMenu,
        onIslandOpenRecap = onIslandOpenRecap,
        onIslandJumpToLive = onIslandJumpToLive,
        onIslandStop = onIslandStop,
        onToggleShelf = onToggleShelf,
        onToggleActivity = onToggleActivity,
    )
}

/** Values for the model menu's context usage header; composed only while the menu is open. */
@Composable
private fun rememberContextUsage(
    conversationFlow: StateFlow<Conversation>,
    contextCompacts: List<ConversationCompact>,
    currentChatModel: Model?,
): ContextUsageSnapshot? {
    val conversation by conversationFlow.collectAsStateWithLifecycle()
    if (conversation.messageNodes.isEmpty()) return null
    val lastAssistant = remember(conversation.messageNodes) {
        conversation.messageNodes.asReversed()
            .asSequence()
            .mapNotNull { it.messages.getOrNull(it.selectIndex) }
            .firstOrNull { it.role == MessageRole.ASSISTANT }
    }
    val usage = lastAssistant?.usage
    val measuredPromptTokens = usage?.promptTokens?.takeIf { it > 0 }
    val cache = remember(conversation.id) { ContextFootprintEstimator.ConversationInputTokenCache() }
    val estimatedInputTokens by produceState(initialValue = 0, conversation, contextCompacts, measuredPromptTokens) {
        value = if (measuredPromptTokens != null) {
            0
        } else {
            withContext(Dispatchers.Default) {
                cache.estimateConversationInputTokens(conversation, contextCompacts)
            }
        }
    }
    val usedTokens = measuredPromptTokens ?: estimatedInputTokens
    // total: 优先使用持续维护的 registry，未知/自定义模型再退回 provider 配置。
    val contextWindowTokens = currentChatModel?.let { model ->
        ModelRegistry.MODEL_CONTEXT_WINDOW.getData(model.modelId) ?: model.contextWindowTokens
    }
    // 速度算的是端到端 (含网络/排队)：createdAt 是第一字符落地时刻, finishedAt 是最后字符。
    val elapsedMs = lastAssistant?.finishedAt?.let { finished ->
        val zone = kotlinx.datetime.TimeZone.currentSystemDefault()
        (finished.toInstant(zone).toEpochMilliseconds() -
            lastAssistant.createdAt.toInstant(zone).toEpochMilliseconds()).takeIf { it > 0 }
    }
    return ContextUsageSnapshot(
        usedK = ((usedTokens + 999) / 1000).coerceAtLeast(0),
        totalK = (((contextWindowTokens ?: 200_000) + 999) / 1000).coerceAtLeast(1),
        lastTurnTotalTokens = usage?.totalTokens,
        lastTurnCompletionTokens = usage?.completionTokens,
        lastTurnCachedTokens = usage?.cachedTokens,
        lastTurnPromptTokens = usage?.promptTokens,
        lastTurnElapsedMs = elapsedMs,
    )
}

@Composable
internal fun ChatModelMenuUsageHeader(
    conversationFlow: StateFlow<Conversation>,
    contextCompacts: List<ConversationCompact>,
    currentChatModel: Model?,
) {
    val usage = rememberContextUsage(conversationFlow, contextCompacts, currentChatModel) ?: return
    ContextUsageSummary(
        snapshot = usage,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
    )
}

@Composable
private fun TopBar(
    title: String,
    hasMessages: Boolean,
    bigScreen: Boolean,
    onBack: () -> Unit,
    currentChatModel: Model?,
    generating: Boolean,
    processingStatus: String?,
    recapEligible: Boolean,
    recapOpen: Boolean,
    shelfOpen: Boolean,
    activityOpen: Boolean,
    otherActivityCount: Int,
    modelMenuOpen: Boolean,
    onToggleModelMenu: () -> Unit,
    onIslandOpenRecap: () -> Unit,
    onIslandJumpToLive: () -> Unit,
    onIslandStop: () -> Unit,
    onToggleShelf: () -> Unit,
    onToggleActivity: () -> Unit,
) {
    val newSessionLabel = stringResource(R.string.chat_page_new_session)
    val amberTokens = LocalAmberTokens.current
    val amberType = LocalAmberType.current
    // V3 phone-screen.jsx header 没有 surface —— 直接坐在 chatTheme.bg 之上
    // 之前用 workspace.paper@96% (legacy 硬编码白底)，用户反馈"顶栏没变暖纸色"
    // ——其实是被白 Surface 罩住了
    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // The header and composer use matching fine accent rules around the timeline.
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 62.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!bigScreen) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .testTag("chat-header-back")
                            .clickable { onBack() },
                        contentAlignment = Alignment.Center,
                    ) {
                        AmberHeaderBackArrow()
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }

                // Two touch targets: the island (status / recap) and the model row below it.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 1.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    ChatIsland(
                        title = title.ifBlank { newSessionLabel },
                        generating = generating,
                        status = processingStatus,
                        recapEligible = recapEligible,
                        recapOpen = recapOpen,
                        enabled = hasMessages || generating,
                        onOpenRecap = onIslandOpenRecap,
                        onJumpToLive = onIslandJumpToLive,
                        onStop = onIslandStop,
                    )
                    // Graphite §6.2 ChatHeader model-id trigger: mono model-id + a chevron that
                    // rotates 180° while the TopModelMenu dropdown is open. Tapping toggles it
                    // (the dropdown itself is rendered in the content area, anchored under header).
                    val chevronRotation by animateFloatAsState(
                        targetValue = if (modelMenuOpen) 180f else 0f,
                        animationSpec = tween(durationMillis = 280),
                        label = "modelMenuChevron",
                    )
                    Row(
                        modifier = Modifier
                            .heightIn(min = 28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .testTag("chat-header-model-menu")
                            .clickable { onToggleModelMenu() }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = currentChatModel?.modelId
                                ?: stringResource(R.string.model_list_select_model),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = amberType.meta.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            // ink3↔ink2 中点：ink3 偏淡、ink2 偏深，取中间的暖中灰
                            color = lerp(amberTokens.ink3, amberTokens.ink2, 0.5f),
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Icon(
                            imageVector = Lucide.ArrowDown,
                            contentDescription = null,
                            tint = amberTokens.ink3,
                            // 箭头 14→13，陪着字号一起缩
                            modifier = Modifier
                                .size(13.dp)
                                .rotate(chevronRotation),
                        )
                    }
                }

                // 跨对话活动卫星只在别处有 run 时出现，不挤掉产物架入口。
                // 刻意为它保留 0 宽度：常驻 48dp 空位会把岛屿永久左移 24dp，
                // 比"别的对话开始/结束时顶栏短暂横移一次"更差。
                ChatActivityButton(
                    count = otherActivityCount,
                    open = activityOpen,
                    onClick = onToggleActivity,
                )

                // The shelf replaces the context ring; usage now lives in the model menu.
                Box(
                    modifier = Modifier.widthIn(min = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (hasMessages) {
                        ChatShelfButton(open = shelfOpen, onClick = onToggleShelf)
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(LocalAmberTokens.current.accent.copy(alpha = 0.10f)),
            )
        }
    }
}

/**
 * 顶栏返回箭头：延续抽象细线语言（1.6dp 圆帽 ink 线）。
 * 顶栏左上一度是汉堡（三条横线，开 ChatDrawer），侧边栏被 Session 首页取代后
 * 改为返回箭头，与当前顶栏的细线语言保持一致。
 */
@Composable
private fun AmberHeaderBackArrow() {
    // 主题感知 ink 色 —— legacy workspaceColors().ink 在浅色硬编码 #1F1F1F，
    // Paper 主题下需要 #2A241B 才能跟 bg 协调
    val ink = LocalChatTheme.current.ink
    Canvas(modifier = Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val cy = h / 2
        val strokeWidth = 1.6.dp.toPx()
        // 主横线 + 左端 chevron 两臂
        drawLine(ink, Offset(w * 0.86f, cy), Offset(w * 0.18f, cy), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(ink, Offset(w * 0.18f, cy), Offset(w * 0.48f, h * 0.22f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(ink, Offset(w * 0.18f, cy), Offset(w * 0.48f, h * 0.78f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
    }
}

// V3 Whisper 空白态本应为纯净留白 + 底部光晕；之前的 EmptyChatHero（宝石 + 问候）
// 是误读 phone-screen.jsx 未被 Hero 引用的 AmberMark 导致的；移除。
// AmberMark.kt 文件保留作未来 agent avatar 等场景的备用组件。
