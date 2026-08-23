//! markdown-preprocess: Rust replacement for `preProcess()` in
//! `app/.../richtext/Markdown.kt` (TD.Rust.1b).
//!
//! Single-pass scan that combines three Kotlin regex passes:
//!   1. Inline LaTeX: `\(expr\)` → `$expr$` outside code blocks
//!   2. Block LaTeX: `\[expr\]` → `$$expr$$` outside code blocks (DOTALL)
//!   3. Bare URL linkify: `foo.com[/path]` → `[foo.com](https://foo.com)`
//!      outside code blocks, with manual lookbehind (\w/@:[( excluded)
//!
//! Code-block ranges (``` ... ``` AND inline `…`) are computed once and
//! checked via O(log n) binary search per match candidate.
//!
//! **Lookbehind handling**: Kotlin uses `(?<![\w/@:\[(])` on the bare-URL
//! pattern. Rust's `regex` crate has no lookbehind, so we manually check
//! the char immediately preceding each match.
//!
//! JNI entry returns null on any failure → Kotlin adapter falls back to
//! the original Kotlin implementation.

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use regex::Regex;

// Cached regexes — same patterns as Markdown.kt
fn re_inline_latex() -> &'static Regex {
    static R: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
    R.get_or_init(|| Regex::new(r"\\\((.+?)\\\)").unwrap())
}

fn re_block_latex() -> &'static Regex {
    static R: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
    // (?s) = DOT_MATCHES_ALL (the JVM RegexOption.DOT_MATCHES_ALL)
    R.get_or_init(|| Regex::new(r"(?s)\\\[(.+?)\\\]").unwrap())
}

fn re_code_block() -> &'static Regex {
    static R: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
    // (?s) for the triple-backtick branch; inline ` doesn't span newlines
    R.get_or_init(|| Regex::new(r"(?s)```.*?```|`[^`\n]*`").unwrap())
}

fn re_bare_url() -> &'static Regex {
    static R: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
    // Same TLD list + path pattern as BARE_WEB_URL_REGEX in Markdown.kt.
    // We strip the JVM lookbehind `(?<![\w/@:\[(])` and apply it manually
    // when iterating matches (see `linkify_outside_code`).
    R.get_or_init(|| {
        Regex::new(
            r"(?:[A-Za-z0-9-]+\.)+(?:com|net|org|io|ai|cn|co|dev|app|me|info|xyz|news)(?:/[^\s<>()\[\]{}\x22']*)?",
        )
        .unwrap()
    })
}

/// JNI: `MarkdownPreprocessNative.preprocessNative(input: String): String?`.
///
/// Returns the preprocessed markdown ready for the parser, or null on any
/// failure (JNI conversion error, panic, regex compile failure — though
/// the latter is a build-time guarantee since all regexes are static).
#[no_mangle]
pub extern "system" fn Java_app_amber_feature_ui_components_richtext_nativebridge_MarkdownPreprocessNative_preprocessNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    input: JString<'local>,
) -> jstring {
    jni_common::init_logger_once!("RustMarkdownPreprocess");

    let input_str: String = match env.get_string(&input) {
        Ok(s) => String::from(s),
        Err(e) => {
            log::error!("markdown-preprocess: get_string(input) failed: {}", e);
            return std::ptr::null_mut();
        }
    };

    let result = catch_unwind(AssertUnwindSafe(|| preprocess(&input_str)));

    match result {
        Ok(out) => match env.new_string(out) {
            Ok(jstr) => jstr.into_raw(),
            Err(e) => {
                log::error!("markdown-preprocess: new_string failed: {}", e);
                std::ptr::null_mut()
            }
        },
        Err(panic) => {
            log::error!(
                "markdown-preprocess: panic: {}",
                jni_common::panic_to_string(&panic)
            );
            std::ptr::null_mut()
        }
    }
}

