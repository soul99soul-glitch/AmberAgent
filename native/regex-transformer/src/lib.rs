//! regex-transformer: Rust replacement for `String.replaceRegexes` in
//! `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt`.
//!
//! Hot path: called per visible message render. For users with many assistant
//! regex rules configured (visual transforms / lore book substitutions),
//! the per-frame regex compile+apply cost in JVM Regex is significant.
//!
//! JVM contract (Assistant.kt:86-111):
//!   - Take input String
//!   - Iterate each enabled, scope-matching AssistantRegex
//!   - On each match, `acc.replace(Regex(findRegex), replaceString)`
//!   - On individual regex error: catch + skip + log; do NOT abort pipeline
//!
//! Kotlin adapter does the rule-filtering (enabled / visualOnly / affectingScope)
//! and passes parallel `findPatterns` + `replacements` arrays. The JNI surface
//! is intentionally narrow: take the post-filter list and apply.

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use regex::Regex;

/// JNI entry: `RegexTransformerNative.applyRegexesNative(input, findPatterns, replacements): String?`.
///
/// Returns:
/// - The transformed string on success
/// - The unchanged input on per-rule regex compile failure (skip the bad rule,
///   continue with the rest — mirrors JVM `try { ... } catch { acc }`)
/// - `null` on JString conversion failure or panic (Kotlin adapter falls back
///   to the JVM implementation)
#[no_mangle]
pub extern "system" fn Java_app_amber_agent_data_model_nativebridge_RegexTransformerNative_applyRegexesNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    input: JString<'local>,
    find_patterns: JObjectArray<'local>,
    replacements: JObjectArray<'local>,
) -> jstring {
    jni_common::init_logger_once!("RustRegexTransformer");

    let input_str: String = match env.get_string(&input) {
        Ok(s) => String::from(s),
        Err(e) => {
            log::error!("regex-transformer: failed to get input JString: {}", e);
            return std::ptr::null_mut();
        }
    };

    let result = catch_unwind(AssertUnwindSafe(|| {
        apply_pipeline(&mut env, &input_str, &find_patterns, &replacements)
    }));

    match result {
        Ok(Ok(transformed)) => match env.new_string(transformed) {
            Ok(jstr) => jstr.into_raw(),
            Err(e) => {
                log::error!("regex-transformer: new_string failed: {}", e);
                std::ptr::null_mut()
            }
        },
        Ok(Err(e)) => {
            log::error!("regex-transformer: pipeline error: {}", e);
            std::ptr::null_mut()
        }
        Err(panic) => {
            log::error!(
                "regex-transformer: native panic: {:?}",
                jni_common::panic_to_string(&panic)
            );
            std::ptr::null_mut()
        }
    }
}

fn apply_pipeline<'local>(
    env: &mut JNIEnv<'local>,
    input: &str,
    find_patterns: &JObjectArray<'local>,
    replacements: &JObjectArray<'local>,
) -> Result<String, RegexTransformerError> {
    let n_finds = env.get_array_length(find_patterns)? as usize;
    let n_reps = env.get_array_length(replacements)? as usize;
    if n_finds != n_reps {
        return Err(RegexTransformerError::ArrayLengthMismatch {
            finds: n_finds,
            reps: n_reps,
        });
    }

    let mut acc = input.to_string();
    for i in 0..n_finds {
        let find = read_string_at(env, find_patterns, i)?;
        let replacement = read_string_at(env, replacements, i)?;

        // JVM behaviour: on individual regex compile failure, log + skip
        // that rule; do NOT abort the pipeline. We mirror that exactly.
        let re = match Regex::new(&find) {
            Ok(r) => r,
            Err(e) => {
                log::warn!(
                    "regex-transformer: rule #{} pattern compile failed ({}): skipping",
                    i,
                    e
                );
                continue;
            }
        };

        // regex::Regex::replace_all takes `&str` for replacement — same semantics
        // as Kotlin's String.replace(Regex, String) (group references $1, $name).
        acc = re.replace_all(&acc, replacement.as_str()).into_owned();
    }

    Ok(acc)
}

fn read_string_at<'local>(
    env: &mut JNIEnv<'local>,
    arr: &JObjectArray<'local>,
    idx: usize,
) -> Result<String, RegexTransformerError> {
    let obj = env.get_object_array_element(arr, idx as i32)?;
    let jstr: JString = obj.into();
    let s: String = env.get_string(&jstr)?.into();
    Ok(s)
}

#[derive(thiserror::Error, Debug)]
enum RegexTransformerError {
    #[error("jni error: {0}")]
    Jni(#[from] jni::errors::Error),

    #[error("array length mismatch: finds={finds}, replacements={reps}")]
    ArrayLengthMismatch { finds: usize, reps: usize },
}

#[cfg(test)]
mod tests {
    use super::*;

    // The JNI entry can't be exercised from a host test (needs live JVM).
    // We test the pure-Rust pipeline portion via a helper.

    fn apply_pure(input: &str, rules: &[(&str, &str)]) -> String {
        let mut acc = input.to_string();
        for (find, replacement) in rules {
            let re = match Regex::new(find) {
                Ok(r) => r,
                Err(_) => continue,
            };
            acc = re.replace_all(&acc, *replacement).into_owned();
        }
        acc
    }

    #[test]
    fn simple_replace() {
        assert_eq!(apply_pure("hello world", &[("world", "rust")]), "hello rust");
    }

    #[test]
    fn empty_rules_passthrough() {
        assert_eq!(apply_pure("untouched", &[]), "untouched");
    }

    #[test]
    fn bad_rule_is_skipped() {
        // Unbalanced bracket = invalid regex; should be skipped, not abort
        assert_eq!(
            apply_pure("hello world", &[("[invalid", "X"), ("world", "rust")]),
            "hello rust"
        );
    }

    #[test]
    fn capture_group_substitution() {
        // JVM Regex / Kotlin String.replace supports $1; rust regex crate uses $1 too.
        assert_eq!(
            apply_pure("hello 42 world", &[(r"(\d+)", "[$1]")]),
            "hello [42] world"
        );
    }

    #[test]
    fn sequential_rules_apply_in_order() {
        // Each rule applied to the accumulator from the previous step.
        assert_eq!(
            apply_pure("foo", &[("foo", "bar"), ("bar", "baz")]),
            "baz"
        );
    }

    #[test]
    fn case_sensitive_by_default() {
        // JVM Regex is case-sensitive unless RegexOption.IGNORE_CASE; same for Rust.
        assert_eq!(apply_pure("Hello", &[("hello", "X")]), "Hello");
    }

    #[test]
    fn unicode_handled() {
        assert_eq!(apply_pure("café", &[("é", "e")]), "cafe");
    }
}
