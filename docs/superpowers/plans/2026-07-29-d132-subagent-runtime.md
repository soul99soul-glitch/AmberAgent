# D-132 Subagent Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Android `feature/subagent` 约 2030 行运行子系统逐字移植到 Harmony chat HAR，并完成设置水合、隔离执行、任务看板、五件公共工具与 ChatPage 装配。

**Architecture:** D-128 的 `SubAgentRuntimeSetting`、六内置角色、override 和 mention 逻辑保持唯一事实源；新增纯域模型/验证/报告/工具作用域/转录读取，再由端口化 runner 与 manager 组合。平台文件、模型解析和 provider 调用只放 entry，manager 与 D-131 task tools 共享同一个 `AgentTaskStore`。

**Tech Stack:** ArkTS/TypeScript、ArkUI V2、Node test/tsx、HarmonyOS API 20、既有 `AgentTool`/`ToolRegistry`/`ChatStreamProvider`/`AgentTaskStore`/`SessionAccessGrantStore`。

**治理分片:**

- D-132a：纯域基础（models/validator/report/scope/transcript）
- D-132b：隔离 runner + manager 生命周期/任务看板
- D-132c：SubAgentTools 五件 + 设置持久化 + entry/ChatPage 装配

每个分片均先测试、后实现、经规格审查与质量审查、跑三重门，然后执行 `feat(...)` + `chore(state)` 双提交；Android 目录只读。JSON/对象属性顺序仅 `subagent_report` schema 与 transcript 外层 `event/created_at_ms/payload` envelope 属于协议要求；普通 model、payload、metadata 属性顺序不构成契约。

---

## File structure

| Path | Responsibility |
|---|---|
| `harmony/chat/src/main/ets/chat/subagent_models.ts` | TaskSpec/Run/Result/Status/ValidationResult/Profile、默认构造器与精确 wire literals（不引入通用 codec） |
| `harmony/chat/src/main/ets/chat/subagent_validator.ts` | 解析任务、角色/模型/工具/预算验证、Kotlin 稳定名称 |
| `harmony/chat/src/main/ets/chat/subagent_report_tool.ts` | 内部 `subagent_report` 捕获、截断与 fallback |
| `harmony/chat/src/main/ets/chat/subagent_tool_scope.ts` | child-only registry 与 discovery/list/policy 替换 |
| `harmony/chat/src/main/ets/chat/subagent_transcript.ts` | JSONL 事件、受限 tail reader 与文件端口 |
| `harmony/chat/src/main/ets/chat/subagent_runner.ts` | 隔离助手、child loop、流式输出、审批/报告重试 |
| `harmony/chat/src/main/ets/chat/subagent_manager.ts` | admission/lifecycle/timeout/cancel/wait/live state/persist/task snapshot |
| `harmony/chat/src/main/ets/chat/subagent_tools.ts` | `subagent_list/start/read/wait/cancel` 五件与系统提示 |
| `harmony/entry/src/main/ets/platform_impl/SubAgentStore.ets` | 私有 run 目录、JSONL append/tail/containment 文件端口 |
| `harmony/entry/src/main/ets/platform_impl/AgentRuntimePrefs.ets` | 完整 subagent 设置 RMW/水合，保留未知键 |
| `harmony/entry/src/main/ets/di/AppContainer.ets` | runner/manager/tools Promise 单例与 provider/file adapter |
| `harmony/entry/src/main/ets/pages/ChatPage.ets` | Android ChatService 顺序下动态挂载 supervisor tools |

---

### Task 1: Runtime models and default constructors (D-132a)

**Android anchors:**
- `feature/subagent/api/src/main/kotlin/app/amber/feature/subagent/SubAgentModels.kt:1-207`

**Files:**
- Create: `harmony/chat/src/main/ets/chat/subagent_models.ts`
- Create: `harmony/chat/src/test/subagent_models.test.ts`
- Modify: `harmony/chat/src/main/ets/index.ts`

- [ ] **Step 1: Write failing model tests**

Tests must pin defaults and exact wire values:

