package app.amber.feature.ui.components.message

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.ai.ui.UIMessagePart
import app.amber.common.http.jsonObjectOrNull
import app.amber.core.automation.SensitiveScreenTextClassifier
import app.amber.core.utils.JsonInstant
import app.amber.core.utils.JsonInstantPretty
import app.amber.core.utils.jsonPrimitiveOrNull
import app.amber.core.utils.maskSensitiveJson
import app.amber.feature.ui.components.richtext.HighlightCodeBlock
import app.amber.feature.ui.components.richtext.ZoomableAsyncImage
import app.amber.feature.ui.theme.LocalAmberTokens
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldAlert
import com.composables.icons.lucide.X
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

enum class ScreenReceiptTier {
    /** Task goal or screen action completed and passed postcondition verification. */
    VERIFIED_COMPLETED,

    /** Pre-commit steps succeeded; sensitive final action (send/pay/submit/delete/auth) held for user. */
    HELD_FOR_USER,

    /** Partial progress or handback to main model/user due to unmet postcondition or step/time limit. */
    PARTIAL_OR_HANDBACK,

    /** Action was dispatched or in-flight when screen state became unavailable. */
    OUTCOME_UNKNOWN,

    /** Screen tool was blocked, disabled, or failed before completion. */
    FAILED_OR_BLOCKED,
}

data class ScreenReceiptStepItem(
    val stepNumber: Int,
    val actionKind: String,
    val targetRef: String?,
    val summaryLabel: String,
    val status: String,
    val dispatched: Boolean,
)

data class ScreenReceiptHeldActionItem(
    val label: String,
    val actionKind: String?,
    val targetRef: String?,
    val reason: String,
    val isSelectedChoice: Boolean,
)

data class ScreenReceiptOcrSummary(
    val triggered: Boolean,
    val mode: String?,
    val reason: String?,
    val blockCount: Int,
    val coverageRatio: Float?,
    val sampleTexts: List<String>,
)

data class ScreenReceiptHostReturnSummary(
    val returnedToHost: Boolean,
    val hostReturnReason: String?,
    val snapshotInvalidated: Boolean,
)

data class ScreenToolReceiptModel(
    val toolName: String,
    val tier: ScreenReceiptTier,
    val rawStatus: String,
    val rawReason: String?,
    val goal: String?,
    val packageName: String?,
    val executedSteps: List<ScreenReceiptStepItem>,
    val heldSensitiveActions: List<ScreenReceiptHeldActionItem>,
    val unmetReasons: List<String>,
    val recommendedNextStep: String?,
    val ocrSummary: ScreenReceiptOcrSummary?,
    val hostReturnSummary: ScreenReceiptHostReturnSummary?,
    val takeoverCount: Int,
) {
    val hasHeldSensitiveActions: Boolean get() = heldSensitiveActions.isNotEmpty()
    val hasUnmetReasons: Boolean get() = unmetReasons.isNotEmpty()
}

object ScreenToolReceiptParser {

    fun isScreenTool(toolName: String): Boolean =
        toolName.startsWith("screen_") || toolName == "vlm_task"

    fun parse(
        toolName: String,
        arguments: JsonElement,
        content: JsonElement?,
        context: android.content.Context? = null,
    ): ScreenToolReceiptModel? {
        if (!isScreenTool(toolName)) return null
        val obj = content?.jsonObjectOrNull ?: return null
        val argsObj = arguments.jsonObjectOrNull

        val rawStatus = obj.string("status")
            ?: when {
                obj.bool("success") == false -> "failed"
                obj.bool("verified") == true || obj.bool("matched") == true || obj.bool("found") == true -> "ok"
                obj.bool("verified") == false || obj.bool("matched") == false || obj.bool("found") == false -> "unverified"
                !obj.string("error").isNullOrBlank() -> "failed"
                else -> "ok"
            }
        val rawReason = obj.string("reason") ?: obj.string("error")
        val goal = obj.string("goal") ?: argsObj?.string("goal")
        val packageName = obj.string("package_name")
            ?: obj.string("package")
            ?: argsObj?.string("package_name")
            ?: argsObj?.string("package")

        val executedSteps = parseExecutedSteps(toolName, argsObj, obj, context)
        val heldSensitiveActions = parseHeldSensitiveActions(obj, context)
        val tier = determineTier(
            toolName = toolName,
            rawStatus = rawStatus.lowercase(),
            rawReason = rawReason,
            obj = obj,
            heldSensitiveActions = heldSensitiveActions,
        )
        val unmetReasons = buildUnmetReasons(
            toolName = toolName,
            tier = tier,
            rawStatus = rawStatus.lowercase(),
            rawReason = rawReason,
            obj = obj,
            heldSensitiveActions = heldSensitiveActions,
            context = context,
        )
        val recommendedNextStep = obj.string("next")
            ?: obj.string("message")?.takeIf { toolName == "vlm_task" }
        val ocrSummary = parseOcrSummary(obj)
        val hostReturnSummary = parseHostReturnSummary(obj)
        val takeoverCount = obj.int("takeover_count") ?: 0

        return ScreenToolReceiptModel(
            toolName = toolName,
            tier = tier,
            rawStatus = rawStatus,
            rawReason = rawReason,
            goal = goal?.let(SensitiveScreenTextClassifier::redactIfSensitive),
            packageName = packageName,
            executedSteps = executedSteps,
            heldSensitiveActions = heldSensitiveActions,
            unmetReasons = unmetReasons,
            recommendedNextStep = recommendedNextStep,
            ocrSummary = ocrSummary,
            hostReturnSummary = hostReturnSummary,
            takeoverCount = takeoverCount,
        )
    }

