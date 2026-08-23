package app.amber.feature.miniapp

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
    fun parsesMiniAppJsonSurroundedByAssistantText() {
        val output = parser.parseOrNull(
            """
            我先把最终版本整理成 JSON：
            {
              "title": "冒险卡片",
              "description": "真心话大冒险抽题",
              "icon": "卡",
              "category": "game",
              "permissions": ["toast"],
              "html": "<!DOCTYPE html><html><body><button onclick=\"Amber.toast('go')\">抽题</button></body></html>"
            }
            """.trimIndent()
        )

        assertEquals("冒险卡片", output?.title)
        assertEquals(listOf("toast"), output?.permissions)
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
    fun acceptsV3Permissions() {
        val output = parser.parse(
            """
            {
              "title": "上下文助手",
              "description": "读取摘要并调用 AI",
              "category": "tool",
              "permissions": ["host.context", "host.sendToConversation", "host.createArtifact", "ai.generate", "sharedStore", "eventBus", "launch", "sensor", "location", "clipboard.read"],
              "html": "<!DOCTYPE html><html><body><script>Amber.host.getConversationContext({mode:'summary'}); Amber.ai.generate({prompt:'hi'});</script></body></html>"
            }
            """.trimIndent()
        )

        assertEquals("host.context", output.permissions.first())
        assertEquals("clipboard.read", output.permissions.last())
    }

    @Test
    fun normalizesCommonPermissionAliases() {
        val output = parser.parse(
            """
            {
              "title": "感应面板",
              "description": "读取网络和传感器",
              "category": "tool",
              "permissions": ["fetch", "Gyroscope", "light", "external_images"],
              "html": "<!DOCTYPE html><html><body><script>fetch('https://example.com/api'); Amber.sensor.subscribe({type:'ambientLight'}, () => {});</script></body></html>"
            }
            """.trimIndent()
        )

        assertEquals(listOf("network", "sensor", "externalImages"), output.permissions)
    }

    @Test
    fun rejectsUnknownPermissions() {
        val output = parser.parseOrNull(
            """
            {
              "title": "定位工具",
              "description": "不开放联系人",
              "category": "tool",
              "permissions": ["contacts.read"],
              "html": "<!DOCTYPE html><html><body>ok</body></html>"
            }
            """.trimIndent()
        )

        assertNull(output)
    }
}
