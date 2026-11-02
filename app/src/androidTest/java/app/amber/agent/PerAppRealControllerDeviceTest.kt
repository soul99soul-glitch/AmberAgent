package app.amber.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.amber.core.automation.PhoneAutomationTakeoverController
import app.amber.core.automation.ScreenAction
import app.amber.core.automation.ScreenActionKind
import app.amber.core.automation.ScreenSnapshotInvalidationRegistry
import app.amber.core.automation.getActiveAccessibilityController
import app.amber.core.jev.FilePerAppSuccessPathStore
import app.amber.core.jev.JevClient
import app.amber.core.jev.JevDataScope
import app.amber.core.jev.JevDecisionCoordinator
import app.amber.core.jev.JevHttpRequest
import app.amber.core.jev.JevMode
import app.amber.core.jev.JevPurpose
import app.amber.core.jev.JevRuntime
import app.amber.core.jev.JevScreenGoalRunner
import app.amber.core.jev.JevSetting
import app.amber.core.jev.JevTransport
import app.amber.core.jev.JevTransportResponse
import app.amber.core.jev.JevUsageStore
import app.amber.core.jev.PerAppSuccessPathSanitizer
import app.amber.core.jev.clearPerAppSuccessPaths
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.Settings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real [app.amber.core.automation.AmberAccessibilityService] controller against
 * [JevScreenFixtureActivity]. Scripted Jev transport; no networked Jev key.
 * Isolated from [PerAppSuccessPathDeviceTest] ART fake snapshots.
 */
@RunWith(AndroidJUnit4::class)
class PerAppRealControllerDeviceTest {
    @After
    fun tearDown() {
        ScreenSnapshotInvalidationRegistry.clearForTest()
    }

    @Test
    fun freshSnapshotLocatesUniqueFixtureControl() {
        AmberS9DeviceSupport.assumeUnlocked()
        AmberS9DeviceSupport.uiAutomation()
        AmberS9DeviceSupport.withAmberAccessibility {
            openFixture()
            val snapshot = runBlocking {
                withContext(Dispatchers.Main) { getActiveAccessibilityController()!!.captureScreenSnapshot()!! }
            }
            assertEquals(AmberS9DeviceSupport.targetPackage, snapshot.packageName)
            val matches = snapshot.nodes.filter { it.clickable && it.label == "打开评论列表" }
            assertEquals("expected a unique clickable 打开评论列表, got ${matches.map { it.ref }}", 1, matches.size)
            val target = matches.single()
            val stale = runBlocking {
                withContext(Dispatchers.Main) {
                    getActiveAccessibilityController()!!.performScreenAction(
                        snapshot.copy(id = "expired"),
                        ScreenAction(ScreenActionKind.CLICK, target.ref),
                    )
                }
            }
            assertFalse(stale.dispatched)
            assertEquals("stale_snapshot", stale.status)
            val clicked = runBlocking {
                withContext(Dispatchers.Main) {
                    getActiveAccessibilityController()!!.performScreenAction(
                        snapshot,
                        ScreenAction(ScreenActionKind.CLICK, target.ref),
                    )
                }
            }
            assertEquals("ok", clicked.status)
            runBlocking { delay(600) }
            val after = runBlocking {
                withContext(Dispatchers.Main) { getActiveAccessibilityController()!!.captureScreenSnapshot()!! }
            }
            assertTrue(after.nodes.any { it.label.contains("评论列表第 1 页") })
            AmberS9DeviceSupport.writeEvidence(
                "perapp-real-locate.txt",
                "pkg=${snapshot.packageName} ref=${target.ref} viewId=${target.viewId} afterPage1=true\n",
            )
            AmberS9DeviceSupport.takeUiScreenshot("perapp-real-locate.png")
        }
    }

    @Test
    fun rememberedPathUniquelyGuidesFreshSnapshot() {
        runBlocking {
        AmberS9DeviceSupport.assumeUnlocked()
        AmberS9DeviceSupport.uiAutomation()
        AmberS9DeviceSupport.withAmberAccessibilitySuspending {
            val memory = store()
            openFixture()
            val first = withContext(Dispatchers.Main) { getActiveAccessibilityController()!!.captureScreenSnapshot()!! }
            val open = first.nodes.single { it.clickable && it.label == "打开评论列表" }
            val seed = PerAppSuccessPathSanitizer.buildPath(
                packageName = AmberS9DeviceSupport.targetPackage,
                goal = "打开评论列表",
                steps = listOf(PerAppSuccessPathSanitizer.stepFrom(ScreenActionKind.CLICK, open)!!),
                recordedAtMs = 1,
            )
            assertTrue(memory.recordSuccess(0, seed!!))
            openFixture()
            val guided = runner(
                runtime(ScriptedTransport(actionStepAnswers(answer("a0")).also { it += answer("done", done = 0.96) })),
                memory,
            ).run(
                getActiveAccessibilityController()!!,
                AmberS9DeviceSupport.targetPackage,
                "打开评论列表",
                emptyList(),
                "run-real-guide",
                autoReturnToHost = false,
            )
            assertEquals("true", guided["experience_guided"]!!.jsonPrimitive.content)
            AmberS9DeviceSupport.writeEvidence("perapp-real-guide.txt", guided.toString())
        }
        }
    }

