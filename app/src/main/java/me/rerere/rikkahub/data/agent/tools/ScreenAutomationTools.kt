package me.rerere.rikkahub.data.agent.tools

import android.content.Context
import android.content.Intent
import android.provider.Settings
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
        screenReadUiTool,
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
                val ok = service.tap(input.requiredFloat("x"), input.requiredFloat("y"))
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
                val ok = service.longPress(
                    x = input.requiredFloat("x"),
                    y = input.requiredFloat("y"),
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
                val ok = service.swipe(
                    fromX = input.requiredFloat("from_x"),
                    fromY = input.requiredFloat("from_y"),
                    toX = input.requiredFloat("to_x"),
                    toY = input.requiredFloat("to_y"),
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
