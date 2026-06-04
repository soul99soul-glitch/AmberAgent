package app.amber.feature.ui.components.message

import app.amber.ai.ui.UIMessagePart
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SearchPresentationTest {
    @Test
    fun derivesGalleryAndSourcesFromSearchToolOutput() {
        val parts = listOf(
            searchTool(
                """
                {
                  "items": [
                    {
                      "id": "abc123",
                      "title": "Will Smith concert update",
                      "url": "https://www.heightline.com/will-smith-tour",
                      "domain": "heightline.com",
                      "images": [
                        "https://img.example/one.jpg",
                        "https://img.example/two.jpg",
                        "https://img.example/one.jpg"
                      ]
                    },
                    {
                      "id": "def456",
                      "title": "新浪娱乐",
                      "url": "https://www.sina.com.cn/ent/music",
                      "domain": "sina.com.cn",
                      "images": [
                        "https://img.example/three.jpg",
                        "https://img.example/four.jpg",
                        "https://img.example/five.jpg",
                        "https://img.example/six.jpg"
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            ),
            UIMessagePart.Text("answer streaming text"),
        )

        val presentation = deriveSearchPresentation(parts)

        assertEquals(5, presentation.images.size)
        assertEquals("https://img.example/one.jpg", presentation.images[0].url)
        assertEquals("heightline", presentation.sources.lookup("https://www.heightline.com/other")?.name)
        assertEquals("新浪", presentation.sources.lookup("https://sina.com.cn/news")?.name)
        assertNotNull(presentation.sources.lookup("https://www.sina.com.cn/ent/music"))
        assertNull(presentation.sources.lookup("https://unmatched.example/story"))
    }

    @Test
    fun searchOutputSignatureIgnoresAssistantTextChanges() {
        val tool = searchTool("""{"items":[]}""")
        val first = listOf(tool, UIMessagePart.Text("partial"))
        val second = listOf(tool, UIMessagePart.Text("partial plus more streamed text"))

        assertEquals(first.searchWebOutputsSignature(), second.searchWebOutputsSignature())
    }

    @Test
    fun normalizesCommonHosts() {
        assertEquals("bbc.co.uk", normalizeSearchSourceHost("https://www.bbc.co.uk/news"))
        assertEquals("sina.com.cn", normalizeSearchSourceHost("//www.sina.com.cn/ent"))
        assertEquals("heightline.com", normalizeSearchSourceHost("heightline.com/story"))
    }

    private fun searchTool(output: String): UIMessagePart.Tool {
        return UIMessagePart.Tool(
            toolCallId = "call-search",
            toolName = "search_web",
            input = "{}",
            output = listOf(UIMessagePart.Text(output)),
        )
    }
}
