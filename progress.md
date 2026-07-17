# Progress Log

## Session: 2026-06-15

### Phase 1: Requirements & Discovery
- **Status:** complete
- Actions taken:
  - 创建独立 worktree：`/Users/mi/Downloads/AI/AmberAgent-harmony-preview`
  - 调研 AmberAgent 当前 Android 架构和 HarmonyOS NEXT 官方文档关键限制
  - 获取架构 reviewer 的迁移计划审查建议
- Files created/modified:
  - `task_plan.md`
  - `findings.md`
  - `progress.md`
  - `docs/harmony-native-migration-plan.md`

### Phase 2: Planning & Structure
- **Status:** complete
- Actions taken:
  - 确定迁移计划采用原生 ArkTS/ArkUI 主线
  - 确定阶段划分：Phase 0 生死判定 → Phase 1 共享核心 → Phase 2 Chat 闭环 → Phase 3 P1 能力 → Phase 4 P2 平台能力 → Phase 5 收敛上架
  - 确定 P0/P1/P2 功能矩阵和验收 gate
- Files created/modified:
  - `docs/harmony-native-migration-plan.md`

### Phase 4: Phase 0 Kickoff Artifacts
- **Status:** in_progress
- Actions taken:
  - 启用 deepwork，并建立 `.slim/deepwork/harmony-native-phase0.md` 本地跟踪文件
  - 创建 `.ignore`，允许本地 agent 读取 `.slim/deepwork/`
  - 更新 `.gitignore`，避免提交 `.slim/deepwork/`
  - 创建 `docs/harmony-capability-matrix.md`
  - 创建 `docs/harmony-phase0-spike-plan.md`
  - 请求 oracle 审查 Phase 0 计划，并按建议修正 Gate 0、go/no-go 范围和 Rust NAPI 优先级
  - 创建安全 `harmony/` 占位 scaffold，不提交假 DevEco 工程文件
  - 创建并运行 `scripts/harmony-inventory/scan-harmony-inventory.mjs`
  - 生成 7 个 inventory 文档到 `docs/harmony-inventory/`
  - 请求 oracle 复核 Phase 0 产物，结论为“通过，可继续进入 Phase 1 准备”
  - 按建议修正 inventory 脚本：补 `feature/tools/`，支持 `src/main/(java|kotlin)`，排序输出，加入 source commit，修正 match 计数
  - 重新生成 inventory 文档
  - 运行 Gate 0 toolchain 检查，结论为 blocked：本机缺 DevEco/hvigor/ohpm/hdc/device target
  - 修正 Gate 0 ID 冲突：脚本探针使用 `G0-Txx`，矩阵概念 gate 保留 `G0-xx`
  - 将依赖设备/SDK 的 matrix 行从 `spike_ready` 调整为 `toolchain_blocked`
  - 创建 Phase 0B source-only fixtures：streaming/messages/transformers/agent_loop/markdown/storage/contracts
  - 创建 source-only skeletons：SSE parser shape、RDB schema、Preferences shape、Agent loop types、Markdown strategy
  - 运行 fixtures 校验，51 条 JSON/JSONL 记录通过
  - Phase 0B/0C review 通过，无必须修正；按建议修正 SSE parser skeleton 与 Android 多行 `data:` 语义偏差
  - 新增 `scripts/harmony-inventory/check-source-contracts.mjs`，将部分 fixtures 升级为可执行 source-only contracts
  - 重新运行 fixtures/contract 校验：52 条 JSON/JSONL 记录通过，5 组 source contract checks 通过
  - 可执行契约 review 发现 TS skeleton 单行 SSE 返回裸对象且 check 脚本没有跑到真源；已修复为数组返回，并抽出 `openai_sse_parser_reference.mjs` 作为可执行参考实现
  - 新增尾空格 nested data fixture；修正 failed event recoverable 标记
  - 最新校验：53 条 JSON/JSONL 记录通过，6 组 source contract checks 通过
  - 最终 source-only review 通过，结论为：source-only phase can stop here until DevEco/Gate0 unblocks
  - 更新 evidence log 到 54 条 JSON/JSONL、10 组 source contract checks
  - 在 streaming contract 中标注：tool delta 合并、reasoning finishedAt、final full-message replacement 尚未 executable，留给 Phase 1 ArkTS accumulator 实现
  - 创建 `docs/harmony-phase0-delivery-summary.md`，作为 Phase 0 source-only 交付总结
  - 升级 `check-harmony-gate0.mjs`：探测 PATH、zsh interactive PATH、常见 DevEco app bundle、常见 SDK/toolchains、Command Line Tools 路径
  - Gate 0 解锁包经 oracle 复核；按必须修正将 DevEco GUI 从唯一硬阻塞改为“DevEco GUI 或 Command Line Tools 任一可作为工具来源”
  - 更新 `docs/harmony-gate0-setup-checklist.md`，明确轻量 Command Line Tools 路线和完整 DevEco Studio 路线
  - 复核后仅剩非阻塞建议；已修正 `G0-T06` missing detail 同时列出 `hvigor` 与 `hvigorw`
  - 最新 Gate 0 仍为 blocked：本机缺 DevEco/Command Line Tools、hvigor/ohpm/hdc、device/emulator target
  - 创建 `docs/harmony-detailed-porting-plan.md`，补充 Android 原项目背景、路径、技术栈、模块架构、核心数据模型、平台能力迁移映射和分阶段鸿蒙化计划
  - 2026-07-16 重新检查工具链：Command Line Tools、SDK、hvigorw、ohpm、hdc 已可用；修正 `hdc list targets` 输出 `[Empty]` 被误判为 found 的 Gate 0 bug
  - 发现本机 HarmonyOS SDK 为 API 26 / `26.0.0.23`，将 `harmony/build-profile.json5` 从旧格式 `5.0.0(12)` 对齐到 `26.0.0`
  - 定位命令行构建所需环境变量：`DEVECO_SDK_HOME` 需指向 SDK 父目录，`OHOS_BASE_SDK_HOME` 指向 `default/openharmony`
  - 新增 `scripts/harmony-inventory/build-harmony-app.mjs`，自动补齐命令行构建环境
  - 运行 `node scripts/harmony-inventory/build-harmony-app.mjs`，真实 HarmonyOS 工程 `assembleApp` 构建成功；仍未上机运行，因为 `hdc list targets` 返回 `[Empty]`
