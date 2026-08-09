package app.amber.core.agent.store

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * P1-c: thread_edge 存储契约（jvmTest + Room 真实 JVM 驱动）。
 *
 * 覆盖：insert/查询（edgeFor/childrenOf）；setStatus；descendants 内存递归
 * （传递子级、跨代稳定序）；v2 → v3 迁移保全（agent_run + mailbox_envelope
 * 行原样保留、thread_edge 表可用）。
 */
class ThreadEdgeDaoTest {

    private fun newDatabase(name: String): AgentRuntimeDatabase {
        val path = Files.createTempFile("thread-edge-$name", ".db")
        Files.delete(path)
        return Room.databaseBuilder<AgentRuntimeDatabase>(name = path.toAbsolutePath().toString())
            .setDriver(BundledSQLiteDriver())
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }

    private fun edge(
        child: String,
        parent: String = "root",
        path: String = "/root/task",
        nickname: String? = null,
        role: String? = null,
        forkTurns: String = "all",
        status: String = ThreadEdgeStatus.OPEN,
        createdAt: Long = 1_000,
    ) = ThreadEdgeEntity(
        childThreadId = child,
        parentThreadId = parent,
        agentPath = path,
        nickname = nickname,
        roleAssistantId = role,
        forkTurns = forkTurns,
        status = status,
        createdAt = createdAt,
    )

    // MARK: - insert / query

    @Test
    fun insertAndQueryRoundTrip() = runTest {
        val db = newDatabase("roundtrip")
        val dao = db.threadEdgeDao()
        dao.insertEdge(
            edge(child = "child-1", parent = "root", path = "/root/research", nickname = "调研", role = "assistant-9", forkTurns = "3")
        )

        val found = dao.edgeFor("child-1")
        assertEquals("child-1", found?.childThreadId)
        assertEquals("root", found?.parentThreadId)
        assertEquals("/root/research", found?.agentPath)
        assertEquals("调研", found?.nickname)
        assertEquals("assistant-9", found?.roleAssistantId)
        assertEquals("3", found?.forkTurns)
        assertEquals(ThreadEdgeStatus.OPEN, found?.status)
        assertEquals(1_000L, found?.createdAt)

        assertNull(dao.edgeFor("missing"))
        assertEquals(listOf("child-1"), dao.childrenOf("root").map { it.childThreadId })
        assertEquals(emptyList<String>(), dao.childrenOf("other").map { it.childThreadId })
    }

    @Test
    fun insertEdgeReplacesOnRetryOfSameSpawn() = runTest {
        val db = newDatabase("replace")
        val dao = db.threadEdgeDao()
        dao.insertEdge(edge(child = "child-1", status = ThreadEdgeStatus.OPEN))
        dao.insertEdge(edge(child = "child-1", status = ThreadEdgeStatus.CLOSED))

        val found = dao.edgeFor("child-1")
        assertEquals(ThreadEdgeStatus.CLOSED, found?.status)
        assertEquals("同一 childThreadId 重试不得产生重复边", 1, dao.childrenOf("root").size)
    }

    // MARK: - setStatus

    @Test
    fun setStatusUpdatesOnlyTargetEdge() = runTest {
        val db = newDatabase("status")
        val dao = db.threadEdgeDao()
        dao.insertEdge(edge(child = "a", path = "/root/a"))
        dao.insertEdge(edge(child = "b", path = "/root/b"))

        val updated = dao.setStatus("a", ThreadEdgeStatus.CLOSED)
        assertEquals("首次 setStatus 更新 1 行", 1L, updated.toLong())
        assertEquals(ThreadEdgeStatus.CLOSED, dao.edgeFor("a")?.status)
        assertEquals("b 的状态不受影响", ThreadEdgeStatus.OPEN, dao.edgeFor("b")?.status)

        assertEquals("重复 setStatus 幂等", 1L, dao.setStatus("a", ThreadEdgeStatus.CLOSED).toLong())
    }

    // MARK: - descendants (in-memory recursion)

