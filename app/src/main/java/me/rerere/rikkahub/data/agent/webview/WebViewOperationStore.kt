package me.rerere.rikkahub.data.agent.webview

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

data class WebViewLink(
    val title: String,
    val url: String,
)

enum class WebViewLoadStatus {
    IDLE,
    REQUESTED,
    LOADING,
    INTERACTIVE,
    READY,
    STALLED,
    FAILED,
}

data class WebViewOperationState(
    val loadId: String = "",
    val toolCallId: String = "",
    val requestedUrl: String = "",
    val committedUrl: String = "",
    val url: String = "",
    val title: String = "",
    val loadingProgress: Int = 0,
    val status: WebViewLoadStatus = WebViewLoadStatus.IDLE,
    val readableText: String = "",
    val links: List<WebViewLink> = emptyList(),
    val thumbnailPath: String = "",
    val openedAtEpochMillis: Long = 0L,
    val lastProgressAtEpochMillis: Long = 0L,
    val lastExtractAtEpochMillis: Long = 0L,
    val lastError: String = "",
    val rendererActive: Boolean = false,
    val updatedAtEpochMillis: Long = 0L,
) {
    val displayUrl: String get() = committedUrl.ifBlank { url.ifBlank { requestedUrl } }
    val hasPage: Boolean get() = requestedUrl.isNotBlank() || url.isNotBlank()
    val hasReadableContent: Boolean get() = readableText.isNotBlank() || title.isNotBlank() || links.isNotEmpty()
    val isLoading: Boolean get() = status == WebViewLoadStatus.REQUESTED || status == WebViewLoadStatus.LOADING
    val statusValue: String get() = status.name.lowercase()
}

class WebViewOperationStore {
    private val _state = MutableStateFlow(WebViewOperationState())
    val state: StateFlow<WebViewOperationState> = _state.asStateFlow()
    private val loadSequence = AtomicLong()
    private var lastReadableCaptureKey: String = ""
    private var lastReadableCaptureAtMillis: Long = 0L
    private var lastThumbnailCaptureKey: String = ""
    private var lastThumbnailCaptureAtMillis: Long = 0L

