package app.amber.core.jev.baseline

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.jev.JevCalibrationRecord
import app.amber.core.jev.JevCalibrationStore
import app.amber.core.jev.JevClient
import app.amber.core.jev.JevDecisionCoordinator
import app.amber.core.jev.JevHttpRequest
import app.amber.core.jev.JevLimits
import app.amber.core.jev.JevMemoryReranker
import app.amber.core.jev.JevMode
import app.amber.core.jev.JevPurpose
import app.amber.core.jev.JevRuntime
import app.amber.core.jev.JevSetting
import app.amber.core.jev.JevTransport
import app.amber.core.jev.JevTransportResponse
import app.amber.core.settings.Settings
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryRecord
import app.amber.core.memory.model.MemoryScope
import app.amber.core.memory.recall.MemoryRecallStore
import app.amber.core.memory.store.MemoryRepository
import app.amber.agent.data.db.dao.MemoryCandidateDAO
import app.amber.agent.data.db.dao.MemoryDAO
import app.amber.agent.data.db.dao.MemoryEventDAO
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * C11 记忆召回 baseline：语料 × 生产全链（MemoryRecallStore 候选池 →
 * JevMemoryReranker 分块出站 → 阈值弃权 → 置顶强保留），Jev 判分由语料
 * relevant 注记经 scripted 传输层回放。冻结点：语料文件 + 本文件断言。
 */
class JevMemoryCorpusBaselineTest {

    @Serializable
    private data class Corpus(
        val version: Int,
        val records: List<RecordDto>,
        val tasks: List<TaskDto>,
    )

    @Serializable
    private data class RecordDto(
        val id: Int,
        val content: String,
        val kind: String,
        val scope: String,
        val pinned: Boolean = false,
        val confidence: Double = 1.0,
    )

    @Serializable
    private data class TaskDto(
        val id: String,
        val text: String,
        val relevant: List<Int>,
        val set: String,
    )

    private class ReplayTransport(
        private val relevantIds: Set<Int>,
    ) : JevTransport {
        val askedIds = mutableListOf<Int>()
        val requestBodies = mutableListOf<String>()

        override suspend fun execute(request: JevHttpRequest): JevTransportResponse {
            requestBodies += request.body
            val ids = Regex("\"Is the memory with id (\\d+) in state\\.memories")
                .findAll(request.body).map { it.groupValues[1].toInt() }.toList()
            assertTrue("chunk asked no candidates", ids.isNotEmpty())
            askedIds += ids
            val answer = ids.joinToString(",") { id ->
                """"$id":{"type":"noul","noul":${if (id in relevantIds) 0.9 else 0.1}}"""
            }
            return JevTransportResponse.Http(200, """{"model":"jev-test","answers":{$answer}}""".toByteArray(), null)
        }
    }

    private class FreshCalibration : JevCalibrationStore {
        val records = mutableListOf<JevCalibrationRecord>()
        override fun append(record: JevCalibrationRecord) {
            records += record
        }
        override fun readAll(): List<JevCalibrationRecord> = records.toList()
        override fun clear() = records.clear()
    }

