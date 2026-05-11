package me.rerere.rikkahub.data.agent.webmount

import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Phase 2 M2.0.6 — Output-shape lint.
 *
 * The Phase 1 holistic review (commit 4eed25b4, fix D6) documented the
 * convention that *adapter* tools (hn_*, juejin_*, feishu_docs_*, ...) must
 * return **flat query-style JSON** at the top level — never wrapping
 * upstream payloads under a "response" key. The Phase 1 violator was
 * `feishu_docs_append_block`, fixed in 4eed25b4.
 *
 * Rather than try to execute every adapter tool with mocked clients (huge
 * mock surface, brittle), this test does a static source scan and flags
 * the literal `put("response"` anti-pattern in any adapter tools file.
 * If a future adapter author adds back the nested envelope, this test
 * fires before review.
 *
 * NB: Browser Primitives (wm_*) intentionally use a nested `{session_id,
 * result: {...}}` shape — that's RPC-style, and the convention exempts
 * them. The scan is scoped to `webmount/adapters/` only.
 */
class AdapterOutputShapeLintTest {

    @Test
    fun noAdapterToolWrapsResponseInNestedEnvelope() {
        val adaptersDir = locateAdaptersDir()
        val toolsFiles = adaptersDir.walk()
            .filter { it.isFile && it.name.endsWith("Tools.kt") }
            .toList()

        check(toolsFiles.isNotEmpty()) {
            "AdapterOutputShapeLintTest could not find any *Tools.kt under $adaptersDir — " +
                "the lint is a no-op which would silently let future regressions slip in."
        }

        // For violation reporting, anchor paths to whichever search root we found
        // (`src/main/...` when running from :app, `app/src/main/...` when from repo root).
        val reportRoot = adaptersDir.absoluteFile
        val violations = mutableListOf<String>()
        for (file in toolsFiles) {
            file.readLines().forEachIndexed { idx, raw ->
                val line = raw.trim()
                // Strip comments / blank lines.
                if (line.startsWith("//") || line.startsWith("*") || line.isEmpty()) return@forEachIndexed
                // The anti-pattern we ban: `put("response", <something>)` inside
                // an adapter tool output. The Phase 1 violator looked exactly like
                // this — wrapping the raw upstream envelope under a "response" key.
                if (RESPONSE_KEY_REGEX.containsMatchIn(line)) {
                    violations += "${file.toRelativeString(reportRoot)}:${idx + 1}: $line"
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("Adapter tool output contains the banned `put(\"response\", ...)` envelope.")
                    appendLine("Adapter tools must return flat query-style JSON; do not nest the upstream")
                    appendLine("response under a \"response\" key. See WebMountAdapter.kt kdoc and Phase 1")
                    appendLine("commit 4eed25b4 (D6 fix) for rationale.")
                    appendLine()
                    appendLine("Violations:")
                    violations.forEach { appendLine("  $it") }
                }
            )
        }
    }

    private fun locateAdaptersDir(): File {
        // The unit test JVM working directory is the :app module root.
        val candidates = listOf(
            File("src/main/java/me/rerere/rikkahub/data/agent/webmount/adapters"),
            File("app/src/main/java/me/rerere/rikkahub/data/agent/webmount/adapters"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error(
                "Could not locate the adapters source directory from any of: " +
                    candidates.joinToString { it.absolutePath }
            )
    }

    companion object {
        // Matches `put("response", ...)` and `put(\"response\", ...)` variants,
        // catching keys with the literal name regardless of quoting style.
        private val RESPONSE_KEY_REGEX = Regex("""put\s*\(\s*"response"\s*,""")
    }
}
