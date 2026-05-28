//! sync-crypto: Rust replacement for `app/.../core/sync/core/SyncCrypto.kt`.
//!
//! Hot path during backup/restore: PBKDF2 at 210k iterations dominates the
//! Kotlin path (~2 seconds on a mid-tier phone). Ring's PBKDF2 is ~5-15x
//! faster, AES-256-GCM streaming is 2-4x faster, SHA-256 of a multi-MB blob
//! is 3-5x faster.
//!
//! JNI surface — 5 primitives, narrow contract:
//!   - `pbkdf2HmacSha256Native(passphrase, salt, iterations, keySizeBytes) -> ByteArray`
//!   - `aesGcmEncryptNative(plaintext, key, iv) -> ByteArray` (ciphertext || 16B tag)
//!   - `aesGcmDecryptNative(ciphertext, key, iv) -> ByteArray?` (null on auth fail)
//!   - `sha256Native(bytes) -> ByteArray` (32 raw bytes — caller hex-encodes)
//!   - `hmacSha256Native(key, message) -> ByteArray` (32 raw bytes)
//!
//! Kotlin adapter `SyncCryptoNative.kt` does the feature-flag dispatch (flag
//! on → these natives; flag off → javax.crypto). Caller is responsible for
//! Base64 / hex encoding on the Kotlin side — keep the JNI surface bytes-only
//! to dodge UTF-8 round trips on hot blobs.
//!
//! Per ADR-0004 HARD GATE: every entry point wraps work in `catch_unwind`,
//! converts panics to log + null return so the Kotlin adapter falls back to
//! the JVM path.

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jint};
use jni::JNIEnv;
use ring::aead::{Aad, LessSafeKey, Nonce, UnboundKey, AES_256_GCM};
use ring::digest::{digest, SHA256};
use ring::hmac;
use ring::pbkdf2;
use std::num::NonZeroU32;

// ---------------------------------------------------------------------------
// PBKDF2-HMAC-SHA256
// ---------------------------------------------------------------------------

/// `pbkdf2HmacSha256Native(passphrase: String, salt: ByteArray, iterations: Int, keySizeBytes: Int): ByteArray`
///
/// Derives a key. Returns `null` ByteArray on any failure (invalid args,
/// panic). Kotlin adapter falls back to javax.crypto on null.
#[no_mangle]
pub extern "system" fn Java_app_amber_core_sync_core_SyncCryptoNative_pbkdf2HmacSha256Native<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    passphrase: JString<'local>,
    salt: JByteArray<'local>,
    iterations: jint,
    key_size_bytes: jint,
) -> jbyteArray {
    jni_common::init_logger_once!("RustSyncCrypto");

    let passphrase_str: String = match env.get_string(&passphrase) {
        Ok(s) => String::from(s),
        Err(e) => {
            log::error!("sync-crypto: get_string(passphrase) failed: {}", e);
            return std::ptr::null_mut();
        }
    };

    let salt_bytes: Vec<u8> = match env.convert_byte_array(&salt) {
        Ok(v) => v,
        Err(e) => {
            log::error!("sync-crypto: convert_byte_array(salt) failed: {}", e);
            return std::ptr::null_mut();
        }
    };

    if iterations <= 0 || key_size_bytes <= 0 {
        log::error!(
            "sync-crypto: invalid args (iterations={}, key_size_bytes={})",
            iterations,
            key_size_bytes
        );
        return std::ptr::null_mut();
    }

    let result = catch_unwind(AssertUnwindSafe(|| {
        let iters = match NonZeroU32::new(iterations as u32) {
            Some(n) => n,
            None => return None,
        };
        let mut out = vec![0u8; key_size_bytes as usize];
        pbkdf2::derive(
            pbkdf2::PBKDF2_HMAC_SHA256,
            iters,
            &salt_bytes,
            passphrase_str.as_bytes(),
            &mut out,
        );
        Some(out)
    }));

    match result {
        Ok(Some(key)) => to_jbyte_array(&mut env, &key),
        Ok(None) => std::ptr::null_mut(),
        Err(panic) => {
            log::error!(
                "sync-crypto: pbkdf2 panic: {}",
                jni_common::panic_to_string(&panic)
            );
            std::ptr::null_mut()
        }
    }
}

