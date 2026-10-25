package app.amber.core.jev

import app.amber.core.automation.ScreenActionKind
import app.amber.core.automation.ScreenNode
import app.amber.core.automation.ScreenSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Locale

/**
 * 有界 per-App 成功路径：候选建议，不是可重放脚本。
 *
 * 只保留能在全新 snapshot 上重新解析的语义（动作类型、目标文案、稳定 resource id）。
 * 敏感过滤是保守落盘闸门，不是完整密钥检测：文案 secret 不能靠单个关键词承诺全部安全。
 */
data class PerAppSuccessPathLimits(
    val maxPackages: Int = 24,
    val maxPathsPerPackage: Int = 6,
    val maxStepsPerPath: Int = 10,
    val maxTextChars: Int = 80,
    val maxFileBytes: Int = 64 * 1024,
) {
    companion object {
        val DEFAULT = PerAppSuccessPathLimits()
    }
}

@Serializable
data class PerAppSuccessPathStep(
    val kind: String,
    val label: String = "",
    @SerialName("semantic_id") val semanticId: String = "",
)

@Serializable
data class PerAppSuccessPath(
    @SerialName("package_name") val packageName: String,
    @SerialName("goal_fingerprint") val goalFingerprint: String = "",
    @SerialName("goal_hint") val goalHint: String = "",
    val steps: List<PerAppSuccessPathStep> = emptyList(),
    @SerialName("recorded_at_ms") val recordedAtMs: Long = 0L,
)

data class PerAppResolvedHint(
    val kind: String,
    val label: String,
    val semanticId: String,
    val freshRef: String,
)

data class PerAppCandidateGuide<T>(
    val candidates: List<T>,
    val hint: String? = null,
)

/** Live candidate identity for memory ranking: at least kind+ref, plus current typed text. */
data class PerAppCandidateIdentity(
    val kind: ScreenActionKind,
    val ref: String,
    val text: String? = null,
)

object PerAppSuccessPathSanitizer {
    private val SENSITIVE = Regex(
        "发送|发布|支付|付款|购买|下单|删除|清空|授权|允许|登录|登出|退出登录|密码|验证码|" +
            "点赞|投币|收藏|关注|订阅|充值|转账|提交|确认|用户名|账号|邮箱|剪贴板|粘贴|" +
            "otp|cvv|cvc|iban|clipboard|paste|" +
            "\\b(send|publish|post|submit|confirm|pay|buy|purchase|delete|authorize|allow|" +
            "login|password|passwd|username|email|account|subscribe|follow|like|donate|" +
            "token|secret|pin|otp)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val OTP_OR_PIN = Regex("^\\s*\\d{4,8}\\s*$")
    private val LONG_DIGITS = Regex("\\d{8,}")
    private val CARDISH_RUN = Regex("\\d[\\d\\s-]{6,}\\d")
    private val COORD_IN_ID = Regex("\\d{2,4}[_.,-]\\d{2,4}[_.,-]\\d{2,4}")
    private val RESOURCE_ID = Regex(
        "^(?:[A-Za-z][A-Za-z0-9_]*\\.)*[A-Za-z][A-Za-z0-9_]*:id/[A-Za-z_][A-Za-z0-9_]*$" +
            "|^id/[A-Za-z_][A-Za-z0-9_]*$",
    )
    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun looksSensitive(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.contains("[password]", ignoreCase = true)) return true
        if (SENSITIVE.containsMatchIn(text)) return true
        if (OTP_OR_PIN.matches(text.trim())) return true
        if (LONG_DIGITS.containsMatchIn(text)) return true
        if (hasSeparatedPaymentDigits(text)) return true
        return false
    }

    fun validPackageName(packageName: String): Boolean =
        PACKAGE_NAME.matches(packageName.trim())

    fun semanticId(viewId: String, nodeRef: String, maxTextChars: Int = PerAppSuccessPathLimits.DEFAULT.maxTextChars): String {
        val id = viewId.trim()
        if (id.isEmpty() || id == nodeRef) return ""
        if (COORD_IN_ID.containsMatchIn(id)) return ""
        if (!RESOURCE_ID.matches(id)) return ""
        if (looksSensitive(id)) return ""
        return id.take(maxTextChars)
    }

    fun clip(text: String, maxTextChars: Int): String = text.trim().replace("\\s+".toRegex(), " ").take(maxTextChars)

