package me.rerere.rikkahub.ui.pages.share.handler

import androidx.lifecycle.ViewModel
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.SettingsStore

class ShareHandlerVM(
    text: String,
    private val settingsStore: SettingsStore
) : ViewModel() {
    val shareText = checkNotNull(text)

    suspend fun selectAmberAgent() {
        settingsStore.updateAssistant(DEFAULT_ASSISTANT_ID)
    }
}
