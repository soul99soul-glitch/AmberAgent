# Amber OHOS Native Core

The E1 pipeline uses one Rust static library and a C++ Node-API shim. Android's JNI crate remains separate.

## Interface

`libamber_native.so` exports `countBatch(tokenizerIds: string[], texts: string[]): Promise<number[]>`.
IDs are `o200k_base`, `cl100k_base`, `claude`, and `gemini`. Arrays must have equal lengths and contain
only strings. Unknown IDs reject the promise. Empty batches resolve to an empty array.

Texts are copied with their UTF-8 byte lengths before async work starts. BPE counting and first
initialization run in the native worker, using `tiktoken-rs = 0.6.0` singletons. Claude and Gemini
retain the Android implementation's UTF-8 byte approximations (ceil(bytes / 3.5) and / 4.0).
Embedded NUL is preserved; special-token literals use ordinary encoding. Rust panic is caught at
the C ABI and becomes a rejected promise.

## Build and checks

```sh
rustup target add aarch64-unknown-linux-ohos
cargo test --locked --manifest-path harmony/native/rust-napi/Cargo.toml
cargo run --locked --manifest-path harmony/native/rust-napi/Cargo.toml --example fixture_counts
harmony/native/rust-napi/scripts/build-rust-native.sh
```

The script uses `OHOS_BASE_SDK_HOME` or the repository's existing command-line SDK location, sets
the compiler target to OHOS API 12, and keeps build artifacts in this directory's ignored `target/`.
It does not change global Cargo configuration. The entry CMake target invokes the same script for
each build so Cargo checks the current sources and lockfile before linking the archive.

Only `arm64-v8a` is currently packaged, matching both connected emulator and device. The C++ shim
includes the SDK's `napi/native_api.h`; its APIs are available since API 10.

Host tests compare empty, ASCII, Chinese, emoji, newline, NUL, special literals, and long text with
the original Android Rust semantics. Cross-compiling the archive or linking the ELF is preparation;
the phase gate additionally requires the signed HAP to load it and return those fixture counts on
device. SSH, embedded Python, and Mosh remain separate subsequent phases.