    fun stepFrom(
        kind: ScreenActionKind,
        node: ScreenNode,
        typedValue: String? = null,
        limits: PerAppSuccessPathLimits = PerAppSuccessPathLimits.DEFAULT,
    ): PerAppSuccessPathStep? {
        if (looksSensitive(typedValue.orEmpty())) return null
        if (looksSensitive(node.label) || looksSensitive(node.viewId) || looksSensitive(node.editableValue.orEmpty())) {
            return null
        }
        val semanticId = semanticId(node.viewId, node.ref, limits.maxTextChars)
        var label = clip(node.label, limits.maxTextChars)
        if (mirrorsTypedInput(kind, node, typedValue, label)) {
            if (semanticId.isEmpty()) return null
            label = ""
        }
        if (label.isBlank() && semanticId.isEmpty()) return null
        return PerAppSuccessPathStep(
            kind = kind.name.lowercase(Locale.ROOT),
            label = label,
            semanticId = semanticId,
        )
    }

    fun buildPath(
        packageName: String,
        goal: String,
        steps: List<PerAppSuccessPathStep>,
        recordedAtMs: Long,
        limits: PerAppSuccessPathLimits = PerAppSuccessPathLimits.DEFAULT,
        inputSecrets: Collection<String> = emptyList(),
        taskSensitive: Boolean = false,
    ): PerAppSuccessPath? {
        if (!validPackageName(packageName)) return null
        val cleaned = steps
            .mapNotNull { step -> sanitizeStep(step, limits) }
            .take(limits.maxStepsPerPath)
        if (cleaned.isEmpty()) return null
        val sensitiveGoal = goalIsSensitive(goal, inputSecrets, taskSensitive)
        return PerAppSuccessPath(
            packageName = packageName.trim(),
            goalFingerprint = if (sensitiveGoal) "" else fingerprint(packageName, goal),
            goalHint = if (sensitiveGoal) "" else clip(goal, limits.maxTextChars),
            steps = cleaned,
            recordedAtMs = recordedAtMs,
        )
    }

    fun sanitizeStep(
        step: PerAppSuccessPathStep,
        limits: PerAppSuccessPathLimits = PerAppSuccessPathLimits.DEFAULT,
    ): PerAppSuccessPathStep? {
        val kind = parseKind(step.kind) ?: return null
        if (looksSensitive(step.label) || looksSensitive(step.semanticId)) return null
        val label = clip(step.label, limits.maxTextChars)
        val semanticId = clip(step.semanticId, limits.maxTextChars)
            .takeIf { id ->
                id.isNotEmpty() &&
                    !COORD_IN_ID.containsMatchIn(id) &&
                    RESOURCE_ID.matches(id) &&
                    !looksSensitive(id)
            }
            .orEmpty()
        if (label.isBlank() && semanticId.isEmpty()) return null
        return PerAppSuccessPathStep(
            kind = kind.name.lowercase(Locale.ROOT),
            label = label,
            semanticId = semanticId,
        )
    }

    fun fingerprint(packageName: String, goal: String): String {
        val normalized = goal.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty() || looksSensitive(normalized)) return ""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${packageName.trim()}\n$normalized".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }.take(32)
    }

    fun parseKind(raw: String): ScreenActionKind? = when (raw.trim().lowercase(Locale.ROOT)) {
        "click" -> ScreenActionKind.CLICK
        "type" -> ScreenActionKind.TYPE
        "scroll_forward" -> ScreenActionKind.SCROLL_FORWARD
        "scroll_backward" -> ScreenActionKind.SCROLL_BACKWARD
        else -> null
    }

    fun goalIsSensitive(
        goal: String,
        inputSecrets: Collection<String> = emptyList(),
        taskSensitive: Boolean = false,
    ): Boolean {
        if (taskSensitive) return true
        if (looksSensitive(goal)) return true
        return inputSecrets.any { secret ->
            secret.isNotBlank() && (looksSensitive(secret) || goal.contains(secret))
        }
    }

    private fun hasSeparatedPaymentDigits(text: String): Boolean {
        for (match in CARDISH_RUN.findAll(text)) {
            val digits = match.value.filter { it.isDigit() }
            if (digits.length >= 8) return true
        }
        return false
    }

    private fun mirrorsTypedInput(
        kind: ScreenActionKind,
        node: ScreenNode,
        typedValue: String?,
        label: String,
    ): Boolean {
        if (kind != ScreenActionKind.TYPE && !node.editable) return false
        val secrets = listOfNotNull(typedValue, node.editableValue).map { it.trim() }.filter { it.isNotEmpty() }
        if (secrets.isEmpty() || label.isBlank()) return false
        return secrets.any { secret -> labelMirrorsSecret(label, secret) }
    }

    private fun labelMirrorsSecret(label: String, secret: String): Boolean {
        val a = label.trim()
        val b = secret.trim()
        if (a.isEmpty() || b.isEmpty()) return false
        return a.equals(b, ignoreCase = true) ||
            a.contains(b, ignoreCase = true) ||
            b.contains(a, ignoreCase = true)
    }
}

