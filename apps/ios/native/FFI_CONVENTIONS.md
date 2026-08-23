# FFI Conventions

This document describes the C-ABI interface exposed by the `amber-ffi` crate,
used by Swift (via XCFramework) and Kotlin/Native.

## Library

The unified library is `libamber_ffi` (staticlib + cdylib). All FFI functions
are prefixed with `amber_`.

## Memory Management

### Strings

| Direction | Type | Ownership |
|-----------|------|-----------|
| Input     | `*const c_char` | Caller-owned (UTF-8). Not freed by the library. |
| Output    | `*mut c_char`   | Library-allocated. Caller MUST free with `amber_free_string`. |

- All strings are UTF-8 encoded, null-terminated C strings.
- Output strings are `null` on error.
- `amber_free_string(null)` is safe (no-op).

### Byte Buffers

| Direction | Type | Ownership |
|-----------|------|-----------|
| Input     | `*const u8` + `len: usize` | Caller-owned. Not freed by the library. |
| Output    | `*mut u8` + `out_len: *mut usize` | Library-allocated. Caller MUST free with `amber_free_bytes`. |

- `out_len` is an output parameter: the library writes the buffer length.
- Output pointer is `null` on error, and `*out_len` is set to 0.
- `amber_free_bytes(null, 0)` is safe (no-op).
- **Important**: `len` passed to `amber_free_bytes` must be the length returned
  by the allocation function, not a derived value.

## Error Handling

- Functions that return pointers return `null` on error.
- `amber_tokenizer_count` returns `-1` on error.
- `amber_json_expr_is_valid` returns `0` for invalid, `1` for valid.
- All FFI functions catch Rust panics internally — panics do not propagate
  across the FFI boundary.

## Thread Safety

All FFI functions are thread-safe. The underlying Rust implementations use
no mutable global state (regex caches use `OnceLock` / `std::sync::OnceLock`).

## Function Reference

### Memory

| Function | Signature |
|----------|-----------|
| `amber_free_string` | `(s: *mut c_char) -> void` |
| `amber_free_bytes` | `(data: *mut u8, len: usize) -> void` |

### Markdown Parser

| Function | Signature | Returns |
|----------|-----------|---------|
| `amber_markdown_parse` | `(text: *const c_char, out_len: *mut usize) -> *mut u8` | Packed AST blob |
| `amber_markdown_to_html` | `(text: *const c_char) -> *mut c_char` | HTML string |

### Markdown Preprocess

| Function | Signature | Returns |
|----------|-----------|---------|
| `amber_markdown_preprocess` | `(input: *const c_char) -> *mut c_char` | Preprocessed markdown |

### Highlight Parser

| Function | Signature | Returns |
|----------|-----------|---------|
| `amber_highlight` | `(code: *const c_char, language: *const c_char, out_len: *mut usize) -> *mut u8` | Packed token blob |
| `amber_highlight_supported_languages` | `() -> *mut c_char` | Newline-separated language names |

### HTML Diff Normalizer

| Function | Signature | Returns |
|----------|-----------|---------|
| `amber_html_diff_normalize` | `(html: *const c_char) -> *mut c_char` | Normalized HTML |

### Reader Extractor

| Function | Signature | Returns |
|----------|-----------|---------|
| `amber_reader_extract` | `(html: *const c_char, base_url: *const c_char) -> *mut c_char` | JSON: `{title, content_html, content_text, section_count}` |

### Office Parsers

| Function | Signature | Returns |
|----------|-----------|---------|
| `amber_parse_docx` | `(path: *const c_char) -> *mut c_char` | Markdown string |
| `amber_parse_pptx` | `(path: *const c_char) -> *mut c_char` | Markdown string |
| `amber_parse_epub` | `(path: *const c_char) -> *mut c_char` | Markdown string |
| `amber_parse_xlsx` | `(path: *const c_char) -> *mut c_char` | Markdown string |

### Regex Transformer

| Function | Signature | Returns |
|----------|-----------|---------|
| `amber_regex_apply` | `(input: *const c_char, find_patterns: *const *const c_char, replacements: *const *const c_char) -> *mut c_char` | Transformed string |

`find_patterns` and `replacements` are null-terminated arrays of C strings
(the last element is a null pointer). They must have the same number of elements.

### Sync Crypto

| Function | Signature | Returns |
|----------|-----------|---------|
| `amber_pbkdf2_hmac_sha256` | `(passphrase, salt, salt_len, iterations, key_size_bytes, out_len) -> *mut u8` | Derived key bytes |
| `amber_aes_gcm_encrypt` | `(plaintext, pt_len, key, key_len, iv, iv_len, out_len) -> *mut u8` | Ciphertext + 16B tag |
| `amber_aes_gcm_decrypt` | `(ciphertext, ct_len, key, key_len, iv, iv_len, out_len) -> *mut u8` | Plaintext bytes |
| `amber_sha256` | `(data, data_len, out_len) -> *mut u8` | 32-byte digest |
| `amber_hmac_sha256` | `(key, key_len, message, msg_len, out_len) -> *mut u8` | 32-byte tag |

### JSON Expression

| Function | Signature | Returns |
|----------|-----------|---------|
| `amber_json_expr_evaluate` | `(root_json, expr) -> *mut c_char` | Result string |
| `amber_json_expr_is_valid` | `(expr) -> i32` | 1 = valid, 0 = invalid |

### Tokenizer

| Function | Signature | Returns |
|----------|-----------|---------|
| `amber_tokenizer_count` | `(tokenizer_id, text) -> i64` | Token count (-1 on error) |

## Binary Format: Packed AST (`amber_markdown_parse`)

```
header: 'PMDA' + u8 version + u8 flags + u16 reserved   (8 bytes)
body:   depth-first nodes; each = u8 tag + varint start + varint endDelta
        + varint extrasLen + extras + varint childCount + children
```

## Binary Format: Packed Tokens (`amber_highlight`)

```
header: 'PHLT' + u8 version + u8 flags + u16 reserved   (8 bytes)
type_pool: varint count + [varint len + utf8 bytes] × count
tokens: varint count + [u8 kind + varint start + varint length + (varint typeRef if kind=1)] × count
```
