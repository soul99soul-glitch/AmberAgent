//! markdown-parser: Rust replacement for `app/.../richtext/Markdown.kt`'s
//! `parser.buildMarkdownTreeFromString(text)` call (JetBrains markdown lib).
//!
//! Strategy:
//!   - pulldown-cmark events → tree IR → packed binary
//!   - JNI returns ByteArray; Kotlin side has `PackedAstNode` lazy view that
//!     mimics the org.intellij.markdown.ast.ASTNode interface
//!
//! See `docs/RUST_NATIVE_SPIKE_PLAN.md` §4 for full design.

mod packed_ast;
mod tree_builder;
mod type_mapping;

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JByteArray, JClass, JString};
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

    let text_str = match env.get_string(&text) {
        Ok(s) => s.into(),
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
