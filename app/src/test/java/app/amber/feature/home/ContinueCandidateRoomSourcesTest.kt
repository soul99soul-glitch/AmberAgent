package app.amber.feature.home

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.work.WorkInfo
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.db.entity.ConversationDraftEntity
import app.amber.agent.data.db.entity.ConversationEntity
import app.amber.agent.data.db.entity.DeepReadCacheEntity
import app.amber.agent.data.db.entity.MiniAppEntity
import app.amber.agent.data.db.entity.MiniAppVersionEntity
import app.amber.agent.data.db.entity.RunTerminalEntity
import app.amber.agent.data.db.entity.ToolEffectEntity
import app.amber.core.utils.JsonInstant
import app.amber.feature.board.hotlist.deepread.DeepReadGenerationPhase
import app.amber.feature.board.hotlist.deepread.DeepReadGenerationStage
import app.amber.feature.board.hotlist.deepread.DeepReadOutput
import app.amber.feature.board.hotlist.deepread.DeepReadSectionState
import app.amber.feature.board.hotlist.deepread.DeepReadSectionStatus
import app.amber.feature.modelcouncil.CouncilRoom
import app.amber.feature.modelcouncil.CouncilRoomStatus
import app.amber.feature.tools.ToolEffectClass
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
        val source = CouncilContinueSource(context = context, conversationDao = db.conversationDao())

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
        val source = CouncilContinueSource(context = context, conversationDao = db.conversationDao())

        val result = source.observe().first()

        assertEquals(1, result.size)
        assertEquals(ContinueStatus.WAITING_USER, result.single().status)
    }

    @Test
    fun `council candidate disappears when conversation is deleted`() = runTest {
        val room = councilRoom(CouncilRoomStatus.INTERRUPTED)
        val conversationId = room.conversationId.toString()
        insertConversation(conversationId, JsonInstant.encodeToString(CouncilRoom.serializer(), room))
        val source = CouncilContinueSource(context = context, conversationDao = db.conversationDao())
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
        sourceUrl: String? = null,
        pinned: Boolean = false,
    ) {
        db.hotListDao().upsertDeepRead(
            DeepReadCacheEntity(
                topicId = topicId,
                title = title,
                outputJson = JsonInstant.encodeToString(DeepReadOutput.serializer(), output),
                createdAt = nowMs,
                expiresAt = expiresAt,
                updatedAt = nowMs,
                pinned = pinned,
                sourceUrl = sourceUrl,
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
        val source = DeepReadContinueSource(hotListDao = db.hotListDao(), context = context) { now }

        val result = source.observe().first()

        assertEquals(1, result.size)
        val candidate = result.single()
        assertEquals(ContinueSourceKind.DEEP_READ, candidate.sourceKind)
        assertEquals(ContinueStatus.FAILED_RESUMABLE, candidate.status)
        assertEquals(
            ContinueRoute.DeepRead(
                topicId = "t1",
                title = "话题 t1",
                sourceUrl = null,
            ),
            candidate.route,
        )
        assertEquals("话题 t1", candidate.title)
        assertTrue(!candidate.isRunning)
        assertTrue(!candidate.summary.contains("1/4"))
    }

    @Test
    fun `running deep read reports the active stage instead of durable section count`() = runTest {
        insertDeepRead(
            topicId = "running",
            title = "一篇正在生成的文章",
            output = DeepReadOutput(
                generationPhase = DeepReadGenerationPhase.WRITING,
                sectionStates = mapOf(
                    DeepReadGenerationStage.OVERVIEW to DeepReadSectionState(DeepReadSectionStatus.RUNNING),
                ),
            ),
        )
        val source = DeepReadContinueSource(
            hotListDao = db.hotListDao(),
            context = context,
            now = { now },
            observeActiveWorkStates = {
                flowOf(mapOf("running" to WorkInfo.State.RUNNING))
            },
        )

        val candidate = source.observe().first().single()

        assertEquals("一篇正在生成的文章", candidate.title)
        assertTrue(candidate.isRunning)
        assertTrue(candidate.summary.contains("overview", ignoreCase = true))
        assertTrue(!candidate.summary.contains("/4"))
    }

    @Test
    fun `stopped deep read with stale writing output is resumable but not running`() = runTest {
        insertDeepRead(
            topicId = "stopped",
            output = DeepReadOutput(
                generationPhase = DeepReadGenerationPhase.WRITING,
                sectionStates = mapOf(
                    DeepReadGenerationStage.NARRATIVE to DeepReadSectionState(DeepReadSectionStatus.RUNNING),
                ),
            ),
        )
        val source = DeepReadContinueSource(
            hotListDao = db.hotListDao(),
            context = context,
            now = { now },
            observeActiveWorkStates = { flowOf(emptyMap()) },
        )

        val candidate = source.observe().first().single()

        assertTrue(!candidate.isRunning)
        assertEquals(context.getString(app.amber.agent.R.string.session_home_status_resumable), candidate.summary)
        assertTrue(!candidate.summary.contains("正在", ignoreCase = true))
    }

    @Test
    fun `queued or blocked deep read does not masquerade as actively running`() = runTest {
        insertDeepRead(
            topicId = "queued",
            output = DeepReadOutput(generationPhase = DeepReadGenerationPhase.PLANNING),
        )
        val source = DeepReadContinueSource(
            hotListDao = db.hotListDao(),
            context = context,
            now = { now },
            observeActiveWorkStates = {
                flowOf(mapOf("queued" to WorkInfo.State.ENQUEUED))
            },
        )

        val candidate = source.observe().first().single()

        assertTrue(!candidate.isRunning)
        assertEquals(context.getString(app.amber.agent.R.string.session_home_status_resumable), candidate.summary)
    }

    @Test
    fun `deep read Continue preserves its source URL`() = runTest {
        insertDeepRead(
            topicId = "with-source",
            output = partialOutput(),
            sourceUrl = "https://example.com/article",
        )
        val candidate = DeepReadContinueSource(db.hotListDao(), context) { now }
            .observe()
            .first()
            .single()

        assertEquals(
            ContinueRoute.DeepRead(
                topicId = "with-source",
                title = "话题 with-source",
                sourceUrl = "https://example.com/article",
            ),
            candidate.route,
        )
    }

    @Test
    fun `pinned incomplete deep read remains a candidate after expiry`() = runTest {
        insertDeepRead(
            topicId = "pinned-expired",
            output = partialOutput(),
            expiresAt = nowMs - 1,
            pinned = true,
        )

        val candidates = DeepReadContinueSource(hotListDao = db.hotListDao(), context = context) { now }
            .observe()
            .first()

        assertEquals(1, candidates.size)
        assertEquals("pinned-expired", candidates.single().sourceId)
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
        val source = DeepReadContinueSource(hotListDao = db.hotListDao(), context = context) { now }

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
        val source = MiniAppDraftContinueSource(context = context, draftDao = db.conversationDraftDao())

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
        val source = MiniAppDraftContinueSource(context = context, draftDao = db.conversationDraftDao())
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

    // --------------------------------------------------------- MiniApp runner ---

    @Test
    fun `recent image query returns conversation and tool anchors without message payload`() = runTest {
        val conversationId = "conversation-image"
        insertConversation(conversationId)
        db.runTerminalDao().insertIgnore(
            RunTerminalEntity(
                runId = "run-image",
                conversationId = conversationId,
                assistantId = null,
                state = "COMPLETED",
                pauseReason = null,
                startedAtMs = nowMs - 10,
                updatedAtMs = nowMs,
                finishedAtMs = nowMs,
            )
        )
        db.toolEffectDao().upsert(
            ToolEffectEntity(
                effectId = "effect-image",
                runId = "run-image",
                turnId = 1,
                toolCallId = "call-image",
                toolName = "generate_image",
                argsDigest = "digest",
                approvalDigest = null,
                effectClass = ToolEffectClass.NON_IDEMPOTENT_WRITE.name,
                status = "FINISHED",
                startedAtMs = nowMs - 10,
                finishedAtMs = nowMs,
                resultSummary = "done",
                resultPayload = "{\"url\":\"file:///image.png\"}",
                errorCategory = null,
                messagePersistenceCursor = "message-image",
                createdAtMs = nowMs - 10,
                updatedAtMs = nowMs,
            )
        )

        val rows = db.toolEffectDao().listRecentFinishedWithConversation(
            toolName = "generate_image",
            sinceMs = nowMs - 1_000,
            limit = 10,
        )

        assertEquals(1, rows.size)
        assertEquals(conversationId, rows.single().conversationId)
        assertEquals("call-image", rows.single().effect.toolCallId)
        assertEquals("message-image", rows.single().effect.messagePersistenceCursor)
    }

    @Test
    fun `mini app runner projects never opened and newer versions then clears after run`() = runTest {
        val appId = "mini-runner-1"
        db.miniAppDao().upsert(
            MiniAppEntity(
                id = appId,
                title = "阅读助手",
                description = "desc",
                htmlContent = "<p>app</p>",
                createdAt = nowMs,
                updatedAt = nowMs,
            )
        )
        db.miniAppVersionDao().upsert(
            MiniAppVersionEntity(
                appId = appId,
                versionNumber = 1,
                htmlContent = "<p>app</p>",
                htmlHash = "hash",
                createdAt = nowMs,
            )
        )
        val source = MiniAppRunnerContinueSource(db.miniAppDao())

        val first = source.observe().first().single()
        assertEquals(ContinueSourceKind.MINIAPP_RUNNER, first.sourceKind)
        assertEquals(ContinueRoute.MiniAppRunner(appId), first.route)
        assertEquals("已生成，尚未打开", first.summary)

        db.miniAppDao().markRun(appId, nowMs + 1)
        assertTrue(source.observe().first().isEmpty())

        db.miniAppVersionDao().upsert(
            MiniAppVersionEntity(
                appId = appId,
                versionNumber = 2,
                htmlContent = "<p>new</p>",
                htmlHash = "hash-2",
                createdAt = nowMs + 2,
            )
        )
        val updated = source.observe().first().single()
        assertEquals("有新版本尚未打开", updated.summary)

        db.miniAppDao().deleteById(appId)
        assertTrue(source.observe().first().isEmpty())
    }

    // -------------------------------------------------------------- dismiss ---

    @Test
    fun `dismiss record hides candidate until expiry then restores`() = runTest {
        val store = RoomContinueDismissStore(dao = db.continueCandidateDismissDao())
        store.dismiss(ContinueSourceKind.COUNCIL, "room-1", until = now.plusSeconds(60))
        val source = CouncilContinueSource(context = context, conversationDao = db.conversationDao())

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
