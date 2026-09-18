package app.amber.feature.bubble

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.runtime.SandboxActivityUiState
import app.amber.feature.runtime.ToolActivityStatus

/** 任务气泡的展示阶段：无 run / 运行中 / 结束后短暂停留。 */
enum class AgentBubblePhase { IDLE, RUNNING, FINISHED }

data class AgentBubbleUiState(
    val phase: AgentBubblePhase = AgentBubblePhase.IDLE,
    val conversationId: String? = null,
    /** 等待工具审批（或 ask_user 等待回复）。 */
    val waitingApproval: Boolean = false,
    /** 等待的是 ask_user 提问：气泡展示"去回复"而不是批准/拒绝。 */
    val waitingAskUser: Boolean = false,
    val approvalToolCallId: String? = null,
    val approvalToolTitle: String = "",
    /** 当前正在执行的步骤标题（仅运行中的工具），空串 = 无步骤信息。 */
    val stepTitle: String = "",
    /** screen_* 工具正在执行：气泡窗口需触摸穿透，避免吞掉注入手势。
     *  展示会话与执行手势的会话可能不同，穿透必须全局判定（任一活跃会话的 screen_* 在执行即穿透）。
     */
    val stepRunning: Boolean = false,
    /** agent 最新文本回复的尾部预览。 */
    val replyPreview: String = "",
    val failed: Boolean = false,
    val finishedAtMillis: Long? = null,
) {
    val isActive: Boolean get() = phase != AgentBubblePhase.IDLE
}

/** 任务气泡的纯逻辑：会话取舍、状态归约、显隐与穿透判定。无 Android 依赖，可直接 JVM 测试。 */
object AgentBubbleReducer {

    /** 结束后气泡停留时长：展示最终回复，到期自动收起。 */
    const val FINISHED_LINGER_MS = 12_000L

    private const val PREVIEW_TAIL_CHARS = 400
    private const val SCREEN_TOOL_PREFIX = "screen_"
    private const val ASK_USER_TOOL = "ask_user"

    /**
     * 选中要展示的会话：优先保持上一次的（防多会话间闪烁），
     * 其次当前工具所属会话，最后取第一个活跃会话。无活跃返回 null。
     */
    fun pickConversation(
        activeIds: Set<String>,
        activityConversationId: String?,
        previous: AgentBubbleUiState,
    ): String? {
        if (activeIds.isEmpty()) return null
        previous.conversationId?.takeIf { it in activeIds }?.let { return it }
        activityConversationId?.takeIf { it in activeIds }?.let { return it }
        return activeIds.first()
    }

    /**
     * 归约气泡状态。[activity] 是全局沙箱活动快照（可能属于任一活跃会话）：
     * stepRunning（触摸穿透）按全局判定，stepTitle 只取被展示会话（[pickedConversationId]）——
     * 展示会话与执行手势的会话可能不同，穿透必须全局判定。
     */
    fun reduce(
        previous: AgentBubbleUiState,
        pickedConversationId: String?,
        activity: SandboxActivityUiState?,
        messages: List<UIMessage>?,
        finalText: String? = null,
        failed: Boolean = false,
        cancelled: Boolean = false,
        nowMillis: Long,
    ): AgentBubbleUiState {
        if (pickedConversationId == null) {
            when {
                // 用户主动停止：不打扰，直接收起
                previous.phase == AgentBubblePhase.RUNNING && cancelled -> return AgentBubbleUiState()
                previous.phase == AgentBubblePhase.RUNNING -> return previous.copy(
                    phase = AgentBubblePhase.FINISHED,
                    failed = failed,
                    finishedAtMillis = nowMillis,
                    waitingApproval = false,
                    waitingAskUser = false,
                    approvalToolCallId = null,
                    stepRunning = false,
                    replyPreview = finalText?.takeLast(PREVIEW_TAIL_CHARS)
                        ?: previous.replyPreview,
                )

                previous.phase == AgentBubblePhase.FINISHED ->
                    if (previous.finishedAtMillis != null &&
                        nowMillis - previous.finishedAtMillis >= FINISHED_LINGER_MS
                    ) {
                        return AgentBubbleUiState()
                    } else {
                        return previous
                    }

                else -> return AgentBubbleUiState()
            }
        }

        // 运行中：每个 tick 从消息与工具活动重算完整状态
        val parts = messages
            ?.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.parts
            .orEmpty()
        val pendingTool = parts
            .filterIsInstance<UIMessagePart.Tool>()
            .lastOrNull { !it.isExecuted && it.approvalState is ToolApprovalState.Pending }
        val lastText = parts
            .filterIsInstance<UIMessagePart.Text>()
            .lastOrNull { it.text.isNotBlank() }
            ?.text

        return AgentBubbleUiState(
            phase = AgentBubblePhase.RUNNING,
            conversationId = pickedConversationId,
            waitingApproval = pendingTool != null,
            waitingAskUser = pendingTool?.toolName == ASK_USER_TOOL,
            approvalToolCallId = pendingTool?.toolCallId,
            approvalToolTitle = pendingTool?.toolName.orEmpty(),
            stepTitle = runningStepTitle(activity?.takeIf { it.conversationId == pickedConversationId }),
            stepRunning = activity?.status == ToolActivityStatus.RUNNING &&
                activity.toolName.startsWith(SCREEN_TOOL_PREFIX),
            replyPreview = lastText?.takeLast(PREVIEW_TAIL_CHARS).orEmpty(),
        )
    }

    fun isVisible(
        state: AgentBubbleUiState,
        enabled: Boolean,
        serviceAvailable: Boolean,
        foregroundIsAmber: Boolean,
    ): Boolean = enabled && serviceAvailable && !foregroundIsAmber && state.isActive

    fun isTouchPassthrough(state: AgentBubbleUiState): Boolean = state.stepRunning

    /** 最后一条 assistant 文本（流式中即当前增量全文的尾部）。 */
    fun lastAssistantText(messages: List<UIMessage>): String? = messages
        .lastOrNull { it.role == MessageRole.ASSISTANT }
        ?.parts
        ?.filterIsInstance<UIMessagePart.Text>()
        ?.lastOrNull { it.text.isNotBlank() }
        ?.text

    private fun runningStepTitle(activity: SandboxActivityUiState?): String =
        activity?.takeIf { it.status == ToolActivityStatus.RUNNING }?.title.orEmpty()
}
