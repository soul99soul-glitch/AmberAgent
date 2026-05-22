package me.rerere.rikkahub.data.agent.board.hotlist

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadEvidenceRegistry
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
                          "text": "mentions https://unvisited.example.com/body only"
                        }
                      ]
                    }
                    """.trimIndent()
                )
            ),
        )

        assertTrue(registry.isAllowed("https://visited.example.com/a"))
        assertTrue(registry.containsEvidence("https://visited.example.com/a", "真实搜索标题用于验真"))
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
}
