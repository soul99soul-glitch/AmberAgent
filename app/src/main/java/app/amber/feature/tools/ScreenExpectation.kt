package app.amber.feature.tools

import kotlinx.coroutines.delay
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import app.amber.ai.ui.UIMessagePart
import app.amber.core.automation.AccessibilityController
import app.amber.core.automation.ScreenOcrEngine
import app.amber.core.automation.ScreenOcrMode
import app.amber.core.automation.ScreenOcrObservation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun screenActionResult(success: Boolean) = textJson {
    put("success", success)
}

/**
 * 动作后验证：手势注入 ≠ 目标生效。
 *
 * 两种入口共用一次「动作后重读」：
 * - `verify_text`（兼容既有调用）：文本出现即算看到；
 * - `expectation`：visible / enabled / absent / value_equals 四类条件，
 *   最多 4 次观察、继续观察预算 2 秒。**等待不会重放动作**——动作只发送一次，
 *   这里只读界面，条件不满足时如实报告 unmet，而不是再点一次。
 *
 * @param targetWasVisibleBefore 动作前目标是否可见；仅 `absent` 用它排除
 *   "目标从未出现过"的假通过。
 */
internal suspend fun verifiedScreenActionResult(
    service: AccessibilityController,
    input: JsonElement,
    success: Boolean,
    targetWasVisibleBefore: Boolean = false,
    ocrEngine: ScreenOcrEngine? = null,
): List<UIMessagePart> {
    val verifyText = input.string("verify_text")
    val expectation = (input.jsonObject["expectation"] as? kotlinx.serialization.json.JsonObject)
    if (!success || (verifyText == null && expectation == null)) return screenActionResult(success)

    if (expectation != null) {
        val probe = AccessibilityExpectationProbe(service)
        val verdict = awaitScreenExpectation(probe, expectation, targetWasVisibleBefore)
        return textJson {
            put("success", success)
            put("verification", verdict.toJson())
        }
    }

    val ocrMode = input.resolveOcrMode()
    val deadline = System.currentTimeMillis() +
        (input.long("verify_timeout_ms") ?: 2_000L).coerceIn(200L, 15_000L)
    var found = false
    var ocrObservation: ScreenOcrObservation? = null
    while (System.currentTimeMillis() < deadline) {
        if (service.findTextNodes(verifyText!!, maxNodes = 200).isNotEmpty()) {
            found = true
            break
        }
        if (ocrMode != ScreenOcrMode.OFF && ocrEngine != null) {
            val snapshot = runCatching { service.captureScreenSnapshot() }.getOrNull()
            ocrObservation = ocrEngine.observe(
                snapshot = snapshot,
                mode = ocrMode,
                forceReason = if (ocrMode == ScreenOcrMode.AUTO) "verify_text_tree_miss" else null,
            )
            if (ocrObservation.findMatches(verifyText).isNotEmpty()) {
                found = true
                break
            }
        }
        delay(250)
    }
    return textJson {
        put("success", success)
        put("verify_text_found", found)
        put("ocr_mode", ocrMode.wireName())
        put("ocr_triggered", ocrObservation?.triggered == true)
        ocrObservation?.let { obs ->
            put("ocr_reason", obs.triggerReason)
            put("ocr_block_count", obs.blocks.size)
            put("ocr_source", obs.source.wireName())
            put("ocr_outcome", obs.outcome.wireName())
            obs.diagnosticSummary?.let { put("ocr_diagnostics", it) }
        }
    }
}

