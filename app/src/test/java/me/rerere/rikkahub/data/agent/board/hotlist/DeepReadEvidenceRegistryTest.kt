package app.amber.feature.board.hotlist

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import app.amber.feature.board.hotlist.deepread.DeepReadEvidenceRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepReadEvidenceRegistryTest {
    @Test
    fun searchRecordingOnlyAllowsStructuredResultUrls() {
        val registry = DeepReadEvidenceRegistry()
        registry.markToolResult(
            toolName = "search_web",
            input = JsonNull,
            parts = listOf(
                UIMessagePart.Text(
                    """
                    {
                      "items": [
                        {
                          "title": "真实搜索标题用于验真",
                          "url": "https://visited.example.com/a",
                          "text": "真实搜索摘要用于验真，mentions https://unvisited.example.com/body only"
                        }
                      ]
                    }
                    """.trimIndent()
                )
            ),
        )

        assertTrue(registry.isAllowed("https://visited.example.com/a"))
        assertTrue(registry.containsEvidence("https://visited.example.com/a", "真实搜索摘要用于验真"))
        assertFalse(registry.containsEvidence("https://visited.example.com/a", "真实搜索标题用于验真"))
        assertFalse(registry.isAllowed("https://unvisited.example.com/body"))
    }

    @Test
    fun scrapeRecordingOnlyAllowsRequestedUrl() {
        val registry = DeepReadEvidenceRegistry()
        registry.markToolResult(
            toolName = "scrape_web",
            input = buildJsonObject { put("url", "https://visited.example.com/a") },
            parts = listOf(
                UIMessagePart.Text(
                    """
                    {
                      "urls": [
                        {
                          "url": "https://visited.example.com/a",
                          "content": "真实来源正文支持关键声明，并且只是在正文里提到 https://unvisited.example.com/body"
                        }
                      ]
                    }
                    """.trimIndent()
                )
            ),
        )

        assertTrue(registry.isAllowed("https://visited.example.com/a"))
        assertTrue(registry.containsEvidence("https://visited.example.com/a", "真实来源正文支持关键声明"))
        assertFalse(registry.isAllowed("https://unvisited.example.com/body"))
    }

    @Test
    fun containsEvidenceToleratesPunctuationAndPartialChunkMatches() {
        val registry = DeepReadEvidenceRegistry()
        registry.mark(
            "https://visited.example.com/a",
            "小米17 Max仅比小米17涨价300元，定价策略务实；8000mAh硅基电池成为新标配。",
        )

        assertTrue(
            registry.containsEvidence(
                "https://visited.example.com/a",
                "小米17 Max 仅比小米17 涨价 300 元 定价策略务实",
            )
        )
    }

    @Test
    fun containsEvidenceRejectsLowOverlapHallucinatedTail() {
        val registry = DeepReadEvidenceRegistry()
        registry.mark(
            "https://visited.example.com/a",
            "小米17 Max仅比小米17涨价300元，定价策略务实；8000mAh硅基电池成为新标配。",
        )

        assertFalse(
            registry.containsEvidence(
                "https://visited.example.com/a",
                "小米17 Max仅比小米17涨价300元，影像功能大幅提升并确认首发",
            )
        )
    }

    @Test
    fun containsEvidenceRequiresCriticalNumberAndUnitTokensToMatch() {
        val registry = DeepReadEvidenceRegistry()
        registry.mark(
            "https://visited.example.com/a",
            "小米17 Max仅比小米17涨价300元，电池容量为8000mAh，渠道折扣约3.5%，降幅约三个百分点。",
        )

        assertFalse(registry.containsEvidence("https://visited.example.com/a", "小米17 Max 涨价3000元"))
        assertFalse(registry.containsEvidence("https://visited.example.com/a", "电池容量为9000mAh"))
        assertFalse(registry.containsEvidence("https://visited.example.com/a", "渠道折扣约35%"))
        assertFalse(registry.containsEvidence("https://visited.example.com/a", "降幅约三十个百分点"))
        assertFalse(registry.containsEvidence("https://visited.example.com/a", "价格下调300美元"))
    }

    @Test
    fun containsEvidenceRejectsDecimalPercentCollapsedByCompactText() {
        val registry = DeepReadEvidenceRegistry()
        registry.mark(
            "https://visited.example.com/a",
            "渠道折扣约3.5%，价格策略调整明显。",
        )

        assertFalse(
            registry.containsEvidence(
                "https://visited.example.com/a",
                "渠道折扣约35%，价格策略调整明显",
            )
        )
    }

    @Test
    fun containsEvidenceDoesNotMatchAcrossSeparateSegmentsForSameUrl() {
        val registry = DeepReadEvidenceRegistry()
        registry.mark("https://visited.example.com/a", "小米17 Max定价策略务实，涨价30元。")
        registry.mark("https://visited.example.com/a", "另有配件优惠300元，渠道政策单独计算。")

        assertFalse(registry.containsEvidence("https://visited.example.com/a", "小米17 Max涨价300元，定价策略务实"))
    }

    @Test
    fun containsEvidenceRequiresCriticalTokensInSameLocalWindow() {
        val registry = DeepReadEvidenceRegistry()
        registry.mark(
            "https://visited.example.com/a",
            "小米17 Max的定价策略务实，涨价30元。另有配件优惠300元，渠道政策单独计算。",
        )

        assertFalse(registry.containsEvidence("https://visited.example.com/a", "小米17 Max涨价300元，定价策略务实"))
    }

    @Test
    fun containsEvidenceNormalizesCommonModelSeparators() {
        val registry = DeepReadEvidenceRegistry()
        registry.mark("https://visited.example.com/a", "GPT 5发布窗口被重新讨论。DeepSeek V3发布窗口被重新讨论。")

        assertTrue(registry.containsEvidence("https://visited.example.com/a", "GPT-5 发布窗口被重新讨论"))
        assertTrue(registry.containsEvidence("https://visited.example.com/a", "DeepSeek-V3 发布窗口被重新讨论"))
    }

    @Test
    fun manualMarkIncludesShortMetadataEvidence() {
        val registry = DeepReadEvidenceRegistry()
        registry.mark("https://visited.example.com/a", "小米17 Max 定价公布")

        assertTrue(registry.containsEvidence("https://visited.example.com/a", "小米17 Max 定价公布"))
    }
}
