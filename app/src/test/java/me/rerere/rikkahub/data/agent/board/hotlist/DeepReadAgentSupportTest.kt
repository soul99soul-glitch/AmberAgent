package me.rerere.rikkahub.data.agent.board.hotlist

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadOutputParser
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.enabledDeepReadSearchServices
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.normalizeDeepReadFailureMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepReadAgentSupportTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parserRejectsInvalidJson() {
        assertNull(DeepReadOutputParser.parse("not-json", json))
    }

    @Test
    fun parserAcceptsJsonInsideModelPreamble() {
        val parsed = DeepReadOutputParser.parse(
            """
            here is json:
            {"summary":"这是一个足够长的摘要文本","analysis":{"implications":"影响分析"}}
            """.trimIndent(),
            json,
        )

        assertNotNull(parsed)
        assertEquals("这是一个足够长的摘要文本", parsed?.summary)
        assertEquals(0, parsed?.imageAssets?.size)
    }

    @Test
    fun parserAcceptsFencedJsonWithTrailingCommas() {
        val parsed = DeepReadOutputParser.parse(
            """
            ```json
            {
              "summary": "这是一个足够长的摘要文本",
              "key_entities": ["小米",],
              "analysis": {"implications": "影响分析",},
            }
            ```
            """.trimIndent(),
            json,
        )

        assertNotNull(parsed)
        assertEquals(listOf("小米"), parsed?.keyEntities)
    }

    @Test
    fun parserSkipsEarlierBracesAndUsesBalancedJsonObject() {
        val parsed = DeepReadOutputParser.parse(
            """
            草稿 {not json}
            {"summary":"这是一个足够长的摘要文本","analysis":{"implications":"影响分析"}}
            """.trimIndent(),
            json,
        )

        assertNotNull(parsed)
        assertEquals("影响分析", parsed?.analysis?.implications)
    }

    @Test
    fun noEnabledSearchServiceLeavesDeepReadWithoutSources() {
        val service = SearchServiceOptions.BingLocalOptions()
        val settings = Settings(
            searchServices = listOf(service),
            searchEnabledServiceIds = emptyList(),
        )

        assertEquals(emptyList<SearchServiceOptions>(), settings.enabledDeepReadSearchServices())
    }

    @Test
    fun modelFailureMessageKeepsProviderDetail() {
        val formatted = normalizeDeepReadFailureMessage(
            """
            Failed to get response: 400 {"error":{"code":400,"message":"The request was rejected because it was flagged by safety filters.","status":"INVALID_ARGUMENT"}}
            """.trimIndent(),
        )

        assertTrue(formatted.contains("模型请求被拒绝"))
        assertTrue(formatted.contains("HTTP 400"))
        assertTrue(formatted.contains("INVALID_ARGUMENT"))
        assertTrue(formatted.contains("The request was rejected because it was flagged by safety filters."))
        assertTrue(formatted.contains("安全策略"))
    }
}
