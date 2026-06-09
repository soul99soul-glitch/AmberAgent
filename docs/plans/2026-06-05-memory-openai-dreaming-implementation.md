# AmberAgent 记忆系统改进实施计划

基准：`main @ 8e57c3cf`

## 1. Goal / Non-goals

Goal：

- 以 `MemoryRecord` / candidate / event 为真源，Summary 只做只读投影，不物化第二真相源。
- Phase 1 聚焦 P0：修 ⑪ 召回门控、补 durable 偏好供给、做召回期时间降权、Summary 只读 MVP、修 Daydream 文案/idle 接线。
- 对齐 OpenAI 三条 eval：带上文、守偏好、随时间变对；先用确定性规则和 JUnit 锁回归。

Non-goals：

- 不上向量库 / embedding / RAG。
- 不默认开启 LLM dream，不做 mutating LLM dream auto-apply。
- 不做 supersede schema 迁移；Phase 1 只做召回期降权。
- 不自动混池 `AssistantMemory`；Dream 写回仅限 `MemoryRecord`。

## 2. 背景与断点

- ⑪：`MemoryRecallStore` 不撞词且非 pinned/CORE/FEEDBACK 即丢弃，导致 `long_term/USER` 稳定偏好召不回。
- ②/③：`MemoryExtractor` 只自动转正高置信 `SHORT_TERM/PROJECT`，durable USER/FEEDBACK 偏好会卡在候选队列。
- ⑫：scope 权重短期高于长期，短期碎片可能压过长期偏好。
- ④：scope/kind 坏 JSON 会静默落默认值，默认落库项不能自动转正，也不能享受 always-eligible。
- ⑥/⑧：Dream 默认关闭且无 synthesize 算子，Phase 1 不依赖 dream 才能让真机立刻有感。
- ⑬：冲突事实无 supersede；Phase 2 以“新条 + 归档旧条”解决。
- ⑦：`runOnlyOnIdle` 当前是死字段，设置页文案与 scheduler idle 硬约束不一致。

## 3. Phase 1

- [x] 1. Eval fixture + 单测骨架
  - 路径：`docs/eval/memory-recall-fixtures.md`、`app/src/test/java/app/amber/agent/data/memory/MemoryRecallStoreTest.kt`
  - 新增 ≥10 条中英 fixture；测试文件保留在 `agent/data/memory/` 目录，包名为 `package app.amber.core.memory`。

- [x] 2. 供给 + ⑪ 门控 + ④ 守卫 + ⑫ 权重再平衡
  - 路径：`MemoryExtractor.kt`、`MemoryRecallStore.kt`、`MemoryPromptBuilder.kt`、`MemoryRecallStoreTest.kt`
  - `LONG_TERM/USER` 或 `LONG_TERM/FEEDBACK` 且 `confidence >= 0.85` 的显式合法候选自动转正；parse-defaulted 项不自动转正。
  - durable auto-write 写 `DURABLE_MEMORY_CREATED` 事件。
  - `LONG_TERM/USER && confidence >= 0.70`、FEEDBACK、CORE、pinned 可绕过关键词门；NOTE/REFERENCE/PROJECT 不享受该通道。
  - kind 主导排序，USER/FEEDBACK 高于 PROJECT；同时保留活跃 SHORT_TERM/PROJECT 在项目延续 query 下进 Top-N。

- [x] 3. 召回期时间降权 v0
  - 路径：`MemoryRecallStore.kt`、`MemoryRecallStoreTest.kt`
  - 识别 `YYYY-MM-DD`、`YYYY-MM`、`YYYY年M月D日`、`YYYY年M月` + 未来意图词；已过锚 score × `0.35`，只降权不改库。

- [x] 4. Summary 只读 MVP + 候选区下移/折叠
  - 路径：`SettingAgentMemoryPage.kt`
  - 「Amber 对你的了解」置于候选区之前；CORE 并入「稳定偏好」顶部；每行绑定 `#id [scope/kind]` 并复用现有编辑流程。

- [x] 5. 候选安全批量忽略
  - 路径：`SettingAgentMemoryPage.kt`、`SettingAgentMemoryVM.kt`
  - 仅批量 ignore pending 且 `confidence < 0.60` 的候选，高置信 pending 保留。

