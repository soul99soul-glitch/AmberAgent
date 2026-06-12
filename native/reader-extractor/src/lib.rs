use std::io::Cursor;

// ---------------------------------------------------------------------------
// Public API (platform-independent)
// ---------------------------------------------------------------------------

/// Extract article content from HTML at the given URL.
pub fn extract_article(html: &str, base_url: &str) -> Result<ExtractedArticle, String> {
    let url = url::Url::parse(base_url).unwrap_or_else(|_| url::Url::parse("https://example.com").unwrap());
    let mut cursor = Cursor::new(html.as_bytes());
    let article = readability::extractor::extract(&mut cursor, &url)
        .map_err(|e| format!("readability extraction failed: {e}"))?;

    let section_count = split_sections(&article.text).len();
    Ok(ExtractedArticle {
        title: article.title,
        content: article.content,
        text: article.text,
        section_count,
    })
}

/// Extracted article data.
pub struct ExtractedArticle {
    pub title: String,
    pub content: String,
    pub text: String,
    pub section_count: usize,
}

fn split_sections(text: &str) -> Vec<&str> {
    let mut sections = Vec::new();
    let mut start = 0;

    for (i, line) in text.lines().enumerate() {
        let trimmed = line.trim();
        if (trimmed.starts_with('#') || trimmed.chars().all(|c| c == '=' || c == '-'))
            && !trimmed.is_empty()
            && i > 0
        {
            let end = text
                .lines()
                .take(i)
                .map(|l| l.len() + 1)
                .sum::<usize>();
            if end > start {
                sections.push(&text[start..end]);
            }
            start = end;
        }
    }
    if start < text.len() {
        sections.push(&text[start..]);
    }
    if sections.is_empty() {
        sections.push(text);
    }
    sections
}

// ---------------------------------------------------------------------------
// Android JNI entry points
// ---------------------------------------------------------------------------

#[cfg(target_os = "android")]
mod jni_bridge {
    use jni::objects::{JClass, JString, JValue};
    use jni::sys::jobject;
    use jni::JNIEnv;

    #[no_mangle]
    pub extern "system" fn Java_app_amber_feature_deepread_nativebridge_ReaderExtractorNative_extract<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        html: JString<'local>,
        base_url: JString<'local>,
    ) -> jobject {
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            extract_impl(&mut env, &html, &base_url)
        }));

        match result {
            Ok(Ok(obj)) => obj,
            Ok(Err(e)) => {
                let _ = env.throw_new("java/lang/RuntimeException", format!("Extraction error: {e}"));
                std::ptr::null_mut()
            }
            Err(_) => {
                let _ = env.throw_new("java/lang/RuntimeException", "Reader extractor panicked");
                std::ptr::null_mut()
            }
        }
    }

    fn extract_impl(
        env: &mut JNIEnv,
        html_jstr: &JString,
        base_url_jstr: &JString,
    ) -> Result<jobject, String> {
        let html: String = env.get_string(html_jstr).map_err(|e| e.to_string())?.into();
        let base_url: String = env.get_string(base_url_jstr).map_err(|e| e.to_string())?.into();

        let article = crate::extract_article(&html, &base_url)?;

        let title_jstr = env.new_string(&article.title).map_err(|e| e.to_string())?;
        let content_jstr = env.new_string(&article.content).map_err(|e| e.to_string())?;
        let text_jstr = env.new_string(&article.text).map_err(|e| e.to_string())?;
        let section_count = article.section_count as i32;

        let result_class = env
            .find_class("app/amber/feature/deepread/nativebridge/ExtractedArticle")
            .map_err(|e| e.to_string())?;
        let obj = env
            .new_object(
                &result_class,
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V",
                &[
                    JValue::Object(&title_jstr),
                    JValue::Object(&content_jstr),
                    JValue::Object(&text_jstr),
                    JValue::Int(section_count),
                ],
            )
            .map_err(|e| e.to_string())?;

        Ok(obj.into_raw())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_split_sections_simple() {
        let text = "Introduction\nSome text\n# Section 1\nMore text\n# Section 2\nFinal text";
        let sections = split_sections(text);
        assert!(sections.len() >= 2);
    }

    #[test]
    fn test_split_sections_empty() {
        let sections = split_sections("");
        assert_eq!(sections.len(), 1);
    }
}
