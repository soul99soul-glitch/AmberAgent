//! amber-ffi: C-ABI FFI export layer for all Amber Rust native crates.
//!
//! This crate unifies the 10 component crates into a single static/cdylib
//! that can be consumed by both Swift (via XCFramework) and Kotlin/Native.
//!
//! ## Memory convention
//!
//! - Input strings: `*const c_char` (UTF-8, caller-owned, not freed by us)
//! - Output strings: `*mut c_char` (UTF-8, allocated by us, caller frees
//!   with [`amber_free_string`])
//! - Binary input: `*const u8` + `len: usize`
//! - Binary output: `*mut u8` + `*mut usize` for output length (caller
//!   frees with [`amber_free_bytes`])
//!
//! All functions that return a pointer return null on error.

use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::panic::catch_unwind;

// ===========================================================================
// Memory management
// ===========================================================================

/// Free a string previously returned by any amber FFI function.
/// Passing null is safe (no-op).
#[no_mangle]
pub extern "C" fn amber_free_string(s: *mut c_char) {
    if !s.is_null() {
        unsafe {
            drop(CString::from_raw(s));
        }
    }
}

/// Free a byte buffer previously returned by any amber FFI function.
/// Passing null data is safe (no-op). `len` must be the length returned
/// alongside the pointer.
#[no_mangle]
pub extern "C" fn amber_free_bytes(data: *mut u8, len: usize) {
    if !data.is_null() {
        unsafe {
            let slice = std::ptr::slice_from_raw_parts_mut(data, len);
            drop(Box::from_raw(slice));
        }
    }
}

// ===========================================================================
// Helpers
// ===========================================================================

/// Convert a C string pointer to a Rust &str. Returns empty string on null.
fn cstr_to_str<'a>(ptr: *const c_char) -> &'a str {
    if ptr.is_null() {
        return "";
    }
    unsafe { CStr::from_ptr(ptr) }.to_str().unwrap_or("")
}

