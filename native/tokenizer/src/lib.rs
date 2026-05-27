use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::{jint, jintArray};
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_app_amber_core_llm_TokenCounterNative_countBatch<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    tokenizer_ids: JObjectArray<'local>,
    texts: JObjectArray<'local>,
) -> jintArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        count_batch_impl(&mut env, &tokenizer_ids, &texts)
    }));

    match result {
        Ok(Ok(arr)) => arr,
        Ok(Err(e)) => {
            let _ = env.throw_new("java/lang/RuntimeException", format!("Tokenizer error: {e}"));
            std::ptr::null_mut()
        }
        Err(_) => {
            let _ = env.throw_new("java/lang/RuntimeException", "Tokenizer panicked");
            std::ptr::null_mut()
        }
    }
}

fn count_batch_impl(
    env: &mut JNIEnv,
    tokenizer_ids: &JObjectArray,
    texts: &JObjectArray,
) -> Result<jintArray, String> {
    let len = env.get_array_length(tokenizer_ids).map_err(|e| e.to_string())? as usize;
    let text_len = env.get_array_length(texts).map_err(|e| e.to_string())? as usize;

    if len != text_len {
        return Err("tokenizer_ids and texts must have the same length".to_string());
    }

    let mut counts = Vec::with_capacity(len);

    for i in 0..len {
        let tid_obj: JString = env
            .get_object_array_element(tokenizer_ids, i as jint)
            .map_err(|e| e.to_string())?
            .into();
        let text_obj: JString = env
            .get_object_array_element(texts, i as jint)
            .map_err(|e| e.to_string())?
            .into();

        let tid: String = env.get_string(&tid_obj).map_err(|e| e.to_string())?.into();
        let text: String = env.get_string(&text_obj).map_err(|e| e.to_string())?.into();

        let count = count_tokens(&tid, &text)?;
        counts.push(count as jint);
    }

    let out = env
        .new_int_array(len as i32)
        .map_err(|e| e.to_string())?;
    env.set_int_array_region(&out, 0, &counts)
        .map_err(|e| e.to_string())?;

    Ok(out.into_raw())
}

fn count_tokens(tokenizer_id: &str, text: &str) -> Result<usize, String> {
    match tokenizer_id {
        "o200k_base" => {
            let bpe = tiktoken_rs::o200k_base().map_err(|e| e.to_string())?;
            Ok(bpe.encode_ordinary(text).len())
        }
        "cl100k_base" => {
            let bpe = tiktoken_rs::cl100k_base().map_err(|e| e.to_string())?;
            Ok(bpe.encode_ordinary(text).len())
        }
        "claude" => {
            // Anthropic doesn't publish their tokenizer.
            // Approximate: ~3.5 chars per token for mixed content.
            Ok((text.len() as f64 / 3.5).ceil() as usize)
        }
        "gemini" => {
            // Google's SentencePiece tokenizer approximation.
            // ~4.0 chars per token for mixed content.
            Ok((text.len() as f64 / 4.0).ceil() as usize)
        }
        other => Err(format!("Unknown tokenizer: {other}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_claude_approximation() {
        let count = count_tokens("claude", "Hello, world!").unwrap();
        assert!(count > 0);
        assert!(count < 20);
    }

    #[test]
    fn test_gemini_approximation() {
        let count = count_tokens("gemini", "Hello, world!").unwrap();
        assert!(count > 0);
        assert!(count < 20);
    }

    #[test]
    fn test_unknown_tokenizer() {
        let result = count_tokens("unknown", "test");
        assert!(result.is_err());
    }

    #[test]
    fn test_o200k() {
        let count = count_tokens("o200k_base", "Hello world").unwrap();
        assert!(count >= 1 && count <= 5);
    }

    #[test]
    fn test_cl100k() {
        let count = count_tokens("cl100k_base", "Hello world").unwrap();
        assert!(count >= 1 && count <= 5);
    }
}
