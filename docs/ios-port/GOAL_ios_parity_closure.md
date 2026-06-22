# GOAL: ios-parity-closure

> 目标驱动规格（成功标准，非步骤）。每个 phase = 入口拦截 + 可证伪出口 + 对抗验证 subagent + 循环。
> 用法心智：跑 goal → 按 phase 依赖推进 → 每个 phase 的"完成"由独立对抗验证裁决，自报不算数。

## meta
- **north_star**: 把 iOS 高级能力从"看着完成"变成 DoD 六条全绿；**先关 P0 发布阻塞，再补宽度**。
- **measure**: `truth_matrix.green_fraction`（见 success_metric）——以此替代不可验证的"parity %"。
- **assumption**: 分支为 *pre-release*。若已进 TestFlight/用户手中 → 激活 `P2.hotfix` 并**前置到 P1 之前**。
- **model_tiering**: `scout=haiku, verify=sonnet, refute=opus`（分层省钱）。

## global_intercepts —— 命中任一即**立即停整条 goal**，上报人类
- 任何**已变绿的矩阵格子再次变红**（回归）。
- 红灯测试被删除、被 `@skip`/env-gate 绕过，或断言被悄悄弱化。
- 对运行时 UserDefaults / 默认备份做 dump，出现**任何明文凭据**。
- 某 phase inner_loop 达 `max_iterations` 仍未过 gate。
- **visual_regression**：关键屏幕快照 vs baseline 出现**非预期**差异（HARD-LOCK 视觉文件出现任何 diff，或 VISUAL-GUARDED 文件改动导致受影响屏幕出现非 intended 视觉变化）。

## verification_protocol —— 每个 phase 的 EXIT 必经此，**自报不构成通过**
1. `scout(haiku)`：取该 phase 绑定测试的**真实运行结果** + 改动点真实代码，不下判断。
2. `verify(sonnet)`：逐条比对 EXIT 准则 vs 真实状态。
3. `refute(opus)`：对抗——专门找"测试假绿 / 改在错路径 / 双路径仍漏 / live-smoke 未配 key 被 skip"的反证。
- **pass_rule**：仅当 refute 找不到反证 → `PASS`；否则 `BLOCK`，反证回灌 inner_loop。自报永不构成通过。
- **visual_rule**：若该 phase 改动了 `visual_protection.locked_visual_files`——HARD-LOCK 有 diff → refute 直接 `BLOCK`；VISUAL-GUARDED 改动 → verify **必须**附受影响屏幕的 baseline 快照比对，仅 intended-and-approved 差异可通过。

## loop_policy
- **inner**：`while EXIT 未全绿 { 实现 → 跑 gate → red? 诊断+修 : 进 verify }`；`max_iterations=4`，否则 escalate。
- **outer**：按 phase 依赖顺序推进；每个 phase 是一个 rollback 点；`P0_block` 矩阵目标达成 = goal 主体完成。
- **discovery（仅 P0）**：loop-until-dry —— 连续 2 轮矩阵盘点无新增红格，才算盘点完。

## success_metric — truth_matrix（即度量本身）
- **rows**: `chat, deepread, subagent_standalone, subagent_chat, council, search, backup`
- **cols**: `entry_real, provider_real, exec_real, honest_fail, secure_store, persist, recover, verified`
- **cell_green_iff**: 该格绑定的具名测试全过 **且** 非 skip-gate **且** 经 verification_protocol 确认。
- **goal_done_when**:
  - `P0_block`: `cols[entry_real..secure_store]` 在 `[chat, deepread, subagent_standalone, subagent_chat, council]` **全绿**。
  - `recover`: `cols[recover]` 在上述行 ≥ 约定阈值。

## interim_safeguard（P0→P4 期间，针对已发布构建）
Deep Read / Council 遇**非 OpenAI-compatible** provider → **明确禁用/提示**，绝不静默打 `/chat/completions`。

## visual_protection（视觉不回归 —— 已有 UI/视觉一律不得被这次重构改坏）
- **baseline（P0 建立）**: 关键屏幕快照存为基线 —— 聊天(Liquid Glass)、Appearance/主题、Board、Council、Provider 列表。机制: swift-snapshot-testing 或模拟器截图（**非** live-key 依赖）。
- **locked_visual_files**:
  - **HARD-LOCK（禁止修改；本 goal 无任何红格涉及它们 → 出现 diff 即 refute BLOCK）**: `iosApp/iosApp/ChatView.swift`、`iosApp/iosApp/PlaceholderViews.swift`、`iosApp/iosApp/AppearanceSettingsView.swift`。
  - **VISUAL-GUARDED（可因逻辑改动，但任何 diff 必须对受影响屏幕做快照比对）**: `iosApp/iosApp/BoardView.swift`(Deep Read)、`iosApp/iosApp/CouncilChatRuntimeView.swift`(Council)、`iosApp/iosApp/AppShell.swift`(导航/chrome)。
