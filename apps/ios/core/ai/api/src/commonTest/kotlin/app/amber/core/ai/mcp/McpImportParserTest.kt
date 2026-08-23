package app.amber.core.ai.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class McpImportParserTest {
    @Test
    fun parsesSseAndStreamableHttpServers() {
        val servers = parseMcpServersFromJson(
            """
            {
              "mcpServers": {
                "docs": {
                  "type": "sse",
                  "url": "https://example.com/sse",
                  "headers": {
                    "Authorization": "Bearer token"
                  }
                },
                "search": {
                  "url": "https://example.com/mcp"
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(2, servers.size)

        val docs = assertIs<McpServerConfig.SseTransportServer>(servers[0])
        assertEquals("docs", docs.commonOptions.name)
        assertEquals("https://example.com/sse", docs.url)
        assertEquals(listOf("Authorization" to "Bearer token"), docs.commonOptions.headers)

        val search = assertIs<McpServerConfig.StreamableHTTPServer>(servers[1])
        assertEquals("search", search.commonOptions.name)
        assertEquals("https://example.com/mcp", search.url)
        assertEquals(emptyList(), search.commonOptions.headers)
    }

    @Test
    fun returnsEmptyListWhenMcpServersSectionIsMissing() {
        assertEquals(emptyList(), parseMcpServersFromJson("{}"))
    }
}
