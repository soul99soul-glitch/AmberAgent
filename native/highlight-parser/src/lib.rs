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
use std::sync::OnceLock;

use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::{jbyteArray, jobjectArray};
use jni::JNIEnv;

/// JNI entry: `HighlighterNative.highlightNative(code, language): ByteArray`.
#[no_mangle]
pub extern "system" fn Java_me_rerere_highlight_nativebridge_HighlighterNative_highlightNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    code: JString<'local>,
    language: JString<'local>,
) -> jbyteArray {
    init_logger_once();

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
                panic_to_string(&panic)
            );
            empty_array(&mut env)
        }
    }
}

/// JNI entry: `HighlighterNative.supportedLanguages(): Array<String>`.
#[no_mangle]
pub extern "system" fn Java_me_rerere_highlight_nativebridge_HighlighterNative_supportedLanguages<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jobjectArray {
    let langs = grammars::supported_languages();
    let string_class = match env.find_class("java/lang/String") {
        Ok(c) => c,
        Err(_) => return std::ptr::null_mut(),
    };
    let arr: JObjectArray = match env.new_object_array(
        langs.len() as i32,
        string_class,
        unsafe { jni::objects::JObject::from_raw(std::ptr::null_mut()) },
    ) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };
    for (i, lang) in langs.iter().enumerate() {
        if let Ok(s) = env.new_string(lang) {
            let _ = env.set_object_array_element(&arr, i as i32, s);
        }
    }
    arr.into_raw()
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
    static INIT: OnceLock<()> = OnceLock::new();
    INIT.get_or_init(|| {
        #[cfg(target_os = "android")]
        {
            android_logger::init_once(
                android_logger::Config::default()
                    .with_max_level(log::LevelFilter::Info)
                    .with_tag("RustHighlightParser"),
            );
        }
    });
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
