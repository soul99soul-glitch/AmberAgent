package app.amber.feature.miniapp

import android.content.Context
import app.amber.agent.R

/**
 * P3-05: basic HTML/JS/CSS structure checks for the source editor, layered on
 * top of the existing [MiniAppHtmlValidator] security rules. Deliberately
 * minimal — no new parsing library: tag pairing for the HTML, brace/paren
 * balance for <script>/<style> blocks. "明显语法错误" only.
 */
object MiniAppSourceChecks {
    data class Issue(val message: String)

    /** Run the existing security validator plus the structural checks. */
    fun issues(html: String, context: Context? = null): List<Issue> {
        val copy = IssueCopy(context)
        val issues = mutableListOf<Issue>()
        runCatching { MiniAppHtmlValidator.validate(html) }
            .onFailure { error -> issues.add(Issue(error.message ?: copy.htmlValidationFailed)) }
        tagPairingIssue(html, copy)?.let { issues.add(it) }
        scriptBraceIssue(html, copy)?.let { issues.add(it) }
        styleBraceIssue(html, copy)?.let { issues.add(it) }
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
    fun tagPairingIssue(html: String, context: Context? = null): Issue? =
        tagPairingIssue(html, IssueCopy(context))

    private fun tagPairingIssue(html: String, copy: IssueCopy): Issue? {
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
                    return Issue(copy.unexpectedClosingTag(name))
                }
                if (expected != name) {
                    return Issue(copy.mismatchedTag(name, expected))
                }
            } else if (!selfClosing && name !in VOID_TAGS) {
                stack.addLast(name)
            }
        }
        val unclosed = stack.lastOrNull()
        return unclosed?.let { Issue(copy.unclosedTag(it)) }
    }

    /** Unbalanced braces/parens inside <script> blocks (obvious JS errors). */
    fun scriptBraceIssue(html: String, context: Context? = null): Issue? =
        scriptBraceIssue(html, IssueCopy(context))

    private fun scriptBraceIssue(html: String, copy: IssueCopy): Issue? {
        for (block in extractBlocks(html, "script")) {
            braceIssue(block, copy)?.let { return Issue(copy.scriptBrackets(it.message)) }
        }
        return null
    }

    /** Unbalanced braces inside <style> blocks (obvious CSS errors). */
    fun styleBraceIssue(html: String, context: Context? = null): Issue? =
        styleBraceIssue(html, IssueCopy(context))

    private fun styleBraceIssue(html: String, copy: IssueCopy): Issue? {
        for (block in extractBlocks(html, "style")) {
            braceIssue(block, copy)?.let { return Issue(copy.styleBraces(it.message)) }
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
    private fun braceIssue(code: String, copy: IssueCopy): Issue? {
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
                            ')' -> copy.parenthesisKind
                            ']' -> copy.squareBracketKind
                            else -> copy.braceKind
                        }
                        return Issue(copy.unbalanced(kind))
                    }
                }

                else -> Unit
            }
            i++
        }
        val unclosed = stack.lastOrNull()
        return unclosed?.let {
            val kind = when (it) {
                '(' -> copy.parenthesisKind
                '[' -> copy.squareBracketKind
                else -> copy.braceKind
            }
            Issue(copy.unclosed(kind))
        }
    }

    private class IssueCopy(private val context: Context?) {
        val htmlValidationFailed: String = context?.getString(R.string.miniapp_source_check_html_failed)
            ?: "HTML validation failed"
        val parenthesisKind: String = context?.getString(R.string.miniapp_source_check_parenthesis)
            ?: "parenthesis ("
        val squareBracketKind: String = context?.getString(R.string.miniapp_source_check_square_bracket)
            ?: "square bracket ["
        val braceKind: String = context?.getString(R.string.miniapp_source_check_brace)
            ?: "brace {"

        fun unexpectedClosingTag(name: String): String = context?.getString(
            R.string.miniapp_source_check_unexpected_closing_tag,
            name,
        ) ?: "Unexpected closing tag </$name>"

        fun mismatchedTag(name: String, expected: String): String = context?.getString(
            R.string.miniapp_source_check_mismatched_tag,
            name,
            expected,
        ) ?: "Mismatched tag: </$name>; expected </$expected>"

        fun unclosedTag(name: String): String = context?.getString(
            R.string.miniapp_source_check_unclosed_tag,
            name,
        ) ?: "Unclosed tag <$name>"

        fun scriptBrackets(message: String): String = context?.getString(
            R.string.miniapp_source_check_script_brackets,
            message,
        ) ?: "Unbalanced brackets in script: $message"

        fun styleBraces(message: String): String = context?.getString(
            R.string.miniapp_source_check_style_braces,
            message,
        ) ?: "Unbalanced braces in style: $message"

        fun unbalanced(kind: String): String = context?.getString(
            R.string.miniapp_source_check_unbalanced,
            kind,
        ) ?: "Unbalanced bracket: $kind"

        fun unclosed(kind: String): String = context?.getString(
            R.string.miniapp_source_check_unclosed,
            kind,
        ) ?: "Unclosed $kind"
    }

    private val VOID_TAGS = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr",
    )
}
