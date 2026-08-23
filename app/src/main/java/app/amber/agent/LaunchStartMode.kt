package app.amber.agent

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
            // 旧版里 create_new_conversation_on_start 默认就是 true（且会被持久化），
            // 它的存在不代表用户主动选择，只有显式关掉（false = 恢复上次会话）才算主动选择；
            // 其余情况一律落到新的会话首页。
            false -> LaunchStartMode.LAST_SESSION
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
        LaunchStartMode.HOME -> Screen.SessionHome
    }
}

private fun String.isValidUuid(): Boolean = runCatching {
    Uuid.parse(this)
}.isSuccess
