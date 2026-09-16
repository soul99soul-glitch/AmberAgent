package app.amber.feature.bubble

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.amber.agent.AppScope
import app.amber.agent.RouteActivity
import app.amber.core.automation.AmberAccessibilityService
import app.amber.core.service.ChatService
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.ui.theme.AmberAgentTheme
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 任务气泡驱动器：agent 任务（含 GUI 操控）运行期间，在系统悬浮气泡里展示
 * 当前步骤、回复预览与内联审批。数据全部来自 StateFlow 快照 + 1s tick
 * （与 LiveModeManager 同款模式），仅主线程操作窗口。
 * 与 Live 伴随气泡共用 [BubbleWindow] 并互斥——任务气泡亮着时 Live 让位。
 */
class AgentTaskBubbleController(
    private val context: Context,
    private val chatService: ChatService,
    private val activityStore: AgentToolActivityStore,
    private val settingsStore: SettingsAggregator,
    private val appScope: AppScope,
) {
    private val window = BubbleWindow()
    private var loopJob: Job? = null

    private val _visible = MutableStateFlow(false)

    /** 当前任务气泡是否在屏上；LiveModeManager 用它做互斥让位。 */
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private val _uiState = MutableStateFlow(AgentBubbleUiState())
    val uiState: StateFlow<AgentBubbleUiState> = _uiState.asStateFlow()

    fun start() {
        if (loopJob != null) return
        loopJob = appScope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        window.hide()
        _visible.value = false
    }

    private fun tick() {
        val settings = settingsStore.settingsFlow.value
        val enabled = settings.agentRuntime.agentTaskBubbleEnabled
        val service = AmberAccessibilityService.getActiveService()
        val foregroundIsAmber = service?.activePackageName() == context.packageName
        val activeIds = chatService.activeConversationIds.value.map { it.toString() }.toSet()
        val activity = activityStore.sandboxActivity.value
        val prev = _uiState.value
        val now = System.currentTimeMillis()

        val picked = AgentBubbleReducer.pickConversation(activeIds, activity?.conversationId, prev)
        var messages: List<app.amber.ai.ui.UIMessage>? = null
        var finalText: String? = null
        var failed = false
        var cancelled = false
        if (picked != null) {
            parseUuid(picked)?.let { uuid ->
                messages = chatService.getConversationFlow(uuid).value.currentMessages
            }
        } else if (prev.phase == AgentBubblePhase.RUNNING) {
            // 过渡 tick：run 刚结束，按内核终态定成败，并补读最终回复全文
            prev.conversationId?.let { id ->
                parseUuid(id)?.let { uuid ->
                    val conversation = chatService.getConversationFlow(uuid).value
                    messages = conversation.currentMessages
                    finalText = AgentBubbleReducer.lastAssistantText(conversation.currentMessages)
                    when (chatService.lastRunOutcomes.value[uuid]) {
                        app.amber.core.agent.runtime.RunStatus.CANCELLED -> cancelled = true
                        app.amber.core.agent.runtime.RunStatus.COMPLETED, null -> failed = false
                        else -> failed = true
                    }
                }
            }
        }

        val next = AgentBubbleReducer.reduce(
            previous = prev,
            pickedConversationId = picked,
            activity = activity?.takeIf { it.conversationId == picked },
            messages = messages,
            finalText = finalText,
            failed = failed,
            cancelled = cancelled,
            nowMillis = now,
        )
        _uiState.value = next
        syncWindow(next, enabled, service, foregroundIsAmber)
    }

    private fun syncWindow(
        state: AgentBubbleUiState,
        enabled: Boolean,
        service: AmberAccessibilityService?,
        foregroundIsAmber: Boolean,
    ) {
        val show = AgentBubbleReducer.isVisible(state, enabled, service != null, foregroundIsAmber)
        if (!show) {
            if (window.isShowing) window.hide()
            _visible.value = false
            return
        }
        val host = service ?: return
        window.show(host) {
            AmberAgentTheme {
                val uiState by uiState.collectAsState()
                AgentTaskBubbleContent(
                    state = uiState,
                    onApprove = { decideApproval(approved = true) },
                    onDeny = { decideApproval(approved = false) },
                    onStop = ::stopTask,
                    onOpenConversation = ::openConversation,
                    onDrag = window::moveBy,
                    onDragEnd = window::snapToEdge,
                    onSizeChanged = window::requestReclamp,
                )
            }
        }
        window.setTouchPassthrough(AgentBubbleReducer.isTouchPassthrough(state))
        _visible.value = true
    }

    private fun decideApproval(approved: Boolean) {
        val state = _uiState.value
        val uuid = state.conversationId?.let(::parseUuid) ?: return
        val toolCallId = state.approvalToolCallId ?: return
        chatService.handleToolApproval(uuid, toolCallId, approved)
    }

    private fun stopTask() {
        val uuid = _uiState.value.conversationId?.let(::parseUuid) ?: return
        appScope.launch { chatService.stopGeneration(uuid) }
    }

    private fun openConversation() {
        val conversationId = _uiState.value.conversationId ?: return
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId)
            putExtra("focus", "conversation")
        }
        runCatching { context.startActivity(intent) }
    }

    private fun parseUuid(raw: String): Uuid? = runCatching { Uuid.parse(raw) }.getOrNull()

    companion object {
        private const val TICK_MS = 1_000L
    }
}