- Files created/modified:
  - `.gitignore`
  - `.ignore`
  - `docs/harmony-capability-matrix.md`
  - `docs/harmony-phase0-spike-plan.md`
  - `docs/harmony-inventory/README.md`
  - `docs/harmony-inventory/rust-native.md`
  - `docs/harmony-inventory/providers-streaming.md`
  - `docs/harmony-inventory/storage-room-preferences.md`
  - `docs/harmony-inventory/platform-android-apis.md`
  - `docs/harmony-inventory/tools.md`
  - `docs/harmony-inventory/ui-pages.md`
  - `harmony/README.md`
  - `harmony/spikes/README.md`
  - `harmony/native/rust-napi/README.md`
  - `harmony/tests/fixtures/README.md`
  - `scripts/harmony-inventory/scan-harmony-inventory.mjs`
  - `scripts/harmony-inventory/check-harmony-gate0.mjs`
  - `scripts/harmony-inventory/validate-harmony-fixtures.mjs`
  - `scripts/harmony-inventory/check-source-contracts.mjs`
  - `scripts/harmony-inventory/build-harmony-app.mjs`
  - `docs/harmony-gate0-report.md`
  - `harmony/tests/fixtures/**`
  - `harmony/spikes/source-only/**`
  - `docs/harmony-phase0-delivery-summary.md`
  - `docs/harmony-detailed-porting-plan.md`

## Test Results

| Test | Input | Expected | Actual | Status |
|------|-------|----------|--------|--------|
| 文档存在性检查 | `docs/harmony-native-migration-plan.md` | 文件存在 | 文件存在，638 行 | pass |
| Git 状态检查 | `git status --short --branch` | 显示新增文档 | `harmony-preview` 分支显示 4 个新增文件 | pass |
| Inventory generation | `node scripts/harmony-inventory/scan-harmony-inventory.mjs` | 生成 inventory 文档 | 生成 7 个文件到 `docs/harmony-inventory` | pass |
| Phase 0 artifact review | oracle reviewer | 必须修正项为 0 | 通过；仅建议修正 inventory 脚本覆盖/确定性 | pass |
| Inventory regeneration | `node scripts/harmony-inventory/scan-harmony-inventory.mjs` | 生成含 source commit 的 inventory | 成功生成，`tools.md` 包含 357 个真实匹配 | pass |
| Gate 0 check | `node scripts/harmony-inventory/check-harmony-gate0.mjs || true` | 生成 toolchain report | `blocked`; report 写入 `docs/harmony-gate0-report.md` | pass |
| Fixture validation | `node scripts/harmony-inventory/validate-harmony-fixtures.mjs` | JSON/JSONL 全部可解析 | 51 条记录通过 | pass |
| Source contract checks | `node scripts/harmony-inventory/check-source-contracts.mjs` | SSE/storage/contract references 自检通过 | 6 组检查通过 | pass |
| Final source-only validation | `node scripts/harmony-inventory/validate-harmony-fixtures.mjs && node scripts/harmony-inventory/check-source-contracts.mjs` | fixtures/contracts 通过 | 54 条 JSON/JSONL、10 组 contract checks 通过 | pass |
| Gate 0 unblock package review | oracle reviewer | 必须修正项为 0 | 可继续；DevEco GUI 不再是唯一硬阻塞 | pass |
| Latest Gate 0 check | `node scripts/harmony-inventory/check-harmony-gate0.mjs || true` | 生成 toolchain report | `blocked`; 缺工具来源、hvigor/ohpm/hdc/device target | pass |
| HarmonyOS command-line build | `node scripts/harmony-inventory/build-harmony-app.mjs` | `assembleApp` 构建通过 | `BUILD SUCCESSFUL`; 未配置 signing，未连接 device target | pass |