```ts
assert.equal(DEFAULT_SUB_AGENT_MAX_CONCURRENT_RUNS, 2);
assert.equal(DEFAULT_SUB_AGENT_TIMEOUT_MS, 300_000);
assert.equal(DEFAULT_SUB_AGENT_MAX_TURNS, 4);
assert.equal(DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS, 12_000);
assert.deepEqual(SUB_AGENT_RUN_STATUS_WIRE, [
  'running', 'completed', 'approval_required', 'failed',
  'cancelled', 'timed_out', 'interrupted',
]);
```

Use named default constructors and exact Android wire literals. Do not add generic codecs or require model property order, payload/metadata property order, nullable-key omission, or generic model roundtrips unless a later protocol boundary explicitly needs them.

- [ ] **Step 2: Verify RED**

Run: `cd /Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat && npx tsx --test src/test/subagent_models.test.ts`

Expected: FAIL because `subagent_models.ts` does not exist.

- [ ] **Step 3: Implement named ArkTS interfaces and default constructors**

Use named interfaces only; no object-spread, indexed-access types, or generic codec layer. Preserve Android defaults, exact serial-name literals, optional fields, and numeric primitive parity without treating ordinary model property order as protocol.

- [ ] **Step 4: Verify GREEN**

Run the exact command from Step 2 and `npx tsc --noEmit`; expect all model tests pass and typecheck clean.

---

### Task 2: Stable names and validator (D-132a)

**Android anchors:**
- `feature/subagent/src/main/kotlin/app/amber/feature/subagent/SmartSubAgentNames.kt:1-16`
- `feature/subagent/src/main/kotlin/app/amber/feature/subagent/SubAgentValidator.kt:1-382`
- `app/src/test/java/app/amber/agent/data/agent/subagent/SubAgentValidatorTest.kt:1-509`

**Files:**
- Create: `harmony/chat/src/main/ets/chat/subagent_validator.ts`
- Create: `harmony/chat/src/test/subagent_validator.test.ts`

- [ ] **Step 1: Port the Android validation matrix as failing tests**

Cover at minimum:

```ts
assert.equal(DEFAULT_SUB_AGENT_OUTPUT_FORMAT,
  'Brief summary, findings, evidence, risks, and recommended next steps.');
assert.equal(DEFAULT_SUB_AGENT_BOUNDARIES,
  'Stay within the assigned objective; do not spawn subagents; report once and stop.');
assert.equal(kotlinStringHashCode('explore-news'), /* signed 32-bit Android result */);
```

Pin: nonblank task fields, context 6000 cap, raw tool-result markers, `subagent_id`/`custom_subagent` mutual exclusion, unavailable tools, `subagent_*` recursion rejection, model/temperature/reasoning validation, min/max budgets, empty dynamic tool set, built-in rejection in smart mode, generic-name replacement, dynamic prompt synthesis (boundary + report requirements), history profile/grant fields, and exact errors:

```text
Built-in subagents are disabled in smart dynamic mode; use custom_subagent.
Tool allowlist contains unavailable tools: ...
```

- [ ] **Step 2: Verify RED**

Run only `subagent_validator.test.ts`; expected missing module failure.

- [ ] **Step 3: Implement pure validator**

Dependencies are injected snapshots of available tools and model metadata. Reuse D-128 `SUB_AGENT_BUILT_INS`, `SubAgentDefinition`, `SubAgentOverride`, `subAgentFindDefinition`, and `subAgentExtractMentions`; do not duplicate role prompts.

Kotlin name pool in exact order:

```ts
['Alice','Henry','Oliver','Emma','Jack','Charlotte','William','Amelia','James',
 'Grace','George','Sophie','Thomas','Lucy','Daniel','Ella','Samuel','Mia']
```

Hash must emulate signed 32-bit Kotlin `String.hashCode()`.

- [ ] **Step 4: Verify GREEN**

Run validator test and chat `tsc`; expect pass.

---

### Task 3: Structured report capture (D-132a)

**Android anchors:**
- `feature/subagent/src/main/kotlin/app/amber/feature/subagent/SubAgentReportTool.kt:1-139`
- `app/src/test/java/app/amber/agent/data/agent/subagent/SubAgentReportToolTest.kt:1-101`