/// Convert a Rust String to a C-allocated string. Returns null on empty error.
fn str_to_cstring(s: String) -> *mut c_char {
    match CString::new(s) {
        Ok(cs) => cs.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Allocate a byte Vec as a C-allocated buffer + length out-parameter.
fn vec_to_cbytes(v: Vec<u8>, out_len: *mut usize) -> *mut u8 {
    if v.is_empty() {
        unsafe { *out_len = 0 };
        return std::ptr::null_mut();
    }
    let bytes = v.into_boxed_slice();
    let len = bytes.len();
    let ptr = Box::into_raw(bytes) as *mut u8;
    unsafe { *out_len = len };
    ptr
}

/// Wrap a closure in catch_unwind. On panic, returns null.
fn ffi_catch<T>(f: impl FnOnce() -> T) -> Option<T> {
    match catch_unwind(std::panic::AssertUnwindSafe(f)) {
        Ok(v) => Some(v),
        Err(_) => None,
    }
}

// ===========================================================================
// Markdown Parser
// ===========================================================================

/// Parse markdown text to packed binary AST blob.
/// Returns allocated bytes via (ptr, out_len). Caller frees with amber_free_bytes.
/// Returns null ptr on error.
#[no_mangle]
pub extern "C" fn amber_markdown_parse(
    text: *const c_char,
    out_len: *mut usize,
) -> *mut u8 {
    let text = cstr_to_str(text);
    ffi_catch(|| {
        let tree = markdown_parser::build_tree(text);
        let packed = markdown_parser::pack(&tree);
        vec_to_cbytes(packed, out_len)
    })
    .unwrap_or_else(|| {
        unsafe { *out_len = 0 };
        std::ptr::null_mut()
    })
}

/// Parse markdown text to HTML string.
/// Returns allocated C string. Caller frees with amber_free_string.
/// Returns null on error.
#[no_mangle]
pub extern "C" fn amber_markdown_to_html(
    text: *const c_char,
) -> *mut c_char {
    let text = cstr_to_str(text);
    ffi_catch(|| str_to_cstring(markdown_parser::render_to_html(text)))
        .unwrap_or(std::ptr::null_mut())
}

// ===========================================================================
// Markdown Preprocess
// ===========================================================================

/// Preprocess markdown (LaTeX conversion, bare URL linkify).
/// Returns allocated C string. Caller frees with amber_free_string.
/// Returns null on error.
#[no_mangle]
pub extern "C" fn amber_markdown_preprocess(
    input: *const c_char,
) -> *mut c_char {
    let input = cstr_to_str(input);
    ffi_catch(|| str_to_cstring(markdown_preprocess::preprocess(input)))
        .unwrap_or(std::ptr::null_mut())
}

// ===========================================================================
// Highlight Parser
// ===========================================================================

/// Syntax-highlight code for the given language, returning packed binary token blob.
/// Returns allocated bytes via (ptr, out_len). Caller frees with amber_free_bytes.
/// Returns null ptr on error.
#[no_mangle]
pub extern "C" fn amber_highlight(
    code: *const c_char,
    language: *const c_char,
    out_len: *mut usize,
) -> *mut u8 {
    let code = cstr_to_str(code);
    let language = cstr_to_str(language);
    ffi_catch(|| {
        let packed = highlight_parser::highlight_to_packed(code, language);
        vec_to_cbytes(packed, out_len)
    })
    .unwrap_or_else(|| {
        unsafe { *out_len = 0 };
        std::ptr::null_mut()
    })
}

/// Get the list of supported language identifiers.
/// Returns a null-terminated C string with language names separated by `\n`.
/// Returns allocated C string. Caller frees with amber_free_string.
/// Returns null on error.
#[no_mangle]
pub extern "C" fn amber_highlight_supported_languages() -> *mut c_char {
    ffi_catch(|| {
        let langs = highlight_parser::supported_languages();
        let joined = langs.join("\n");
        str_to_cstring(joined)
    })
    .unwrap_or(std::ptr::null_mut())
}

// ===========================================================================
// HTML Diff Normalizer
// ===========================================================================

/// Normalize HTML for diff comparison.
/// Returns allocated C string. Caller frees with amber_free_string.
/// Returns null on error.
#[no_mangle]
pub extern "C" fn amber_html_diff_normalize(
    html: *const c_char,
) -> *mut c_char {
    let html = cstr_to_str(html);
    ffi_catch(|| str_to_cstring(html_diff_normalizer::normalize(html)))
        .unwrap_or(std::ptr::null_mut())
}

// ===========================================================================
// Reader Extractor
// ===========================================================================

/// Extract readable article from HTML.
///
/// Output is a JSON string with fields: title, content_html, content_text, section_count.
/// Returns allocated C string. Caller frees with amber_free_string.
/// Returns null on error.
#[no_mangle]
pub extern "C" fn amber_reader_extract(
    html: *const c_char,
    base_url: *const c_char,
) -> *mut c_char {
    let html = cstr_to_str(html);
    let base_url = cstr_to_str(base_url);
    ffi_catch(|| {
        match reader_extractor::extract_article(html, base_url) {
            Ok(article) => {
                // Return as JSON so Swift can parse it
                let json = format!(
                    r#"{{"title":"{}","content_html":"{}","content_text":"{}","section_count":{}}}"#,
                    json_escape(&article.title),
                    json_escape(&article.content),
                    json_escape(&article.text),
                    article.section_count,
                );
                str_to_cstring(json)
            }
            Err(_) => std::ptr::null_mut(),
        }
    })
    .unwrap_or(std::ptr::null_mut())
}

/// Escape a string for embedding in JSON.
fn json_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c.is_control() => {
                out.push_str(&format!("\\u{:04x}", c as u32));
            }
            other => out.push(other),
        }
    }
    out
}

// ===========================================================================
// Office Parsers
// ===========================================================================

/// Parse a DOCX file to markdown.
/// `path` is a UTF-8 file path.
/// Returns allocated C string (markdown content or error sentinel).
/// Caller frees with amber_free_string. Returns null on error.
#[no_mangle]
pub extern "C" fn amber_parse_docx(
    path: *const c_char,
) -> *mut c_char {
    let path = cstr_to_str(path);
    ffi_catch(|| {
        // office-parsers modules are private; we call the internal function
        // indirectly. The crate exposes its functionality via JNI only,
        // so we need to use the internal modules.
        let result = office_parsers::docx::parse_to_markdown(path);
        str_to_cstring(result)
    })
    .unwrap_or(std::ptr::null_mut())
}

/// Parse a PPTX file to markdown.
#[no_mangle]
pub extern "C" fn amber_parse_pptx(
    path: *const c_char,
) -> *mut c_char {
    let path = cstr_to_str(path);
    ffi_catch(|| {
        let result = office_parsers::pptx::parse_to_markdown(path);
        str_to_cstring(result)
    })
    .unwrap_or(std::ptr::null_mut())
}

/// Parse an EPUB file to markdown.
#[no_mangle]
pub extern "C" fn amber_parse_epub(
    path: *const c_char,
) -> *mut c_char {
    let path = cstr_to_str(path);
    ffi_catch(|| {
        let result = office_parsers::epub::parse_to_markdown(path);
        str_to_cstring(result)
    })
    .unwrap_or(std::ptr::null_mut())
}

/// Parse an XLSX file to markdown.
#[no_mangle]
pub extern "C" fn amber_parse_xlsx(
    path: *const c_char,
) -> *mut c_char {
    let path = cstr_to_str(path);
    ffi_catch(|| {
        let result = office_parsers::xlsx::parse_to_markdown(path);
        str_to_cstring(result)
    })
    .unwrap_or(std::ptr::null_mut())
}

