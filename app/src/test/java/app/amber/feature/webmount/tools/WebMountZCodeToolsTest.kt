package app.amber.feature.webmount.tools

import android.app.Application
import app.amber.ai.ui.UIMessagePart
import app.amber.core.infra.AppScope
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.ui.pages.zcode.ZCodeUrlStore
import app.amber.feature.webmount.cookie.WebMountCookieProvider
import app.amber.feature.webmount.core.WebMountManager
import app.amber.feature.webmount.primitives.WebMountSessionOwner
import app.amber.feature.webmount.primitives.WebViewPool
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebMountZCodeToolsTest {
    @Test
    fun connectionOptInAndSelectedSessionAreCheckedBeforeAllocatingBrowser() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val scope = AppScope()
        val activity = AgentToolActivityStore()
        val pool = WebViewPool(context)
        val store = ZCodeUrlStore(context)
        val manager = WebMountManager(context, emptyList(), WebMountCookieProvider(), activity, scope)
        val tools = WebMountZCodeTools(WebMountDeps(pool, activity, WebMountSessionOwner(context, pool)), store, manager)
            .tools().associateBy { it.name }
        val input = buildJsonObject { put("session_id", "unrelated") }.withWebMountScope("chat-a", "run-a")
        suspend fun error(name: String): String? = Json.parseToJsonElement(
            (tools.getValue(name).execute(input).single() as UIMessagePart.Text).text,
        ).jsonObject["error"]?.jsonPrimitive?.content
        try {
            store.save("https://zcode.z.ai/remote/v4?token=not-a-real-token")
            store.setAgentEnabled(false)
            manager.setGlobalEnabled(true)
            assertEquals("zcode_agent_disabled", error("wm_zcode_open"))
            store.setAgentEnabled(true)
            manager.setGlobalEnabled(false)
            assertEquals("webmount_disabled", error("wm_zcode_open"))
            manager.setGlobalEnabled(true)
            val discovery = Json.parseToJsonElement(
                (tools.getValue("wm_zcode_open").execute(input).single() as UIMessagePart.Text).text,
            ).jsonObject
            assertEquals("https://zcode.z.ai", discovery["connection_origin"]?.jsonPrimitive?.content)
            assertEquals("false", discovery["browser_opened"]?.jsonPrimitive?.content)
            assertFalse(discovery.toString().contains("not-a-real-token"))
            store.recordSession("https://zcode.z.ai/remote/v4?token=not-a-real-token", "selected")
            assertEquals("zcode_session_mismatch", error("wm_zcode_read"))
            assertTrue(pool.listSessions().isEmpty())
            assertFalse(activity.sandboxActivity.value?.inputPreview.orEmpty().contains("not-a-real-token"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun openCannotAcquireAnUnscopedConversation() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val scope = AppScope()
        val activity = AgentToolActivityStore()
        val pool = WebViewPool(context)
        try {
            val tool = WebMountZCodeTools(
                WebMountDeps(pool, activity, WebMountSessionOwner(context, pool)),
                ZCodeUrlStore(context),
                WebMountManager(context, emptyList(), WebMountCookieProvider(), activity, scope),
            ).tools().first { it.name == "wm_zcode_open" }
            val result = Json.parseToJsonElement((tool.execute(buildJsonObject {}).single() as UIMessagePart.Text).text)
            assertEquals("missing_conversation", result.jsonObject["error"]?.jsonPrimitive?.content)
            assertTrue(pool.listSessions().isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun originCheckRejectsAChangedHostSchemePortOrUserInfo() {
        val saved = "https://zcode.z.ai/remote/v4?token=secret"
        assertTrue(sameZCodeOrigin(saved, "https://zcode.z.ai:443/remote/v4"))
        assertFalse(sameZCodeOrigin(saved, "https://zcode.z.ai.evil.test/"))
        assertFalse(sameZCodeOrigin(saved, "http://zcode.z.ai/"))
        assertFalse(sameZCodeOrigin(saved, "https://zcode.z.ai:8443/"))
        assertFalse(sameZCodeOrigin(saved, "https://user@zcode.z.ai/"))
        assertFalse(sameZCodeOrigin(saved, null))
        assertEquals("https://zcode.z.ai", zCodeOrigin(saved))
        assertTrue(validZCodeOriginArgument("https://zcode.z.ai/", saved))
        assertFalse(validZCodeOriginArgument(saved, saved))
        assertFalse(validZCodeOriginArgument("https://zcode.z.ai/remote/v4", saved))
        assertFalse(validZCodeOriginArgument("https://zcode.z.ai/#secret", saved))
        assertFalse(validZCodeOriginArgument("https://other.test", saved))
    }
}
