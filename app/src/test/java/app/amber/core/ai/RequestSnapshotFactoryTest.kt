package app.amber.core.ai

import app.amber.ai.core.MessageRole
import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.runtime.sha256Hex
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Step 5 request-snapshot factory: cap boundaries of the
 * rendered preview, digest inputs, and the per-subtype placeholder coverage
 * (the preview must not silently drop part types it cannot render).
 * Pure JVM — the factory is pure, no Robolectric needed.
 */
class RequestSnapshotFactoryTest {

    // Parity: must match REQUEST_SNAPSHOT_PREVIEW_CAP in RequestSnapshotFactory.kt.
    private val previewCap = 8_000

    // The factory digests ONE serialization of the fit messages with the Json
    // instance its caller (ChatGenerationRoundEngine) was constructed with.
    // Same config here, so the recomputation is exact — this is the same
    // parity requirement the replay golden s01 asserts end-to-end.
    private val json = Json { ignoreUnknownKeys = true }

    private val model = Model(
        modelId = "snapshot-model",
        displayName = "Snapshot Model",
        contextWindowTokens = 128_000,
    )

    private fun build(
        fitMessages: List<UIMessage>,
        tools: List<Tool> = emptyList(),
        systemParts: List<UIMessagePart> = emptyList(),
    ) = buildRequestSnapshot(
        json = json,
        stepIndex = 0,
        attempt = 0,
        kind = "primary",
        model = model,
        providerSettingId = "provider-1",
        fitMessages = fitMessages,
        tools = tools,
        systemParts = systemParts,
        estimatedTokens = null,
    )

    private fun blockPart(text: String, tag: String) = UIMessagePart.Text(
        text = text,
        metadata = buildJsonObject {
            put("system_prompt_block", tag)
        },
    )

    private fun userMessage(vararg parts: UIMessagePart) =
        UIMessage(role = MessageRole.USER, parts = parts.toList())

    private val lookupTool = Tool(
        name = "snapshot_lookup",
        description = "test tool",
        execute = { emptyList<UIMessagePart>() },
    )

    @Test
    fun exactFillIsNotTruncated() {
        // "[user]" (6) + separator (1) + text == cap exactly.
        val fill = "x".repeat(previewCap - 7)
        val snapshot = build(fitMessages = listOf(UIMessage.user(fill)))
        assertFalse(snapshot.truncated)
        assertEquals(previewCap, snapshot.renderedPreview.length)
        assertEquals(1, snapshot.messageCount)
    }

    @Test
    fun oneCharOverCapIsTruncated() {
        val fill = "x".repeat(previewCap - 6) // one char past the exact fill
        val snapshot = build(fitMessages = listOf(UIMessage.user(fill)))
        assertTrue(snapshot.truncated)
        assertEquals(previewCap, snapshot.renderedPreview.length)
        // The preview stops AT the cap — no overshoot.
        assertEquals("[user] " + "x".repeat(previewCap - 7), snapshot.renderedPreview)
    }

    @Test
    fun singlePartLargerThanCapIsHardCapped() {
        val huge = "y".repeat(3 * previewCap)
        val snapshot = build(fitMessages = listOf(UIMessage.user(huge)))
        assertTrue(snapshot.truncated)
        assertEquals(previewCap, snapshot.renderedPreview.length)
        assertEquals("[user] " + "y".repeat(previewCap - 7), snapshot.renderedPreview)
    }

    @Test
    fun emptyMessageListYieldsEmptyPreview() {
        val snapshot = build(fitMessages = emptyList())
        assertEquals(0, snapshot.messageCount)
        assertEquals("", snapshot.renderedPreview)
        assertFalse(snapshot.truncated)
    }

    @Test
    fun systemBlockTagsPreserveOrder() {
        val snapshot = build(
            fitMessages = listOf(UIMessage.user("hi")),
            systemParts = listOf(
                blockPart("static prompt", "static"),
                blockPart("dynamic prompt", "dynamic"),
                blockPart("tool prompts", "tool_prompts"),
            ),
        )
        assertEquals(listOf("static", "dynamic", "tool_prompts"), snapshot.systemBlockTags)
    }

