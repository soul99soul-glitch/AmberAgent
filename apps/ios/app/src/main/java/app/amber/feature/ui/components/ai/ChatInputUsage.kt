package app.amber.feature.ui.components.ai

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.coerceToReasoningOptions
import app.amber.ai.provider.labelFor
import app.amber.ai.provider.providerRoutingKey
import app.amber.ai.provider.reasoningOptions
import app.amber.ai.provider.providers.openai.OpenAICodexUsageStatus
import app.amber.ai.provider.providers.openai.OpenAICodexUsageWindow
import app.amber.ai.registry.ModelRegistry
import app.amber.agent.R
import app.amber.core.context.CompactLifecycleState
import app.amber.core.context.CompactLifecycleStatus
import app.amber.core.context.ContextFootprintEstimator
import app.amber.core.context.ConversationCompact
import app.amber.core.model.Conversation
import app.amber.core.usage.ProviderUsageMetric
import app.amber.core.usage.ProviderUsageStatus
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.core.utils.formatNumber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Composer-row indicators for context budget, provider usage quotas, and the
 * reasoning-level chip. Extracted from the ChatInput god class so the main
 * composable stays focused on layout + state wiring.
 *
 * Only the three composables called from `ChatInput()` and the two
 * `toComposerUsageStatus` mappers / `providerRoutingKey` extension are
 * `internal`; everything else is file-private.
 */

