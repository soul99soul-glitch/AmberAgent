package app.amber.agent

import android.app.UiAutomation
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.core.automation.getActiveAccessibilityController
import app.amber.core.di.JevApiKeyDescriptor
import app.amber.core.jev.JevDataScope
import app.amber.core.jev.JevMode
import app.amber.core.jev.JevPurpose
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.secret.SecretStore
import app.amber.feature.tools.ScreenAutomationTools
import app.amber.ai.ui.UIMessagePart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File

/** Explicit opt-in: uses the user's saved Jev key and real Accessibility service + HTTP. */
class JevScreenDeviceTest {
    private suspend fun connectAccessibility() {
        AmberS9DeviceSupport.enableAmberAccessibility()
        withTimeout(20_000) { while (getActiveAccessibilityController() == null) delay(250) }
    }

    private suspend fun openFixture() {
        AmberS9DeviceSupport.launchTestPackageActivity("app.amber.agent.JevScreenFixtureActivity")
        AmberS9DeviceSupport.waitForSnapshotPackage(
            AmberS9DeviceSupport.targetPackage,
            timeoutMs = 10_000,
        )
    }

    @org.junit.After
    fun restoreAccessibility() {
        AmberS9DeviceSupport.restoreAmberAccessibility()
    }

    @Test
    fun nativeSnapshotsRejectStaleAndVerifyLocalActions() = runBlocking {
        AmberS9DeviceSupport.assumeUnlocked()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        connectAccessibility()
        val testPackage = AmberS9DeviceSupport.targetPackage
        openFixture()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val controller = getActiveAccessibilityController()!!
            val before = controller.captureScreenSnapshot()!!
            assertEquals(testPackage, before.packageName)
            val target = before.nodes.single { it.clickable && it.label == "打开评论列表" }
            val action = app.amber.core.automation.ScreenAction(app.amber.core.automation.ScreenActionKind.CLICK, target.ref)
            val stale = controller.performScreenAction(before.copy(id = "expired"), action)
            assertFalse(stale.dispatched)
            assertEquals("stale_snapshot", stale.status)
            assertEquals("ok", controller.performScreenAction(before, action).status)
        }
        delay(600)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val snapshot = getActiveAccessibilityController()!!.captureScreenSnapshot()!!
            assertTrue(snapshot.nodes.any { it.label.contains("评论列表第 1 页") })
        }
    }

    @Test
    fun realJevBilibiliSearch() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        AmberS9DeviceSupport.assumeUnlocked()
        AmberS9DeviceSupport.assumeS9FixtureApplication()
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("bilibiliJev") == "true")
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsAggregator>()
        withTimeout(15_000) { settings.settingsFlow.first { !it.init } }
        assertEquals(JevMode.ACTIVE, settings.settingsFlow.value.jev.modeFor(JevPurpose.PHONE_ACTIONS))
        connectAccessibility()
        // W08：多步自控只进显式授权的应用；测试操作员在此显式授权。
        settings.update { current ->
            val scope = current.agentRuntime.screenAutomation
            current.copy(
                agentRuntime = current.agentRuntime.copy(
                    screenAutomation = scope.copy(
                        allowedPackages = (scope.allowedPackages + "tv.danmaku.bili").distinct(),
                    ),
                ),
            )
        }
        val tool = koin.get<ScreenAutomationTools>().getTools("device-bilibili-${System.nanoTime()}")
            .single { it.name == "screen_run_goal" }
        val output = tool.execute(buildJsonObject {
            put("package_name", "tv.danmaku.bili")
            put("goal", "打开搜索界面，在搜索输入框填写“两颗皮蛋”。看到输入框中的文字即完成，停留在搜索页，不提交搜索。")
            put("texts", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("两颗皮蛋")) })
            put("max_steps", 8)
            put("max_duration_ms", 30_000)
        }).filterIsInstance<UIMessagePart.Text>().single().text
        File(instrumentation.targetContext.filesDir, "jev-screen-bilibili-result.json").writeText(output)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "JEV_BILIBILI_RESULT=$output\n") })
        val result = Json.parseToJsonElement(output).jsonObject
        assertEquals("completed", result["status"]!!.jsonPrimitive.content)
        assertTrue(result["jev_decisions"]!!.jsonPrimitive.content.toInt() > 0)
        assertTrue(result["visible_text"]!!.jsonPrimitive.content.contains("两颗皮蛋"))
    }

    @Test
    fun realJevRunsNativeGoalThroughProductionTool() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        AmberS9DeviceSupport.assumeUnlocked()
        AmberS9DeviceSupport.assumeS9FixtureApplication()
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("realJev") == "true")
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsAggregator>()
        withTimeout(15_000) { settings.settingsFlow.first { !it.init } }
        assertFalse("A saved Jev key is required", koin.get<SecretStore>().read(JevApiKeyDescriptor).isNullOrBlank())
        // This opt-in test also enables the feature requested by the device owner.
        settings.update { current -> current.copy(jev = current.jev.copy(
            enabled = true,
            purposes = current.jev.purposes +
                (JevPurpose.PHONE_VERIFICATION to JevMode.ACTIVE) +
                (JevPurpose.PHONE_ACTIONS to JevMode.ACTIVE),
            dataScopes = current.jev.dataScopes + JevDataScope.SCREEN_CONTENT + JevDataScope.TASK_TEXT,
        )) }
        withTimeout(10_000) { settings.settingsFlow.first {
            it.jev.modeFor(JevPurpose.PHONE_VERIFICATION) == JevMode.ACTIVE &&
                it.jev.modeFor(JevPurpose.PHONE_ACTIONS) == JevMode.ACTIVE &&
                JevDataScope.SCREEN_CONTENT in it.jev.dataScopes
        } }
        connectAccessibility()
        val testPackage = AmberS9DeviceSupport.targetPackage
        // W08：先显式授权测试夹具包名，再跑多步目标。
        settings.update { current ->
            val scope = current.agentRuntime.screenAutomation
            current.copy(
                agentRuntime = current.agentRuntime.copy(
                    screenAutomation = scope.copy(
                        allowedPackages = (scope.allowedPackages + testPackage).distinct(),
                    ),
                ),
            )
        }
        openFixture()
        val tool = koin.get<ScreenAutomationTools>().getTools("device-jev-${System.nanoTime()}")
            .single { it.name == "screen_run_goal" }
        assertTrue(tool.mandatoryApproval)
        assertFalse(tool.allowsAutoApproval)
        // The test operator explicitly authorizes this fixture goal; normal chats use the kernel approval gate.
        val output = tool.execute(buildJsonObject {
            put("package_name", testPackage)
            put("goal", "打开评论列表，再点击下一页，直到屏幕显示“琥珀验证完成”。")
            put("max_steps", 6)
            put("max_duration_ms", 30_000)
        }).filterIsInstance<UIMessagePart.Text>().single().text
        val result = Json.parseToJsonElement(output).jsonObject
        File(instrumentation.targetContext.filesDir, "jev-screen-device-result.json").writeText(output)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "JEV_SCREEN_RESULT=$output\n") })
        assertEquals("completed", result["status"]!!.jsonPrimitive.content)
        assertEquals("2", result["actions_dispatched"]!!.jsonPrimitive.content)
        assertTrue(result["jev_decisions"]!!.jsonPrimitive.content.toInt() >= 3)
        assertTrue(result["visible_text"]!!.jsonPrimitive.content.contains("琥珀验证完成"))
    }
}