    fun open(url: String, toolCallId: String = "webview_${System.currentTimeMillis()}"): String {
        val now = System.currentTimeMillis()
        val loadId = "webview_load_${now}_${loadSequence.incrementAndGet()}"
        _state.value = WebViewOperationState(
            loadId = loadId,
            toolCallId = toolCallId,
            requestedUrl = url,
            url = url,
            status = WebViewLoadStatus.REQUESTED,
            loadingProgress = 1,
            openedAtEpochMillis = now,
            lastProgressAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        return loadId
    }

    fun shouldExtractReadablePage(loadId: String, url: String?, now: Long = System.currentTimeMillis()): Boolean {
        if (url.isNullOrBlank()) return false
        val key = "$loadId|$url"
        if (key == lastReadableCaptureKey && now - lastReadableCaptureAtMillis < READABLE_CAPTURE_INTERVAL_MS) {
            return false
        }
        lastReadableCaptureKey = key
        lastReadableCaptureAtMillis = now
        return true
    }

    fun shouldCaptureThumbnail(loadId: String, url: String?, now: Long = System.currentTimeMillis()): Boolean {
        if (url.isNullOrBlank()) return false
        val key = "$loadId|$url"
        if (key == lastThumbnailCaptureKey && now - lastThumbnailCaptureAtMillis < THUMBNAIL_CAPTURE_INTERVAL_MS) {
            return false
        }
        lastThumbnailCaptureKey = key
        lastThumbnailCaptureAtMillis = now
        return true
    }

    fun markRendererActive(loadId: String, active: Boolean) {
        _state.update { current ->
            if (current.loadId != loadId) return@update current
            current.copy(rendererActive = active, updatedAtEpochMillis = System.currentTimeMillis())
        }
    }

    fun updateLoading(loadId: String, url: String?, progress: Int) {
        if (loadId.isBlank()) return
        _state.update { current ->
            if (current.loadId != loadId) return@update current
            val now = System.currentTimeMillis()
            val resolvedUrl = url.orEmpty().takeIf { it.isNotBlank() } ?: current.displayUrl
            val nextProgress = progress.coerceIn(0, 100)
            val nextStatus = when {
                nextProgress >= 100 && current.hasReadableContent -> WebViewLoadStatus.READY
                current.hasReadableContent -> WebViewLoadStatus.INTERACTIVE
                nextProgress <= 1 -> WebViewLoadStatus.LOADING
                else -> WebViewLoadStatus.LOADING
            }
            current.copy(
                url = resolvedUrl,
                committedUrl = resolvedUrl,
                loadingProgress = nextProgress,
                status = nextStatus,
                lastProgressAtEpochMillis = now,
                lastError = "",
                updatedAtEpochMillis = now,
            )
        }
    }

    fun updateReadablePage(
        loadId: String,
        url: String?,
        title: String?,
        readableText: String,
        links: List<WebViewLink>,
    ) {
        if (loadId.isBlank()) return
        _state.update { current ->
            if (current.loadId != loadId) return@update current
            val now = System.currentTimeMillis()
            val resolvedUrl = url.orEmpty().takeIf { it.isNotBlank() } ?: current.displayUrl
            val nextReadableText = readableText.take(MAX_READABLE_TEXT_CHARS)
            val nextLinks = links.take(MAX_LINKS)
            val hasContent = nextReadableText.isNotBlank() || !title.isNullOrBlank() || nextLinks.isNotEmpty()
            val nextStatus = when {
                current.loadingProgress >= 100 && hasContent -> WebViewLoadStatus.READY
                hasContent -> WebViewLoadStatus.INTERACTIVE
                current.status == WebViewLoadStatus.REQUESTED -> WebViewLoadStatus.LOADING
                else -> current.status
            }
            current.copy(
                url = resolvedUrl,
                committedUrl = resolvedUrl,
                title = title.orEmpty().take(MAX_TITLE_CHARS),
                status = nextStatus,
                readableText = nextReadableText,
                links = nextLinks,
                lastExtractAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        }
    }

    fun markPageFinished(loadId: String, url: String?) {
        updateLoading(loadId, url, 100)
        _state.update { current ->
            if (current.loadId != loadId) return@update current
            val nextStatus = if (current.hasReadableContent) WebViewLoadStatus.READY else current.status
            current.copy(status = nextStatus, updatedAtEpochMillis = System.currentTimeMillis())
        }
    }

    fun markFailed(loadId: String, url: String?, error: String) {
        if (loadId.isBlank()) return
        _state.update { current ->
            if (current.loadId != loadId) return@update current
            val now = System.currentTimeMillis()
            val resolvedUrl = url.orEmpty().takeIf { it.isNotBlank() } ?: current.displayUrl
            current.copy(
                url = resolvedUrl,
                committedUrl = resolvedUrl,
                status = WebViewLoadStatus.FAILED,
                lastError = error.take(MAX_TITLE_CHARS),
                updatedAtEpochMillis = now,
            )
        }
    }

    fun refreshStalled(now: Long = System.currentTimeMillis()): WebViewOperationState {
        _state.update { current ->
            if (
                current.status in setOf(WebViewLoadStatus.REQUESTED, WebViewLoadStatus.LOADING) &&
                current.lastProgressAtEpochMillis > 0L &&
                now - current.lastProgressAtEpochMillis > STALLED_AFTER_MS
            ) {
                current.copy(
                    status = WebViewLoadStatus.STALLED,
                    updatedAtEpochMillis = now,
                )
            } else {
                current
            }
        }
        return _state.value
    }

    fun updateThumbnail(loadId: String, url: String?, thumbnailPath: String) {
        if (url.isNullOrBlank() || thumbnailPath.isBlank()) return
        _state.update { current ->
            if (current.loadId != loadId) return@update current
            current.copy(
                url = url,
                committedUrl = url,
                thumbnailPath = thumbnailPath,
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
        }
    }

    fun clear() {
        _state.value = WebViewOperationState()
    }

    companion object {
        private const val MAX_TITLE_CHARS = 240
        private const val MAX_READABLE_TEXT_CHARS = 40_000
        private const val MAX_LINKS = 40
        private const val READABLE_CAPTURE_INTERVAL_MS = 2_500L
        private const val THUMBNAIL_CAPTURE_INTERVAL_MS = 4_000L
        private const val STALLED_AFTER_MS = 12_000L
    }
}
