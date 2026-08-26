//! JNI entry point for the retained XLSX reader backed by calamine.

mod error;
mod xlsx;

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

fn jstring_to_rust(env: &mut JNIEnv, value: JString) -> Result<String, jni::errors::Error> {
    Ok(env.get_string(&value)?.into())
}

fn rust_to_jstring(env: &mut JNIEnv, value: &str) -> jstring {
    match env.new_string(value) {
        Ok(string) => string.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn safe_parse<F>(env: &mut JNIEnv, f: F) -> jstring
where
    F: FnOnce() -> String,
{
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(value) => rust_to_jstring(env, &value),
        Err(payload) => {
            log::error!(
                "native XLSX parser panicked: {}",
                jni_common::panic_to_string(&payload)
            );
            std::ptr::null_mut()
        }
    }
}

/// JNI entry: `OfficeParserNative.parseXlsxNative(path: String): String`.
#[no_mangle]
pub extern "system" fn Java_app_amber_document_nativebridge_OfficeParserNative_parseXlsxNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jstring {
    jni_common::init_logger_once!("RustOfficeParsers");
    let path = match jstring_to_rust(&mut env, path) {
        Ok(path) => path,
        Err(error) => return rust_to_jstring(
            &mut env,
            &format!("Error parsing XLSX file: bad path — {error}"),
        ),
    };
    safe_parse(&mut env, || xlsx::parse_to_markdown(&path))
}