    @Test
    fun descendantsIncludeAllTransitiveChildrenInStableOrder() = runTest {
        val db = newDatabase("descendants")
        val dao = db.threadEdgeDao()
        // 树：root → a → b、a → c；root → d。
        dao.insertEdge(edge(child = "a", parent = "root", path = "/root/a"))
        dao.insertEdge(edge(child = "b", parent = "a", path = "/root/a/b"))
        dao.insertEdge(edge(child = "c", parent = "a", path = "/root/a/c"))
        dao.insertEdge(edge(child = "d", parent = "root", path = "/root/d"))

        val all = dao.descendantsOf("root")
        assertEquals(
            "BFS：root 的直接子级按插入序，孙级跟随其父",
            listOf("a", "d", "b", "c"),
            all.map { it.childThreadId },
        )

        val fromA = dao.descendantsOf("a")
        assertEquals(listOf("b", "c"), fromA.map { it.childThreadId })

        val fromLeaf = dao.descendantsOf("b")
        assertTrue(fromLeaf.isEmpty())
    }

    // MARK: - v2 → v3 migration preserves production data

    /**
     * P1-b 升级过的老设备（v2 库，agent_run + mailbox_envelope 已在用）再升
     * v3：用 2.json 的确切 v2 schema + identity hash 造库，写入生产形态行，
     * 经 MIGRATION_2_3 打开——数据完整保留、thread_edge 可用。
     */
    @Test
    fun migrationFromV2PreservesAgentRunAndMailboxAndEnablesThreadEdge() = runTest {
        val path = Files.createTempFile("thread-edge-migration-v2", ".db")
        Files.delete(path)
        val absolutePath = path.toAbsolutePath().toString()
        createV2DatabaseWithProductionRows(absolutePath)

        val db = Room.databaseBuilder<AgentRuntimeDatabase>(name = absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

        // agent_run 行保留。
        val run = db.agentRuntimeDao().getRun("run-v2-prod")
        assertEquals("run-v2-prod", run?.runId)
        assertEquals("cafe-dead", run?.conversationId)
        assertEquals("completed", run?.status)
        assertEquals(1234567L, run?.startedAt)

        // mailbox_envelope 行保留且仍可 drain（exactly-once 语义不因升级丢失）。
        val mailboxDao = db.mailboxDao()
        assertEquals(
            listOf("env-v2-1", "env-v2-2"),
            mailboxDao.drainPending("cafe-dead", deliveredAt = 9_000).map { it.id },
        )
        assertTrue("升级后二次 drain 为空", mailboxDao.drainPending("cafe-dead", deliveredAt = 9_001).isEmpty())

        // thread_edge 表迁移后真实可用。
        val edgeDao = db.threadEdgeDao()
        edgeDao.insertEdge(edge(child = "post-migration", parent = "root", path = "/root/x"))
        assertEquals(
            listOf("post-migration"),
            edgeDao.descendantsOf("root").map { it.childThreadId },
        )
    }

    /** 用 2.json 的确切 v2 schema + identity hash 构造 v2 库，写入生产形态行。 */
    private fun createV2DatabaseWithProductionRows(absolutePath: String) {
        val driver = BundledSQLiteDriver()
        val connection = driver.open(absolutePath)
        connection.execSQL(V2_AGENT_RUN_CREATE)
        connection.execSQL(V2_AGENT_EVENT_CREATE)
        connection.execSQL(V2_TRACE_SPAN_CREATE)
        connection.execSQL(V2_PERMISSION_INTENT_CREATE)
        connection.execSQL(V2_MAILBOX_ENVELOPE_CREATE)
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_run_status` ON `agent_run` (`status`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_run_agent_descriptor_id` ON `agent_run` (`agent_descriptor_id`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_run_conversation_id` ON `agent_run` (`conversation_id`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_run_message_node_id` ON `agent_run` (`message_node_id`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_run_assistant_id` ON `agent_run` (`assistant_id`)")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_event_run_id_seq` ON `agent_event` (`run_id`, `seq`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_event_run_id` ON `agent_event` (`run_id`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_event_ts` ON `agent_event` (`ts`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_trace_span_run_id` ON `trace_span` (`run_id`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_trace_span_parent_span_id` ON `trace_span` (`parent_span_id`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_trace_span_kind` ON `trace_span` (`kind`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_trace_span_started_at` ON `trace_span` (`started_at`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_permission_intent_run_id` ON `permission_intent` (`run_id`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_permission_intent_decision` ON `permission_intent` (`decision`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_permission_intent_created_at` ON `permission_intent` (`created_at`)")
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_mailbox_envelope_recipient_thread_id_delivered_at_created_at` " +
                "ON `mailbox_envelope` (`recipient_thread_id`, `delivered_at`, `created_at`)",
        )
        connection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        connection.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '0b9a5ae40ddcbf1527090bff0a1b045f')",
        )
        connection.execSQL("PRAGMA user_version = 2")
        connection.execSQL(
            """
            INSERT INTO agent_run (
                run_id, parent_run_id, agent_descriptor_id, agent_version, conversation_id,
                message_node_id, produces_message_id, assistant_id, status, input_digest,
                input_snapshot_ref, input_schema_version, started_at, finished_at, interrupted_reason
            ) VALUES (
                'run-v2-prod', NULL, 'chat', '1', 'cafe-dead',
                NULL, NULL, NULL, 'completed', 'digest',
                NULL, 1, 1234567, 1239999, NULL
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO mailbox_envelope (
                id, author_thread_id, recipient_thread_id, type, payload,
                trigger_turn, parent_turn_id, created_at, delivered_at
            ) VALUES
                ('env-v2-1', '/root/a', 'cafe-dead', 'MESSAGE', 'p1', 0, NULL, 100, NULL),
                ('env-v2-2', '/root/a', 'cafe-dead', 'FINAL_ANSWER', 'p2', 0, 'turn-1', 200, NULL)
            """.trimIndent(),
        )
        connection.close()
    }

    private companion object {
        // 与 schemas/app.amber.core.agent.store.AgentRuntimeDatabase/2.json 逐字一致。
        const val V2_AGENT_RUN_CREATE =
            "CREATE TABLE IF NOT EXISTS `agent_run` (`run_id` TEXT NOT NULL, `parent_run_id` TEXT, `agent_descriptor_id` TEXT NOT NULL, `agent_version` TEXT NOT NULL, `conversation_id` TEXT, `message_node_id` TEXT, `produces_message_id` TEXT, `assistant_id` TEXT, `status` TEXT NOT NULL, `input_digest` TEXT NOT NULL, `input_snapshot_ref` TEXT, `input_schema_version` INTEGER NOT NULL, `started_at` INTEGER NOT NULL, `finished_at` INTEGER, `interrupted_reason` TEXT, PRIMARY KEY(`run_id`))"
        const val V2_AGENT_EVENT_CREATE =
            "CREATE TABLE IF NOT EXISTS `agent_event` (`event_id` TEXT NOT NULL, `run_id` TEXT NOT NULL, `parent_run_id` TEXT, `seq` INTEGER NOT NULL, `type` TEXT NOT NULL, `payload_type` TEXT NOT NULL, `payload` TEXT NOT NULL, `payload_schema_version` INTEGER NOT NULL, `agent_descriptor_id` TEXT NOT NULL, `agent_version` TEXT NOT NULL, `is_final` INTEGER NOT NULL, `ts` INTEGER NOT NULL, PRIMARY KEY(`event_id`))"
        const val V2_TRACE_SPAN_CREATE =
            "CREATE TABLE IF NOT EXISTS `trace_span` (`span_id` TEXT NOT NULL, `run_id` TEXT NOT NULL, `parent_span_id` TEXT, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `status` TEXT NOT NULL, `started_at` INTEGER NOT NULL, `ended_at` INTEGER, `attributes_json` TEXT NOT NULL, PRIMARY KEY(`span_id`))"
        const val V2_PERMISSION_INTENT_CREATE =
            "CREATE TABLE IF NOT EXISTS `permission_intent` (`intent_id` TEXT NOT NULL, `run_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `tool_id` TEXT, `payload_digest` TEXT NOT NULL, `reason` TEXT NOT NULL, `channel` TEXT NOT NULL, `decision` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `decided_at` INTEGER, `decided_by` TEXT, PRIMARY KEY(`intent_id`))"
        const val V2_MAILBOX_ENVELOPE_CREATE =
            "CREATE TABLE IF NOT EXISTS `mailbox_envelope` (`id` TEXT NOT NULL, `author_thread_id` TEXT NOT NULL, `recipient_thread_id` TEXT NOT NULL, `type` TEXT NOT NULL, `payload` TEXT NOT NULL, `trigger_turn` INTEGER NOT NULL, `parent_turn_id` TEXT, `created_at` INTEGER NOT NULL, `delivered_at` INTEGER, PRIMARY KEY(`id`))"
    }
}
