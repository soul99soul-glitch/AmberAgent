package app.amber.core.storage.conversation

import app.amber.ai.ui.UIMessage
import app.amber.ai.core.MessageRole
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class JsonConversationStorageTest {

    private lateinit var tempDir: ConversationFile
    private lateinit var storage: JsonConversationStorage

    @BeforeTest
    fun setup() {
        // jvmMain target：用 JVM 临时目录做真实文件 IO（jvmMain actual = java.io.File）。
        // commonTest 在 JVM 上执行时实际跑的就是 jvmMain 的 actual。
        val tmp = kotlin.io.path.createTempDirectory(prefix = "conv-storage-test").toFile()
        tempDir = ConversationFile(tmp.absolutePath)
        storage = JsonConversationStorage(tempDir)
    }

    @AfterTest
    fun teardown() {
        tempDir.delete()
    }

    @Test
    fun emptyStorageReturnsEmptySummaryList() = runTest {
        assertEquals(emptyList(), storage.listSummaries())
    }

    @Test
    fun saveAndLoadRoundTripsConversationWithMessages() = runTest {
        val conv = sampleConversation(
            id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
            title = "hello",
            userText = "hi",
        )
        storage.saveConversation(conv)

        val loaded = storage.loadConversation(conv.id)
        assertNotNull(loaded)
        assertEquals(conv.id, loaded.id)
        assertEquals("hello", loaded.title)
        assertEquals(1, loaded.messageNodes.size)
        // 通过 UIMessage.toText() 校验文本内容，避免直接依赖 UIMessagePart 抽象——
        // 后者在 commonTest sourceSet 解析不可靠（api 透传链在该 sourceSet 上不稳）。
        assertEquals("hi", loaded.currentMessages.first().toText())
    }

    @Test
    fun saveAppearsInSummariesWithDerivedFields() = runTest {
        val conv = sampleConversation(
            id = Uuid.parse("00000000-0000-0000-0000-000000000002"),
            title = "list test",
            userText = "anything",
            isPinned = true,
        )
        storage.saveConversation(conv)

        val summaries = storage.listSummaries()
        assertEquals(1, summaries.size)
        val s = summaries.first()
        assertEquals(conv.id, s.id)
        assertEquals("list test", s.title)
        assertTrue(s.isPinned)
        assertEquals(1, s.messageCount)
    }

    @Test
    fun summariesAreOrderedPinnedFirstThenUpdateAtDesc() = runTest {
        val older = sampleConversation(
            id = Uuid.parse("00000000-0000-0000-0000-000000000010"),
            title = "older pinned",
            isPinned = true,
        )
        val newer = sampleConversation(
            id = Uuid.parse("00000000-0000-0000-0000-000000000011"),
            title = "newer unpinned",
            isPinned = false,
        )
        // 保存顺序：先 older 再 newer。newer.updateAt 更晚。
        storage.saveConversation(older)
        // 给 newer 一个更晚的 updateAt，确保排序依赖真实时间而不是插入顺序。
        val newerLater = newer.copy(updateAt = newer.updateAt)
        storage.saveConversation(newerLater)

        val order = storage.listSummaries().map { it.title }
        // 置顶（older）必须排第一，即使它更新更早。
        assertEquals(listOf("older pinned", "newer unpinned"), order)
    }

    @Test
    fun saveUpsertsExistingConversation() = runTest {
        val id = Uuid.parse("00000000-0000-0000-0000-000000000020")
        val first = sampleConversation(id = id, title = "v1", userText = "first")
        storage.saveConversation(first)

        val second = first.copy(title = "v2")
        storage.saveConversation(second)

        val summaries = storage.listSummaries()
        assertEquals(1, summaries.size, "upsert 不应新增列表条目")
        assertEquals("v2", summaries.first().title)
    }

    @Test
    fun deleteRemovesConversationAndIndexEntry() = runTest {
        val id = Uuid.parse("00000000-0000-0000-0000-000000000030")
        storage.saveConversation(sampleConversation(id = id, title = "to delete"))
        assertEquals(1, storage.listSummaries().size)

        storage.deleteConversation(id)
        assertNull(storage.loadConversation(id))
        assertEquals(0, storage.listSummaries().size)
    }

    @Test
    fun deleteNonexistentIsNoop() = runTest {
        storage.deleteConversation(Uuid.random())
        assertEquals(0, storage.listSummaries().size)
    }

    @Test
    fun loadNonexistentReturnsNull() = runTest {
        assertNull(storage.loadConversation(Uuid.random()))
    }

    @Test
    fun updateMetadataChangesTitleAndPinWithoutTouchingMessages() = runTest {
        val id = Uuid.parse("00000000-0000-0000-0000-000000000040")
        storage.saveConversation(
            sampleConversation(id = id, title = "orig", userText = "kept", isPinned = false)
        )

        storage.updateMetadata(id, title = "renamed", isPinned = true)

        val loaded = storage.loadConversation(id)
        assertNotNull(loaded)
        assertEquals("renamed", loaded.title)
        assertTrue(loaded.isPinned)
        assertEquals(1, loaded.messageNodes.size, "消息节点不应被 metadata 更新改动")
    }

    @Test
    fun updateMetadataNullArgsPreserveOriginalValues() = runTest {
        val id = Uuid.parse("00000000-0000-0000-0000-000000000041")
        storage.saveConversation(
            sampleConversation(id = id, title = "keep-title", isPinned = false)
        )
        storage.updateMetadata(id)  // 两个参数都 null

        val loaded = storage.loadConversation(id)
        assertNotNull(loaded)
        assertEquals("keep-title", loaded.title)
        assertFalse(loaded.isPinned)
    }

    @Test
    fun updateMetadataNonexistentIsNoop() = runTest {
        storage.updateMetadata(Uuid.random(), title = "x")
        assertEquals(0, storage.listSummaries().size)
    }

    @Test
    fun indexCorruptionIsRebuiltFromConversationFiles() = runTest {
        // 保存两条，再人为破坏 index.json。
        val idA = Uuid.parse("00000000-0000-0000-0000-000000000050")
        val idB = Uuid.parse("00000000-0000-0000-0000-000000000051")
        storage.saveConversation(sampleConversation(id = idA, title = "A"))
        storage.saveConversation(sampleConversation(id = idB, title = "B"))
        assertTrue(tempDir.child("index.json").exists())

        // 破坏 index。
        tempDir.child("index.json").writeText("{ this is not valid json")

        // listSummaries 必须能从 {id}.json 重建，而不是崩溃或返回空。
        val rebuilt = storage.listSummaries()
        assertEquals(2, rebuilt.size)
        val titles = rebuilt.map { it.title }.sorted()
        assertEquals(listOf("A", "B"), titles)
    }

    @Test
    fun loadSkipsCorruptConversationFileGracefully() = runTest {
        val id = Uuid.parse("00000000-0000-0000-0000-000000000060")
        storage.saveConversation(sampleConversation(id = id, title = "real"))
        // 人为破坏 {id}.json。
        tempDir.child("${id}.json").writeText("{ broken")

        assertNull(storage.loadConversation(id), "损坏文件应返回 null，不抛异常")
    }

    // ---- 构造助手 ----

    /**
     * 独立 round-trip ConversationSummary（不经过 storage 层），专门盯 Instant codec：
     * 若 kotlin.time.Instant 与 InstantSerializer(KSerializer<kotlinx.datetime.Instant>)
     * 之间出现对称编解码 bug，listSummaries 路径（自写自读）发现不了，只有独立编解码能抓到。
     * 用非默认 Instant 值，避免「巧合相等」掩盖问题。
     */
    @Test
    fun conversationSummaryRoundTripsThroughJsonWithExplicitInstant() {
        val fixedInstant = Instant.parse("2024-03-15T10:30:45.123Z")
        val original = ConversationSummary(
            id = Uuid.parse("11111111-2222-3333-4444-555555555555"),
            title = "summary codec probe",
            assistantId = app.amber.core.model.DEFAULT_ASSISTANT_ID,
            createAt = fixedInstant,
            updateAt = fixedInstant,
            isPinned = true,
            messageCount = 42,
        )

        val json = JsonInstant.encodeToString(original)
        val decoded = JsonInstant.decodeFromString<ConversationSummary>(json)

        assertEquals(original, decoded)
        // 显式断言 Instant 字段被正确编解码（ISO 字符串往返），而非仅靠 data class equals
        // ——equals 通过了就说明 codec 自洽，但分开写让失败信息更清晰。
        assertEquals(fixedInstant, decoded.createAt)
        assertEquals(fixedInstant, decoded.updateAt)
        assertEquals(42, decoded.messageCount)
        // JSON 中应出现 ISO 字符串形式，而非某种结构体/object——这是 InstantSerializer 的契约。
        assertTrue(json.contains("2024-03-15T10:30:45.123Z"), "Instant 必须序列化为 ISO 字符串: $json")
    }

    private fun sampleConversation(
        id: Uuid,
        title: String = "test",
        userText: String = "hello",
        isPinned: Boolean = false,
    ): Conversation {
        // 用 UIMessage.companion.user 工厂构造，避免直接依赖 UIMessagePart 抽象——
        // 后者在 commonTest sourceSet 上解析不稳定（api 透传链对该 sourceSet 不保证可见）。
        val userMsg = UIMessage.user(prompt = userText)
        val node = MessageNode(messages = listOf(userMsg), selectIndex = 0)
        return Conversation(
            id = id,
            assistantId = app.amber.core.model.DEFAULT_ASSISTANT_ID,
            title = title,
            messageNodes = listOf(node),
            isPinned = isPinned,
        )
    }
}
