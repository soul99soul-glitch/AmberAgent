package app.amber.core.agent.store

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * P1-b: mailbox 信封存储契约（jvmTest + Room 真实 JVM 驱动）。
 *
 * 覆盖：FIFO（createdAt, id 稳定序）；drain exactly-once（二次 drain 空）；
 * markDelivered 幂等；渲染格式；triggerTurn/parentTurnId 往返；
 * v1 → v2 迁移不丢既有 agent_run 数据（iOS 老设备生产库升级安全证据）。
 */
class MailboxDaoTest {

    private fun newDatabase(name: String, addMigrations: Boolean = true): AgentRuntimeDatabase {
        val path = Files.createTempFile("mailbox-$name", ".db")
        Files.delete(path)
        return builder(path = path.toAbsolutePath().toString(), addMigrations = addMigrations)
    }

    private fun builder(path: String, addMigrations: Boolean): AgentRuntimeDatabase =
        Room.databaseBuilder<AgentRuntimeDatabase>(name = path)
            .setDriver(BundledSQLiteDriver())
            .apply { if (addMigrations) addMigrations(MIGRATION_1_2, MIGRATION_2_3) }
            .build()

    private fun envelope(
        id: String,
        recipient: String = "root",
        author: String = "/root/a",
        type: String = MailboxEnvelopeType.MESSAGE.wireName,
        payload: String = "payload-$id",
        triggerTurn: Boolean = false,
        parentTurnId: String? = null,
        createdAt: Long = 1_000,
    ) = MailboxEnvelopeEntity(
        id = id,
        authorThreadId = author,
        recipientThreadId = recipient,
        type = type,
        payload = payload,
        triggerTurn = triggerTurn,
        parentTurnId = parentTurnId,
        createdAt = createdAt,
        deliveredAt = null,
    )

    // MARK: - FIFO

    @Test
    fun pendingForRecipientReturnsFifoByCreatedAtThenId() = runTest {
        val db = newDatabase("fifo")
        val dao = db.mailboxDao()
        // 插入顺序打乱；期望按 createdAt 升序，createdAt 相同按 id 升序。
        dao.enqueue(envelope(id = "c", createdAt = 300))
        dao.enqueue(envelope(id = "a", createdAt = 100))
        dao.enqueue(envelope(id = "b", createdAt = 200))
        dao.enqueue(envelope(id = "a2", createdAt = 200))

        val pending = dao.pendingForRecipient("root")
        assertEquals(listOf("a", "a2", "b", "c"), pending.map { it.id })
    }

    @Test
    fun pendingForRecipientIsScopedByRecipient() = runTest {
        val db = newDatabase("scoped")
        val dao = db.mailboxDao()
        dao.enqueue(envelope(id = "mine", recipient = "root-a"))
        dao.enqueue(envelope(id = "other", recipient = "root-b"))

        assertEquals(listOf("mine"), dao.pendingForRecipient("root-a").map { it.id })
        assertEquals(listOf("other"), dao.pendingForRecipient("root-b").map { it.id })
    }

    // MARK: - drain exactly-once

    @Test
    fun drainPendingDeliversOnceAndSecondDrainIsEmpty() = runTest {
        val db = newDatabase("drain-once")
        val dao = db.mailboxDao()
        dao.enqueue(envelope(id = "e1", createdAt = 100))
        dao.enqueue(envelope(id = "e2", createdAt = 200))

        val first = dao.drainPending("root", deliveredAt = 5_000)
        assertEquals(listOf("e1", "e2"), first.map { it.id })
        assertTrue("drain 返回的信封是查到的原始行（未投递态）", first.all { it.deliveredAt == null })

        val second = dao.drainPending("root", deliveredAt = 6_000)
        assertTrue("二次 drain 必须为空（exactly-once）", second.isEmpty())
        assertTrue(dao.pendingForRecipient("root").isEmpty())
    }