// ===========================================================================
// Regex Transformer
// ===========================================================================

/// Apply a pipeline of regex find-replace rules sequentially.
///
/// `find_patterns` and `replacements` are null-terminated C string arrays
/// (each element is a C string, terminated by a null pointer). They must
/// have the same number of elements.
///
/// Returns allocated C string. Caller frees with amber_free_string.
/// Returns null on error.
#[no_mangle]
pub extern "C" fn amber_regex_apply(
    input: *const c_char,
    find_patterns: *const *const c_char,
    replacements: *const *const c_char,
) -> *mut c_char {
    let input = cstr_to_str(input);
    ffi_catch(|| {
        let finds = unsafe { cstr_array_to_vec(find_patterns) };
        let reps = unsafe { cstr_array_to_vec(replacements) };
        let rules: Vec<(&str, &str)> = finds.iter().zip(reps.iter())
            .map(|(f, r)| (f.as_str(), r.as_str()))
            .collect();
        let result = regex_transformer::apply_regex_pipeline(input, &rules);
        str_to_cstring(result)
    })
    .unwrap_or(std::ptr::null_mut())
}

/// Convert a null-terminated array of C strings into a Vec<String>.
unsafe fn cstr_array_to_vec(arr: *const *const c_char) -> Vec<String> {
    let mut result = Vec::new();
    if arr.is_null() {
        return result;
    }
    let mut ptr = arr;
    unsafe {
        while !(*ptr).is_null() {
            let s = CStr::from_ptr(*ptr).to_string_lossy();
            result.push(s.into_owned());
            ptr = ptr.add(1);
        }
    }
    result
}

// ===========================================================================
// Sync Crypto
// ===========================================================================

/// Derive a key using PBKDF2-HMAC-SHA256.
///
/// Returns allocated bytes via (ptr, out_len). Caller frees with amber_free_bytes.
/// Returns null ptr on error (e.g. iterations <= 0 or key_size_bytes <= 0).
#[no_mangle]
pub extern "C" fn amber_pbkdf2_hmac_sha256(
    passphrase: *const c_char,
    salt: *const u8,
    salt_len: usize,
    iterations: u32,
    key_size_bytes: usize,
    out_len: *mut usize,
) -> *mut u8 {
    let passphrase = cstr_to_str(passphrase);
    let salt = if salt.is_null() || salt_len == 0 {
        &[]
    } else {
        unsafe { std::slice::from_raw_parts(salt, salt_len) }
    };
    ffi_catch(|| {
        match sync_crypto::pbkdf2_derive(passphrase.as_bytes(), salt, iterations, key_size_bytes) {
            Some(key) => vec_to_cbytes(key, out_len),
            None => {
                unsafe { *out_len = 0 };
                std::ptr::null_mut()
            }
        }
    })
    .unwrap_or_else(|| {
        unsafe { *out_len = 0 };
        std::ptr::null_mut()
    })
}

/// AES-256-GCM encrypt.
///
/// Returns ciphertext || 16-byte auth tag via (ptr, out_len).
/// Caller frees with amber_free_bytes. Returns null on error.
#[no_mangle]
pub extern "C" fn amber_aes_gcm_encrypt(
    plaintext: *const u8,
    plaintext_len: usize,
    key: *const u8,
    key_len: usize,
    iv: *const u8,
    iv_len: usize,
    out_len: *mut usize,
) -> *mut u8 {
    let pt = if plaintext.is_null() || plaintext_len == 0 {
        &[]
    } else {
        unsafe { std::slice::from_raw_parts(plaintext, plaintext_len) }
    };
    let key = if key.is_null() || key_len == 0 {
        &[]
    } else {
        unsafe { std::slice::from_raw_parts(key, key_len) }
    };
    let iv = if iv.is_null() || iv_len == 0 {
        &[]
    } else {
        unsafe { std::slice::from_raw_parts(iv, iv_len) }
    };
    ffi_catch(|| {
        match sync_crypto::aes_gcm_encrypt(pt, key, iv) {
            Some(ct) => vec_to_cbytes(ct, out_len),
            None => {
                unsafe { *out_len = 0 };
                std::ptr::null_mut()
            }
        }
    })
    .unwrap_or_else(|| {
        unsafe { *out_len = 0 };
        std::ptr::null_mut()
    })
}

