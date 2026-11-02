package app.amber.agent.perffixture

import android.app.Application
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.feature.ui.utils.ChatPerfTrace
import app.amber.feature.ui.utils.ChatPerfTargetKind
import app.amber.feature.ui.utils.ChatPerfCommandBridge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PerfFixtureStreamLifecycleTest {
    private val originalScope = ChatPerfTrace.exportScope
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private fun metrics(id: String): JSONObject? {
        val file = File(app.getExternalFilesDir("chat-perf"), "runs/$id/metrics-S7.json")
        return if (file.isFile) JSONObject(file.readText()) else null
    }
    private fun TestScope.start(id: String) {
        ChatPerfTrace.exportScope = backgroundScope
        ChatPerfTrace.clockNanos = { 1_000_000_000L + testScheduler.currentTime * 1_000_000 }
        ChatPerfTrace.startRun(app, "S7", id)
        ChatPerfTrace.dataReady()
        ChatPerfTrace.initialPreparationReady()
        ChatPerfTrace.firstTimelineLayout()
        val node = Uuid.random()
        ChatPerfTrace.beginTarget(ChatPerfTargetKind.MESSAGE, node)
        ChatPerfTrace.onTargetVisible(ChatPerfTrace.targetGeneration(), true)
        ChatPerfTrace.onMarkdownReadiness(ChatPerfTrace.runGeneration(), node.toString(), 1, false)
        ChatPerfTrace.onTargetDraw(1f)
    }
    private fun reset() {
        ChatPerfTrace.clockNanos = { android.os.SystemClock.elapsedRealtimeNanos() }
        ChatPerfTrace.exportScope = originalScope
    }
    private fun gateway() = PerfFixtureTextGateway()
    private val provider = ProviderSetting.OpenAI(name = "fixture", apiKey = "fixture-only")
    private val params = TextGenerationParams(Model(modelId = "fixture"))

    @Test fun realLengthAndCadenceFinishThenAuxiliaryInteractionFinalizes() = runTest {
        try {
            val id = "s7-full-timeline"
            start(id)
            assertFalse(ChatPerfTrace.beginFixtureStream(id)) // cannot send inside the sample
            advanceTimeBy(1500)
            assertTrue(ChatPerfTrace.beginFixtureStream(id))
            val received = StringBuilder()
            val stream = launch {
                gateway().stream(provider, emptyList(), params).collect { chunk ->
                    chunk.choices.first().delta?.parts?.forEach { part ->
                        if (part is app.amber.ai.ui.UIMessagePart.Text) received.append(part.text)
                    }
                }
            }
            advanceTimeBy(8500) // old 10-second watchdog must not invalidate mid-stream
            runCurrent()
            assertNotEquals("invalid", metrics(id)?.optString("status"))
            assertTrue(stream.isActive)
            advanceTimeBy(6000)
            runCurrent()
            assertTrue(stream.isCompleted)
            assertEquals(PerfFixtureStreamSpec.reply, received.toString())
            assertTrue(PerfFixtureStreamSpec.reply.length > 3800)
            advanceTimeBy(11000) // reproduces slow post-window UIAutomator assistance
            ChatPerfTrace.interactionRequested("swipe")
            ChatPerfTrace.confirmInteraction("real-test-response")
            runCurrent()
            assertEquals("final", metrics(id)?.getString("status"))
            assertEquals("auxiliary-interaction", metrics(id)?.getString("finalizationStage"))
        } finally { reset() }
    }

    @Test fun phaseTimeoutsAreTerminalAndNewRunCancelsOldDeadline() = runTest {
        try {
            start("s7-entry-timeout")
            advanceTimeBy(10001); runCurrent()
            assertTrue(metrics("s7-entry-timeout")!!.getJSONArray("invalidReasons").toString().contains("entry-window"))
            assertFalse(ChatPerfTrace.beginFixtureStream("s7-entry-timeout"))
            start("s7-stream-timeout")
            advanceTimeBy(1500)
            assertTrue(ChatPerfTrace.beginFixtureStream("s7-stream-timeout"))
            advanceTimeBy(20001); runCurrent()
            ChatPerfTrace.fixtureStreamSettled("s7-stream-timeout")
            ChatPerfTrace.interactionRequested("swipe"); ChatPerfTrace.confirmInteraction("late")
            runCurrent()
            assertEquals("invalid", metrics("s7-stream-timeout")?.getString("status"))
            start("s7-aux-timeout")
            advanceTimeBy(1500)
            assertTrue(ChatPerfTrace.beginFixtureStream("s7-aux-timeout"))
            ChatPerfTrace.fixtureStreamSettled("s7-aux-timeout")
            advanceTimeBy(20001); runCurrent()
            assertTrue(metrics("s7-aux-timeout")!!.getJSONArray("invalidReasons").toString().contains("auxiliary-interaction"))
            start("s7-replaced")
            advanceTimeBy(2000)
            start("s7-current")
            advanceTimeBy(8001); runCurrent()
            assertNotEquals("invalid", metrics("s7-current")?.optString("status"))
        } finally { reset() }
    }

    @Test fun realGatewayCancellationInvalidatesAndLateCallbackCannotChangeNewRun() = runTest {
        try {
            val id = "s7-cancelled"
            start(id)
            advanceTimeBy(1500)
            assertTrue(ChatPerfTrace.beginFixtureStream(id))
            val job = launch { gateway().stream(provider, emptyList(), params).collect() }
            advanceTimeBy(1000)
            job.cancel(); runCurrent()
            assertEquals("invalid", metrics(id)?.getString("status"))
            assertTrue(metrics(id)!!.getJSONArray("invalidReasons").toString().contains("cancelled"))
            start("s7-after-cancel")
            ChatPerfTrace.fixtureStreamSettled(id)
            assertFalse(ChatPerfTrace.beginFixtureStream(id))
            advanceTimeBy(1500)
            assertTrue(ChatPerfTrace.beginFixtureStream("s7-after-cancel"))
        } finally { reset() }
    }
}