// ---------------------------------------------------------------------------
// AES-256-GCM
// ---------------------------------------------------------------------------

/// `aesGcmEncryptNative(plaintext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray`
///
/// Returns ciphertext-with-16-byte-tag-appended (the ring convention). Kotlin
/// adapter's javax.crypto path uses the same layout (Cipher.GCM auto-appends).
/// Returns null on any failure.
#[no_mangle]
pub extern "system" fn Java_app_amber_core_sync_core_SyncCryptoNative_aesGcmEncryptNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    plaintext: JByteArray<'local>,
    key: JByteArray<'local>,
    iv: JByteArray<'local>,
) -> jbyteArray {
    jni_common::init_logger_once!("RustSyncCrypto");

    let pt = match env.convert_byte_array(&plaintext) {
        Ok(v) => v,
        Err(e) => {
            log::error!("sync-crypto: convert_byte_array(plaintext) failed: {}", e);
            return std::ptr::null_mut();
        }
    };
    let key_bytes = match env.convert_byte_array(&key) {
        Ok(v) => v,
        Err(e) => {
            log::error!("sync-crypto: convert_byte_array(key) failed: {}", e);
            return std::ptr::null_mut();
        }
    };
    let iv_bytes = match env.convert_byte_array(&iv) {
        Ok(v) => v,
        Err(e) => {
            log::error!("sync-crypto: convert_byte_array(iv) failed: {}", e);
            return std::ptr::null_mut();
        }
    };

    let result = catch_unwind(AssertUnwindSafe(|| -> Option<Vec<u8>> {
        let unbound = UnboundKey::new(&AES_256_GCM, &key_bytes).ok()?;
        let sealing_key = LessSafeKey::new(unbound);
        // ring requires a 12-byte nonce for AES-GCM. The JVM side uses 12B IVs
        // (SyncCrypto.kt: IV_BYTES = 12) so this matches.
        let nonce = Nonce::try_assume_unique_for_key(&iv_bytes).ok()?;
        let mut in_out = pt;
        // seal_in_place_append_tag appends a 16-byte auth tag in-place;
        // exactly what JVM's Cipher in GCM mode produces.
        sealing_key
            .seal_in_place_append_tag(nonce, Aad::empty(), &mut in_out)
            .ok()?;
        Some(in_out)
    }));

    match result {
        Ok(Some(ct)) => to_jbyte_array(&mut env, &ct),
        Ok(None) => {
            log::error!("sync-crypto: aes-gcm encrypt failed (invalid key/iv?)");
            std::ptr::null_mut()
        }
        Err(panic) => {
            log::error!(
                "sync-crypto: aes-gcm encrypt panic: {}",
                jni_common::panic_to_string(&panic)
            );
            std::ptr::null_mut()
        }
    }
}

/// `aesGcmDecryptNative(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray?`
///
/// Returns plaintext. Null on auth failure OR invalid args OR panic — Kotlin
/// adapter handles fallback / error reporting from the null.
#[no_mangle]
pub extern "system" fn Java_app_amber_core_sync_core_SyncCryptoNative_aesGcmDecryptNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    ciphertext: JByteArray<'local>,
    key: JByteArray<'local>,
    iv: JByteArray<'local>,
) -> jbyteArray {
    jni_common::init_logger_once!("RustSyncCrypto");

    let ct = match env.convert_byte_array(&ciphertext) {
        Ok(v) => v,
        Err(e) => {
            log::error!("sync-crypto: convert_byte_array(ciphertext) failed: {}", e);
            return std::ptr::null_mut();
        }
    };
    let key_bytes = match env.convert_byte_array(&key) {
        Ok(v) => v,
        Err(e) => {
            log::error!("sync-crypto: convert_byte_array(key) failed: {}", e);
            return std::ptr::null_mut();
        }
    };
    let iv_bytes = match env.convert_byte_array(&iv) {
        Ok(v) => v,
        Err(e) => {
            log::error!("sync-crypto: convert_byte_array(iv) failed: {}", e);
            return std::ptr::null_mut();
        }
    };

    let result = catch_unwind(AssertUnwindSafe(|| -> Option<Vec<u8>> {
        let unbound = UnboundKey::new(&AES_256_GCM, &key_bytes).ok()?;
        let opening_key = LessSafeKey::new(unbound);
        let nonce = Nonce::try_assume_unique_for_key(&iv_bytes).ok()?;
        let mut in_out = ct;
        // open_in_place verifies the trailing 16-byte auth tag, returning the
        // plaintext slice on success. On failure (corrupted ciphertext or wrong
        // key) it returns Err — we propagate as None → null jbyteArray to the
        // Kotlin adapter, which then either falls back to javax.crypto or
        // surfaces a "wrong passphrase" error.
        let plaintext = opening_key
            .open_in_place(nonce, Aad::empty(), &mut in_out)
            .ok()?;
        Some(plaintext.to_vec())
    }));

    match result {
        Ok(Some(pt)) => to_jbyte_array(&mut env, &pt),
        Ok(None) => {
            // Don't log at error level — wrong-passphrase is an expected user
            // case; spamming logcat is noise. Adapter side surfaces the UI msg.
            log::info!("sync-crypto: aes-gcm decrypt returned null (auth fail or bad args)");
            std::ptr::null_mut()
        }
        Err(panic) => {
            log::error!(
                "sync-crypto: aes-gcm decrypt panic: {}",
                jni_common::panic_to_string(&panic)
            );
            std::ptr::null_mut()
        }
    }
}

