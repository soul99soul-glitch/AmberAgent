//! generative-widget-parser: stub (real impl below in this same lib.rs).
//!
//! See `app/src/main/java/me/rerere/rikkahub/data/ai/generative/GenerativeWidgetParser.kt`
//! for the JVM reference (~466 lines: regex fence detection + JSON parse).

pub mod parser;

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JClass, JString};
use jni::sys::jbyteArray;
use jni::JNIEnv;

/// JNI entry: `GenerativeWidgetParserNative.parseNative(content, streaming): ByteArray`.
///
/// Returns packed binary [`PWGS`] segment list. Caller decodes via
/// `app/.../nativebridge/GenerativeWidgetParserNative.kt`. Empty array on
/// load failure / panic → Kotlin adapter falls back to JVM impl.
#[no_mangle]
pub extern "system" fn Java_me_rerere_rikkahub_data_ai_generative_nativebridge_GenerativeWidgetParserNative_parseNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    content: JString<'local>,
    streaming: jni::sys::jboolean,
) -> jbyteArray {
    init_logger_once();

    let content_str: String = match env.get_string(&content) {
        Ok(s) => String::from(s),
        Err(e) => {
            log::error!("generative-widget-parser: failed to get JString: {}", e);
            return empty_array(&mut env);
        }
    };

    let streaming_bool = streaming != 0;

    let result =
        catch_unwind(AssertUnwindSafe(|| parser::parse_to_packed(&content_str, streaming_bool)));
    match result {
        Ok(blob) => match env.byte_array_from_slice(&blob) {
            Ok(arr) => arr.into_raw(),
            Err(e) => {
                log::error!("generative-widget-parser: byte_array_from_slice failed: {}", e);
                empty_array(&mut env)
            }
        },
        Err(panic) => {
            log::error!(
                "generative-widget-parser: native panic: {:?}",
                panic_to_string(&panic)
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

fn panic_to_string(payload: &Box<dyn std::any::Any + Send>) -> String {
    if let Some(s) = payload.downcast_ref::<&'static str>() {
        (*s).to_string()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        format!(
            "non-string panic payload (type_name = {})",
            std::any::type_name_of_val(&**payload)
        )
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
                    .with_tag("RustGenerativeWidgetParser"),
            );
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_content_returns_minimal_blob() {
        let out = parser::parse_to_packed("", false);
        assert!(out.len() >= 8);
        assert_eq!(&out[..4], b"PWGS");
    }

    #[test]
    fn plain_text_one_segment() {
        let out = parser::parse_to_packed("hello world", false);
        assert!(out.len() > 8);
        assert_eq!(&out[..4], b"PWGS");
    }
}
