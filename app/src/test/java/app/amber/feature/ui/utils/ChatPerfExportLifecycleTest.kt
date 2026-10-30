package app.amber.feature.ui.utils

import android.app.Application
import app.amber.ai.ui.UIMessagePart
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatPerfExportLifecycleTest {
    private lateinit var context: Application

    @Before
    fun setUp() {
        assumeTrue("requires -PchatPerfTrace=true", ChatPerfTrace.enabled)
        context = RuntimeEnvironment.getApplication()
        // Robolectric's elapsedRealtime is paused; these existing asynchronous
        // IO tests use a real monotonic clock. S7 uses a virtual scheduler.
        ChatPerfTrace.clockNanos = { System.nanoTime() }
        ChatPerfTrace.snapshotDelayMs = 60
        ChatPerfTrace.finalWindowMs = 0.0
        ChatPerfTrace.finalWatchdogMs = 700
    }

    @After
    fun tearDown() {
        ChatPerfTrace.clockNanos = { android.os.SystemClock.elapsedRealtimeNanos() }
        ChatPerfTrace.snapshotDelayMs = 1_600
        ChatPerfTrace.finalWindowMs = 1_500.0
        ChatPerfTrace.finalWatchdogMs = 10_000
    }

    private fun metricsFile(runId: String): File {
        val root = context.getExternalFilesDir("chat-perf") ?: File(context.filesDir, "chat-perf")
        return File(root, "runs/$runId/metrics-S1.json")
    }

    private fun read(runId: String): JSONObject? =
        metricsFile(runId).takeIf { it.isFile }?.let { JSONObject(it.readText()) }

    private fun waitFor(runId: String, status: String, timeoutMs: Long = 2_000): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            read(runId)?.let { if (it.optString("status") == status) return it }
            Thread.sleep(20)
        }
        error("run $runId did not reach status $status; last=${read(runId)}")
    }

    private fun phaseRun(runId: String, readable: Boolean = true) {
        ChatPerfTrace.startRun(context, "S1", runId)
        ChatPerfTrace.dataReady()
        ChatPerfTrace.initialPreparationReady()
        ChatPerfTrace.firstTimelineLayout()
        val node = Uuid.random()
        ChatPerfTrace.beginTarget(ChatPerfTargetKind.MESSAGE, node)
        ChatPerfTrace.onTargetVisible(ChatPerfTrace.targetGeneration(), true)
        ChatPerfTrace.onMarkdownReadiness(
            ChatPerfTrace.runGeneration(), node.toString(), 1, false,
        )
        if (readable) ChatPerfTrace.onTargetDraw(1f)
    }

    @Test
    fun earlySnapshotExistsButIsNotFinalAndFinalContainsInteraction() {
        val runId = "export-lifecycle-final"
        phaseRun(runId)
        val snapshot = waitFor(runId, "snapshot", 1_000)
        assertEquals("snapshot", snapshot.getString("status"))
        assertFalse(snapshot.has("interaction"))
        assertFalse(snapshot.has("phasesMs") && snapshot.getJSONObject("phasesMs").has("FirstInteractive"))

        Thread.sleep(120)
        ChatPerfTrace.interactionRequested("swipe")
        ChatPerfTrace.confirmInteraction("test-script")
        val final = waitFor(runId, "final")
        assertEquals("final", final.getString("status"))
        assertTrue(final.getInt("revision") > 0)
        assertNotNull(final.getJSONObject("interaction"))
        assertEquals(
            final.getJSONObject("interaction").getDouble("confirmedAtMs"),
            final.getJSONObject("phasesMs").getDouble("FirstInteractive"),
            0.5,
        )
    }

    @Test
    fun toolOutputCharsExportCountsUtf16AcrossTextOutputsWithoutByteEncoding() {
        val tools = listOf(
            UIMessagePart.Tool("unicode-1", "fixture", "ignored input", output = listOf(
                UIMessagePart.Text("中文"), UIMessagePart.Reasoning("not a Text output"),
            )),
            UIMessagePart.Tool("unicode-2", "fixture", "", output = listOf(UIMessagePart.Text("😀"))),
        )
        val chars = ChatPerfTrace.countToolOutputChars(tools)
        assertEquals(4L, chars) // 2 CJK code units + the emoji's UTF-16 surrogate pair.
        assertEquals(10, "中文😀".toByteArray(Charsets.UTF_8).size) // Encoding only in this regression.
        val runId = "export-unicode-tool-chars"
        phaseRun(runId)
        ChatPerfTrace.recordPreparation(toolInputs = tools.size, toolOutputCharsPrepared = chars)
        ChatPerfTrace.interactionRequested("swipe")
        ChatPerfTrace.confirmInteraction("test-script")
        val final = waitFor(runId, "final")
        assertEquals(2, final.getInt("schema"))
        val preparation = final.getJSONObject("preparation")
        assertEquals(4L, preparation.getLong("toolOutputCharsPrepared"))
        assertEquals(setOf("nodesScanned", "textsPrepared", "charsPrepared", "toolInputsPrepared",
            "toolOutputCharsPrepared", "planBuildMs", "markdownParseCount", "markdownParseMs"),
            preparation.keys().asSequence().toSet())
    }

    @Test
    fun noInteractionProducesInvalidNotFinal() {
        val runId = "export-lifecycle-no-interaction"
        phaseRun(runId)
        val invalid = waitFor(runId, "invalid", 1_500)
        assertEquals("invalid", invalid.getString("status"))
        assertFalse(invalid.has("interaction"))
        assertTrue(invalid.getJSONArray("invalidReasons").length() > 0)
        assertEquals("invalid", read(runId)?.getString("status"))
    }

    @Test
    fun interactionBeforeReadableIsInvalid() {
        val runId = "export-lifecycle-before-readable"
        phaseRun(runId, readable = false)
        ChatPerfTrace.interactionRequested("swipe")
        ChatPerfTrace.confirmInteraction("test-script")
        val invalid = waitFor(runId, "invalid", 1_500)
        assertEquals("invalid", invalid.getString("status"))
        val reasons = invalid.getJSONArray("invalidReasons").toString()
        assertTrue(reasons.contains("FirstReadableDraw") || reasons.contains("FirstContentDraw"))
    }

    @Test
    fun newRunCancelsOldExportJobAndOldSnapshotCannotBecomeFinal() {
        val oldRun = "export-lifecycle-old"
        phaseRun(oldRun)
        waitFor(oldRun, "snapshot", 1_000)
        val newRun = "export-lifecycle-new"
        phaseRun(newRun)
        waitFor(newRun, "snapshot", 1_000)
        ChatPerfTrace.interactionRequested("swipe")
        ChatPerfTrace.confirmInteraction("test-script")
        waitFor(newRun, "final")
        // Old run's watchdog/final jobs were cancelled; it must not gain a final.
        Thread.sleep(300)
        assertFalse(read(oldRun)?.optString("status") == "final")
    }
}
