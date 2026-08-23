package app.amber.core.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import app.amber.ai.ui.UIMessage
import app.amber.agent.Screen
import app.amber.feature.ui.context.Navigator
import kotlin.uuid.Uuid

private const val TAG = "ChatUtil"

fun navigateToChatPage(
    navigator: Navigator,
    chatId: Uuid = Uuid.random(),
    initText: String? = null,
    initFiles: List<Uri> = emptyList(),
    nodeId: Uuid? = null,
) {
    Log.i(TAG, "navigateToChatPage: navigate to $chatId")
    // ChatPage receives `text` as base64 (line 208 calls text?.base64Decode()) — that is the
    // contract every other navigator-to-Chat call site follows. Encode here so callers can
    // pass plain text without each remembering to base64-encode it (and crashing the app
    // with IllegalArgumentException when they forget).
    navigator.clearAndNavigate(
        Screen.Chat(
            id = chatId.toString(),
            text = initText?.takeIf { it.isNotEmpty() }?.base64Encode(),
            files = initFiles.map { it.toString() },
            nodeId = nodeId?.toString(),
        )
    )
}

fun Context.copyMessageToClipboard(message: UIMessage) {
    this.writeClipboardText(message.toText())
}

/**
 * 构建消息的引用块文本: 只取文本部分 (不含 reasoning/tool), 每行加 "> " 前缀,
 * 总长超过 [maxLength] 时截断并加省略号. 无文本内容时返回空串.
 */
fun UIMessage.toQuoteBlock(maxLength: Int = 200): String {
    val text = toText().trim()
    if (text.isEmpty()) return ""
    val truncated = if (text.length > maxLength) text.take(maxLength).trimEnd() + "…" else text
    return truncated.lineSequence().joinToString("\n") { "> $it" }
}
