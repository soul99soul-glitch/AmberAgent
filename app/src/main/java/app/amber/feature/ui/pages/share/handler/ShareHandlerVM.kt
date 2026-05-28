package app.amber.feature.ui.pages.share.handler

import androidx.lifecycle.ViewModel
import app.amber.core.settings.DEFAULT_ASSISTANT_ID
import app.amber.core.settings.prefs.SettingsAggregator

class ShareHandlerVM(
    text: String,
    private val settingsStore: SettingsAggregator
) : ViewModel() {
    val shareText = checkNotNull(text)

    suspend fun selectAmberAgent() {
        settingsStore.updateAssistant(DEFAULT_ASSISTANT_ID)
    }
}
