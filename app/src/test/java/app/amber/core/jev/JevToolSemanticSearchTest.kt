package app.amber.core.jev

import app.amber.core.settings.Settings
import app.amber.feature.tools.SemanticToolCandidate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 工具发现：shadow 不阻塞词面结果，判断在后台完成。 */
class JevToolSemanticSearchTest {

    @Test
    fun shadowReturnsWithoutWaitingForJev() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val transport = object : JevTransport {
            override suspend fun execute(request: JevHttpRequest): JevTransportResponse {
                gate.await()
                calls++
                return JevTransportResponse.Http(
                    200,
                    """{"model":"jev-test","answers":{"a":{"type":"noul","noul":0.9},"b":{"type":"noul","noul":0.1}}}""".toByteArray(),
                    null,
                )
            }
        }
        val settings = Settings(
            jev = JevSetting(
                enabled = true,
                purposes = mapOf(JevPurpose.TOOL_DISCOVERY to JevMode.SHADOW),
                dataScopes = setOf(JevDataScope.TOOL_METADATA, JevDataScope.TASK_TEXT),
            ),
        )
        val runtime = JevRuntime(
            coordinator = JevDecisionCoordinator(
                client = JevClient(transport = transport),
                apiKeyProvider = { "key" },
                clock = { 1_000_000L },
            ),
            settingsProvider = { settings },
            backgroundScope = backgroundScope,
        )
        val result = JevToolSemanticSearch(runtime).forRun("run-1").rerank(
            "read a file",
            null,
            5,
            listOf(
                SemanticToolCandidate("a", "files", "Read a file"),
                SemanticToolCandidate("b", "web", "Search the web"),
            ),
        )

        assertNull(result)
        assertEquals(0L, testScheduler.currentTime)
        gate.complete(Unit)
        // backgroundScope 的任务不计入 advanceUntilIdle，用 runCurrent 执行后台 shadow。
        runCurrent()
        assertEquals(1, calls)
    }
}
