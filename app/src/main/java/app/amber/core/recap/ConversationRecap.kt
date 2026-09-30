package app.amber.core.recap

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Minimum user messages on the current branch before a recap is offered. */
const val RECAP_MIN_USER_MESSAGES = 3
const val RECAP_MAX_NODES = 8

@Serializable
enum class RecapNodeKind {
    @SerialName("decision") DECISION,
    @SerialName("milestone") MILESTONE,
    @SerialName("failure") FAILURE,
    @SerialName("artifact") ARTIFACT,
}

@Serializable
data class RecapNode(
    val title: String,
    val kind: RecapNodeKind = RecapNodeKind.MILESTONE,
    /** Message node the entry points to; null when the model cited nothing valid. */
    val nodeId: String? = null,
    /** Selected message id of [nodeId] at generation time; the jump is only valid while it stays selected. */
    val messageId: String? = null,
)

@Serializable
data class ConversationRecap(
    val schemaVersion: Int = 1,
    val conversationId: String,
    val overview: String,
    val nodes: List<RecapNode> = emptyList(),
    val nextSteps: List<String> = emptyList(),
    /** Selected message ids of the current branch this recap covers, oldest first. */
    val branchMessageIds: List<String>,
    val generatedAtMillis: Long,
    /** Content signature of the last covered message; catches a reply that grew in place. */
    val tailSignature: String = "",
)

enum class RecapFreshness { FRESH, STALE }

/** One numbered message the model may cite. */
data class RecapSourceMessage(
    val ref: String,
    val nodeId: String,
    val messageId: String,
    val role: MessageRole,
    val text: String,
)

fun Conversation.recapUserMessageCount(): Int =
    currentMessages.count { it.role == MessageRole.USER }

fun Conversation.isRecapEligible(): Boolean =
    recapUserMessageCount() >= RECAP_MIN_USER_MESSAGES

private fun Conversation.currentBranchPairs(): List<Pair<String, UIMessage>> =
    messageNodes.mapNotNull { node ->
        node.messages.getOrNull(node.selectIndex)?.let { node.id.toString() to it }
    }

fun Conversation.currentBranchMessageIds(): List<String> =
    currentBranchPairs().map { it.second.id.toString() }

/**
 * Signature of the last message on the current branch. A resumed run can append to the
 * same assistant message (tool results, continuation), which keeps the id unchanged.
 */
fun Conversation.recapTailSignature(): String {
    val node = messageNodes.lastOrNull() ?: return ""
    val message = node.messages.getOrNull(node.selectIndex) ?: return ""
    val textLength = message.parts.sumOf { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.length
            is UIMessagePart.Tool -> part.input.length + part.output.size
            else -> 1
        }
    }
    return "${message.id}:${message.parts.size}:$textLength:${message.finishedAt}"
}

/**
 * A recap is fresh only when it covers exactly the current branch: any new message
 * after it, or a different variant selected anywhere inside it, makes it stale.
 */
fun ConversationRecap.freshnessFor(conversation: Conversation): RecapFreshness =
    if (branchMessageIds == conversation.currentBranchMessageIds() &&
        tailSignature == conversation.recapTailSignature()
    ) {
        RecapFreshness.FRESH
    } else {
        RecapFreshness.STALE
    }

/**
 * Freshness against the chat page's paged window, which holds only the newest nodes.
 * Variants can only be switched on loaded nodes, so matching the window as a suffix of
 * the covered branch is enough.
 */
fun ConversationRecap.freshnessForWindow(window: Conversation): RecapFreshness {
    val current = window.currentBranchMessageIds()
    if (current.isEmpty() || branchMessageIds.size < current.size) return RecapFreshness.STALE
    if (tailSignature != window.recapTailSignature()) return RecapFreshness.STALE
    return if (branchMessageIds.subList(branchMessageIds.size - current.size, branchMessageIds.size) == current) {
        RecapFreshness.FRESH
    } else {
        RecapFreshness.STALE
    }
}