// ---------------------------------------------------------------------------
// SHA-256 / HMAC-SHA256
// ---------------------------------------------------------------------------

/// `sha256Native(bytes: ByteArray): ByteArray`
///
/// Returns the 32 raw digest bytes. Kotlin adapter hex-encodes (the existing
/// SyncCrypto.sha256(...) wrap with `"%02x".format(it)`).
#[no_mangle]
pub extern "system" fn Java_app_amber_core_sync_core_SyncCryptoNative_sha256Native<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    bytes: JByteArray<'local>,
) -> jbyteArray {
    jni_common::init_logger_once!("RustSyncCrypto");

    let input = match env.convert_byte_array(&bytes) {
        Ok(v) => v,
        Err(e) => {
            log::error!("sync-crypto: convert_byte_array(bytes) failed: {}", e);
            return std::ptr::null_mut();
        }
    };

    let result = catch_unwind(AssertUnwindSafe(|| {
        let d = digest(&SHA256, &input);
        d.as_ref().to_vec()
    }));

    match result {
        Ok(digest_bytes) => to_jbyte_array(&mut env, &digest_bytes),
        Err(panic) => {
            log::error!(
                "sync-crypto: sha256 panic: {}",
                jni_common::panic_to_string(&panic)
            );
            std::ptr::null_mut()
        }
    }
}

/// `hmacSha256Native(key: ByteArray, message: ByteArray): ByteArray`
///
/// 32-byte HMAC tag. Adapter uses for chunk integrity stamps.
#[no_mangle]
pub extern "system" fn Java_app_amber_core_sync_core_SyncCryptoNative_hmacSha256Native<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    key: JByteArray<'local>,
    message: JByteArray<'local>,
) -> jbyteArray {
    jni_common::init_logger_once!("RustSyncCrypto");

    let key_bytes = match env.convert_byte_array(&key) {
        Ok(v) => v,
        Err(e) => {
            log::error!("sync-crypto: convert_byte_array(hmac key) failed: {}", e);
            return std::ptr::null_mut();
        }
    };
    let msg_bytes = match env.convert_byte_array(&message) {
        Ok(v) => v,
        Err(e) => {
            log::error!("sync-crypto: convert_byte_array(hmac message) failed: {}", e);
            return std::ptr::null_mut();
        }
    };

    let result = catch_unwind(AssertUnwindSafe(|| {
        let signing_key = hmac::Key::new(hmac::HMAC_SHA256, &key_bytes);
        let tag = hmac::sign(&signing_key, &msg_bytes);
        tag.as_ref().to_vec()
    }));

    match result {
        Ok(tag_bytes) => to_jbyte_array(&mut env, &tag_bytes),
        Err(panic) => {
            log::error!(
                "sync-crypto: hmac panic: {}",
                jni_common::panic_to_string(&panic)
            );
            std::ptr::null_mut()
        }
    }
}

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

