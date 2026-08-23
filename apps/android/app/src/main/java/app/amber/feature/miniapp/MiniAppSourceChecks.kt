package app.amber.feature.miniapp

/**
 * P3-05: basic HTML/JS/CSS structure checks for the source editor, layered on
 * top of the existing [MiniAppHtmlValidator] security rules. Deliberately
 * minimal — no new parsing library: tag pairing for the HTML, brace/paren
 * balance for <script>/<style> blocks. "明显语法错误" only.
 */
object MiniAppSourceChecks {
    data class Issue(val message: String)

    /** Run the existing security validator plus the structural checks. */
    fun issues(html: String): List<Issue> {
        val issues = mutableListOf<Issue>()
        runCatching { MiniAppHtmlValidator.validate(html) }
            .onFailure { error -> issues.add(Issue(error.message ?: "HTML 校验失败")) }
        tagPairingIssue(html)?.let { issues.add(it) }
        scriptBraceIssue(html)?.let { issues.add(it) }
        styleBraceIssue(html)?.let { issues.add(it) }
        return issues
    }

    /** True when the editor content may be saved (no blocking issues). */
    fun isSavable(html: String): Boolean = issues(html).isEmpty()

    /** P3-05: whether the editor holds changes not yet saved (未保存 indicator). */
    fun hasUnsavedChanges(savedHtml: String, currentHtml: String): Boolean = savedHtml != currentHtml

    /**
     * First tag-pairing mismatch, if any. Comments, complete <script>/
     * <style> blocks (whose content may legitimately contain "<…>" inside
     * JS/CSS strings) and self-closing/void tags are ignored; the stack only
     * tracks real open/close pairs.
     */
    fun tagPairingIssue(html: String): Issue? {
        val clean = html
            .replace(Regex("(?s)<!--.*?-->"), "")
            .replace(Regex("""(?is)<\s*script\b[^>]*>.*?<\s*/\s*script\s*>"""), "")
            .replace(Regex("""(?is)<\s*style\b[^>]*>.*?<\s*/\s*style\s*>"""), "")
        val stack = ArrayDeque<String>()
        val tagRegex = Regex("""<\s*(/?)\s*([a-zA-Z][a-zA-Z0-9-]*)((?:\s[^<>]*?)?)(/?)>""")
        for (match in tagRegex.findAll(clean)) {
            val closing = match.groupValues[1].isNotEmpty()
            val name = match.groupValues[2].lowercase()
            val selfClosing = match.groupValues[4].isNotEmpty()
            if (closing) {
                val expected = stack.removeLastOrNull()
                if (expected == null) {
                    return Issue("多余的闭合标签 </$name>")
                }
                if (expected != name) {
                    return Issue("标签不配对：</$name> 期望 </$expected>")
                }
            } else if (!selfClosing && name !in VOID_TAGS) {
                stack.addLast(name)
            }
        }
        val unclosed = stack.lastOrNull()
        return unclosed?.let { Issue("未闭合的标签 <$it>") }
    }

    /** Unbalanced braces/parens inside <script> blocks (obvious JS errors). */
    fun scriptBraceIssue(html: String): Issue? {
        for (block in extractBlocks(html, "script")) {
            braceIssue(block)?.let { return Issue("script 中的括号不配对：${it.message}") }
        }
        return null
    }

    /** Unbalanced braces inside <style> blocks (obvious CSS errors). */
    fun styleBraceIssue(html: String): Issue? {
        for (block in extractBlocks(html, "style")) {
            braceIssue(block)?.let { return Issue("style 中的花括号不配对：${it.message}") }
        }
        return null
    }

    private fun extractBlocks(html: String, tag: String): List<String> {
        val regex = Regex("""(?is)<\s*$tag\b[^>]*>(.*?)<\s*/\s*$tag\s*>""")
        return regex.findAll(html).map { it.groupValues[1] }.toList()
    }

    /**
     * Brace/paren balance over [code], skipping quoted strings, line comments
     * and block comments. Reports the first unbalanced closing bracket.
     */
    private fun braceIssue(code: String): Issue? {
        val stack = ArrayDeque<Char>()
        var i = 0
        val pairs = mapOf(')' to '(', ']' to '[', '}' to '{')
        while (i < code.length) {
            val c = code[i]
            when {
                c == '/' && code.getOrNull(i + 1) == '/' -> {
                    while (i < code.length && code[i] != '\n') i++
                }

                c == '/' && code.getOrNull(i + 1) == '*' -> {
                    val end = code.indexOf("*/", i + 2)
                    i = if (end < 0) code.length else end + 2
                }

                c == '"' || c == '\'' || c == '`' -> {
                    var j = i + 1
                    while (j < code.length) {
                        if (code[j] == '\\') j += 2
                        else if (code[j] == c) break
                        else j++
                    }
                    i = j + 1
                }

                c in "([{" -> stack.addLast(c)

                c in ")]}" -> {
                    val expected = pairs.getValue(c)
                    val actual = stack.removeLastOrNull()
                    if (actual != expected) {
                        val kind = when (c) {
                            ')' -> "括号 )"
                            ']' -> "方括号 ]"
                            else -> "花括号 }"
                        }
                        return Issue("$kind 不配对")
                    }
                }

                else -> Unit
            }
            i++
        }
        val unclosed = stack.lastOrNull()
        return unclosed?.let {
            val kind = when (it) {
                '(' -> "括号 ("
                '[' -> "方括号 ["
                else -> "花括号 {"
            }
            Issue("未闭合的$kind")
        }
    }

    private val VOID_TAGS = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr",
    )
}
