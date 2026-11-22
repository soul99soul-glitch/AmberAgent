package app.amber.feature.ui.components.message

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
        val scheme = MaterialTheme.colorScheme
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
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = scheme.errorContainer,
                    contentColor = scheme.onErrorContainer,
                    border = BorderStroke(1.dp, scheme.error.copy(alpha = 0.30f)),
                ) {
                    Text(
                        text = "风险级别：${summary.riskLevel}（默认逐步确认）",
                        style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onErrorContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                AccessibilityApprovalFieldRow(
                    label = "目标应用",
                    value = summary.targetApp,
                    maxLines = 2,
                )
                AccessibilityApprovalFieldRow(
                    label = "动作类型",
                    value = summary.actionType,
                    maxLines = 2,
                )
                AccessibilityApprovalFieldRow(
                    label = "操作摘要",
                    value = summary.operationSummary,
                    maxLines = 3,
                    secondary = true,
                )
            }
        }
    }
    return summary.notice to content
}

@Composable
private fun AccessibilityApprovalFieldRow(
    label: String,
    value: String,
    maxLines: Int,
    secondary: Boolean = false,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = type.meta.copy(fontWeight = FontWeight.Medium),
            color = tokens.ink2,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 56.dp),
        )
        Text(
            text = value,
            style = type.secondary,
            color = if (secondary) tokens.ink2 else tokens.ink,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