    /**
     * P1-c checker 项红测试：两个 drain 并发竞争同一批信封时，同一条信封最多被
     * 一个调用折入。旧实现忽略 markDelivered 返回行数，读到同一批 pending 的
     * 两个事务都会把整批返回 → 重复折入；加固后行数不足的一方返回空。
     */
    @Test
    fun concurrentDrainNeverDoubleFoldsEnvelopes() = runTest {
        val db = newDatabase("drain-concurrent")
        val dao = db.mailboxDao()
        dao.enqueue(envelope(id = "c1", createdAt = 100))
        dao.enqueue(envelope(id = "c2", createdAt = 200))

        val results = (1L..4L).map { attempt ->
            async {
                runCatching { dao.drainPending("root", deliveredAt = 5_000 + attempt) }
                    .getOrDefault(emptyList())
            }
        }.awaitAll()

        val foldedIds = results.flatten().map { it.id }
        assertEquals(
            "并发 drain 不得把同一条信封重复折入（实际折入: $foldedIds）",
            foldedIds.size.toLong(),
            foldedIds.toSet().size.toLong(),
        )
        assertTrue("信封必须恰好折入一次", foldedIds.toSet() == setOf("c1", "c2"))
        assertTrue(
            "并发 loser 必须返回空列表而不是陈旧快照",
            results.count { it.isNotEmpty() } == 1,
        )

        val after = dao.drainPending("root", deliveredAt = 9_000)
        assertTrue("全部信封标投递后 drain 为空", after.isEmpty())
    }

    // MARK: - markDelivered idempotent

    @Test
    fun markDeliveredIsIdempotentAndExcludesFromPending() = runTest {
        val db = newDatabase("mark-delivered")
        val dao = db.mailboxDao()
        dao.enqueue(envelope(id = "e1", createdAt = 100))
        dao.enqueue(envelope(id = "e2", createdAt = 200))
        dao.enqueue(envelope(id = "e3", createdAt = 300))

        val updated = dao.markDelivered(listOf("e1", "e2"), deliveredAt = 9_000)
        assertEquals("首次标记更新 2 行", 2L, updated.toLong())

        val again = dao.markDelivered(listOf("e1", "e2"), deliveredAt = 10_000)
        assertEquals("重复标记不再更新（幂等，deliveredAt 不被改写）", 0L, again.toLong())

        val pending = dao.pendingForRecipient("root")
        assertEquals(listOf("e3"), pending.map { it.id })
    }

    // MARK: - render

    @Test
    fun renderMailboxEnvelopeToUserTextMatchesStructureHeaderFormat() {
        assertEquals(
            "[mailbox MESSAGE from /root/a]\n继续搜索 x",
            renderMailboxEnvelopeToUserText("/root/a", "MESSAGE", "继续搜索 x"),
        )
        assertEquals(
            "[mailbox NEW_TASK from /root]\n帮我调研房价",
            renderMailboxEnvelopeToUserText("/root", "NEW_TASK", "帮我调研房价"),
        )
        assertEquals(
            "[mailbox FINAL_ANSWER from /root/a/b]\n完成",
            renderMailboxEnvelopeToUserText("/root/a/b", "FINAL_ANSWER", "完成"),
        )
    }

    // MARK: - triggerTurn round-trip

    @Test
    fun triggerTurnAndParentTurnIdRoundTripThroughDrain() = runTest {
        val db = newDatabase("trigger-turn")
        val dao = db.mailboxDao()
        dao.enqueue(envelope(id = "t1", triggerTurn = true, parentTurnId = "turn-42", createdAt = 100))
        dao.enqueue(envelope(id = "t2", triggerTurn = false, parentTurnId = null, createdAt = 200))

        val drained = dao.drainPending("root", deliveredAt = 7_000)
        val t1 = drained.first { it.id == "t1" }
        val t2 = drained.first { it.id == "t2" }
        assertTrue("triggerTurn=true 必须往返保持", t1.triggerTurn)
        assertEquals("turn-42", t1.parentTurnId)
        assertFalse(t2.triggerTurn)
        assertNull(t2.parentTurnId)
        assertTrue(t1.type == MailboxEnvelopeType.MESSAGE.wireName)
    }

