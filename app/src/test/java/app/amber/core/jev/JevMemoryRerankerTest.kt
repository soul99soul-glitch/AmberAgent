package app.amber.core.jev

import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryRecord
import app.amber.core.memory.model.MemoryScope
import app.amber.core.settings.Settings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 记忆重排：多 chunk 判分聚合进单条校准记录；chunk 失败整体不落记录。 */
class JevMemoryRerankerTest {

    private class ScriptedTransport(
        private val failFromCall: Int? = null,
    ) : JevTransport {
        var calls = 0

        override suspend fun execute(request: JevHttpRequest): JevTransportResponse {
            calls++
            if (failFromCall != null && calls >= failFromCall) {
                return JevTransportResponse.Failure("down")
            }
            val ids = requestedIds(request)
            val answer = ids.joinToString(",") { """"$it":{"type":"noul","noul":0.9}""" }
            return JevTransportResponse.Http(200, """{"model":"jev-test","answers":{$answer}}""".toByteArray(), null)
        }

        /** 从出站请求里提取 questions 的候选 id（记录 id 字符串）。 */
        private fun requestedIds(request: JevHttpRequest): List<String> =
            Regex("\"Is the memory with id ([0-9a-f-]+) in state\\.memories").findAll(request.body)
                .map { it.groupValues[1] }
                .toList()
    }

    private fun runtime(mode: JevMode, calibration: JevCalibrationStore, transport: JevTransport): JevRuntime {
        val settings = Settings(
            jev = JevSetting(
                enabled = true,
                purposes = mapOf(JevPurpose.MEMORY_RECALL to mode),
                dataScopes = setOf(JevDataScope.PERSONAL_MEMORY, JevDataScope.TASK_TEXT),
            ),
        )
        return JevRuntime(
            coordinator = JevDecisionCoordinator(
                client = JevClient(transport = transport),
                apiKeyProvider = { "key" },
                clock = { 1_000_000L },
            ),
            settingsProvider = { settings },
            calibration = calibration,
        )
    }

    /** 40 条 → 32+8 两个 chunk，覆盖跨 chunk 聚合路径。 */
    private fun records(count: Int) = (1..count).map { index ->
        MemoryRecord(
            id = index,
            content = "memory content $index",
            scope = MemoryScope.CORE,
            kind = MemoryKind.USER,
            assistantId = "assistant",
        )
    }

    @Test
    fun multiChunkAggregatesIntoSingleRecord() = runTest {
        val all = records(40)
        val calibration = FreshCalibrationStore()
        val transport = ScriptedTransport()
        val result = JevMemoryReranker(runtime(JevMode.ACTIVE, calibration, transport))
            .rerank(all, taskText = "what did I say about coffee?", runKey = "run1")
        assertTrue(result.applied)
        assertEquals(40, result.rankedIds?.size)
        assertEquals(2, transport.calls)
        val record = calibration.records.single()
        assertEquals(JevPurpose.MEMORY_RECALL, record.purpose)
        assertEquals(JevMode.ACTIVE, record.mode)
        assertEquals(40, record.scores.size)
        assertEquals(all.first().id.toString(), record.incumbentTop1)
        assertTrue(record.jevTop1 != null && record.scores[record.jevTop1] == 0.9)
    }

    @Test
    fun secondChunkFailureRecordsNothing() = runTest {
        val calibration = FreshCalibrationStore()
        val result = JevMemoryReranker(runtime(JevMode.ACTIVE, calibration, ScriptedTransport(failFromCall = 2)))
            .rerank(records(40), taskText = "what did I say about coffee?", runKey = "run1")
        assertFalse(result.applied)
        assertTrue(calibration.records.isEmpty())
    }

    /** 局部独立记录器：IN_MEMORY 是进程级单例，会被其他测试污染。 */
    private class FreshCalibrationStore : JevCalibrationStore {
        val records = mutableListOf<JevCalibrationRecord>()
        override fun append(record: JevCalibrationRecord) {
            records += record
        }
        override fun readAll(): List<JevCalibrationRecord> = records.toList()
        override fun clear() = records.clear()
    }
}
