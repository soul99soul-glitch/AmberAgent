package app.amber.feature.chat.impl

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageAnnotation
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.canResumeToolExecution
import app.amber.ai.ui.finishReasoning
import app.amber.core.model.Conversation
import app.amber.feature.chat.api.ChatEventPayload
import app.amber.feature.chat.api.StreamToolState
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.time.Instant
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Projects the last stream checkpoint of an interrupted run back onto the
 * persisted conversation, so the UI reopens into a coherent state instead
 * of a ghost "still streaming" message:
 *
 *  - the tail assistant message is annotated [UIMessageAnnotation.GenerationInterrupted]
 *    (with a ":stale_tail" suffix when the persisted content lags the checkpoint),
 *    its dangling reasoning closed and finishedAt stamped;
 *  - tools pending user approval are PRESERVED (the approval card re-renders);
 *  - tools the checkpoint saw as awaiting approval but whose Pending state
 *    was lost in the snapshot window are restored to Pending;
 *  - tools whose arguments were still streaming are marked non-executable
 *    (synthetic interrupted output + Denied) so truncated args can never run;
 *  - tools the user already explicitly decided on (approved/answered/denied)
 *    and tools with persisted output are left untouched.
 *
 * Pure function — no clock injection needed beyond timestamps on the copies,
 * no Android dependencies, deliberately JVM-unit-testable.
 */
object InterruptedRunProjection {

    const val DEFAULT_REASON = "process_restart"
    const val STALE_TAIL_SUFFIX = ":stale_tail"

    private const val ARGS_INCOMPLETE_NOTE =
        "Tool call was interrupted before its arguments finished streaming; it was not executed."
    private const val RESULT_LOST_NOTE =
        "Tool executed before the interruption but its result was not persisted; it was not re-executed."

    /**
     * Returns the same [conversation] instance when there is nothing to do
     * (tail not found / not an assistant message / already projected), so
     * callers can use reference inequality to decide whether to persist.
     */
    fun project(
        conversation: Conversation,
        checkpoint: ChatEventPayload.StreamCheckpoint,
        reason: String = DEFAULT_REASON,
    ): Conversation {
        val tailId = runCatching { Uuid.parse(checkpoint.messageId) }.getOrNull()
        val exactNodeIndex = tailId?.let { id ->
            conversation.messageNodes.indexOfLast { node -> node.messages.any { it.id == id } }
        } ?: -1
        // The checkpointed tail may be newer than the last conversation
        // snapshot (created inside the loss window). Fall back to the last
        // node: an interrupted run's tail is by construction at the end.
        val nodeIndex = if (exactNodeIndex >= 0) exactNodeIndex else conversation.messageNodes.size - 1
        val node = conversation.messageNodes.getOrNull(nodeIndex) ?: return conversation

        val target = if (exactNodeIndex >= 0) {
            node.messages.lastOrNull { it.id == tailId } ?: return conversation
        } else {
            node.messages.getOrNull(node.selectIndex) ?: return conversation
        }
        if (target.role != MessageRole.ASSISTANT) return conversation
        if (target.annotations.any { it is UIMessageAnnotation.GenerationInterrupted }) {
            return conversation
        }

        val matchedTail = exactNodeIndex >= 0
        val staleTail = !matchedTail || target.streamPartsHash() != checkpoint.partsHash
        val fullReason = if (staleTail) "$reason$STALE_TAIL_SUFFIX" else reason

        val checkpointStates = checkpoint.toolStates.associateBy { it.toolCallId }
        val updatedParts = target.parts.map { part ->
            if (part !is UIMessagePart.Tool || part.isExecuted) return@map part
            when {
                // 保留 pending approval：重启后审批卡片原样回来
                part.isPending -> part
                // 用户已显式决策（approved/answered/denied）：args 在决策时已完整
                part.approvalState.canResumeToolExecution() -> part
                else -> when (checkpointStates[part.toolCallId]?.state) {
                    StreamToolState.PENDING_APPROVAL ->
                        part.copy(approvalState = ToolApprovalState.Pending)
                    StreamToolState.EXECUTED ->
                        markNotExecutable(part, RESULT_LOST_NOTE)
                    else ->
                        markNotExecutable(part, ARGS_INCOMPLETE_NOTE)
                }
            }
        }

        val updatedMessage = target.copy(
            parts = updatedParts,
            annotations = target.annotations + UIMessageAnnotation.GenerationInterrupted(fullReason),
            finishedAt = target.finishedAt
                ?: Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
        ).finishReasoning()

        return conversation.copy(
            messageNodes = conversation.messageNodes.mapIndexed { index, n ->
                if (index != nodeIndex) n
                else n.copy(
                    messages = n.messages.map { if (it.id == target.id) updatedMessage else it }
                )
            },
            updateAt = Instant.now(),
        )
    }

    private fun markNotExecutable(tool: UIMessagePart.Tool, note: String): UIMessagePart.Tool =
        tool.copy(
            output = listOf(
                UIMessagePart.Text("""{"status":"interrupted","error":"$note"}""")
            ),
            approvalState = ToolApprovalState.Denied(note),
        )
}
