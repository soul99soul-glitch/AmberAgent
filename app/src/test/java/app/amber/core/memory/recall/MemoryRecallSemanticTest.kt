package app.amber.core.memory.recall

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.jev.MemorySemanticResult
import app.amber.core.memory.model.MemoryKind
import app.amber.core.memory.model.MemoryRecord
import app.amber.core.memory.model.MemoryScope
import app.amber.core.memory.store.MemoryRepository
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.Settings
import app.amber.agent.data.db.dao.MemoryCandidateDAO
import app.amber.agent.data.db.dao.MemoryDAO
import app.amber.agent.data.db.dao.MemoryEventDAO
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

private typealias Reranker = app.amber.core.jev.MemorySemanticReranker

/** 语义重排接线的行为验证：active 重排生效、置顶强保留、shadow/回退零业务变更。 */
class MemoryRecallSemanticTest {

    private class FakeReranker(var result: MemorySemanticResult = MemorySemanticResult.NOT_APPLIED) : Reranker {
        var invoked = 0
        var lastCandidates: List<MemoryRecord> = emptyList()
        var lastTaskText: String? = null

        override suspend fun rerank(records: List<MemoryRecord>, taskText: String, runKey: String?): MemorySemanticResult {
            invoked++
            lastCandidates = records
            lastTaskText = taskText
            return result
        }
    }

    private class FakeRepo(records: List<MemoryRecord>) : MemoryRepository(
        memoryDAO = dummyDao(MemoryDAO::class.java),
        candidateDAO = dummyDao(MemoryCandidateDAO::class.java),
        eventDAO = dummyDao(MemoryEventDAO::class.java),
    ) {
        val touched = mutableListOf<Int>()

        override suspend fun getActiveRecords(scopes: Set<MemoryScope>, now: Long): List<MemoryRecord> = _records

        override suspend fun touchMemories(ids: List<Int>, usedAt: Long) {
            touched += ids
        }

        private val _records = records
    }

    private fun record(
        id: Int,
        content: String,
        pinned: Boolean = false,
        updatedAt: Long = 1_000_000L,
        scope: MemoryScope = MemoryScope.CORE,
    ) = MemoryRecord(
        id = id,
        content = content,
        scope = scope,
        kind = MemoryKind.NOTE,
        assistantId = "a",
        pinned = pinned,
        updatedAt = updatedAt,
    )

    private fun settings() = Settings(agentRuntime = AgentRuntimeSetting())

    private fun userMessage(text: String) = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    @Test
    fun activeRerankReordersAndSurfacesZeroLexicalRecord() = runTest {
        val lexicalHit = record(id = 1, content = "素食 vegetarian diet preference")
        val zeroLexical = record(id = 2, content = "饮食偏好：不吃肉")
        val reranker = FakeReranker(MemorySemanticResult(rankedIds = listOf(2, 1), applied = true))
        val store = MemoryRecallStore(FakeRepo(listOf(lexicalHit, zeroLexical)), reranker)
        val prompt = store.buildPrompt(settings(), listOf(userMessage("vegetarian")), runKey = "run-1")
        assertTrue(zeroLexical.content in prompt)
        assertTrue(lexicalHit.content in prompt)
        assertTrue(prompt.indexOf(zeroLexical.content) < prompt.indexOf(lexicalHit.content))
        assertEquals("vegetarian", reranker.lastTaskText)
        assertEquals(setOf(2, 1), reranker.lastCandidates.map { it.id }.toSet())
    }

    @Test
    fun pinnedSurvivesEvenWhenDroppedByRerank() = runTest {
        val pinned = record(id = 1, content = "pinned keeper", pinned = true)
        val normal = record(id = 2, content = "normal note about plants")
        val reranker = FakeReranker(MemorySemanticResult(rankedIds = listOf(2), applied = true))
        val store = MemoryRecallStore(FakeRepo(listOf(pinned, normal)), reranker)
        val prompt = store.buildPrompt(settings(), listOf(userMessage("plants")), runKey = "run-1")
        assertTrue(pinned.content in prompt)
        assertTrue(normal.content in prompt)
    }

    @Test
    fun notAppliedFallsBackToBaselineAndTouchesBaselineSelection() = runTest {
        val hit = record(id = 1, content = "vegetarian preference")
        // SHORT_TERM + 零词面：基线过滤会丢弃（CORE scope 的记忆 always-eligible 必注入）
        val other = record(id = 2, content = "unrelated weather note", scope = MemoryScope.SHORT_TERM)
        val reranker = FakeReranker(MemorySemanticResult.NOT_APPLIED)
        val fakeRepo = FakeRepo(listOf(hit, other))
        val store = MemoryRecallStore(fakeRepo, reranker)
        val prompt = store.buildPrompt(settings(), listOf(userMessage("vegetarian")), runKey = "run-1")
        assertTrue(hit.content in prompt)
        assertTrue(other.content !in prompt)
        assertEquals(listOf(1), fakeRepo.touched)
    }

    @Test
    fun noRerankerKeepsOriginalPath() = runTest {
        val hit = record(id = 1, content = "vegetarian preference")
        val store = MemoryRecallStore(FakeRepo(listOf(hit)))
        val prompt = store.buildPrompt(settings(), listOf(userMessage("vegetarian")))
        assertTrue(hit.content in prompt)
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private fun <T> dummyDao(iface: Class<T>): T = Proxy.newProxyInstance(
            MemoryRecallSemanticTest::class.java.classLoader,
            arrayOf(iface),
        ) { _, method, _ -> throw UnsupportedOperationException("unexpected ${method.name} on dummy DAO") } as T
    }
}
