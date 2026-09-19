package app.amber.core.jev

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 循环可操作的页面元素（ref + 快照身份 + 可读标签）。 */
data class WebGoalElement(val ref: String, val snapshotId: String?, val label: String)

/** 单步动作；文本值只来自主模型提供的候选，Jev 不生成任意字符串。 */
sealed interface WebGoalAction {
    data class Click(val element: WebGoalElement) : WebGoalAction

    data class Type(val element: WebGoalElement, val text: String) : WebGoalAction

    data object ScrollDown : WebGoalAction

    data object ScrollUp : WebGoalAction

    data object Back : WebGoalAction

    data object Done : WebGoalAction
}

/** 页面观察与动作执行的抽象；生产实现包 WebMount bridge，测试注入桩。 */
interface WebGoalDriver {
    /** semantic_state 摘要：snapshot_id/title/url/requires_human。 */
    suspend fun pageState(): JsonObject

    /** 当前快照中的可交互元素（上限由调用方给出）。 */
    suspend fun interactiveElements(max: Int): List<WebGoalElement>

    /**
     * 执行一个动作并返回 runVerifiedAction 回执（dispatched/status/
     * snapshot_id_after/page_changed/may_have_applied…）。status=unknown 的
     * 结果由调用方按 outcome_unknown 处理，绝不自动重放。
     */
    suspend fun performAction(action: WebGoalAction): JsonObject
}

/** 单步决策轨迹。 */
data class WebGoalStep(
    val index: Int,
    val action: String,
    val detail: String?,
    val receipt: JsonObject?,
)

data class WebGoalOutcome(
    val status: String,
    val reason: String?,
    val steps: List<WebGoalStep>,
    val lastState: JsonObject?,
)

/**
 * 有界网页目标快循环（WEB_AUTOMATION 用途）。
 *
 * 每轮从真实快照观察，Jev Choice 选动作、逐元素 Noul 选目标、DONE 经独立
 * noul 核验；默认 100 次动作决策 / 600s / 10 次无进展，且只执行读取/滚动/导航/
 * 点击/受控输入等低风险动作（提交/支付等一律 handback 回主模型）。网页动作
 * 不缓存；shadow/dry-run 只产决策轨迹不执行。任何 Jev 失败形态 handback。
 */
class JevWebGoalRunner(private val runtime: JevRuntime) {