/** Whether [node] still points at the message it was generated from. */
fun RecapNode.isJumpable(conversation: Conversation): Boolean {
    val nodeId = nodeId ?: return false
    val messageId = messageId ?: return false
    val node = conversation.messageNodes.firstOrNull { it.id.toString() == nodeId } ?: return false
    return node.messages.getOrNull(node.selectIndex)?.id?.toString() == messageId
}

/**
 * [isJumpable] for the chat page's paged window: a node on a page that is not loaded
 * yet is assumed valid; the jump loads the page first.
 */
fun RecapNode.isJumpableInWindow(window: Conversation, fullyLoaded: Boolean): Boolean {
    val id = nodeId ?: return false
    if (messageId == null) return false
    val loaded = window.messageNodes.any { it.id.toString() == id }
    return if (loaded) isJumpable(window) else !fullyLoaded
}

object ConversationRecapPrompt {
    const val MAX_MESSAGE_CHARS = 700
    const val MAX_TOTAL_CHARS = 24_000
    private const val HEAD_KEEP = 4

    fun sourceMessages(conversation: Conversation): List<RecapSourceMessage> =
        conversation.currentBranchPairs()
            .filter { (_, message) -> message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT }
            .mapIndexedNotNull { index, (nodeId, message) ->
                val text = message.recapText().takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                RecapSourceMessage(
                    ref = "m${index + 1}",
                    nodeId = nodeId,
                    messageId = message.id.toString(),
                    role = message.role,
                    text = text.truncateMiddle(MAX_MESSAGE_CHARS),
                )
            }

    /**
     * A previous recap can be extended only when the branch it covered is unchanged and
     * new messages were appended after it; a switched variant needs a full rebuild.
     */
    fun isIncremental(currentBranchIds: List<String>, previous: ConversationRecap?): Boolean {
        val covered = previous?.branchMessageIds ?: return false
        return covered.isNotEmpty() &&
            currentBranchIds.size > covered.size &&
            currentBranchIds.subList(0, covered.size) == covered
    }

    /**
     * Messages to show the model. An incremental update sends only the messages after
     * the previous recap; otherwise the head and the most recent messages are kept
     * within [MAX_TOTAL_CHARS].
     */
    fun selectForPrompt(
        all: List<RecapSourceMessage>,
        previous: ConversationRecap?,
        incremental: Boolean,
    ): List<RecapSourceMessage> {
        val covered = previous?.branchMessageIds?.toSet().orEmpty()
        val candidates = if (incremental) all.filter { it.messageId !in covered } else all
        if (candidates.sumOf { it.text.length } <= MAX_TOTAL_CHARS) return candidates
        val head = if (incremental) emptyList() else candidates.take(HEAD_KEEP)
        var budget = MAX_TOTAL_CHARS - head.sumOf { it.text.length }
        val tail = ArrayDeque<RecapSourceMessage>()
        for (message in candidates.drop(head.size).asReversed()) {
            if (message.text.length > budget) break
            budget -= message.text.length
            tail.addFirst(message)
        }
        return head + tail
    }

    fun build(
        title: String,
        localeName: String,
        messages: List<RecapSourceMessage>,
        allSources: List<RecapSourceMessage>,
        previous: ConversationRecap?,
        previousIsIncremental: Boolean,
    ): String = buildString {
        appendLine("You write a structured recap of a chat session so the user can quickly remember what happened.")
        appendLine("Reply in $localeName. Output ONLY one JSON object, no prose, with this shape:")
        appendLine("""{"overview": string, "nodes": [{"ref": "m3", "kind": "decision|milestone|failure|artifact", "title": string}], "next": [string]}""")
        appendLine("Rules:")
        appendLine("- overview: 2-4 sentences: what was worked on, the outcome, what is still open.")
        appendLine("- nodes: 3 to $RECAP_MAX_NODES key moments in chronological order. ref MUST be one of the message refs below. title under 24 words.")
        appendLine("- next: 0 to 3 concrete next steps the user could send as a message. Empty if nothing is open.")
        if (title.isNotBlank()) appendLine("Session title: $title")
        if (previous != null && previousIsIncremental) {
            appendLine()
            appendLine("Previous recap (update it with the new messages; keep still-relevant nodes by their refs):")
            appendLine("overview: ${previous.overview}")
            previous.nodes.forEach { node ->
                val ref = allSources.firstOrNull { it.messageId == node.messageId }?.ref ?: "none"
                appendLine("- $ref [${node.kind.name.lowercase()}] ${node.title}")
            }
            previous.nextSteps.forEach { appendLine("- next: $it") }
        }
        appendLine()
        appendLine("Messages:")
        messages.forEach { message ->
            appendLine("[${message.ref}] ${message.role.name.lowercase()}: ${message.text}")
        }
    }

