package me.rerere.rikkahub.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.Navigator
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
    // Replace any Chat already on top so we don't stack chats every
    // time the user taps "+", but PRESERVE everything beneath (the
    // canonical History entry, in particular) so back from the new
    // chat falls through to the conversations list. ShareHandler is
    // a transient redirect surface — when it navigates to Chat we
    // want to be replaced too, not stacked on top of, so back from
    // the chat doesn't re-trigger the share handler in a loop.
    navigator.pushOrReplaceTop(
        screen = Screen.Chat(
            id = chatId.toString(),
            text = initText,
            files = initFiles.map { it.toString() },
            nodeId = nodeId?.toString(),
        ),
        replaceWhen = { it is Screen.Chat || it is Screen.ShareHandler },
    )
}

fun Context.copyMessageToClipboard(message: UIMessage) {
    this.writeClipboardText(message.toText())
}
