//! Shared helpers used by every JNI-facing amber-agent Rust crate
//! (`office-parsers`, `markdown-parser`, `highlight-parser`,
//! `regex-transformer`). Extracted in Phase 3 D-2 — previously each crate
//! kept identical inline copies.
//!
//! Three primitives only — keep the dependency boundary narrow so adding a
//! new JNI crate doesn't accidentally pull in unrelated dependencies:
//!
//! 1. [`panic_to_string`] — best-effort diagnostic for `catch_unwind` payloads
//! 2. [`init_logger_once!`] — Android logcat tag-gated lazy logger init
//! 3. [`write_varint`] — unsigned LEB128 writer used by every packed binary
//!    wire format we ship across the JNI boundary
//!
//! Crates that don't need all three can still pull this in cheaply; the
//! whole crate is < 100 lines.

/// Convert a `catch_unwind` payload into a human-readable string for log /
/// Crashlytics propagation. Recognises the two common payload types
/// (`&'static str` and `String`) and falls back to the type name for anything
/// exotic so future crash reports aren't just "non-string panic payload".
pub fn panic_to_string(payload: &Box<dyn std::any::Any + Send>) -> String {
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

/// Initialise `android_logger` with the given tag. **Not part of the public
/// API** — `#[doc(hidden)]` because it's only meant for the
/// [`init_logger_once!`] macro to call; calling it directly bypasses the
/// `OnceLock` and re-initialises the logger on every call. The macro
/// references it via `$crate::android_logger_init` so it must be `pub`
/// (otherwise downstream crate expansion can't see it).
#[cfg(target_os = "android")]
#[doc(hidden)]
pub fn android_logger_init(tag: &'static str) {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag(tag),
    );
}

#[cfg(not(target_os = "android"))]
#[doc(hidden)]
pub fn android_logger_init(_tag: &'static str) {
    // host-side cargo test: skip
}

/// Idempotently initialise the Android logger with the given tag. Expand at
/// the JNI entry of each crate — the macro keeps a per-crate `OnceLock`
/// inside the calling site so two crates initialising in the same process
/// don't clobber each other's tags. Pass a `&'static str` literal.
///
/// ```ignore
/// # use jni_common::init_logger_once;
/// jni_common::init_logger_once!("RustOfficeParsers");
/// ```
#[macro_export]
macro_rules! init_logger_once {
    ($tag:expr) => {{
        static INIT: ::std::sync::OnceLock<()> = ::std::sync::OnceLock::new();
        INIT.get_or_init(|| {
            $crate::android_logger_init($tag);
        });
    }};
}

/// Write an unsigned 64-bit value as LEB128 into `out`. Same encoding used by
/// the protobuf varint format: every byte stores 7 payload bits + 1
/// continuation bit. Decoded on the Kotlin side by hand-rolled `readVarint`
/// helpers in `*Native.kt`.
pub fn write_varint(mut value: u64, out: &mut Vec<u8>) {
    loop {
        let byte = (value & 0x7F) as u8;
        value >>= 7;
        if value == 0 {
            out.push(byte);
            return;
        }
        out.push(byte | 0x80);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn varint_single_byte_under_128() {
        let mut out = Vec::new();
        write_varint(0, &mut out);
        assert_eq!(out, vec![0]);
        out.clear();
        write_varint(127, &mut out);
        assert_eq!(out, vec![127]);
    }

    #[test]
    fn varint_two_bytes_at_128() {
        let mut out = Vec::new();
        write_varint(128, &mut out);
        assert_eq!(out, vec![0x80, 0x01]);
    }

    #[test]
    fn varint_large() {
        // 300 = 0b100101100 → low7=0101100|0x80, next7=10 → 0x80 0xAC, 0x02
        let mut out = Vec::new();
        write_varint(300, &mut out);
        assert_eq!(out, vec![0xAC, 0x02]);
    }

    #[test]
    fn panic_to_string_handles_static_str() {
        let payload: Box<dyn std::any::Any + Send> = Box::new("boom");
        assert_eq!(panic_to_string(&payload), "boom");
    }

    #[test]
    fn panic_to_string_handles_string() {
        let payload: Box<dyn std::any::Any + Send> = Box::new(String::from("dynamic boom"));
        assert_eq!(panic_to_string(&payload), "dynamic boom");
    }

    #[test]
    fn panic_to_string_handles_other_with_type_name() {
        // `type_name_of_val(&**payload)` on a `Box<dyn Any + Send>` returns
        // the trait-object type name (`dyn core::any::Any + ...`), not the
        // concrete struct name — that's a Rust language constraint, not a
        // limitation of this helper. The diagnostic still distinguishes
        // exotic panic types from the common `&str` / `String` cases.
        struct Weird;
        let payload: Box<dyn std::any::Any + Send> = Box::new(Weird);
        let s = panic_to_string(&payload);
        assert!(
            s.starts_with("non-string panic payload"),
            "unexpected payload string: {s}"
        );
    }
}