    @Test
    fun emptyToolsDigestTheEmptyCatalog() {
        val snapshot = build(fitMessages = listOf(UIMessage.user("hi")), tools = emptyList())
        assertEquals(0, snapshot.exposedToolCount)
        // sha256 of the empty string — the universally known constant, so a
        // regression that digests something else cannot pass.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            snapshot.toolCatalogDigest,
        )
    }

    @Test
    fun everyPartSubtypeGetsItsPlaceholder() {
        val message = userMessage(
            UIMessagePart.Text("hello"),
            UIMessagePart.Image("https://example.com/img.png"),
            UIMessagePart.Video("https://example.com/clip.mp4"),
            UIMessagePart.Audio("https://example.com/clip.mp3", fileName = "clip.mp3"),
            UIMessagePart.Document("https://example.com/report.pdf", fileName = "report.pdf"),
            UIMessagePart.MiniApp(appId = "app_1", title = "Mini", description = "demo"),
            UIMessagePart.Reasoning("abcde"),
            UIMessagePart.Tool(
                toolCallId = "call_1",
                toolName = "search",
                input = """{"q":"amber"}""",
                output = listOf(UIMessagePart.Text("result text")),
            ),
        )
        val snapshot = build(fitMessages = listOf(message))
        // Exact rendering, order preserved; tool OUTPUT is a char count, never
        // inlined content.
        assertEquals(
            "[user] hello <image> <video> <audio> <document:report.pdf> " +
                "<mini_app:app_1> <reasoning:5> <tool:search> <tool_result:11>",
            snapshot.renderedPreview,
        )
        assertFalse(snapshot.truncated)
        // Digest parity: recomputed over the same list with the same Json.
        assertEquals(
            sha256Hex(json.encodeToString(ListSerializer(UIMessage.serializer()), listOf(message))),
            snapshot.messagesDigest,
        )
    }

    @Test
    fun toolWithoutOutputOmitsResultMarker() {
        val message = userMessage(
            UIMessagePart.Tool(
                toolCallId = "call_1",
                toolName = "pending_tool",
                input = """{"q":"amber"}""",
            ),
        )
        val snapshot = build(fitMessages = listOf(message))
        assertEquals("[user] <tool:pending_tool>", snapshot.renderedPreview)
        assertFalse("<tool_result:" in snapshot.renderedPreview)
    }

    @Test
    fun toolResultCharsCountNestedContentRecursively() {
        val message = userMessage(
            UIMessagePart.Tool(
                toolCallId = "call_1",
                toolName = "composite",
                input = "",
                output = listOf(
                    UIMessagePart.Text("12345"), // 5
                    UIMessagePart.Reasoning("xyz"), // 3
                ),
            ),
        )
        val snapshot = build(fitMessages = listOf(message))
        assertTrue("<tool_result:8>" in snapshot.renderedPreview)
    }

    @Test
    fun messagesDigestBindsTheSerializedRequestNotTheFallbackText() {
        // A message whose text-only fallback rendering would differ from the
        // real serialization (metadata + role + id survive only in the JSON).
        val message = userMessage(blockPart("annotated", "static"))
        val snapshot = build(fitMessages = listOf(message))
        val real = sha256Hex(json.encodeToString(ListSerializer(UIMessage.serializer()), listOf(message)))
        val fallback = sha256Hex("annotated")
        assertEquals(real, snapshot.messagesDigest)
        assertTrue(
            "the real serialization digest must differ from the text fallback digest",
            real != fallback,
        )
    }

    @Test
    fun toolCatalogDigestIsOrderInsensitive() {
        val a = lookupTool.copy(name = "alpha")
        val b = lookupTool.copy(name = "beta")
        val sorted = build(fitMessages = listOf(UIMessage.user("hi")), tools = listOf(a, b))
        val shuffled = build(fitMessages = listOf(UIMessage.user("hi")), tools = listOf(b, a))
        assertEquals(sorted.toolCatalogDigest, shuffled.toolCatalogDigest)
        assertEquals(2, sorted.exposedToolCount)
    }
}
