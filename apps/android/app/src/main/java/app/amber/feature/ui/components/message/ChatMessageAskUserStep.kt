package app.amber.feature.ui.components.message

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Tick01
import app.amber.agent.R
import app.amber.feature.ui.components.ui.ChainOfThoughtScope
import app.amber.feature.ui.components.ui.DotLoading
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.modifier.shimmer
import app.amber.core.utils.JsonInstant
import app.amber.core.utils.jsonPrimitiveOrNull

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChainOfThoughtScope.AskUserToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
) {
    val isPending = tool.approvalState is ToolApprovalState.Pending
    val isAnswered = tool.approvalState is ToolApprovalState.Answered
    val arguments = remember(tool.input) { tool.inputAsJson() }
    val answeredAnswers = remember(tool.approvalState) {
        val state = tool.approvalState
        if (state is ToolApprovalState.Answered) {
            runCatching {
                JsonInstant.parseToJsonElement(state.answer)
                    .jsonObject["answers"]?.jsonObject
            }.getOrNull()
        } else {
            null
        }
    }

    // Parse questions from arguments
    val questions = remember(arguments) {
        runCatching {
            arguments.jsonObject["questions"]?.jsonArray?.map { q ->
                val obj = q.jsonObject
                AskUserQuestion(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    question = obj["question"]?.jsonPrimitive?.contentOrNull ?: "",
                    options = obj["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    selectionType = obj["selection_type"]?.jsonPrimitive?.contentOrNull ?: "text"
                )
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    // Track answers for text/single questions
    val answers = remember { mutableStateMapOf<String, String>() }
    // Track selected options for multi questions
    val multiAnswers = remember { mutableStateMapOf<String, Set<String>>() }

    val firstQuestion = questions.firstOrNull()?.question ?: "..."

    var expanded by remember { mutableStateOf(true) }
    val workspace = workspaceColors()

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(size = 10.dp)
            } else {
                // §6.2 AskUser card: accent "?" indicator, flat & hairline (no glow).
                Icon(
                    imageVector = HugeIcons.BubbleChatQuestion,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = LocalAmberTokens.current.accent,
                )
            }
        },
        label = {
            // §6.2 heading "询问 N 个问题" — human text → sans, accent-colored, weight 600.
            Text(
                text = if (questions.size <= 1) firstQuestion else stringResource(
                    R.string.chat_message_tool_ask_questions,
                    questions.size
                ),
                style = LocalAmberType.current.body.copy(fontWeight = FontWeight.SemiBold),
                color = LocalAmberTokens.current.accent,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                questions.forEachIndexed { index, q ->
                    if (index > 0) {
                        // §4 hairline divider between question blocks.
                        HorizontalDivider(
                            color = LocalAmberTokens.current.line,
                            thickness = 1.dp,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Question text is human-readable → sans body, ink.
                        Text(
                            text = q.question,
                            style = LocalAmberType.current.body,
                            color = LocalAmberTokens.current.ink,
                        )

                        if (isPending && onToolAnswer != null) {
                            when (q.selectionType) {
                                "single" -> {
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                AskOptionChip(
                                                    selected = answers[q.id] == option,
                                                    label = option,
                                                    onClick = { answers[q.id] = option },
                                                )
                                            }
                                        }
                                    }
                                }
                                "multi" -> {
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                val selectedSet = multiAnswers[q.id] ?: emptySet()
                                                AskOptionChip(
                                                    selected = selectedSet.contains(option),
                                                    label = option,
                                                    onClick = {
                                                        val current = selectedSet.toMutableSet()
                                                        if (current.contains(option)) current.remove(option)
                                                        else current.add(option)
                                                        multiAnswers[q.id] = current
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                else -> {
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                AskOptionChip(
                                                    selected = answers[q.id] == option,
                                                    label = option,
                                                    onClick = { answers[q.id] = option },
                                                )
                                            }
                                        }
                                    }
                                    // §6.1 .field: surface fill + hairline; focus lifts border to accent.
                                    val fieldTokens = LocalAmberTokens.current
                                    OutlinedTextField(
                                        value = answers[q.id] ?: "",
                                        onValueChange = { answers[q.id] = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = LocalAmberType.current.body,
                                        placeholder = {
                                            Text(
                                                text = "在此输入回答…",
                                                style = LocalAmberType.current.body,
                                                color = fieldTokens.ink4,
                                            )
                                        },
                                        singleLine = false,
                                        minLines = 2,
                                        maxLines = 6,
                                        shape = RoundedCornerShape(14.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            unfocusedBorderColor = fieldTokens.line,
                                            focusedBorderColor = fieldTokens.accent,
                                            unfocusedContainerColor = fieldTokens.surface,
                                            focusedContainerColor = fieldTokens.raised,
                                        ),
                                    )
                                }
                            }
                        } else if (isAnswered) {
                            val answeredState = tool.approvalState as ToolApprovalState.Answered
                            val answerJson = answeredAnswers?.get(q.id)
                            val answerText = when {
                                answerJson is JsonArray -> answerJson.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
                                    .joinToString(" · ")
                                answerJson != null -> answerJson.jsonPrimitiveOrNull?.contentOrNull
                                    ?: answeredState.answer
                                else -> answeredState.answer
                            }
                            // Answered state: flat surface-2 inset + hairline (§4, §7).
                            val answeredTokens = LocalAmberTokens.current
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = answeredTokens.surface2,
                                border = BorderStroke(1.dp, answeredTokens.line),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(5.dp),
                                        // Faint accent inset derived from the accent token (§7.2).
                                        color = answeredTokens.accent.copy(alpha = 0.12f),
                                    ) {
                                        // "A" is a fixed marker glyph → mono (machine-fact), accent.
                                        Text(
                                            text = "A",
                                            style = LocalAmberType.current.eyebrow,
                                            color = answeredTokens.accent,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                    // Answer is human text → sans body, ink.
                                    Text(
                                        text = answerText,
                                        style = LocalAmberType.current.body,
                                        color = answeredTokens.ink,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }

                if (isPending && onToolAnswer != null) {
                    val actionTokens = LocalAmberTokens.current
                    val anyAnswered = answers.values.any { it.isNotBlank() } ||
                        multiAnswers.values.any { it.isNotEmpty() }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (anyAnswered) {
                            // Secondary "清空" affordance: faint ink, flat.
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .clickable {
                                        answers.clear()
                                        multiAnswers.clear()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Refresh01,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = actionTokens.ink3,
                                )
                                Text(
                                    text = "清空",
                                    style = LocalAmberType.current.secondary,
                                    color = actionTokens.ink3,
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Button(
                            onClick = {
                                val answerPayload = buildJsonObject {
                                    put("answers", buildJsonObject {
                                        questions.forEach { q ->
                                            when (q.selectionType) {
                                                "multi" -> put(
                                                    q.id,
                                                    buildJsonArray {
                                                        multiAnswers[q.id].orEmpty().forEach { add(JsonPrimitive(it)) }
                                                    }
                                                )
                                                else -> put(q.id, JsonPrimitive(answers[q.id] ?: ""))
                                            }
                                        }
                                    })
                                }
                                onToolAnswer(tool.toolCallId, answerPayload.toString())
                            },
                            enabled = questions.all { q ->
                                when (q.selectionType) {
                                    "multi" -> !multiAnswers[q.id].isNullOrEmpty()
                                    else -> !answers[q.id].isNullOrBlank()
                                }
                            },
                            // Primary action → §6.1 .btn-accent: accent fill + accent-ink, pill.
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = actionTokens.accent,
                                contentColor = actionTokens.accentInk,
                            ),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Tick01,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = stringResource(R.string.chat_message_tool_submit),
                                style = LocalAmberType.current.body.copy(fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun AskOptionChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    // §6.2 AskUser pill: surface-2 fill + 1dp hairline + fully-round; selected = accent.
    // Flat & hairline first (§7): no shadow. Option label is human text → sans.
    val tokens = LocalAmberTokens.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (selected) tokens.accent else tokens.surface2,
        contentColor = if (selected) tokens.accentInk else tokens.ink2,
        border = if (selected) null else BorderStroke(width = 1.dp, color = tokens.line),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (selected) {
                Icon(
                    imageVector = HugeIcons.Tick01,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(
                text = label,
                style = LocalAmberType.current.secondary.copy(
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                ),
            )
        }
    }
}

private data class AskUserQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val selectionType: String = "text", // "text" | "single" | "multi"
)

@Composable
internal fun ToolDenyReasonDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.chat_message_tool_deny_dialog_title))
        },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(stringResource(R.string.chat_message_tool_deny_dialog_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                maxLines = 4
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason) }) {
                Text(stringResource(R.string.chat_message_tool_deny))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