### Phase 4 Final State
- **Status:** complete_source_only
- **Status:** command_line_build_verified
- **Blocked by:** runtime/device Gate 0 readiness (`hdc list targets` returns `[Empty]`; no device/emulator target; signing still not configured)
- **Next when unblocked:** install/run on target, then run P0 runtime spikes for SSE, RDB/Preferences, ArkUI + Markdown streaming

## Error Log

| Timestamp | Error | Attempt | Resolution |
|-----------|-------|---------|------------|
| 2026-06-15 | 无 | 1 | 无需处理 |

## 5-Question Reboot Check

| Question | Answer |
|----------|--------|
| Where am I? | Phase 4: Phase 0 source-only complete；Gate 0 unblock package 已复核 |
| Where am I going? | Gate 0 blocked；停止扩张 source-only，等待 DevEco Studio 或 Command Line Tools + SDK + 设备/模拟器 target 后再跑 P0 spike |
| What's the goal? | 制定 Android 到原生 HarmonyOS 迁移计划 |
| What have I learned? | 见 `findings.md` 和 `docs/harmony-capability-matrix.md` |
| What have I done? | 创建 planning 文件、正式迁移 plan、Phase 0 matrix/spike plan、harmony 占位 scaffold、inventory/Gate0/fixture/contract 校验脚本、fixtures、source-only skeletons，并修复 review 发现的 SSE contract bug 和 Gate 0 GUI-only 阻塞问题 |

## Session: Plan Review (2026-06-17)

- **Goal:** 对已完成的迁移计划做一次 reviewer 级自查，找出会在执行期反咬的硬伤并修正，再提交。
- **Findings:** 战略判断正确（原生重写、P0 Chat、P2 降级、Gate 0 纪律）；但有 6 处执行期硬伤。
- **Changes applied (docs only, no ArkTS code):**
  - `docs/harmony-native-migration-plan.md`：
    - 顶部加文档分工声明（本文件=战略，detailed-plan=执行权威；战略里程碑 `S0–S5` vs 执行 `M0–M7`）。
    - 里程碑表 M0–M5 改为 S0–S5，加"对应执行里程碑"列，消除 ID 冲突。
    - §1 + §12 时间数字标注"范围估算，非承诺"。
    - 新增 §8.1 时间估算假设清单（含 ArkTS 语料稀少 disclaimer）。
    - §6.1 迁移工厂加产能预期 disclaimer。
  - `docs/harmony-detailed-porting-plan.md`：
    - 顶部加权威等级声明 + cross-link + 审阅记录。
    - 新增 §5.2 三方库 availability 表（Room/OkHttp/Koin/Compose 有；Coil/sqlite-vector/MCP SDK/MLKit 缺口；QuickJS 需重评估；Rust 待验证）。标出 3 个硬缺口：sqlite-vector（memory 降级）、MCP ArkTS SDK（产品卖点）、Coil（图片管线）。
    - 新增 §5.3 ArkTS 技术风险（R-ARK-1..5）：ArkTS≠TS、`@Sendable` 并发限制、ArkUI 深度刷新、AI 语料稀少、流式渲染性能未证实；附 Gate 0 后的 4 个最小 spike。
    - 新增 §10.6 Phase 2 性能预算（≥50fps / 首帧<800ms / P95 单 token<100ms / 长列表<16ms / 冷启动<2s / 取消<200ms / 内存<250MB），作为 Phase 2→3 硬 gate。
    - 新增 §10.7 i18n 前置约束：Phase 2 起所有用户可见字符串资源化，禁硬编码字面量（除 `// i18n-todo` 调试）。
- **Verification:**
  - `node scripts/harmony-inventory/check-source-contracts.mjs` → 10 组通过（不变）。
  - `node scripts/harmony-inventory/validate-harmony-fixtures.mjs` → 54 条通过（不变）。
- **Decision:** 本次只改文档、不碰 ArkTS 代码（Gate 0 仍 blocked，不写业务代码）；修正随 Phase 0 产物一起提交。
- **Next:** 提交后仍停在 Gate 0 blocked；解锁工具链后按 §5.3 spike 清单 + §8 Phase 0 真实 spike 推进。
