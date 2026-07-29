# AmberAgent iSH 集成方案分析 & 借鉴 OpenMinis 设计建议

> 日期：2026-07-25
> 对比对象：[OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis)（含 `deps/ISH_INTEGRATION.md`）
> 结论：**AmberAgent 已走在与 OpenMinis 相同的架构路线上**（进程内嵌入 ish-arm64），方向正确；差距主要在供应链控制、rootfs 自主构建、交互能力和 agent 集成深度。

---

## 一、AmberAgent 现状

| 层面 | 现状 | 位置 |
| --- | --- | --- |
| 运行时来源 | 第三方预编译 SPM 包 `Lolendor/ish-arm64-pkg`（产品 `IshEmbed`） | `iosApp/project.yml` |
| Rootfs | vendor 该包 release 的预构建 fakefs（`fs/` = `meta.db` + `data/`，v0.3.3，SHA-256 已校验） | `iosApp/IshRuntimeResources/` |
| 调用方式 | `IOSEmbeddedIshRuntime` actor，仅使用 `runOneshot`（`/bin/sh -lc`），未使用 PTY 交互 | `iosApp/iosApp/IOSEmbeddedIshRuntime.swift` |
| 许可隔离 | 独立 `iosAppExperimentalGPL` target + `ENABLE_EXPERIMENTAL_TERMINAL_RUNTIMES` 编译宏，稳定版不含 GPL 代码 ✅ | `iosApp/project.yml` |
| 运行时抽象 | `IOSTerminalRuntimeKind`：remoteSSH（稳定推荐）/ localIOSTools / remoteMosh / ishExperimental | `iosApp/iosApp/IOSTerminalRuntime.swift` |
| 历史方案 | `ish_handoff` 工具：脚本复制到剪贴板，用户手动粘贴到真实 iSH app 执行 | `iosApp/iosApp/IOSIshHandoff.swift` |

### 现有实现中的亮点（应保留）

- **Rootfs 准备逻辑比 OpenMinis 更严谨**：版本化目录 + sentinel 文件 + 原子替换 + 失败回滚备份（`preparedWritableRootfsURL()`），OpenMinis 只是简单 unzip
- **GPL 隔离策略正确**：单独 target + 编译宏，稳定版零 GPL 污染
- **运行时分层清晰**：remoteSSH 作为稳定默认，嵌入式 iSH 作为 opt-in experimental

### IshEmbed 包能力盘点（已具备但未充分利用）

依赖的 `IshEmbed` API 实际上已相当完整：

- `boot(BootOptions)` / `shutdown()`
- `runOneshot(IshSpawnOptions)` — 当前唯一在用
- `spawn(IshSpawnOptions)` → `IshSession`：**PTY 交互会话**，支持 `read`（流式事件）/ `write` / `resize` / `signal` / `closeStdin`
- 多 VM（`ensureDefaultVM`）
- 自带 VT 终端模拟器（`VTEmulator`、`IshTerminal`）

---

## 二、OpenMinis 方案要点

| 维度 | OpenMinis 做法 |
| --- | --- |
| 源码控制 | 自己的 fork `OpenMinis/ish-arm64`（`feature-arm64` 分支）放入 `deps/`，`build_ish.sh`（meson + ninja）从源码编出 `libish.a` / `libish_emu.a` / `libfakefs.a`，全程可审计可修改 |
| Rootfs 构建 | `prepare_alpine_rootfs.sh` 从 Alpine aarch64 官方源自行组装 rootfs，预装包自主决定 |
| 内核启动 | App 进程内引导 Linux 内核：`mount_root(&fakefs)` → `become_first_process()`（PID 1）→ 建 `/dev` 节点 → 挂 `/proc` → 注册 `exit_hook` |
| 终端交互 | 自定义 TTY 驱动：`tty_write` 回调 → `NSNotification` → 主线程 UI；输入经 `tty_input()` 注回内核 |
| 文件路由 | fakefs path-translate hook 将任务文件操作路由到 app 沙盒 / workspace |
| Agent 集成 | shell 是 agent 的一等工具，配合权限系统直接执行 |
| 许可策略 | 整个项目 GPLv3 |
| Android | 不用 iSH，用 PRoot（fork 自 termux/proot），静态 `proot-aarch64` 塞进 APK assets |

---

## 三、差距对比