    suspend fun run(
        driver: WebGoalDriver,
        goal: String,
        texts: List<String>,
        maxSteps: Int = DEFAULT_MAX_STEPS,
        maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
        dryRun: Boolean,
        runKey: String?,
    ): WebGoalOutcome {
        val config = runtime.configFor(JevPurpose.WEB_AUTOMATION)
            ?: return WebGoalOutcome("disabled", "jev web automation is off", emptyList(), null)
        val steps = mutableListOf<WebGoalStep>()
        val startedAt = System.currentTimeMillis()
        var noProgress = 0
        var lastState: JsonObject? = null
        var performedActions = 0

        while (steps.size < maxSteps && performedActions < maxSteps) {
            if (System.currentTimeMillis() - startedAt > maxDurationMs) {
                return WebGoalOutcome("budget_exhausted", "duration", steps, lastState)
            }
            val state = try {
                driver.pageState()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return WebGoalOutcome("error", "observe failed: ${e.message}", steps, lastState)
            }
            lastState = state
            if (state.booleanField("requires_human") == true) {
                return WebGoalOutcome("needs_user_action", state.stringField("resume_condition"), steps, state)
            }

            val elements = try {
                driver.interactiveElements(MAX_ELEMENTS)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            val stepOutcome = decideStep(goal, steps, state, elements, texts, runKey)
            if (stepOutcome.decision == null) {
                val reason = stepOutcome.unavailableReason?.let { ": $it" } ?: ""
                return WebGoalOutcome("handback", "jev decision unavailable$reason", steps, state)
            }
            val decision = stepOutcome.decision

            when (decision.action) {
                "done" -> {
                    val verified = decision.doneVerified == true
                    steps += WebGoalStep(steps.size, "done", "verified=$verified", null)
                    return if (verified) {
                        WebGoalOutcome("completed", null, steps, state)
                    } else {
                        WebGoalOutcome("handback", "done_not_verified", steps, state)
                    }
                }
                "handback" -> {
                    steps += WebGoalStep(steps.size, "handback", decision.detail, null)
                    return WebGoalOutcome("handback", decision.detail ?: "model requested handback", steps, state)
                }
            }

            if (decision.action == "observe") {
                // 原地观察计步数，也计入无进展
                steps += WebGoalStep(steps.size, "observe", null, null)
                if (++noProgress >= MAX_NO_PROGRESS) {
                    return WebGoalOutcome("handback", "no_progress", steps, state)
                }
                continue
            }
            val action: WebGoalAction = when (decision.action) {
                "scroll_down" -> WebGoalAction.ScrollDown
                "scroll_up" -> WebGoalAction.ScrollUp
                "back" -> WebGoalAction.Back
                "click" -> WebGoalAction.Click(decision.element!!)
                "type" -> WebGoalAction.Type(decision.element!!, decision.text!!)
                else -> return WebGoalOutcome("handback", "unmapped_action:${decision.action}", steps, state)
            }

            // 时钟上界在动作派发前再核一次，缩小单步观察/判题超时带来的软超窗。
            if (System.currentTimeMillis() - startedAt > maxDurationMs) {
                return WebGoalOutcome("budget_exhausted", "duration", steps, state)
            }
            if (dryRun || config.mode == JevMode.SHADOW) {
                steps += WebGoalStep(steps.size, decision.action, "dry-run${if (config.mode == JevMode.SHADOW) "/shadow" else ""}: not executed", null)
                // dry-run 产决策轨迹后即返回，不循环消耗预算
                return WebGoalOutcome("shadow_trace", null, steps, state)
            }

            val receipt = try {
                driver.performAction(action)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return WebGoalOutcome("outcome_unknown", "action reply lost: ${e.message}", steps, state)
            }
            steps += WebGoalStep(steps.size, decision.action, decision.detail, receipt)
            performedActions++

            val status = receipt.stringField("status")
            if (receipt.booleanField("requires_human") == true) {
                // dialog/popup 回执同时带 status=unknown 与 requires_human：人工接管优先归类
                return WebGoalOutcome("needs_user_action", receipt.stringField("resume_condition"), steps, state)
            }
            if (status == "unknown") {
                return WebGoalOutcome("outcome_unknown", receipt.stringField("error"), steps, state)
            }
            if (status == "failed") {
                if (++noProgress >= MAX_NO_PROGRESS) {
                    return WebGoalOutcome("handback", "no_progress", steps, state)
                }
            } else {
                noProgress = 0
            }
        }
        return WebGoalOutcome("steps_exhausted", null, steps, lastState)
    }

    private class StepDecision(
        val action: String,
        val element: WebGoalElement?,
        val text: String?,
        val detail: String?,
        val doneVerified: Boolean?,
    )

    /** decision 为 null 时带具体不可用原因（skip/fail 码），供 handback 透传。 */
    private class StepOutcome(
        val decision: StepDecision?,
        val unavailableReason: String?,
    )

    private suspend fun decideStep(
        goal: String,
        steps: List<WebGoalStep>,
        state: JsonObject,
        elements: List<WebGoalElement>,
        texts: List<String>,
        runKey: String?,
    ): StepOutcome {
        val actionOptions = buildMap {
            put("observe", "look more; the page state alone is not enough to decide")
            put("scroll_down", "scroll down to reveal more content")
            put("scroll_up", "scroll up")
            put("back", "navigate back")
            if (elements.isNotEmpty()) put("click", "click the best matching element")
            if (elements.isNotEmpty() && texts.isNotEmpty()) put("type", "type a provided text into the best matching input element")
            put("done", "the goal is already achieved on this page")
            put("handback", "cannot progress autonomously; return to the main model")
        }
        val pageState: JsonElement = buildJsonObject {
            put("goal", goal.take(1_000))
            put("note", "Only use the elements and texts listed. Never invent selectors or text values.")
            put("actions_taken", buildJsonArray {
                steps.takeLast(6).forEach { step ->
                    add(buildJsonObject {
                        put("action", step.action)
                        step.detail?.let { put("detail", it.take(120)) }
                    })
                }
            })
            put("page", buildJsonObject {
                state.stringField("title")?.let { put("title", it.take(120)) }
                state.stringField("url")?.let { put("url", it) }
                state.stringField("snapshot_id")?.let { put("snapshot_id", it) }
            })
            put("elements", buildJsonArray {
                elements.forEachIndexed { index, element ->
                    add(buildJsonObject {
                        put("i", index.toString())
                        put("label", element.label.take(160))
                    })
                }
            })
            if (texts.isNotEmpty()) {
                put("texts", buildJsonArray {
                    texts.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.take(300))) }
                })
            }
        }
        val questions = buildMap {
            put(
                "action",
                JevQuestion.Choice(
                    instructions = "Pick exactly one next action to advance the goal on the current page.",
                    options = actionOptions,
                ),
            )
            elements.forEachIndexed { index, _ ->
                put(
                    "target_$index",
                    JevQuestion.Noul(
                        instructions = "Is element i=$index in state.elements the correct target for the chosen click/type action toward the goal?",
                    ),
                )
            }
            if (texts.isNotEmpty()) {
                put(
                    "text",
                    JevQuestion.Choice(
                        instructions = "Which entry of state.texts should be typed?",
                        options = texts.mapIndexed { index, text -> "t$index" to text.take(80) }.toMap(),
                    ),
                )
            }
            put(
                "done_check",
                JevQuestion.Noul(
                    instructions = "Judging only from state.page and state.actions_taken, is the goal already fully achieved?",
                ),
            )
        }
        val outcome = runtime.decide(
            purpose = JevPurpose.WEB_AUTOMATION,
            runKey = runKey,
            state = pageState,
            questions = questions,
            requiredScopes = setOf(JevDataScope.WEB_CONTENT, JevDataScope.TASK_TEXT),
            cacheAnchor = null, // 网页动作不缓存
        ) ?: return StepOutcome(null, "off")
        val evaluated = outcome.evaluated ?: return StepOutcome(null, outcome.decision.unavailableReason())
        // shadow 也要产出决策轨迹（run() 层已保证不执行）；只有过期才回退。
        if (outcome.stale) return StepOutcome(null, "stale_config")

        val actionAnswer = evaluated.answers["action"] as? JevAnswer.Choice
            ?: return StepOutcome(null, "missing_action_answer")
        // 低置信门：Jev 自报信心不足时交还主模型，不替它下注。
        val confidence = actionAnswer.confidence
        if (confidence != null && confidence < LOW_CONFIDENCE_THRESHOLD) {
            return StepOutcome(null, "low_confidence:$confidence")
        }
        val action = actionAnswer.selected.takeIf { it in actionOptions }
            ?: return StepOutcome(null, "unknown_action:${actionAnswer.selected}")

        var element: WebGoalElement? = null
        if (action == "click" || action == "type") {
            var best: Pair<Int, Double>? = null
            elements.forEachIndexed { index, _ ->
                val answer = evaluated.answers["target_$index"] as? JevAnswer.Noul ?: return@forEachIndexed
                if (answer.probability >= TARGET_THRESHOLD && (best == null || answer.probability > best!!.second)) {
                    best = index to answer.probability
                }
            }
            element = best?.let { elements[it.first] } ?: return StepOutcome(
                StepDecision(
                    action = "handback",
                    element = null,
                    text = null,
                    detail = "no_element_target",
                    doneVerified = null,
                ),
                null,
            )
        }
        var text: String? = null
        if (action == "type") {
            val textAnswer = evaluated.answers["text"] as? JevAnswer.Choice
            val textIndex = textAnswer?.selected?.removePrefix("t")?.toIntOrNull()
            text = textIndex?.let { texts.getOrNull(it) }
            if (text == null) {
                return StepOutcome(StepDecision("handback", null, null, "no_text_value", null), null)
            }
        }
        val doneVerified = (evaluated.answers["done_check"] as? JevAnswer.Noul)?.let { it.probability >= DONE_VERIFIED_THRESHOLD }
        return StepOutcome(StepDecision(action, element, text, element?.label, doneVerified), null)
    }

    private fun JevDecision.unavailableReason(): String = when (this) {
        is JevDecision.Skipped -> reason.name.lowercase()
        is JevDecision.Failed -> reason.name.lowercase()
        is JevDecision.Evaluated -> "evaluated"
    }

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.booleanField(key: String): Boolean? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.let {
            runCatching { it.content.lowercase() == "true" }.getOrDefault(false)
        }

    companion object {
        const val DEFAULT_MAX_STEPS = 100
        const val DEFAULT_MAX_DURATION_MS = 600_000L
        const val MAX_NO_PROGRESS = 10
        const val MAX_ELEMENTS = 24
        const val TARGET_THRESHOLD = 0.5
        const val DONE_VERIFIED_THRESHOLD = 0.6
        /** Choice 答案自报信心低于此值时交还主模型（0.5 与 iOS 一致）。 */
        const val LOW_CONFIDENCE_THRESHOLD = 0.5
    }
}