/// Pure-Rust preprocess pipeline. Exposed for unit tests + the JNI entry.
pub fn preprocess(input: &str) -> String {
    let code_ranges = collect_code_block_ranges(input);
    // Step 1+2: replace inline & block LaTeX outside code.
    let after_inline = replace_outside_code(input, re_inline_latex(), &code_ranges, "$", "$");
    // After the first replacement the indices have shifted; recompute.
    let code_ranges_2 = collect_code_block_ranges(&after_inline);
    let after_block =
        replace_outside_code(&after_inline, re_block_latex(), &code_ranges_2, "$$", "$$");
    // Step 3: linkify bare URLs.
    let code_ranges_3 = collect_code_block_ranges(&after_block);
    linkify_outside_code(&after_block, &code_ranges_3)
}

fn collect_code_block_ranges(s: &str) -> Vec<(usize, usize)> {
    re_code_block()
        .find_iter(s)
        .map(|m| (m.start(), m.end()))
        .collect()
}

fn in_any_range(pos: usize, ranges: &[(usize, usize)]) -> bool {
    // Ranges are returned in scan order ⇒ already sorted. Binary search
    // for the first range whose end > pos; if its start <= pos, hit.
    let idx = ranges.partition_point(|&(_, end)| end <= pos);
    if idx < ranges.len() {
        let (start, end) = ranges[idx];
        if start <= pos && pos < end {
            return true;
        }
    }
    false
}

fn replace_outside_code(
    input: &str,
    re: &Regex,
    code_ranges: &[(usize, usize)],
    prefix: &str,
    suffix: &str,
) -> String {
    let mut out = String::with_capacity(input.len() + 16);
    let mut last = 0usize;
    for caps in re.captures_iter(input) {
        let m = caps.get(0).unwrap();
        if in_any_range(m.start(), code_ranges) {
            // Skip; let the verbatim copy happen below
            continue;
        }
        out.push_str(&input[last..m.start()]);
        out.push_str(prefix);
        if let Some(g1) = caps.get(1) {
            out.push_str(g1.as_str());
        }
        out.push_str(suffix);
        last = m.end();
    }
    out.push_str(&input[last..]);
    out
}

fn linkify_outside_code(input: &str, code_ranges: &[(usize, usize)]) -> String {
    let mut out = String::with_capacity(input.len() + 16);
    let mut cursor = 0usize;
    for &(cs, ce) in code_ranges {
        if cursor < cs {
            out.push_str(&linkify_segment(&input[cursor..cs]));
        }
        out.push_str(&input[cs..ce]);
        cursor = ce;
    }
    if cursor < input.len() {
        out.push_str(&linkify_segment(&input[cursor..]));
    }
    out
}

fn linkify_segment(segment: &str) -> String {
    let re = re_bare_url();
    // Pre-scan explicit-link `[label](dest)` spans so the bare-URL scanner never
    // rewrites a domain-looking token already inside an explicit link — its label
    // (`[see example.com](...)`) or its destination
    // (`[guides](https://developer.android.com/guide)`). GFM does not re-linkify
    // text inside an explicit link, so the WHOLE construct is skipped. Mirrors the
    // Kotlin `findExplicitLinkRanges` reference (Markdown.kt); kept output-identical.
    let skip_ranges = find_explicit_link_ranges(segment);
    let mut out = String::with_capacity(segment.len() + 16);
    let mut last = 0usize;
    for m in re.find_iter(segment) {
        // Manual lookbehind: skip if preceding char is in [\w/@:\[(]
        // (Kotlin: `(?<![\w/@:\[(])`).
        if m.start() > 0 {
            // Find char immediately before via char_indices
            let prev_char = segment[..m.start()].chars().next_back();
            if let Some(c) = prev_char {
                if c.is_alphanumeric() || c == '_' || c == '/' || c == '@' || c == ':' || c == '['
                    || c == '('
                {
                    continue;
                }
            }
        }
        // Skip candidates whose start falls inside an explicit link's label/dest.
        if position_in_any_range(m.start(), &skip_ranges) {
            continue;
        }
        let raw = m.as_str();
        let trimmed = raw.trim_end_matches(|c| {
            matches!(c, '.' | ',' | ';' | ':' | '。' | '，' | '；' | '：')
        });
        let trailing = &raw[trimmed.len()..];
        out.push_str(&segment[last..m.start()]);
        out.push('[');
        out.push_str(trimmed);
        out.push_str("](https://");
        out.push_str(trimmed);
        out.push(')');
        out.push_str(trailing);
        last = m.end();
    }
    out.push_str(&segment[last..]);
    out
}