    private fun determineTier(
        toolName: String,
        rawStatus: String,
        rawReason: String?,
        obj: JsonObject,
        heldSensitiveActions: List<ScreenReceiptHeldActionItem>,
    ): ScreenReceiptTier {
        // Takeover / held outcomes return success=false, so they must be classified before the
        // failure short-circuit or a paused hand-back would render as a red "failed" receipt.
        val needsUserConfirmation = rawStatus == "needs_user_action" ||
            rawReason in setOf(
                "action_not_read_only",
                "user_takeover_paused",
                "fresh_snapshot_unavailable",
                "takeover_snapshot_not_refreshed",
            ) ||
            (heldSensitiveActions.any { it.isSelectedChoice } && rawStatus != "completed")
        if (!obj.string("error").isNullOrBlank() ||
            rawStatus in setOf(
                "failed",
                "error",
                "disabled",
                "busy",
                "denied",
                "timed_out",
                "policy_denied",
                // The allow-list refused the tool and nothing was dispatched.
                "no_scope",
                // The flow rejected its input before the first step.
                "rejected",
            ) ||
            (obj.bool("success") == false && !needsUserConfirmation)
        ) {
            return ScreenReceiptTier.FAILED_OR_BLOCKED
        }
        if (rawStatus in setOf("outcome_unknown", "unknown")) {
            return ScreenReceiptTier.OUTCOME_UNKNOWN
        }
        if (needsUserConfirmation) {
            return ScreenReceiptTier.HELD_FOR_USER
        }
        val verifyTextFound = obj.bool("verify_text_found")
        val verified = obj.bool("verified")
        val matched = obj.bool("matched")
        val found = obj.bool("found")
        // screen_click/swipe/long_click with `expectation` report the acceptance result here.
        val expectationMet = (obj["verification"] as? JsonObject)?.bool("met")
        if (rawStatus in setOf(
                "handback",
                "steps_exhausted",
                "budget_exhausted",
                "shadow_trace",
                "stale_snapshot",
                "unverified",
                // vlm_task only records intent; it never dispatches an action.
                "planned",
            ) ||
            verifyTextFound == false ||
            expectationMet == false ||
            (verified == false && toolName in setOf("screen_tap_text", "screen_click", "screen_input_text")) ||
            ((matched == false || found == false) && toolName == "screen_wait_for_text") ||
            (found == false && toolName in setOf("screen_find_text", "screen_scroll_until"))
        ) {
            return if (heldSensitiveActions.isNotEmpty()) {
                ScreenReceiptTier.HELD_FOR_USER
            } else {
                ScreenReceiptTier.PARTIAL_OR_HANDBACK
            }
        }
        if (heldSensitiveActions.isNotEmpty() && rawStatus != "completed") {
            return ScreenReceiptTier.HELD_FOR_USER
        }
        return ScreenReceiptTier.VERIFIED_COMPLETED
    }

