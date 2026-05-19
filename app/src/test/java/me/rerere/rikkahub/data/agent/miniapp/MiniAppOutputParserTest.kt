package me.rerere.rikkahub.data.agent.miniapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiniAppOutputParserTest {
    private val parser = MiniAppOutputParser()

    @Test
    fun parsesFencedMiniAppJson() {
        val output = parser.parse(
            """
            ```json
            {
              "title": "喝水记录器",
              "description": "记录每天喝水量",
              "icon": "水",
              "category": "tool",
              "permissions": ["storage", "toast", "theme"],
              "html": "<!DOCTYPE html><html><body><script>Amber.toast('hi')</script></body></html>"
            }
            ```
            """.trimIndent()
        )

        assertEquals("喝水记录器", output.title)
        assertEquals(listOf("storage", "toast", "theme"), output.permissions)
    }

    @Test
    fun acceptsV2Permissions() {
        val output = parser.parse(
            """
            {
              "title": "新闻工具",
              "description": "搜索并展示新闻",
              "category": "info",
              "permissions": ["network", "externalImages", "search", "clipboard.copy", "host.updateBoardSummary"],
              "html": "<!DOCTYPE html><html><body><img src=\"https://example.com/a.png\"><script>Amber.search({query:'AI'}); Amber.fetch({url:'https://example.com/api'});</script></body></html>"
            }
            """.trimIndent()
        )

        assertEquals(
            listOf("network", "externalImages", "search", "clipboard.copy", "host.updateBoardSummary"),
            output.permissions,
        )
    }

    @Test
    fun rejectsUnknownPermissions() {
        val output = parser.parseOrNull(
            """
            {
              "title": "定位工具",
              "description": "V2 不开放定位",
              "category": "tool",
              "permissions": ["location"],
              "html": "<!DOCTYPE html><html><body>ok</body></html>"
            }
            """.trimIndent()
        )

        assertNull(output)
    }
}