/** 最多 4 次观察、总预算 2 秒；条件一旦满足（或不唯一）立即返回，不做额外轮询。 */
internal suspend fun awaitScreenExpectation(
    probe: ScreenExpectationProbe,
    expectation: kotlinx.serialization.json.JsonObject,
    targetWasVisibleBefore: Boolean,
): ExpectationVerdict {
    val deadline = System.currentTimeMillis() + EXPECTATION_BUDGET_MS
    val startedAt = System.currentTimeMillis()
    var verdict = ExpectationVerdict(kind = expectation.string("kind").orEmpty(), met = false, detail = "not_observed")
    var observations = 0
    while (observations < EXPECTATION_MAX_OBSERVATIONS) {
        observations++
        verdict = evaluateExpectation(probe, expectation, targetWasVisibleBefore)
        if (verdict.met || verdict.ambiguous) break
        if (observations == EXPECTATION_MAX_OBSERVATIONS) break
        if (System.currentTimeMillis() + EXPECTATION_INTERVAL_MS >= deadline) break
        delay(EXPECTATION_INTERVAL_MS)
    }
    return verdict.copy(
        observations = observations,
        elapsedMs = System.currentTimeMillis() - startedAt,
    )
}

internal suspend fun evaluateExpectation(
    probe: ScreenExpectationProbe,
    expectation: kotlinx.serialization.json.JsonObject,
    targetWasVisibleBefore: Boolean,
): ExpectationVerdict = withContext(Dispatchers.Main.immediate) {
    evaluateScreenExpectation(
        probe = probe,
        kind = expectation.string("kind").orEmpty(),
        target = expectation.string("target").orEmpty(),
        anchor = expectation.string("anchor").orEmpty(),
        value = expectation.string("value").orEmpty(),
        targetWasVisibleBefore = targetWasVisibleBefore,
    )
}

internal fun ExpectationVerdict.toJson(): kotlinx.serialization.json.JsonObject = buildJsonObject {
    put("kind", kind)
    put("met", met)
    put("detail", detail)
    if (ambiguous) put("ambiguous", true)
    put("observations", observations)
    put("elapsed_ms", elapsedMs)
}

/** 动作前目标是否可见；只有 absent 需要，其它 kind 不额外读树。 */
internal suspend fun wasScreenAbsenceTargetVisible(service: AccessibilityController, input: JsonElement): Boolean {
    val target = (input.jsonObject["expectation"] as? kotlinx.serialization.json.JsonObject)
        ?.takeIf { it.string("kind") == "absent" }
        ?.string("target")
        ?.takeIf { it.isNotBlank() }
        ?: return false
    return withContext(Dispatchers.Main.immediate) {
        AccessibilityExpectationProbe(service).matchingTexts(target).isNotEmpty()
    }
}

/** expectation 的形状；四类条件都在动作后的新界面上判定。 */
internal fun screenExpectationProp() = buildJsonObject {
    put("type", "object")
    put("description", "Optional post-action acceptance check, evaluated on the re-read UI. " +
        "The action is sent once; a failed check reports unmet and never replays it.")
    put("properties", buildJsonObject {
        put("kind", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray {
                add("visible"); add("enabled"); add("absent"); add("value_equals")
            })
            put("description", "visible = target text is present; enabled = the single visible " +
                "target is enabled; absent = target gone while anchor still visible (target must " +
                "have been visible before the action); value_equals = exactly one non-password " +
                "editable field now holds value.")
        })
        put("target", screenStringProp("Exact text or accessibility label to check. Required for " +
            "visible/enabled/absent; ignored by value_equals."))
        put("anchor", screenStringProp("Required for absent: text that must still be visible."))
        put("value", screenStringProp("Required for value_equals: full expected text of the field."))
    })
    put("required", buildJsonArray { add("kind") })
}

/** 动作后验收的判定结果；ambiguous 表示界面无法唯一确定目标，需交回而不是重试。 */
internal data class ExpectationVerdict(
    val kind: String,
    val met: Boolean,
    val detail: String,
    val ambiguous: Boolean = false,
    val observations: Int = 0,
    val elapsedMs: Long = 0,
)

/**
 * 验收所需的最小界面读取面。抽成接口是为了让四类条件能在 JVM 单测里
 * 直接验证，而不必启动 Android 框架或真机无障碍服务。
 */
internal interface ScreenExpectationProbe {
    fun matchingTexts(query: String): List<ProbeTextMatch>
    fun editableValues(): List<String>
}

