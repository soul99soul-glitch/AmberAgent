# AmberAgent for Android

AmberAgent 的 Android 产品仓库。应用、Compose UI、Room、WorkManager、Keystore、Android DI 与本地 Native 构建都在本仓维护；iOS 产品代码不再混放。

## 构建

```bash
./gradlew :app:assembleDebug
```

需要完整 Native APK 时，先安装 Rust、`aarch64-linux-android` target、Android NDK `27.0.12077973` 与 `cargo-ndk` 3.5.4。缺少 `cargo-ndk` 时 debug 构建会明确跳过 Native task，只能证明 JVM/普通 APK 组装；发布 CI 会验证八个 required `.so`。细节见 `native/README.md`。

Core 只接收两端已经共同消费且平台无关的稳定契约；当前 Android 代码不通过相对路径读取其他仓库。
