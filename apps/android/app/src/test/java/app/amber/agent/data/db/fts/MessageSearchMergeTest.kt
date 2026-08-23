package app.amber.agent.data.db.fts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * P8-04：搜索会话标题和消息正文。
 *
 * - 合并/去重逻辑是纯函数 [mergeMessageSearchResults]，直接单测。
 * - 已删除/重命名会话的标题索引同步、以及 CJK 分词配置是 SQL/原生层，
 *   JVM 单测无法执行 libsimple.so（jieba 扩展），用源码断言如实记录接入点。
 */
class MessageSearchMergeTest {

    private fun bodyHit(
        conversationId: String,
        nodeId: String = "node-$conversationId",
        updateAt: Instant = Instant.EPOCH,
    ) = MessageSearchResult(
        nodeId = nodeId,
        messageId = "msg-$conversationId",
        conversationId = conversationId,
        assistantId = "assistant-1",
        title = "title-$conversationId",
        updateAt = updateAt,
        snippet = "[hit] body snippet",
        hitSource = SearchHitSource.BODY,
    )

    private fun titleHit(
        conversationId: String,
        updateAt: Instant = Instant.EPOCH,
    ) = MessageSearchResult(
        nodeId = null,
        messageId = null,
        conversationId = conversationId,
        assistantId = "assistant-1",
        title = "title-$conversationId",
        updateAt = updateAt,
        snippet = "[hit] title snippet",
        hitSource = SearchHitSource.TITLE,
    )

    @Test
    fun `only title hit keeps title entry with null node and message ids`() {
        val titleOnly = titleHit("conv-1")

        val merged = mergeMessageSearchResults(bodyHits = emptyList(), titleHits = listOf(titleOnly))

        assertEquals(1, merged.size)
        assertEquals("conv-1", merged[0].conversationId)
        assertEquals(SearchHitSource.TITLE, merged[0].hitSource)
        assertTrue(merged[0].titleMatched)
        assertNull(merged[0].nodeId)
        assertNull(merged[0].messageId)
    }

    @Test
    fun `title and body both hit dedupes to single body entry`() {
        val body = bodyHit("conv-1")
        val title = titleHit("conv-1")

        val merged = mergeMessageSearchResults(bodyHits = listOf(body), titleHits = listOf(title))

        assertEquals(1, merged.size)
        assertEquals(SearchHitSource.BODY, merged[0].hitSource)
        assertTrue(merged[0].titleMatched)
        assertEquals("node-conv-1", merged[0].nodeId)
    }

    @Test
    fun `body hits keep their order and title hits are appended after`() {
        val bodyFirst = bodyHit("conv-1", updateAt = Instant.parse("2026-01-01T00:00:00Z"))
        val bodySecond = bodyHit("conv-2", updateAt = Instant.parse("2026-02-01T00:00:00Z"))
        val titleOnly = titleHit("conv-3", updateAt = Instant.parse("2026-03-01T00:00:00Z"))

        val merged = mergeMessageSearchResults(
            bodyHits = listOf(bodyFirst, bodySecond),
            titleHits = listOf(titleOnly),
        )

        assertEquals(listOf("conv-1", "conv-2", "conv-3"), merged.map { it.conversationId })
        assertEquals(
            listOf(SearchHitSource.BODY, SearchHitSource.BODY, SearchHitSource.TITLE),
            merged.map { it.hitSource },
        )
        assertEquals(listOf(false, false, true), merged.map { it.titleMatched })
    }

    @Test
    fun `dedupe only drops title entries of conversations already hit in body`() {
        val body = bodyHit("conv-1")
        val titleHitForBody = titleHit("conv-1")
        val titleOnly = titleHit("conv-9")

        val merged = mergeMessageSearchResults(
            bodyHits = listOf(body),
            titleHits = listOf(titleHitForBody, titleOnly),
        )

        assertEquals(listOf("conv-1", "conv-9"), merged.map { it.conversationId })
    }

    // ---- 已删除 / 重命名会话的标题索引同步（SQL 层，源码断言） ----

    @Test
    fun `deleted conversation removes its title fts row and query joins live entity`() {
        val source = repoFile("src/main/java/app/amber/agent/data/db/fts/MessageFtsManager.kt").readText()

        // 删除路径必须清掉标题行
        assertTrue(source.contains("DELETE FROM conversation_title_fts WHERE conversation_id = ?"))
        // 标题查询 JOIN 会话实体——实体已删除的行即使残留也不会返回（与正文查询同策略）
        assertTrue(source.contains("FROM conversation_title_fts"))
        assertTrue(source.contains("JOIN ConversationEntity c ON c.id = conversation_title_fts.conversation_id"))
    }

    @Test
    fun `renamed conversation resyncs title fts row`() {
        val source = repoFile("src/main/java/app/amber/agent/data/db/fts/MessageFtsManager.kt").readText()

        // 重命名路径同步标题表：先删旧行再插新行，保证每会话恰好一行
        // （FTS5 无唯一约束，INSERT OR IGNORE 会产生重复行，故用 DELETE+INSERT）。
        val renameSection = source.substringAfter("UPDATE message_fts SET title = ?, update_at = ?")
        assertTrue("重命名应删除旧标题行", renameSection.contains("DELETE FROM conversation_title_fts WHERE conversation_id = ?"))
        assertTrue("重命名应插入新标题行", renameSection.contains("INSERT INTO conversation_title_fts(title, conversation_id, update_at) VALUES (?, ?, ?)"))
    }

    // ---- CJK 分词：现有 FTS 配置（libsimple.so + jieba 词典）支持 CJK ----

    @Test
    fun `title query uses same jieba tokenizer as body query`() {
        val source = repoFile("src/main/java/app/amber/agent/data/db/fts/MessageFtsManager.kt").readText()
        val module = repoFile("src/main/java/app/amber/core/di/DataSourceModule.kt").readText()

        assertTrue(source.contains("WHERE title MATCH jieba_query(?)"))
        assertTrue(source.contains("WHERE text MATCH jieba_query(?)"))
        // 标题表与正文表同 tokenizer（simple + jieba 词典），CJK 分词行为一致
        assertTrue(module.contains("CREATE VIRTUAL TABLE IF NOT EXISTS conversation_title_fts USING fts5("))
        assertTrue(module.contains("tokenize = 'simple'"))
    }

    @Test
    fun `jieba dictionary contains CJK entries so title tokenization supports chinese`() {
        val dict = repoFile("src/main/assets/simple_dict/jieba.dict.utf8")
        assertTrue("jieba 词典应存在", dict.isFile)
        val sample = dict.readText(Charsets.UTF_8).take(2_000)
        assertTrue("jieba 词典应包含 CJK 词条（如 一/区块链）", sample.any { it.code > 0x4E00 })
    }

    private fun repoFile(pathInAppModule: String): File {
        return listOf(
            File(pathInAppModule),
            File("app/$pathInAppModule"),
        ).firstOrNull { it.isFile }
            ?: error("Cannot locate $pathInAppModule")
    }
}