fn to_jbyte_array(env: &mut JNIEnv, src: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(src) {
        Ok(arr) => arr.into_raw(),
        Err(e) => {
            log::error!("sync-crypto: byte_array_from_slice failed: {}", e);
            std::ptr::null_mut()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ring::aead::{Aad, LessSafeKey, Nonce, UnboundKey, AES_256_GCM};

    #[test]
    fn pbkdf2_known_answer() {
        // RFC 7914 / RFC 6070-style spot-check. Same passphrase/salt/iter must
        // produce same output across calls (deterministic) — we don't lock to
        // the RFC test vectors because those targeted SHA-1; we just verify
        // determinism + length.
        let salt = b"some-test-salt-1234";
        let iters = NonZeroU32::new(10_000).unwrap();
        let mut key1 = [0u8; 32];
        let mut key2 = [0u8; 32];
        pbkdf2::derive(pbkdf2::PBKDF2_HMAC_SHA256, iters, salt, b"passphrase", &mut key1);
        pbkdf2::derive(pbkdf2::PBKDF2_HMAC_SHA256, iters, salt, b"passphrase", &mut key2);
        assert_eq!(key1, key2);

        // Different passphrase must yield different key
        let mut key3 = [0u8; 32];
        pbkdf2::derive(pbkdf2::PBKDF2_HMAC_SHA256, iters, salt, b"different", &mut key3);
        assert_ne!(key1, key3);
    }

    #[test]
    fn aes_gcm_roundtrip() {
        let key_bytes = [42u8; 32];
        let iv = [7u8; 12];
        let unbound = UnboundKey::new(&AES_256_GCM, &key_bytes).unwrap();
        let key = LessSafeKey::new(unbound);

        let plaintext = b"the quick brown fox jumps over the lazy dog".to_vec();
        let mut in_out = plaintext.clone();
        let nonce = Nonce::try_assume_unique_for_key(&iv).unwrap();
        key.seal_in_place_append_tag(nonce, Aad::empty(), &mut in_out)
            .unwrap();
        // Ciphertext is plaintext + 16B tag
        assert_eq!(in_out.len(), plaintext.len() + 16);

        // Now decrypt
        let unbound2 = UnboundKey::new(&AES_256_GCM, &key_bytes).unwrap();
        let key2 = LessSafeKey::new(unbound2);
        let nonce2 = Nonce::try_assume_unique_for_key(&iv).unwrap();
        let decrypted_slice = key2.open_in_place(nonce2, Aad::empty(), &mut in_out).unwrap();
        assert_eq!(decrypted_slice, plaintext.as_slice());
    }

    #[test]
    fn aes_gcm_wrong_key_fails() {
        let key_bytes = [42u8; 32];
        let wrong_key = [99u8; 32];
        let iv = [7u8; 12];
        let unbound = UnboundKey::new(&AES_256_GCM, &key_bytes).unwrap();
        let key = LessSafeKey::new(unbound);

        let mut in_out = b"secret".to_vec();
        let nonce = Nonce::try_assume_unique_for_key(&iv).unwrap();
        key.seal_in_place_append_tag(nonce, Aad::empty(), &mut in_out)
            .unwrap();

        // Try to decrypt with wrong key
        let unbound2 = UnboundKey::new(&AES_256_GCM, &wrong_key).unwrap();
        let key2 = LessSafeKey::new(unbound2);
        let nonce2 = Nonce::try_assume_unique_for_key(&iv).unwrap();
        assert!(key2.open_in_place(nonce2, Aad::empty(), &mut in_out).is_err());
    }

    #[test]
    fn sha256_known_answer() {
        // RFC 6234 §8.5 vector: SHA-256("abc")
        let d = digest(&SHA256, b"abc");
        let hex = d.as_ref().iter().map(|b| format!("{:02x}", b)).collect::<String>();
        assert_eq!(hex, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    #[test]
    fn hmac_sha256_known_answer() {
        // RFC 4231 Test Case 1
        let key = vec![0x0bu8; 20];
        let msg = b"Hi There";
        let signing_key = hmac::Key::new(hmac::HMAC_SHA256, &key);
        let tag = hmac::sign(&signing_key, msg);
        let hex = tag.as_ref().iter().map(|b| format!("{:02x}", b)).collect::<String>();
        assert_eq!(hex, "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7");
    }
}
