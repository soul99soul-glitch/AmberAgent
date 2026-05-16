package me.rerere.rikkahub.data.agent.webmount.adapters.feishudocs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FeishuDocRefsTest {
    @Test
    fun encodesAndDecodesDocRefs() {
        val encoded = FeishuDocRefs.encode(
            FeishuDocRef(
                documentId = "AbCdEf123",
                docType = "docx",
                sourceUrl = "https://xiaomi.feishu.cn/docx/AbCdEf123",
            )
        )

        val decoded = FeishuDocRefs.decode(encoded)

        assertTrue(encoded.startsWith("fdc_"))
        assertNotNull(decoded)
        assertEquals("AbCdEf123", decoded!!.documentId)
        assertEquals("docx", decoded.docType)
        assertEquals("https://xiaomi.feishu.cn/docx/AbCdEf123", decoded.sourceUrl)
    }

    @Test
    fun extractsDocRefFromFeishuUrl() {
        val ref = FeishuDocRefs.fromUrl("https://xiaomi.feishu.cn/docx/AbCdEf123?from=from_copylink")

        assertNotNull(ref)
        assertEquals("AbCdEf123", ref!!.documentId)
        assertEquals("docx", ref.docType)
    }

    @Test
    fun encodesAndDecodesBlockRefs() {
        val encoded = FeishuDocRefs.encodeBlock("doc-1", "block-2")
        val decoded = FeishuDocRefs.decodeBlock(encoded)

        assertTrue(encoded.startsWith("fdb_"))
        assertNotNull(decoded)
        assertEquals("doc-1", decoded!!.documentId)
        assertEquals("block-2", decoded.blockId)
    }

    @Test
    fun rejectsInvalidRefs() {
        assertNull(FeishuDocRefs.decode(null))
        assertNull(FeishuDocRefs.decode("fdc_not-base64"))
        assertNull(FeishuDocRefs.decodeBlock("fdb_not-base64"))
        assertNull(FeishuDocRefs.fromUrl("https://example.com/not-feishu"))
    }

    @Test
    fun toolsExposeStableDocAndBlockRefs() {
        val source = locateTools().readText()

        assertTrue(source.contains("name = \"feishu_docs_resolve\""))
        assertTrue(source.contains("name = \"feishu_docs_blocks\""))
        assertTrue(source.contains("put(\"doc_ref\""))
        assertTrue(source.contains("put(\"block_ref\""))
        assertTrue(source.contains("parent_block_ref"))
        assertTrue(source.contains("unsupportedDocRefJson"))
        assertTrue(source.contains("wm_open + wm_extract"))
    }

    private fun locateTools(): File {
        val candidates = listOf(
            File("src/main/java/me/rerere/rikkahub/data/agent/webmount/adapters/feishudocs/FeishuDocsTools.kt"),
            File("app/src/main/java/me/rerere/rikkahub/data/agent/webmount/adapters/feishudocs/FeishuDocsTools.kt"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Could not locate FeishuDocsTools.kt")
    }
}
