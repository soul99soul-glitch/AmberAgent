package me.rerere.rikkahub.data.agent.webview

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class WebViewLink(
    val title: String,
    val url: String,
)

data class WebViewOperationState(
    val toolCallId: String = "",
    val requestedUrl: String = "",
    val url: String = "",
    val title: String = "",
    val loadingProgress: Int = 0,
    val readableText: String = "",
    val links: List<WebViewLink> = emptyList(),
    val thumbnailPath: String = "",
    val updatedAtEpochMillis: Long = 0L,
) {
    val hasPage: Boolean get() = url.isNotBlank()
    val isLoading: Boolean get() = hasPage && loadingProgress in 1..99
}

class WebViewOperationStore {
    private val _state = MutableStateFlow(WebViewOperationState())
    val state: StateFlow<WebViewOperationState> = _state.asStateFlow()

    fun open(url: String, toolCallId: String = "webview_${System.currentTimeMillis()}") {
        _state.value = WebViewOperationState(
            toolCallId = toolCallId,
            requestedUrl = url,
            url = url,
            loadingProgress = 1,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    fun updateLoading(url: String?, progress: Int) {
        if (url.isNullOrBlank()) return
        _state.update { current ->
            if (!current.samePage(url)) return@update current
            current.copy(
                url = url,
                loadingProgress = progress.coerceIn(0, 100),
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
        }
    }

    fun updateReadablePage(
        url: String?,
        title: String?,
        readableText: String,
        links: List<WebViewLink>,
    ) {
        if (url.isNullOrBlank()) return
        _state.update { current ->
            if (!current.samePage(url)) return@update current
            current.copy(
                url = url,
                title = title.orEmpty().take(MAX_TITLE_CHARS),
                loadingProgress = 100,
                readableText = readableText.take(MAX_READABLE_TEXT_CHARS),
                links = links.take(MAX_LINKS),
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
        }
    }

    fun updateThumbnail(url: String?, thumbnailPath: String) {
        if (url.isNullOrBlank() || thumbnailPath.isBlank()) return
        _state.update { current ->
            if (!current.samePage(url)) return@update current
            current.copy(
                thumbnailPath = thumbnailPath,
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
        }
    }

    fun clear() {
        _state.value = WebViewOperationState()
    }

    private fun WebViewOperationState.samePage(candidate: String): Boolean =
        url.isBlank() || url == candidate || requestedUrl == candidate || loadingProgress in 1..99

    companion object {
        private const val MAX_TITLE_CHARS = 240
        private const val MAX_READABLE_TEXT_CHARS = 40_000
        private const val MAX_LINKS = 40
    }
}
