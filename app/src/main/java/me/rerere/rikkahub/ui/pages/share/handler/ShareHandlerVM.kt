package me.rerere.rikkahub.ui.pages.share.handler

import androidx.lifecycle.ViewModel
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator

class ShareHandlerVM(
    text: String,
    private val settingsStore: SettingsAggregator
) : ViewModel() {
    val shareText = checkNotNull(text)

    suspend fun selectAmberAgent() {
        settingsStore.updateAssistant(DEFAULT_ASSISTANT_ID)
    }
}
