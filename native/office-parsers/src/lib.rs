//! office-parsers: Rust replacement for `document/DocxParser.kt` + `document/PptxParser.kt`.
//!
//! JNI entry points return a single [`String`]. Output format intentionally
//! matches the JVM implementation byte-for-byte (Markdown-style: `# H1`,
//! `**bold**`, `- item`, `| col | col |`, etc.) so the rest of the app does
//! not need to change.
//!
//! Errors are returned as a sentinel string `"Error parsing ... file: <reason>"`
//! mirroring JVM behavior — the Kotlin adapter inspects this prefix when
//! deciding whether to fall back to the JVM parser.

mod docx;
mod pptx;
mod epub;
mod xlsx;
mod error;

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

/// Convert a Java `String` argument into an owned Rust `String`.
fn jstring_to_rust(env: &mut JNIEnv, s: JString) -> Result<String, jni::errors::Error> {
    let java_str = env.get_string(&s)?;
    Ok(java_str.into())
}

/// Wrap a Rust `String` into a Java string and return its `jstring` raw pointer.
///
/// On allocation failure (extremely rare — only when the JVM is out of memory)
/// we fall back to a static ASCII sentinel rather than returning `null_mut()`,
/// because the Kotlin adapter accepts a nullable `String?` and treats `null`
/// as "use JVM fallback". The sentinel value lets callers see what went wrong
/// without crashing.
fn rust_to_jstring(env: &mut JNIEnv, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(jstr) => jstr.into_raw(),
        Err(_) => match env.new_string("Error parsing file: native jstring allocation failed") {
            Ok(jstr) => jstr.into_raw(),
            // If even the fallback fails the JVM is in deep trouble; null
            // signals to Kotlin that fallback is required.
            Err(_) => std::ptr::null_mut(),
        },
    }
}

/// Top-level helper: dispatches to the supplied closure and signals panic vs.
/// soft-error distinctly back to the JNI caller.
///
/// - Closure returns a String (potentially the JVM-compatible
///   "Error parsing ..." sentinel for soft errors like IO / zip / xml): we
///   pass that String through as the success value, matching the legacy JVM
///   contract.
/// - Closure panics: we return `null` (`std::ptr::null_mut()`) so the
///   Kotlin adapter sees `parseDocxNative(...) == null`, downgrades to
///   `Result.NativeUnavailable`, and the Switch routes the event through
///   `cfg.onNativePanic` + JVM fallback (Codex review P1-1). Previously the
///   panic string was returned as a success value, so the caller silently
///   showed `"Error parsing DOCX file: native panic — ..."` to the user
///   instead of falling back.
fn safe_parse<F>(env: &mut JNIEnv, kind: &str, f: F) -> jstring
where
    F: FnOnce() -> String,
{
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(s) => rust_to_jstring(env, &s),
        Err(panic_payload) => {
            let msg = jni_common::panic_to_string(&panic_payload);
            log::error!("native {} parser panicked: {}", kind, msg);
            std::ptr::null_mut()
        }
    }
}

/// JNI entry: `OfficeParserNative.parseDocxNative(path: String): String`.
///
/// Matches JVM `DocxParser.parse(file: File): String`. Output is markdown-flavoured
/// plain text with structural markers (`# heading`, `- list`, `**bold**`, table pipes).
///
/// # Safety
/// Standard JNI signature; receives an environment pointer and arguments owned
/// by the JVM. All Rust panics are caught.
#[no_mangle]
pub extern "system" fn Java_me_rerere_document_nativebridge_OfficeParserNative_parseDocxNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jstring {
    jni_common::init_logger_once!("RustOfficeParsers");
    let path_str = match jstring_to_rust(&mut env, path) {
        Ok(p) => p,
        Err(e) => return rust_to_jstring(&mut env, &format!("Error parsing DOCX file: bad path — {}", e)),
    };
    safe_parse(&mut env, "DOCX", || docx::parse_to_markdown(&path_str))
}

/// JNI entry: `OfficeParserNative.parsePptxNative(path: String): String`.
#[no_mangle]
pub extern "system" fn Java_me_rerere_document_nativebridge_OfficeParserNative_parsePptxNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jstring {
    jni_common::init_logger_once!("RustOfficeParsers");
    let path_str = match jstring_to_rust(&mut env, path) {
        Ok(p) => p,
        Err(e) => return rust_to_jstring(&mut env, &format!("Error parsing PPTX file: bad path — {}", e)),
    };
    safe_parse(&mut env, "PPTX", || pptx::parse_to_markdown(&path_str))
}

/// JNI entry: `OfficeParserNative.parseEpubNative(path: String): String`.
#[no_mangle]
pub extern "system" fn Java_me_rerere_document_nativebridge_OfficeParserNative_parseEpubNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jstring {
    jni_common::init_logger_once!("RustOfficeParsers");
    let path_str = match jstring_to_rust(&mut env, path) {
        Ok(p) => p,
        Err(e) => return rust_to_jstring(&mut env, &format!("Error parsing EPUB file: bad path — {}", e)),
    };
    safe_parse(&mut env, "EPUB", || epub::parse_to_markdown(&path_str))
}

/// JNI entry: `OfficeParserNative.parseXlsxNative(path: String): String`.
///
/// Phase 3 D-1 — net-new (no JVM `XlsxParser` exists). Markdown-pipe-table
/// representation of every sheet, see `xlsx::parse_to_markdown`.
#[no_mangle]
pub extern "system" fn Java_me_rerere_document_nativebridge_OfficeParserNative_parseXlsxNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jstring {
    jni_common::init_logger_once!("RustOfficeParsers");
    let path_str = match jstring_to_rust(&mut env, path) {
        Ok(p) => p,
        Err(e) => return rust_to_jstring(&mut env, &format!("Error parsing XLSX file: bad path — {}", e)),
    };
    safe_parse(&mut env, "XLSX", || xlsx::parse_to_markdown(&path_str))
}

#[cfg(test)]
mod tests {
    // Integration tests live alongside corpus fixtures in
    // `native/office-parsers/tests/corpus/` and `tests/equivalence.rs`.
    // The library-level lib.rs only exercises JNI plumbing helpers when
    // possible; the JNI entry symbols cannot be invoked from host tests
    // because they require a live JVM. cargo-ndk + Gradle drives the
    // device-side execution.
    // Integration test coverage now lives in each module's own #[cfg(test)]
    // block (docx::tests, pptx::tests, epub::tests). The 2+2 sanity test
    // that lived here was dropped in the P3 sweep.
}
