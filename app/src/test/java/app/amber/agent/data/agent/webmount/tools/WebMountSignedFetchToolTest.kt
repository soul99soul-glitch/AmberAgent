package app.amber.feature.webmount.tools

import android.app.Application
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.webmount.primitives.WebViewPool
import app.amber.feature.webmount.profile.HostShimRegistry
import app.amber.feature.webmount.profile.ProfileBridge
import app.amber.feature.webmount.profile.ProfileRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebMountSignedFetchToolTest {

    @Test
    fun executeRejectsHttpUrlWithoutHostBeforeSessionLookup() {
        val context = RuntimeEnvironment.getApplication()
        val profileRegistry = ProfileRegistry(
            context = context,
            builtInLoader = object : ProfileRegistry.BuiltInLoader {
                override fun load(): List<Pair<String, String>> = emptyList()
            },
        )
        val tool = createSignedFetchTool(
            deps = WebMountDeps(
                pool = WebViewPool(context),
                activityStore = AgentToolActivityStore(),
                context = context,
            ),
            profileRegistry = profileRegistry,
            profileBridge = ProfileBridge(HostShimRegistry(context)),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                tool.execute(buildJsonObject {
                    put("session_id", "missing")
                    put("url", "https:///evil.example/path")
                })
            }
        }
    }
}