/// AES-256-GCM decrypt.
///
/// Returns plaintext via (ptr, out_len). Returns null on auth failure.
/// Caller frees with amber_free_bytes.
#[no_mangle]
pub extern "C" fn amber_aes_gcm_decrypt(
    ciphertext: *const u8,
    ciphertext_len: usize,
    key: *const u8,
    key_len: usize,
    iv: *const u8,
    iv_len: usize,
    out_len: *mut usize,
) -> *mut u8 {
    let ct = if ciphertext.is_null() || ciphertext_len == 0 {
        &[]
    } else {
        unsafe { std::slice::from_raw_parts(ciphertext, ciphertext_len) }
    };
    let key = if key.is_null() || key_len == 0 {
        &[]
    } else {
        unsafe { std::slice::from_raw_parts(key, key_len) }
    };
    let iv = if iv.is_null() || iv_len == 0 {
        &[]
    } else {
        unsafe { std::slice::from_raw_parts(iv, iv_len) }
    };
    ffi_catch(|| {
        match sync_crypto::aes_gcm_decrypt(ct, key, iv) {
            Some(pt) => vec_to_cbytes(pt, out_len),
            None => {
                unsafe { *out_len = 0 };
                std::ptr::null_mut()
            }
        }
    })
    .unwrap_or_else(|| {
        unsafe { *out_len = 0 };
        std::ptr::null_mut()
    })
}

/// SHA-256 hash.
///
/// Returns 32 bytes via (ptr, out_len). Caller frees with amber_free_bytes.
#[no_mangle]
pub extern "C" fn amber_sha256(
    data: *const u8,
    data_len: usize,
    out_len: *mut usize,
) -> *mut u8 {
    let data = if data.is_null() || data_len == 0 {
        &[]
    } else {
        unsafe { std::slice::from_raw_parts(data, data_len) }
    };
    ffi_catch(|| {
        let hash = sync_crypto::sha256(data);
        vec_to_cbytes(hash, out_len)
    })
    .unwrap_or_else(|| {
        unsafe { *out_len = 0 };
        std::ptr::null_mut()
    })
}

/// HMAC-SHA256.
///
/// Returns 32 bytes via (ptr, out_len). Caller frees with amber_free_bytes.
#[no_mangle]
pub extern "C" fn amber_hmac_sha256(
    key: *const u8,
    key_len: usize,
    message: *const u8,
    message_len: usize,
    out_len: *mut usize,
) -> *mut u8 {
    let key = if key.is_null() || key_len == 0 {
        &[]
    } else {
        unsafe { std::slice::from_raw_parts(key, key_len) }
    };
    let message = if message.is_null() || message_len == 0 {
        &[]
    } else {
        unsafe { std::slice::from_raw_parts(message, message_len) }
    };
    ffi_catch(|| {
        let tag = sync_crypto::hmac_sha256(key, message);
        vec_to_cbytes(tag, out_len)
    })
    .unwrap_or_else(|| {
        unsafe { *out_len = 0 };
        std::ptr::null_mut()
    })
}

// ===========================================================================
// JSON Expression
// ===========================================================================

/// Evaluate a JSON expression against a root JSON object.
///
/// Returns allocated C string (result). Caller frees with amber_free_string.
/// Returns null on error.
#[no_mangle]
pub extern "C" fn amber_json_expr_evaluate(
    root_json: *const c_char,
    expr: *const c_char,
) -> *mut c_char {
    let root_json = cstr_to_str(root_json);
    let expr = cstr_to_str(expr);
    ffi_catch(|| {
        match json_expr::evaluate(root_json, expr) {
            Ok(result) => str_to_cstring(result),
            Err(_) => std::ptr::null_mut(),
        }
    })
    .unwrap_or(std::ptr::null_mut())
}

/// Check if a JSON expression is syntactically valid.
///
/// Returns 1 for valid, 0 for invalid.
#[no_mangle]
pub extern "C" fn amber_json_expr_is_valid(
    expr: *const c_char,
) -> i32 {
    let expr = cstr_to_str(expr);
    if json_expr::is_valid(expr) { 1 } else { 0 }
}

// ===========================================================================
// Tokenizer
// ===========================================================================

/// Count tokens for the given tokenizer ID and text.
///
/// Returns the token count, or -1 on error.
#[no_mangle]
pub extern "C" fn amber_tokenizer_count(
    tokenizer_id: *const c_char,
    text: *const c_char,
) -> i64 {
    let tokenizer_id = cstr_to_str(tokenizer_id);
    let text = cstr_to_str(text);
    match tokenizer::count_tokens(tokenizer_id, text) {
        Ok(count) => count as i64,
        Err(_) => -1,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn byte_buffer_round_trip_does_not_depend_on_vec_capacity() {
        let mut source = Vec::with_capacity(64);
        source.extend_from_slice(b"amber");
        assert!(source.capacity() > source.len());

        let mut len = 0usize;
        let ptr = vec_to_cbytes(source, &mut len);

        assert_eq!(len, 5);
        assert_eq!(unsafe { std::slice::from_raw_parts(ptr, len) }, b"amber");
        amber_free_bytes(ptr, len);
    }
}
