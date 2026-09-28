# E1 native core implementation

日期：2026-09-28。所有权：`harmony/native/rust-napi/` 与 `harmony/entry/src/main/cpp/`。
没有修改 entry 构建配置、ArkTS 或产品 API，没有执行 HAP 构建、签名、安装或设备操作。

## 已实现

- 独立 Cargo workspace，锁定 `tiktoken-rs = 0.6.0`，产出 `amber_native_core` staticlib；不改 Android JNI crate。
- C ABI `amber_count_tokens(id, utf8, byte_len) -> int64_t`，借用输入，无跨 allocator 生命周期。
  ID 1–4 对应 o200k/cl100k/claude/gemini；负值明确区分 unknown ID、非法 UTF-8、非空 null、panic。
- BPE 使用版本自带 singleton；Claude/Gemini 按现 Android Rust 的 UTF-8 字节数近似。
- C++ Node-API 模块 `amber_native` 导出 `countBatch(string[], string[]): Promise<number[]>`。
  入参先验证并复制到 native，async execute 不调用任何 NAPI；complete 在 ArkTS 线程返回结果并释放 work/job。
  不等长、未知 ID、非数组、非字符串有明确 promise rejection；空批次正常返回空数组。
- 字符串显式长度传递，保留内嵌 NUL。Rust FFI 使用 catch_unwind，panic 不越过 C 边界。
- 类型包 `entry/src/main/cpp/types/libamber_native`，CMake 前置调用 Cargo 检查当前源和 lockfile。
  只打包当前设备实际使用的 arm64-v8a；C++ 和 Rust 均按 OHOS API 12 target 编译。
- `--no-undefined` 链接，隐藏 Rust archive 非接口符号；`--as-needed` 排除 SDK 自动添加但未使用的 deviceinfo 依赖。

## 真实验证

1. 测试先红：最初 stub 在 5 项测试中全部失败（`/tmp/e1-rust-red.log`）。
2. 实现后 host tests **6/6 通过**（`/tmp/e1-rust-green.log`）：所有 fixture 对照 Android 原始 Rust 语义，
   精确 ASCII，中文/emoji/NUL 字节数，未知 ID，非法 UTF-8/null，受控 panic 捕获。
3. OHOS release staticlib 交叉编译通过（`/tmp/e1-rust-ohos.log`）。
4. Rust `--print=native-static-libs` 实际返回 `-lunwind -lc`（`/tmp/e1-static-libs.log`），CMake 按此链接。
5. SDK clang++ API 12，`-Wall -Wextra -Werror` 单文件语法验证通过。
6. 独立 CMake + Unix Makefiles 完整链接通过（`/tmp/e1-native-link.log`）。
   ELF 为 AArch64 shared object，SONAME=`libamber_native.so`，注册符号存在，链接无未解析引用。
   最终 DT_NEEDED 仅 `libace_napi.z.so`、`libc.so`、`libc++_shared.so`。
7. `git diff --check` 与两个 shell script 语法检查通过。
8. 使用已有 DevEco Studio arm64 ninja 的独立 CMake 完整链接同样通过（`/tmp/e1-native-ninja-link.log`）。

依赖只需将 Cargo 已有缓存解压到 `~/.cargo/registry`；首次 sandbox 拦截后按此原因申请升级权限，
不修改全局 Cargo/Rust 配置。stable Rust toolchain 没有 rustfmt；使用本机已存在的 1.97.1 rustfmt 格式化，未安装组件。

## 构建接入注意

- entry `externalNativeOptions.path` 指向 `./src/main/cpp/CMakeLists.txt`，`abiFilters=['arm64-v8a']`。
- entry dependency：`"libamber_native.so": "file:./src/main/cpp/types/libamber_native"`。
- SDK CMake toolchain 实际以 `OHOS_COMPATIBLE_SDK_VERSION=12` 注入 `aarch64-linux-ohos12.0.0`。
  `OHOS_PLATFORM_LEVEL=12` 单独设置不会改变 compiler triple。
- command-line SDK 自带 ninja 是 x86_64，本机执行报 `Bad CPU type in executable`。
  已有 DevEco Studio SDK ninja 是 arm64（版本 1.12.0），不需安装工具；Hvigor 的 native CMake 参数可显式设置
  `-DCMAKE_MAKE_PROGRAM=/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/native/build-tools/cmake/bin/ninja`。
  独立 Makefile 验证使用宿主 `/usr/bin/make`。
- SDK toolchain 自动传 `--gcc-toolchain`，clang OHOS 不消费该选项；仅关闭这条命令行无用参数警告，保留代码 `-Werror`。

## 设备对照 fixture

可运行 `cargo run --locked --manifest-path harmony/native/rust-napi/Cargo.toml --example fixture_counts` 重现。

| 文本 | UTF-8 字节 | o200k_base | cl100k_base | claude | gemini |
| --- | ---: | ---: | ---: | ---: | ---: |
| 空字符串 | 0 | 0 | 0 | 0 | 0 |
| `Hello world` | 11 | 2 | 2 | 4 | 3 |
| `你好，世界` | 15 | 3 | 6 | 5 | 4 |
| `🦀🚀🙂` | 12 | 6 | 8 | 4 | 3 |
| `line 1\nline 2` | 13 | 7 | 7 | 4 | 4 |
| `a\0b`，实际内嵌 NUL | 3 | 3 | 3 | 1 | 1 |
| `<\|endoftext\|>`，ordinary 编码 | 13 | 7 | 7 | 4 | 4 |
| `你好, world! 🦀\n` 重复 4096 次 | 81920 | 32768 | 36864 | 23406 | 20480 |

设备 smoke 还须验证空批次、长度不等、未知 ID、输入类型错误、并行两个 batch，以及错误之后可再次成功调用。
主 agent 负责实际产品 consumer、HAP 打包、加载、这些设备结果和 UI 回路；本报告不把 archive/ELF 通过当作 E1 phase 完成。
