package app.amber.feature.webmount

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.tools.ToolRegistry
import app.amber.feature.webmount.tools.WEBMOUNT_CONVERSATION_ID
import app.amber.feature.webmount.tools.WEBMOUNT_RUN_ID
import app.amber.feature.webmount.tools.WebMountDeps
import app.amber.feature.webmount.tools.createSiteAddTool
import app.amber.feature.webmount.tools.withWebMountScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.feature.webmount.primitives.WebMountSessionOwner
import app.amber.feature.webmount.primitives.WebViewPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebMountScopeAndPolicyTest {
    @Test
    fun hostScopeReplacesModelSuppliedIdentityAndFailsClosedWhenAbsent() {
        val input = buildJsonObject {
            put("session_id", "session-1")
            put(WEBMOUNT_CONVERSATION_ID, "forged-conversation")
            put(WEBMOUNT_RUN_ID, "forged-run")
        }

        val scoped = input.withWebMountScope("conversation-1", "run-1")
        assertEquals("conversation-1", scoped.jsonObject[WEBMOUNT_CONVERSATION_ID]?.jsonPrimitive?.content)
        assertEquals("run-1", scoped.jsonObject[WEBMOUNT_RUN_ID]?.jsonPrimitive?.content)

        val unscoped = input.withWebMountScope(null, null)
        assertFalse(unscoped.jsonObject.containsKey(WEBMOUNT_CONVERSATION_ID))
        assertFalse(unscoped.jsonObject.containsKey(WEBMOUNT_RUN_ID))
    }

    @Test
    fun siteAddFactoryAndRegistryPolicyRequireExplicitApproval() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pool = WebViewPool(context)
        val owner = WebMountSessionOwner(context, pool)
        val tool = createSiteAddTool(
            deps = WebMountDeps(pool, AgentToolActivityStore(), owner),
            userSiteRegistry = app.amber.feature.webmount.usersites.UserSiteRegistry(context),
        )

        assertTrue(tool.needsApproval)
        assertFalse(tool.allowsAutoApproval)
        assertTrue(tool.mandatoryApproval)

        val metadata = ToolRegistry.from(listOf(tool)).metadataFor("wm_site_add")
        assertTrue(metadata?.needsApproval == true)
        assertFalse(metadata?.autoApprovable == true)
        assertTrue(metadata?.mandatoryApproval == true)
    }
}