/// Collects the BYTE ranges `[start, end_inclusive]` of explicit markdown links
/// `[label](dest)` in `segment`, each spanning the whole construct (label `[` to
/// destination `)`). The bare-URL linkifier skips any candidate whose start byte
/// falls inside one. Mirrors the Kotlin `findExplicitLinkRanges` reference so both
/// implementations stay output-identical.
///
/// `]`, `[`, `(`, `)`, `\` are all ASCII (one byte), so scanning the raw bytes is
/// boundary-safe for them; multi-byte UTF-8 chars are skipped over as opaque
/// non-delimiter bytes (their continuation bytes are all >= 0x80 and never equal
/// an ASCII delimiter), so they cannot be mistaken for a bracket/paren.
fn find_explicit_link_ranges(segment: &str) -> Vec<(usize, usize)> {
    let b = segment.as_bytes();
    let n = b.len();
    if n < 4 {
        return Vec::new();
    }
    let mut ranges: Vec<(usize, usize)> = Vec::new();
    let mut i = 0usize;
    while i + 1 < n {
        if b[i] == b']' && b[i + 1] == b'(' {
            if let Some(label_start) = find_label_start(b, i) {
                if let Some(dest_end) = find_destination_end(b, i + 1) {
                    ranges.push((label_start, dest_end));
                    i = dest_end + 1;
                    continue;
                }
            }
        }
        i += 1;
    }
    ranges
}

/// From the `]` at `close_bracket`, scans back to the matching unescaped `[`,
/// balancing nested `[]`. Returns the `[` byte index, or None.
fn find_label_start(b: &[u8], close_bracket: usize) -> Option<usize> {
    let mut depth = 0i32;
    let mut j = close_bracket;
    while j > 0 {
        j -= 1;
        let escaped = j > 0 && b[j - 1] == b'\\';
        if !escaped {
            if b[j] == b']' {
                depth += 1;
            } else if b[j] == b'[' {
                if depth == 0 {
                    return Some(j);
                }
                depth -= 1;
            }
        }
    }
    None
}

/// From the `(` at `open_paren`, scans forward to the matching `)`, balancing
/// nested parens and honoring backslash escapes. Returns the `)` byte index, or None.
fn find_destination_end(b: &[u8], open_paren: usize) -> Option<usize> {
    let mut depth = 0i32;
    let mut k = open_paren;
    let n = b.len();
    while k < n {
        let c = b[k];
        if c == b'\\' {
            k += 2;
            continue;
        }
        if c == b'(' {
            depth += 1;
        } else if c == b')' {
            depth -= 1;
            if depth == 0 {
                return Some(k);
            }
        }
        k += 1;
    }
    None
}

