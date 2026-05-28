//! markdown-parser: Rust replacement for `app/.../richtext/Markdown.kt`'s
//! `parser.buildMarkdownTreeFromString(text)` call (JetBrains markdown lib).
//!
//! Strategy:
//!   - pulldown-cmark events → tree IR → packed binary
//!   - JNI returns ByteArray; Kotlin side has `PackedAstNode` lazy view that
//!     mimics the org.intellij.markdown.ast.ASTNode interface
//!
//! Also exposes `parseMarkdownToHtmlNative` (Component #8) — direct
//! pulldown-cmark events → HTML emit, replacing MarkdownNew.kt's
//! HtmlGenerator + Jsoup-reparse two-pass pipeline.
//!
//! See `docs/RUST_NATIVE_SPIKE_PLAN.md` §4 for full design.

mod packed_ast;
mod tree_builder;
mod type_mapping;

use std::panic::{catch_unwind, AssertUnwindSafe};
use pulldown_cmark::Options;

/// Single source of truth for which pulldown-cmark extensions we enable.
/// Both the packed-AST path (`tree_builder::build_tree`) and the HTML emit
/// path (`render_to_html`) call this so they can't drift.
///
/// Choices:
/// - `ENABLE_TABLES` / `ENABLE_FOOTNOTES` / `ENABLE_STRIKETHROUGH` /
///   `ENABLE_TASKLISTS` — GFM extensions present in JetBrains GFMFlavour.
/// - `ENABLE_HEADING_ATTRIBUTES` — supports `{#id}` heading anchors.
/// - `ENABLE_MATH` — `$...$` / `$$...$$` math blocks.
/// - `ENABLE_GFM` — GFM autolink for bare URLs + extension flags.
/// - **NOT** `ENABLE_SMART_PUNCTUATION` — JetBrains doesn't do smart-quote
///   conversion, so leaving it on would diff `'hello'` vs `'hello'` on
///   every comparison run (Round 2 review P1 fix).
pub(crate) fn markdown_options() -> Options {
    let mut opts = Options::empty();
    opts.insert(Options::ENABLE_TABLES);
    opts.insert(Options::ENABLE_FOOTNOTES);
    opts.insert(Options::ENABLE_STRIKETHROUGH);
    opts.insert(Options::ENABLE_TASKLISTS);
    opts.insert(Options::ENABLE_HEADING_ATTRIBUTES);
    opts.insert(Options::ENABLE_MATH);
    opts.insert(Options::ENABLE_GFM);
    opts
}

use jni::objects::{JClass, JString};
use jni::sys::jbyteArray;
use jni::JNIEnv;

/// JNI entry: `MarkdownParserNative.parseMarkdownNative(text: String): ByteArray`.
///
/// Returns a packed binary AST blob. On error returns an empty byte array
/// and the Kotlin adapter falls back to the JVM JetBrains-markdown parser.
///
/// # Safety
/// Standard JNI signature. Panics are caught and converted to empty returns.
#[no_mangle]
pub extern "system" fn Java_app_amber_agent_ui_components_richtext_nativebridge_MarkdownParserNative_parseMarkdownNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    text: JString<'local>,
) -> jbyteArray {
    jni_common::init_logger_once!("RustMarkdownParser");

    let text_str: String = match env.get_string(&text) {
        Ok(s) => String::from(s),
        Err(e) => {
            log::error!("markdown-parser: failed to get JString: {}", e);
            return empty_array(&mut env);
        }
    };

    let result = catch_unwind(AssertUnwindSafe(|| parse_to_packed(&text_str)));
    match result {
        Ok(blob) => match env.byte_array_from_slice(&blob) {
            Ok(arr) => arr.into_raw(),
            Err(e) => {
                log::error!("markdown-parser: byte_array_from_slice failed: {}", e);
                empty_array(&mut env)
            }
        },
        Err(panic) => {
            log::error!("markdown-parser: native panic: {:?}", jni_common::panic_to_string(&panic));
            empty_array(&mut env)
        }
    }
}

