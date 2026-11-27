# Rust Native Components

本目录是 Android 端的 Rust workspace。`jni-common` 提供共享 JNI 边界，其余七个 crate 构建为 arm64 Android `.so`。

```text
office-parsers
markdown-parser
highlight-parser
regex-transformer
sync-crypto
markdown-preprocess
html-diff-normalizer
```

## Native 单测

```bash
cargo test --workspace --locked
```

## Android 交叉编译

需要 Rust、`aarch64-linux-android` target、`cargo-ndk` 3.5.4 和 Android NDK `27.0.12077973`。Gradle 在 `app`、`document` 和 `highlight` 模块内分别调用 `cargo ndk`，再把七个库合并进 APK。

手动交叉编译读取 `native/.cargo/config.toml`。Gradle 的构建任务通过 `cargo ndk rustc ... -- -C link-arg=...` 给最终库显式传入 `-Wl,-z,max-page-size=16384` 和 `-Wl,-z,common-page-size=16384`，并开启 verbose 日志以核对实际 rustc 命令。每个任务只复制自己负责的库，避免 `cargo-ndk -o` 把 workspace 中旧的兄弟库一起带入模块。

`assemble*` 会检查 APK 里每个 `lib/**/*.so` 的 ELF `PT_LOAD` 对齐及文件/虚拟地址同余；未压缩库还须满足 16 KB zip 对齐。目前使用 `useLegacyPackaging = true`，由安装器提取压缩库，使 proot 的 ET_EXEC loader 位于可执行的 `nativeLibraryDir`，而不是 Android 10+ 禁止直接执行的应用私有目录。只有这一固定路径的 loader 允许 ET_EXEC，仍检查它的所有 LOAD 段。[Android 官方文档](https://developer.android.com/guide/practices/page-sizes#update-packaging)支持压缩库加安装时提取；此方式增加安装后的磁盘占用。

```bash
ANDROID_NDK_HOME=/path/to/android-sdk/ndk/27.0.12077973 \
  ./gradlew :app:assembleFullDebug
```

如果 `cargo-ndk` 不在 `PATH`，debug 构建会记录跳过并保留 JVM fallback。`release`、`graphite` 和 `baseline` 构建会在缺少必需的 `.so` 时失败。CI 也会逐项检查 APK 内的七个 Rust 库。

终端的 proot、talloc 和 loader 也从固定源码构建为 16 KB ELF，并参与最终 APK 检查。重建方式与来源见 [TERMINAL_RUNTIME.md](TERMINAL_RUNTIME.md)。

## 终端设备验证

`EmbeddedTerminalStartupDeviceTest` 使用 APK 内真实 proot、loader 和 Alpine，在应用 UID/SELinux 域内运行批处理命令及交互 shell，并验证 `/workspace` 写回。它使用独立临时目录，不修改用户的终端 rootfs。Robolectric 的路径断言不能替代此测试。

```bash
./gradlew :app:assembleFullDebug :app:assembleFullDebugAndroidTest
adb -s DEVICE install -r app/build/outputs/apk/full/debug/app-full-arm64-v8a-debug.apk
adb -s DEVICE install -r app/build/outputs/apk/androidTest/full/debug/app-full-debug-androidTest.apk
adb -s DEVICE shell am instrument -w -r \
  -e class app.amber.agent.EmbeddedTerminalStartupDeviceTest \
  app.amber.agent.graphite.test/app.amber.agent.AmberAgentAndroidTestRunner
```

安装命令会更新同包名应用。设备测试必须记录 Android API、`getconf PAGE_SIZE` 和设备型号，分别报告 4 KB 与 16 KB 环境的结果。

在官方 ARM64 16 KB 页镜像或对应设备上，先确认 `adb -s DEVICE shell getconf PAGE_SIZE` 输出 `16384`，再运行以下测试。显式参数开启严格页大小断言，防止把 4 KB 环境误记为 16 KB 通过：

```bash
adb -s DEVICE shell am instrument -w -r \
  -e amberNative16KbFixture true \
  -e class app.amber.agent.Native16KbPageSizeDeviceTest,app.amber.agent.NativePathDeviceBenchmarkTest,app.amber.agent.EmbeddedTerminalStartupDeviceTest,app.amber.feature.ui.pages.sessionhome.HomeSessionRowSwipeDeviceTest \
  app.amber.agent.graphite.test/app.amber.agent.AmberAgentAndroidTestRunner
```

专用测试直接加载七个 Rust JNI 库，执行语法高亮、XLSX 和 Markdown 解析，并用生产 SQLite 扩展配置运行 FTS 索引/查询。其余测试覆盖原生计算、终端启动/升级保留与 LTR/RTL 手势。CI 同时编译测试 APK。设备运行结果单独记录。

## JNI 边界

- JNI 入口不得让 Rust panic 跨越 FFI 边界。
- 失败必须返回现有 Kotlin adapter 能识别的错误，由当前生产调用链决定是否使用 JVM fallback。
- 修改 wire/binary 格式时，先更新直接消费方和对应 corpus 测试。
