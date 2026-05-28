package app.amber.feature.webmount.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import app.amber.core.agent.utils.boolean
import app.amber.core.agent.utils.long
import app.amber.core.agent.utils.requiredString
import app.amber.core.agent.utils.string
import app.amber.core.ai.transformers.OcrTransformer
import app.amber.feature.webmount.primitives.WebViewScreenshot

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

internal fun createVisualSnapshotTool(deps: WebMountDeps): Tool = Tool(
    name = "wm_visual_snapshot",
    description = """
        List visible visual candidates in a WebMount page without sending pixels to a model: images,
        canvas, SVG, video, background images, and cross-origin iframes with rects, alt/title, nearby
        text, and refs. Use this before wm_visual_read to avoid full-page screenshot payloads.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", stringProp("Session id."))
                put("max_candidates", integerProp("Default 30, cap 120."))
            },
            required = listOf("session_id"),
        )
    },
    execute = { input ->
        deps.track("wm_visual_snapshot", "WebMount 视觉候选", input) {
            val sessionId = input.requiredString("session_id")
            val handle = deps.pool.peek(sessionId) ?: error("session not found: $sessionId")
            val payload = handle.callBridge(
                "visual_snapshot",
                buildJsonObject {
                    put("max_candidates", (input.long("max_candidates") ?: 30L).coerceIn(0L, 120L))
                },
                timeoutMs = 5_000L,
            )
            listOf(UIMessagePart.Text(buildJsonObject {
                put("session_id", sessionId)
                put("remote_vision_used", false)
                put("result", payload)
            }.toString()))
        }
    },
)

internal fun createVisualReadTool(deps: WebMountDeps): Tool = Tool(
    name = "wm_visual_read",
    description = """
        Read a small visual region from a WebMount page using the configured vision recognition model.
        Prefer target_ref or selector from wm_visual_snapshot/wm_observe. Sends only a cropped ROI
        (JPEG q70, max edge 1280) to the configured provider, not the full page.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", stringProp("Session id."))
                put("target", stringProp("Visual candidate ref returned by wm_visual_snapshot or wm_observe."))
                put("selector", stringProp("Selector fallback for the visual target."))
                put("x", integerProp("Viewport x for explicit region."))
                put("y", integerProp("Viewport y for explicit region."))
                put("width", integerProp("Region width."))
                put("height", integerProp("Region height."))
                put("prompt", stringProp("Optional focused visual question/instruction."))
                put("max_edge", integerProp("Max ROI edge. Default 1280, cap 1600."))
            },
            required = listOf("session_id"),
        )
    },
    needsApproval = true,
    allowsAutoApproval = false,
    mandatoryApproval = true,
    execute = { input ->
        deps.track("wm_visual_read", "WebMount 视觉读取", input.safeVisualPreview()) {
            val sessionId = input.requiredString("session_id")
            val handle = deps.pool.peek(sessionId) ?: error("session not found: $sessionId")
            val region = input.explicitRegion() ?: run {
                val target = input.string("target")
                val selector = input.string("selector")
                require(target != null || selector != null) {
                    "wm_visual_read requires target, selector, or explicit x/y/width/height region"
                }
                val targetPayload = handle.callBridge(
                    "target_region",
                    buildJsonObject {
                        target?.let { put("target", it) }
                        selector?.let { put("selector", it) }
                    },
                    timeoutMs = 5_000L,
                )
                targetPayload.regionFromTarget()
            }
            val maxEdge = (input.long("max_edge") ?: 1_280L).coerceIn(320L, 1_600L).toInt()
            val image = WebViewScreenshot.captureRegion(
                handle = handle,
                region = region.expand(8),
                format = WebViewScreenshot.Format.JPEG,
                quality = 70,
                maxEdge = maxEdge,
            )
            when (image) {
                is WebViewScreenshot.Result.Failed -> listOf(UIMessagePart.Text(buildJsonObject {
                    put("session_id", sessionId)
                    put("ok", false)
                    put("error", image.message)
                }.toString()))
                is WebViewScreenshot.Result.Success -> {
                    val dataUrl = "data:image/jpeg;base64,${image.base64}"
                    val prompt = input.string("prompt")?.let {
                        """
                        Read only the provided cropped webpage region. Answer the user's focused request:
                        $it
                        Keep the result compact and factual.
                        """.trimIndent()
                    }
                    val vision = OcrTransformer.performImageRecognition(
                        part = UIMessagePart.Image(dataUrl),
                        promptOverride = prompt,
                        useCache = false,
                    )
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("session_id", sessionId)
                        put("ok", true)
                        put("remote_vision_used", true)
                        put("privacy_notice", "Only the cropped WebMount viewport region was sent to the configured vision provider.")
                        put("region", buildJsonObject {
                            put("x", region.x)
                            put("y", region.y)
                            put("width", region.width)
                            put("height", region.height)
                        })
                        put("image_width", image.width)
                        put("image_height", image.height)
                        put("image_size_bytes", image.sizeBytes)
                        put("result", vision)
                    }.toString()))
                }
            }
        }
    },
)

private fun WebViewScreenshot.Region.expand(px: Int): WebViewScreenshot.Region =
    WebViewScreenshot.Region(
        x = (x - px).coerceAtLeast(0),
        y = (y - px).coerceAtLeast(0),
        width = width + px * 2,
        height = height + px * 2,
    )

private fun kotlinx.serialization.json.JsonElement.explicitRegion(): WebViewScreenshot.Region? {
    val x = long("x")?.toInt()
    val y = long("y")?.toInt()
    val width = long("width")?.toInt()
    val height = long("height")?.toInt()
    return if (x != null && y != null && width != null && height != null && width > 0 && height > 0) {
        WebViewScreenshot.Region(x, y, width, height)
    } else {
        null
    }
}

private fun kotlinx.serialization.json.JsonElement.regionFromTarget(): WebViewScreenshot.Region {
    val rect = jsonObject["rect"]?.jsonArray
        ?: jsonObject["target"]?.jsonObject?.get("rect")?.jsonArray
        ?: error("target did not return a readable rect")
    val values = rect.map { it.jsonPrimitive.intOrNull ?: 0 }
    require(values.size >= 4 && values[2] > 0 && values[3] > 0) {
        "target rect is empty"
    }
    return WebViewScreenshot.Region(values[0], values[1], values[2], values[3])
}

private fun kotlinx.serialization.json.JsonElement.safeVisualPreview() = buildJsonObject {
    put("session_id", string("session_id"))
    put("has_target", string("target") != null)
    put("has_selector", string("selector") != null)
    put("has_region", long("x") != null && long("y") != null && long("width") != null && long("height") != null)
    long("x")?.let { put("x", it) }
    long("y")?.let { put("y", it) }
    long("width")?.let { put("width", it) }
    long("height")?.let { put("height", it) }
    put("prompt_chars", string("prompt")?.length ?: 0)
}
