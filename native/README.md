# Rust Native Components

本目录是 Android 端的 Rust workspace。十个 crate 中，`jni-common` 提供共享 JNI 边界，`tokenizer` 只参与 Native 单测；其余八个 crate 构建为 arm64 Android `.so`：

```text
office-parsers
markdown-parser
highlight-parser
regex-transformer
reader-extractor
sync-crypto
markdown-preprocess
html-diff-normalizer
```

## Native 单测

```bash
cargo test --workspace --locked
```

## Android 交叉编译

需要 Rust、`aarch64-linux-android` target、`cargo-ndk` 3.5.4 和 Android NDK `27.0.12077973`。Gradle 在 `app`、`document` 和 `highlight` 模块内分别调用 `cargo ndk`，再把八个库合并进 APK。

```bash
ANDROID_NDK_HOME=/path/to/android-sdk/ndk/27.0.12077973 \
  ./gradlew :app:assembleDebug
```

如果 `cargo-ndk` 不在 `PATH`，debug 构建会明确记录跳过并保留 JVM fallback；这种 APK 不能作为 Native 完整性证据。`release`、`graphite` 和 `baseline` 构建会在缺少任一 required `.so` 时失败。CI 也会逐项检查 APK 内的八个 `lib/arm64-v8a/*.so`。

## JNI 边界

- JNI 入口不得让 Rust panic 跨越 FFI 边界。
- 失败必须返回现有 Kotlin adapter 能识别的错误，由当前生产调用链决定是否使用 JVM fallback。
- 修改 wire/binary 格式时，先更新直接消费方和对应 corpus 测试。
