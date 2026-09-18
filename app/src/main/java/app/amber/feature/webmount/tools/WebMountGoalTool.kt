package app.amber.feature.webmount.tools

import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.core.agent.utils.boolean
import app.amber.core.agent.utils.long
import app.amber.core.agent.utils.requiredString
import app.amber.core.agent.utils.string
import app.amber.core.jev.JevWebGoalRunner
import app.amber.core.jev.WebGoalAction
import app.amber.core.jev.WebGoalDriver
import app.amber.core.jev.WebGoalElement
import app.amber.feature.webmount.primitives.WebMountLease
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * `wm_run_goal`：有界网页目标快循环（Jev WEB_AUTOMATION）。
 *
 * 主模型显式调用才进入；默认 6 次动作决策 / 15s / 3 次无进展。只执行
 * 观察/滚动/后退/点击/受控输入（文本值只来自调用方提供的候选）；提交、
 * 支付、发布等高风险动作不在快循环内，交回主模型走原审批流程。整个工具
 * 强制逐次审批；每步动作仍经 runVerifiedAction 的快照校验与回执记录，
 * unknown 结果按 outcome_unknown 返回，绝不自动重放。
 */
internal fun createGoalTool(deps: WebMountDeps, runner: JevWebGoalRunner?): Tool = Tool(
    name = "wm_run_goal",
    description = """
        Run a bounded autonomous loop toward one web goal on an existing WebMount session.
        Each step observes the live snapshot, picks a low-risk action (observe/scroll/back/
        click/type), executes it with snapshot validation, and stops at completion, handback,
        user action, unknown outcome, or budget exhaustion. Type text values must be supplied
        by you in `texts`; this tool never invents input values. Submissions, payments, account
        changes and captchas are out of scope — it hands back to you. Requires approval per call.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("session_id", stringProp("Session id returned by wm_open."))
                put("goal", stringProp("One concrete user goal for this page, e.g. 'open the second search result'."))
                put("texts", buildJsonObject {
                    put("type", "array")
                    put("items", stringProp("Candidate text values the loop may type, supplied by you."))
                    put("description", "Optional. Type actions can only use these strings; without them typing hands back.")
                })
                put("max_steps", integerProp("Action decision budget. Default 6, max 6."))
                put("max_duration_ms", integerProp("Wall clock budget. Default 15000, max 15000."))
                put("dry_run", booleanProp("Produce the decision trace without executing actions (default false)."))
            },
            required = listOf("session_id", "goal"),
        )
    },
    needsApproval = true,
    allowsAutoApproval = false,
    mandatoryApproval = true,
    execute = { input ->
        deps.track("wm_run_goal", "WebMount 目标执行", input) {
            val sessionId = input.requiredString("session_id")
            if (runner == null) {
                return@track listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("session_id", sessionId)
                            put("ok", false)
                            put("error", "goal_runner_unavailable")
                        }.toString(),
                    ),
                )
            }
            deps.withAgentSession(input, sessionId) { lease ->
                val goal = input.requiredString("goal")
                if (goal.isBlank()) {
                    return@withAgentSession listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("session_id", sessionId)
                                put("ok", false)
                                put("status", "handback")
                                put("reason", "invalid_goal")
                            }.toString(),
                        ),
                    )
                }
                val driver = BridgeGoalDriver(deps, input, lease, sessionId)
                val outcome = runner.run(
                    driver = driver,
                    goal = goal,
                    texts = input.stringArray("texts"),
                    maxSteps = (input.long("max_steps") ?: 6L).toInt().coerceIn(1, 6),
                    maxDurationMs = (input.long("max_duration_ms") ?: 15_000L).coerceIn(1_000L, 15_000L),
                    dryRun = input.boolean("dry_run") ?: false,
                    runKey = input.webMountRunId(),
                )
                val payload = buildJsonObject {
                    put("session_id", sessionId)
                    put("ok", outcome.status in setOf("completed", "shadow_trace"))
                    put("status", outcome.status)
                    outcome.reason?.let { put("reason", it) }
                    put("steps_taken", outcome.steps.size)
                    put("steps", kotlinx.serialization.json.buildJsonArray {
                        outcome.steps.forEach { step ->
                            add(buildJsonObject {
                                put("action", step.action)
                                step.detail?.let { put("detail", it.take(160)) }
                                step.receipt?.let { put("receipt", it) }
                            })
                        }
                    })
                    outcome.lastState?.let { put("final_state", it) }
                    putWebMountWindowState(lease.handle)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        }
    },
)

private fun JsonElement.stringArray(key: String): List<String> {
    val array = (this as? JsonObject)?.get(key) as? JsonArray ?: return emptyList()
    return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
}

/** WebMount bridge 驱动：semantic_state / interactive extract / runVerifiedAction。 */
private class BridgeGoalDriver(
    private val deps: WebMountDeps,
    private val input: JsonElement,
    private val lease: WebMountLease,
    private val sessionId: String,
) : WebGoalDriver {

    private val handle get() = lease.handle
    private val dispatch get() = deps.dispatchWithLease(input, lease)

    override suspend fun pageState(): JsonObject {
        val bridge = handle.callBridge(
            "semantic_state",
            buildJsonObject { },
            timeoutMs = 2_000L,
            dispatchWithLease = dispatch,
        ) as? JsonObject ?: buildJsonObject { }
        // 合并窗口状态（popup/dialog → requires_human + resume_condition），
        // 否则 dialog 场景在生产中无法前置进入 needs_user_action。
        return buildJsonObject {
            bridge.forEach { (key, value) -> put(key, value) }
            putWebMountWindowState(handle)
        }
    }

    override suspend fun interactiveElements(max: Int): List<WebGoalElement> {
        val payload = handle.callBridge(
            "extract",
            buildJsonObject {
                put("mode", "interactive")
                put("max_nodes", max.toLong())
                put("visible_only", true)
            },
            timeoutMs = 6_000L,
            dispatchWithLease = dispatch,
        ) as? JsonObject ?: return emptyList()
        return parseElements(payload, max)
    }

    override suspend fun performAction(action: WebGoalAction): JsonObject {
        val verified: suspend (String, JsonObject, Long) -> JsonObject = { bridge, args, timeout ->
            runVerifiedAction(
                sessionId = sessionId,
                handle = handle,
                requestedSnapshotId = when (action) {
                    is WebGoalAction.Click -> action.element.snapshotId
                    is WebGoalAction.Type -> action.element.snapshotId
                    else -> null
                },
                leaseIsActive = {
                    deps.owner.isLeaseActive(
                        lease.leaseId,
                        input.webMountConversationId(),
                        input.webMountRunId(),
                    )
                },
                dispatchWithLease = dispatch,
            ) {
                handle.callBridge(bridge, args, timeoutMs = timeout, dispatchWithLease = dispatch)
            }
        }
        return when (action) {
            is WebGoalAction.Click -> verified(
                "click",
                buildJsonObject {
                    put("target", action.element.ref)
                    action.element.snapshotId?.let { put("snapshot_id", it) }
                },
                5_000L,
            )
            is WebGoalAction.Type -> verified(
                "type",
                buildJsonObject {
                    put("target", action.element.ref)
                    action.element.snapshotId?.let { put("snapshot_id", it) }
                    put("text", action.text)
                },
                8_000L,
            )
            WebGoalAction.ScrollDown -> verified(
                "scroll",
                buildJsonObject { put("by", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(0L), JsonPrimitive(600L)))) },
                5_000L,
            )
            WebGoalAction.ScrollUp -> verified(
                "scroll",
                buildJsonObject { put("by", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(0L), JsonPrimitive(-600L)))) },
                5_000L,
            )
            WebGoalAction.Back -> verified("back", buildJsonObject { }, 5_000L)
            WebGoalAction.Done -> buildJsonObject { } // never executed
        }
    }

    /** 从 extract 结果通用收集带 ref 的节点（不依赖具体包装层结构）。 */
    private fun parseElements(payload: JsonObject, max: Int): List<WebGoalElement> {
        val snapshotId = payload.stringField("snapshot_id")
        val out = mutableListOf<WebGoalElement>()
        fun walk(element: JsonElement) {
            if (out.size >= max) return
            when (element) {
                is JsonObject -> {
                    val ref = (element["ref"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    if (ref != null) {
                        val label = sequenceOf("name", "label", "text", "accessible_name", "aria", "role")
                            .mapNotNull { (element[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
                            .firstOrNull() ?: ref
                        out += WebGoalElement(ref = ref, snapshotId = snapshotId, label = label)
                    }
                    element.values.forEach(::walk)
                }
                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(payload)
        return out
    }

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