**Files:**
- Create: `harmony/chat/src/main/ets/chat/subagent_report_tool.ts`
- Create: `harmony/chat/src/test/subagent_report_tool.test.ts`

- [ ] **Step 1: Write failing report tests**

Pin schema order `summary/findings/evidence/risks/recommended_next_steps/confidence/error`, summary 4000, error 1000, confidence 64, list max 12 and item max 1000, failed result when error nonblank, capture latest report, and exact strings:

```text
subagent_report requires a non-empty summary or findings.
Structured subagent report recorded. Finish with normal human-readable text.
Subagent completed without structured report or text output.
Subagent finished without calling subagent_report; summary was derived from visible text.
```

- [ ] **Step 2: Verify RED**

Run the new test; expected missing module failure.

- [ ] **Step 3: Implement report capture/tool/fallback**

Use explicit `JsonObject` insertion and injected capture closure. Tool acknowledgment is only tool output and must not become display text.

- [ ] **Step 4: Verify GREEN**

Run report tests and `tsc`; expect pass.

---

### Task 4: Child tool scope (D-132a)

**Android anchors:**
- `feature/subagent/src/main/kotlin/app/amber/feature/subagent/SubAgentToolScope.kt:1-152`
- `app/src/test/java/app/amber/agent/data/agent/subagent/SubAgentToolScopeTest.kt:1-89`

**Files:**
- Create: `harmony/chat/src/main/ets/chat/subagent_tool_scope.ts`
- Create: `harmony/chat/src/test/subagent_tool_scope.test.ts`

- [ ] **Step 1: Write failing scope tests**

Create a parent registry containing child-executable tools, parent-only tools, discovery tools, and `subagent_start`. Assert:

```ts
assert.deepEqual(scoped.map((tool) => tool.name), expectedChildNames);
assert.equal(searchResult.includes('parent_secret'), false);
assert.equal(policyResult.status, 'not_found');
```

Pin replacement of `tool_search`, conditional replacement of `tools_list` and `tool_policy_explain`, no `subagent_*`, no parent schema leakage, and metadata field semantics without property-order requirements.

- [ ] **Step 2: Verify RED**

Run the new test; expected missing module failure.

- [ ] **Step 3: Implement using existing `ToolRegistry` and introspection helpers**

Do not create a second metadata registry. Filter executable tools first, then build child discovery tools over that filtered registry.

- [ ] **Step 4: Verify GREEN**

Run scope tests and `tsc`; expect pass.

---

### Task 5: Transcript JSONL and secure tail reader (D-132a)

**Android anchors:**
- `feature/subagent/src/main/kotlin/app/amber/feature/subagent/SubAgentTranscriptReader.kt:1-56`
- `app/src/test/java/app/amber/agent/data/agent/subagent/SubAgentTranscriptReaderTest.kt:1-71`
- `feature/subagent/src/main/kotlin/app/amber/feature/subagent/SubAgentManager.kt` transcript append sites

**Files:**
- Create: `harmony/chat/src/main/ets/chat/subagent_transcript.ts`
- Create: `harmony/chat/src/test/subagent_transcript.test.ts`
- Later create: `harmony/entry/src/main/ets/platform_impl/SubAgentStore.ets`

- [ ] **Step 1: Write failing transcript tests with an in-memory port**

Pin event key order `event/created_at_ms/payload`, `.jsonl`, regular-file and containment checks, last 256 KiB, malformed lines skipped, partial first line skipped, and newest valid `payload.display_text` selected.

- [ ] **Step 2: Verify RED**

Run transcript test; expected missing module failure.

- [ ] **Step 3: Implement `SubAgentTranscriptPort` and reader/writer helpers**

The public reader accepts only manager-generated transcript paths or run IDs. Port methods expose `appendText`, `readTail`, `exists`, `isRegularFile`, and `isPathInside`; no platform imports in chat HAR.

- [ ] **Step 4: Verify GREEN and D-132a review/gates**

Run all new subagent tests, full chat tests, deepread tests, HAR/HAP builds. Conduct spec review then code-quality review. Append D-132a to `DECISION_LOG.md`, commit feature, update `MIGRATION_STATE.yaml` head, commit state pointer.

---

