package app.amber.core.ai.tools

import android.content.Context
import app.amber.ai.ui.UIMessagePart
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.jev.JevApiMode
import app.amber.core.jev.JevClient
import app.amber.core.jev.JevDataScope
import app.amber.core.jev.JevDecisionCoordinator
import app.amber.core.jev.JevMode
import app.amber.core.jev.JevPurpose
import app.amber.core.jev.JevSetting
import app.amber.core.jev.JevTransport
import app.amber.core.jev.JevTransportResponse
import app.amber.core.settings.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** jev_status 只读内省：配置/范围/日用量/运行态原样透出，绝不回传 Key 与判题原文。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class JevStatusToolTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private val noNetworkTransport = JevTransport {
        JevTransportResponse.Failure("offline")
    }

    private fun coordinator(): JevDecisionCoordinator = JevDecisionCoordinator(
        client = JevClient(transport = noNetworkTransport),
        apiKeyProvider = { "secret-key" },
        clock = { 1_000_000L },
    )

    private fun payload(settings: Settings): kotlinx.serialization.json.JsonObject = runBlocking {
        val tool = createJevStatusTool(
            settingsProvider = { settings },
            coordinator = coordinator(),
            displayContext = context,
        )
        val text = (tool.execute(buildJsonObject {}).single() as UIMessagePart.Text).text
        JsonInstant.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `default settings report disabled and every purpose off`() {
        val out = payload(Settings())

        assertEquals(false, out["enabled"]!!.jsonPrimitive.content.toBooleanStrict())
        assertEquals(false, out["api_key_configured"]!!.jsonPrimitive.content.toBooleanStrict())
        val purposes = out["purposes"]!!.jsonObject
        JevPurpose.entries.forEach { purpose ->
            assertEquals(
                "off",
                purposes.getValue(purpose.name).jsonObject.getValue("mode").jsonPrimitive.content,
            )
        }
        assertEquals(0, out["data_scopes_allowed"]!!.let { it as kotlinx.serialization.json.JsonArray }.size)
        assertEquals(0, out["daily_usage"]!!.jsonObject.getValue("requests").jsonPrimitive.content.toInt())
        assertEquals(false, out["runtime"]!!.jsonObject.getValue("auth_paused").jsonPrimitive.content.toBooleanStrict())
    }

    @Test
    fun `configured settings surface modes scopes mask and model`() {
        val settings = Settings(
            jev = JevSetting(
                enabled = true,
                model = "jev-2026-09",
                purposes = mapOf(
                    JevPurpose.TOOL_DISCOVERY to JevMode.ACTIVE,
                    JevPurpose.MEMORY_RECALL to JevMode.SHADOW,
                ),
                dataScopes = setOf(JevDataScope.TOOL_METADATA, JevDataScope.TASK_TEXT),
                apiKeyMask = "abc…wxyz",
            ),
        )

        val out = payload(settings)

        assertEquals(true, out["enabled"]!!.jsonPrimitive.content.toBooleanStrict())
        assertEquals("jev-2026-09", out["model"]!!.jsonPrimitive.content)
        assertEquals(true, out["api_key_configured"]!!.jsonPrimitive.content.toBooleanStrict())
        assertEquals("abc…wxyz", out["api_key_mask"]!!.jsonPrimitive.content)
        val purposes = out["purposes"]!!.jsonObject
        assertEquals("active", purposes.getValue("TOOL_DISCOVERY").jsonObject.getValue("mode").jsonPrimitive.content)
        assertEquals("shadow", purposes.getValue("MEMORY_RECALL").jsonObject.getValue("mode").jsonPrimitive.content)
        assertEquals("off", purposes.getValue("MODEL_ROUTING").jsonObject.getValue("mode").jsonPrimitive.content)
        val scopes = out["data_scopes_allowed"]!!.let {
            (it as kotlinx.serialization.json.JsonArray).map { s -> s.jsonPrimitive.content }.toSet()
        }
        assertEquals(setOf("TOOL_METADATA", "TASK_TEXT"), scopes)
        assertEquals(JevDataScope.entries.size, (out["data_scopes_supported"] as kotlinx.serialization.json.JsonArray).size)
    }

    @Test
    fun `payload never embeds the raw api key`() {
        val settings = Settings(jev = JevSetting(enabled = true, apiKeyMask = "abc…wxyz"))

        val out = payload(settings)

        assertFalse(out.toString().contains("secret-key"))
    }

    @Test
    fun `metrics and runtime sections are always present`() {
        val out = payload(Settings())

        val metrics = out["recent_metrics"]!!.jsonObject
        assertEquals(0, metrics.getValue("total").jsonPrimitive.content.toInt())
        assertEquals(0, metrics.getValue("applied").jsonPrimitive.content.toInt())
        assertEquals(0, out["runtime"]!!.jsonObject.getValue("cooldown_remaining_ms").jsonPrimitive.content.toLong())
        assertTrue(out.containsKey("about"))
        assertTrue(out.containsKey("settings_path"))
        assertTrue(out.containsKey("endpoint"))
    }

    @Test
    fun `vercel mode reports api mode resolved endpoint and model nullability`() {
        val out = payload(
            Settings(
                jev = JevSetting(
                    enabled = true,
                    apiMode = JevApiMode.VERCEL,
                    baseUrl = "https://gw.example/v1/",
                ),
            ),
        )

        assertEquals("vercel", out["api_mode"]!!.jsonPrimitive.content)
        assertEquals(
            "https://gw.example/v1/evaluation-model",
            out["endpoint"]!!.jsonPrimitive.content,
        )
        // VERCEL 评估模型有标准缺省：未配置时透出生效值而非 null
        assertEquals("typesafe-ai/jev", out["model"]!!.jsonPrimitive.content)

        val configured = payload(
            Settings(jev = JevSetting(enabled = true, apiMode = JevApiMode.VERCEL, vercelModel = "acme/eval-v2")),
        )
        assertEquals("acme/eval-v2", configured["model"]!!.jsonPrimitive.content)
        assertEquals(
            "https://ai-gateway.vercel.sh/v4/ai/evaluation-model",
            configured["endpoint"]!!.jsonPrimitive.content,
        )
    }
}
