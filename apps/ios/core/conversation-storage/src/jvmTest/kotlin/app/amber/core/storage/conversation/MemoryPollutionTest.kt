package app.amber.core.storage.conversation

import app.amber.ai.ui.UIMessage
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.model.Conversation
import app.amber.core.model.ConversationMemoryMode
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
import kotlin.uuid.Uuid

/**
 * P2-a：会话记忆三态（ENABLED/DISABLED/POLLUTED）的序列化与存储契约。
 *
 * - 旧 JSON（无 memoryMode 字段）解码为 ENABLED，用户数据无损升级。
 * - 三态序列化往返稳定（wireName 小写蛇形）。
 * - JsonConversationStorage save/load 保留该字段；消息写入（旧快照）不得把
 *   POLLUTED 回写降级为 ENABLED——memoryMode 由 harness/用户显式写者拥有。
 * - updateMemoryMode：harness 置 POLLUTED 只升不降（幂等）；用户复位
 *   POLLUTED→ENABLED 是唯一允许的降级路径。
 */
class MemoryPollutionTest {

    private lateinit var tempDir: ConversationFile
    private lateinit var storage: JsonConversationStorage

    @BeforeTest
    fun setup() {
        val tmp = kotlin.io.path.createTempDirectory(prefix = "memory-pollution-test").toFile()
        tempDir = ConversationFile(tmp.absolutePath)
        storage = JsonConversationStorage(tempDir)
    }

    @AfterTest
    fun teardown() {
        tempDir.delete()
    }

    private fun sampleConversation(id: Uuid, memoryMode: ConversationMemoryMode = ConversationMemoryMode.ENABLED): Conversation {
        val userMsg = UIMessage.user(prompt = "hello")
        val node = MessageNode(messages = listOf(userMsg), selectIndex = 0)
        return Conversation(
            id = id,
            assistantId = app.amber.core.model.DEFAULT_ASSISTANT_ID,
            title = "test",
            messageNodes = listOf(node),
            memoryMode = memoryMode,
        )
    }

    @Test
    fun oldJsonWithoutMemoryModeDecodesAsEnabled() {
        val id = "00000000-0000-0000-0000-000000000001"
        // 旧版会话 JSON：无 memoryMode 字段。
        val oldJson = """
            {
              "id": "$id",
              "assistantId": "${app.amber.core.model.DEFAULT_ASSISTANT_ID}",
              "title": "legacy",
              "messageNodes": [],
              "chatSuggestions": [],
              "isPinned": false,
              "autoApproveToolCalls": false,
              "createAt": "2026-01-01T00:00:00Z",
              "updateAt": "2026-01-01T00:00:00Z",
              "newConversation": false
            }
        """.trimIndent()

        val decoded = JsonInstant.decodeFromString<Conversation>(oldJson)
        assertEquals(ConversationMemoryMode.ENABLED, decoded.memoryMode)
    }

    @Test
    fun memoryModeRoundTripsThroughSerialization() {
        val wireNames = mapOf(
            ConversationMemoryMode.ENABLED to "enabled",
            ConversationMemoryMode.DISABLED to "disabled",
            ConversationMemoryMode.POLLUTED to "polluted",
        )

        for ((mode, wireName) in wireNames) {
            val json = JsonInstant.encodeToString(Conversation.serializer(), sampleConversation(
                id = Uuid.parse("00000000-0000-0000-0000-000000000002"),
                memoryMode = mode,
            ))
            val decoded = JsonInstant.decodeFromString(Conversation.serializer(), json)
            assertEquals(wireName, mode.wireName)
            assertTrue(json.contains("\"memoryMode\":\"$wireName\""), "wire name 必须稳定: $json")
            assertEquals(mode, decoded.memoryMode)
        }
    }

    @Test
    fun updateMemoryModeStateTransitionsAreGuarded() = runTest {
        val id = Uuid.parse("00000000-0000-0000-0000-000000000004")
        storage.saveConversation(sampleConversation(id))

        assertTrue(storage.updateMemoryMode(id, ConversationMemoryMode.DISABLED))
        assertEquals(ConversationMemoryMode.DISABLED, storage.loadConversation(id)?.memoryMode)
        assertTrue(storage.updateMemoryMode(id, ConversationMemoryMode.ENABLED))
        assertEquals(ConversationMemoryMode.ENABLED, storage.loadConversation(id)?.memoryMode)
        assertTrue(storage.updateMemoryMode(id, ConversationMemoryMode.DISABLED))
        assertTrue(storage.updateMemoryMode(id, ConversationMemoryMode.POLLUTED))
        assertEquals(ConversationMemoryMode.POLLUTED, storage.loadConversation(id)?.memoryMode)

        // POLLUTED 的唯一出口是显式复位到 ENABLED；关闭记忆不能掩盖污染状态。
        assertFalse(storage.updateMemoryMode(id, ConversationMemoryMode.DISABLED))
        assertEquals(ConversationMemoryMode.POLLUTED, storage.loadConversation(id)?.memoryMode)

        val missing = Uuid.parse("00000000-0000-0000-0000-000000000007")
        assertFalse(storage.updateMemoryMode(missing, ConversationMemoryMode.POLLUTED))
        assertNull(storage.loadConversation(missing))
        // 幂等：重复置位保持 POLLUTED，不报错、不降级。
        assertTrue(storage.updateMemoryMode(id, ConversationMemoryMode.POLLUTED))
        assertEquals(
            ConversationMemoryMode.POLLUTED,
            storage.loadConversation(id)?.memoryMode,
        )
    }

    @Test
    fun oldMessageSnapshotCannotRollBackPolluted() = runTest {
        val id = Uuid.parse("00000000-0000-0000-0000-000000000005")
        storage.saveConversation(sampleConversation(id))
        assertTrue(storage.updateMemoryMode(id, ConversationMemoryMode.POLLUTED))

        // 旧消息快照（内存里仍是默认 ENABLED）回写：POLLUTED 必须被保留。
        val staleSnapshot = sampleConversation(id) // memoryMode 默认 ENABLED
        storage.saveConversation(staleSnapshot)
        assertEquals(
            ConversationMemoryMode.POLLUTED,
            storage.loadConversation(id)?.memoryMode,
        )
    }

    @Test
    fun updateMemoryModeResetAllowsPollutedToEnabled() = runTest {
        val id = Uuid.parse("00000000-0000-0000-0000-000000000006")
        storage.saveConversation(sampleConversation(id))
        assertTrue(storage.updateMemoryMode(id, ConversationMemoryMode.POLLUTED))

        // 用户手动复位是唯一允许的降级路径。
        assertTrue(storage.updateMemoryMode(id, ConversationMemoryMode.ENABLED))
        assertEquals(
            ConversationMemoryMode.ENABLED,
            storage.loadConversation(id)?.memoryMode,
        )
    }

    @Test
    fun saveAndSummaryCarryMemoryModeThroughStorage() = runTest {
        val id = Uuid.parse("00000000-0000-0000-0000-000000000008")
        storage.saveConversation(sampleConversation(id, memoryMode = ConversationMemoryMode.POLLUTED))

        val loaded = storage.loadConversation(id)
        assertNotNull(loaded)
        assertEquals(ConversationMemoryMode.POLLUTED, loaded.memoryMode)

        val summaries = storage.listSummaries()
        val summary = summaries.firstOrNull { it.id == id }
        assertNotNull(summary)
        assertEquals(ConversationMemoryMode.POLLUTED, summary.memoryMode)
    }
}
