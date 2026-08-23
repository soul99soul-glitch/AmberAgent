package app.amber.core.event

sealed class AppEvent {
    data class Speak(val text: String) : AppEvent()
    data class OpenDeepRead(
        val topicId: String,
        val title: String,
        val sourceUrl: String? = null,
        val forceRegenerate: Boolean = false,
    ) : AppEvent()

    /**
     * P6-02: the user confirmed an image edit in a [GeneratedImageCarousel].
     * [sourceImageUrl] is the local `file://` URL of the image to modify;
     * [prompt] is the prefilled modification instruction the user confirmed.
     * The chat screen routes this to its own conversation only when the URL
     * lives inside that conversation's chat_images dir.
     */
    data class EditGeneratedImage(
        val sourceImageUrl: String,
        val prompt: String,
    ) : AppEvent()

    /**
     * MCP OAuth 授权回调（`amberagent://mcp-oauth-callback` deep link 解析结果）。
     * 由 [app.amber.feature.ui.activity.McpOAuthCallbackActivity] 解析后经 [AppEventBus]
     * 转发，等待授权的 [app.amber.core.ai.mcp.McpOAuthCoordinator] 按 state 匹配并完成令牌交换。
     */
    data class McpOAuthCallback(
        val state: String?,
        val code: String?,
        val error: String?,
    ) : AppEvent()
}