| 维度 | OpenMinis | AmberAgent | 差距 |
| --- | --- | --- | --- |
| 运行时供应链 | 自维护 fork + 源码构建脚本 | 依赖 Lolendor 预编译包 | ⚠️ 上游停更/下架即被卡死 |
| Rootfs | 脚本自主构建，内容可控 | 别人打好的 fs.tar.gz | ⚠️ 内容不可控、无法预装工具 |
| 终端交互 | 自定义 TTY 驱动，真交互终端 | 有 PTY API 未接线，仅 oneshot | ⚠️ 长任务/交互式命令体验差 |
| Agent 集成 | 一等工具 + 权限系统 | oneshot 藏在 experimental 后 | 中 |
| Workspace 隔离 | path-translate hook | 单一 rootfs | 低（当前单 workspace 影响小） |
| Rootfs 安装健壮性 | 简单 unzip | sentinel + 原子替换 + 回滚 | ✅ AmberAgent 更好 |
| 许可隔离 | 全项目 GPLv3 | experimental target 隔离 | ✅ AmberAgent 更适合双轨发布 |

---

## 四、借鉴方案（按优先级）

### P0 — 运行时供应链自主化（学 OpenMinis 的 deps 模式）

```
deps/
  ish/                      # git submodule → fork ish-arm64 源码
                            # （fork Lolendor/ish-arm64-pkg 内的 ish，或直接用 OpenMinis/ish-arm64）
  build_ish.sh              # 参照 OpenMinis：meson + ninja → libish.a / libish_emu.a / libfakefs.a
  prepare_alpine_rootfs.sh  # 从 Alpine aarch64 官方源自行组装 rootfs，预装 git / python3 / curl
```

- 做成**本地 SwiftPM 包** `IshEmbed`，公开 API 对齐现有 `IshInstance`（boot / runOneshot / spawn）
- 上层 `IOSEmbeddedIshRuntime` **零改动**切换
- 收益：可审计、可打补丁、可跟进上游、不受第三方仓库存续影响

### P1 — 接上 PTY 交互能力（API 已有，只差接线）

- 将 `IshInstance.spawn()` + `IshSessionEvent` 接入 `IOSTerminalRuntime`：
  - 长任务**流式输出**（当前 oneshot 需等超时一次性返回，agent 体验差）
  - 支持交互式命令（`resize` / `signal` / `write` stdin）
- 用 IshEmbed 自带 `VTEmulator` / `IshTerminal` 做终端 UI（对应 OpenMinis 的 `iSH/Terminal/`）

### P2 — Rootfs 自主构建 + Workspace 隔离

- `prepare_alpine_rootfs.sh` 产出版本化 rootfs，替换 vendor 的 fs.tar.gz
- **保留现有** sentinel / 原子替换 / 回滚机制（优于 OpenMinis 的 unzip 方案）
- 学 fakefs path-translate hook：为每个 agent 任务 / workspace 挂独立 data 目录，避免互相污染

### P3 — 淘汰 `ish_handoff`

嵌入式 PTY 稳定后，将剪贴板交接方案（`IOSIshHandoff.swift`）降级为 fallback 或删除。

---

## 五、风险与注意事项

### 1. 许可证

- OpenMinis 整体 **GPLv3**，直接复制其代码会传染整个项目
- 正确做法：抄**架构和构建脚本思路**，代码基于 iSH 上游（同为 GPL，但已隔离在 experimental target）自行实现
- 维持现有"GPL 隔离到 `iosAppExperimentalGPL` target"策略不变

### 2. App Store 审核

- OpenMinis 已上架（App Store id6759188481），说明嵌入式 Linux 环境路线可行
- 但 iSH 历史上有过审核反复（2020 年险些下架）
- 保留 remoteSSH 作为稳定默认运行时、嵌入式 iSH 作为 opt-in，不动

### 3. Rootfs 体积

- 预装 python3 / git 后 rootfs 会明显增大，需评估对 IPA 体积的影响
- 可考虑首启时按需下载（带 SHA-256 校验），而非打进 bundle

---

## 六、参考

- OpenMinis 集成文档：`deps/ISH_INTEGRATION.md`（本仓库 /tmp/OpenMinis 有克隆）
- OpenMinis ish fork：<https://github.com/OpenMinis/ish-arm64> （`feature-arm64` 分支）
- iSH 上游：<https://github.com/ish-app/ish>
- 当前依赖：<https://github.com/Lolendor/ish-arm64-pkg>
- Alpine Linux（aarch64）：<https://alpinelinux.org/>
