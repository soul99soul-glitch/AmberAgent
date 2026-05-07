package me.rerere.rikkahub

import kotlin.uuid.Uuid

const val LAUNCH_START_MODE_PREF = "launch_start_mode"
const val LEGACY_CREATE_NEW_CONVERSATION_ON_START_PREF = "create_new_conversation_on_start"
const val LAST_CONVERSATION_ID_PREF = "lastConversationId"

enum class LaunchStartMode {
    AUTO,
    LAST_SESSION,
    NEW_CHAT,
    HOME,
}

fun parseLaunchStartMode(value: String?): LaunchStartMode? =
    value?.let { stored ->
        LaunchStartMode.entries.firstOrNull { it.name.equals(stored, ignoreCase = true) }
    }

fun migrateLaunchStartMode(
    storedMode: String?,
    legacyCreateNewConversationOnStart: Boolean?,
): LaunchStartMode =
    parseLaunchStartMode(storedMode)
        ?: when (legacyCreateNewConversationOnStart) {
            false -> LaunchStartMode.LAST_SESSION
            // Default for fresh installs on the Pulse-redesign branch is
            // HOME — the app launches into the Conversations list page
            // (Screen.History) instead of immediately spawning a new chat,
            // matching the Pulse mockup where the conversations list is
            // the primary entry surface and individual chats are detail
            // views reached by tapping a row.
            true, null -> LaunchStartMode.HOME
        }

fun resolveLaunchStartScreen(
    mode: LaunchStartMode,
    lastConversationId: String?,
    newConversationId: String,
): Screen {
    val reusableLastConversationId = lastConversationId?.takeIf { it.isValidUuid() }
    return when (mode) {
        LaunchStartMode.AUTO -> Screen.Chat(reusableLastConversationId ?: newConversationId)
        LaunchStartMode.LAST_SESSION -> Screen.Chat(reusableLastConversationId ?: newConversationId)
        LaunchStartMode.NEW_CHAT -> Screen.Chat(newConversationId)
        LaunchStartMode.HOME -> Screen.History
    }
}

/**
 * Returns the list of screens to seed the backstack with. Always
 * includes Screen.History at the bottom so a back-press from any
 * Chat lands on the conversations list instead of finishing the
 * activity. When the resolved start screen IS Screen.History (HOME
 * mode), the list is just [History]; otherwise the resolved start
 * screen is layered on top of History.
 */
fun resolveLaunchBackStack(
    mode: LaunchStartMode,
    lastConversationId: String?,
    newConversationId: String,
): List<Screen> {
    val start = resolveLaunchStartScreen(mode, lastConversationId, newConversationId)
    return if (start is Screen.History) listOf(start) else listOf(Screen.History, start)
}

private fun String.isValidUuid(): Boolean = runCatching {
    Uuid.parse(this)
}.isSuccess
