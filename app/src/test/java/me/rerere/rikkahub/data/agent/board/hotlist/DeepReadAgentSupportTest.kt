package me.rerere.rikkahub.data.agent.board.hotlist

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadOutputParser
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.enabledDeepReadSearchServices
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