    private fun UIMessage.recapText(): String = parts.mapNotNull { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.trim().takeIf { it.isNotEmpty() }
            is UIMessagePart.Image -> "(image)"
            is UIMessagePart.Document -> "(file: ${part.fileName})"
            is UIMessagePart.MiniApp -> "(mini app: ${part.title})"
            is UIMessagePart.Tool -> {
                val failed = part.output.any { it is UIMessagePart.Text && it.text.contains("\"error\"") }
                "(tool ${part.toolName}${if (failed) " failed" else ""}: ${part.input.take(120)})"
            }
            else -> null
        }
    }.joinToString("\n")

    internal fun String.truncateMiddle(max: Int): String {
        if (length <= max) return this
        val keepHead = max * 2 / 3
        val keepTail = max - keepHead - 1
        return take(keepHead) + "…" + takeLast(keepTail)
    }
}

object ConversationRecapParser {
    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Tolerant parse: accepts fenced code blocks and prose around the object, keeps up
     * to [RECAP_MAX_NODES] nodes, and drops refs that are not in [sources] (the full
     * numbered branch, so an incremental update can keep citing earlier messages).
     * A node without a valid ref keeps its previous target when the title is unchanged.
     */
    fun parse(
        raw: String,
        conversationId: String,
        sources: List<RecapSourceMessage>,
        branchMessageIds: List<String>,
        previous: ConversationRecap?,
        nowMillis: Long,
        tailSignature: String = "",
    ): ConversationRecap? {
        val obj = extractObject(raw) ?: return null
        val overview = obj.string("overview")?.trim().orEmpty()
        if (overview.isEmpty()) return null
        val byRef = sources.associateBy { it.ref }
        val previousByTitle = previous?.nodes?.associateBy { it.title.trim() }.orEmpty()
        val nodes = (obj["nodes"] as? JsonArray).orEmpty().mapNotNull { element ->
            val node = element as? JsonObject ?: return@mapNotNull null
            val title = node.string("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val kind = when (node.string("kind")?.trim()?.lowercase()) {
                "decision" -> RecapNodeKind.DECISION
                "failure", "error" -> RecapNodeKind.FAILURE
                "artifact", "output" -> RecapNodeKind.ARTIFACT
                else -> RecapNodeKind.MILESTONE
            }
            val source = node.string("ref")?.trim()?.let(byRef::get)
            val carried = if (source == null) previousByTitle[title] else null
            RecapNode(
                title = title,
                kind = kind,
                nodeId = source?.nodeId ?: carried?.nodeId,
                messageId = source?.messageId ?: carried?.messageId,
            )
        }.take(RECAP_MAX_NODES)
        val next = (obj["next"] as? JsonArray ?: obj["nextSteps"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            .take(3)
        return ConversationRecap(
            conversationId = conversationId,
            overview = overview,
            nodes = nodes,
            nextSteps = next,
            branchMessageIds = branchMessageIds,
            generatedAtMillis = nowMillis,
            tailSignature = tailSignature,
        )
    }

    private fun extractObject(raw: String): JsonObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { lenientJson.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject }
            .getOrNull()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.jsonPrimitive?.contentOrNull
}
