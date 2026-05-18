package me.rerere.rikkahub.data.agent.webmount.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.tools.boolean
import me.rerere.rikkahub.data.agent.tools.long
import me.rerere.rikkahub.data.agent.tools.requiredString
import me.rerere.rikkahub.data.agent.tools.string
import me.rerere.rikkahub.data.agent.webmount.primitives.WebViewScreenshot

internal fun createScreenshotTool(deps: WebMountDeps): Tool = Tool(
    name = "wm_screenshot",
    description = """
        Capture the current page of a WebMount session and return its base64-encoded image in
        the tool result text (a `data:` URI). `full_page=false` (default) captures the visible
        viewport; `full_page=true` scrolls + stitches up to ~16384px tall. Uses native Android
        Bitmap rendering with a software-rendering fallback for hardware-accelerated WebViews.
        Heads up: full-page PNGs are large; for full-page captures prefer format="jpeg" with
        quality=75-85 to keep the agent's context manageable.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", stringProp("Session id."))
                put("full_page", booleanProp("Stitch scrolled slices (default false)."))
                put("format", stringProp("'png' (default) | 'jpeg'."))
                put("quality", integerProp("JPEG quality 1-100, default 85. PNG ignores this."))
            },
            required = listOf("session_id"),
        )
    },
    execute = { input ->
        deps.track("wm_screenshot", "WebMount 截图", input) {
            val sessionId = input.requiredString("session_id")
            val handle = deps.pool.peek(sessionId) ?: error("session not found: $sessionId")
            val fullPage = input.boolean("full_page") ?: false
            val format = when (input.string("format")?.lowercase()) {
                "jpeg", "jpg" -> WebViewScreenshot.Format.JPEG
                else -> WebViewScreenshot.Format.PNG
            }
            val quality = (input.long("quality") ?: 85L).coerceIn(1L, 100L).toInt()
            val result = WebViewScreenshot.capture(handle, fullPage, format, quality)
            when (result) {
                is WebViewScreenshot.Result.Success -> {
                    // Inline the base64 in the Text payload — every provider's
                    // tool-result serializer drops UIMessagePart.Image, so the
                    // model never sees standalone Image parts on a tool return.
                    // The wm_screenshot Tool's `outputBudgetChars` is bumped
                    // (see ToolRegistry.outputBudgetChars) so the base64 fits.
                    val mime = if (result.format == "jpeg") "image/jpeg" else "image/png"
                    val payload = buildJsonObject {
                        put("session_id", sessionId)
                        put("ok", true)
                        put("width", result.width)
                        put("height", result.height)
                        put("format", result.format)
                        put("size_bytes", result.sizeBytes)
                        put("full_page", fullPage)
                        put("image_data_url", "data:$mime;base64,${result.base64}")
                    }
                    listOf(UIMessagePart.Text(payload.toString()))
                }
                is WebViewScreenshot.Result.Failed -> {
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("session_id", sessionId)
                        put("ok", false)
                        put("error", result.message)
                    }.toString()))
                }
            }
        }
    },
)
