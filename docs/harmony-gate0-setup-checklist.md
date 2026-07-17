# HarmonyOS Gate 0 本机环境清单

这份清单只解决一件事：让 `docs/harmony-gate0-report.md` 从 `blocked` 变成 `ready`。工具链没就绪前，SSE、RDB、ArkUI、NAPI 这些 spike 都不能算真正验证过。

## 1. 先选一条安装路线

有两条路：

- 轻量路线：安装 Huawei Command Line Tools，拿到 `hvigor` / `ohpm` / `hdc` / SDK 管理能力。适合先跑命令行 spike。
- 完整路线：安装 DevEco Studio GUI。适合创建工程、自动签名、调试 UI 和管理设备。

如果只是为了让 Gate 0 从 blocked 变成可跑，Command Line Tools 可以先起步。后面真机签名和调试一般还是 DevEco Studio 省事。

## 2. 安装 DevEco Studio（完整路线）

官方入口：

- https://developer.huawei.com/consumer/cn/download/
- https://developer.harmonyos.com/cn/develop/deveco-studio

macOS 常见安装路径：

```text
/Applications/DevEco-Studio.app
/Applications/DevEco Studio.app
```

安装后重新运行：

```bash
node scripts/harmony-inventory/check-harmony-gate0.mjs
```

## 3. 安装/确认 HarmonyOS NEXT SDK

DevEco Studio 通常内置 SDK。也可能在首次启动时让你选择 SDK 存储目录。

常见路径：

```text
/Applications/DevEco-Studio.app/Contents/sdk
~/Library/Huawei/Sdk
~/HarmonyOS/Sdk
```

如果要验证 AccessibilityExtensionAbility，可能需要 Full SDK。普通应用开发通常先用 Public SDK，不要一开始就强行替换 Full SDK。

官方参考：

- https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/ide-software-install
- https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/ide-environment-config

## 4. 配置命令行工具

Gate 0 需要这些命令可用。完整 DevEco GUI 不是唯一入口，但这些命令少不了：

```text
hvigor 或 hvigorw
ohpm
hdc
```

常见路径：

```text
/Applications/DevEco-Studio.app/Contents/tools/ohpm/bin/ohpm
/Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigor
/Applications/DevEco-Studio.app/Contents/sdk/**/toolchains/hdc
~/command-line-tools/bin
~/Library/Huawei/commandline/command-line-tools/bin
```

HDC 常见环境变量：

```bash
export HDC_SERVER_PORT=7035
```

如果使用 Huawei Command Line Tools，命令行构建还需要这两个路径。注意：`DEVECO_SDK_HOME` 指向 SDK 父目录，不是 `default` 子目录。

```bash
export DEVECO_SDK_HOME="$HOME/Library/Huawei/commandline/command-line-tools/sdk"
export OHOS_BASE_SDK_HOME="$HOME/Library/Huawei/commandline/command-line-tools/sdk/default/openharmony"
```

本仓库已提供可重复构建脚本，会自动补齐上述环境变量：

```bash
node scripts/harmony-inventory/build-harmony-app.mjs
```

官方参考：

- https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/ide-commandline-get
- https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/ide-hvigor-commandline
- https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/environmental-preparation

## 5. 准备签名和设备

真机调试需要签名。DevEco Studio 里推荐先用自动签名：

```text
File > Project Structure > Project > Signing Configs
```

勾选自动生成签名，并登录华为开发者账号。

真机需要：

- 开启开发者模式
- 开启 USB 调试
- `hdc list targets` 能看到设备

手动签名会涉及 `.p12`、`.cer`、`.p7b`。第一轮 spike 不建议先走手动签名，除非自动签名不可用。

官方参考：

- https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/ide-signing
- https://developer.huawei.com/consumer/cn/doc/app/agc-help-add-debugcert-0000001914263178

## 6. 验证命令

在 `AmberAgent-harmony-preview` 目录执行：

```bash
node scripts/harmony-inventory/check-harmony-gate0.mjs
```

期望结果：

```text
Gate 0 status: ready
```

如果仍然是 `blocked`，先看 `docs/harmony-gate0-report.md` 的 Blocking Missing Items。不要跳过 Gate 0 直接写 DevEco 工程。

注意：当前脚本把设备/模拟器 target 也作为 Gate 0 ready 的一部分。如果只是想先确认命令行构建工具，`hdc` 可用但没有 target 时仍会显示 blocked。

如果只验证构建，不安装到设备，可运行：

```bash
node scripts/harmony-inventory/build-harmony-app.mjs
```

当前已知：命令行构建可以在没有 target 时通过；真实 P0 runtime spike 仍需要 `hdc list targets` 能看到设备或模拟器。

## 7. Gate 0 ready 后立刻做什么

按顺序跑三个 P0 spike：

1. OpenAI-compatible SSE streaming。
2. RDB + Preferences。
3. ArkUI + Markdown 流式渲染。

Rust NAPI、Accessibility、终端/proot、通知监听都不是第一批阻塞项。
