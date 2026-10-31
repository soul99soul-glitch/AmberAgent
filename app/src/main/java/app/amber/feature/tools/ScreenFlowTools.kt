package app.amber.feature.tools

import kotlinx.coroutines.delay
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.core.automation.PhoneTakeoverPhase
import app.amber.core.jev.JevScreenGoalRunner
import app.amber.core.settings.prefs.SettingsAggregator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Fixed steps: fresh snapshot, one dispatch, receipt and read-only acceptance. */
internal class ScreenFlowTools(
    private val settingsStore: SettingsAggregator?,
    private val operations: ScreenOperationOwner,
) {
    fun screenRunFlowTool(runId: String?) = Tool(
        name = "screen_run_flow",
        description = "Run 1-12 fixed, already-decided steps in the authorized app without asking the " +
            "judgment service at each step. Use it for known navigation, search, or filling a draft " +
            "supplied by the user. Every step re-reads a fresh observation and re-locates the target " +
            "by exact text; refs and coordinates are never stored between steps. A step is dispatched " +
            "at most once. Any ambiguity, missing target, app switch, or unmet acceptance condition " +
            "hands the remaining work back to you with the evidence, never a replay. Final send, " +
            "publish, pay, delete, login and permission grants are not supported here — use screen_act " +
            "style single actions for those.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("package_name", screenStringProp("Authorized package the flow stays inside."))
                    put("steps", buildJsonObject {
                        put("type", "array")
                        put("minItems", 1)
                        put("maxItems", 12)
                        put("description", "Ordered steps; each one is dispatched once, in order.")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("target", screenStringProp("Exact visible text or label to act on."))
                                put("operation", buildJsonObject {
                                    put("type", "string")
                                    put("enum", buildJsonArray { add("click"); add("type") })
                                })
                                put("text", screenStringProp("Text to type; required when operation=type."))
                                put("expectation", screenExpectationProp())
                            })
                            put("required", buildJsonArray { add("target"); add("operation") })
                        })
                    })
                },
                required = listOf("package_name", "steps"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        mandatoryApproval = true,
        execute = { input ->
            operations.trackScreenTool(
                "screen_run_flow", "固定流程执行", input,
                runtime = "Android Accessibility", runId = runId,
            ) {
                if (operations.runKey(runId) == null) {
                    return@trackScreenTool textJson {
                        put("status", "handback")
                        put("reason", "no_run_scope")
                    }
                }
                val packageName = input.requiredString("package_name")
                val allowed = allowedPackages()
                if (packageName !in allowed) {
                    return@trackScreenTool textJson {
                        put("status", "no_scope")
                        put("package_name", packageName)
                        put("reason", if (allowed.isEmpty()) "no_app_scope" else "package_not_allowed")
                        put("allowed_packages", buildJsonArray { allowed.forEach { add(it) } })
                        put("remedy", "call screen_apps then screen_add_app with the exact package name")
                    }
                }
                val steps = (input.jsonObject["steps"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
                    .orEmpty()
                if (steps.isEmpty() || steps.size > MAX_FLOW_STEPS) {
                    return@trackScreenTool textJson {
                        put("status", "rejected")
                        put("reason", "steps_must_be_1_to_${MAX_FLOW_STEPS}")
                    }
                }
                val service = requireScreenAccessibility()
                val deadline = System.currentTimeMillis() + FLOW_BUDGET_MS
                val executed = mutableListOf<kotlinx.serialization.json.JsonObject>()

                var handback: String? = null
                val takeoverController = operations.takeoverController
                val currentRunKey = operations.runKey(runId)
                for ((index, step) in steps.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    if (operations.isStopped(runId)) { handback = "stopped_by_user"; break }
                    if (takeoverController != null) {
                        val preStepSession = takeoverController.state.value
                        if (preStepSession.isActive &&
                            currentRunKey != null &&
                            preStepSession.runId == currentRunKey &&
                            (preStepSession.phase == PhoneTakeoverPhase.PAUSING ||
                                preStepSession.phase == PhoneTakeoverPhase.PAUSED ||
                                preStepSession.phase == PhoneTakeoverPhase.RESUMING)
                        ) {
                            val resumed = takeoverController.awaitResumeIfPaused(
                                currentSnapshotId = preStepSession.pausedAtSnapshotId,
                                controller = service,
                            )
                            if (!resumed) { handback = "user_takeover_paused"; break }
                        }
                    }
                    if (System.currentTimeMillis() >= deadline) { handback = "budget_exhausted"; break }
                    val target = step.string("target").orEmpty().trim()
                    val operation = step.string("operation").orEmpty()
                    val text = step.string("text")
                    if (target.isBlank()) { handback = "missing_target"; break }
                    if (operation != "click" && operation != "type") { handback = "unsupported_operation"; break }
                    if (operation == "type" && text.isNullOrEmpty()) { handback = "missing_text"; break }
                    // 与 screen_run_goal 同一条红线：提交类目标不在固定流程里执行。
                    if (BLOCKED_FLOW_TARGET.containsMatchIn(target)) { handback = "blocked_target"; break }

                    val snapshot = withContext(Dispatchers.Main.immediate) { service.captureScreenSnapshot() }
                        ?: return@trackScreenTool textJson {
                            put("status", "handback"); put("reason", "screen_unavailable")
                        }
                    if (operations.isStopped(runId)) { handback = "stopped_by_user"; break }
                    if (takeoverController != null) {
                        val sessionAfterCapture = takeoverController.state.value
                        val belongsToRun = sessionAfterCapture.isActive &&
                            (currentRunKey == null || sessionAfterCapture.runId == currentRunKey)
                        if (belongsToRun && sessionAfterCapture.phase == PhoneTakeoverPhase.RESUMING) {
                            if (!takeoverController.confirmResumedWithFreshSnapshot(snapshot)) {
                                if (takeoverController.state.value.phase == PhoneTakeoverPhase.RESUMING) {
                                    takeoverController.confirmPaused(
                                        snapshotId = snapshot.id,
                                        reason = "fresh_snapshot_unavailable",
                                        controller = service,
                                    )
                                }
                                handback = "fresh_snapshot_unavailable"
                                break
                            }
                        }
                        if ((belongsToRun && takeoverController.state.value.isPausedOrPausing) ||
                            service.isSnapshotInvalidated(snapshot.id)
                        ) {
                            service.invalidateSnapshot(snapshot.id, "takeover_snapshot_invalidated")
                            handback = "user_takeover_paused"
                            break
                        }
                        if (belongsToRun) {
                            takeoverController.recordObservedSnapshot(snapshot.id, index + 1)
                        }
                    }
                    if (snapshot.packageName != packageName) { handback = "foreground_package_changed"; break }
                    val candidates = snapshot.nodes.filter { it.label == target }
                    if (candidates.isEmpty()) { handback = "target_not_found"; break }
                    // 同名控件不猜：交给主模型用更具体的目标重新规划。
                    if (candidates.size > 1) { handback = "ambiguous_target"; break }
                    val node = candidates.single()
                    if (operation == "type" && !node.editable) { handback = "target_not_editable"; break }
                    if (operation == "click" && !node.clickable) { handback = "target_not_clickable"; break }

                    val expectationStep = step["expectation"] as? kotlinx.serialization.json.JsonObject
                    // absent 需要"动作前确实可见"作为前提，否则永远不可能通过。
                    val targetVisibleBefore = if (expectationStep?.string("kind") == "absent") {
                        val absentTarget = expectationStep.string("target").orEmpty()
                        absentTarget.isNotBlank() &&
                            snapshot.nodes.any { it.hasOwnLabel && it.label == absentTarget }
                    } else {
                        false
                    }
                    val action = app.amber.core.automation.ScreenAction(
                        kind = if (operation == "click") {
                            app.amber.core.automation.ScreenActionKind.CLICK
                        } else {
                            app.amber.core.automation.ScreenActionKind.TYPE
                        },
                        ref = node.ref,
                        text = text,
                    )
                    if (operations.isStopped(runId)) { handback = "stopped_by_user"; break }
                    if (takeoverController != null) {
                        val sessionBeforeDispatch = takeoverController.state.value
                        val belongsToRun = sessionBeforeDispatch.isActive &&
                            (currentRunKey == null || sessionBeforeDispatch.runId == currentRunKey)
                        if ((belongsToRun && sessionBeforeDispatch.isPausedOrPausing) ||
                            service.isSnapshotInvalidated(snapshot.id)
                        ) {
                            service.invalidateSnapshot(snapshot.id, "takeover_snapshot_invalidated")
                            handback = "user_takeover_paused"
                            break
                        }
                        if (!takeoverController.tryRecordSideEffectDispatch(snapshot.id, action)) {
                            handback = "side_effect_already_dispatched"
                            break
                        }
                    }
                    val receipt = withContext(Dispatchers.Main.immediate) {
                        service.performScreenAction(snapshot, action)
                    }
                    val expectation = expectationStep
                    val verdict = if (receipt.dispatched && expectation != null && receipt.status == "ok") {
                        awaitScreenExpectation(AccessibilityExpectationProbe(service), expectation, targetVisibleBefore)
                    } else {
                        null
                    }
                    executed += buildJsonObject {
                        put("step", index)
                        put("target", target)
                        put("operation", operation)
                        put("receipt", receipt.status)
                        put("dispatched", receipt.dispatched)
                        verdict?.let { put("verification", it.toJson()) }
                    }
                    // 回执 unknown / rejected 一律停手：可能已执行，禁止重放。
                    if (receipt.status != "ok") { handback = "receipt_${receipt.status}"; break }
                    if (verdict != null && !verdict.met) {
                        handback = if (verdict.ambiguous) "verification_ambiguous" else "verification_${verdict.detail}"
                        break
                    }
                    // 下一步从新观察开始：给界面一点稳定时间，避免读到过渡态。
                    delay(FLOW_STEP_SETTLE_MS)
                }
                val remaining = steps.size - executed.size
                textJson {
                    put("status", if (handback == null) "completed" else "handback")
                    handback?.let { put("reason", it) }
                    if (handback != null) {
                        put(
                            "next",
                            if (handback == "receipt_unknown") {
                                "never replay an unknown action; verify on screen and re-plan."
                            } else {
                                "re-plan the remaining steps from the returned evidence; do not replay executed ones."
                            },
                        )
                    }
                    put("steps_executed", executed.size)
                    put("steps_remaining", remaining.coerceAtLeast(0))
                    put("steps", buildJsonArray { executed.forEach { add(it) } })
                }
            }
        },
    )

    private fun allowedPackages(): List<String> =
        settingsStore?.settingsFlow?.value?.agentRuntime?.screenAutomation?.allowedPackages.orEmpty()
}

private const val MAX_FLOW_STEPS = 12
private const val FLOW_BUDGET_MS = 60_000L
/** 固定流程两步之间的稳定时间。 */
private const val FLOW_STEP_SETTLE_MS = 250L

/**
 * 固定流程不得点击/输入的目标。与 JevScreenGoalRunner 的候选排除同一个红线，
 * 只是这里由宿主自己判：提交、支付、删除、授权、登录类目标一律交回。
 */
private val BLOCKED_FLOW_TARGET = Regex(
    "发送|发布|支付|付款|购买|下单|删除|清空|授权|允许|登录|登出|退出登录|密码|验证码|充值|转账|提交|确认|" +
        "\\b(send|publish|post|submit|confirm|pay|buy|purchase|delete|authorize|allow|login|password|" +
        "verification code|subscribe|donate)\\b",
    RegexOption.IGNORE_CASE,
)