- [x] 6. Daydream 文案 + `runOnlyOnIdle` 接线
  - 路径：`MemoryDreamScheduler.kt`、`SettingAgentMemoryPage.kt`
  - `runOnlyOnIdle=true` 时要求 device idle；`false` 时不设置 idle 约束；不新增 dream 骨架字段。

- [x] 7. Debug 召回解释最小化
  - 路径：`MemoryRecallStore.kt`、`MemoryPromptBuilder.kt`
  - `memoryRecall.debug=true` 时在 memory context 内输出 score/reasons/freshness。

## 4. Phase 2

- Supersede：新增 review-gated 机制，新事实创建新 `MemoryRecord`，旧事实归档，并保留 source/supersedes 链；不做原地 rewrite。
- Dream review：拆分 `dreamMaintenance` 与 `dreamModel`；Phase 2 真使用时再加字段，避免 Phase 1 制造新的死字段。
- 抽取时间锚：扩展 `MemoryExtractionPrompt`，把明确截止/旅行/事件时间写入 `expiresAt`；相对时间解析失败则保守进候选。
- Candidate lifecycle：补正式 candidate inbox 状态和批量操作审计，长期考虑高置信 durable 自动转正的通知/事件。
- AssistantMemory：召回读侧可分块注入；Dream 写回永远不触碰 AssistantMemory；显式“复制到 Core Memory”另开。
- 向量库：仅当 tags/时间规则/always-eligible 后，失败归因仍主要是“词汇鸿沟型”时再论证。

## 5. Eval

`docs/eval/memory-recall-fixtures.md` 覆盖：

- E1：摄影 setup、Android 项目延续。
- E2：素食偏好、回复风格偏好。
- E3：ASCII 年月、中文年月、历史旅行。
- E4：否定指令。
- E5：无关 NOTE、敏感 Summary。

JUnit：

- `MemoryRecallStoreTest`：auto-write durable、parse-default 守卫、always-eligible、权重排序、时间降权。
- `MemoryDreamPlannerTest`：继续覆盖 `planLocally(records, candidates, now)`。

## 6. 风险与回滚

- Durable auto-write 误写：把阈值从 `0.85` 调高，或回滚到只进候选。
- USER always-eligible 误召回：把阈值从 `0.70` 调高，或仅允许 FEEDBACK/CORE。
- 权重再平衡影响项目延续：用 E1 反向测试兜住；必要时给 active SHORT_TERM/PROJECT 在 query 命中时额外加分。
- 时间降权误判：将 multiplier 从 `0.35` 回滚到 `1.0`，保留 reason 观测。
- 候选批量忽略误删：只操作 pending 且 `confidence < 0.60`；出现风险时移除批量按钮。
- Summary 泄露敏感：先隐藏 Summary section，不影响原记忆库。
- Idle 接线行为变化：默认 `runOnlyOnIdle=true` 保持现状；false 仅在用户显式关闭时生效。

## 7. 验收 checklist

- [x] E1：活跃短期 PROJECT 在延续 query 下仍进 Top-N。
- [x] E2：高置信 durable USER/FEEDBACK 能自动转正为 active record；`LONG_TERM/USER` 无关键词也能召回，且不被短期 PROJECT 挤出预算。
- [x] E3：`2026-07` 和 `2026年7月` 均识别为时间锚并降权；`去过` 历史态不降权。
- [x] E4：FEEDBACK 否定指令可绕过关键词门控召回。
- [x] E5：无关 NOTE/REFERENCE 不因泛词或 parse-default 享受 always-eligible。
- [x] Summary：显示 record id，CORE 有归属，点击进入现有编辑流程，位于候选区之前。
- [x] 候选：durable 高置信先转正；批量忽略只清 `confidence < 0.60` pending，不删 active record、不删高置信 pending。
- [x] durable auto-write：有独立 event 记录；Summary 可见且可编辑/删除。
- [x] Daydream：设置页文案与 `runOnlyOnIdle` 真实行为一致；未新增未读取 dream 字段。
- [x] Debug：开启 `memoryRecall.debug` 后 prompt 包含 recall reasons/freshness。
- [x] 测试：`./gradlew :app:testDebugUnitTest --tests app.amber.core.memory.MemoryRecallStoreTest --tests app.amber.core.memory.MemoryDreamPlannerTest` 通过。
- [x] 无 schema migration、无向量库、无默认 LLM dream、无 AssistantMemory 自动混池。