    private fun parseExecutedSteps(
        toolName: String,
        argsObj: JsonObject?,
        obj: JsonObject,
        context: android.content.Context? = null,
    ): List<ScreenReceiptStepItem> {
        val stepsArray = obj["steps"] as? JsonArray
        if (stepsArray != null) {
            val result = mutableListOf<ScreenReceiptStepItem>()
            stepsArray.forEachIndexed { index, element ->
                val stepObj = element.jsonObjectOrNull ?: return@forEachIndexed
                val dispatched = stepObj.bool("dispatched") ?: (stepObj.string("status") == "ok")
                val reason = stepObj.string("reason")
                if (!dispatched) {
                    // Shadow/stale/held steps were never sent to the device; held ones are listed
                    // separately and the rest must not inflate "confirmed executed steps".
                    return@forEachIndexed
                }
                val actionKind = stepObj.string("action")
                    ?: stepObj.string("choice")
                    ?: "step"
                val targetRef = stepObj.string("target")
                val rawLabel = stepObj.string("label")
                    ?: stepObj.string("choice")
                    ?: actionKind
                val status = stepObj.string("status") ?: if (dispatched) "ok" else "skipped"
                result += ScreenReceiptStepItem(
                    stepNumber = result.size + 1,
                    actionKind = actionKind,
                    targetRef = targetRef,
                    summaryLabel = SensitiveScreenTextClassifier.redactIfSensitive(rawLabel),
                    status = status,
                    dispatched = dispatched,
                )
            }
            return result
        }

        // Single low-level screen_* tool call. vlm_task only plans, so it has no executed step.
        if (toolName == "vlm_task") return emptyList()
        val isFailed = !obj.string("error").isNullOrBlank() ||
            obj.string("status") in setOf("failed", "error", "disabled", "busy", "stale_snapshot")
        if (isFailed) return emptyList()

        val label = when (toolName) {
            "screen_open_app" -> {
                val pkg = obj.string("package_name") ?: argsObj?.string("package_name").orEmpty()
                (context?.getString(R.string.screen_receipt_step_open_app, pkg) ?: "打开应用 $pkg").trim()
            }
            "screen_open_url" -> {
                val url = obj.string("url") ?: argsObj?.string("url").orEmpty()
                (context?.getString(R.string.screen_receipt_step_open_url, url) ?: "打开链接 $url").trim()
            }
            "screen_tap_text" -> {
                val text = obj.string("text") ?: argsObj?.string("text").orEmpty()
                context?.getString(R.string.screen_receipt_step_tap_text, text) ?: "点击文本 \"$text\""
            }
            "screen_find_text" -> {
                val text = argsObj?.string("text").orEmpty()
                val count = obj.int("count") ?: 0
                context?.getString(R.string.screen_receipt_step_find_text, text, count)
                    ?: "查找文本 \"$text\" ($count 处匹配)"
            }
            "screen_wait_for_text" -> {
                val text = obj.string("text") ?: argsObj?.string("text").orEmpty()
                context?.getString(R.string.screen_receipt_step_wait_for_text, text) ?: "等待文本 \"$text\""
            }
            "screen_scroll_until" -> {
                val text = argsObj?.string("text").orEmpty()
                val swipes = obj.int("swipes") ?: 0
                context?.getString(R.string.screen_receipt_step_scroll_until, text, swipes)
                    ?: "滚动查找 \"$text\" (滑动 $swipes 次)"
            }
            "screen_input_text" -> {
                val rawText = SensitiveScreenTextClassifier.redactIfSensitive(argsObj?.string("text").orEmpty())
                context?.getString(R.string.screen_receipt_step_input_text, rawText)
                    ?: "输入文本 \"$rawText\""
            }
            "screen_click" -> {
                val target = argsObj?.string("node_ref") ?: "(${argsObj?.int("x") ?: 0}, ${argsObj?.int("y") ?: 0})"
                context?.getString(R.string.screen_receipt_step_click, target)
                    ?: "点击坐标/节点 $target"
            }
            "screen_long_click" -> {
                val x = argsObj?.int("x") ?: 0
                val y = argsObj?.int("y") ?: 0
                context?.getString(R.string.screen_receipt_step_long_click, x, y)
                    ?: "长按坐标 ($x, $y)"
            }
            "screen_swipe" -> context?.getString(R.string.screen_receipt_step_swipe) ?: "滑动屏幕"
            "screen_back" -> context?.getString(R.string.screen_receipt_step_back) ?: "返回上一级"
            "screen_home" -> context?.getString(R.string.screen_receipt_step_home) ?: "回到主屏幕"
            "screen_read_ui" -> context?.getString(R.string.screen_receipt_step_read_ui) ?: "读取当前界面结构"
            "screen_screenshot" -> {
                val w = obj.int("width") ?: 0
                val h = obj.int("height") ?: 0
                context?.getString(R.string.screen_receipt_step_screenshot, w, h)
                    ?: "截取当前屏幕 (${w}x${h})"
            }
            "vlm_task" -> {
                val g = obj.string("goal") ?: argsObj?.string("goal").orEmpty()
                (context?.getString(R.string.screen_receipt_step_plan_goal, g) ?: "规划屏幕目标 $g").trim()
            }
            else -> toolName
        }
        val dispatched = obj.bool("dispatched") ?: true
        return listOf(
            ScreenReceiptStepItem(
                stepNumber = 1,
                actionKind = toolName.removePrefix("screen_"),
                targetRef = argsObj?.string("node_ref") ?: obj.string("snapshot_id"),
                summaryLabel = SensitiveScreenTextClassifier.redactIfSensitive(label),
                status = obj.string("status") ?: "ok",
                dispatched = dispatched,
            )
        )
    }