- **唯一允许的预期视觉变化**: P4 的 Deep Read/Council 失败态(failed/partial)展示，以及 interim_safeguard 的禁用/提示；改动中必须**显式标注 intended** 并经人工确认。其余一切视觉差异视为回归。
- **pre-start hygiene**: 开跑前把工作区未提交改动（当前为 `iosApp/iosApp/AppShell.swift`、`.claude/settings.local.json`）commit 或 stash，从干净状态起步。

---

## phases（ordered）

### P0 — baseline_and_red_lights
- **goal**: 把已确认缺陷固化为"必然失败且不可绕过"的**白盒**测试 + 建真相矩阵。
- **entry_gate**: 无（起点）。
- **exit**:
  - 真相矩阵建成，P0 行红格全部对应到具名测试。
  - 下列红灯测试存在且当前为 **RED**（白盒，**不依赖 live key**）：
    `test_deepread_claudeSelected_constructsClaudeSetting`,
    `test_deepread_allStagesThrow_statusFailed`,
    `test_deepread_stageEmpty_notMarkedReady`,
    `test_deepread_searchFailure_isSourceFailure`,
    `test_subagent_standalone_usesEnginePath`,
    `test_council_claudeSelected_constructsClaudeSetting`,
    `test_settings_encode_containsNoCredential`,
    `test_backup_default_containsNoCredential`,
    `test_recovery_killMidStream_runInterruptedOrResumed`
  - **视觉基线已建**: 关键屏幕快照存为 baseline；`visual_protection` 锁定清单生效；工作区未提交改动已 commit/stash。
- **verify**: refute 专项查"是否有测试其实是 live-smoke、未配 key 就 skip"。
- **loop**: discovery（loop-until-dry）。
- **rollback**: n/a。

### P0.5 — vertical_slice（Deep Read + Claude）⭐
- **goal**: 让 **ONE** 能力端到端打通 DoD 六条，作为后续可复制的参考模板。
- **entry_gate（拦截）**: P0 EXIT 全绿。
- **exit**:
  - `deepread` 行的 `entry_real, provider_real, exec_real, honest_fail, secure_store` 全绿。
  - 上述五条对应的 P0 红灯测试由 **RED→GREEN**。
  - 产出 `SLICE_TEMPLATE.md`：记录"真实 provider 解析 / 诚实失败状态机 / 凭据落 Keychain"的可复制做法。
- **verify**: refute 检查"是否真走 Claude 原生 `/messages` 而非 `/chat/completions`；失败是否真标 `failed`"。
- **loop**: inner。
- **decision_locked（防过度工程化）**:
  - **不**引入 `ProviderExecutionContext`；复用 chat 已有的 `ProviderSetting` 解析。
  - 凭据用 **iOS-only 方案 B**（encode 前剥离 + Keychain 边表），**不动共享 `ProviderSetting`**。
- **rollback**: 切片分支。

### P1 — protected_main_merge
- **goal**: 分支恢复可合并，native 与源码一致，且 P0 红灯 / 已绿格全部存活。
- **entry_gate**: P0.5 EXIT 全绿。
- **exit**:
  - `integration/ios-main-convergence` 分支：merge（**非 squash**）完成。
  - iOS build + KMP framework 链接通过。
  - `AmberNative.xcframework` 重建，二进制与 `native/*/src` 一致（git: 无 src 提交晚于 xcframework）。
  - P0 全部红灯测试仍存在且未被冲突绕过；P0.5 已绿格仍绿。
- **verify**: refute 逐一核对 32 个冲突文件的解决**未弱化任何已绑定断言**。
- **loop**: 分块合并，每块（`ProviderSetting → UIMessage/accumulator → runtime → search → council → deepread → FFI`）单独跑窄测试；某块红 → 回滚该块。
- **intercept**: main 收敛期**冻结**或分块 rebase-forward（不追移动靶）。
- **rollback**: 每块一个 commit 边界。

### P2 — credentials（iOS-only 方案 B）
- **goal**: 任何正式入口写入的凭据都**不进** UserDefaults / 默认备份。
- **entry_gate**: P1 EXIT 全绿。（若 `assumption=TestFlight` → 本 phase 作为 `P2.hotfix` **前置到 P1 之前**。）
- **exit**:
  - `test_settings_encode_containsNoCredential` RED→GREEN。
  - `test_backup_default_containsNoCredential` RED→GREEN。
  - 运行时 UserDefaults dump 无明文凭据（自动检查）。
  - 迁移：旧三处存储对账；**Keychain 写入回读确认后**才删 UserDefaults 旧值。
  - `secure_store` 列在所有行变绿（含 search / tts / remote-sync 凭据）。
