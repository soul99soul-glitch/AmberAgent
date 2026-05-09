package me.rerere.rikkahub.data.agent.office.radar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SlidesPreviewRequest(
    val title: String,
    val specJson: String,
)

object SlidesPreviewBus {
    private val _request = MutableStateFlow<SlidesPreviewRequest?>(null)
    val request: StateFlow<SlidesPreviewRequest?> = _request.asStateFlow()

    fun preview(title: String, specJson: String) {
        _request.value = SlidesPreviewRequest(title, specJson)
    }

    fun dismiss() {
        _request.value = null
    }
}