internal data class ProbeTextMatch(val enabled: Boolean)

/** 生产实现：读 live Accessibility 树，不改任何状态。 */
internal class AccessibilityExpectationProbe(
    private val service: AccessibilityController,
) : ScreenExpectationProbe {
    override fun matchingTexts(query: String): List<ProbeTextMatch> =
        service.captureScreenSnapshot()?.nodes.orEmpty()
            .filter { it.hasOwnLabel && it.label == query }
            .map { ProbeTextMatch(enabled = it.enabled) }

    override fun editableValues(): List<String> =
        service.captureScreenSnapshot()?.nodes
            ?.mapNotNull { it.editableValue }
            .orEmpty()
}

/** 密码节点的展示遮罩；其 editableValue 为空，不进入等值验收。 */
internal const val MASKED_EDITABLE_LABEL = "[password]"

/**
 * 四类验收条件的纯判定。只读 probe，绝不重放动作。
 *
 * - visible：目标文本存在；
 * - enabled：目标唯一可见且 enabled；
 * - absent：目标此前可见、现在消失，且 anchor 仍可见；
 * - value_equals：恰好一个非密码可编辑框的文本等于 value。
 */
internal fun evaluateScreenExpectation(
    probe: ScreenExpectationProbe,
    kind: String,
    target: String,
    anchor: String,
    value: String,
    targetWasVisibleBefore: Boolean,
): ExpectationVerdict = when (kind) {
    "visible" -> {
        if (target.isBlank()) ExpectationVerdict(kind, false, "missing_target")
        else {
            val found = probe.matchingTexts(target).isNotEmpty()
            ExpectationVerdict(kind, found, if (found) "visible" else "not_visible")
        }
    }
    "enabled" -> {
        val matches = if (target.isBlank()) emptyList() else probe.matchingTexts(target)
        when {
            target.isBlank() -> ExpectationVerdict(kind, false, "missing_target")
            matches.isEmpty() -> ExpectationVerdict(kind, false, "not_visible")
            // 多命中时无法唯一确定目标，交回而不是猜。
            matches.size > 1 -> ExpectationVerdict(kind, false, "ambiguous_target", ambiguous = true)
            else -> ExpectationVerdict(kind, matches.single().enabled, if (matches.single().enabled) "enabled" else "disabled")
        }
    }
    "absent" -> when {
        target.isBlank() || anchor.isBlank() -> ExpectationVerdict(kind, false, "missing_anchor")
        // 目标从未出现过时"消失"没有意义，不能给出假通过。
        !targetWasVisibleBefore -> ExpectationVerdict(kind, false, "target_not_visible_before_action")
        else -> {
            val targetGone = probe.matchingTexts(target).isEmpty()
            val anchorVisible = probe.matchingTexts(anchor).isNotEmpty()
            ExpectationVerdict(
                kind,
                met = targetGone && anchorVisible,
                detail = when {
                    !targetGone -> "target_still_visible"
                    !anchorVisible -> "anchor_not_visible"
                    else -> "absent"
                },
            )
        }
    }
    "value_equals" -> when {
        value.isBlank() -> ExpectationVerdict(kind, false, "missing_value")
        else -> {
            val matching = probe.editableValues()
                .filter { it == value }
            when {
                matching.size == 1 -> ExpectationVerdict(kind, true, "value_equals")
                matching.size > 1 -> ExpectationVerdict(kind, false, "ambiguous_target", ambiguous = true)
                else -> ExpectationVerdict(kind, false, "value_mismatch")
            }
        }
    }
    else -> ExpectationVerdict(kind, false, "unsupported_kind")
}

/** 一次验收最多 4 次观察，继续观察预算 2 秒（与 iOS 的 phone_act 一致）。 */
private const val EXPECTATION_MAX_OBSERVATIONS = 4
private const val EXPECTATION_INTERVAL_MS = 250L
private const val EXPECTATION_BUDGET_MS = 2_000L