    // MARK: - v1 → v2 migration keeps production data

    /**
     * 老设备（iOS 生产库已是 v1，agent_run 在账本/恢复/热力图中使用）升级路径：
     * 用 1.json 的确切 v1 schema + identity hash 造一个 v1 库，写入 agent_run 行，
     * 再经 MIGRATION_1_2 打开——数据完整保留、mailbox 表可用。
     */
    @Test
    fun migrationFromV1PreservesAgentRunRowsAndEnablesMailbox() = runTest {
        val path = Files.createTempFile("mailbox-migration-v1", ".db")
        Files.delete(path)
        val absolutePath = path.toAbsolutePath().toString()
        createV1DatabaseWithProductionRun(absolutePath)

        // 2) 升级路径打开：MIGRATION_1_2 创建 mailbox 表，不动既有数据。
        val migratedDb = builder(path = absolutePath, addMigrations = true)
        val migratedDao = migratedDb.agentRuntimeDao()

        val run = migratedDao.getRun("run-v1-prod")
        assertEquals("run-v1-prod", run?.runId)
        assertEquals("cafe-dead", run?.conversationId)
        assertEquals("completed", run?.status)
        assertEquals(1234567L, run?.startedAt)

        // 3) 迁移后 mailbox 表真实可用（同一库文件）。
        val mailboxDao = migratedDb.mailboxDao()
        mailboxDao.enqueue(envelope(id = "post-migration", createdAt = 100))
        assertEquals(
            listOf("post-migration"),
            mailboxDao.drainPending("root", deliveredAt = 9_000).map { it.id },
        )
    }

    /**
     * 数据安全红：builder 未配置迁移时（本阶段改造前的状态），Room 必须拒绝打开
     * v1 库而不是静默破坏（我们绝不走 fallbackToDestructiveMigration）。MIGRATION_1_2
     * 配置后同一库可打开（见上一条）。这条同时证明升级策略是「正式迁移」而非重建。
     */
    @Test
    fun v1DatabaseCannotBeOpenedWithoutConfiguredMigration() = runTest {
        val path = Files.createTempFile("mailbox-migration-no-migration", ".db")
        Files.delete(path)
        val absolutePath = path.toAbsolutePath().toString()
        createV1DatabaseWithProductionRun(absolutePath)

        val unconfiguredDb = builder(path = absolutePath, addMigrations = false)
        try {
            unconfiguredDb.agentRuntimeDao().getRun("run-v1-prod")
            assertTrue("未配置迁移时打开 v1 库必须失败（而不是静默丢数据）", false)
        } catch (expected: IllegalStateException) {
            // 预期：Room 报「缺少 1→2 迁移」。
            assertTrue(expected.message ?: "no message", expected.message?.contains("Migration") == true)
        }
    }