### Task 6: Child loop adapter and isolated runner (D-132b)

**Android anchors:**
- `feature/subagent/src/main/kotlin/app/amber/feature/subagent/SubAgentRunner.kt:1-313`
- `app/src/test/java/app/amber/feature/subagent/SubAgentRunnerTest.kt:1-209`
- `app/src/test/java/app/amber/agent/data/agent/subagent/SubAgentIsolationTest.kt:1-97`

**Files:**
- Create: `harmony/chat/src/main/ets/chat/subagent_runner.ts`
- Create: `harmony/chat/src/test/subagent_runner.test.ts`
- Modify only if required: `harmony/chat/src/main/ets/chat/tool_loop.ts`
- Test existing loop invariants: `harmony/chat/src/test/tool_loop.test.ts`

- [ ] **Step 1: Write failing child-loop/runner tests**

Pin child step limits 1/2/4 without changing main-agent limits, isolated assistant fields, one user task message, scoped tools + `subagent_report`, streaming visible text/rich parts, abort, report retry with exactly two extra steps, pending approval mapping, ordinary provider failure, and the narrow `invalid function arguments json string` visible-text fallback only when no executable child tools exist.

- [ ] **Step 2: Verify RED**

Run runner tests; expected missing module/behavior failure.

- [ ] **Step 3: Implement `SubAgentGenerationPort` and `GenerationSubAgentRunner`**

The domain runner receives model resolution and generation through a named port. Do not pass a parent `Conversation`; disable memory/presets/regex/MCP/local inheritance/mode/lorebook/skills/time reminder/soul/generative UI/speculation and reset template to `{{ message }}`.

Exact approval strings:

```text
Subagent requested approval for ${toolName}.
Subagent cannot self-approve sensitive tools.
```

- [ ] **Step 4: Verify GREEN**

Run runner + existing loop/dispatcher tests and `tsc`; expect pass.

---

### Task 7: Manager lifecycle/task board (D-132b)

**Android anchors:**
- `feature/subagent/src/main/kotlin/app/amber/feature/subagent/SubAgentManager.kt:1-440`
- `feature/task/.../AgentTaskStore.kt` integration

**Files:**
- Create: `harmony/chat/src/main/ets/chat/subagent_manager.ts`
- Create: `harmony/chat/src/test/subagent_manager.test.ts`

- [ ] **Step 1: Write failing manager tests**

Cover disabled start, overall/dynamic admission lock, registration before launch, started/finished transcript order, complete/fail/approval/cancel/timeout/interrupted, late provider race, wait clamp 0..60000 with 200ms poll, missing run with/without transcript, live text/parts/run subscriptions, 64-flow soft cap, grants, task snapshot mapping and no retry callback.

Exact errors:

```text
Subagent experimental mode is disabled.
Subagent concurrency limit reached.
Dynamic subagent per-turn limit reached.
Subagent run was cancelled.
Subagent run is no longer active in memory.
Unknown subagent run_id: ...
```

- [ ] **Step 2: Verify RED**

Run manager tests; expected missing module failure.

- [ ] **Step 3: Implement manager with serialized terminal transitions**

Start reads latest settings, validates before admission, creates optional history grant, registers `AgentTaskSnapshot(type='subagent', outputRef.type='transcript')`, appends transcript and launches. Timeout/cancel abort first and transition only if current state is RUNNING. Failed snapshots are retryable with max 1 but do not register a live retry callback, preserving Android behavior.

- [ ] **Step 4: Verify GREEN and D-132b review/gates**

Run full three gates, spec review, quality review, D-132b decision entry, feature/state double commit.

---

### Task 8: Public SubAgentTools (D-132c)

**Android anchors:**
- `feature/tools/impl/src/main/kotlin/app/amber/feature/tools/SubAgentTools.kt:1-300`
- `app/src/test/java/app/amber/agent/data/agent/subagent/SubAgentPayloadTest.kt:1-68`

**Files:**
- Create: `harmony/chat/src/main/ets/chat/subagent_tools.ts`
- Create: `harmony/chat/src/test/subagent_tools.test.ts`
- Modify: `harmony/chat/src/main/ets/index.ts`