    @Test
    fun takeoverDoesNotRepeatAndClearBlocksInflightWrite() {
        runBlocking {
        AmberS9DeviceSupport.assumeUnlocked()
        AmberS9DeviceSupport.uiAutomation()
        AmberS9DeviceSupport.withAmberAccessibilitySuspending {
            val memory = store()
            openFixture()
            val first = withContext(Dispatchers.Main) { getActiveAccessibilityController()!!.captureScreenSnapshot()!! }
            val open = first.nodes.single { it.clickable && it.label == "打开评论列表" }
            val seed = PerAppSuccessPathSanitizer.buildPath(
                packageName = AmberS9DeviceSupport.targetPackage,
                goal = "打开评论列表",
                steps = listOf(PerAppSuccessPathSanitizer.stepFrom(ScreenActionKind.CLICK, open)!!),
                recordedAtMs = 1,
            )
            assertTrue(memory.recordSuccess(0, seed!!))
            val takeover = PhoneAutomationTakeoverController()
            val sideTransport = ScriptedTransport(actionStepAnswers(answer("a0"))) { calls ->
                if (calls == 1) {
                    val snap = runBlocking {
                        withContext(Dispatchers.Main) { getActiveAccessibilityController()!!.captureScreenSnapshot()!! }
                    }
                    takeover.tryRecordSideEffectDispatch(snap.id, ScreenAction(ScreenActionKind.CLICK, open.ref))
                }
            }
            openFixture()
            val side = runner(runtime(sideTransport), memory, takeover).run(
                getActiveAccessibilityController()!!,
                AmberS9DeviceSupport.targetPackage,
                "打开评论列表",
                emptyList(),
                "run-real-side",
                autoReturnToHost = false,
            )
            assertEquals("handback", status(side))
            assertEquals("false", side["experience_written"]!!.jsonPrimitive.content)

            openFixture()
            val inflight = runner(
                runtime(ScriptedTransport(actionStepAnswers(answer("a0")).also { it += answer("done", done = 0.96) })),
                memory,
                settle = { clearPerAppSuccessPaths(memory) },
            ).run(
                getActiveAccessibilityController()!!,
                AmberS9DeviceSupport.targetPackage,
                "打开评论列表",
                emptyList(),
                "run-real-inflight",
                autoReturnToHost = false,
            )
            assertEquals("false", inflight["experience_written"]!!.jsonPrimitive.content)
            assertTrue(memory.pathsFor(AmberS9DeviceSupport.targetPackage).isEmpty())
            AmberS9DeviceSupport.writeEvidence(
                "perapp-real-takeover-clear.txt",
                "side=${status(side)} reason=${side["reason"]} inflightWritten=${inflight["experience_written"]}\n",
            )
        }
        }
    }

    private fun openFixture() {
        AmberS9DeviceSupport.launchTestPackageActivity("app.amber.agent.JevScreenFixtureActivity")
        AmberS9DeviceSupport.waitForSnapshotPackage(AmberS9DeviceSupport.targetPackage, timeoutMs = 10_000)
    }

    private fun store() = FilePerAppSuccessPathStore(
        File(AmberS9DeviceSupport.targetContext.cacheDir, "amber-s9-per-app-real.json").also { it.delete() },
    )

    private fun runtime(transport: JevTransport) = JevRuntime(
        coordinator = JevDecisionCoordinator(
            client = JevClient(transport),
            apiKeyProvider = { "test-key" },
            usageStore = JevUsageStore.IN_MEMORY,
            clock = { 1_000_000L },
        ),
        settingsProvider = {
            Settings(
                jev = JevSetting(
                    enabled = true,
                    purposes = mapOf(JevPurpose.PHONE_ACTIONS to JevMode.ACTIVE, JevPurpose.PHONE_VERIFICATION to JevMode.ACTIVE),
                    dataScopes = setOf(JevDataScope.SCREEN_CONTENT, JevDataScope.TASK_TEXT),
                ),
                agentRuntime = AgentRuntimeSetting(autoReturnToHostOnScreenGoalComplete = false),
            )
        },
    )

    private fun runner(
        runtime: JevRuntime,
        store: FilePerAppSuccessPathStore,
        takeover: PhoneAutomationTakeoverController? = null,
        settle: suspend () -> Unit = {},
    ) = JevScreenGoalRunner(
        runtime = runtime,
        mainDispatcher = Dispatchers.Main.immediate,
        settle = settle,
        takeoverController = takeover,
        successPathStore = store,
    )

    private class ScriptedTransport(
        private val responses: MutableList<String>,
        private val onCall: (Int) -> Unit = {},
    ) : JevTransport {
        var calls = 0
            private set

        override suspend fun execute(request: JevHttpRequest): JevTransportResponse {
            calls++
            onCall(calls)
            return JevTransportResponse.Http(
                code = 200,
                body = (responses.removeFirstOrNull() ?: error("unexpected Jev request #$calls")).toByteArray(),
                retryAfterHeader = null,
            )
        }
    }

    private fun status(result: kotlinx.serialization.json.JsonObject) = result["status"]!!.jsonPrimitive.content

    private fun actionStepAnswers(vararg answers: String) = answers.flatMap { listOf(it, it) }.toMutableList()

    private fun answer(action: String, safe: Double = 0.99, done: Double = 0.1): String {
        val safety = (0 until 30).joinToString(",") { "\"safe_a$it\":{\"type\":\"noul\",\"noul\":$safe}" }
        return """{"model":"jev-test","answers":{
            "action":{"type":"choice","choice":"$action","confidence":0.9},
            $safety,
            "done":{"type":"noul","noul":$done}
        }}"""
    }
}
