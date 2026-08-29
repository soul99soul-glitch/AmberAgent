package app.amber.core.ai

import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.chat.api.ChatEventPayload
import app.amber.feature.runtime.sha256Hex
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** Same 8000-char budget as AgentCapabilitySnapshotBuilder's prompt cap. */
private const val REQUEST_SNAPSHOT_PREVIEW_CAP = 8_000

/** Metadata key ChatGenerationRoundEngine stamps on assembled system blocks. */
private const val SYSTEM_PROMPT_BLOCK_METADATA_KEY = "system_prompt_block"

/**
 * Step 5 — builds the durable per-wire-request snapshot from the FINAL
 * fitted state: the exact message list about to cross the provider boundary,
 * the params' exposed tool catalog, the assembled system-block metadata and
 * the model/provider identity. Pure; callers still wrap emission in
 * runCatching so an audit failure can never break generation.
 *
 * Budget note (audit guardrail): the digest is computed over ONE
 * serialization of fit.messages; the preview is a bounded StringBuilder that
 * stops appending at the cap — no O(conversation) waste beyond reading the
 * parts once.
 */
internal fun buildRequestSnapshot(
    json: Json,
    stepIndex: Int,
    attempt: Int,
    kind: String,
    model: Model,
    providerSettingId: String,
    fitMessages: List<UIMessage>,
    tools: List<Tool>,
    systemParts: List<UIMessagePart>,
    estimatedTokens: Int?,
): ChatEventPayload.RequestSnapshot {
    // One serialization pass of the exact wire list; the digest binds it.
    val serialized = runCatching {
        json.encodeToString(ListSerializer(UIMessage.serializer()), fitMessages)
    }.getOrElse { error ->
        // Encoding failed (never expected — UIMessage is @Serializable): fall
        // back to a text-only rendering so the digest still separates
        // requests instead of vanishing. The runCatching at the commit site
        // would otherwise drop the whole snapshot.
        android.util.Log.w(
            "RequestSnapshotFactory",
            "fit.messages serialization failed; digesting text fallback",
            error,
        )
        fitMessages.joinToString("\n") { message ->
            message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        }
    }
    val preview = renderRequestPreview(fitMessages, REQUEST_SNAPSHOT_PREVIEW_CAP)
    return ChatEventPayload.RequestSnapshot(
        stepIndex = stepIndex,
        attempt = attempt,
        kind = kind,
        modelId = model.id.toString(),
        providerSettingId = providerSettingId,
        messageCount = fitMessages.size,
        messagesDigest = sha256Hex(serialized),
        systemBlockTags = systemParts.mapNotNull { part ->
            (part.metadata?.get(SYSTEM_PROMPT_BLOCK_METADATA_KEY) as? JsonPrimitive)?.content
        },
        toolCatalogDigest = sha256Hex(tools.map { it.name }.sorted().joinToString("\n")),
        exposedToolCount = tools.size,
        estimatedTokens = estimatedTokens,
        renderedPreview = preview.text,
        truncated = preview.truncated,
    )
}

private class RenderedPreview(val text: String, val truncated: Boolean)

/**
 * Capped role+text rendering of the fitted request. Media payloads are never
 * inlined — each UIMessagePart subtype renders as a bounded placeholder
 * (`<image>`, `<video>`, `<audio>`, `<document:fileName>`, `<mini_app:appId>`,
 * `<reasoning:chars>`) so the preview cannot lie by omission: every part type
 * that crossed the wire leaves a visible marker. Tool calls render as
 * `<tool:name>` plus `<tool_result:chars>` — a character count for the
 * output, never its content. The StringBuilder stops at the cap — a single
 * huge part cannot blow past it. `truncated` keeps its over-cap meaning.
 */
private fun renderRequestPreview(messages: List<UIMessage>, cap: Int): RenderedPreview {
    val sb = StringBuilder(cap.coerceAtLeast(64))
    var truncated = false
    // Returns false once the cap is reached; appends at most `cap` chars total.
    fun appendCapped(value: String): Boolean {
        val remaining = cap - sb.length
        if (remaining <= 0) return false
        if (value.length <= remaining) {
            sb.append(value)
            return true
        }
        sb.append(value, 0, remaining)
        return false
    }
    // Payload size of a part, recursively — the number `<tool_result:chars>`
    // reports. Only real content counts; placeholders contribute 0.
    fun contentChars(part: UIMessagePart): Int = when (part) {
        is UIMessagePart.Text -> part.text.length
        is UIMessagePart.Reasoning -> part.reasoning.length
        is UIMessagePart.Tool -> part.input.length + part.output.sumOf { contentChars(it) }
        else -> 0 // Image/Video/Audio/Document/MiniApp carry no inlined text
    }
    loop@ for (message in messages) {
        if (sb.length >= cap) {
            truncated = true
            break
        }
        if (sb.isNotEmpty() && !appendCapped("\n")) {
            truncated = true
            break
        }
        if (!appendCapped("[${message.role.name.lowercase()}]")) {
            truncated = true
            break
        }
        for (part in message.parts) {
            val rendered = when (part) {
                is UIMessagePart.Text -> part.text
                is UIMessagePart.Image -> "<image>"
                is UIMessagePart.Video -> "<video>"
                is UIMessagePart.Audio -> "<audio>"
                is UIMessagePart.Document -> "<document:${part.fileName}>"
                is UIMessagePart.MiniApp -> "<mini_app:${part.appId}>"
                is UIMessagePart.Reasoning -> "<reasoning:${part.reasoning.length}>"
                is UIMessagePart.Tool -> buildString {
                    append("<tool:")
                    append(part.toolName)
                    if (part.output.isNotEmpty()) {
                        append("> <tool_result:")
                        append(part.output.sumOf { contentChars(it) })
                    }
                    append('>')
                }
            }
            if (!appendCapped(" ")) {
                truncated = true
                break@loop
            }
            if (!appendCapped(rendered)) {
                truncated = true
                break@loop
            }
        }
    }
    return RenderedPreview(sb.toString(), truncated)
}
