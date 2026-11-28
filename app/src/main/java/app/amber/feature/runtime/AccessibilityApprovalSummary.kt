package app.amber.feature.runtime

import app.amber.ai.ui.UIMessagePart
import app.amber.core.automation.SensitiveScreenTextClassifier
import app.amber.feature.tools.isAccessibilityL3ActionTool
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Structured L3 approval preview for Android accessibility cross-app write actions.
 * Pure Kotlin model so JVM unit tests can assert target app, action type, and operation summary.
 */
@kotlinx.serialization.Serializable
data class AccessibilityApprovalSummary(
    val riskLevel: String = "L3",
    val targetApp: String,
    val actionType: String,
    val operationSummary: String,
    val notice: String = "L3 高风险无障碍代操作 · 本步执行前需您明确确认",
)

object AccessibilityApprovalSummaryFormatter {
    const val METADATA_TARGET_APP_KEY = SCREEN_TARGET_PACKAGE

    fun format(
        tool: UIMessagePart.Tool,
        arguments: JsonElement?,
        resolveAppLabel: (String) -> String? = { null },
    ): AccessibilityApprovalSummary? {
        if (!isAccessibilityL3ActionTool(tool.toolName)) {
            val saved = tool.pendingRecipeApprovalStep()?.get("screen_approval_summary") ?: return null
            return runCatching { Json.decodeFromJsonElement(AccessibilityApprovalSummary.serializer(), saved) }.getOrNull()
        }
        val argsObj = arguments as? JsonObject
        val rawPackage = when (tool.toolName) {
            "screen_open_app", "screen_add_app", "screen_run_flow", "screen_run_goal" -> argsObj?.stringField("package_name")
            "screen_open_url", "screen_home" -> null
            else -> tool.metadata?.stringField(METADATA_TARGET_APP_KEY)
        }
        val targetApp = if (rawPackage != null) {
            val label = runCatching { resolveAppLabel(rawPackage) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            if (label != null && label != rawPackage) "$label ($rawPackage)" else rawPackage
        } else {
            when (tool.toolName) {
                "screen_home" -> "系统桌面"
                "screen_open_url" -> "系统选定的链接处理应用"
                else -> "未识别目标应用"
            }
        }
        val actionType = actionTypeLabel(tool.toolName)
        val summary = buildOperationSummary(tool.toolName, argsObj)
        return AccessibilityApprovalSummary(
            riskLevel = "L3",
            targetApp = targetApp,
            actionType = actionType,
            operationSummary = summary,
        )
    }

    private fun actionTypeLabel(toolName: String): String = when (toolName) {
        "screen_click" -> "点击坐标 (screen_click)"
        "screen_long_click" -> "长按坐标 (screen_long_click)"
        "screen_swipe" -> "滑动手势 (screen_swipe)"
        "screen_input_text" -> "输入文字 (screen_input_text)"
        "screen_tap_text" -> "点击文字控件 (screen_tap_text)"
        "screen_back" -> "系统返回 (screen_back)"
        "screen_home" -> "回到桌面 (screen_home)"
        "screen_open_app" -> "打开外部应用 (screen_open_app)"
        "screen_open_url" -> "打开外部链接 (screen_open_url)"
        "screen_scroll_until" -> "滚动查找 (screen_scroll_until)"
        "screen_collect_list" -> "滚动采集列表 (screen_collect_list)"
        "screen_run_flow" -> "固定无障碍操作 (screen_run_flow)"
        "screen_run_goal" -> "目标无障碍执行 (screen_run_goal)"
        "screen_add_app" -> "授权目标应用 (screen_add_app)"
        else -> toolName
    }

    private fun buildOperationSummary(toolName: String, args: JsonObject?): String = when (toolName) {
        "screen_click" -> {
            val x = args?.numberField("x") ?: "?"
            val y = args?.numberField("y") ?: "?"
            val verify = args?.stringField("verify_text")?.let { "，校验「${it.truncatePreview(24)}」" }.orEmpty()
            "点击坐标 ($x, $y)$verify"
        }
        "screen_long_click" -> {
            val x = args?.numberField("x") ?: "?"
            val y = args?.numberField("y") ?: "?"
            val duration = args?.numberField("duration_ms")?.let { "，时长 ${it}ms" }.orEmpty()
            "长按坐标 ($x, $y)$duration"
        }
        "screen_swipe" -> {
            val fx = args?.numberField("from_x") ?: "?"
            val fy = args?.numberField("from_y") ?: "?"
            val tx = args?.numberField("to_x") ?: "?"
            val ty = args?.numberField("to_y") ?: "?"
            "从 ($fx, $fy) 滑动到 ($tx, $ty)"
        }
        "screen_input_text" -> {
            val length = (args?.get("text") as? JsonPrimitive)?.contentOrNull?.length
            if (length != null) "在当前焦点输入框写入文本（${length} 个字符）" else "向当前焦点输入框写入文本"
        }
        "screen_tap_text" -> {
            val text = args?.stringField("text")?.truncatePreview(32) ?: ""
            if (text.isNotEmpty()) "点击包含文字「$text」的控件" else "点击匹配文字的控件"
        }
        "screen_back" -> "执行系统返回键操作"
        "screen_home" -> "返回系统桌面"
        "screen_open_app" -> {
            val pkg = args?.stringField("package_name") ?: "目标应用"
            "启动应用 $pkg"
        }
        "screen_open_url" -> {
            val host = args?.stringField("url")?.let(::extractUrlHostForDisplay)
            host?.let { "打开来自 $it 的链接" } ?: "打开外部链接"
        }
        "screen_scroll_until" -> {
            val text = args?.stringField("text")?.truncatePreview(28) ?: ""
            val dir = args?.stringField("direction") ?: "down"
            "向 $dir 滚动查找「$text」（最多一次）"
        }
        "screen_collect_list" -> {
            val container = args?.stringField("container")?.truncatePreview(28) ?: "列表"
            val dir = args?.stringField("direction") ?: "down"
            val max = args?.numberField("max_swipes")?.toIntOrNull()?.coerceIn(0, 1) ?: 1
            "在「$container」向 $dir 滚动读取（最多 $max 次）"
        }
        "screen_run_flow" -> {
            val steps = (args?.get("steps") as? JsonArray).orEmpty()
            val step = steps.singleOrNull() as? JsonObject
            val target = step?.stringField("target")?.truncatePreview(24).orEmpty()
            when (step?.stringField("operation")) {
                "click" -> "点击「$target」"
                "type" -> "向「$target」写入文本（${(step["text"] as? JsonPrimitive)?.contentOrNull?.length ?: 0} 个字符）"
                else -> "每次审批仅支持一个明确操作"
            }
        }
        "screen_run_goal" -> {
            val goal = args?.stringField("goal")?.truncatePreview(48) ?: "无障碍目标任务"
            if (args?.stringField("dry_run") == "true") "只读检查目标：「$goal」"
            else "打开目标应用并判断下一步：「$goal」"
        }
        "screen_add_app" -> {
            val pkg = args?.stringField("package_name") ?: "目标应用"
            "将 $pkg 加入无障碍允许列表，并允许本轮对该应用执行浏览目标"
        }
        else -> "执行外部无障碍操作"
    }

    fun extractUrlHostForDisplay(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null
        val direct = runCatching { URI(trimmed).host }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        if (direct != null) return direct
        // Strip query/fragment/whitespace only to recover host for malformed URLs without ever leaking path/query.
        val authorityCandidate = trimmed.substringBefore('?').substringBefore('#').substringBefore(' ')
        return runCatching { URI(authorityCandidate).host }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.numberField(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.toDoubleOrNull()?.isFinite() == true }

    private fun String.truncatePreview(maxChars: Int): String {
        val singleLine = SensitiveScreenTextClassifier.redactForDisplay(this).replace('\n', ' ').replace('\r', ' ').trim()
        return if (singleLine.length <= maxChars) singleLine else singleLine.take(maxChars - 1) + "…"
    }
}
