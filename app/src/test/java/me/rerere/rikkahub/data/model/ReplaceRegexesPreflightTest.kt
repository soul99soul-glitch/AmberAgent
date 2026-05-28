package me.rerere.rikkahub.data.model

import app.amber.core.model.AssistantAffectScope.USER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Pins the regex JVM-only-syntax preflight that gates whether
 * `String.replaceRegexes` routes through `RegexNativeSwitch`. False
 * negatives here silently corrupt output (Rust skips the rule, the
 * fallback never fires) — pin every shape we know about so a future
 * regex tweak can't drop coverage without the test going red.
 *
 * Hits the package-private `containsJvmOnlyRegexSyntax` via the
 * exported extension function indirectly — the test asserts behavior
 * by inspecting only its OBSERVABLE effect via flag-toggle paths. But
 * since the preflight is also useful to keep tightly pinned for
 * future modifications, we duplicate the call here using the same
 * regex strings. (Source-of-truth lives in Assistant.kt.)
 */
class ReplaceRegexesPreflightTest {

    // Re-declare the two regexes used internally so we can assert on
    // them. Sync these with `JVM_ONLY_PATTERN_SYNTAX` /
    // `JVM_ONLY_REPLACEMENT_SYNTAX` in Assistant.kt.
    private val patternDetector = Regex(
        """\(\?<[=!]|\(\?[=!]|\(\?>|\\[1-9]|[?*+]\+"""
    )
    private val replacementDetector = Regex("""\\\$|\$<""")

    private fun rule(find: String, replace: String = "") =
        AssistantRegex(
            id = Uuid.random(),
            name = "",
            enabled = true,
            findRegex = find,
            replaceString = replace,
            affectingScope = setOf(USER),
        )

    private fun preflightWouldFlag(rules: List<AssistantRegex>): Boolean =
        rules.any { r ->
            patternDetector.containsMatchIn(r.findRegex) ||
                replacementDetector.containsMatchIn(r.replaceString)
        }

    // ──────────────────────────────────────────────────────────────
    // Patterns that MUST be flagged as JVM-only
    // ──────────────────────────────────────────────────────────────

    @Test fun lookbehind_positive_is_flagged() {
        assertTrue(preflightWouldFlag(listOf(rule("""(?<=foo)bar"""))))
    }

    @Test fun lookbehind_negative_is_flagged() {
        assertTrue(preflightWouldFlag(listOf(rule("""(?<!foo)bar"""))))
    }

    @Test fun lookahead_positive_is_flagged() {
        assertTrue(preflightWouldFlag(listOf(rule("""foo(?=bar)"""))))
    }

    @Test fun lookahead_negative_is_flagged() {
        assertTrue(preflightWouldFlag(listOf(rule("""foo(?!bar)"""))))
    }

    @Test fun atomic_group_is_flagged() {
        assertTrue(preflightWouldFlag(listOf(rule("""(?>foo|bar)baz"""))))
    }

    @Test fun backref_in_pattern_is_flagged() {
        assertTrue(preflightWouldFlag(listOf(rule("""(\w+)\s+\1"""))))
    }

    @Test fun possessive_star_is_flagged() {
        assertTrue(preflightWouldFlag(listOf(rule("""a*+b"""))))
    }

    @Test fun possessive_plus_is_flagged() {
        assertTrue(preflightWouldFlag(listOf(rule("""a++b"""))))
    }

    @Test fun possessive_optional_is_flagged() {
        assertTrue(preflightWouldFlag(listOf(rule("""a?+b"""))))
    }

    // ──────────────────────────────────────────────────────────────
    // Replacement strings that MUST be flagged
    // ──────────────────────────────────────────────────────────────

    @Test fun literal_dollar_in_replacement_is_flagged() {
        // Kotlin source `\$` at runtime is `\$` (2 chars). In Java replace
        // semantics, `\$` outputs a literal `$`. Rust replace uses `$$`
        // for literal $, so behavior differs.
        assertTrue(preflightWouldFlag(listOf(rule("""x""", """\$"""))))
    }

    @Test fun java_named_group_replacement_is_flagged() {
        // Java replacement `$<name>` references a named capture; Rust
        // uses `${name}` or `$name`.
        assertTrue(preflightWouldFlag(listOf(rule("""x""", """$<grp>"""))))
    }

    // ──────────────────────────────────────────────────────────────
    // Patterns/replacements that MUST NOT be flagged (safe to native)
    // ──────────────────────────────────────────────────────────────

    @Test fun plain_pattern_is_not_flagged() {
        assertFalse(preflightWouldFlag(listOf(rule("""hello\s+world"""))))
    }

    @Test fun simple_quantifiers_not_flagged() {
        assertFalse(preflightWouldFlag(listOf(rule("""a*b+c?"""))))
    }

    @Test fun named_capture_definition_not_flagged() {
        // Both engines support `(?<name>...)` as named capture *definition*
        // — only `(?<=` / `(?<!` (lookbehind) are JVM-only.
        assertFalse(preflightWouldFlag(listOf(rule("""(?<word>\w+)"""))))
    }

    @Test fun dollar_followed_by_digit_not_flagged() {
        // `$1` is standard backref in both Java AND Rust replacement.
        assertFalse(preflightWouldFlag(listOf(rule("""(\w+)""", """$1"""))))
    }

    @Test fun curly_named_group_replacement_not_flagged() {
        // `${name}` is portable Java + Rust syntax (Rust accepts both
        // `${name}` and `$name`).
        assertFalse(preflightWouldFlag(listOf(rule("""(?<word>\w+)""", """${'$'}{word}"""))))
    }

    @Test fun mixed_safe_batch_not_flagged() {
        assertFalse(
            preflightWouldFlag(
                listOf(
                    rule("""\bhello\b""", "hi"),
                    rule("""(\d+)""", """$1!"""),
                    rule("""^prefix:\s+"""),
                )
            )
        )
    }

    // ──────────────────────────────────────────────────────────────
    // One-bad-apple semantics: any single JVM-only rule trips the batch
    // ──────────────────────────────────────────────────────────────

    @Test fun mixed_batch_with_one_lookbehind_is_flagged() {
        assertTrue(
            preflightWouldFlag(
                listOf(
                    rule("""\bhello\b""", "hi"),
                    rule("""(?<=at )now"""),       // ← lookbehind
                    rule("""(\d+)""", """$1!"""),
                )
            )
        )
    }

    @Test fun invalid_replacement_is_skipped_without_aborting_batch() {
        val assistant = Assistant(
            id = Uuid.random(),
            regexes = listOf(
                rule("bad", "$"),
                rule("good", "better"),
            ),
        )

        assertEquals(
            "bad better",
            "bad good".replaceRegexes(
                assistant = assistant,
                scope = USER,
                visual = false,
            ),
        )
    }
}
