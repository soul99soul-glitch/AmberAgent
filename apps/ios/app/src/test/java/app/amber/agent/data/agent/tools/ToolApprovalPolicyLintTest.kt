package app.amber.feature.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Static guard for the harness approval boundary. Tool names that look like
 * writes or outbound actions must opt into explicit user approval and opt out
 * of regular auto-approval at the Tool factory site.
 */
class ToolApprovalPolicyLintTest {
    @Test
    fun mutatingNamedToolsDeclareExplicitApprovalBoundary() {
        val blocks = toolBlocks()
        check(blocks.isNotEmpty()) {
            "ToolApprovalPolicyLintTest found no Tool(...) blocks; the lint would be a no-op."
        }

        val violations = blocks
            .filter { isWriteLikeToolName(it.name) }
            .filterNot { block ->
                NEEDS_APPROVAL_TRUE.containsMatchIn(block.source) &&
                    ALLOWS_AUTO_APPROVAL_FALSE.containsMatchIn(block.source)
            }

        if (violations.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("Write-like tool names must declare `needsApproval = true` and `allowsAutoApproval = false`.")
                    appendLine("If a name is read-only despite containing a write-like token, add a narrow exception here.")
                    appendLine()
                    appendLine("Violations:")
                    violations.sortedBy { "${it.relativePath}:${it.line}" }.forEach { block ->
                        appendLine("  ${block.relativePath}:${block.line}: ${block.name}")
                    }
                }
            )
        }

        listOf(
            "cron_task_create",
            "cron_task_update",
            "cron_task_delete",
            "file_write",
            "file_edit",
            "file_move",
            "icloud_write",
            "notification_post",
            "feishu_docs_create",
            "feishu_docs_append_block",
        ).forEach { required ->
            assertTrue("$required should be covered by the write-like lint", blocks.any { it.name == required })
            assertTrue("$required should be classified as write-like", isWriteLikeToolName(required))
        }
    }

    @Test
    fun writeNameHeuristicPreservesReadOnlyExceptions() {
        assertFalse(isWriteLikeToolName("reddit_post_read"))
        assertFalse(isWriteLikeToolName("juejin_my_posts"))
        assertFalse(isWriteLikeToolName("notification_list"))
        assertFalse(isWriteLikeToolName("run_plan_update"))
    }

    private fun toolBlocks(): List<ToolBlock> {
        val roots = sourceRoots()
        check(roots.isNotEmpty()) {
            "Could not locate source roots for approval lint."
        }
        val blocks = mutableListOf<ToolBlock>()
        roots.forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file -> blocks += file.toolBlocks(root) }
        }
        return blocks.distinctBy { "${it.relativePath}:${it.startOffset}" }
    }

    private fun sourceRoots(): List<File> {
        val candidates = listOf(
            File("src/main/java"),
            File("src/main/kotlin"),
            File("../feature/tools/impl/src/main/kotlin"),
            File("../feature/tools/api/src/main/kotlin"),
            File("../feature/subagent/src/main/kotlin"),
            File("app/src/main/java"),
            File("app/src/main/kotlin"),
            File("feature/tools/impl/src/main/kotlin"),
            File("feature/tools/api/src/main/kotlin"),
            File("feature/subagent/src/main/kotlin"),
        )
        return candidates
            .filter { it.isDirectory }
            .map { it.canonicalFile }
            .distinct()
    }

    private fun File.toolBlocks(reportRoot: File): List<ToolBlock> {
        val source = readText()
        return NAME_REGEX.findAll(source).mapNotNull { match ->
            val start = source.findToolCallStart(before = match.range.first) ?: return@mapNotNull null
            val end = source.findCallEnd(start) ?: return@mapNotNull null
            val name = match.groupValues[1]
            ToolBlock(
                name = name,
                relativePath = toRelativeString(reportRoot),
                line = source.substring(0, match.range.first).count { it == '\n' } + 1,
                startOffset = start,
                source = source.substring(start, end + 1),
            )
        }.toList()
    }

    private fun String.findToolCallStart(before: Int): Int? {
        var index = lastIndexOf("Tool(", before)
        while (index >= 0) {
            val previous = getOrNull(index - 1)
            if (previous == null || (!previous.isLetterOrDigit() && previous != '_')) return index
            index = lastIndexOf("Tool(", index - 1)
        }
        return null
    }

    private fun String.findCallEnd(start: Int): Int? {
        var depth = 0
        var i = start
        var inLineComment = false
        var inBlockComment = false
        var inString = false
        var inTripleString = false
        var escaped = false

        while (i < length) {
            val c = this[i]
            when {
                inLineComment -> {
                    if (c == '\n') inLineComment = false
                    i += 1
                }
                inBlockComment -> {
                    if (c == '*' && getOrNull(i + 1) == '/') {
                        inBlockComment = false
                        i += 2
                    } else {
                        i += 1
                    }
                }
                inString -> {
                    when {
                        inTripleString && startsWith("\"\"\"", i) -> {
                            inString = false
                            inTripleString = false
                            i += 3
                        }
                        inTripleString -> i += 1
                        escaped -> {
                            escaped = false
                            i += 1
                        }
                        c == '\\' -> {
                            escaped = true
                            i += 1
                        }
                        c == '"' -> {
                            inString = false
                            i += 1
                        }
                        else -> i += 1
                    }
                }
                startsWith("//", i) -> {
                    inLineComment = true
                    i += 2
                }
                startsWith("/*", i) -> {
                    inBlockComment = true
                    i += 2
                }
                startsWith("\"\"\"", i) -> {
                    inString = true
                    inTripleString = true
                    i += 3
                }
                c == '"' -> {
                    inString = true
                    i += 1
                }
                c == '(' -> {
                    depth += 1
                    i += 1
                }
                c == ')' -> {
                    depth -= 1
                    if (depth == 0) return i
                    i += 1
                }
                else -> i += 1
            }
        }
        return null
    }

    private data class ToolBlock(
        val name: String,
        val relativePath: String,
        val line: Int,
        val startOffset: Int,
        val source: String,
    )

    companion object {
        private val NAME_REGEX = Regex("""name\s*=\s*"([a-zA-Z0-9_]+)"""")
        private val NEEDS_APPROVAL_TRUE = Regex("""needsApproval\s*=\s*true""")
        private val ALLOWS_AUTO_APPROVAL_FALSE = Regex("""allowsAutoApproval\s*=\s*false""")
        private val WRITE_TOKENS = setOf("create", "append", "comment", "post", "publish", "send", "update")
        private val READ_ONLY_TOKENS = setOf("read", "list", "search", "status")
        private val NON_MUTATING_WRITE_LIKE_EXCEPTIONS = setOf(
            "notification_list",
            "reddit_post_read",
            "juejin_my_posts",
            "run_plan_update",
        )

        internal fun isWriteLikeToolName(name: String): Boolean {
            if (name in NON_MUTATING_WRITE_LIKE_EXCEPTIONS) return false
            if (name.startsWith("deep_read_write_")) return false
            val readOnlyName = READ_ONLY_TOKENS.any { name.hasToken(it) }
            val hasWriteToken = WRITE_TOKENS.any { token ->
                name.hasToken(token) && (token !in setOf("comment", "post") || !readOnlyName)
            }
            return name.contains("_write") ||
                name.contains("_edit") ||
                name.contains("_move") ||
                name.contains("_delete") ||
                hasWriteToken
        }

        private fun String.hasToken(token: String): Boolean =
            this == token || startsWith("${token}_") || endsWith("_$token") || contains("_${token}_")
    }
}
