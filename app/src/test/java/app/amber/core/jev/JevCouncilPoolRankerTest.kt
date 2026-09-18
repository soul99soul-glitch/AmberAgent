package app.amber.core.jev

import app.amber.ai.provider.Model
import app.amber.ai.provider.OpenAIBrand
import app.amber.ai.provider.ProviderSetting
import app.amber.core.settings.Settings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/** 议会池模型调度：active 重排、shadow/低分回退 null、合法池子集校验。 */
class JevCouncilPoolRankerTest {

    private class ScriptedTransport(private val body: String?) : JevTransport {
        var calls = 0

        override suspend fun execute(request: JevHttpRequest): JevTransportResponse {
            calls++
            val payload = body ?: return JevTransportResponse.Failure("down")
            return JevTransportResponse.Http(200, payload.toByteArray(), null)
        }
    }

    private fun runtime(transport: JevTransport, mode: JevMode): JevRuntime {
        val settings = Settings(
            jev = JevSetting(
                enabled = true,
                purposes = mapOf(JevPurpose.MODEL_ROUTING to mode),
                dataScopes = setOf(JevDataScope.TASK_TEXT, JevDataScope.TOOL_METADATA),
            ),
        )
        return JevRuntime(
            coordinator = JevDecisionCoordinator(
                client = JevClient(transport = transport),
                apiKeyProvider = { "key" },
                clock = { 1_000_000L },
            ),
            settingsProvider = { settings },
        )
    }

    private fun settingsWithModels(): Settings {
        val m1 = Uuid.random()
        val m2 = Uuid.random()
        val m3 = Uuid.random()
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "Test Provider",
            models = listOf(
                Model(id = m1, displayName = "Fast Model"),
                Model(id = m2, displayName = "Vision Model"),
                Model(id = m3, displayName = "Deep Model"),
            ),
            brand = OpenAIBrand.GENERIC,
        )
        return Settings(chatModelId = m3, providers = listOf(provider))
    }

    private fun councilInput(objective: String = "analyze the screenshot for layout issues") = buildJsonObject {
        put(
            "task",
            buildJsonObject {
                put("objective", objective)
                put("context", "a mobile screenshot was captured")
            },
        )
    }

    @Test
    fun activeRankingReordersPool() = runTest {
        val settings = settingsWithModels()
        val ids = app.amber.feature.modelcouncil.ModelCouncilValidator
            .defaultPoolModelIds(settings, settings.agentRuntime.modelCouncil)
        assertEquals(3, ids.size)
        // Jev 认为第 2、3 个模型适配（第 1 个低分被筛掉），且按分数排序
        val probabilities = listOf(0.05, 0.9, 0.8)
        val answer = ids.indices.joinToString(",") { index ->
            """"${ids[index]}":{"type":"noul","noul":${probabilities[index]}}"""
        }
        val transport = ScriptedTransport("""{"model":"jev-test","answers":{$answer}}""")
        val ranked = JevCouncilPoolRanker(runtime(transport, JevMode.ACTIVE))
            .rank(councilInput(), settings, settings.agentRuntime.modelCouncil)
        assertEquals(1, transport.calls)
        assertEquals(listOf(ids[1], ids[2]), ranked)
    }

    @Test
    fun shadowNeverApplies() = runTest {
        val settings = settingsWithModels()
        val ids = app.amber.feature.modelcouncil.ModelCouncilValidator
            .defaultPoolModelIds(settings, settings.agentRuntime.modelCouncil)
        val answer = ids.joinToString(",") { """"${it}":{"type":"noul","noul":0.9}""" }
        val transport = ScriptedTransport("""{"model":"jev-test","answers":{$answer}}""")
        val ranked = JevCouncilPoolRanker(runtime(transport, JevMode.SHADOW))
            .rank(councilInput(), settings, settings.agentRuntime.modelCouncil)
        assertNull(ranked)
        assertEquals(1, transport.calls)
    }

    @Test
    fun transportFailureReturnsNull() = runTest {
        val settings = settingsWithModels()
        val ranked = JevCouncilPoolRanker(runtime(ScriptedTransport(null), JevMode.ACTIVE))
            .rank(councilInput(), settings, settings.agentRuntime.modelCouncil)
        assertNull(ranked)
    }

    @Test
    fun blankObjectiveShortCircuits() = runTest {
        val settings = settingsWithModels()
        val transport = ScriptedTransport("""{"answers":{}}""")
        val ranked = JevCouncilPoolRanker(runtime(transport, JevMode.ACTIVE))
            .rank(councilInput(objective = " "), settings, settings.agentRuntime.modelCouncil)
        assertNull(ranked)
        assertEquals(0, transport.calls)
    }

    @Test
    fun fewerThanTwoSuitableReturnsNull() = runTest {
        val settings = settingsWithModels()
        val ids = app.amber.feature.modelcouncil.ModelCouncilValidator
            .defaultPoolModelIds(settings, settings.agentRuntime.modelCouncil)
        // 只有 1 个过阈值 → 议会至少两席，回退原轮转
        val answer = ids.joinToString(",") { """"${it}":{"type":"noul","noul":${if (it == ids[0]) 0.9 else 0.05}}""" }
        val transport = ScriptedTransport("""{"model":"jev-test","answers":{$answer}}""")
        val ranked = JevCouncilPoolRanker(runtime(transport, JevMode.ACTIVE))
            .rank(councilInput(), settings, settings.agentRuntime.modelCouncil)
        assertNull(ranked)
        assertTrue(transport.calls >= 1)
    }
}
