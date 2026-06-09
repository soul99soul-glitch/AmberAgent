package app.amber.core.service

import kotlinx.serialization.Serializable
import app.amber.ai.ui.UIMessagePart

const val MAX_PENDING_USER_MESSAGES = 20

@Serializable
enum class PendingUserMessageMode {
    FOLLOWUP,
    STEER,
    COLLECT,
}

@Serializable
data class PendingUserMessage(
    val id: String,
    val parts: List<UIMessagePart>,
    val answer: Boolean = true,
    val mode: PendingUserMessageMode = PendingUserMessageMode.FOLLOWUP,
    val createdAtMs: Long = System.currentTimeMillis(),
)

val PendingUserMessage.isCollectable: Boolean
    get() = mode == PendingUserMessageMode.COLLECT && parts.all { it is UIMessagePart.Text }

fun PendingUserMessage.asFollowup(): PendingUserMessage {
    return if (mode == PendingUserMessageMode.FOLLOWUP) this else copy(mode = PendingUserMessageMode.FOLLOWUP)
}

fun buildCollectedPendingUserMessage(messages: List<PendingUserMessage>): PendingUserMessage {
    require(messages.isNotEmpty()) { "messages must not be empty" }
    if (messages.size == 1) {
        return messages.single().asFollowup()
    }
    val text = buildString {
        appendLine("下面是用户在上一轮运行时连续排队补充的消息，请按顺序处理：")
        messages.forEachIndexed { index, message ->
            appendLine()
            appendLine("Queued #${index + 1}:")
            appendLine(message.previewText(maxChars = 4_000))
        }
    }.trim()
    return PendingUserMessage(
        id = messages.joinToString(separator = "+") { it.id },
        parts = listOf(UIMessagePart.Text(text)),
        answer = messages.any { it.answer },
        mode = PendingUserMessageMode.FOLLOWUP,
        createdAtMs = messages.minOf { it.createdAtMs },
    )
}

fun PendingUserMessage.previewText(maxChars: Int = 180): String {
    val text = parts.joinToString(separator = "\n") { part ->
        when (part) {
            is UIMessagePart.Text -> part.text
            is UIMessagePart.Image -> "[图片]"
            is UIMessagePart.Video -> "[视频]"
            is UIMessagePart.Audio -> "[音频]"
            is UIMessagePart.Document -> "[文件] ${part.fileName}"
            else -> part.toString()
        }
    }.trim()
    return if (text.length <= maxChars) text else text.take(maxChars).trimEnd() + "..."
}