    private fun parseHeldSensitiveActions(
        obj: JsonObject,
        context: android.content.Context? = null,
    ): List<ScreenReceiptHeldActionItem> {
        val items = mutableListOf<ScreenReceiptHeldActionItem>()
        val seenLabels = mutableSetOf<String>()

        val heldArray = obj["held_sensitive_actions"] as? JsonArray
        heldArray?.forEach { element ->
            val itemObj = element.jsonObjectOrNull ?: return@forEach
            val rawLabel = itemObj.string("label") ?: itemObj.string("choice") ?: return@forEach
            val cleanLabel = SensitiveScreenTextClassifier.redactIfSensitive(rawLabel)
            if (seenLabels.add(cleanLabel)) {
                items += ScreenReceiptHeldActionItem(
                    label = cleanLabel,
                    actionKind = itemObj.string("action"),
                    targetRef = itemObj.string("target"),
                    reason = itemObj.string("reason") ?: "action_not_read_only",
                    isSelectedChoice = true,
                )
            }
        }

        // Fallback: check steps array for undispatched action_not_read_only steps
        val stepsArray = obj["steps"] as? JsonArray
        stepsArray?.forEach { element ->
            val stepObj = element.jsonObjectOrNull ?: return@forEach
            if (stepObj.bool("dispatched") == false && stepObj.string("reason") == "action_not_read_only") {
                val rawLabel = stepObj.string("label")
                    ?: stepObj.string("choice")
                    ?: (context?.getString(R.string.screen_receipt_sensitive_action_default) ?: "敏感操作")
                val cleanLabel = SensitiveScreenTextClassifier.redactIfSensitive(rawLabel)
                if (seenLabels.add(cleanLabel)) {
                    items += ScreenReceiptHeldActionItem(
                        label = cleanLabel,
                        actionKind = stepObj.string("action"),
                        targetRef = stepObj.string("target"),
                        reason = "action_not_read_only",
                        isSelectedChoice = true,
                    )
                }
            }
        }

        val intercepted = obj.string("intercepted_sensitive_action")
        if (!intercepted.isNullOrBlank()) {
            val cleanLabel = SensitiveScreenTextClassifier.redactIfSensitive(intercepted)
            if (seenLabels.add(cleanLabel)) {
                items += ScreenReceiptHeldActionItem(
                    label = cleanLabel,
                    actionKind = null,
                    targetRef = null,
                    reason = "action_not_read_only",
                    isSelectedChoice = true,
                )
            }
        }

        val blockedArray = obj["blocked_sensitive_candidates"] as? JsonArray
        blockedArray?.forEach { element ->
            val itemObj = element.jsonObjectOrNull ?: return@forEach
            val rawLabel = itemObj.string("label") ?: return@forEach
            val cleanLabel = SensitiveScreenTextClassifier.redactIfSensitive(rawLabel)
            if (seenLabels.add(cleanLabel)) {
                items += ScreenReceiptHeldActionItem(
                    label = cleanLabel,
                    actionKind = itemObj.string("action"),
                    targetRef = itemObj.string("target"),
                    reason = itemObj.string("reason") ?: "sensitive_commit_button_filtered",
                    isSelectedChoice = false,
                )
            }
        }

        return items
    }

