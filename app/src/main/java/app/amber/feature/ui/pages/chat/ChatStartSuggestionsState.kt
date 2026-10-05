package app.amber.feature.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalConfiguration
import app.amber.core.service.ChatStartSuggestionGenerator
import app.amber.core.settings.Settings
import app.amber.core.settings.findProvider
import app.amber.core.settings.resolveTaskChatModel

@Composable
internal fun rememberChatStartSuggestions(vm: ChatVM, settings: Settings): State<List<String>> {
    val locale = LocalConfiguration.current.locales[0]
    val model = settings.resolveTaskChatModel(settings.suggestionModelId)
    // Unrelated settings or keyboard changes must not trigger another model request.
    val requestKey = listOf(
        settings.init,
        model,
        model?.findProvider(settings.providers),
        ChatStartSuggestionGenerator.enabledScopes(settings),
        settings.agentRuntime.memoryRecall,
        locale,
    )
    return produceState<List<String>>(emptyList(), vm, requestKey) {
        value = emptyList()
        value = vm.generateStartSuggestions(settings, locale)
    }
}
