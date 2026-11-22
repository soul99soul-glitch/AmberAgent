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
import app.amber.feature.runtime.AccessibilityApprovalSummaryFormatter
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import kotlinx.serialization.json.JsonElement

@Composable
internal fun rememberAccessibilityApprovalState(
    tool: UIMessagePart.Tool,
    arguments: JsonElement?,
): Pair<String?, (@Composable () -> Unit)?> {
    val context = LocalContext.current
    val summary = remember(tool.toolName, tool.metadata, arguments) {
        AccessibilityApprovalSummaryFormatter.format(
            tool = tool,
            arguments = arguments,
            resolveAppLabel = { pkg ->
                runCatching {
                    val info = context.packageManager.getApplicationInfo(pkg, 0)
                    context.packageManager.getApplicationLabel(info).toString()
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