    private fun buildUnmetReasons(
        toolName: String,
        tier: ScreenReceiptTier,
        rawStatus: String,
        rawReason: String?,
        obj: JsonObject,
        heldSensitiveActions: List<ScreenReceiptHeldActionItem>,
        context: android.content.Context? = null,
    ): List<String> {
        if (tier == ScreenReceiptTier.VERIFIED_COMPLETED) return emptyList()
        val reasons = mutableListOf<String>()

        when (rawReason) {
            "action_not_read_only" ->
                reasons += context?.getString(R.string.screen_receipt_reason_action_not_read_only)
                    ?: "已为您完成前置步骤，最后一步属于具有外部副作用的敏感操作，已暂停并等待您手动点击确认。"
            "user_takeover_paused" ->
                reasons += context?.getString(R.string.screen_receipt_reason_user_takeover_paused)
                    ?: "您已主动接管当前手机操作，自动执行已安全暂停。"
            "fresh_snapshot_unavailable", "takeover_snapshot_not_refreshed" ->
                reasons += context?.getString(R.string.screen_receipt_reason_fresh_snapshot_unavailable)
                    ?: "接管后未能重新读取到最新画面，已保持暂停；请回到目标应用后再次点击「继续自动操作」。"
            "foreground_package_changed" ->
                reasons += context?.getString(R.string.screen_receipt_reason_foreground_package_changed)
                    ?: "当前前台应用已切换（如弹出系统权限框或跳转到其他应用），为保护隐私已暂停跨应用操作。"
            "done_not_verified" ->
                reasons += context?.getString(R.string.screen_receipt_reason_done_not_verified)
                    ?: "当前屏幕尚未出现足以证明目标完全达成的视觉证据。"
            "completion_snapshot_changed" ->
                reasons += context?.getString(R.string.screen_receipt_reason_completion_snapshot_changed)
                    ?: "完成验收瞬间界面状态仍在变化，未能锁定最终验收快照。"
            "no_progress" ->
                reasons += context?.getString(R.string.screen_receipt_reason_no_progress)
                    ?: "连续两步操作后界面无变化（可能已滑到底部或按钮未响应）。"
            "screen_unavailable", "post_action_screen_unavailable" ->
                reasons += context?.getString(R.string.screen_receipt_reason_screen_unavailable)
                    ?: "无法读取当前屏幕无障碍节点树（可能处于安全隐私界面或无障碍服务未就绪）。"
            "stale_snapshot", "takeover_snapshot_invalidated", "returned_to_host_snapshot_invalidated" ->
                reasons += context?.getString(R.string.screen_receipt_reason_stale_snapshot)
                    ?: "操作前界面快照已过期失效，已拦截基于旧界面的重复动作。"
            "jev_requested_handback" ->
                reasons += context?.getString(R.string.screen_receipt_reason_jev_handback)
                    ?: "当前界面存在歧义或需要额外信息，已交回主模型或由您确认。"
            "duration" ->
                reasons += context?.getString(R.string.screen_receipt_reason_duration)
                    ?: "单次屏幕目标执行达到安全时长上限。"
            "enable_jev_screen_automation" ->
                reasons += context?.getString(R.string.screen_receipt_reason_jev_disabled)
                    ?: "尚未在设置中开启 Jev 屏幕自动化策略。"
            "screen_content_or_task_text_not_authorized" ->
                reasons += context?.getString(R.string.screen_receipt_reason_scope_not_authorized)
                    ?: "尚未授权屏幕内容或任务文本数据范围。"
            "no_app_scope" ->
                reasons += context?.getString(R.string.screen_receipt_reason_no_app_scope)
                    ?: "尚未授权任何应用，自动操作未开始；请先让我确认要操作的应用。"
            "package_not_allowed" ->
                reasons += context?.getString(R.string.screen_receipt_reason_package_not_allowed)
                    ?: "目标应用不在已授权列表，自动操作未开始；请先授权该应用。"
            "no_run_scope" ->
                reasons += context?.getString(R.string.screen_receipt_reason_no_run_scope)
                    ?: "本次运行没有宿主绑定的任务范围，未执行任何自动操作。"
            else -> if (!rawReason.isNullOrBlank()) {
                reasons += rawReason
            }
        }

        if (rawReason?.startsWith("steps_must_be") == true) {
            reasons += context?.getString(R.string.screen_receipt_reason_invalid_steps_count)
                ?: "固定流程的步骤数量不合法，未执行任何步骤。"
        }

        if (rawStatus == "steps_exhausted" && reasons.isEmpty()) {
            reasons += context?.getString(R.string.screen_receipt_reason_steps_exhausted)
                ?: "已达到单次自动操作的最大步数上限，尚未观察到最终完成标志。"
        }
        if (rawStatus == "budget_exhausted" && reasons.isEmpty()) {
            reasons += context?.getString(R.string.screen_receipt_reason_budget_exhausted)
                ?: "已达到单次自动操作的时间预算上限。"
        }
        if (rawStatus == "planned" && reasons.isEmpty()) {
            reasons += context?.getString(R.string.screen_receipt_reason_planned_only)
                ?: "该工具只生成屏幕操作计划，未执行任何点击或输入。"
        }
        if ((obj["verification"] as? JsonObject)?.bool("met") == false) {
            reasons += context?.getString(R.string.screen_receipt_reason_expectation_unmet)
                ?: "点击后未满足你设定的验收条件。"
        }
        if (rawStatus == "outcome_unknown" && reasons.isEmpty()) {
            reasons += context?.getString(R.string.screen_receipt_reason_outcome_unknown)
                ?: "动作已发出，但界面回执尚未确认，请先核对当前屏幕状态避免重复点击。"
        }
        if (obj.bool("verify_text_found") == false) {
            val expected = obj.string("verify_text").orEmpty()
            reasons += if (expected.isNotBlank()) {
                context?.getString(R.string.screen_receipt_reason_verify_text_missing, expected)
                    ?: "点击后未在界面上检测到预期验收文字「$expected」。"
            } else {
                context?.getString(R.string.screen_receipt_reason_verify_change_missing)
                    ?: "点击后界面未出现预期变化。"
            }
        }
        if (toolName == "screen_wait_for_text" && (obj.bool("matched") == false || obj.bool("found") == false)) {
            reasons += context?.getString(R.string.screen_receipt_reason_wait_timeout)
                ?: "等待超时，目标文字尚未出现在当前屏幕上。"
        }
        if (toolName == "screen_scroll_until" && obj.bool("found") == false) {
            reasons += if (obj.bool("stalled") == true) {
                context?.getString(R.string.screen_receipt_reason_scroll_stalled)
                    ?: "已滑动到底部且画面不再变化，未找到目标文字。"
            } else {
                context?.getString(R.string.screen_receipt_reason_scroll_exhausted)
                    ?: "达到最大滑动次数后仍未找到目标文字。"
            }
        }
        if (heldSensitiveActions.isNotEmpty() && reasons.isEmpty()) {
            val labels = heldSensitiveActions.take(2).joinToString("、") { "「${it.label}」" }
            reasons += context?.getString(R.string.screen_receipt_reason_held_buttons_detected, labels)
                ?: "检测到敏感提交按钮 $labels，已自动留给您亲自确认。"
        }

        return reasons.distinct()
    }

    private fun parseOcrSummary(obj: JsonObject): ScreenReceiptOcrSummary? {
        val triggered = obj.bool("ocr_triggered")
        val ocrBlocks = obj["ocr_blocks"] as? JsonArray
        val treeCoverage = obj["tree_coverage"] as? JsonObject
        if (triggered == null && ocrBlocks == null && treeCoverage == null) return null

        val blockCount = obj.int("ocr_block_count") ?: ocrBlocks?.size ?: 0
        val coverageRatio = treeCoverage?.float("coverage_ratio")
        val samples = ocrBlocks
            ?.mapNotNull { it.jsonObjectOrNull?.string("text") }
            ?.filter { it.isNotBlank() }
            ?.take(6)
            ?.map(SensitiveScreenTextClassifier::redactIfSensitive)
            .orEmpty()

        return ScreenReceiptOcrSummary(
            triggered = triggered ?: (blockCount > 0),
            mode = obj.string("ocr_mode"),
            reason = obj.string("ocr_reason"),
            blockCount = blockCount,
            coverageRatio = coverageRatio,
            sampleTexts = samples,
        )
    }

