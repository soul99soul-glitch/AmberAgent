package app.amber.feature.home

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.entity.ConversationDraftEntity
import app.amber.agent.data.db.entity.ConversationEntity
import app.amber.agent.data.db.entity.DeepReadCacheEntity
import app.amber.core.utils.JsonInstant
import app.amber.feature.board.hotlist.deepread.DeepReadGenerationPhase
import app.amber.feature.board.hotlist.deepread.DeepReadGenerationStage
import app.amber.feature.board.hotlist.deepread.DeepReadOutput
import app.amber.feature.board.hotlist.deepread.DeepReadSectionState
import app.amber.feature.board.hotlist.deepread.DeepReadSectionStatus
import app.amber.feature.modelcouncil.CouncilRoom
import app.amber.feature.modelcouncil.CouncilRoomStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * P8-08 验收测试（Room 持久投影层）：
 * - Council：非终态房间（INTERRUPTED/IDLE）成为候选，终态（FINALIZED）不出现；
 * - DeepRead：未完成且有进度的深度阅读成为候选，完成/过期/删除后消失；
 * - MiniApp 草稿：所属会话存在时成为候选，发送（删除）或会话删除后消失；
 * - dismiss 记录：到期前过滤、到期后恢复。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ContinueCandidateRoomSourcesTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    private val now = Instant.parse("2026-08-01T00:00:00Z")
    private val nowMs = now.toEpochMilli()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertConversation(
        id: String,
        councilState: String? = null,
    ) {
        db.conversationDao().insert(
            ConversationEntity(
                id = id,
                assistantId = "assistant-test",
                title = "会话 $id",
                nodes = "[]",
                createAt = nowMs,
                updateAt = nowMs,
                chatSuggestions = "[]",
                isPinned = false,
                councilState = councilState,
            )
        )
    }

    private fun councilRoom(status: CouncilRoomStatus): CouncilRoom = CouncilRoom(
        id = "room-$status",
        conversationId = Uuid.random(),
        hostAssistantId = Uuid.random(),
        objective = "讨论某个议题",
        status = status,
        createdAtMs = nowMs,
        updatedAtMs = nowMs,
    )

    // ------------------------------------------------------------- Council ---

    @Test
    fun `interrupted council room is a FAILED_RESUMABLE candidate routed to the room`() = runTest {
        val room = councilRoom(CouncilRoomStatus.INTERRUPTED)
        insertConversation(room.conversationId.toString(), JsonInstant.encodeToString(CouncilRoom.serializer(), room))
        val source = CouncilContinueSource(conversationDao = db.conversationDao())

        val result = source.observe().first()

        assertEquals(1, result.size)
        val candidate = result.single()
        assertEquals(ContinueSourceKind.COUNCIL, candidate.sourceKind)
        assertEquals(ContinueStatus.FAILED_RESUMABLE, candidate.status)
        assertEquals(ContinueRoute.CouncilRoom(conversationId = room.conversationId.toString()), candidate.route)
        assertEquals("讨论某个议题", candidate.title)
    }

    @Test
    fun `idle council room is WAITING_USER and terminal room never appears`() = runTest {
        val idle = councilRoom(CouncilRoomStatus.IDLE)
        val finished = councilRoom(CouncilRoomStatus.FINALIZED)
        insertConversation(idle.conversationId.toString(), JsonInstant.encodeToString(CouncilRoom.serializer(), idle))
        insertConversation(finished.conversationId.toString(), JsonInstant.encodeToString(CouncilRoom.serializer(), finished))
        val source = CouncilContinueSource(conversationDao = db.conversationDao())

        val result = source.observe().first()

        assertEquals(1, result.size)
        assertEquals(ContinueStatus.WAITING_USER, result.single().status)
    }

    @Test
    fun `council candidate disappears when conversation is deleted`() = runTest {
        val room = councilRoom(CouncilRoomStatus.INTERRUPTED)
        val conversationId = room.conversationId.toString()
        insertConversation(conversationId, JsonInstant.encodeToString(CouncilRoom.serializer(), room))
        val source = CouncilContinueSource(conversationDao = db.conversationDao())
        assertEquals(1, source.observe().first().size)

        db.conversationDao().deleteById(conversationId)

        assertEquals(0, source.observe().first().size)
    }

    // ------------------------------------------------------------ DeepRead ---

    private suspend fun insertDeepRead(
        topicId: String,
        output: DeepReadOutput,
        expiresAt: Long = nowMs + 86_400_000L,
        title: String = "话题 $topicId",
    ) {
        db.hotListDao().upsertDeepRead(
            DeepReadCacheEntity(
                topicId = topicId,
                title = title,
                outputJson = JsonInstant.encodeToString(DeepReadOutput.serializer(), output),
                createdAt = nowMs,
                expiresAt = expiresAt,
                updatedAt = nowMs,
            )
        )
    }

    private fun partialOutput(): DeepReadOutput = DeepReadOutput(
        generationPhase = DeepReadGenerationPhase.IDLE,
        generationComplete = false,
        sectionStates = mapOf(
            DeepReadGenerationStage.OVERVIEW to DeepReadSectionState(DeepReadSectionStatus.READY),
            DeepReadGenerationStage.NARRATIVE to DeepReadSectionState(),
        ),
    )

    @Test
    fun `incomplete deep read with progress is a FAILED_RESUMABLE candidate`() = runTest {
        insertDeepRead(topicId = "t1", output = partialOutput())
        val source = DeepReadContinueSource(hotListDao = db.hotListDao()) { now }

        val result = source.observe().first()

        assertEquals(1, result.size)
        val candidate = result.single()
        assertEquals(ContinueSourceKind.DEEP_READ, candidate.sourceKind)
        assertEquals(ContinueStatus.FAILED_RESUMABLE, candidate.status)
        assertEquals(ContinueRoute.DeepRead(topicId = "t1", title = "话题 t1"), candidate.route)
        assertTrue(candidate.summary.contains("1/4"))
    }

    @Test
    fun `complete expired or deleted deep reads never appear`() = runTest {
        val complete = DeepReadOutput(
            generationPhase = DeepReadGenerationPhase.COMPLETE,
            generationComplete = true,
            sectionStates = DeepReadGenerationStage.entries.associateWith {
                DeepReadSectionState(DeepReadSectionStatus.READY)
            },
        )
        insertDeepRead(topicId = "done", output = complete)
        insertDeepRead(topicId = "expired", output = partialOutput(), expiresAt = nowMs - 1)
        insertDeepRead(topicId = "deleted", output = partialOutput())
        val source = DeepReadContinueSource(hotListDao = db.hotListDao()) { now }

        assertEquals(1, source.observe().first().size)

        db.hotListDao().deleteDeepRead("deleted")

        assertEquals(0, source.observe().first().size)
    }

    // ---------------------------------------------------------- MiniApp draft ---

    @Test
    fun `mini app draft with existing conversation is a DRAFT candidate routed to chat`() = runTest {
        insertConversation("conv-draft-1")
        db.conversationDraftDao().upsert(
            ConversationDraftEntity(
                conversationId = "conv-draft-1",
                draftId = "draft-1",
                text = "这是小应用生成的草稿内容",
                attachmentsJson = "[]",
                updatedAtMs = nowMs,
            )
        )
        val source = MiniAppDraftContinueSource(draftDao = db.conversationDraftDao())

        val result = source.observe().first()

        assertEquals(1, result.size)
        val candidate = result.single()
        assertEquals(ContinueSourceKind.MINIAPP_DRAFT, candidate.sourceKind)
        assertEquals(ContinueStatus.DRAFT, candidate.status)
        assertEquals(ContinueRoute.Chat(conversationId = "conv-draft-1"), candidate.route)
        assertEquals("这是小应用生成的草稿内容", candidate.summary)
    }

    @Test
    fun `draft disappears after send (deleted) or when its conversation is deleted`() = runTest {
        insertConversation("conv-draft-2")
        db.conversationDraftDao().upsert(
            ConversationDraftEntity(
                conversationId = "conv-draft-2",
                draftId = "draft-2",
                text = "草稿",
                attachmentsJson = "[]",
                updatedAtMs = nowMs,
            )
        )
        val source = MiniAppDraftContinueSource(draftDao = db.conversationDraftDao())
        assertEquals(1, source.observe().first().size)

        // 发送后 ChatVM 清空草稿
        db.conversationDraftDao().delete("conv-draft-2")
        assertEquals(0, source.observe().first().size)

        // 会话被删除后草稿也不再出现
        insertConversation("conv-draft-3")
        db.conversationDraftDao().upsert(
            ConversationDraftEntity(
                conversationId = "conv-draft-3",
                draftId = "draft-3",
                text = "草稿三",
                attachmentsJson = "[]",
                updatedAtMs = nowMs,
            )
        )
        assertEquals(1, source.observe().first().size)
        db.conversationDao().deleteById("conv-draft-3")
        assertEquals(0, source.observe().first().size)
    }

    // -------------------------------------------------------------- dismiss ---

    @Test
    fun `dismiss record hides candidate until expiry then restores`() = runTest {
        val store = RoomContinueDismissStore(dao = db.continueCandidateDismissDao())
        store.dismiss(ContinueSourceKind.COUNCIL, "room-1", until = now.plusSeconds(60))
        val source = CouncilContinueSource(conversationDao = db.conversationDao())

        // 模拟已存在的房间（直接插入持久化）
        val room = councilRoom(CouncilRoomStatus.INTERRUPTED).copy(conversationId = Uuid.random())
        insertConversation(room.conversationId.toString(), JsonInstant.encodeToString(CouncilRoom.serializer(), room))
        val aggregator = ContinueCandidateAggregator(listOf(source), store) { now }
        val before = aggregator.observe().first()
        // 房间未隐藏 → 出现
        assertEquals(1, before.size)

        store.dismiss(ContinueSourceKind.COUNCIL, room.conversationId.toString(), until = now.plusSeconds(60))
        assertEquals(0, aggregator.observe().first().size)

        // 到期后恢复
        val afterExpiry = ContinueCandidateAggregator(listOf(source), store) { now.plusSeconds(61) }
        assertEquals(1, afterExpiry.observe().first().size)
    }
}
