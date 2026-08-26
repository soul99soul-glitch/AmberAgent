package app.amber.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchAdapterMappingTest {
    @Test
    fun grokResponseMapsAnswerAndCitations() {
        val result = GrokSearchService.mapGrokSearchResponse(
            """
            {
              "output": [{
                "type": "message",
                "role": "assistant",
                "content": [{
                  "type": "output_text",
                  "text": "An answer.",
                  "annotations": [
                    {"type": "url_citation", "url": "https://example.com", "title": "Example"}
                  ]
                }]
              }],
              "citations": ["https://second.example.com"]
            }
            """.trimIndent(),
            resultSize = 2,
        )

        assertEquals("An answer.", result.answer)
        assertEquals(2, result.items.size)
        assertEquals(SearchResult.SearchResultItem("Example", "https://example.com", ""), result.items[0])
        assertEquals("https://second.example.com", result.items[1].url)
    }

    @Test
    fun linkUpResponseMapsSourcedAnswer() {
        val result = LinkUpService.mapLinkUpSearchResponse(
            """
            {
              "answer": "A sourced answer.",
              "sources": [{"name": "Example", "url": "https://example.com", "snippet": "A snippet."}]
            }
            """.trimIndent(),
            resultSize = 1,
        )

        assertEquals("A sourced answer.", result.answer)
        assertEquals("Example", result.items.single().title)
        assertEquals("A snippet.", result.items.single().text)
    }

    @Test
    fun firecrawlResponseMapsWebAndNews() {
        val result = FirecrawlSearchService.mapFirecrawlSearchResponse(
            """
            {
              "success": true,
              "data": {
                "web": [{"url": "https://example.com", "title": "Web", "description": "Description"}],
                "news": [{"url": "https://news.example.com", "title": "News", "snippet": "Snippet", "date": "2026-08-26"}]
              }
            }
            """.trimIndent(),
            resultSize = 2,
        )

        assertEquals(2, result.items.size)
        assertEquals("Description", result.items[0].text)
        assertEquals("2026-08-26", result.items[1].publishedAt)
    }

    @Test
    fun jinaResponseUsesContentWhenDescriptionIsEmpty() {
        val result = JinaSearchService.mapJinaSearchResponse(
            """
            {
              "code": 200,
              "status": 20000,
              "data": [{"title": "Example", "url": "https://example.com", "content": "Reader content"}]
            }
            """.trimIndent(),
            resultSize = 1,
        )

        assertEquals("Reader content", result.items.single().text)
    }

    @Test
    fun bochaResponseMapsSummaryAndPublishedAt() {
        val result = BochaSearchService.mapBochaSearchResponse(
            """
            {
              "code": 200,
              "log_id": "request-id",
              "data": {
                "_type": "SearchResponse",
                "queryContext": {"originalQuery": "query"},
                "webPages": {
                  "value": [{
                    "name": "Example",
                    "url": "https://example.com",
                    "snippet": "Snippet",
                    "summary": "Summary",
                    "datePublished": "2026-08-26"
                  }]
                }
              }
            }
            """.trimIndent(),
            resultSize = 1,
        )

        assertEquals("Summary", result.items.single().text)
        assertEquals("2026-08-26", result.items.single().publishedAt)
    }

    @Test
    fun tavilyResponseMapsStringAndObjectImagesToFirstResult() {
        val result = TavilySearchService.mapTavilySearchResponse(
            """
            {
              "query": "query",
              "answer": "Answer",
              "images": ["https://example.com/one.png", {"url": "https://example.com/two.png"}],
              "results": [{"title": "Example", "url": "https://example.com", "content": "Content", "score": 0.9}]
            }
            """.trimIndent(),
            resultSize = 1,
        )

        assertEquals("Answer", result.answer)
        assertEquals(listOf("https://example.com/one.png", "https://example.com/two.png"), result.items.single().images)
    }
}