    /** 用 1.json 的确切 v1 schema + identity hash 构造 v1 库，并写入一条生产形态 agent_run 行。 */
    private fun createV1DatabaseWithProductionRun(absolutePath: String) {
        val driver = BundledSQLiteDriver()
        val connection = driver.open(absolutePath)
        connection.execSQL(V1_AGENT_RUN_CREATE)
        connection.execSQL(V1_AGENT_EVENT_CREATE)
        connection.execSQL(V1_TRACE_SPAN_CREATE)
        connection.execSQL(V1_PERMISSION_INTENT_CREATE)
        // v1 索引（Room 打开时对表做完整校验，缺索引视同 schema 不匹配）。
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
        connection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        connection.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '1f8e8b2851ffc9c2fbe306e03a38370d')",
        )
        connection.execSQL("PRAGMA user_version = 1")
        connection.execSQL(
            """
            INSERT INTO agent_run (
                run_id, parent_run_id, agent_descriptor_id, agent_version, conversation_id,
                message_node_id, produces_message_id, assistant_id, status, input_digest,
                input_snapshot_ref, input_schema_version, started_at, finished_at, interrupted_reason
            ) VALUES (
                'run-v1-prod', NULL, 'chat', '1', 'cafe-dead',
                NULL, NULL, NULL, 'completed', 'digest',
                NULL, 1, 1234567, 1239999, NULL
            )
            """.trimIndent(),
        )
        connection.close()
    }

    // MARK: - 幂等入队（P1-c 终态去重）

    @Test
    fun enqueueIfAbsentSkipsDuplicateIdInsteadOfThrowing() = runTest {
        val db = newDatabase("enqueue-if-absent")
        val dao = db.mailboxDao()
        // 首次入队成功。
        dao.enqueueIfAbsent(envelope(id = "final-run-1", type = MailboxEnvelopeType.FINAL_ANSWER.wireName))
        // 同 id 重复入队（cancel 与 finishStreaming 双触发）静默跳过，不抛异常。
        dao.enqueueIfAbsent(envelope(id = "final-run-1", type = MailboxEnvelopeType.FINAL_ANSWER.wireName))
        // 已投递后再重投同样被跳过（去重以 id 为准，覆盖已消费信封）。
        dao.markDelivered(listOf("final-run-1"), deliveredAt = 2_000)
        dao.enqueueIfAbsent(envelope(id = "final-run-1", type = MailboxEnvelopeType.FINAL_ANSWER.wireName))
        // 不同 id 不受影响。
        dao.enqueueIfAbsent(envelope(id = "final-run-2", type = MailboxEnvelopeType.FINAL_ANSWER.wireName))

        // 重复入队不复活已投递信封：pending 只剩新 id。
        val pending = dao.pendingForRecipient("root")
        assertEquals(listOf("final-run-2"), pending.map { it.id })
    }

    private companion object {
        // 与 schemas/app.amber.core.agent.store.AgentRuntimeDatabase/1.json 逐字一致。
        const val V1_AGENT_RUN_CREATE =
            "CREATE TABLE IF NOT EXISTS `agent_run` (`run_id` TEXT NOT NULL, `parent_run_id` TEXT, `agent_descriptor_id` TEXT NOT NULL, `agent_version` TEXT NOT NULL, `conversation_id` TEXT, `message_node_id` TEXT, `produces_message_id` TEXT, `assistant_id` TEXT, `status` TEXT NOT NULL, `input_digest` TEXT NOT NULL, `input_snapshot_ref` TEXT, `input_schema_version` INTEGER NOT NULL, `started_at` INTEGER NOT NULL, `finished_at` INTEGER, `interrupted_reason` TEXT, PRIMARY KEY(`run_id`))"
        const val V1_AGENT_EVENT_CREATE =
            "CREATE TABLE IF NOT EXISTS `agent_event` (`event_id` TEXT NOT NULL, `run_id` TEXT NOT NULL, `parent_run_id` TEXT, `seq` INTEGER NOT NULL, `type` TEXT NOT NULL, `payload_type` TEXT NOT NULL, `payload` TEXT NOT NULL, `payload_schema_version` INTEGER NOT NULL, `agent_descriptor_id` TEXT NOT NULL, `agent_version` TEXT NOT NULL, `is_final` INTEGER NOT NULL, `ts` INTEGER NOT NULL, PRIMARY KEY(`event_id`))"
        const val V1_TRACE_SPAN_CREATE =
            "CREATE TABLE IF NOT EXISTS `trace_span` (`span_id` TEXT NOT NULL, `run_id` TEXT NOT NULL, `parent_span_id` TEXT, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `status` TEXT NOT NULL, `started_at` INTEGER NOT NULL, `ended_at` INTEGER, `attributes_json` TEXT NOT NULL, PRIMARY KEY(`span_id`))"
        const val V1_PERMISSION_INTENT_CREATE =
            "CREATE TABLE IF NOT EXISTS `permission_intent` (`intent_id` TEXT NOT NULL, `run_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `tool_id` TEXT, `payload_digest` TEXT NOT NULL, `reason` TEXT NOT NULL, `channel` TEXT NOT NULL, `decision` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `decided_at` INTEGER, `decided_by` TEXT, PRIMARY KEY(`intent_id`))"
    }
}