/// True if `pos` lies within any inclusive `[start, end]` range.
fn position_in_any_range(pos: usize, ranges: &[(usize, usize)]) -> bool {
    ranges.iter().any(|&(s, e)| pos >= s && pos <= e)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn inline_latex_outside_code() {
        let s = r"hello \(a+b\) world";
        let out = preprocess(s);
        assert_eq!(out, "hello $a+b$ world");
    }

    #[test]
    fn block_latex_outside_code() {
        let s = r"text \[x = 1\] more";
        let out = preprocess(s);
        assert_eq!(out, "text $$x = 1$$ more");
    }

    #[test]
    fn latex_inside_code_block_is_preserved() {
        let s = "before ```\nfoo \\(bar\\) baz\n``` after \\(x\\)";
        let out = preprocess(s);
        // Inside the fence stays raw; outside is replaced
        assert!(out.contains("foo \\(bar\\) baz"));
        assert!(out.ends_with("$x$"));
    }

    #[test]
    fn latex_inside_inline_code_is_preserved() {
        let s = "see `\\(a\\)` here and \\(b\\) outside";
        let out = preprocess(s);
        assert!(out.contains("`\\(a\\)`"));
        assert!(out.contains("$b$"));
    }

    #[test]
    fn linkify_bare_url() {
        let s = "visit example.com today";
        let out = preprocess(s);
        assert_eq!(out, "visit [example.com](https://example.com) today");
    }

    #[test]
    fn linkify_url_with_path() {
        let s = "see github.com/user/repo for details";
        let out = preprocess(s);
        assert!(out.contains("[github.com/user/repo](https://github.com/user/repo)"));
    }

    #[test]
    fn linkify_skips_when_after_at_sign() {
        // user@example.com — would-be-link is preceded by @, should be skipped
        let s = "email user@example.com here";
        let out = preprocess(s);
        // The @example.com should remain raw (not linkified) since the
        // lookbehind excludes @-prefixed matches
        assert!(!out.contains("[example.com]"));
    }

    #[test]
    fn linkify_strips_trailing_punctuation() {
        let s = "visit example.com.";
        let out = preprocess(s);
        // Period should land OUTSIDE the link
        assert!(out.contains("[example.com](https://example.com)"));
        assert!(out.ends_with("."));
    }

    #[test]
    fn linkify_inside_code_block_is_preserved() {
        let s = "outside example.com ```\ninside example.com\n``` outside again example.com";
        let out = preprocess(s);
        assert!(out.contains("inside example.com")); // raw inside fence
        // Two links outside
        let link_count = out.matches("[example.com](https://example.com)").count();
        assert_eq!(link_count, 2);
    }

    #[test]
    fn linkify_skips_explicit_link_destination() {
        // The bare-URL scanner must not rewrite a domain-looking token inside an
        // explicit link's destination. Pre-fix this produced a nested link:
        // `[guides](https://developer.[android.com/guide](https://android.com/guide))`.
        let s = "[Android developer guides](https://developer.android.com/guide) are canonical.";
        let out = preprocess(s);
        assert_eq!(out, s);
        assert!(!out.contains("[android.com/guide]"));
    }

    #[test]
    fn linkify_skips_multiple_explicit_links_on_one_line() {
        let s =
            "For [Maven Central](https://search.maven.org) and [Google Maven](https://maven.google.com).";
        let out = preprocess(s);
        assert_eq!(out, s);
        assert!(!out.contains("[maven.org]"));
        assert!(!out.contains("[google.com]"));
    }

    #[test]
    fn linkify_skips_domain_inside_link_label() {
        // GFM does not re-linkify text inside an explicit link — its label included.
        let s = "[see example.com](https://example.com)";
        let out = preprocess(s);
        assert_eq!(out, s);
        assert!(!out.contains("[example.com]("));
    }

    #[test]
    fn linkify_after_explicit_link_still_works() {
        // The explicit link is skipped; a trailing bare domain still linkifies.
        let s = "See [the docs](https://example.com/docs) or visit kotlinlang.org for more.";
        let out = preprocess(s);
        assert!(out.contains("[kotlinlang.org](https://kotlinlang.org)"));
        assert!(out.contains("[the docs](https://example.com/docs)"));
    }

    #[test]
    fn linkify_balanced_parens_in_destination() {
        // Balanced parens in a URL (Wikipedia) must not confuse the destination
        // scan or trigger re-linkification. (Note: an ESCAPED-paren destination
        // like `path\(1\)` is converted to `path$1$` by the inline-LaTeX pass —
        // a separate, pre-existing preprocess behavior unrelated to linkify — so
        // it is deliberately not asserted here.)
        let balanced = "[Wikipedia](https://en.wikipedia.org/wiki/Kotlin_(programming_language))";
        assert_eq!(preprocess(balanced), balanced);
    }

    #[test]
    fn empty_input() {
        assert_eq!(preprocess(""), "");
    }

    #[test]
    fn no_replacements_passthrough() {
        let s = "plain text with no special markers";
        assert_eq!(preprocess(s), s);
    }
}
