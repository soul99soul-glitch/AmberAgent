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
pub extern "system" fn Java_me_rerere_rikkahub_ui_components_richtext_nativebridge_MarkdownParserNative_parseMarkdownNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    text: JString<'local>,
) -> jbyteArray {
    init_logger_once();

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
            log::error!("markdown-parser: native panic: {:?}", panic_to_string(&panic));
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
/// Single-pass markdown → HTML conversion via `pulldown_cmark::html::push_html`.
/// Replaces the JVM `MarkdownNew.kt` path which currently does:
///   text → JetBrains MarkdownParser → HtmlGenerator → Jsoup-like reparse → Compose
/// We collapse the first two steps into one Rust call so MarkdownNew only needs
/// to do `Jsoup.parse(html)` once instead of running both a JVM markdown parser
/// AND a JVM html generator.
///
/// Returns `null` on JString conversion failure / panic — Kotlin adapter then
/// falls back to the JVM HtmlGenerator path.
#[no_mangle]
pub extern "system" fn Java_me_rerere_rikkahub_ui_components_richtext_nativebridge_MarkdownParserNative_parseMarkdownToHtmlNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    text: JString<'local>,
) -> jni::sys::jstring {
    init_logger_once();

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
                panic_to_string(&panic)
            );
            std::ptr::null_mut()
        }
    }
}

/// Pure-Rust markdown → HTML emit. Uses the SAME `Options` set as
/// `tree_builder::make_options` so the packed-AST path and the HTML path
/// agree on which GFM extensions are enabled.
fn render_to_html(text: &str) -> String {
    use pulldown_cmark::{html, Options, Parser};
    let mut opts = Options::empty();
    opts.insert(Options::ENABLE_TABLES);
    opts.insert(Options::ENABLE_FOOTNOTES);
    opts.insert(Options::ENABLE_STRIKETHROUGH);
    opts.insert(Options::ENABLE_TASKLISTS);
    opts.insert(Options::ENABLE_HEADING_ATTRIBUTES);
    opts.insert(Options::ENABLE_MATH);
    let parser = Parser::new_ext(text, opts);
    let mut html_out = String::with_capacity(text.len() + (text.len() / 4));
    html::push_html(&mut html_out, parser);
    html_out
}

fn panic_to_string(payload: &Box<dyn std::any::Any + Send>) -> String {
    if let Some(s) = payload.downcast_ref::<&'static str>() {
        (*s).to_string()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        "non-string panic payload".to_string()
    }
}

fn init_logger_once() {
    use std::sync::Once;
    static INIT: Once = Once::new();
    INIT.call_once(|| {
        #[cfg(target_os = "android")]
        {
            android_logger::init_once(
                android_logger::Config::default()
                    .with_max_level(log::LevelFilter::Info)
                    .with_tag("RustMarkdownParser"),
            );
        }
    });
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
}