    private fun parseHostReturnSummary(obj: JsonObject): ScreenReceiptHostReturnSummary? {
        val returned = obj.bool("returned_to_host")
        val reason = obj.string("host_return_reason")
        if (returned == null && reason == null) return null
        return ScreenReceiptHostReturnSummary(
            returnedToHost = returned ?: false,
            hostReturnReason = reason,
            snapshotInvalidated = obj.bool("snapshot_invalidated") ?: false,
        )
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.bool(key: String): Boolean? =
        this[key]?.jsonPrimitiveOrNull?.booleanOrNull

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitiveOrNull?.intOrNull

    private fun JsonObject.float(key: String): Float? =
        this[key]?.jsonPrimitiveOrNull?.floatOrNull
            ?: this[key]?.jsonPrimitiveOrNull?.doubleOrNull?.toFloat()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScreenToolReceiptInlineSummary(
    receipt: ScreenToolReceiptModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    val tierColor = when (receipt.tier) {
        ScreenReceiptTier.VERIFIED_COMPLETED -> Color(0xFF2EA043)
        ScreenReceiptTier.HELD_FOR_USER -> Color(0xFFD9822B)
        ScreenReceiptTier.PARTIAL_OR_HANDBACK -> Color(0xFFD9822B)
        ScreenReceiptTier.OUTCOME_UNKNOWN -> tokens.ink3
        ScreenReceiptTier.FAILED_OR_BLOCKED -> Color(0xFFD94848)
    }
    val tierBadgeText = when (receipt.tier) {
        ScreenReceiptTier.VERIFIED_COMPLETED -> stringResource(R.string.screen_receipt_tier_badge_verified)
        ScreenReceiptTier.HELD_FOR_USER -> stringResource(R.string.screen_receipt_tier_badge_held)
        ScreenReceiptTier.PARTIAL_OR_HANDBACK -> stringResource(R.string.screen_receipt_tier_badge_handback)
        ScreenReceiptTier.OUTCOME_UNKNOWN -> stringResource(R.string.screen_receipt_tier_badge_unknown)
        ScreenReceiptTier.FAILED_OR_BLOCKED -> stringResource(R.string.screen_receipt_tier_badge_failed)
    }
    val tierIcon = when (receipt.tier) {
        ScreenReceiptTier.VERIFIED_COMPLETED -> Lucide.Check
        ScreenReceiptTier.HELD_FOR_USER -> Lucide.ShieldAlert
        ScreenReceiptTier.PARTIAL_OR_HANDBACK,
        ScreenReceiptTier.OUTCOME_UNKNOWN -> Lucide.Clock
        ScreenReceiptTier.FAILED_OR_BLOCKED -> Lucide.X
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 32.dp, top = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .minimumInteractiveComponentSize()
            .heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        color = tokens.surface,
        contentColor = tokens.ink2,
        border = BorderStroke(
            width = 1.dp,
            color = if (receipt.tier == ScreenReceiptTier.HELD_FOR_USER) {
                tierColor.copy(alpha = 0.45f)
            } else {
                tokens.line
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = tierIcon,
                        contentDescription = null,
                        tint = tierColor,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = tierBadgeText,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = tierColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (receipt.executedSteps.isNotEmpty()) {
                    ReceiptMetricChip(
                        text = "${stringResource(R.string.screen_receipt_section_executed_steps)} (${receipt.executedSteps.size})",
                        color = tokens.ink3,
                        background = tokens.surface2,
                    )
                }

                if (receipt.heldSensitiveActions.isNotEmpty()) {
                    val firstHeld = receipt.heldSensitiveActions.first().label
                    ReceiptMetricChip(
                        text = stringResource(R.string.screen_receipt_chip_held_action, firstHeld),
                        color = Color(0xFFD9822B),
                        background = Color(0xFFD9822B).copy(alpha = 0.12f),
                    )
                }

                if (receipt.ocrSummary?.triggered == true) {
                    ReceiptMetricChip(
                        text = stringResource(R.string.screen_receipt_chip_ocr_count, receipt.ocrSummary.blockCount),
                        color = tokens.ink3,
                        background = tokens.surface2,
                    )
                }

                if (receipt.hostReturnSummary != null) {
                    val returnLabel = if (receipt.hostReturnSummary.returnedToHost) {
                        stringResource(R.string.screen_receipt_chip_returned_to_host)
                    } else if (receipt.tier == ScreenReceiptTier.HELD_FOR_USER) {
                        stringResource(R.string.screen_receipt_chip_staying_for_confirm)
                    } else {
                        null
                    }
                    if (returnLabel != null) {
                        ReceiptMetricChip(
                            text = returnLabel,
                            color = tokens.ink3,
                            background = tokens.surface2,
                        )
                    }
                }
            }

            val detailLine = when {
                receipt.heldSensitiveActions.isNotEmpty() -> {
                    val heldNames = receipt.heldSensitiveActions.take(2).joinToString("、") { "「${it.label}」" }
                    val reasonText = receipt.unmetReasons.firstOrNull()
                        ?: stringResource(R.string.screen_receipt_held_default_reason)
                    stringResource(R.string.screen_receipt_inline_held_detail, heldNames, reasonText)
                }
                receipt.unmetReasons.isNotEmpty() -> receipt.unmetReasons.first()
                receipt.executedSteps.isNotEmpty() -> {
                    val lastStep = receipt.executedSteps.last()
                    stringResource(R.string.screen_receipt_inline_last_step, lastStep.summaryLabel)
                }
                else -> null
            }

            if (!detailLine.isNullOrBlank()) {
                Text(
                    text = detailLine,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
                    color = if (receipt.tier == ScreenReceiptTier.HELD_FOR_USER) tokens.ink else tokens.ink3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ReceiptMetricChip(
    text: String,
    color: Color,
    background: Color,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScreenToolReceiptDetailSheetContent(
    toolName: String,
    arguments: JsonElement,
    content: JsonElement,
    output: List<UIMessagePart>,
    receipt: ScreenToolReceiptModel,
) {
    val tokens = LocalAmberTokens.current
    val density = LocalDensity.current
    val isLargeFont = density.fontScale >= 1.3f
    var showRawJson by remember { mutableStateOf(false) }

    val tierColor = when (receipt.tier) {
        ScreenReceiptTier.VERIFIED_COMPLETED -> Color(0xFF2EA043)
        ScreenReceiptTier.HELD_FOR_USER -> Color(0xFFD9822B)
        ScreenReceiptTier.PARTIAL_OR_HANDBACK -> Color(0xFFD9822B)
        ScreenReceiptTier.OUTCOME_UNKNOWN -> tokens.ink3
        ScreenReceiptTier.FAILED_OR_BLOCKED -> Color(0xFFD94848)
    }
    val tierTitle = when (receipt.tier) {
        ScreenReceiptTier.VERIFIED_COMPLETED -> stringResource(R.string.screen_receipt_tier_title_verified)
        ScreenReceiptTier.HELD_FOR_USER -> stringResource(R.string.screen_receipt_tier_title_held)
        ScreenReceiptTier.PARTIAL_OR_HANDBACK -> stringResource(R.string.screen_receipt_tier_title_handback)
        ScreenReceiptTier.OUTCOME_UNKNOWN -> stringResource(R.string.screen_receipt_tier_title_unknown)
        ScreenReceiptTier.FAILED_OR_BLOCKED -> stringResource(R.string.screen_receipt_tier_title_failed)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f),
    ) {
        val isNarrow = maxWidth < 320.dp || isLargeFont
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. Header Banner
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = tierColor.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, tierColor.copy(alpha = 0.35f)),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(tierColor),
                        )
                        Text(
                            text = tierTitle,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = tokens.ink,
                            maxLines = if (isNarrow) 3 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!receipt.goal.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.screen_receipt_goal_prefix, receipt.goal),
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.ink2,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (!receipt.packageName.isNullOrBlank()) {
                            ReceiptMetricChip(
                                text = stringResource(R.string.screen_receipt_app_chip, receipt.packageName),
                                color = tokens.ink3,
                                background = tokens.surface,
                            )
                        }
                        if (receipt.takeoverCount > 0) {
                            ReceiptMetricChip(
                                text = stringResource(R.string.screen_receipt_takeover_chip, receipt.takeoverCount),
                                color = tierColor,
                                background = tokens.surface,
                            )
                        }
                        if (receipt.hostReturnSummary != null) {
                            ReceiptMetricChip(
                                text = if (receipt.hostReturnSummary.returnedToHost) {
                                    stringResource(R.string.screen_receipt_host_returned_detail)
                                } else {
                                    stringResource(R.string.screen_receipt_host_kept_foreground)
                                },
                                color = tokens.ink3,
                                background = tokens.surface,
                            )
                        }
                    }
                }
            }

            // 2. Held / Blocked Sensitive Final Actions Section
            if (receipt.heldSensitiveActions.isNotEmpty()) {
                ReceiptSectionCard(
                    title = stringResource(R.string.screen_receipt_section_held_actions),
                    accentColor = Color(0xFFD9822B),
                ) {
                    receipt.heldSensitiveActions.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFD9822B).copy(alpha = 0.08f))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Lucide.ShieldAlert,
                                contentDescription = null,
                                tint = Color(0xFFD9822B),
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(15.dp),
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = item.label,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = tokens.ink,
                                )
                                Text(
                                    text = if (item.isSelectedChoice) {
                                        stringResource(R.string.screen_receipt_held_selected_desc)
                                    } else {
                                        stringResource(R.string.screen_receipt_held_blocked_desc)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tokens.ink3,
                                )
                            }
                        }
                    }
                }
            }

            // 3. Unmet Acceptance Criteria & Recommended Next Step Section
            if (receipt.unmetReasons.isNotEmpty() || !receipt.recommendedNextStep.isNullOrBlank()) {
                ReceiptSectionCard(
                    title = stringResource(R.string.screen_receipt_section_unmet_criteria),
                    accentColor = tokens.ink2,
                ) {
                    receipt.unmetReasons.forEach { reason ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodySmall,
                                color = tierColor,
                            )
                            Text(
                                text = reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.ink2,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (!receipt.recommendedNextStep.isNullOrBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = tokens.surface2,
                        ) {
                            Text(
                                text = stringResource(R.string.screen_receipt_recommended_next_step, receipt.recommendedNextStep),
                                style = MaterialTheme.typography.labelSmall,
                                color = tokens.ink3,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }

            // 4. Confirmed Executed Steps Section
            if (receipt.executedSteps.isNotEmpty()) {
                ReceiptSectionCard(
                    title = "${stringResource(R.string.screen_receipt_section_executed_steps)} (${receipt.executedSteps.size})",
                    accentColor = Color(0xFF2EA043),
                ) {
                    receipt.executedSteps.forEach { step ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(tokens.surface2)
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .sizeIn(minWidth = 20.dp, minHeight = 20.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(tokens.surface)
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = step.stepNumber.toString(),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = tokens.ink3,
                                    maxLines = 1,
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = step.summaryLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tokens.ink,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!step.targetRef.isNullOrBlank()) {
                                    Text(
                                        text = "${step.actionKind} · ${step.targetRef}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = tokens.ink4,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            ReceiptMetricChip(
                                text = step.status,
                                color = if (step.dispatched) Color(0xFF2EA043) else tokens.ink3,
                                background = tokens.surface,
                            )
                        }
                    }
                }
            }

            // 5. On-Device OCR Recognition Summary Section
            if (receipt.ocrSummary != null && (receipt.ocrSummary.triggered || receipt.ocrSummary.coverageRatio != null)) {
                ReceiptSectionCard(
                    title = stringResource(R.string.screen_receipt_section_ocr),
                    accentColor = tokens.accent,
                ) {
                    val ocr = receipt.ocrSummary
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ReceiptMetricChip(
                            text = if (ocr.triggered) {
                                stringResource(R.string.screen_receipt_ocr_triggered_chip, ocr.blockCount)
                            } else {
                                stringResource(R.string.screen_receipt_ocr_not_needed_chip)
                            },
                            color = tokens.ink2,
                            background = tokens.surface2,
                        )
                        if (ocr.coverageRatio != null) {
                            val pct = (ocr.coverageRatio * 100).toInt().coerceIn(0, 100)
                            ReceiptMetricChip(
                                text = stringResource(R.string.screen_receipt_ocr_coverage_chip, pct),
                                color = tokens.ink3,
                                background = tokens.surface2,
                            )
                        }
                        if (!ocr.reason.isNullOrBlank()) {
                            ReceiptMetricChip(
                                text = stringResource(R.string.screen_receipt_ocr_reason_chip, ocr.reason),
                                color = tokens.ink3,
                                background = tokens.surface2,
                            )
                        }
                    }
                    if (ocr.sampleTexts.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.screen_receipt_ocr_sample_summary, ocr.sampleTexts.joinToString(" · ")),
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.ink3,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Attached screenshots if any
            val images = output.filterIsInstance<UIMessagePart.Image>()
            if (images.isNotEmpty()) {
                images.forEach { part ->
                    ZoomableAsyncImage(
                        model = part.url,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp)),
                    )
                }
            }

            // 6. Collapsible Raw JSON Inspector for power users / debugging
            TextButton(
                onClick = { showRawJson = !showRawJson },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(
                    text = if (showRawJson) {
                        stringResource(R.string.screen_receipt_raw_json_hide)
                    } else {
                        stringResource(R.string.screen_receipt_raw_json_show, toolName)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (showRawJson) {
                HighlightCodeBlock(
                    code = JsonInstantPretty.encodeToString(redactScreenJsonElement(maskSensitiveJson(arguments))),
                    language = "json",
                    style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp),
                    forceAutoWrap = true,
                )
                HighlightCodeBlock(
                    code = runCatching {
                        JsonInstantPretty.encodeToString(redactScreenJsonElement(maskSensitiveJson(content)))
                    }.getOrElse { SensitiveScreenTextClassifier.redactIfSensitive(content.toString()) },
                    language = "json",
                    style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp),
                    forceAutoWrap = true,
                )
            }
        }
    }
}

private fun redactScreenJsonElement(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> kotlinx.serialization.json.buildJsonObject {
        element.forEach { (key, value) ->
            put(key, redactScreenJsonElement(value))
        }
    }
    is JsonArray -> JsonArray(element.map(::redactScreenJsonElement))
    is kotlinx.serialization.json.JsonPrimitive -> if (element.isString) {
        kotlinx.serialization.json.JsonPrimitive(SensitiveScreenTextClassifier.redactIfSensitive(element.content))
    } else {
        element
    }
}

@Composable
private fun ReceiptSectionCard(
    title: String,
    accentColor: Color,
    content: @Composable () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = tokens.surface,
        border = BorderStroke(1.dp, tokens.line),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accentColor),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = tokens.ink2,
                    // Localized section titles (e.g. the English held-actions label) exceed one line
                    // on a narrow screen or at large font scale; wrap instead of truncating.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            content()
        }
    }
}
