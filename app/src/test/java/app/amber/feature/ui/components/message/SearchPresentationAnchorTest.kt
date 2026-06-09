package app.amber.feature.ui.components.message

import app.amber.ai.ui.UIMessagePart
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchPresentationAnchorTest {
    @Test
    fun anchorsImagesByCitationIdAndLeavesUnreferencedImagesForHeader() {
        val presentation = deriveSearchPresentation(
            listOf(
                searchTool(
                    """
                    {
                      "items": [
                        {
                          "id": "abc123",
                          "title": "Alpha",
                          "url": "https://example.com/a",
                          "images": ["https://img.example/a.jpg"]
                        },
                        {
                          "id": "def456",
                          "title": "Beta",
                          "url": "https://beta.example/story",
                          "images": ["https://img.example/b.jpg"]
                        }
                      ]
                    }
                    """.trimIndent(),
                )
            )
        )
        val markdown = "Alpha has one image [citation,example](abc123).\n\nBeta is not referenced."
        val parseResult = MessageRenderCache.markdownParseResult(markdown)
        val block = parseResult.astTree.children.first()
        val resolver = SearchBlockImageAnchorResolver(presentation)

        assertEquals(listOf("https://img.example/a.jpg"), resolver.resolveBlock(block, parseResult.preprocessed).map { it.url })
        assertEquals(listOf("https://img.example/b.jpg"), resolver.orphans().map { it.url })
    }

    @Test
    fun canonicalUrlMatchingKeepsDistinctQueries() {
        val presentation = deriveSearchPresentation(
            listOf(
                searchTool(
                    """
                    {
                      "items": [
                        {
                          "id": "vid001",
                          "title": "Video A",
                          "url": "https://www.youtube.com/watch?v=A",
                          "images": ["https://img.example/a.jpg"]
                        },
                        {
                          "id": "vid002",
                          "title": "Video B",
                          "url": "https://youtube.com/watch?v=B",
                          "images": ["https://img.example/b.jpg"]
                        }
                      ]
                    }
                    """.trimIndent(),
                )
            )
        )
        val markdown = "Video B source is [YouTube](https://www.youtube.com/watch?v=B&utm_source=search)."
        val parseResult = MessageRenderCache.markdownParseResult(markdown)
        val block = parseResult.astTree.children.first()
        val resolver = SearchBlockImageAnchorResolver(presentation)

        assertEquals(
            listOf(SearchBlockRef.Link("https://youtube.com/watch?v=B&utm_source=search")),
            extractSearchBlockReferences(block, parseResult.preprocessed),
        )
        assertEquals(listOf("https://img.example/b.jpg"), resolver.resolveBlock(block, parseResult.preprocessed).map { it.url })
        assertEquals(listOf("https://img.example/a.jpg"), resolver.orphans().map { it.url })
    }

    @Test
    fun hostFallbackUsesUnusedImagesInOrder() {
        val presentation = deriveSearchPresentation(
            listOf(
                searchTool(
                    """
                    {
                      "items": [
                        {
                          "id": "one111",
                          "title": "One",
                          "url": "https://news.example/one",
                          "images": ["https://img.example/one.jpg"]
                        },
                        {
                          "id": "two222",
                          "title": "Two",
                          "url": "https://news.example/two",
                          "images": ["https://img.example/two.jpg"]
                        }
                      ]
                    }
                    """.trimIndent(),
                )
            )
        )
        val markdown = "See [first](https://news.example/other) and [second](https://news.example/more)."
        val parseResult = MessageRenderCache.markdownParseResult(markdown)
        val block = parseResult.astTree.children.first()
        val resolver = SearchBlockImageAnchorResolver(presentation)

        assertEquals(
            listOf("https://img.example/one.jpg", "https://img.example/two.jpg"),
            resolver.resolveBlock(block, parseResult.preprocessed).map { it.url },
        )
        assertTrue(resolver.orphans().isEmpty())
    }

    @Test
    fun skipsCodeAndMarkdownImageReferences() {
        val presentation = deriveSearchPresentation(
            listOf(
                searchTool(
                    """
                    {
                      "items": [
                        {
                          "id": "abc123",
                          "title": "Alpha",
                          "url": "https://example.com/a",
                          "images": ["https://img.example/a.jpg"]
                        }
                      ]
                    }
                    """.trimIndent(),
                )
            )
        )
        val markdown = "`[citation,example](abc123)`\n\n![Alpha](https://example.com/a)"
        val parseResult = MessageRenderCache.markdownParseResult(markdown)
        val blocks = parseResult.astTree.children
        val resolver = SearchBlockImageAnchorResolver(presentation)

        assertTrue(resolver.resolveBlock(blocks[0], parseResult.preprocessed).isEmpty())
        assertTrue(resolver.resolveBlock(blocks[1], parseResult.preprocessed).isEmpty())
        assertEquals(listOf("https://img.example/a.jpg"), resolver.orphans().map { it.url })
    }

    @Test
    fun resolverDeduplicatesUsedImagesAcrossBlocks() {
        val presentation = deriveSearchPresentation(
            listOf(
                searchTool(
                    """
                    {
                      "items": [
                        {
                          "id": "aaa111",
                          "title": "Alpha",
                          "url": "https://example.com/a",
                          "images": ["https://img.example/a.jpg"]
                        }
                      ]
                    }
                    """.trimIndent(),
                )
            )
        )
        val markdown = """
            First paragraph [citation,example](aaa111).

            Second paragraph has no citation.

            Third paragraph repeats [citation,example](aaa111).
        """.trimIndent()
        val parseResult = MessageRenderCache.markdownParseResult(markdown)
        val blocks = parseResult.astTree.children
        val resolver = SearchBlockImageAnchorResolver(presentation)

        assertEquals(listOf("https://img.example/a.jpg"), resolver.resolveBlock(blocks[0], parseResult.preprocessed).map { it.url })
        assertTrue(resolver.resolveBlock(blocks[1], parseResult.preprocessed).isEmpty())
        assertTrue(resolver.resolveBlock(blocks[2], parseResult.preprocessed).isEmpty())
        assertTrue(resolver.orphans().isEmpty())
    }

    @Test
    fun resolverCapsImagesPerBlockAtTwo() {
        val presentation = deriveSearchPresentation(
            listOf(
                searchTool(
                    """
                    {
                      "items": [
                        {
                          "id": "aaa111",
                          "title": "Alpha",
                          "url": "https://example.com/a",
                          "images": ["https://img.example/a.jpg"]
                        },
                        {
                          "id": "bbb222",
                          "title": "Beta",
                          "url": "https://example.com/b",
                          "images": ["https://img.example/b.jpg"]
                        },
                        {
                          "id": "ccc333",
                          "title": "Gamma",
                          "url": "https://example.com/c",
                          "images": ["https://img.example/c.jpg"]
                        }
                      ]
                    }
                    """.trimIndent(),
                )
            )
        )
        val markdown = """
            Sources: [citation,example](aaa111), [citation,example](bbb222), [citation,example](ccc333).
        """.trimIndent()
        val parseResult = MessageRenderCache.markdownParseResult(markdown)
        val block = parseResult.astTree.children.first()
        val resolver = SearchBlockImageAnchorResolver(presentation)

        assertEquals(
            listOf("https://img.example/a.jpg", "https://img.example/b.jpg"),
            resolver.resolveBlock(block, parseResult.preprocessed).map { it.url },
        )
        assertEquals(listOf("https://img.example/c.jpg"), resolver.orphans().map { it.url })
    }

    @Test
    fun deriveSearchPresentationKeepsGlobalImageCapAtFive() {
        val presentation = deriveSearchPresentation(
            listOf(
                searchTool(
                    """
                    {
                      "items": [
                        {"id": "aaa111", "title": "One", "url": "https://example.com/1", "images": ["https://img.example/1.jpg"]},
                        {"id": "bbb222", "title": "Two", "url": "https://example.com/2", "images": ["https://img.example/2.jpg"]},
                        {"id": "ccc333", "title": "Three", "url": "https://example.com/3", "images": ["https://img.example/3.jpg"]},
                        {"id": "ddd444", "title": "Four", "url": "https://example.com/4", "images": ["https://img.example/4.jpg"]},
                        {"id": "eee555", "title": "Five", "url": "https://example.com/5", "images": ["https://img.example/5.jpg"]},
                        {"id": "fff666", "title": "Six", "url": "https://example.com/6", "images": ["https://img.example/6.jpg"]}
                      ]
                    }
                    """.trimIndent(),
                )
            )
        )

        assertEquals(5, presentation.images.size)
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