@Composable
internal fun ContextUsageIndicator(
    conversation: Conversation,
    contextCompacts: List<ConversationCompact>,
    compactLifecycleState: CompactLifecycleState = CompactLifecycleState.idle(),
    model: Model?,
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    val currentMessages = remember(conversation.messageNodes) {
        conversation.currentMessages
    }
    val contextFingerprint = remember(currentMessages) {
        ContextFootprintEstimator.inputFingerprint(currentMessages)
    }
    // 2026-05-15: estimator now consumes active compacts so the ring reflects
    // the POST-substitution footprint (summary + recent messages), not the
    // raw timeline. Without this, every successful compaction would leave the
    // ring stuck at the pre-compact reading and the indicator would erode
    // user trust (training them to ignore a permanently-red ring).
    // Fingerprint must also depend on compact identity so a new compact (or
    // an invalidation) re-runs the estimator. Combining via simple xor — both
    // values are 64-bit hashes already, collision risk is negligible at the
    // scale of a chat history.
    val compactsFingerprint = remember(contextCompacts) {
        contextCompacts.fold(0L) { acc, c -> (acc * 31) xor c.id.hashCode().toLong() xor c.tokenEstimate.toLong() }
    }
    val estimatedTokens = remember(contextFingerprint, compactsFingerprint) {
        ContextFootprintEstimator.estimateConversationInputTokens(conversation, contextCompacts)
    }
    val usedTokens = estimatedTokens
    val contextWindow = remember(model?.modelId, model?.contextWindowTokens) {
        // Prefer registry (kept up-to-date) over persisted model value (may be stale
        // from an old fetch). Fall back to persisted value for unknown/custom models.
        model?.modelId?.let { ModelRegistry.MODEL_CONTEXT_WINDOW.getData(it) }
            ?: model?.contextWindowTokens
    }
    val ratio = if (contextWindow != null && contextWindow > 0) {
        (usedTokens.toFloat() / contextWindow.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val compactActive = compactLifecycleState.status == CompactLifecycleStatus.PLANNING ||
        compactLifecycleState.status == CompactLifecycleStatus.COMPACTING
    val ringColor = when {
        compactActive -> workspace.blue
        ratio >= 0.7f -> workspace.red
        ratio >= 0.5f -> workspace.amber
        else -> workspace.muted.copy(alpha = 0.56f)
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(modifier = Modifier.size(10.dp)) {
            val stroke = 1.6.dp.toPx()
            val radius = (size.minDimension - stroke) / 2f
            drawCircle(
                color = workspace.faint.copy(alpha = 0.24f),
                radius = radius,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * ratio,
                useCenter = false,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            text = if (compactActive) {
                stringResource(R.string.chat_context_auto_compacting)
            } else {
                "Context ${usedTokens.formatContextTokens()} / ${contextWindow?.formatNumber() ?: "--"}"
            },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = workspace.muted.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

private fun Int.formatContextTokens(): String = when {
    this <= 0 -> "0"
    this < 1_000 -> "<1K"
    else -> formatNumber()
}

internal data class ComposerUsageStatus(
    val title: String? = null,
    val planType: String? = null,
    val metrics: List<Pair<String, ComposerUsageMetric>> = emptyList(),
    val fiveHourQuota: ComposerUsageMetric? = null,
    val weeklyQuota: ComposerUsageMetric? = null,
    val cacheHitRate: ComposerUsageMetric? = null,
) {
    val hasData: Boolean
        get() = metrics.isNotEmpty() || fiveHourQuota != null || weeklyQuota != null || cacheHitRate != null
}

internal data class ComposerUsageMetric(
    val percent: Int? = null,
    val detail: String? = null,
)

internal fun OpenAICodexUsageStatus.toComposerUsageStatus(context: Context): ComposerUsageStatus {
    return ComposerUsageStatus(
        title = context.getString(R.string.chat_input_usage_sheet_title),
        planType = planType,
        fiveHourQuota = fiveHour?.toComposerUsageMetric(context),
        weeklyQuota = weekly?.toComposerUsageMetric(context),
    )
}

internal fun ProviderUsageStatus.toComposerUsageStatus(): ComposerUsageStatus {
    return ComposerUsageStatus(
        title = title,
        planType = planType,
        metrics = metrics.map { metric ->
            metric.label to metric.toComposerUsageMetric()
        },
    )
}

private fun ProviderUsageMetric.toComposerUsageMetric(): ComposerUsageMetric {
    return ComposerUsageMetric(
        percent = percent,
        detail = detail,
    )
}

private fun OpenAICodexUsageWindow.toComposerUsageMetric(context: Context): ComposerUsageMetric {
    return ComposerUsageMetric(
        percent = usedPercent.toInt(),
        detail = resetsAtEpochSeconds?.formatUsageResetDetail(context),
    )
}

@Composable
internal fun ComposerUsageSheet(
    status: ComposerUsageStatus,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val workspace = workspaceColors()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = status.title ?: stringResource(R.string.chat_input_usage_sheet_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = workspace.ink,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onRefresh,
                    enabled = !loading,
                ) {
                    Text(stringResource(R.string.chat_input_usage_refresh))
                }
            }
            status.planType?.let {
                Text(
                    text = stringResource(R.string.chat_input_usage_plan, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.muted,
                )
            }
            if (loading && !status.hasData) {
                Text(
                    text = stringResource(R.string.chat_input_usage_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = workspace.muted,
                )
            } else if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (!status.hasData) {
                Text(
                    text = stringResource(R.string.chat_input_usage_no_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = workspace.muted,
                )
            } else {
                if (status.metrics.isNotEmpty()) {
                    status.metrics.forEach { (label, metric) ->
                        UsageMetricRow(label = label, metric = metric)
                    }
                } else {
                    status.fiveHourQuota?.let { UsageMetricRow(label = "5h", metric = it) }
                    status.weeklyQuota?.let { UsageMetricRow(label = "weekly", metric = it) }
                    status.cacheHitRate?.let { UsageMetricRow(label = "cache", metric = it) }
                }
            }
        }
    }
}

@Composable
private fun UsageMetricRow(
    label: String,
    metric: ComposerUsageMetric,
) {
    val workspace = workspaceColors()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = workspace.muted,
        )
        Text(
            text = listOfNotNull(
                metric.percent?.let { stringResource(R.string.chat_input_usage_percent_used, it) },
                metric.detail,
            ).joinToString("  ").ifBlank { "--" },
            style = MaterialTheme.typography.bodyMedium,
            color = workspace.ink,
        )
    }
}

private fun Long.formatUsageResetDetail(context: Context): String {
    val resetMillis = if (this < 10_000_000_000L) this * 1000L else this
    val remainingMillis = (resetMillis - System.currentTimeMillis()).coerceAtLeast(0L)
    val totalMinutes = remainingMillis / 60_000L
    val timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(resetMillis))
    val relative = when {
        totalMinutes >= 24 * 60 -> "${totalMinutes / (24 * 60)}d"
        totalMinutes >= 60 -> "${totalMinutes / 60}h ${totalMinutes % 60}m"
        totalMinutes > 0 -> "${totalMinutes}m"
        else -> "now"
    }
    return context.getString(R.string.chat_input_usage_reset_detail, timeText, relative)
}
