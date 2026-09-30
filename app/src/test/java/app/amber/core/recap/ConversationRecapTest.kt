package app.amber.core.recap

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationRecapTest {
    private fun msg(role: MessageRole, text: String) = UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)))

    private fun conversation(vararg turns: String): Conversation {
        val nodes = turns.mapIndexed { index, text ->
            MessageNode(messages = listOf(msg(if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT, text)))
        }
        return Conversation(assistantId = Uuid.random(), messageNodes = nodes)
    }

    @Test
    fun `eligibility needs three user messages on the current branch`() {
        assertFalse(conversation("a", "b", "c", "d").isRecapEligible())
        assertTrue(conversation("a", "b", "c", "d", "e").isRecapEligible())
    }

    @Test
    fun `parser accepts fenced json with prose and drops unknown refs`() {
        val conv = conversation("plan it", "ok", "build it", "done", "ship")
        val sources = ConversationRecapPrompt.sourceMessages(conv)
        val raw = """
            Sure, here is the recap:
            ```json
            {"overview": "Planned and built the feature.",
             "nodes": [
               {"ref": "m1", "kind": "decision", "title": "Chose the plan"},
               {"ref": "m99", "kind": "milestone", "title": "Invented"},
               {"ref": "m4", "kind": "error", "title": "Build failed"}
             ],
             "next": ["Ship it", ""]}
            ```
        """.trimIndent()
        val recap = ConversationRecapParser.parse(raw, conv.id.toString(), sources, conv.currentBranchMessageIds(), null, 1L)
        assertNotNull(recap)
        recap!!
        assertEquals("Planned and built the feature.", recap.overview)
        assertEquals(3, recap.nodes.size)
        assertEquals(conv.messageNodes[0].id.toString(), recap.nodes[0].nodeId)
        assertNull(recap.nodes[1].nodeId)
        assertEquals(RecapNodeKind.FAILURE, recap.nodes[2].kind)
        assertEquals(listOf("Ship it"), recap.nextSteps)
    }

    @Test
    fun `parser keeps at most eight nodes and rejects missing overview`() {
        val conv = conversation("a", "b", "c", "d", "e")
        val sources = ConversationRecapPrompt.sourceMessages(conv)
        val nodes = (1..12).joinToString(",") { """{"ref":"m1","title":"n$it"}""" }
        val recap = ConversationRecapParser.parse("""{"overview":"x","nodes":[$nodes]}""", "id", sources, emptyList(), null, 0L)
        assertEquals(RECAP_MAX_NODES, recap!!.nodes.size)
        assertNull(ConversationRecapParser.parse("""{"nodes":[]}""", "id", sources, emptyList(), null, 0L))
        assertNull(ConversationRecapParser.parse("no json here", "id", sources, emptyList(), null, 0L))
    }

    @Test
    fun `freshness turns stale on new messages and on a switched variant`() {
        val conv = conversation("a", "b", "c", "d", "e")
        val recap = ConversationRecap(
            conversationId = conv.id.toString(),
            overview = "x",
            branchMessageIds = conv.currentBranchMessageIds(),
            generatedAtMillis = 0L,
            tailSignature = conv.recapTailSignature(),
        )
        assertEquals(RecapFreshness.FRESH, recap.freshnessFor(conv))
        val longer = conv.copy(messageNodes = conv.messageNodes + MessageNode(messages = listOf(msg(MessageRole.ASSISTANT, "f"))))
        assertEquals(RecapFreshness.STALE, recap.freshnessFor(longer))

        val node = conv.messageNodes[1]
        val switched = conv.copy(
            messageNodes = conv.messageNodes.toMutableList().also {
                it[1] = node.copy(messages = node.messages + msg(MessageRole.ASSISTANT, "b2"), selectIndex = 1)
            }
        )
        assertEquals(RecapFreshness.STALE, recap.freshnessFor(switched))
        assertFalse(RecapNode("t", nodeId = node.id.toString(), messageId = node.messages[0].id.toString()).isJumpable(switched))
        assertTrue(RecapNode("t", nodeId = node.id.toString(), messageId = node.messages[0].id.toString()).isJumpable(conv))
    }

    @Test
    fun `window freshness matches the loaded suffix only`() {
        val conv = conversation("a", "b", "c", "d", "e")
        val recap = ConversationRecap(
            conversationId = conv.id.toString(),
            overview = "x",
            branchMessageIds = conv.currentBranchMessageIds(),
            generatedAtMillis = 0L,
            tailSignature = conv.recapTailSignature(),
        )
        val window = conv.copy(messageNodes = conv.messageNodes.takeLast(2))
        assertEquals(RecapFreshness.FRESH, recap.freshnessForWindow(window))
        val newer = window.copy(messageNodes = window.messageNodes + MessageNode(messages = listOf(msg(MessageRole.USER, "z"))))
        assertEquals(RecapFreshness.STALE, recap.freshnessForWindow(newer))
    }

    @Test
    fun `incremental prompt sends only messages after the covered branch`() {
        val conv = conversation("a", "b", "c", "d", "e", "f")
        val ids = conv.currentBranchMessageIds()
        val previous = ConversationRecap(
            conversationId = conv.id.toString(),
            overview = "old",
            branchMessageIds = ids.take(4),
            generatedAtMillis = 0L,
        )
        val sources = ConversationRecapPrompt.sourceMessages(conv)
        assertTrue(ConversationRecapPrompt.isIncremental(ids, previous))
        val selected = ConversationRecapPrompt.selectForPrompt(sources, previous, incremental = true)
        assertEquals(listOf("e", "f"), selected.map { it.text })
        val rebased = previous.copy(branchMessageIds = listOf("other") + ids.drop(1).take(3))
        assertFalse(ConversationRecapPrompt.isIncremental(ids, rebased))
    }

    @Test
    fun `prompt budget keeps the head and the newest messages`() {
        val big = "x".repeat(ConversationRecapPrompt.MAX_MESSAGE_CHARS)
        val turns = Array(80) { "$it$big" }
        val sources = ConversationRecapPrompt.sourceMessages(conversation(*turns))
        val selected = ConversationRecapPrompt.selectForPrompt(sources, null, incremental = false)
        assertTrue(selected.sumOf { it.text.length } <= ConversationRecapPrompt.MAX_TOTAL_CHARS)
        assertEquals("m1", selected.first().ref)
        assertEquals(sources.last().ref, selected.last().ref)
        assertTrue(sources.all { it.text.length <= ConversationRecapPrompt.MAX_MESSAGE_CHARS })
    }

    @Test
    fun `store round-trips and deletes the recap file`() = runBlocking {
        val dir = Files.createTempDirectory("recap").toFile()
        val id = Uuid.random().toString()
        val recap = ConversationRecap(conversationId = id, overview = "x", branchMessageIds = listOf("a"), generatedAtMillis = 1L)
        ConversationRecapStore(dir).save(recap)
        val reopened = ConversationRecapStore(dir)
        assertEquals(recap, reopened.ensureLoaded(id))
        reopened.delete(id)
        assertFalse(dir.resolve("$id.json").exists())
        assertNull(ConversationRecapStore(dir).ensureLoaded(id))
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `a reply growing in place makes the recap stale`() {
        val conv = conversation("a", "b", "c", "d", "e", "partial")
        val recap = ConversationRecap(
            conversationId = conv.id.toString(),
            overview = "x",
            branchMessageIds = conv.currentBranchMessageIds(),
            generatedAtMillis = 0L,
            tailSignature = conv.recapTailSignature(),
        )
        assertEquals(RecapFreshness.FRESH, recap.freshnessFor(conv))
        val last = conv.messageNodes.last()
        val grown = conv.copy(
            messageNodes = conv.messageNodes.dropLast(1) + last.copy(
                messages = listOf(last.messages[0].copy(parts = listOf(UIMessagePart.Text("partial, then more")))),
            ),
        )
        assertEquals(conv.currentBranchMessageIds(), grown.currentBranchMessageIds())
        assertEquals(RecapFreshness.STALE, recap.freshnessFor(grown))
        assertEquals(RecapFreshness.STALE, recap.freshnessForWindow(grown.copy(messageNodes = grown.messageNodes.takeLast(2))))
    }

    @Test
    fun `window jump check trusts unloaded pages only while more pages exist`() {
        val conv = conversation("a", "b", "c", "d", "e")
        val first = conv.messageNodes.first()
        val node = RecapNode("t", nodeId = first.id.toString(), messageId = first.messages[0].id.toString())
        val window = conv.copy(messageNodes = conv.messageNodes.takeLast(2))
        assertTrue(node.isJumpableInWindow(window, fullyLoaded = false))
        assertFalse(node.isJumpableInWindow(window, fullyLoaded = true))
        assertTrue(node.isJumpableInWindow(conv, fullyLoaded = true))
        assertFalse(RecapNode("t").isJumpableInWindow(conv, fullyLoaded = true))
    }

    @Test
    fun `store state machine ends every attempt in a terminal state`() = runBlocking {
        val dir = Files.createTempDirectory("recap").toFile()
        val store = ConversationRecapStore(dir)
        val id = Uuid.random().toString()
        store.setGenerating(id, true)
        assertTrue(store.current(id).generating)
        store.markFailed(id, RecapFailure.NO_MODEL)
        assertEquals(RecapFailure.NO_MODEL, store.current(id).failure)
        assertFalse(store.current(id).generating)
        assertTrue(store.current(id).loaded)
        store.setGenerating(id, true)
        assertNull(store.current(id).failure)
        store.markIneligible(id)
        assertTrue(store.current(id).ineligible)
        assertFalse(store.current(id).generating)
        val flow = store.observe(id)
        store.save(ConversationRecap(conversationId = id, overview = "x", branchMessageIds = emptyList(), generatedAtMillis = 0L))
        assertFalse(flow.value.ineligible)
        store.delete(id)
        // The same flow instance a live screen collects is reset, not orphaned.
        assertNull(flow.value.recap)
        dir.deleteRecursively()
        Unit
    }
}