- [ ] **Step 1: Write failing five-tool tests**

Pin order:

```ts
['subagent_list','subagent_start','subagent_read','subagent_wait','subagent_cancel']
```

Pin schemas/required fields, wait default 10000/max 60000, list payload field semantics without property-order requirements, roster versus smart prompts, mention mandatory-delegation instruction, self-loop guard, manager delegation, and default omission of display text.

- [ ] **Step 2: Verify RED**

Run tools tests; expected missing module failure.

- [ ] **Step 3: Implement five tools and system prompt**

`parentToolsProvider` is injected and returns the already-profiled parent base tool set; public tools never leak into child scope.

- [ ] **Step 4: Verify GREEN**

Run tools tests and `tsc`; expect pass.

---

### Task 9: Runtime settings and entry adapters (D-132c)

**Android anchors:**
- `core/settings/.../PreferencesStore.kt:150-183`
- `app/src/main/java/app/amber/core/di/AgentRuntimeModule.kt`
- `feature/subagent/.../SubAgentManager.kt` file paths

**Files:**
- Modify: `harmony/entry/src/main/ets/platform_impl/AgentRuntimePrefs.ets`
- Create: `harmony/entry/src/main/ets/platform_impl/SubAgentStore.ets`
- Modify: `harmony/entry/src/main/ets/di/AppContainer.ets`

- [ ] **Step 1: Add failing settings/adapter tests in chat-domain where platform-free**

Pin old snapshot defaults, setting/override/custom-definition field semantics, unknown-key RMW preservation, numeric primitive lexical parity, file append/tail/containment semantics through the port contract, and one shared AgentTaskStore instance. Do not impose generic roundtrip or property-order contracts.

- [ ] **Step 2: Implement platform adapters**

`SubAgentStore.ets` uses `filesDir/amberagent/subagents/runs`, fileIo append/stat/access and bounded tail reads. AppContainer caches runner/manager Promise and injects current settings, IDs/time/sleep, session grants, task store, files, provider/model adapter.

- [ ] **Step 3: Verify entry build**

Run chat `tsc`, then HAR/HAP build; resolve ArkTS errors without deleting behavior.

---

### Task 10: ChatPage assembly (D-132c)

**Android anchors:**
- `app/src/main/java/app/amber/core/service/ChatService.kt:2280-2343`
- `app/src/main/java/app/amber/core/service/ChatService.kt:2299-2315`

**Files:**
- Modify: `harmony/entry/src/main/ets/pages/ChatPage.ets`

- [ ] **Step 1: Pin assembly order with an extracted pure helper test if needed**

Required order:

1. conversation context/history/queue
2. unconditional D-131 task tools
3. parent profile filter
4. base registry + parent discovery
5. conditional SubAgentTools only when conversation exists and settings enabled
6. model council and other supervisor groups afterward
7. final profile/registry/activity wrapping

- [ ] **Step 2: Implement async hydration and dynamic mounting**

The parent tools provider captures only step-4 base tools and excludes all `subagent_*`; do not put SubAgentTools in LocalTools.

- [ ] **Step 3: Run D-132c/full subsystem verification**

Commands:

```bash
cd /Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat && npx tsc --noEmit && npm test
cd /Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread && npx tsc --noEmit && npm test
cd /Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony && hvigorw assembleHar --mode module -p product=default --no-daemon && hvigorw assembleHap --mode module -p product=default --no-daemon
```

Expected: zero test failures, both builds `BUILD SUCCESSFUL`.

- [ ] **Step 4: Review and double commit**

Conduct complete subsystem spec review then code-quality review; fix and re-review all findings. Append D-132c to `DECISION_LOG.md`, register any real parity debt, make feature commit, update `MIGRATION_STATE.yaml` line 10 to feature SHA, then state pointer commit.

---

## Deferred after D-132 runtime closure

The Android coalesced `ChatMessageSubAgentStep` UI (live phase rotation, run sheet, transcript fallback and coalescing `start/wait/read/cancel` by run ID) is a separate interaction/visual slice after the data/protocol/core/error-recovery/runtime closure. Generic tool cards remain truthful and do not fake success; this UI gap must stay explicitly listed until implemented.
