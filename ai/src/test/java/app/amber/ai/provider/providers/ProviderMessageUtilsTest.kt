package app.amber.ai.provider.providers

import app.amber.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderMessageUtilsTest {

    // ==================== groupPartsByToolBoundary Tests ====================

    @Test
    fun `empty parts should return empty groups`() {
        val parts = emptyList<UIMessagePart>()
        val result = groupPartsByToolBoundary(parts)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `only text parts should return single Content group`() {
        val parts = listOf(
            UIMessagePart.Text("Hello"),
            UIMessagePart.Text("World")
        )
        val result = groupPartsByToolBoundary(parts)

        assertEquals(1, result.size)
        assertTrue(result[0] is PartGroup.Content)
        assertEquals(2, (result[0] as PartGroup.Content).parts.size)
    }

    @Test
    fun `only executed tools should return single Tools group`() {
        val parts = listOf(
            createExecutedTool("call1", "tool1"),
            createExecutedTool("call2", "tool2")
        )
        val result = groupPartsByToolBoundary(parts)

        assertEquals(1, result.size)
        assertTrue(result[0] is PartGroup.Tools)
        assertEquals(2, (result[0] as PartGroup.Tools).tools.size)
    }

    @Test
    fun `unexecuted tool should be in Content group`() {
        val parts = listOf(
            UIMessagePart.Text("Hello"),
            createUnexecutedTool("call1", "tool1")
        )
        val result = groupPartsByToolBoundary(parts)

        assertEquals(1, result.size)
        assertTrue(result[0] is PartGroup.Content)
        assertEquals(2, (result[0] as PartGroup.Content).parts.size)
    }

    @Test
    fun `interleaved text and tools should create alternating groups`() {
        // [Text1, Tool1, Tool2, Text2, Tool3]
        val parts = listOf(
            UIMessagePart.Text("Text1"),
            createExecutedTool("call1", "tool1"),
            createExecutedTool("call2", "tool2"),
            UIMessagePart.Text("Text2"),
            createExecutedTool("call3", "tool3")
        )
        val result = groupPartsByToolBoundary(parts)

        assertEquals(4, result.size)

        // Content([Text1])
        assertTrue(result[0] is PartGroup.Content)
        assertEquals(1, (result[0] as PartGroup.Content).parts.size)

        // Tools([Tool1, Tool2])
        assertTrue(result[1] is PartGroup.Tools)
        assertEquals(2, (result[1] as PartGroup.Tools).tools.size)

        // Content([Text2])
        assertTrue(result[2] is PartGroup.Content)
        assertEquals(1, (result[2] as PartGroup.Content).parts.size)

        // Tools([Tool3])
        assertTrue(result[3] is PartGroup.Tools)
        assertEquals(1, (result[3] as PartGroup.Tools).tools.size)
    }

    @Test
    fun `image parts should be grouped with content`() {
        val parts = listOf(
            UIMessagePart.Image(url = "http://example.com/image.png"),
            UIMessagePart.Text("Description"),
            createExecutedTool("call1", "tool1")
        )
        val result = groupPartsByToolBoundary(parts)

        assertEquals(2, result.size)

        // Content([Image, Text])
        assertTrue(result[0] is PartGroup.Content)
        val contentParts = (result[0] as PartGroup.Content).parts
        assertEquals(2, contentParts.size)
        assertTrue(contentParts[0] is UIMessagePart.Image)
        assertTrue(contentParts[1] is UIMessagePart.Text)
    }

    @Test
    fun `multi-round reasoning should be preserved in correct positions`() {
        // Input: [Reasoning1, Text1, Tool1, Reasoning2, Text2, Tool2, Reasoning3, Text3]
        // Each Reasoning should stay with its following content

        val parts = listOf(
            UIMessagePart.Reasoning(reasoning = "First thought"),
            UIMessagePart.Text("First action"),
            createExecutedTool("call1", "tool1"),
            UIMessagePart.Reasoning(reasoning = "Second thought"),
            UIMessagePart.Text("Second action"),
            createExecutedTool("call2", "tool2"),
            UIMessagePart.Reasoning(reasoning = "Final thought"),
            UIMessagePart.Text("Final answer")
        )
        val groups = groupPartsByToolBoundary(parts)

        assertEquals(5, groups.size)

        // Group 0: [Reasoning1, Text1]
        assertTrue(groups[0] is PartGroup.Content)
        var content = (groups[0] as PartGroup.Content).parts
        assertEquals(2, content.size)
        assertEquals("First thought", (content[0] as UIMessagePart.Reasoning).reasoning)
        assertEquals("First action", (content[1] as UIMessagePart.Text).text)

        // Group 1: [Tool1]
        assertTrue(groups[1] is PartGroup.Tools)

        // Group 2: [Reasoning2, Text2]
        assertTrue(groups[2] is PartGroup.Content)
        content = (groups[2] as PartGroup.Content).parts
        assertEquals(2, content.size)
        assertEquals("Second thought", (content[0] as UIMessagePart.Reasoning).reasoning)
        assertEquals("Second action", (content[1] as UIMessagePart.Text).text)

        // Group 3: [Tool2]
        assertTrue(groups[3] is PartGroup.Tools)

        // Group 4: [Reasoning3, Text3]
        assertTrue(groups[4] is PartGroup.Content)
        content = (groups[4] as PartGroup.Content).parts
        assertEquals(2, content.size)
        assertEquals("Final thought", (content[0] as UIMessagePart.Reasoning).reasoning)
        assertEquals("Final answer", (content[1] as UIMessagePart.Text).text)
    }

    // ==================== Helper Functions ====================

    private fun createExecutedTool(callId: String, name: String): UIMessagePart.Tool {
        return UIMessagePart.Tool(
            toolCallId = callId,
            toolName = name,
            input = "{}",
            output = listOf(UIMessagePart.Text("Result from $name"))
        )
    }

    private fun createUnexecutedTool(callId: String, name: String): UIMessagePart.Tool {
        return UIMessagePart.Tool(
            toolCallId = callId,
            toolName = name,
            input = "{}",
            output = emptyList()
        )
    }
}
