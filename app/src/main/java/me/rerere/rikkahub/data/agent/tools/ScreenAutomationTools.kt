package me.rerere.rikkahub.data.agent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.delay
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.AgentToolActivityStore
import me.rerere.rikkahub.data.automation.AmberAccessibilityService
import me.rerere.rikkahub.data.automation.ScreenCaptureManager

class ScreenAutomationTools(
    private val context: Context,
    private val screenCaptureManager: ScreenCaptureManager,
    private val activityStore: AgentToolActivityStore,
) {
    fun getTools(): List<Tool> = listOf(
        screenClickTool,
        screenLongClickTool,
        screenSwipeTool,
        screenInputTextTool,
        screenBackTool,
        screenHomeTool,
        screenOpenAppTool,
        screenOpenUrlTool,
        screenReadUiTool,
        screenFindTextTool,
        screenTapTextTool,
        screenWaitForTextTool,
        screenScrollUntilTool,
        screenScreenshotTool,
        vlmTaskTool,
    )

    private val screenClickTool = Tool(
        name = "screen_click",
        description = "Tap screen coordinates using AmberAgent Accessibility after user approval.",
        parameters = { coordinateSchema("x", "y") },
        needsApproval = true,
        execute = { input ->
            trackScreenTool("screen_click", "点击屏幕", input) {
                val service = requireService()
                val point = requireDisplayPoint(
                    x = input.requiredFloat("x"),
                    y = input.requiredFloat("y"),
                    label = "screen_click"
                )
                val ok = service.tap(point.x, point.y)
                result(ok)
            }
        }
    )

    private val screenLongClickTool = Tool(
        name = "screen_long_click",
        description = "Long-press screen coordinates using AmberAgent Accessibility after user approval.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("x", numberProp("X coordinate."))
                    put("y", numberProp("Y coordinate."))
                    put("duration_ms", integerProp("Press duration in milliseconds. Defaults to 600."))
                },
                required = listOf("x", "y")
            )
        },
        needsApproval = true,
        execute = { input ->
            trackScreenTool("screen_long_click", "长按屏幕", input) {
                val service = requireService()
                val point = requireDisplayPoint(
                    x = input.requiredFloat("x"),
                    y = input.requiredFloat("y"),
                    label = "screen_long_click"
                )
                val ok = service.longPress(
                    x = point.x,
                    y = point.y,
                    durationMillis = input.long("duration_ms") ?: 600L
                )
                result(ok)
            }
        }
    )

    private val screenSwipeTool = Tool(
        name = "screen_swipe",
        description = "Swipe between screen coordinates using AmberAgent Accessibility after user approval.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("from_x", numberProp("Start X coordinate."))
                    put("from_y", numberProp("Start Y coordinate."))
                    put("to_x", numberProp("End X coordinate."))
                    put("to_y", numberProp("End Y coordinate."))
                    put("duration_ms", integerProp("Swipe duration in milliseconds. Defaults to 350."))
                },
                required = listOf("from_x", "from_y", "to_x", "to_y")
            )
        },
        needsApproval = true,
        execute = { input ->
            trackScreenTool("screen_swipe", "滑动屏幕", input) {
                val service = requireService()
                val from = requireDisplayPoint(
                    x = input.requiredFloat("from_x"),
                    y = input.requiredFloat("from_y"),
                    label = "screen_swipe.from"
                )
                val to = requireDisplayPoint(
                    x = input.requiredFloat("to_x"),
                    y = input.requiredFloat("to_y"),
                    label = "screen_swipe.to"
                )
                val ok = service.swipe(
                    fromX = from.x,
                    fromY = from.y,
                    toX = to.x,
                    toY = to.y,
                    durationMillis = input.long("duration_ms") ?: 350L
                )
                result(ok)
            }
        }
    )

    private val screenInputTextTool = Tool(
        name = "screen_input_text",
        description = "Set text on the currently focused editable field using Accessibility.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", stringProp("Text to enter."))
                },
                required = listOf("text")
            )
        },
        needsApproval = true,
        execute = { input ->
            trackScreenTool("screen_input_text", "输入文字", input) {
                val service = requireService()
                result(service.setFocusedText(input.requiredString("text")))
            }
        }
    )

    private val screenBackTool = Tool(
        name = "screen_back",
        description = "Press Android Back using Accessibility.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        needsApproval = true,
        execute = { input ->
            trackScreenTool("screen_back", "返回", input) {
                result(requireService().back())
            }
        }
    )

    private val screenHomeTool = Tool(
        name = "screen_home",
        description = "Press Android Home using Accessibility.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        needsApproval = true,
        execute = { input ->
            trackScreenTool("screen_home", "回到桌面", input) {
                result(requireService().home())
            }
        }
    )

    private val screenOpenAppTool = Tool(
        name = "screen_open_app",
        description = "Open an installed Android app by package name.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("package_name", stringProp("Android package name to launch."))
                },
                required = listOf("package_name")
            )
        },
        needsApproval = true,
        execute = { input ->
            trackScreenTool("screen_open_app", "打开应用", input) {
                val packageName = input.requiredString("package_name")
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                    ?: error("No launch intent for package: $packageName")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                result(true)
            }
        }
    )

    private val screenOpenUrlTool = Tool(
        name = "screen_open_url",
        description = "Open an http/https URL with Android ACTION_VIEW. Prefer webview_open for in-app visual preview; use this when the user wants the system browser/app.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("url", stringProp("http or https URL to open."))
                },
                required = listOf("url")
            )
        },
        needsApproval = true,
        execute = { input ->
            trackScreenTool("screen_open_url", "打开 URL", input, runtime = "Android Intent") {
                val url = input.requiredString("url")
                require(url.startsWith("http://") || url.startsWith("https://")) {
                    "Only http and https URLs are allowed"
                }
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                result(true)
            }
        }
    )

    private val screenReadUiTool = Tool(
        name = "screen_read_ui",
        description = "Read the current Accessibility UI tree for screen reasoning.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("max_nodes", integerProp("Maximum nodes to include. Defaults to 120."))
                }
            )
        },
        execute = { input ->
            trackScreenTool("screen_read_ui", "读取 UI 树", input) {
                val service = requireService()
                textJson {
                    put("ui_tree", service.dumpUiTree(input.int("max_nodes") ?: 120))
                }
            }
        }
    )

    private val screenFindTextTool = Tool(
        name = "screen_find_text",
        description = "Find text in the current Accessibility UI tree and return matching node bounds.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", stringProp("Text to find."))
                    put("max_nodes", integerProp("Maximum nodes to scan. Defaults to 160."))
                },
                required = listOf("text")
            )
        },
        execute = { input ->
            trackScreenTool("screen_find_text", "查找屏幕文字", input) {
                val matches = requireService().findTextNodes(
                    query = input.requiredString("text"),
                    maxNodes = input.int("max_nodes") ?: 160,
                )
                textJson {
                    put("matches", buildJsonArray {
                        matches.forEachIndexed { index, match ->
                            add(match.toJson(index))
                        }
                    })
                }
            }
        }
    )

    private val screenTapTextTool = Tool(
        name = "screen_tap_text",
        description = "Tap the first Accessibility node containing the requested text.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", stringProp("Text to tap."))
                    put("max_nodes", integerProp("Maximum nodes to scan. Defaults to 160."))
                },
                required = listOf("text")
            )
        },
        needsApproval = true,
        execute = { input ->
            trackScreenTool("screen_tap_text", "点击文字", input) {
                val service = requireService()
                val match = service.findTextNodes(
                    query = input.requiredString("text"),
                    maxNodes = input.int("max_nodes") ?: 160,
                ).firstOrNull() ?: error("Text not found on screen: ${input.requiredString("text")}")
                val ok = service.tap(match.bounds.exactCenterX(), match.bounds.exactCenterY())
                textJson {
                    put("success", ok)
                    put("match", match.toJson(0))
                }
            }
        }
    )

    private val screenWaitForTextTool = Tool(
        name = "screen_wait_for_text",
        description = "Wait until text appears in the Accessibility UI tree.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", stringProp("Text to wait for."))
                    put("timeout_ms", integerProp("Timeout in milliseconds. Defaults to 10000."))
                    put("interval_ms", integerProp("Polling interval in milliseconds. Defaults to 500."))
                },
                required = listOf("text")
            )
        },
        execute = { input ->
            trackScreenTool("screen_wait_for_text", "等待屏幕文字", input) {
                val deadline = System.currentTimeMillis() + (input.long("timeout_ms") ?: 10_000L).coerceIn(500L, 60_000L)
                val interval = (input.long("interval_ms") ?: 500L).coerceIn(100L, 3_000L)
                val service = requireService()
                var matches = emptyList<me.rerere.rikkahub.data.automation.AccessibilityTextMatch>()
                while (System.currentTimeMillis() < deadline) {
                    matches = service.findTextNodes(input.requiredString("text"), maxNodes = 200)
                    if (matches.isNotEmpty()) break
                    delay(interval)
                }
                textJson {
                    put("found", matches.isNotEmpty())
                    put("matches", buildJsonArray { matches.take(5).forEachIndexed { index, match -> add(match.toJson(index)) } })
                }
            }
        }
    )

    private val screenScrollUntilTool = Tool(
        name = "screen_scroll_until",
        description = "Swipe repeatedly until target text appears in the Accessibility UI tree.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", stringProp("Text to find."))
                    put("direction", stringProp("Swipe direction: down, up, left, or right. Defaults to down."))
                    put("max_swipes", integerProp("Maximum swipes. Defaults to 8."))
                },
                required = listOf("text")
            )
        },
        needsApproval = true,
        execute = { input ->
            trackScreenTool("screen_scroll_until", "滚动查找文字", input) {
                val service = requireService()
                val maxSwipes = (input.int("max_swipes") ?: 8).coerceIn(1, 20)
                val direction = input.string("direction") ?: "down"
                var matches = service.findTextNodes(input.requiredString("text"), maxNodes = 200)
                var swipes = 0
                while (matches.isEmpty() && swipes < maxSwipes) {
                    swipeByDirection(service, direction)
                    swipes++
                    delay(450)
                    matches = service.findTextNodes(input.requiredString("text"), maxNodes = 200)
                }
                textJson {
                    put("found", matches.isNotEmpty())
                    put("swipes", swipes)
                    put("matches", buildJsonArray { matches.take(5).forEachIndexed { index, match -> add(match.toJson(index)) } })
                }
            }
        }
    )

    private val screenScreenshotTool = Tool(
        name = "screen_screenshot",
        description = "Capture one screen frame for VLM reasoning using Android MediaProjection after user approval.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        needsApproval = true,
        execute = { input ->
            trackScreenTool("screen_screenshot", "截取屏幕", input, runtime = "MediaProjection") {
                val result = screenCaptureManager.capture()
                textJson {
                    put("status", "captured")
                    put("path", result.file.absolutePath)
                    put("width", result.width)
                    put("height", result.height)
                    put("size_bytes", result.sizeBytes)
                    put("created_at", result.createdAt.toString())
                }
            }
        }
    )

    private val vlmTaskTool = Tool(
        name = "vlm_task",
        description = "Plan a phone automation task with VLM-style trace semantics. Stage 0 records intent and requires low-level tools for execution.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("goal", stringProp("Natural language phone automation goal."))
                },
                required = listOf("goal")
            )
        },
        needsApproval = true,
        execute = { input ->
            trackScreenTool("vlm_task", "规划 VLM 任务", input, runtime = "vlm-contract-stage0") {
                textJson {
                    put("status", "planned")
                    put("goal", input.requiredString("goal"))
                    put("message", "VLM orchestration contract is connected. Use screen_read_ui plus low-level screen actions until remote VLM screenshot reasoning is wired.")
                }
            }
        }
    )

    private suspend fun trackScreenTool(
        toolName: String,
        title: String,
        input: JsonElement,
        runtime: String = "Android Accessibility",
        block: suspend () -> List<UIMessagePart>,
    ): List<UIMessagePart> {
        val toolCallId = activityStore.startTool(
            toolName = toolName,
            title = title,
            inputPreview = input.activityInputPreview(toolName),
            runtime = runtime,
        )
        return try {
            val result = block()
            activityStore.complete(toolCallId, result.previewText())
            result
        } catch (error: Throwable) {
            activityStore.fail(toolCallId, error)
            throw error
        }
    }

    private fun requireService(): AmberAccessibilityService =
        AmberAccessibilityService.getActiveService()
            ?: error("AmberAgent Accessibility is not enabled. Open ${Settings.ACTION_ACCESSIBILITY_SETTINGS} and enable AmberAgent Accessibility.")

    private fun requireDisplayPoint(x: Float, y: Float, label: String): ScreenPoint {
        val metrics = context.resources.displayMetrics
        return requirePointInDisplayBounds(
            x = x,
            y = y,
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            label = label
        )
    }

    private fun coordinateSchema(xName: String, yName: String) = InputSchema.Obj(
        properties = buildJsonObject {
            put(xName, numberProp("X coordinate."))
            put(yName, numberProp("Y coordinate."))
        },
        required = listOf(xName, yName)
    )

    private fun result(success: Boolean) = textJson {
        put("success", success)
    }

    private suspend fun swipeByDirection(service: AmberAccessibilityService, direction: String): Boolean {
        val width = context.resources.displayMetrics.widthPixels.toFloat()
        val height = context.resources.displayMetrics.heightPixels.toFloat()
        return when (direction) {
            "up" -> service.swipe(width * 0.5f, height * 0.35f, width * 0.5f, height * 0.75f)
            "left" -> service.swipe(width * 0.75f, height * 0.5f, width * 0.25f, height * 0.5f)
            "right" -> service.swipe(width * 0.25f, height * 0.5f, width * 0.75f, height * 0.5f)
            else -> service.swipe(width * 0.5f, height * 0.75f, width * 0.5f, height * 0.35f)
        }
    }

    private fun me.rerere.rikkahub.data.automation.AccessibilityTextMatch.toJson(index: Int) = buildJsonObject {
        put("index", index)
        put("text", text)
        put("content_description", contentDescription)
        put("class_name", className)
        put("view_id", viewId.orEmpty())
        put("clickable", clickable)
        put("enabled", enabled)
        put("bounds", buildJsonObject {
            put("left", bounds.left)
            put("top", bounds.top)
            put("right", bounds.right)
            put("bottom", bounds.bottom)
            put("center_x", bounds.exactCenterX().toDouble())
            put("center_y", bounds.exactCenterY().toDouble())
        })
    }

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun numberProp(description: String) = buildJsonObject {
        put("type", "number")
        put("description", description)
    }

    private fun integerProp(description: String) = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    private fun List<UIMessagePart>.previewText(): String =
        joinToString("\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                else -> part.toString()
            }
        }.takeLast(1_600)

    private fun JsonElement.activityInputPreview(toolName: String): String =
        when (toolName) {
            "screen_input_text" -> buildJsonObject {
                put("text_chars", string("text")?.length ?: 0)
            }.toString()
            else -> toString()
        }
}

private fun JsonElement.requiredFloat(name: String): Float =
    jsonObject[name]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: error("$name is required")

internal data class ScreenPoint(val x: Float, val y: Float)

internal fun requirePointInDisplayBounds(
    x: Float,
    y: Float,
    width: Int,
    height: Int,
    label: String,
): ScreenPoint {
    require(x.isFinite() && y.isFinite()) {
        "$label point out of bounds: coordinates must be finite"
    }
    require(width > 0 && height > 0) {
        "$label point out of bounds: invalid display bounds ${width}x$height"
    }
    require(x >= 0f && x < width && y >= 0f && y < height) {
        "$label point out of bounds: ($x, $y) outside ${width}x$height"
    }
    return ScreenPoint(x, y)
}
