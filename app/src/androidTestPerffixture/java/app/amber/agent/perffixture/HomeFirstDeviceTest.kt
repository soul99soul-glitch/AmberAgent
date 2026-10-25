package app.amber.agent.perffixture

import android.os.SystemClock
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.core.repository.ConversationRepository
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.ui.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Run as its own instrumentation process; reads/probes happen on the Activity thread. */
@RunWith(AndroidJUnit4::class)
class HomeFirstDeviceTest {
    @Test(timeout = 110_000)
    fun productionHomeHasPlacedTargetAndArmingDoesNotStartTrace(): Unit {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val token = InstrumentationRegistry.getArguments().getString("homeTestToken")
            ?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,80}")) } ?: "local-${SystemClock.elapsedRealtime()}"
        fun checkpoint(stage: String, event: String) {
            // Test stages/identity only. Never settings values, message bodies or user content.
            Log.i("AmberHomeDeviceTest", "token=$token stage=$stage event=$event monoMs=${SystemClock.elapsedRealtime()}")
            if (event == "timeout") {
                // Class/method stacks locate a non-cooperative UI/DI stall without content.
                Thread.getAllStackTraces().entries
                    .filter { it.key.name == "main" || it.key.name == "home-device-stage" }
                    .forEach { (thread, stack) ->
                        stack.take(24).forEach {
                            Log.i("AmberHomeDeviceTest", "token=$token stage=$stage threadId=${thread.id} at=$it")
                        }
                    }
            }
        }
        checkpoint("test", "begin")
        HomeTestStages(90_000, ::checkpoint).use { stages ->
            HomeWindowAccess({ instrumentation.uiAutomation }) { event, access ->
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("homeWindowToken", token)
                    putString("homeWindowEvent", event)
                    putString("homeWindowFlags", JSONObject().apply {
                        put("before", access.flagsBefore ?: JSONObject.NULL)
                        put("enabled", access.flagsEnabled ?: JSONObject.NULL)
                        put("restored", access.flagsRestored ?: JSONObject.NULL)
                        put("capabilities", access.capabilities ?: JSONObject.NULL)
                        put("restoreError", access.restoreError ?: JSONObject.NULL)
                    }.toString())
                })
            }.use { windowAccess ->
                // This is functional setup, NOT a performance sample. Start real Home before
                // resolving settings/repository, letting normal Application/Main initialization run.
                // No Chat is opened and no readiness/home flag is manually changed.
                val id = PerfFixtureScenarios.spec("S1").conversationId.toString()
                val scenario = ShellHomeActivity(instrumentation, token, ::checkpoint)
                var failure: Throwable? = null
                try {
                    stages.step("activity-launch", 20_000) { windowAccess.enable(); scenario.launch() }
                    fun snapshot(): JSONObject {
                        var result: JSONObject? = null
                        scenario.onActivity { result = ChatPerfHomeEntry.snapshot(id) }
                        return checkNotNull(result)
                    }
                    fun awaitState(label: String, condition: (JSONObject) -> Boolean): JSONObject {
                        val deadline = SystemClock.elapsedRealtime() + 15000
                        var last = snapshot()
                        while (!condition(last) && SystemClock.elapsedRealtime() < deadline) {
                            SystemClock.sleep(50)
                            last = snapshot()
                        }
                        assertTrue("$label not observed; actual=$last", condition(last))
                        return last
                    }
                    fun clickActualRow(phase: String, activityId: String, pid: Int) {
                        HomeRowTapper(instrumentation, token, phase, id, pid, activityId, windowAccess) {
                            var owner: HomeRowTapContract.Owner? = null
                            scenario.onActivity { owner = HomeRowTapper.captureOwner(it, id) }
                            checkNotNull(owner)
                        }.tap()
                    }
                    val unseededHome = stages.step("initial-home", 15_000) {
                        awaitState("Home before setup seed") { it.optString("topRoute") == "Home" && it.optBoolean("home") }
                    }
                    assertFalse("setup must never visit Chat", unseededHome.getBoolean("chatVisited"))
                    val koin = stages.step("koin-context", 5_000) { GlobalContext.get() }
                    val settings = stages.step("koin-settings", 5_000) { koin.get<SettingsAggregator>() }
                    stages.step("settings-ready", 10_000) {
                        runBlocking(Dispatchers.IO) { withTimeout(9_000) { settings.settingsFlow.first { !it.init } } }
                    }
                    val repository = stages.step("koin-repository", 5_000) { koin.get<ConversationRepository>() }
                    stages.step("seed-io-readback", 15_000) {
                        runBlocking(Dispatchers.IO) {
                            withTimeout(14_000) { PerfFixtureScenarios.seed(context.applicationContext, repository, settings, "S1") }
                        }
                    }
                    val initial = stages.step("seeded-home-row", 15_000) {
                        awaitState("initial Home row") {
                            it.optString("topRoute") == "Home" && it.optBoolean("home") && it.optBoolean("rowVisible")
                        }
                    }
                    assertEquals(unseededHome.getString("activityId"), initial.getString("activityId"))
                    assertEquals(unseededHome.getInt("pid"), initial.getInt("pid"))
                    val activityId = initial.getString("activityId")
                    val pid = initial.getInt("pid")
                    assertFalse(initial.getBoolean("chatVisited"))
                    assertFalse(initial.getBoolean("targetVisited"))
                    stages.step("arm-cold", 5_000) {
                        scenario.onActivity {
                            val prior = ChatPerfTrace.currentRunId()
                            assertNull(ChatPerfHomeEntry.arm("device-home-cold-a2", "S1", id, "cold"))
                            assertEquals("ARM cannot start the run", prior, ChatPerfTrace.currentRunId())
                        }
                    }
                    SystemClock.sleep(500)
                    stages.step("click-cold-row", 7_000) { clickActualRow("cold", activityId, pid) } // production Home row -> original openChat -> actual NavDisplay
                    val chat = stages.step("cold-chat", 15_000) {
                        awaitState("cold Chat top") {
                            !it.optBoolean("home", true) && it.optString("topRoute") == "Chat" && it.optString("topConversation") == id
                        }
                    }
                    assertEquals(activityId, chat.getString("activityId"))
                    assertEquals(pid, chat.getInt("pid"))
                    assertTrue(chat.getBoolean("chatVisited"))
                    assertTrue(chat.getBoolean("targetVisited"))
                    assertFalse(chat.getBoolean("rowVisible"))
                    assertEquals(activityId, chat.getString("lastEntryActivity"))
                    assertEquals("device-home-cold-a2", ChatPerfCommandBridge.fixtureRunId)
                    stages.step("production-back", 5_000) { scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() } }
                    val returned = stages.step("returned-home", 15_000) {
                        awaitState("production Back to Home") {
                            it.optBoolean("home") && it.optString("topRoute") == "Home" && it.optBoolean("rowVisible")
                        }
                    }
                    assertEquals(activityId, returned.getString("activityId"))
                    assertEquals(pid, returned.getInt("pid"))
                    assertTrue(returned.getBoolean("targetVisited"))
                    stages.step("arm-warm", 5_000) {
                        scenario.onActivity {
                            assertEquals("cold_already_visited_chat", ChatPerfHomeEntry.arm("bad-cold-a2", "S1", id, "cold"))
                            assertNull(ChatPerfHomeEntry.arm("device-home-warm-a2", "S1", id, "warm"))
                        }
                    }
                    SystemClock.sleep(500)
                    stages.step("click-warm-row", 7_000) { clickActualRow("warm", activityId, pid) }
                    val warm = stages.step("warm-chat", 15_000) {
                        awaitState("warm Chat top") {
                            !it.optBoolean("home", true) && it.optString("topRoute") == "Chat" && it.optString("topConversation") == id
                        }
                    }
                    assertEquals(activityId, warm.getString("activityId"))
                    assertEquals(pid, warm.getInt("pid"))
                    assertTrue(warm.getBoolean("targetVisited"))
                    assertFalse(warm.getBoolean("rowVisible"))
                    assertEquals("device-home-warm-a2", ChatPerfCommandBridge.fixtureRunId)
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    // A timed-out stage poisons the worker; step then fails immediately instead
                    // of queueing cleanup behind the blocked call. Host boundary stops the process.
                    try {
                        stages.step("activity-close", 5_000) { scenario.close() }
                    } catch (cleanup: Throwable) {
                        if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
                    } finally {
                        scenario.dispose() // releases shell FDs/observer even after a timed-out stage
                    }
                }
            }
        }
        checkpoint("test", "ok")
    }
}
