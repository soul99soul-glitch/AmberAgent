package me.rerere.rikkahub.data.agent.webmount.adapters.feishudocs

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeishuDocsMarkdownPackTest {
    @Test
    fun packsOpenApiBlocksAsMarkdownWithBlockMap() {
        val result = FeishuDocsMarkdownPack.fromBlocks(
            documentId = "doc-1",
            docRef = FeishuDocRefs.encode("doc-1"),
            blocks = listOf(
                block("b1", blockType = 3, text = "Roadmap", headingLevel = 1),
                block("b2", blockType = 2, text = "Ship the stable reader."),
                block("b3", blockType = 12, text = "Snapshot current page"),
                block("b4", blockType = 14, text = "println(\"ok\")"),
            ),
        )

        val markdown = result["markdown"]!!.jsonPrimitive.contentOrNull.orEmpty()
        assertTrue(markdown.contains("# Roadmap"))
        assertTrue(markdown.contains("- Snapshot current page"))
        assertTrue(markdown.contains("```\nprintln(\"ok\")\n```"))
        assertEquals(4, result["block_map"]!!.jsonArray.size)
        assertEquals("Roadmap", result["outline"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun packsSnapshotBlocksWithoutOpenApiIds() {
        val snapshot = buildJsonObject {
            put("title", "Visible doc")
            put("session_id", "wm_1")
            put("url", "https://xiaomi.feishu.cn/wiki/abc")
            put("visible_blocks", buildJsonArray {
                add(buildJsonObject {
                    put("block_ref", "wfblk_1_1")
                    put("type", "heading")
                    put("level", 2)
                    put("text", "Decision")
                })
                add(buildJsonObject {
                    put("block_ref", "wfblk_1_2")
                    put("type", "paragraph")
                    put("text", "Use current page snapshot.")
                })
            })
        }

        val result = FeishuDocsMarkdownPack.fromSnapshot(snapshot)

        assertEquals("webview_snapshot", result["source"]!!.jsonPrimitive.content)
        assertTrue(result["markdown"]!!.jsonPrimitive.contentOrNull.orEmpty().contains("## Decision"))
        assertEquals("wfblk_1_1", result["block_map"]!!.jsonArray.first().jsonObject["block_ref"]!!.jsonPrimitive.content)
    }

    @Test
    fun marksBlocksPayloadAsTruncatedWhenAnyBlockTextWasCapped() {
        val payload = buildJsonObject {
            put("document_id", "doc-1")
            put("has_more", false)
            put("blocks", buildJsonArray {
                add(buildJsonObject {
                    put("block_id", "b1")
                    put("block_ref", FeishuDocRefs.encodeBlock("doc-1", "b1"))
                    put("block_type", 2)
                    put("text", "partial")
                    put("text_truncated", true)
                })
            })
        }

        val result = FeishuDocsMarkdownPack.fromBlocksPayload(payload)

        assertTrue(result["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            "source_blocks_text_truncated",
            result["limitations"]!!.jsonArray.first().jsonPrimitive.content,
        )
    }

    private fun block(
        id: String,
        blockType: Int,
        text: String,
        headingLevel: Int? = null,
    ) = FeishuDocBlock(
        blockId = id,
        parentId = null,
        blockType = blockType,
        text = text,
        headingLevel = headingLevel,
        childrenCount = 0,
        raw = JsonObject(emptyMap()),
    )
}