fn empty_array<'local>(env: &mut JNIEnv<'local>) -> jbyteArray {
    match env.new_byte_array(0) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn parse_to_packed(text: &str) -> Vec<u8> {
    let tree = tree_builder::build_tree(text);
    packed_ast::pack(&tree)
}

/// JNI entry for Component #8: `MarkdownParserNative.parseMarkdownToHtmlNative(text): String?`.
///
/// Single-pass markdown → HTML conversion via `pulldown_cmark::html::push_html`,
/// **intended** to replace the JVM `MarkdownNew.kt` HtmlGenerator hop. The
/// production caller has not yet been switched over; this is spike infrastructure.
///
/// Returns `null` on JString conversion failure / panic — Kotlin adapter then
/// falls back to the JVM HtmlGenerator path.
#[no_mangle]
pub extern "system" fn Java_app_amber_agent_ui_components_richtext_nativebridge_MarkdownParserNative_parseMarkdownToHtmlNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    text: JString<'local>,
) -> jni::sys::jstring {
    jni_common::init_logger_once!("RustMarkdownParser");

    let text_str: String = match env.get_string(&text) {
        Ok(s) => String::from(s),
        Err(e) => {
            log::error!("markdown-parser (to-html): failed to get JString: {}", e);
            return std::ptr::null_mut();
        }
    };

    let result = catch_unwind(AssertUnwindSafe(|| render_to_html(&text_str)));
    match result {
        Ok(html) => match env.new_string(html) {
            Ok(jstr) => jstr.into_raw(),
            Err(e) => {
                log::error!("markdown-parser (to-html): new_string failed: {}", e);
                std::ptr::null_mut()
            }
        },
        Err(panic) => {
            log::error!(
                "markdown-parser (to-html): native panic: {:?}",
                jni_common::panic_to_string(&panic)
            );
            std::ptr::null_mut()
        }
    }
}

/// Pure-Rust markdown → HTML emit. Uses the SAME `Options` set as
/// `tree_builder::make_options` so the packed-AST path and the HTML path
/// agree on which GFM extensions are enabled.
///
/// Two safety nets mirror JetBrains' GFMFlavourDescriptor flags:
/// - Bare HTTP/HTTPS URLs wrapped in `<...>` before parsing so pulldown-cmark
///   emits anchors (mirrors `makeHttpsAutoLinks = true`; Round 1 P1 fix).
/// - Post-processing strips dangerous URL schemes (`javascript:`, `data:`,
///   `vbscript:`, `file:`) to mirror `useSafeLinks = true`.
fn render_to_html(text: &str) -> String {
    use pulldown_cmark::{html, Parser};
    let pre = autolink_bare_urls(text);
    let parser = Parser::new_ext(&pre, markdown_options());
    let mut html_out = String::with_capacity(pre.len() + (pre.len() / 4));
    html::push_html(&mut html_out, parser);
    sanitize_dangerous_schemes(&html_out)
}

