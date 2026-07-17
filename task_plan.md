# Task Plan: AmberAgent 原生 HarmonyOS 迁移计划

## Goal

为 AmberAgent 从 Android Kotlin/Jetpack Compose 迁移到 HarmonyOS NEXT 原生 ArkTS/ArkUI 制定可执行计划，明确范围、阶段、风险、验收标准和 AI-assisted 开发方式。

## Current Phase

Phase 4 source-only complete; command-line HarmonyOS build verified; blocked on device/emulator target and signing for runtime spikes

## Phases

### Phase 1: Requirements & Discovery
- [x] 明确用户要求：制定 Android → 原生 HarmonyOS 迁移 plan
- [x] 基于已有 AmberAgent 架构调研和 HarmonyOS 官方文档结论整理关键事实
- [x] 获取架构 reviewer 对计划路线的审查建议
- **Status:** complete

### Phase 2: Planning & Structure
- [x] 确定计划文档结构：执行摘要、目标、架构、阶段、功能矩阵、风险、验收
- [x] 确定迁移策略：原生 ArkTS/ArkUI，不采用 Web 壳作为主线
- [x] 确定计划文件位置：`docs/harmony-native-migration-plan.md`
- **Status:** complete

### Phase 3: Delivery
- [x] 创建 `task_plan.md` / `findings.md` / `progress.md`
- [x] 创建正式迁移计划文档
- [x] 最终检查文件存在与分支状态
- **Status:** complete

### Phase 4: Phase 0 Kickoff Artifacts
- [x] 建立 deepwork 跟踪文件
- [x] 根据 reviewer 意见修正 Phase 0 gating：Gate 0、真正 go/no-go、Rust NAPI 降级
- [x] 创建 `docs/harmony-capability-matrix.md`
- [x] 创建 `docs/harmony-phase0-spike-plan.md`
- [x] 创建安全的 `harmony/` 占位 scaffold（不生成假 DevEco 工程）
- [x] 创建并运行 `scripts/harmony-inventory/scan-harmony-inventory.mjs`
- [x] 运行 Gate 0 检查并记录 blocked 状态
- [x] 创建 source-only fixtures/contracts/skeletons
- [x] 多轮 reviewer 复核并修正 SSE/parser/contract 问题
- [x] 创建 `docs/harmony-phase0-delivery-summary.md`
- [x] 升级 Gate 0 探测与 setup checklist，支持 Command Line Tools 作为 DevEco GUI 之外的解锁路线
- [x] reviewer 复核 Gate 0 unblock package；无必须修正项
- [x] 创建 `docs/harmony-detailed-porting-plan.md`，补充 Android 项目背景和完整鸿蒙化执行计划
- [x] 修正 Gate 0 `hdc [Empty]` 误判，命令行工具链从“看起来 ready”改为“build 可用但 runtime target 缺失”
- [x] 将 `harmony/build-profile.json5` 对齐本机 HarmonyOS API 26 SDK，并通过 `node scripts/harmony-inventory/build-harmony-app.mjs` 验证 `assembleApp` 成功
- **Status:** complete

## Key Questions

1. 迁移是否等同于直接复用 Android 代码？否。HarmonyOS NEXT 不兼容 APK/ART/Java/Kotlin/Compose，主线是原生重写。
2. 哪些部分可复用？Rust 纯逻辑 crate、协议设计、数据模型、Agent 行为模型、部分纯 Kotlin 逻辑语义、测试语料与产品设计。
3. 最大风险是什么？Accessibility、通知监听、本地 CLI/proot 终端、后台长时任务在 HarmonyOS NEXT 上可能缺失或受限。
4. 是否先做 Web 壳？本计划目标是原生鸿蒙迁移；Web 壳仅作为应急/验证方案，不作为主路线。

## Decisions Made

| Decision | Rationale |
|----------|-----------|
| 主线采用 ArkTS/ArkUI 原生重写 | 用户明确要求“原生鸿蒙”，且 HarmonyOS NEXT 不支持 Android APK/Kotlin/Compose 直接运行 |
| Phase 0 先做可行性生死判定 | Accessibility、通知监听、proot/CLI 可能不是代码量问题，而是平台能力问题 |
| 推荐 ArkTS 重写 Agent 内核，Rust 复用 native crates | 避免把 Kotlin 协程/Android 依赖带入鸿蒙；Rust crate 已具备跨平台复用潜力 |
| 不做 1:1 全量平移 | `app` 模块过大且历史包袱多，必须先裁剪再迁移 |
| Rust NAPI 不作为 Chat 生死门 | reviewer 指出 Android 侧 native 均有 fallback，Phase 0 真正 go/no-go 是 SSE、RDB/Preferences、ArkUI+Markdown |
| 不手写假 DevEco 工程文件 | 避免提交看起来像工程但无法编译的 hvigor/module.json5；仅创建 README 占位，等工具链验证后再生成 |
| Gate 0 工具来源可为 DevEco GUI 或 Command Line Tools | Command Line Tools 可提供 hvigor/ohpm/hdc；GUI 主要用于工程创建、自动签名和调试体验 |
| Gate 0 分为 build readiness 和 runtime readiness | 当前 build 已通过；runtime spike 仍需设备/模拟器 target 与签名 |

## Errors Encountered

| Error | Attempt | Resolution |
|-------|---------|------------|
| 无 | 1 | 无需处理 |

## Notes

- 本计划文件属于工作记忆；正式对外计划在 `docs/harmony-native-migration-plan.md`。
- 后续若开始实现，应继续更新本文件和 `progress.md`。
