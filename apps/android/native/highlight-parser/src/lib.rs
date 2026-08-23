//! highlight-parser: Rust replacement for `highlight/Highlighter.kt`'s
//! QuickJS + Prism JS-engine pipeline.
//!
//! Strategy:
//!   - 14 bundled tree-sitter grammars (see Cargo.toml)
//!   - tree-sitter-highlight emits typed events; we serialize to packed binary
//!   - Kotlin adapter decodes to existing `HighlightToken` sealed class
//!
//! See `docs/RUST_NATIVE_SPIKE_PLAN.md` §5 for design.

mod grammars;
mod packed_tokens;
mod scope_mapping;

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JClass, JString};
use jni::sys::jbyteArray;
use jni::JNIEnv;

/// JNI entry: `HighlighterNative.highlightNative(code, language): ByteArray`.
#[no_mangle]
pub extern "system" fn Java_app_amber_highlight_nativebridge_HighlighterNative_highlightNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    code: JString<'local>,
    language: JString<'local>,
) -> jbyteArray {
    jni_common::init_logger_once!("RustHighlightParser");

    let code_str: String = match env.get_string(&code) {
        Ok(s) => s.into(),
        Err(e) => {
            log::error!("highlight-parser: failed to get code JString: {}", e);
            return empty_array(&mut env);
        }
    };
    let lang_str: String = match env.get_string(&language) {
        Ok(s) => s.into(),
        Err(e) => {
            log::error!("highlight-parser: failed to get language JString: {}", e);
            return empty_array(&mut env);
        }
    };

    let result = catch_unwind(AssertUnwindSafe(|| highlight_to_packed(&code_str, &lang_str)));
    match result {
        Ok(blob) => match env.byte_array_from_slice(&blob) {
            Ok(arr) => arr.into_raw(),
            Err(e) => {
                log::error!("highlight-parser: byte_array_from_slice failed: {}", e);
                empty_array(&mut env)
            }
        },
        Err(panic) => {
            log::error!(
                "highlight-parser: native panic: {:?}",
                jni_common::panic_to_string(&panic)
            );
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

fn highlight_to_packed(code: &str, language: &str) -> Vec<u8> {
    let grammar = match grammars::for_language(language) {
        Some(g) => g,
        None => {
            // Unknown language → emit "all Plain" so the renderer just shows
            // monospace text without highlighting (graceful degradation).
            return packed_tokens::pack_plain_only(code);
        }
    };

    match grammars::run_highlight(grammar, code) {
        Ok(events) => packed_tokens::pack(code, events),
        Err(e) => {
            log::warn!(
                "highlight-parser: highlight failed for {}: {} — falling back to plain",
                language,
                e
            );
            packed_tokens::pack_plain_only(code)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unknown_language_returns_plain_blob() {
        let out = highlight_to_packed("foo bar", "esoteric-lang");
        assert!(out.len() >= 8);
        assert_eq!(&out[..4], b"PHLT");
    }

    #[test]
    fn rust_highlight_emits_more_than_plain() {
        let code = "fn main() { let x = 42; }";
        let out = highlight_to_packed(code, "rust");
        // Should be more than just a single Plain token
        assert!(out.len() > 16);
        assert_eq!(&out[..4], b"PHLT");
    }
}
