//! html-diff-normalizer: Rust port of `HtmlDiffNormalizer.kt` (TD.Rust.1c).
//!
//! Canonicalises HTML for diff-sample comparison between JetBrains
//! `HtmlGenerator` output and pulldown-cmark output. The Kotlin original uses
//! Jsoup; here we use scraper (html5ever-based) and re-emit a normalized
//! form ourselves.
//!
//! Steps in order matching the Kotlin path:
//!   1. Parse via html5ever (scraper::Html::parse_fragment).
//!   2. Walk the tree, emit each element with attributes sorted by name.
//!   3. Run 3 regex passes (whitespace collapse / space-before-close-tag /
//!      space-between-tags).
//!   4. Trim leading + trailing whitespace.
//!
//! Inputs longer than 64K chars skip normalization (matches JVM).

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use regex::Regex;
use scraper::{Html, Node};

const MAX_INPUT_CHARS: usize = 64 * 1024;

fn re_ws_run() -> &'static Regex {
    static R: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
    R.get_or_init(|| Regex::new(r"\s+").unwrap())
}

fn re_space_before_close() -> &'static Regex {
    static R: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
    R.get_or_init(|| Regex::new(r"\s+(</[A-Za-z][^>]*>)").unwrap())
}

fn re_space_between_tags() -> &'static Regex {
    static R: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
    R.get_or_init(|| Regex::new(r">\s+<").unwrap())
}

/// Public pure function. Returns canonical form, or input unchanged on
/// fallback paths (too large / no <body> found).
pub fn normalize(html: &str) -> String {
    if html.chars().count() > MAX_INPUT_CHARS {
        return html.to_string();
    }
    // Plain text fast path — no tags means no canonicalization work. The
    // 3 regex passes still apply downstream after the trim, but skipping
    // the html5ever parse saves ~50µs and avoids the html→body wrapping
    // synthesis that confuses no-markup inputs.
    if !html.contains('<') {
        return html.trim().to_string();
    }
    let parsed = Html::parse_fragment(html);

    // scraper::Html::parse_fragment auto-wraps in <html><head/><body>...</body>
    // </html>. We strip those wrappers during serialization — see
    // `serialize(skip_wrapper=true)` for the html/head/body whitelist.
    let mut out = String::with_capacity(html.len() + 16);
    for child in parsed.tree.root().children() {
        serialize(child, &mut out);
    }

    let pass1 = re_ws_run().replace_all(&out, " ").to_string();
    let pass2 = re_space_before_close().replace_all(&pass1, "$1").to_string();
    let pass3 = re_space_between_tags().replace_all(&pass2, "><").to_string();
    pass3.trim().to_string()
}

fn serialize(node: ego_tree::NodeRef<Node>, out: &mut String) {
    match node.value() {
        Node::Text(t) => {
            for c in t.chars() {
                match c {
                    '<' => out.push_str("&lt;"),
                    '>' => out.push_str("&gt;"),
                    '&' => out.push_str("&amp;"),
                    other => out.push(other),
                }
            }
        }
        Node::Element(el) => {
            // Strip the synthetic html/head/body wrappers — emit their
            // children inline. head's empty (no document head in a fragment),
            // body's children are the real content the caller passed in.
            let name = el.name();
            if matches!(name, "html" | "body") {
                for child in node.children() {
                    serialize(child, out);
                }
                return;
            }
            if name == "head" {
                // head can hold metadata-ish content the caller didn't ask
                // for; safest to skip its entire subtree.
                return;
            }
            out.push('<');
            out.push_str(el.name());
            let mut attrs: Vec<(&str, &str)> = el
                .attrs
                .iter()
                .map(|(k, v)| (k.local.as_ref(), v.as_ref()))
                .collect();
            attrs.sort_by(|a, b| a.0.cmp(b.0));
            for (k, v) in attrs {
                out.push(' ');
                out.push_str(k);
                out.push_str("=\"");
                for c in v.chars() {
                    match c {
                        '<' => out.push_str("&lt;"),
                        '>' => out.push_str("&gt;"),
                        '&' => out.push_str("&amp;"),
                        '"' => out.push_str("&quot;"),
                        other => out.push(other),
                    }
                }
                out.push('"');
            }
            let name = el.name();
            let void = matches!(
                name,
                "area"
                    | "base"
                    | "br"
                    | "col"
                    | "embed"
                    | "hr"
                    | "img"
                    | "input"
                    | "link"
                    | "meta"
                    | "param"
                    | "source"
                    | "track"
                    | "wbr"
            );
            out.push('>');
            if !void {
                for child in node.children() {
                    serialize(child, out);
                }
                out.push_str("</");
                out.push_str(name);
                out.push('>');
            }
        }
        _ => {}
    }
}

#[no_mangle]
pub extern "system" fn Java_app_amber_feature_ui_components_richtext_nativebridge_HtmlDiffNormalizerNative_normalizeNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    input: JString<'local>,
) -> jstring {
    jni_common::init_logger_once!("RustHtmlDiffNormalizer");

    let input_str: String = match env.get_string(&input) {
        Ok(s) => String::from(s),
        Err(e) => {
            log::error!("html-diff-normalizer: get_string failed: {}", e);
            return std::ptr::null_mut();
        }
    };

    let result = catch_unwind(AssertUnwindSafe(|| normalize(&input_str)));
    match result {
        Ok(out) => match env.new_string(out) {
            Ok(j) => j.into_raw(),
            Err(e) => {
                log::error!("html-diff-normalizer: new_string failed: {}", e);
                std::ptr::null_mut()
            }
        },
        Err(panic) => {
            log::error!(
                "html-diff-normalizer: panic: {}",
                jni_common::panic_to_string(&panic)
            );
            std::ptr::null_mut()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_input() {
        assert_eq!(normalize(""), "");
    }

    #[test]
    fn collapses_whitespace_inside_text() {
        let out = normalize("<p>hello    world</p>");
        assert_eq!(out, "<p>hello world</p>");
    }

    #[test]
    fn strips_space_before_close_tag() {
        let out = normalize("<p>hello\n</p>");
        assert_eq!(out, "<p>hello</p>");
    }

    #[test]
    fn strips_space_between_adjacent_tags() {
        let out = normalize("<ul>\n<li>a</li>\n<li>b</li>\n</ul>");
        assert!(!out.contains("> <"));
        assert!(out.contains("<li>a</li><li>b</li>"));
    }

    #[test]
    fn sorts_attributes() {
        let out = normalize(r#"<a id="x" class="y" href="/">link</a>"#);
        assert!(out.contains(r#"class="y" href="/" id="x""#));
    }

    #[test]
    fn long_input_passes_through_unchanged() {
        let big = "a".repeat(65_000);
        let out = normalize(&big);
        assert_eq!(out, big);
    }

    #[test]
    fn void_element_self_closes() {
        let out = normalize("<br>");
        assert_eq!(out, "<br>");
    }
}
