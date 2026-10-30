package app.amber.agent

import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.ui.UIMessage
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.repository.ConversationRepository
import app.amber.core.service.ChatService
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.java.KoinJavaComponent.getKoin
import kotlin.uuid.Uuid

/** No Compose test clock: the OS and Compose render return gestures at native frame cadence. */
class ChatNativeMenuBackDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test(timeout = 120_000)
    fun nativeBackAndEdgeGesturesDismissOnlyTheMenu(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("amberDeviceRegression") == "true")
        check(context.applicationContext is ChatDeviceFixtureApplication)
        Thread.sleep(1_500) // Let the device script's foreground kick finish.
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        event(SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0f, 0f)
        val settings = getKoin().get<SettingsAggregator>()
        withTimeout(15_000) { settings.settingsFlow.first { !it.init } }
        val model = Model(modelId = "native-menu", displayName = "Native menu regression")
        val provider = ProviderSetting.OpenAI(name = "Local fixture", models = listOf(model), apiKey = "fixture-only")
        settings.update(Settings().copy(providers = listOf(provider), chatModelId = model.id))
        val repository = getKoin().get<ConversationRepository>()
        val service = getKoin().get<ChatService>()
        val id = Uuid.random()
        val marker = "NATIVE_MENU_BACK_REPLY"
        repository.insertConversation(Conversation.ofId(id, messages = listOf(
            MessageNode.of(UIMessage.user("Check native back gestures")),
            MessageNode.of(UIMessage.assistant(marker)),
        )).copy(title = "Native menu back fixture"))
        try {
            shell("am start -W -n ${context.packageName}/androidx.activity.ComponentActivity")
            ActivityScenario.launch<RouteActivity>(Intent(context, RouteActivity::class.java)
                .putExtra("conversationId", id.toString())).use {
                it.onActivity { activity -> activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                awaitNode(marker)
                Thread.sleep(1_000)
                for (mode in listOf("key", "left", "right", "cancel-left", "down")) {
                    val reply = awaitNode(marker)
                    touch(reply.centerX().toFloat(), reply.centerY().toFloat(), holdMs = 700)
                    awaitNode(context.getString(R.string.copy))
                    Thread.sleep(700)
                    val display = instrumentation.uiAutomation.rootInActiveWindow!!.let { root ->
                        Rect().also { root.getBoundsInScreen(it) }
                    }
                    val copy = awaitNode(context.getString(R.string.copy))
                    val edgeContrast = screenEdgeContrast(copy.centerY())
                    val assertNoReturnScaling = {
                        val during = screenEdgeContrast(copy.centerY())
                        Log.i(TAG, "EDGE mode=$mode before=$edgeContrast during=$during")
                        check(during <= edgeContrast + 12) {
                            "Back gesture exposed the background beside the menu: contrast $edgeContrast -> $during"
                        }
                    }
                    Log.i(TAG, "BEGIN mode=$mode uptime=${SystemClock.uptimeMillis()} display=$display")
                    when (mode) {
                        "key" -> shell("input keyevent KEYCODE_BACK")
                        "left", "cancel-left" -> drag(1f, display.height() * .58f,
                            display.width() * (if (mode == "cancel-left") .12f else .35f),
                            display.height() * .58f, cancel = mode == "cancel-left",
                            midpoint = assertNoReturnScaling)
                        "right" -> drag(display.width() - 2f, display.height() * .58f,
                            display.width() * .65f, display.height() * .58f, midpoint = assertNoReturnScaling)
                        else -> drag(display.centerX().toFloat(), display.height() * .4f,
                            display.centerX().toFloat(), display.height() - 10f)
                    }
                    Thread.sleep(1_200)
                    if (mode == "cancel-left") {
                        check(find(context.getString(R.string.copy)) != null) { "Canceled back dismissed the menu" }
                        shell("input keyevent KEYCODE_BACK")
                    }
                    val end = SystemClock.uptimeMillis() + 5_000
                    while (find(context.getString(R.string.copy)) != null && SystemClock.uptimeMillis() < end) {
                        Thread.sleep(50)
                    }
                    check(find(context.getString(R.string.copy)) == null) { "$mode did not dismiss the menu" }
                    check(find(marker) != null) { "$mode also left the conversation" }
                    Log.i(TAG, "END mode=$mode uptime=${SystemClock.uptimeMillis()}")
                }
            }
        } finally {
            repository.getConversationById(id)?.let { service.deleteConversation(it) }
        }
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    private fun find(text: String): Rect? {
        fun visit(node: AccessibilityNodeInfo): Rect? {
            if (node.text?.toString()?.contains(text) == true) return Rect().also(node::getBoundsInScreen)
            repeat(node.childCount) { index -> node.getChild(index)?.let { visit(it)?.let { bounds -> return bounds } } }
            return null
        }
        return instrumentation.uiAutomation.windows.firstNotNullOfOrNull { it.root?.let(::visit) }
            ?: instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }

    private fun awaitNode(text: String): Rect {
        val end = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < end) {
            find(text)?.takeIf { !it.isEmpty }?.let { return it }
            Thread.sleep(100)
        }
        error("Native UI node did not appear: $text")
    }

    private fun event(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        val delivered = instrumentation.uiAutomation.injectInputEvent(event, true)
        event.recycle()
        if (action != MotionEvent.ACTION_CANCEL) check(delivered)
    }

    private fun screenEdgeContrast(y: Int): Int {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        return try {
            // Same-frame comparison excludes global HDR/notification dimming.
            val edge = bitmap.getPixel(4, y)
            val center = bitmap.getPixel(bitmap.width / 2, y)
            maxOf(kotlin.math.abs(Color.red(edge) - Color.red(center)),
                kotlin.math.abs(Color.green(edge) - Color.green(center)),
                kotlin.math.abs(Color.blue(edge) - Color.blue(center)))
        } finally { bitmap.recycle() }
    }

    private fun touch(x: Float, y: Float, holdMs: Long) {
        val down = SystemClock.uptimeMillis()
        event(down, MotionEvent.ACTION_DOWN, x, y)
        Thread.sleep(holdMs)
        event(down, MotionEvent.ACTION_UP, x, y)
    }

    private fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float,
        cancel: Boolean = false, midpoint: (() -> Unit)? = null) {
        val down = SystemClock.uptimeMillis()
        event(down, MotionEvent.ACTION_DOWN, fromX, fromY)
        var terminalAction = MotionEvent.ACTION_CANCEL
        try {
            repeat(24) { step ->
                Thread.sleep(16)
                val linear = (step + 1) / 24f
                // Cancel like a finger: drag back to the edge before lifting.
                // Synthetic ACTION_CANCEL is treated as a commit on some OEMs.
                val fraction = if (cancel) 1f - kotlin.math.abs(2f * linear - 1f) else linear
                event(down, MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * fraction, fromY + (toY - fromY) * fraction)
                if (step == 11) midpoint?.invoke()
            }
            terminalAction = MotionEvent.ACTION_UP
        } finally {
            event(down, terminalAction, if (cancel) fromX else toX, if (cancel) fromY else toY)
        }
    }

    private companion object { const val TAG = "AmberNativeMenuBack" }
}