- **verify**: refute 跑真实 UserDefaults + 备份 dump 并 grep 凭据；验证迁移失败不毁原数据。
- **loop**: inner。
- **note**: `credentialRef` 跨端统一 = **单独立项**，不在本 goal P0 范围。
- **rollback**: 凭据存储为独立 commit；迁移有 dry-run。

### P3 — unified_provider_resolution
- **goal**: 所有高级能力用**用户实际选择的 provider**，删除手搓 OpenAI 的模式。
- **entry_gate**: P2 EXIT 全绿。
- **exit**:
  - 全仓 grep 不到重建式 `ProviderSetting.OpenAI(... chatCompletionsPath:"/chat/completions")`（OpenAI 真为目标时除外）。
  - `provider_real` 列在 `[deepread, council, subagent_standalone]` 变绿。
  - OpenAI×Claude 验收矩阵：chat / tool / deepread / subagent / council / 模型列表 / 取消错误 **每格有测试**。
- **verify**: refute 确认删除的是重建点、而非 adapter 的合法构造；Claude 真走原生协议。
- **loop**: inner；按 P0.5 模板复制到各能力。
- **decision_locked**: `capability` 字段**不引入**；只透传 `(provider, model, params)`。
- **rollback**: per-capability commit。

### P4 — close_three_entries（deepread / subagent / council）
- **goal**: 三条正式入口达 DoD（诚实失败 + 真实执行 + 单执行链 + Room 持久化）。
- **entry_gate**: P3 EXIT 全绿。
- **exit**:
  - **deepread**: 阶段状态机（queued/running/ready/failed）+ `partial_failed/failed` 语义；`honest_fail` 列绿；余下 deepread 红灯全绿。
  - **subagent**: 独立页改走 `runViaEngine`；`test_subagent_standalone_usesEnginePath` GREEN；高风险工具上浮父审批 + report 缺失重试。
  - **council**: provider fidelity（P3 已覆盖）+ Room 持久化（messages/seats/round/pendingQuestion）；可重开 + 强杀恢复。
  - `persist` 列在三行变绿。
- **verify**: refute 注入"第 N 段失败 / 搜索失败 / 取消 / 单段重试"，确认状态如实；council 退出再进消息仍在。
- **loop**: inner，每能力独立。
- **rollback**: per-capability。

### P5 — interruptible_runtime
- **goal**: 强杀不丢内容、不重复执行工具。
- **entry_gate**: P4 EXIT 全绿（执行链已统一，checkpoint 才不会加到错路径）。
- **exit**:
  - 数据模型 `AgentRun / ToolCall / Approval / StreamCheckpoint / CapabilitySnapshot` 落地。
  - 写入顺序：`create→running→checkpoint→tool pending→exec→result→resume→done`。
  - 幂等键 `runId + toolCallId + inputDigest`：强杀重启**不二次**写文件 / 记忆 / 远程 / MCP。
  - `test_recovery_killMidStream_runInterruptedOrResumed` GREEN；`recover` 列达阈值。
  - 启动恢复：可恢复续跑 / 待审批重显卡片 / 半参数 tool 标 interrupted / **已执行但结果丢失的 tool 不自动重跑**。
- **verify**: refute 用 fault-injection harness 在不同点反复强杀，断言无重复副作用。
- **loop**: inner。
- **rollback**: 恢复层为独立模块，可整体禁用。

### P6 — capability_width（P0 全关后再扩）
- **goal**: 搜索编排优先于更多 provider。
- **entry_gate**: `P0_block` 矩阵目标已达成。
- **exit（按优先）**: shared search orchestrator → deepread 来源质量/citation → OpenAI Response API → Gemini → subagent 父子审批恢复 → council 多 provider 席位 → MCP 图片 → webmount 多标签/截图 → OCR。
- **verify**: 每项独立 verify；不阻塞 P0。
- **loop**: 按优先逐项，可并行。
- **rollback**: per-feature flag。

### P7 — native_and_release
- **goal**: 原生入口 + 发布验收。
- **entry_gate**: P5 EXIT 全绿。
- **exit**: Share Extension / App Intents / Shortcuts / Universal Links / 通知深链；iPad / 多窗口；Dynamic Type / VoiceOver；弱网 / 低存储 / 内存压力；App Store 隐私说明；真相矩阵 `verified` 列全绿（scripted + 真实 provider smoke）。
- **verify**: refute 复核 `verified` 列的 live-smoke **真的跑过、非 skip**。
- **loop**: inner。
- **rollback**: 发布前 tag。