/// Wrap bare `https?://...` URLs in CommonMark autolink `<...>` syntax so
/// pulldown-cmark renders them as anchors. Skip URLs that are already
/// in `<...>` (autolink) or `[text](url)` (markdown link) or in code spans.
///
/// UTF-8 safe: copies untouched spans via string slicing rather than
/// byte-by-byte char cast (the byte-cast approach corrupts every multi-byte
/// code point — same regression we fixed in EpubParser::collapse_newlines).
fn autolink_bare_urls(text: &str) -> String {
    use std::sync::OnceLock;
    static URL_RE: OnceLock<regex::Regex> = OnceLock::new();
    let re = URL_RE.get_or_init(|| {
        regex::Regex::new(r#"https?://[^\s<>\)\]\}]+"#).expect("autolink regex compiles")
    });

    // Find spans to leave alone (already-linked / code blocks). We collect
    // byte ranges that should NOT be matched against the URL regex; anything
    // outside these ranges may have its URLs wrapped.
    let skip_ranges = collect_skip_ranges(text);

    let mut out = String::with_capacity(text.len() + 16);
    let mut cursor = 0usize;
    for mat in re.find_iter(text) {
        if mat.start() < cursor {
            // Overlapping previous match (shouldn't happen with non-overlapping iter)
            continue;
        }
        if in_any_range(&skip_ranges, mat.start()) {
            // Inside a skip span — leave URL alone. Copy through unchanged.
            continue;
        }
        // Copy text between cursor and match start verbatim (slice is UTF-8 safe)
        out.push_str(&text[cursor..mat.start()]);
        let raw = mat.as_str();
        let trimmed = raw.trim_end_matches(|c: char| {
            matches!(c, '.' | ',' | ';' | ':' | '!' | '?')
        });
        out.push('<');
        out.push_str(trimmed);
        out.push('>');
        cursor = mat.start() + trimmed.len();
        // Don't push back stripped punctuation here — it's part of the original
        // text past `cursor` and the next iteration / final copy will emit it.
    }
    out.push_str(&text[cursor..]);
    out
}

/// Byte ranges that should be left alone when wrapping bare URLs:
/// - `<...>` autolinks
/// - `[text](url)` markdown links
/// - `` `...` `` inline code
/// - ```` ```...``` ```` fenced code blocks
fn collect_skip_ranges(text: &str) -> Vec<(usize, usize)> {
    let mut ranges: Vec<(usize, usize)> = Vec::new();
    let bytes = text.as_bytes();

    // Fenced code blocks at start of line: find pairs of ``` on their own line.
    let mut line_start = 0usize;
    let mut in_fenced = false;
    let mut fence_open_byte = 0usize;
    for i in 0..bytes.len() {
        let is_line_end = bytes[i] == b'\n';
        if is_line_end || i == bytes.len() - 1 {
            let line_end = if is_line_end { i } else { i + 1 };
            let line = &text[line_start..line_end];
            if line.trim_start().starts_with("```") {
                if !in_fenced {
                    in_fenced = true;
                    fence_open_byte = line_start;
                } else {
                    in_fenced = false;
                    ranges.push((fence_open_byte, line_end));
                }
            }
            line_start = i + 1;
        }
    }

    // Inline code spans `...` and `` ... ``  — naïve but enough to skip URLs
    // inside `code https://...` segments. Pair odd/even backticks.
    let mut tick_starts: Vec<usize> = Vec::new();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'`' {
            // Count consecutive backticks
            let run_start = i;
            while i < bytes.len() && bytes[i] == b'`' {
                i += 1;
            }
            tick_starts.push(run_start);
        } else {
            i += 1;
        }
    }
    // Pair up tick_starts greedily
    let mut idx = 0;
    while idx + 1 < tick_starts.len() {
        ranges.push((tick_starts[idx], tick_starts[idx + 1] + 1));
        idx += 2;
    }

    // `<url>` autolinks and `[text](url)` markdown links — find via regex
    use std::sync::OnceLock;
    static SKIP_RE: OnceLock<regex::Regex> = OnceLock::new();
    let skip_re = SKIP_RE.get_or_init(|| {
        regex::Regex::new(r#"<[^>]*>|\[[^\]]*\]\([^\)]*\)"#).expect("skip regex compiles")
    });
    for mat in skip_re.find_iter(text) {
        ranges.push((mat.start(), mat.end()));
    }

    ranges.sort_by_key(|r| r.0);
    ranges
}

fn in_any_range(ranges: &[(usize, usize)], pos: usize) -> bool {
    ranges.iter().any(|&(start, end)| pos >= start && pos < end)
}

/// Replace `href="javascript:..."` / `data:` / `vbscript:` / `file:` with `href="#"`
/// (and same for `src=`) to mirror JetBrains' `useSafeLinks = true`.
fn sanitize_dangerous_schemes(html: &str) -> String {
    use std::sync::OnceLock;
    static SAFE_LINK_RE: OnceLock<regex::Regex> = OnceLock::new();
    let re = SAFE_LINK_RE.get_or_init(|| {
        // `(?i)` for case-insensitive; `[^"']*` for URL value; `["']` for either quote style.
        regex::Regex::new(
            r#"(?i)(href|src)\s*=\s*(["'])(?:javascript:|data:|vbscript:|file:)[^"']*(["'])"#,
        )
        .expect("safe-link regex compiles")
    });
    re.replace_all(html, |caps: &regex::Captures| {
        format!("{}={}#{}", &caps[1], &caps[2], &caps[3])
    })
    .into_owned()
}


#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sanity_empty_input() {
        let out = parse_to_packed("");
        // Header still emitted even for empty input
        assert!(out.len() >= 8, "header must be present");
        assert_eq!(&out[..4], b"PMDA");
    }

    #[test]
    fn parses_paragraph() {
        let out = parse_to_packed("hello world");
        assert!(out.len() > 8);
        assert_eq!(&out[..4], b"PMDA");
    }

    #[test]
    fn html_renders_paragraph() {
        let html = render_to_html("hello");
        assert!(html.contains("<p>hello</p>"), "got: {}", html);
    }

    #[test]
    fn html_renders_empty_input() {
        let html = render_to_html("");
        assert_eq!(html, "");
    }

    /// JetBrains' makeHttpsAutoLinks converts bare URLs to anchors. With
    /// `ENABLE_GFM` we get the same behaviour from pulldown-cmark.
    /// Round 1 P1 fix verification.
    #[test]
    fn html_autolinks_bare_url() {
        let html = render_to_html("visit https://example.com today");
        // Either `<a href="https://example.com">https://example.com</a>` or
        // a similar GFM autolink wrapping. We assert the URL becomes an anchor.
        assert!(
            html.contains("<a href=\"https://example.com"),
            "expected autolinked URL, got: {}",
            html
        );
    }

    /// useSafeLinks=true in JetBrains strips dangerous schemes. Round 1 P1 fix.
    #[test]
    fn html_strips_javascript_href() {
        let html = render_to_html("[click](javascript:alert(1))");
        assert!(
            !html.contains("javascript:"),
            "javascript: scheme not stripped: {}",
            html
        );
        assert!(html.contains("href=\"#\""), "expected sanitized href: {}", html);
    }

    #[test]
    fn html_strips_data_uri_href() {
        let html = render_to_html("[click](data:text/html,<script>)");
        assert!(!html.contains("data:"), "data: scheme not stripped: {}", html);
    }

    #[test]
    fn html_passes_through_safe_https() {
        let html = render_to_html("[ok](https://example.com)");
        assert!(html.contains("href=\"https://example.com\""), "got: {}", html);
    }

    #[test]
    fn html_preserves_cjk() {
        let html = render_to_html("你好世界");
        assert!(html.contains("你好世界"), "got: {}", html);
    }
}
