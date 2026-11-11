package app.amber.feature.tools

import android.provider.Settings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.core.automation.AccessibilityController
import app.amber.core.automation.PhoneAutomationTakeoverController
import app.amber.core.automation.PhoneTakeoverPhase
import app.amber.core.automation.getActiveAccessibilityController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Owns one screen operation, run stop markers and the matching activity settlement. */
internal class ScreenOperationOwner(
    private val activityStore: AgentToolActivityStore,
    internal val takeoverController: PhoneAutomationTakeoverController? = null,
) {
    private val screenOwner = Mutex()

    /**
     * 已由 screen_stop 主动结束的 run。按 run 键控：本工具是 Koin 单例，
     * 进程级布尔会把一次 stop 变成"App 级永久关闭屏幕自控"。
     */
    private val stoppedRuns: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    @Volatile
    private var boundRunId: String? = null

    fun bindRun(runId: String?) {
        boundRunId = runKey(runId)
    }

    fun runKey(runId: String?): String? = runId?.takeIf { it.isNotBlank() }

    fun isStopped(runId: String?): Boolean = runKey(runId)?.let { it in stoppedRuns } ?: false

    val isActive: Boolean get() = screenOwner.isLocked

    fun stop(runId: String) { stoppedRuns += runId }

    suspend fun trackScreenTool(
        toolName: String,
        title: String,
        input: JsonElement,
        runtime: String = "Android Accessibility",
        runId: String? = null,
        enforceScreenStop: Boolean = true,
        block: suspend () -> List<UIMessagePart>,
    ): List<UIMessagePart> {
        // 只有能界定"本次 run"的入口才受 screen_stop 约束；低级手势由每次审批把关。
        if (enforceScreenStop) {
            check(!isStopped(runId)) {
                "Screen control was ended by screen_stop for this run; no further action is dispatched."
            }
        }
        check(screenOwner.tryLock()) { "Another screen operation is active; wait for it to finish before observing again." }
        try {
            val toolCallId = activityStore.startTool(
                toolName = toolName,
                title = title,
                inputPreview = input.activityInputPreview(toolName),
                runtime = runtime,
            )
            return try {
                val result = if (toolName != "screen_run_goal" && takeoverController != null) {
                    val controller = getActiveAccessibilityController()
                    val currentRunKey = runKey(runId) ?: boundRunId
                    val initialSession = takeoverController.state.value
                    if (initialSession.isActive &&
                        (initialSession.pauseReason == "process_restart" ||
                            initialSession.runId.isNullOrBlank() ||
                            (currentRunKey != null && initialSession.runId != currentRunKey))
                    ) {
                        // A stale or orphaned takeover session from an ended/restarted run must
                        // never block a new run's screen operations.
                        takeoverController.finishSession(initialSession.runId)
                    }
                    val phase = takeoverController.state.value.phase
                    if (phase == PhoneTakeoverPhase.PAUSING ||
                        phase == PhoneTakeoverPhase.PAUSED ||
                        phase == PhoneTakeoverPhase.RESUMING
                    ) {
                        val resumed = takeoverController.awaitResumeIfPaused(
                            currentSnapshotId = takeoverController.state.value.pausedAtSnapshotId,
                            controller = controller,
                        )
                        if (!resumed) {
                            return textJson {
                                put("success", false)
                                put("status", "needs_user_action")
                                put("reason", "user_takeover_paused")
                            }
                        }
                    }
                    // Re-read the phase: the awaited transition above ends in RESUMING, so the
                    // pre-await value cannot be used to decide whether a fresh snapshot is due.
                    if (takeoverController.state.value.phase == PhoneTakeoverPhase.RESUMING) {
                        val fresh = controller?.let {
                            withContext(Dispatchers.Main.immediate) { it.captureScreenSnapshot() }
                        }
                        if (fresh != null && takeoverController.confirmResumedWithFreshSnapshot(fresh)) {
                            // RESUMING -> AUTOMATED only after a post-takeover snapshot exists.
                        } else {
                            // Fail closed instead of dispatching against a pre-takeover view; a new
                            // PAUSED state keeps a usable "resume" action in the UI. Only regress an
                            // actual RESUMING phase so a session that already advanced is untouched.
                            if (takeoverController.state.value.phase == PhoneTakeoverPhase.RESUMING) {
                                takeoverController.confirmPaused(
                                    reason = "fresh_snapshot_unavailable",
                                    controller = controller,
                                )
                            }
                            return textJson {
                                put("success", false)
                                put("status", "needs_user_action")
                                put("reason", "fresh_snapshot_unavailable")
                            }
                        }
                    }
                    if (takeoverController.state.value.isPausedOrPausing) {
                        textJson {
                            put("success", false)
                            put("status", "needs_user_action")
                            put("reason", "user_takeover_paused")
                        }
                    } else {
                        block()
                    }
                } else {
                    block()
                }
                activityStore.complete(toolCallId, result.previewText())
                result
            } catch (error: Throwable) {
                activityStore.fail(toolCallId, error)
                throw error
            }
        } finally {
            screenOwner.unlock()
        }
    }
    private fun List<UIMessagePart>.previewText(): String =
        joinToString("\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                else -> part.toString()
            }
        }.takeLast(1_600)

    private fun JsonElement.activityInputPreview(toolName: String): String =
        when (toolName) {
            "screen_run_goal" -> buildJsonObject {
                put("package_name", string("package_name").orEmpty())
                put("goal_chars", string("goal")?.length ?: 0)
            }.toString()
            "screen_input_text" -> buildJsonObject {
                put("text_chars", string("text")?.length ?: 0)
            }.toString()
            else -> toString()
        }
}

internal fun requireScreenAccessibility(): AccessibilityController =
    getActiveAccessibilityController()
        ?: error("AmberAgent Accessibility is not enabled. Open ${Settings.ACTION_ACCESSIBILITY_SETTINGS} and enable AmberAgent Accessibility.")
