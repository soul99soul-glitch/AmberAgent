package app.amber.core.service

import android.content.Context
import kotlinx.serialization.Serializable
import app.amber.agent.R
import app.amber.ai.ui.UIMessagePart
import app.amber.core.utils.appLocale
import java.util.Locale

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

data class PendingUserMessageDisplayCopy(
    val imageLabel: String,
    val videoLabel: String,
    val audioLabel: String,
    val fileLabel: String,
    val localeTag: String = Locale.ENGLISH.toLanguageTag(),
) {
    companion object {
        val ENGLISH = PendingUserMessageDisplayCopy(
            imageLabel = "Images",
            videoLabel = "Video",
            audioLabel = "Audio",
            fileLabel = "File",
        )

        fun from(context: Context): PendingUserMessageDisplayCopy = PendingUserMessageDisplayCopy(
            imageLabel = context.getString(R.string.chat_message_tool_preview_images),
            videoLabel = context.getString(R.string.permission_display_media_video_title),
            audioLabel = context.getString(R.string.permission_display_media_audio_title),
            fileLabel = context.getString(R.string.chat_message_tool_kind_file),
            localeTag = context.appLocale().toLanguageTag().ifBlank { Locale.ENGLISH.toLanguageTag() },
        )
    }
}

fun buildCollectedPendingUserMessage(
    messages: List<PendingUserMessage>,
    copy: PendingUserMessageDisplayCopy = PendingUserMessageDisplayCopy.ENGLISH,
): PendingUserMessage {
    require(messages.isNotEmpty()) { "messages must not be empty" }
    if (messages.size == 1) {
        return messages.single().asFollowup()
    }
    val text = buildString {
        appendLine("The following messages were queued during the previous run. Process them in order.")
        appendLine("Use app locale ${copy.localeTag} for user-facing text and preserve protocol/schema field names.")
        messages.forEachIndexed { index, message ->
            appendLine()
            appendLine("Queued #${index + 1}:")
            appendLine(message.previewText(maxChars = 4_000, copy = copy))
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

fun PendingUserMessage.previewText(
    maxChars: Int = 180,
    copy: PendingUserMessageDisplayCopy = PendingUserMessageDisplayCopy.ENGLISH,
): String {
    val text = parts.joinToString(separator = "\n") { part ->
        when (part) {
            is UIMessagePart.Text -> part.text
            is UIMessagePart.Image -> "[${copy.imageLabel}]"
            is UIMessagePart.Video -> "[${copy.videoLabel}]"
            is UIMessagePart.Audio -> "[${copy.audioLabel}]"
            is UIMessagePart.Document -> "[${copy.fileLabel}] ${part.fileName}"
            else -> part.toString()
        }
    }.trim()
    return if (text.length <= maxChars) text else text.take(maxChars).trimEnd() + "..."
}
