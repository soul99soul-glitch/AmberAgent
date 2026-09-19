package app.amber.agent

import android.app.Activity
import android.app.UiAutomation
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.content.Intent
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

/** Native UI fixture installed only in the test APK; no external-account side effects. */
class JevScreenFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showPage(0)
    }

    private fun showPage(page: Int) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 160, 48, 48)
            addView(TextView(context).apply {
                textSize = 24f
                text = when (page) {
                    0 -> "无障碍实测首页。请打开评论列表，再进入第二页。"
                    1 -> "评论列表第 1 页。评论甲：画质清晰。"
                    else -> "评论列表第 2 页。验证标记：琥珀验证完成。评论乙：声音清晰。"
                }
            })
            if (page < 2) addView(Button(context).apply {
                text = if (page == 0) "打开评论列表" else "下一页"
                setOnClickListener { showPage(page + 1) }
            })
            addView(Button(context).apply { text = "删除全部评论" })
        }
        setContentView(layout)
    }
}

/** Explicit opt-in: uses the user's saved Jev key and real Accessibility service + HTTP. */
class JevScreenDeviceTest {
    private suspend fun connectAccessibility() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        // Instrumentation force-stops its target; some OEMs do not rebind enabled services automatically.
        if (getActiveAccessibilityController() == null) {
            val resolver = instrumentation.targetContext.contentResolver
            val key = android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            val component = "${instrumentation.targetContext.packageName}/app.amber.core.automation.AmberAccessibilityService"
            val original = android.provider.Settings.Secure.getString(resolver, key).orEmpty()
            require(component in original.split(':')) { "Enable Amber Accessibility before testing" }
            automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            try {
                android.provider.Settings.Secure.putString(resolver, key, original.split(':').filterNot { it == component }.joinToString(":"))
                delay(250)
            } finally {
                try { android.provider.Settings.Secure.putString(resolver, key, original) }
                finally { automation.dropShellPermissionIdentity() }
            }
        }
        withTimeout(20_000) { while (getActiveAccessibilityController() == null) delay(250) }
    }

    private suspend fun openFixture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testPackage = instrumentation.context.packageName
        val fd = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand("am start -W -n $testPackage/app.amber.agent.JevScreenFixtureActivity --activity-clear-task --activity-new-task")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
        withTimeout(10_000) {
            while (kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                getActiveAccessibilityController()?.captureScreenSnapshot()?.packageName
            } != testPackage) delay(250)
        }
    }

    @Test
    fun nativeSnapshotsRejectStaleAndVerifyLocalActions() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        connectAccessibility()
        val testPackage = instrumentation.context.packageName
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
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("bilibiliJev") == "true")
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsAggregator>()
        withTimeout(15_000) { settings.settingsFlow.first { !it.init } }
        assertEquals(JevMode.ACTIVE, settings.settingsFlow.value.jev.modeFor(JevPurpose.SCREEN_AUTOMATION))
        connectAccessibility()
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
            purposes = current.jev.purposes + (JevPurpose.SCREEN_AUTOMATION to JevMode.ACTIVE),
            dataScopes = current.jev.dataScopes + JevDataScope.SCREEN_CONTENT + JevDataScope.TASK_TEXT,
        )) }
        withTimeout(10_000) { settings.settingsFlow.first {
            it.jev.modeFor(JevPurpose.SCREEN_AUTOMATION) == JevMode.ACTIVE && JevDataScope.SCREEN_CONTENT in it.jev.dataScopes
        } }
        connectAccessibility()
        val testPackage = instrumentation.context.packageName
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
