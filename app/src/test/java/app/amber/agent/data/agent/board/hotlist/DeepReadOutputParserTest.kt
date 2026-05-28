package app.amber.feature.board.hotlist

import kotlinx.serialization.json.Json
import app.amber.feature.board.hotlist.deepread.DeepReadOutputParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DeepReadOutputParserTest {
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
}
