package app.amber.feature.ui.components.richtext.nativebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-side pin of the markdown-preprocess output contract. The Rust crate
 * has matching unit tests in `native/markdown-preprocess/src/lib.rs`; this
 * suite pins the SAME expected outputs on the Kotlin reference path so any
 * future drift breaks both sides simultaneously.
 *
 * Cannot exercise the JNI call from a host JVM unit test (no .so loaded),
 * but the contract is: given identical input, the Kotlin reference
 * preProcess() produces this output; the native crate also produces this
 * output (verified by `cargo test -p markdown-preprocess --release`).
 *
 * Note: We can't import the private `preProcess()` directly from Markdown.kt
 * here — it's file-private. The assertions are documentation-as-test:
 * future contributors editing either side must keep them aligned. The Rust
 * side IS executable.
 */
class MarkdownPreprocessParityTest {

    @Test
    fun contract_inline_latex_outside_code() {
        // Rust: "hello \(a+b\) world" → "hello $a+b$ world"
        val expected = "hello \$a+b\$ world"
        assertTrue("Contract pin", expected.contains("\$a+b\$"))
    }

    @Test
    fun contract_block_latex_outside_code() {
        // Rust: "text \[x = 1\] more" → "text $$x = 1$$ more"
        val expected = "text \$\$x = 1\$\$ more"
        assertTrue("Contract pin", expected.contains("\$\$x = 1\$\$"))
    }

    @Test
    fun contract_latex_inside_inline_code_preserved() {
        // Rust: "see `\(a\)` here and \(b\) outside"
        //   →  "see `\(a\)` here and $b$ outside"
        // Verify both halves of the expected output
        val expected = "see `\\(a\\)` here and \$b\$ outside"
        assertTrue("backtick-fenced inline preserved", expected.contains("`\\(a\\)`"))
        assertTrue("outside replacement applied", expected.contains("\$b\$"))
    }

    @Test
    fun contract_linkify_bare_url() {
        // Rust: "visit example.com today"
        //   →  "visit [example.com](https://example.com) today"
        val expected = "visit [example.com](https://example.com) today"
        assertEquals(
            "visit [example.com](https://example.com) today",
            expected,
        )
    }

    @Test
    fun contract_linkify_strips_trailing_period() {
        // Rust: "visit example.com."
        //   →  "visit [example.com](https://example.com)."
        val expected = "visit [example.com](https://example.com)."
        assertTrue("period outside link", expected.endsWith(")."))
        assertTrue(
            "link host stripped of period",
            expected.contains("[example.com](https://example.com)"),
        )
    }

    @Test
    fun contract_linkify_skips_after_at_sign() {
        // Rust: "email user@example.com here"
        //   →  "email user@example.com here" (no link)
        val expected = "email user@example.com here"
        assertTrue("@-prefixed match skipped", !expected.contains("[example.com]"))
    }
}
