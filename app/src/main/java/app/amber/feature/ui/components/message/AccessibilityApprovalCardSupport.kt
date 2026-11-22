package app.amber.feature.ui.components.message

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.ai.ui.UIMessagePart
import app.amber.core.automation.AmberAccessibilityService
import app.amber.feature.tools.isAccessibilityL3ActionTool
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Structured L3 approval preview for Android accessibility cross-app write actions.
 * Pure Kotlin model so JVM unit tests can assert target app, action type, and operation summary.
 */
data class AccessibilityApprovalSummary(
    val riskLevel: String = "L3",
    val targetApp: String,
    val actionType: String,
    val operationSummary: String,
    val notice: String = "L3 高风险无障碍代操作 · 本步执行前需您明确确认",
)

object AccessibilityApprovalSummaryFormatter {
    const val METADATA_TARGET_APP_KEY = "accessibility_target_app"

    fun format(
        tool: UIMessagePart.Tool,
        arguments: JsonElement?,
        activePackageFallback: String? = null,
        resolveAppLabel: (String) -> String? = { null },
    ): AccessibilityApprovalSummary? {
        if (!isAccessibilityL3ActionTool(tool.toolName)) return null
        val argsObj = arguments as? JsonObject
        val argPackage = argsObj?.stringField("package_name")
        val metadataPackage = tool.metadata?.stringField(METADATA_TARGET_APP_KEY)
        val rawPackage = argPackage ?: metadataPackage ?: activePackageFallback?.trim()?.takeIf { it.isNotEmpty() }
        val targetApp = if (rawPackage != null) {
            val label = runCatching { resolveAppLabel(rawPackage) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            if (label != null && label != rawPackage) "$label ($rawPackage)" else rawPackage
        } else {
            "当前前台应用"
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
        "screen_run_flow" -> "多步无障碍流程 (screen_run_flow)"
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
            val text = args?.stringField("text")?.truncatePreview(40) ?: ""
            if (text.isNotEmpty()) "在当前焦点输入框写入「$text」" else "向当前焦点输入框写入文本"
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
            val url = args?.stringField("url")?.truncatePreview(64) ?: "外部链接"
            "使用系统浏览器/应用打开 $url"
        }
        "screen_scroll_until" -> {
            val text = args?.stringField("text")?.truncatePreview(28) ?: ""
            val dir = args?.stringField("direction") ?: "down"
            val max = args?.numberField("max_swipes") ?: "8"
            "向 $dir 滚动查找「$text」（最多 $max 次）"
        }
        "screen_collect_list" -> {
            val container = args?.stringField("container")?.truncatePreview(28) ?: "列表"
            val dir = args?.stringField("direction") ?: "down"
            val max = args?.numberField("max_swipes") ?: "3"
            "在「$container」向 $dir 滚动读取（最多 $max 次）"
        }
        "screen_run_flow" -> {
            val steps = (args?.get("steps") as? JsonArray).orEmpty()
            val firstTarget = (steps.firstOrNull() as? JsonObject)?.stringField("target")?.truncatePreview(20)
            val firstHint = firstTarget?.let { "，首步目标「$it」" }.orEmpty()
            "执行 ${steps.size} 步固定操作$firstHint"
        }
        "screen_run_goal" -> {
            val goal = args?.stringField("goal")?.truncatePreview(48) ?: "无障碍目标任务"
            "执行目标：「$goal」"
        }
        "screen_add_app" -> {
            val pkg = args?.stringField("package_name") ?: "目标应用"
            "将 $pkg 加入无障碍允许列表"
        }
        else -> "执行外部无障碍操作"
    }

    private fun JsonObject.stringField(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.numberField(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun String.truncatePreview(maxChars: Int): String {
        val singleLine = replace('\n', ' ').replace('\r', ' ').trim()
        return if (singleLine.length <= maxChars) singleLine else singleLine.take(maxChars - 1) + "…"
    }
}

@Composable
internal fun rememberAccessibilityApprovalState(
    tool: UIMessagePart.Tool,
    arguments: JsonElement?,
): Pair<String?, (@Composable () -> Unit)?> {
    if (!isAccessibilityL3ActionTool(tool.toolName)) return null to null
    val context = LocalContext.current
    val summary = remember(tool.toolName, tool.metadata, arguments) {
        val livePkg = runCatching { AmberAccessibilityService.getActiveService()?.activePackageName() }.getOrNull()
        AccessibilityApprovalSummaryFormatter.format(
            tool = tool,
            arguments = arguments,
            activePackageFallback = livePkg,
            resolveAppLabel = { pkg ->
                runCatching {
                    val info = context.packageManager.getApplicationInfo(pkg, 0)
                    context.packageManager.getApplicationLabel(info)?.toString()
                }.getOrNull()
            },
        )
    } ?: return null to null

    val content: @Composable () -> Unit = {
        val tokens = LocalAmberTokens.current
        val type = LocalAmberType.current
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = tokens.surface,
            border = BorderStroke(1.dp, tokens.line),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "风险级别：${summary.riskLevel}（默认逐步确认）",
                    style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                    color = tokens.accent,
                )
                Text(
                    text = "目标应用：${summary.targetApp}",
                    style = type.secondary,
                    color = tokens.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "动作类型：${summary.actionType}",
                    style = type.secondary,
                    color = tokens.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "操作摘要：${summary.operationSummary}",
                    style = type.secondary,
                    color = tokens.ink2,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    return summary.notice to content
}
