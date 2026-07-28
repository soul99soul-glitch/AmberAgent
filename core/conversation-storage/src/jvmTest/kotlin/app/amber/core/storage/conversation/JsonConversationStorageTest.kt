package app.amber.core.storage.conversation

import app.amber.ai.ui.UIMessage
import app.amber.ai.core.MessageRole
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
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
    fun bulkImportValidatesTheWholeBatchBeforeWritingAnyConversation() = runTest {
        val existingId = Uuid.parse("00000000-0000-0000-0000-000000000003")
        val importId = Uuid.parse("00000000-0000-0000-0000-000000000004")
        storage.saveConversation(sampleConversation(id = existingId, title = "existing"))
        val validImport = JsonInstant.encodeToString(
            sampleConversation(id = importId, title = "must not be written")
        )

        val failure = runCatching {
            storage.importConversations(listOf(validImport, "{ broken"))
        }.exceptionOrNull()

        assertTrue(failure is ConversationStorageException)
        assertNull(storage.loadConversation(importId))
        assertEquals("existing", storage.loadConversation(existingId)?.title)
        assertEquals(setOf(existingId), storage.listSummaries().mapTo(mutableSetOf()) { it.id })
    }

    @Test
    fun bulkImportOverwritesMatchingEntriesAndPreservesTheRestOfTheDataset() = runTest {
        val overwrittenId = Uuid.parse("00000000-0000-0000-0000-000000000005")
        val preservedId = Uuid.parse("00000000-0000-0000-0000-000000000006")
        storage.saveConversation(
            sampleConversation(id = overwrittenId, title = "local title", userText = "local message")
        )
        storage.saveConversation(
            sampleConversation(id = preservedId, title = "preserved", userText = "preserved message")
        )
        val backupConversation = sampleConversation(
            id = overwrittenId,
            title = "backup title",
            userText = "backup message",
            isPinned = true,
        )

        storage.importConversations(listOf(JsonInstant.encodeToString(backupConversation)))

        val overwritten = storage.loadConversation(overwrittenId)
        assertNotNull(overwritten)
        assertEquals("backup title", overwritten.title)
        assertEquals("backup message", overwritten.currentMessages.single().toText())
        assertTrue(overwritten.isPinned)
        assertEquals("preserved", storage.loadConversation(preservedId)?.title)
        assertEquals(
            setOf(overwrittenId, preservedId),
            storage.listSummaries().mapTo(mutableSetOf()) { it.id },
        )
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

        val second = sampleConversation(id = id, title = "v2", userText = "second")
        storage.saveConversation(second)

        val summaries = storage.listSummaries()
        assertEquals(1, summaries.size, "upsert 不应新增列表条目")
        assertEquals("v1", summaries.first().title, "消息写入不拥有标题 metadata")
        assertEquals("second", storage.loadConversation(id)?.currentMessages?.first()?.toText())
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
    fun conditionalTitleUpdateDoesNotOverwriteNewerTitle() = runTest {
        val id = Uuid.parse("00000000-0000-0000-0000-000000000044")
        storage.saveConversation(
            sampleConversation(id = id, title = "generated baseline")
        )
        storage.updateMetadata(id, title = "user title")

        val didUpdate = storage.updateTitleIfCurrentTitleMatches(
            id = id,
            title = "late generated title",
            expectedTitle = "generated baseline",
        )

        assertFalse(didUpdate)
        assertEquals("user title", storage.loadConversation(id)?.title)
    }

    @Test
    fun saveConversationMessageWritePreservesExistingMetadataOwnerFields() = runTest {
        val id = Uuid.parse("00000000-0000-0000-0000-000000000043")
        storage.saveConversation(
            sampleConversation(
                id = id,
                title = "orig",
                userText = "old message",
                isPinned = false,
                chatSuggestions = listOf("kept suggestion"),
                autoApproveToolCalls = true,
            )
        )
        storage.updateMetadata(id, title = "renamed", isPinned = true)

        storage.saveConversation(
            sampleConversation(
                id = id,
                title = "stale title",
                userText = "new message",
                isPinned = false,
                chatSuggestions = listOf("stale suggestion"),
                autoApproveToolCalls = false,
            )
        )

        val loaded = storage.loadConversation(id)
        assertNotNull(loaded)
        assertEquals("new message", loaded.currentMessages.first().toText())
        assertEquals("renamed", loaded.title, "message 写入不得反向覆盖 metadata owner 的标题")
        assertTrue(loaded.isPinned, "message 写入不得反向覆盖 metadata owner 的置顶状态")
        assertEquals(
            listOf("kept suggestion"),
            loaded.chatSuggestions,
            "message 写入不得反向覆盖非消息 owner 的建议状态"
        )
        assertTrue(loaded.autoApproveToolCalls, "message 写入不得反向覆盖工具审批 owner 状态")
    }

    @Test
    fun updateMetadataSerializesAgainstConcurrentSaveWithoutOverwritingMessages() = runTest {
        val id = Uuid.parse("00000000-0000-0000-0000-000000000042")
        storage.saveConversation(
            sampleConversation(id = id, title = "orig", userText = "old message", isPinned = false)
        )

        val metadataSaveReached = CompletableDeferred<Unit>()
        val releaseMetadataSave = CompletableDeferred<Unit>()
        storage.beforeUpdateMetadataSaveForTesting = {
            metadataSaveReached.complete(Unit)
            releaseMetadataSave.await()
        }

        val renameTask = async {
            storage.updateMetadata(id, title = "renamed", isPinned = true)
        }
        metadataSaveReached.await()

        var concurrentSaveCompleted = false
        val saveTask = async {
            storage.saveConversation(
                sampleConversation(id = id, title = "new save", userText = "new message", isPinned = false)
            )
            concurrentSaveCompleted = true
        }

        withTimeoutOrNull(100) {
            saveTask.await()
        }
        assertFalse(
            concurrentSaveCompleted,
            "metadata 的读改写窗口内不应允许完整 save 穿插，否则旧 metadata 快照会覆盖新消息"
        )

        releaseMetadataSave.complete(Unit)
        renameTask.await()
        saveTask.await()
        storage.beforeUpdateMetadataSaveForTesting = null

        val loaded = storage.loadConversation(id)
        assertNotNull(loaded)
        assertEquals("new message", loaded.currentMessages.first().toText())
        assertEquals("renamed", loaded.title)
        assertTrue(loaded.isPinned)
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
    fun staleIndexEntryWithoutConversationFileIsFilteredAndRepaired() = runTest {
        val id = Uuid.parse("00000000-0000-0000-0000-000000000052")
        storage.saveConversation(sampleConversation(id = id, title = "deleted"))
        assertTrue(tempDir.child("${id}.json").delete())

        assertTrue(storage.listSummaries().none { it.id == id })
        assertFalse(tempDir.child("index.json").readText()?.contains(id.toString()) == true)
    }

    @Test
    fun validIndexRefreshesStaleSummaryForTheSameConversationId() = runTest {
        val id = Uuid.parse("00000000-0000-0000-0000-000000000055")
        storage.saveConversation(sampleConversation(id = id, title = "cached title"))
        tempDir.child("${id}.json").writeText(
            JsonInstant.encodeToString(sampleConversation(id = id, title = "disk title"))
        )

        val summaries = storage.listSummaries()
        val persistedIndex = JsonInstant.decodeFromString<List<ConversationSummary>>(
            tempDir.child("index.json").readText()!!
        )

        assertEquals("disk title", summaries.single().title)
        assertEquals("disk title", persistedIndex.single().title)
    }

    @Test
    fun validIndexDiscoversConversationFileLeftByInterruptedIndexWrite() = runTest {
        val indexedId = Uuid.parse("00000000-0000-0000-0000-000000000053")
        val orphanId = Uuid.parse("00000000-0000-0000-0000-000000000054")
        storage.saveConversation(sampleConversation(id = indexedId, title = "indexed"))
        tempDir.child("${orphanId}.json").writeText(
            JsonInstant.encodeToString(sampleConversation(id = orphanId, title = "orphan"))
        )

        val summaries = storage.listSummaries()

        assertEquals(setOf(indexedId, orphanId), summaries.mapTo(mutableSetOf()) { it.id })
        assertTrue(tempDir.child("index.json").readText()?.contains(orphanId.toString()) == true)
    }

    @Test
    fun validIndexPersistsRepairWhenStaleAndOrphanEntriesHaveEqualCounts() = runTest {
        val retainedId = Uuid.parse("00000000-0000-0000-0000-000000000056")
        val staleId = Uuid.parse("00000000-0000-0000-0000-000000000057")
        val orphanId = Uuid.parse("00000000-0000-0000-0000-000000000058")
        storage.saveConversation(sampleConversation(id = retainedId, title = "retained"))
        storage.saveConversation(sampleConversation(id = staleId, title = "stale"))
        assertTrue(tempDir.child("${staleId}.json").delete())
        tempDir.child("${orphanId}.json").writeText(
            JsonInstant.encodeToString(sampleConversation(id = orphanId, title = "orphan"))
        )

        assertEquals(
            setOf(retainedId, orphanId),
            storage.listSummaries().mapTo(mutableSetOf()) { it.id },
        )
        val persistedIndex = JsonInstant.decodeFromString<List<ConversationSummary>>(
            tempDir.child("index.json").readText()!!
        )
        assertEquals(setOf(retainedId, orphanId), persistedIndex.mapTo(mutableSetOf()) { it.id })
    }

    @Test
    fun loadSkipsCorruptConversationFileGracefully() = runTest {
        val id = Uuid.parse("00000000-0000-0000-0000-000000000060")
        storage.saveConversation(sampleConversation(id = id, title = "real"))
        // 人为破坏 {id}.json。
        tempDir.child("${id}.json").writeText("{ broken")

        assertNull(storage.loadConversation(id), "损坏文件应返回 null，不抛异常")
    }

    @Test
    fun atomicWriteReplacesContentWithoutLeakingTempFiles() = runTest {
        val file = tempDir.child("atomic.json")

        file.writeText("first")
        file.writeText("second")

        assertEquals("second", file.readText())
        val tempFiles = File(tempDir.path).listFiles { candidate ->
            candidate.name.startsWith("atomic.json") && candidate.name.endsWith(".tmp")
        }.orEmpty()
        assertTrue(tempFiles.isEmpty(), "atomic write tmp files must be cleaned up")
    }

    /**
     * P3+ 风险拦截（Surface A / 跨端 actual 行为分叉）：
     *
     * `ConversationFile.delete()` 的 KDoc 契约（ConversationFile.kt:23-26）是
     * "删除。不存在返回 false"。jvmMain actual = `File.delete()`，iOS actual =
     * `NSFileManager.removeItemAtPath`。两者对**不存在的路径**返回值一致（false），
     * 但对**非空目录**语义分叉：
     *   - JVM `File.delete()` 在非空目录上返回 false（不抛、不递归删）
     *   - iOS `removeItemAtPath` 会递归删除整个目录树并返回 true
     *
     * 本测试钉死 JVM 侧契约：删除一个**不存在**的文件必须返回 false。
     * 它是跨端一致性回归网的一部分——若未来 iOS actual 被改成"不存在返回 true"
     * 或 JVM actual 被改成"不存在返回 true"，与 KDoc 契约矛盾，本测试会失败。
     *
     * 注意：仅靠 jvmTest 无法直接断言 iOS actual 的行为（需 iosSimulatorArm64Test），
     * 但本测试至少保证 JVM 不偏离 KDoc 契约——这是跨端对齐的基线。
     */
    @Test
    fun deleteNonExistentFileReturnsFalsePerContract() {
        val ghost = tempDir.child("does-not-exist.json")
        assertFalse(ghost.exists(), "前置条件：文件确实不存在")
        // KDoc 契约：不存在返回 false。若 JVM actual 改成返回 true，此断言失败。
        assertFalse(ghost.delete(), "delete() 在不存在的路径上必须返回 false（见 ConversationFile.kt:25 契约）")
    }

    /**
     * 钉死「删除已存在的普通文件返回 true」契约——防止任何一端把 delete() 改成
     * 永远返回 false（静默吞删除），导致 JsonConversationStorage.deleteConversation()
     * (JsonConversationStorage.kt:71-75) 调用 delete() 后 removeFromIndex() 仍执行，
     * 造成 index 与文件不一致（文件还在但 index 里没了 → 幽灵会话）。
     */
    @Test
    fun deleteExistingFileReturnsTrueAndRemovesIt() {
        val f = tempDir.child("deletable.json")
        f.writeText("payload")
        assertTrue(f.exists())
        assertTrue(f.delete(), "delete() 删除已存在文件必须返回 true")
        assertFalse(f.exists(), "delete() 后文件必须不存在")
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
        chatSuggestions: List<String> = emptyList(),
        autoApproveToolCalls: Boolean = false,
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
            chatSuggestions = chatSuggestions,
            isPinned = isPinned,
            autoApproveToolCalls = autoApproveToolCalls,
        )
    }
}