    private class CorpusRepo(records: List<MemoryRecord>) : MemoryRepository(
        memoryDAO = dummyDao(MemoryDAO::class.java),
        candidateDAO = dummyDao(MemoryCandidateDAO::class.java),
        eventDAO = dummyDao(MemoryEventDAO::class.java),
    ) {
        private val backed = records
        override suspend fun getActiveRecords(scopes: Set<MemoryScope>, now: Long): List<MemoryRecord> =
            backed.filter { it.scope in scopes }

        override suspend fun touchMemories(ids: List<Int>, usedAt: Long) = Unit
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun corpus(): Corpus = json.decodeFromString(
        Corpus.serializer(),
        javaClass.classLoader.getResourceAsStream("jev-corpus/memories.json")!!
            .readBytes().decodeToString(),
    )

    private fun corpusRecords(corpus: Corpus): List<MemoryRecord> = corpus.records.map { dto ->
        MemoryRecord(
            id = dto.id,
            content = dto.content,
            scope = MemoryScope.valueOf(dto.scope),
            kind = MemoryKind.valueOf(dto.kind),
            assistantId = "corpus",
            pinned = dto.pinned,
            confidence = dto.confidence.toFloat(),
            updatedAt = dto.id * 1_000L,
        )
    }

    private fun userMessage(text: String) =
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    @Test
    fun corpusVersionAndScaleAreFrozen() {
        val corpus = corpus()
        assertEquals(1, corpus.version)
        assertTrue("record corpus scale", corpus.records.size >= 40)
        assertTrue("task corpus scale", corpus.tasks.size >= 10)
        val sets = corpus.tasks.groupBy { it.`set` }.keys
        assertTrue("tuning/frozen split required", setOf("tuning", "frozen").all(sets::contains))
        assertTrue("task ids must be unique", corpus.tasks.map { it.id }.distinct().size == corpus.tasks.size)
    }

    @Test
    fun fullChainRecallMatchesAnnotations() = runTest {
        val corpus = corpus()
        val all = corpusRecords(corpus)
        val pinnedIds = all.filter { it.pinned }.map { it.id }.toSet()

        corpus.tasks.forEach { task ->
            val calibration = FreshCalibration()
            val transport = ReplayTransport(task.relevant.toSet())
            val runtime = JevRuntime(
                coordinator = JevDecisionCoordinator(
                    client = JevClient(transport = transport),
                    apiKeyProvider = { "key" },
                    clock = { 1_000_000L },
                ),
                settingsProvider = {
                    Settings(
                        jev = JevSetting(
                            enabled = true,
                            purposes = mapOf(JevPurpose.MEMORY_RECALL to JevMode.ACTIVE),
                            dataScopes = setOf(
                                app.amber.core.jev.JevDataScope.PERSONAL_MEMORY,
                                app.amber.core.jev.JevDataScope.TASK_TEXT,
                            ),
                        ),
                    )
                },
                calibration = calibration,
            )
            val store = MemoryRecallStore(CorpusRepo(all), JevMemoryReranker(runtime))

            val output = store.recall(Settings(), listOf(userMessage(task.text)), runKey = task.id)
            val outIds = output.map { it.id }.toSet()
            val asked = transport.askedIds.toSet()

            // 候选池必非空；相关注记进了池的必须全部出现在结果里。
            assertTrue("task ${task.id}: empty candidate pool", asked.isNotEmpty())
            val poolRelevant = asked intersect task.relevant.toSet()
            assertTrue(
                "task ${task.id}: none of the relevant memories entered the pool",
                poolRelevant.isNotEmpty(),
            )
            assertTrue(
                "task ${task.id}: relevant-in-pool missing from output: ${poolRelevant - outIds}",
                outIds.containsAll(poolRelevant),
            )
            // 判 0.1 的非置顶候选低于弃权阈值，不得出现在结果里。
            val askedIrrelevant = (asked - task.relevant.toSet()) - pinnedIds
            assertTrue(
                "task ${task.id}: irrelevant candidates leaked into output: ${askedIrrelevant intersect outIds}",
                (askedIrrelevant intersect outIds).isEmpty(),
            )
            // 置顶强保留：无论 Jev 判分如何，置顶记忆必须留存。
            assertTrue(
                "task ${task.id}: pinned memories dropped: ${pinnedIds - outIds}",
                outIds.containsAll(pinnedIds),
            )
            // 校准记录：每次召回恰好一条，判分覆盖全部被询候选。
            val record = calibration.records.single()
            assertEquals(JevPurpose.MEMORY_RECALL, record.purpose)
            assertEquals(JevMode.ACTIVE, record.mode)
            assertEquals("calibration scores must cover exactly the asked candidates",
                asked.map { it.toString() }.toSet(), record.scores.keys)
            assertEquals(asked.first().toString(), record.incumbentTop1)
            assertTrue(
                "task ${task.id}: jevTop1 ${record.jevTop1} not annotated relevant",
                record.jevTop1?.toIntOrNull() in task.relevant,
            )
        }
    }

    @Test
    fun baselineWithoutRerankerStaysNonEmpty() = runTest {
        val corpus = corpus()
        val all = corpusRecords(corpus)
        val baseline = MemoryRecallStore(CorpusRepo(all))
        corpus.tasks.forEach { task ->
            val output = baseline.recall(Settings(), listOf(userMessage(task.text)), runKey = task.id)
            assertTrue("task ${task.id}: lexical baseline returned nothing", output.isNotEmpty())
        }
    }

    @Test
    fun worstCasePoolStaysWithinEnvelopeBudgets() = runTest {
        val corpus = corpus()
        val all = corpusRecords(corpus)
        val transport = ReplayTransport(emptySet())
        val runtime = JevRuntime(
            coordinator = JevDecisionCoordinator(
                client = JevClient(transport = transport),
                apiKeyProvider = { "key" },
                clock = { 1_000_000L },
            ),
            settingsProvider = {
                Settings(
                    jev = JevSetting(
                        enabled = true,
                        purposes = mapOf(JevPurpose.MEMORY_RECALL to JevMode.ACTIVE),
                        dataScopes = setOf(
                            app.amber.core.jev.JevDataScope.PERSONAL_MEMORY,
                            app.amber.core.jev.JevDataScope.TASK_TEXT,
                        ),
                    ),
                )
            },
        )
        MemoryRecallStore(CorpusRepo(all), JevMemoryReranker(runtime))
            .recall(Settings(), listOf(userMessage(corpus.tasks.first().text)), runKey = "budget")
        transport.requestBodies.forEach { body ->
            assertTrue(
                "outbound body ${body.length} chars over budget",
                body.toByteArray(Charsets.UTF_8).size <= JevLimits.MAX_REQUEST_BODY_BYTES,
            )
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> dummyDao(iface: Class<T>): T = Proxy.newProxyInstance(
    JevMemoryCorpusBaselineTest::class.java.classLoader,
    arrayOf(iface),
) { _, method, _ -> throw UnsupportedOperationException("unexpected ${method.name} on dummy DAO") } as T

