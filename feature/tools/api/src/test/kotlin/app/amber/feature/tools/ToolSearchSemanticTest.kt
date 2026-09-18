package app.amber.feature.tools

import app.amber.ai.core.Tool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** tool_search 语义重排挂点：lazy 目录才尝试、精确名零调用、applied/shadow 两态。 */
class ToolSearchSemanticTest {

    private class RecordingSearch : ToolSemanticSearch {
        var invoked = 0
        var lastCandidates: List<SemanticToolCandidate> = emptyList()
        var result: SemanticToolRankResult? = null

        override suspend fun rerank(
            query: String,
            category: String?,
            limit: Int,
            candidates: List<SemanticToolCandidate>,
        ): SemanticToolRankResult? {
            invoked++
            lastCandidates = candidates
            return result
        }
    }

    private fun tool(name: String, description: String = "does $name") = Tool(
        name = name,
        description = description,
        execute = { emptyList() },
    )

    private fun bigRegistry(): ToolRegistry {
        val tools = (1..45).map { index -> tool("bulk_tool_$index") } +
            tool("screen_screenshot", "capture 截图 of the phone screen") +
            tool("terminal_execute", "run a shell command in the terminal")
        return ToolRegistry.from(tools)
    }

    private fun smallRegistry(): ToolRegistry =
        ToolRegistry.from((1..10).map { tool("small_tool_$it") } + tool("screen_screenshot", "capture 截图"))

    @Test
    fun appliedSemanticReplacesMatchesAndMarksTrace() = runTest {
        val registry = bigRegistry()
        val search = RecordingSearch().apply {
            result = SemanticToolRankResult(rankedNames = listOf("terminal_execute", "screen_screenshot"), applied = true)
        }
        val payload = ToolSearchIndex(registry).searchPayloadWithSemantic(
            query = "帮我跑个命令",
            category = null,
            limit = 5,
            semanticSearch = search,
        )
        assertEquals(1, search.invoked)
        val expanded = payload["expanded_tools"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("terminal_execute", "screen_screenshot"), expanded)
        assertEquals("applied", payload["semantic"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun shadowResultKeepsLexicalMatches() = runTest {
        val registry = bigRegistry()
        val search = RecordingSearch().apply {
            result = SemanticToolRankResult(rankedNames = listOf("terminal_execute"), applied = false)
        }
        val payload = ToolSearchIndex(registry).searchPayloadWithSemantic("terminal", null, 5, search)
        val expanded = payload["expanded_tools"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals("terminal_execute", expanded.first())
        assertEquals("shadow", payload["semantic"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun nullResultKeepsLexicalWithoutTrace() = runTest {
        val registry = bigRegistry()
        val search = RecordingSearch()
        val payload = ToolSearchIndex(registry).searchPayloadWithSemantic("terminal", null, 5, search)
        assertEquals(1, search.invoked)
        assertNull(payload["semantic"])
        val expanded = payload["expanded_tools"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals("terminal_execute", expanded.first())
    }

    @Test
    fun smallCatalogNeverCallsSemantic() = runTest {
        val search = RecordingSearch().apply {
            result = SemanticToolRankResult(rankedNames = listOf("small_tool_1"), applied = true)
        }
        val payload = ToolSearchIndex(smallRegistry()).searchPayloadWithSemantic("截图", null, 5, search)
        assertEquals(0, search.invoked)
        // 小目录全量常驻，词面结果不受影响
        assertTrue(payload["expanded_tools"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun exactToolNameSkipsSemantic() = runTest {
        val search = RecordingSearch()
        val payload = ToolSearchIndex(bigRegistry()).searchPayloadWithSemantic("screen_screenshot", null, 5, search)
        assertEquals(0, search.invoked)
        val expanded = payload["expanded_tools"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals("screen_screenshot", expanded.first())
        assertNull(payload["semantic"])
    }
}
