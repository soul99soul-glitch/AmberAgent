#[derive(Debug, PartialEq)]
enum TokenError {
    UnknownTokenizer,
    InvalidUtf8,
    NullPointer,
}

fn count_tokens(id: u32, text: &str) -> Result<usize, TokenError> {
    match id {
        1 => Ok(tiktoken_rs::o200k_base_singleton()
            .lock()
            .encode_ordinary(text)
            .len()),
        2 => Ok(tiktoken_rs::cl100k_base_singleton()
            .lock()
            .encode_ordinary(text)
            .len()),
        3 => Ok((text.len() as f64 / 3.5).ceil() as usize),
        4 => Ok((text.len() as f64 / 4.0).ceil() as usize),
        _ => Err(TokenError::UnknownTokenizer),
    }
}

fn ffi_result(operation: impl FnOnce() -> Result<usize, TokenError>) -> i64 {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(operation)) {
        Ok(Ok(count)) => count as i64,
        Ok(Err(TokenError::UnknownTokenizer)) => -1,
        Ok(Err(TokenError::InvalidUtf8)) => -2,
        Ok(Err(TokenError::NullPointer)) => -3,
        Err(_) => -4,
    }
}

/// Returns a count or a negative error code. The caller owns the input bytes.
///
/// # Safety
/// `text` must refer to `len` readable bytes, or be null when `len` is zero.
#[no_mangle]
pub unsafe extern "C" fn amber_count_tokens(id: u32, text: *const u8, len: usize) -> i64 {
    ffi_result(|| {
        let bytes = if len == 0 {
            &[]
        } else if text.is_null() {
            return Err(TokenError::NullPointer);
        } else {
            std::slice::from_raw_parts(text, len)
        };
        let value = std::str::from_utf8(bytes).map_err(|_| TokenError::InvalidUtf8)?;
        count_tokens(id, value)
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn reference_count(id: u32, text: &str) -> usize {
        match id {
            1 => tiktoken_rs::o200k_base()
                .unwrap()
                .encode_ordinary(text)
                .len(),
            2 => tiktoken_rs::cl100k_base()
                .unwrap()
                .encode_ordinary(text)
                .len(),
            3 => (text.len() as f64 / 3.5).ceil() as usize,
            4 => (text.len() as f64 / 4.0).ceil() as usize,
            _ => unreachable!(),
        }
    }

    #[test]
    fn all_tokenizers_match_android_utf8_semantics() {
        let long_text = "你好, world! 🦀\n".repeat(4096);
        let fixtures = [
            "",
            "Hello world",
            "你好，世界",
            "🦀🚀🙂",
            "line 1\nline 2",
            "a\0b",
            "<|endoftext|>",
            long_text.as_str(),
        ];
        for id in 1..=4 {
            for text in fixtures {
                let expected = reference_count(id, text);
                assert_eq!(
                    count_tokens(id, text),
                    Ok(expected),
                    "id={id}, bytes={}",
                    text.len()
                );
                assert_eq!(
                    unsafe { amber_count_tokens(id, text.as_ptr(), text.len()) },
                    expected as i64
                );
            }
        }
    }

    #[test]
    fn ascii_bpe_counts_are_exact() {
        assert_eq!(count_tokens(1, "Hello world"), Ok(2));
        assert_eq!(count_tokens(2, "Hello world"), Ok(2));
    }

    #[test]
    fn approximations_count_bytes_including_nul() {
        assert_eq!(count_tokens(3, "你好"), Ok(2));
        assert_eq!(count_tokens(4, "你好"), Ok(2));
        assert_eq!(count_tokens(3, "🦀🦀"), Ok(3));
        assert_eq!(count_tokens(4, "🦀🦀"), Ok(2));
        assert_eq!(count_tokens(3, "a\0b"), Ok(1));
        assert_eq!(count_tokens(4, "a\0b"), Ok(1));
    }

    #[test]
    fn unknown_tokenizer_is_explicit() {
        assert_eq!(count_tokens(0, "text"), Err(TokenError::UnknownTokenizer));
        assert_eq!(unsafe { amber_count_tokens(9, b"text".as_ptr(), 4) }, -1);
        assert_eq!(unsafe { amber_count_tokens(9, std::ptr::null(), 0) }, -1);
    }

    #[test]
    fn ffi_rejects_invalid_utf8_and_nonempty_null() {
        assert_eq!(unsafe { amber_count_tokens(1, [0xff].as_ptr(), 1) }, -2);
        assert_eq!(unsafe { amber_count_tokens(1, std::ptr::null(), 1) }, -3);
        assert_eq!(unsafe { amber_count_tokens(1, std::ptr::null(), 0) }, 0);
    }

    #[test]
    fn panic_is_contained_at_ffi_boundary() {
        assert_eq!(ffi_result(|| panic!("controlled test panic")), -4);
        assert_eq!(ffi_result(|| Ok(2)), 2);
    }
}
