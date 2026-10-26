package app.amber.feature.live

import java.security.MessageDigest
import java.util.Locale

object LiveUiTreeProcessor {
    private val sensitiveKeys = listOf(
        "password",
        "passwd",
        "pwd",
        "token",
        "secret",
        "验证码",
        "密碼",
        "密码",
        "口令",
        "短信码",
    )
    private val chromeKeywords = listOf(
        "ai 伴随",
        "amberagent",
        "按住说话",
        "松开结束",
        "发到聊天",
        "再看一次",
        "再看",
        "暂停",
        "继续",
        "文字",
        "总结一下",
        "找重点",
        "下一步",
        "有什么风险",
        "帮我写回复",
        "自动分析",
        "分屏分割线",
        "分割线",
        "canvas window",
        "statusbar",
        "navigationbar",
        "导航栏",
        "状态栏",
        "输入法",
        "搜索聊天",
        "消息",
        "文件",
        "云文档",
        "pin",
    )
    private val fillerPhrases = listOf(
        "可以尝试查看",
        "是否需要",
        "你可以问我",
        "需要查看更多",
        "建议继续观察",
        "查看分割线",
        "拖动分割线",
        "分屏分割线",
        "canvas window",
        "当前界面元素较少",
        "处于分屏待操作状态",
    )

    fun sanitizeText(text: String): String {
        val trimmed = text.replace(Regex("\\s+"), " ").trim()
        if (trimmed.isBlank()) return ""
        val lower = trimmed.lowercase(Locale.ROOT)
        if (sensitiveKeys.any { lower.contains(it.lowercase(Locale.ROOT)) }) {
            return "[已隐藏敏感内容]"
        }
        return trimmed
            .replace(Regex("\\b\\d{6}\\b"), "[验证码]")
            .replace(Regex("\\b[\\w.%+-]+@[\\w.-]+\\.[A-Za-z]{2,}\\b"), "[邮箱]")
            .replace(Regex("\\b1[3-9]\\d{9}\\b"), "[手机号]")
            .take(240)
    }

    fun sanitizeUiTree(uiTree: String): String =
        uiTree
            .lineSequence()
            .map { line ->
                line
                    .replace(Regex("text=([^|\\n]+)")) { match -> "text=${sanitizeText(match.groupValues[1])}" }
                    .replace(Regex("desc=([^|\\n]+)")) { match -> "desc=${sanitizeText(match.groupValues[1])}" }
            }
            .filter { it.isNotBlank() && !looksLikeChromeLine(it) }
            .joinToString("\n")
            .take(20_000)

    fun compressContentText(visibleText: String, uiTree: String): String {
        val candidates = buildList {
            addAll(visibleText.lineSequence())
            if (visibleText.isBlank()) {
                addAll(
                    uiTree.lineSequence().flatMap { line ->
                        Regex("(?:text|desc)=([^|\\n]+)")
                            .findAll(line)
                            .map { it.groupValues[1] }
                    }.toList()
                )
            }
        }
        return candidates
            .map(::sanitizeText)
            .map { it.trim(' ', '·', '-', '•', '|') }
            .filter { it.isUsefulContentText() }
            .distinct()
            .take(80)
            .joinToString("\n")
            .take(6_000)
    }

    fun compactAnalysisItems(items: List<String>, maxItems: Int): List<String> =
        items
            .mapNotNull(::cleanAnalysisItem)
            .distinct()
            .take(maxItems)

    fun cleanAnalysisItem(text: String): String? {
        val cleaned = text
            .trim()
            .trimStart('-', '•', '*', ' ', '\t')
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('。')
        if (cleaned.isBlank()) return null
        val lower = cleaned.lowercase(Locale.ROOT)
        if (fillerPhrases.any { lower.contains(it.lowercase(Locale.ROOT)) }) return null
        if (looksLikeChromeText(cleaned)) return null
        return cleaned.take(90)
    }

    fun stableHash(packageName: String, title: String, uiTree: String, contentText: String = ""): String {
        val normalized = buildString {
            append(packageName)
            append('\n')
            append(title.trim())
            append('\n')
            append(contentText.normalizeForHash())
            append('\n')
            append(sanitizeUiTree(uiTree).normalizeForHash())
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    @Deprecated("由 LiveEngine 取代，仅留作参考")
    fun shouldAnalyze(
        previousHash: String?,
        nextHash: String?,
        nowMillis: Long,
        changedAtMillis: Long,
        lastAnalysisAtMillis: Long,
        stableDelayMs: Long,
        minAnalysisIntervalMs: Long,
        force: Boolean = false,
    ): Boolean {
        if (nextHash.isNullOrBlank()) return false
        if (force) return true
        if (previousHash == nextHash) return false
        if (nowMillis - changedAtMillis < stableDelayMs) return false
        if (nowMillis - lastAnalysisAtMillis < minAnalysisIntervalMs) return false
        return true
    }

    private fun String.normalizeForHash(): String =
        lowercase(Locale.ROOT)
            .replace(Regex("\\d{1,2}:\\d{2}"), "[time]")
            .replace(Regex("\\b\\d+(\\.\\d+)?\\b"), "[num]")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun String.isUsefulContentText(): Boolean {
        if (isBlank()) return false
        if (this == "[已隐藏敏感内容]") return false
        if (length <= 1) return false
        if (looksLikeChromeText(this)) return false
        return true
    }

    private fun looksLikeChromeLine(line: String): Boolean =
        looksLikeChromeText(line.substringAfter("text=", line).substringAfter("desc=", line))

    private fun looksLikeChromeText(text: String): Boolean {
        val normalized = text.lowercase(Locale.ROOT).replace("\\s+".toRegex(), "")
        if (normalized.isBlank()) return true
        if (normalized.matches(Regex("[0-9:/.,%\\-+\\s]+"))) return true
        return chromeKeywords.any { keyword ->
            normalized.contains(keyword.lowercase(Locale.ROOT).replace("\\s+".toRegex(), ""))
        }
    }
}
