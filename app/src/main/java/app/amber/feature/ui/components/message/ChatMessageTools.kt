package app.amber.feature.ui.components.message

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.FileText
import app.amber.agent.R
import app.amber.feature.ui.components.richtext.ZoomableAsyncImage
import app.amber.feature.ui.components.ui.ChainOfThoughtScope
import app.amber.feature.ui.components.ui.FaviconRow
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.modifier.shimmer
import app.amber.feature.ui.theme.LocalAmberTokens

@Composable
fun ChainOfThoughtScope.ChatMessageToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean = false,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onOpenWorkspaceFile: ((String) -> Unit)? = null,
    themeTryOnCardTarget: ThemeTryOnCardTarget? = null,
) {
    val isAskUser = tool.toolName == ToolNames.ASK_USER

    if (isAskUser) {
        AskUserToolStep(tool = tool, loading = loading, onToolAnswer = onToolAnswer)
        return
    }
    var showResult by remember { mutableStateOf(false) }
    var showDenyDialog by remember { mutableStateOf(false) }
    val isPending = tool.approvalState is ToolApprovalState.Pending
    val isDenied = tool.approvalState is ToolApprovalState.Denied
    val dedicatedThemeApproval = isPending && onToolApproval != null &&
        tool.matchesThemeTryOnCardTarget(themeTryOnCardTarget)
    val arguments = remember(tool.input) { MessageRenderCache.toolInputJson(tool.input) }
    val memoryAction = arguments.getStringContent("action")
    val content = remember(tool.isExecuted, tool.output) {
        if (tool.isExecuted) {
            MessageRenderCache.toolOutputJson(tool.output)
        } else {
            null
        }
    }
    val images = tool.output.filterIsInstance<UIMessagePart.Image>()
    val title = toolDisplayTitle(tool.toolName, arguments, memoryAction)
    val status = toolStatusFromMessagePart(tool = tool, loading = loading, content = content)
    val workspace = workspaceColors()
    val tokens = LocalAmberTokens.current

    val workspaceFilePath = remember(tool.toolName, status, content, arguments) {
        if (status != AgentToolStatus.SUCCEEDED) return@remember null
        if (tool.toolName !in setOf("file_write", "file_edit", "file_read")) return@remember null
        val candidate = content.getStringContent("path")
            ?: arguments.getStringContent("path")
        candidate?.takeIf { it.isNotBlank() && !it.startsWith("/") }
    }
    // generate_image renders its result as a dedicated full-width carousel
    // below the capsule, NOT as 64dp thumbnails inside the small extra-content
    // surface. Skip the regular extra-content path so we don't double-render
    // (capsule + tiny thumb + big carousel).
    val isGenerateImage = tool.toolName == "generate_image"
    val hasExtraContent = isDenied || (images.isNotEmpty() && !isGenerateImage)
    val toolKind = getToolKind(tool.toolName)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            // Settled history tool steps never change size; the spring only
            // pays off while input/output are still arriving.
            .then(
                if (loading) {
                    Modifier.animateContentSize(
                        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                    )
                } else {
                    Modifier
                },
            ),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (isPending && !dedicatedThemeApproval) {
            ChatToolApprovalCard(
                title = title,
                toolName = tool.toolName,
                kindLabel = toolKindLabel(toolKind, tool.toolName),
                icon = getToolIcon(tool.toolName, memoryAction),
                statusLabel = toolStatusLabel(status),
                detailsLabel = stringResource(R.string.setting_cron_tasks_view_details),
                denyLabel = stringResource(R.string.chat_message_tool_deny),
                approveLabel = stringResource(R.string.chat_message_tool_approve),
                onDetails = { showResult = true },
                onDeny = { showDenyDialog = true },
                onApprove = { onToolApproval?.invoke(tool.toolCallId, true, "") },
                actionsEnabled = onToolApproval != null,
                notice = jevAutoApprovalNotice(tool),
            )
        } else {
            AgentToolCallCapsule(
                title = title,
                toolName = tool.toolName,
                icon = getToolIcon(tool.toolName, memoryAction),
                kind = toolKind,
                status = status,
                loading = loading,
                onClick = { showResult = true },
                approvalActions = null,
            )
        }

        // generate_image tool: pre-allocate the result area while the tool
        // is running so we can show an animated dot-grid placeholder card
        // (same shape as the requested aspect ratio), then crossfade to the
        // real carousel once images arrive. Skip entirely on FAILED / DENIED
        // — the capsule itself surfaces those states.
        if (isGenerateImage &&
            status !in setOf(AgentToolStatus.FAILED, AgentToolStatus.TIMED_OUT, AgentToolStatus.INTERRUPTED) &&
            !isDenied
        ) {
            val aspectRatioFloat = remember(arguments) {
                parseAspectRatioFloat(
                    arguments.jsonObject["aspect_ratio"]?.jsonPrimitive?.contentOrNull
                )
            }
            val requestedCount = remember(arguments) {
                arguments.jsonObject["count"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 4) ?: 1
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, top = 4.dp),
            ) {
                AnimatedContent(
                    targetState = images.isNotEmpty(),
                    transitionSpec = {
                        fadeIn(animationSpec = tween(durationMillis = 420)) togetherWith
                            fadeOut(animationSpec = tween(durationMillis = 260))
                    },
                    label = "imageGenSwap",
                ) { hasImages ->
                    if (hasImages) {
                        GeneratedImageCarousel(images = images)
                    } else {
                        // Match the post-load carousel width so the
                        // crossfade doesn't visibly resize:
                        //   - count == 1 → carousel renders single full-width
                        //     card, so placeholder also full-width.
                        //   - count >  1 → carousel uses 0.85f per card in a
                        //     horizontal scroller, so placeholder matches.
                        val widthFraction = if (requestedCount > 1) 0.85f else 1f
                        GeneratedImagePlaceholder(
                            aspectRatio = aspectRatioFloat,
                            modifier = Modifier.fillMaxWidth(widthFraction),
                        )
                    }
                }
            }
        }

        if (workspaceFilePath != null && onOpenWorkspaceFile != null) {
            Surface(
                modifier = Modifier
                    .padding(start = 32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenWorkspaceFile(workspaceFilePath) },
                shape = RoundedCornerShape(8.dp),
                color = workspace.row,
                contentColor = workspace.ink,
                border = BorderStroke(1.dp, workspace.hairline),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Lucide.FileText,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = workspace.muted,
                    )
                    Text(
                        text = workspaceFilePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = workspace.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = stringResource(R.string.chat_message_tool_preview_workspace_file),
                        style = MaterialTheme.typography.labelSmall,
                        color = workspace.faint,
                    )
                }
            }
        }

        if (hasExtraContent) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = tokens.surface,
                contentColor = tokens.ink2,
                border = BorderStroke(1.dp, tokens.line),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (tool.toolName == ToolNames.MEMORY &&
                        memoryAction in listOf(MemoryActions.CREATE, MemoryActions.EDIT)
                    ) {
                        content.getStringContent("content")?.let { memoryContent ->
                            Text(
                                text = memoryContent,
                                style = MaterialTheme.typography.labelSmall,
                                color = workspace.muted,
                                modifier = Modifier.shimmer(isLoading = loading),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (tool.toolName == ToolNames.SEARCH_WEB) {
                        content.getStringContent("answer")?.let { answer ->
                            Text(
                                text = answer,
                                style = MaterialTheme.typography.labelSmall,
                                color = workspace.muted,
                                modifier = Modifier.shimmer(isLoading = loading),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val items = content?.jsonObject?.get("items")?.jsonArray ?: emptyList()
                        if (items.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                FaviconRow(
                                    urls = items.mapNotNull { it.getStringContent("url") },
                                    size = 18.dp,
                                )
                                Text(
                                    text = stringResource(R.string.chat_message_tool_search_results_count, items.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = workspace.faint,
                                )
                            }
                        }
                    }
                    if (tool.toolName == ToolNames.SCRAPE_WEB) {
                        val url = arguments.getStringContent("url") ?: ""
                        Text(
                            text = url,
                            style = MaterialTheme.typography.labelSmall,
                            color = workspace.faint,
                        )
                    }
                    if (images.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.wrapContentWidth(),
                        ) {
                            items(images) { image ->
                                ZoomableAsyncImage(
                                    model = image.url,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .height(64.dp)
                                        .wrapContentWidth(),
                                )
                            }
                        }
                    }
                    if (isDenied) {
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        Text(
                            text = if (reason.isNotBlank()) {
                                stringResource(R.string.chat_message_tool_denied_with_reason, reason)
                            } else {
                                stringResource(R.string.chat_message_tool_denied)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = workspace.red,
                        )
                    }
                }
            }
        }
    }

    if (showDenyDialog && onToolApproval != null) {
        ToolDenyReasonDialog(
            onDismiss = { showDenyDialog = false },
            onConfirm = { reason ->
                showDenyDialog = false
                onToolApproval(tool.toolCallId, false, reason)
            }
        )
    }

    if (showResult) {
        ToolCallPreviewSheet(
            toolName = tool.toolName,
            arguments = arguments,
            content = content,
            output = tool.output,
            onDismissRequest = { showResult = false }
        )
    }
}

/** Jev 复核把自动批准收紧为人工审批时，审批卡上说明命中的风险类别。 */
@Composable
private fun jevAutoApprovalNotice(tool: UIMessagePart.Tool): String? {
    val keys = (tool.metadata?.get(app.amber.core.jev.JevAutoApprovalGate.METADATA_KEY) as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    val labels = keys.mapNotNull { key ->
        when (app.amber.core.jev.JevAutoApprovalGate.Risk.fromKey(key)) {
            app.amber.core.jev.JevAutoApprovalGate.Risk.DESTRUCTIVE -> stringResource(R.string.chat_tool_jev_risk_destructive)
            app.amber.core.jev.JevAutoApprovalGate.Risk.EXFILTRATION -> stringResource(R.string.chat_tool_jev_risk_exfiltration)
            app.amber.core.jev.JevAutoApprovalGate.Risk.OFF_TASK -> stringResource(R.string.chat_tool_jev_risk_off_task)
            null -> null
        }
    }
    return stringResource(R.string.chat_tool_jev_auto_approval_paused, labels.joinToString(stringResource(R.string.chat_tool_jev_risk_separator)))
}