object PerAppSuccessPathPlanner {
    fun resolveUniqueHints(
        packageName: String,
        snapshot: ScreenSnapshot,
        remembered: List<PerAppSuccessPath>,
        goal: String,
    ): List<PerAppResolvedHint> {
        if (snapshot.packageName != packageName) return emptyList()
        val path = selectPath(packageName, remembered, goal) ?: return emptyList()
        val hints = mutableListOf<PerAppResolvedHint>()
        for (step in path.steps) {
            val matches = matchingNodes(snapshot, step)
            if (matches.size != 1) continue
            val node = matches.single()
            hints += PerAppResolvedHint(
                kind = step.kind,
                label = step.label,
                semanticId = step.semanticId,
                freshRef = node.ref,
            )
            break
        }
        return hints
    }

    fun <T> guide(
        packageName: String,
        snapshot: ScreenSnapshot,
        remembered: List<PerAppSuccessPath>,
        goal: String,
        candidates: List<T>,
        identityOf: (T) -> PerAppCandidateIdentity,
    ): PerAppCandidateGuide<T> {
        if (candidates.isEmpty() || remembered.isEmpty()) {
            return PerAppCandidateGuide(candidates)
        }
        val hints = resolveUniqueHints(packageName, snapshot, remembered, goal)
        if (hints.isEmpty()) return PerAppCandidateGuide(candidates)
        val hint = hints.first()
        val wantKind = PerAppSuccessPathSanitizer.parseKind(hint.kind) ?: return PerAppCandidateGuide(candidates)
        val matches = candidates.withIndex().filter { (_, candidate) ->
            val identity = identityOf(candidate)
            identity.kind == wantKind && identity.ref == hint.freshRef
        }
        if (matches.size != 1) return PerAppCandidateGuide(candidates)
        val matchIndex = matches.single().index
        val reordered = ArrayList<T>(candidates.size)
        reordered.add(candidates[matchIndex])
        candidates.forEachIndexed { index, candidate ->
            if (index != matchIndex) reordered.add(candidate)
        }
        return PerAppCandidateGuide(
            candidates = reordered,
            hint = "Prior accepted success uniquely matched ${hint.kind} '${hint.label}'. " +
                "History only ranks current-tree candidates; it never clicks.",
        )
    }

    private fun selectPath(
        packageName: String,
        remembered: List<PerAppSuccessPath>,
        goal: String,
    ): PerAppSuccessPath? {
        val scoped = remembered.filter { it.packageName == packageName && it.steps.isNotEmpty() }
        if (scoped.isEmpty()) return null
        val fingerprint = PerAppSuccessPathSanitizer.fingerprint(packageName, goal)
        if (fingerprint.isNotEmpty()) {
            scoped.firstOrNull { it.goalFingerprint == fingerprint }?.let { return it }
        }
        return scoped.first()
    }

    private fun matchingNodes(snapshot: ScreenSnapshot, step: PerAppSuccessPathStep): List<ScreenNode> {
        val kind = PerAppSuccessPathSanitizer.parseKind(step.kind) ?: return emptyList()
        val wantLabel = normalize(step.label)
        val wantId = step.semanticId.trim()
        if (wantLabel.isEmpty() && wantId.isEmpty()) return emptyList()
        return snapshot.nodes.filter { node ->
            if (!node.enabled) return@filter false
            if (!kindMatches(kind, node)) return@filter false
            val labelOk = wantLabel.isNotEmpty() && normalize(node.label) == wantLabel
            val idOk = wantId.isNotEmpty() && node.viewId.trim() == wantId
            when {
                wantId.isNotEmpty() && wantLabel.isNotEmpty() -> idOk && labelOk
                wantId.isNotEmpty() -> idOk
                else -> labelOk
            }
        }
    }

    private fun kindMatches(kind: ScreenActionKind, node: ScreenNode): Boolean = when (kind) {
        ScreenActionKind.CLICK -> node.clickable
        ScreenActionKind.TYPE -> node.editable
        ScreenActionKind.SCROLL_FORWARD, ScreenActionKind.SCROLL_BACKWARD -> node.scrollable
    }

    private fun normalize(text: String): String = text.trim().replace("\\s+".toRegex(), " ").lowercase(Locale.ROOT)
}
