# AmberAgent for Android

AmberAgent 的 Android 产品仓库。应用、Compose UI、Room、WorkManager、Keystore、Android DI 与本地 Native 构建都在本仓维护；iOS 产品代码不再混放。

## 构建

```bash
./gradlew :app:assembleDebug
```

需要重编 Rust Native 组件时，先安装 Rust、Android NDK 与 `cargo-ndk`，再执行对应 Gradle 构建。

Core 只接收两端已经共同消费且平台无关的稳定契约；当前 Android 代码不通过相对路径读取其他仓库。
