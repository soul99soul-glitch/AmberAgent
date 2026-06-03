# Deep Read 可交付体验与自恢复优化计划 v2 — 架构审查结论

> 审查日期：2026-06-03
> 对照基线：v1 审查结论（`docs/REVIEW_DEEP_READ_OPTIMIZATION_PLAN.md`）+ 当前代码
> 审查方式：现场核对源码与单测

---

## 总体结论：**部分同意（较 v1 明显可落地）**

v2 已正面回应 v1 的三处硬伤：

1. 显式 `verification-only` 路径，不再依赖 `emptyList()` + vacuous `stages.all {}`
2. 双分支 UI notice 同步 + `hasBasicDraft` 不再盖住验真 warning 的优先级设计
3. 测试包名修正为 `app.amber.agent.data.agent.board.hotlist.*`

**仍建议在开工前补齐 3 处实现细节**，否则会出现「重新验真绕开 coverage」「evidence 白名单丢历史 scrape URL」「coverage 失败与验真失败共用 UI 但重试语义不一致」等回归或体验断层。

**v2 不会（在正确实现前提下）把未验真稿误标为 verified complete，也不会放宽 refuted / evidence_urls / evidence_excerpt / token 硬门。**

---

## v1 → v2 对照：已修复项

| v1 问题 | v2 回应 | 评价 |
|---------|---------|------|
| 隐式空 stages 重新验真 | §2 显式 `runVerificationOnly()` | ✅ 方向正确 |
| `markRunning(empty)` 清空 `verificationState` | §2 不调用 `markRunning(stages)` | ✅ 方向正确 |
| 两套 notice + `hasBasicDraft` 盖住验真失败 | §1 优先级：`generating` > verification warning > section failure > `hasBasicDraft` | ✅ 方向正确 |
| helper 在 Screen 私有函数 | §1 下沉到 `DeepReadModels.kt` | ✅ 可测性改善 |
| 测试包名 / 类名错误 | Test Plan 已修正 | ✅ |
| Writer 反馈范围过大 | §4 仅 overview / narrative / diagram / verification | ✅ 范围收敛 |
| diagram 测试冲突 | §4 明确同步更新 `diagramToolCompactsDenseSpecsBeforePersisting` | ✅ |
| 预算提示重复做 | §5 明确不做 | ✅ **无需重复做** |

---

## 与代码事实仍须对齐的点

### 已对齐 / 无需重复做

| 项 | 证据 |
|----|------|
| `isComplete()` 硬门 | `DeepReadModels.kt:90-94`；`finishTool` `DeepReadSectionWriterTools.kt:478` |
| `markVerificationFailed` 不 clobber READY section | `DeepReadSectionWriterTools.kt:100-109`；`DeepReadRepositoryTest.kt:416-436` |
| Coverage 当前仅 1 轮 supplement | `DeepReadAgentRunManager.kt:449-468` |
| overview 门槛 40 | `DeepReadSectionWriterTools.kt:1178-1179` |
| diagram 相邻边过滤 | `DeepReadSectionWriterTools.kt:880-887` |
| 预算提示测试已有 | `DeepReadPromptWordingTest.kt:29-55` |
| `DeepReadScreenSplit` 为 scaffold，非生产路径 | `DeepReadScreenSplit.kt:51-58` — **本轮可忽略** |

### v2 新增但代码尚不存在

| 项 | 说明 |
|----|------|
| `isDeliverableDraft()` / `sectionFailureMessage()` / `verificationWarningMessage()` | 需在 `DeepReadModels.kt` 新增 |
| `runVerificationOnly()` | 需在 `DeepReadAgentRunManager.kt` 新增 |
| 中性 verification warning composable | 需在 `DeepReadScreen.kt` 新增 |
| `MAX_COVERAGE_SUPPLEMENT_PASSES = 2` | 需替换现有单轮 loop |
| overview 24 字 + 拆分 missing 反馈 | 需改 `hasOverviewContent()` 与 overview tool execute |
| diagram 非相邻边 | 需改 `normalizedDiagramEdges()` + 测试 |

---

## 问题清单（按严重级别）

### 🔴 硬伤

#### 1. `verification-only` 未明确包含 coverage 补漏，可能绕过硬门前的 coverage check

当前完整链路在验真**之前**必做 coverage（`DeepReadAgentRunManager.kt:443-470`）：

```kotlin
if (allTargetedReady && allSectionsReady && !writer.hasFreshVerification) {
    var coverageReport = researchHarness.verifyCoverage(...)
    // supplement 1 轮 → 仍 needsSupplement → markVerificationFailed("本地覆盖检查未通过：...")
    runVerificationSupervisorLoop()
}
```

coverage 失败与验真失败**共用** `verificationState=FAILED`（`markVerificationFailed`，`DeepReadSectionWriterTools.kt:100-109`），错误文案却不同（「本地覆盖检查未通过」vs「最终验真未通过」）。

v2 §2 只描述「重新验真」走 `runVerificationOnly`，§3 coverage 补漏写在 `generateStages` 语境，**未要求 verification-only 复用同一 coverage tail**。

**风险**：用户因 coverage 失败看到「验真未完成」warning，点「重新验真」→ 只跑 `runVerificationSupervisorLoop()` → **跳过 coverage supplement** → 可能验真通过但稿件仍缺本地 coverage 要求的维度 — **与现有「先 coverage 后验真」顺序不一致**。

**必改建议**：抽取共享尾部，例如：

```text
runCoverageWithSupplement(maxPasses=2) → runVerificationSupervisorLoop() → finishIfPossible()
```

`generateStages`（四段已 READY）与 `runVerificationOnly` **都必须调用同一函数**，而不是 verification-only 只跑验真。

---

#### 2. `DeepReadEvidenceRegistry` 为内存态；verification-only 重建 registry 可能丢失首轮 scrape 的 URL 白名单

每轮 `generateStages` 都 `DeepReadEvidenceRegistry()` 新建（`DeepReadAgentRunManager.kt:180`），运行中通过 `withEvidenceRecording` 追加 scrape/search 结果（898-904 行）。

`verificationGate` 要求 `evidence_urls` 在 registry 白名单内（`DeepReadSectionWriterTools.kt:929-934`），excerpt 匹配走 `containsEvidence`（`DeepReadEvidenceRegistry.kt:62-88`）。

v2 §2 写「复用 prefetch / evidence registry 必要构造」，但 **registry 未持久化**；verification-only 若只 seed prefetch 来源，首轮写作/验真中 `scrape_web` 新访问、且不在 prefetch 列表的 URL 会变为 **unknownEvidence**，导致重验真无故失败 — **误伤事实核查体验**（硬门本身未放宽，但重试路径变脆）。

**必改建议**（最小 diff，无需 DB 迁移）：

1. verification-only 启动时：`prefetch` + **`output.references` / `output.extendedReading` 全部 URL `mark(url)`**
2. 若有 prefetch / card 的 `evidenceText`，继续 `mark(url, text)`（与现 `generateStages` 187-189 行一致）
3. 验真 pass 仍暴露 scrape 工具，允许模型补抓；但 seed 应覆盖稿件已引用 URL

---

### 🟠 必改

#### 3. `markVerificationRunning()` 会清掉 FAILED 的 `errorMessage`，与 §2「保留上一轮失败原因直到新结果落库」字面冲突

```kotlin
// DeepReadSectionWriterTools.kt:112-118
verificationState = DeepReadSectionState(DeepReadSectionStatus.RUNNING),  // errorMessage 丢失
```

`runVerificationSupervisorLoop()` 首行即调用（`DeepReadAgentRunManager.kt:397`）。

**影响**：重验真开始后 `verificationWarningMessage()`（要求 `FAILED`）不再成立；UI 若只读 DB 态，warning 会闪没，直到再次 FAILED。

**建议**（二选一，写进 plan）：

- A. `markVerificationRunning` 保留 `errorMessage` 到 RUNNING（仅改 status）
- B. UI 在 `lifecycleRunning && verificationRunning` 时继续展示 cached warning 文案（Compose 层 remember）

v2 §1 优先级已把 `generating` 放最前，采用 B 也可，但应在 plan 中写清，避免实现者以为 DB 里仍保留 FAILED 文案。

---

#### 4. verification-only 须避免 `generateStages` 开头的「COLLECTING 全量重置」

`generateStages` 入口即：

```kotlin
// DeepReadAgentRunManager.kt:152-158
output = cached.copy(generationPhase = COLLECTING, generationComplete = false)
```

v2 要求不重置 section、不 `markRunning`。若 verification-only **误复用** `generateStages` 整函数入口，会把 phase 打回 COLLECTING，与 §2 冲突。

**建议**：`runVerificationOnly` 独立入口；或抽取 `prepareVerificationRun()` 跳过 COLLECTING 写入与 stage loop。plan 已倾向前者 — 实现时 **禁止** 从 `generateStages` 顶部 fall-through。

---

#### 5. `articlePlan` 未持久化；coverage 在 verification-only 仍需再生 plan

`DeepReadArticlePlan` 仅在 `generateStages` 内存生成（`DeepReadAgentRunManager.kt:217`），不在 `DeepReadOutput` 中。

v2 §2「可复用 article plan 必要构造」= verification-only 若跑 coverage（见 🔴#1），仍需 **`generateArticlePlan()` 一次 LLM 调用** + prefetch。plan 应明确这是 **可接受成本**，避免实现者以为纯本地重验真零 LLM。

---

#### 6. UI「重新验真」按钮文案：v2 写了 warning 文案，未改 `failureRetryLabel`

当前（`DeepReadScreen.kt:234-236`）：

```kotlin
val failureRetryLabel = if (firstFailedStage != null) "仅重试这一段" else "重试"
fun retryFirstFailure() {
    firstFailedStage?.let(::runOne) ?: runAll(force = false)
}
```

验真 warning 场景下 `firstFailedStage == null`，按钮仍显示 **「重试」**，与 §1「重新验真」不一致。

**建议**：当 `verificationWarningMessage() != null` 时 `retryLabel = "重新验真"`；section failure 仍用「仅重试这一段」。

---

#### 7. `run()` 入口条件需写清边界，避免与 background fill 冲突

当前 `run()`（`DeepReadAgentRunManager.kt:87-99`）：

- 有 READY section 且 **有 missing stage** → `scheduleBackgroundFill`，**不**走 verification-only
- 四段 READY → `stagesToGenerate = emptyList()` → 现仍进 `generateStages`

v2 应用 **显式分支替换 L95-99**，建议条件：

```text
!force && cached != null && cached.sectionsReady() && !cached.isComplete()
  → runVerificationOnly(...)
```

并 **删除** `cached?.sectionsReady() == true -> emptyList()` 隐式路径。

注意：`!isComplete()` 已含 verification FAILED / 无 fresh verification / `generationComplete=false` 等 — 与 v2 假设一致。

---

#### 8. `initialDisplayError` 须改用 `sectionFailureMessage()`，不能继续用合并后的 `firstFailureMessage()`

`DeepReadScreen.kt:241-242` 在全屏 `DeepReadError` 路径使用 `firstFailureMessage`。helper 拆分后，**未四段 READY 且无 READY section** 时只能用 section failure；**绝不能**把 verification warning 抬成全屏红页（否则 v2 §1 失效）。

**建议**：`initialDisplayError = runError ?: sectionFailureMessage()`，且仅当 `!hasAnyReadySection()`。

---

### 🟡 可选细化

#### 9. `runSection()` 仍会 `markRunning` 并清空 `verificationState`（`DeepReadSectionWriterTools.kt:75-80`）

v2 未要求改 — **现有行为合理**（改段后应失效旧验真）。单测可加一条：section retry 后 `hasFreshVerification=false`。

---

#### 10. Prompt 与 diagram 行为不一致风险

`buildPrompt` 仍要求「流程/因果只写主链路」（`DeepReadAgentRunManager.kt:726`），与 §4 放宽非相邻边矛盾。实现 diagram 改动时 **同步改 prompt 一句**，减少模型与工具校验打架。

---

#### 11. 通知文案可跟进，非阻塞

`DeepReadNotifier.kt:42`：`complete=false` →「已部分生成」。deliverable + 验真失败可改为「正文已生成，验真未完成」— 非本轮必须。

---

#### 12. `targetStage == null` 停止补漏

与 v1 相同：`needsSupplement=true` 时 `targetStage` 在当前 `DeepReadResearchHarness.kt:217-218` 几乎总有值。§3 分支为防御性描述，非主改动点。

---

## 未验真误标 complete / 硬门放松 — 专项结论

| 风险 | v2 是否规避 | 说明 |
|------|-------------|------|
| 未验真标 complete | ✅ | §5 不改 `isComplete()` / `finishTool()`；`DeepReadSectionStateTest.kt:40-72` 已有锁 |
| 放宽 refuted | ✅ | §4 明确不改 schema、不降低硬门 |
| 放宽 evidence_urls | ⚠️ 实现依赖 | 硬门未放宽；但 registry 未 seed 会导致 **误拒**，见 🔴#2 |
| 放宽 evidence_excerpt / token | ✅ | `DeepReadEvidenceRegistry.kt:74-88` 无 plan 改动 |
| coverage 被 verification-only 绕过 | ⚠️ | 见 🔴#1 |

---

## 认可的 v2 落地顺序（在 plan 基础上的微调）

### Phase 1 — 模型 helper（`DeepReadModels.kt`）

- `isDeliverableDraft()`、`sectionFailureMessage()`、`verificationWarningMessage()`
- 单测：`DeepReadSectionStateTest`

### Phase 2 — UI（`DeepReadScreen.kt` 两处 notice + retry label + `initialDisplayError`）

- 中性 `DeepReadVerificationWarningNotice`
- 优先级：`generating` > verification warning > section failure > `hasBasicDraft`
- 验真场景按钮「重新验真」

### Phase 3 — RunManager 共享尾部 + 显式入口（`DeepReadAgentRunManager.kt`）

1. 抽取 `runCoverageAndVerificationTail(...)`（coverage 2-pass + verification supervisor + finish）
2. 实现 `runVerificationOnly()`：prefetch → plan → **seed registry（含 output URLs）** → tail
3. `run()` 替换 L95-99 隐式空 stages 分支
4. `generateStages` 四段 READY 路径改调同一 tail

### Phase 4 — Writer 反馈（`DeepReadSectionWriterTools.kt`）

- overview 24 + missing / too short
- `verificationGate` reason 细化
- diagram 边规则 + 测试更新
- `accepted/dropped/drop_reasons` 限 overview / narrative / diagram

### Phase 5 — 小修

- `markVerificationRunning` 或 UI remember 策略（🟠#3）
- `buildPrompt` diagram 文案（🟡#10）

### Phase 6 — 测试

使用 v2 Test Plan 命令；**不重复** `DeepReadPromptWordingTest` 预算用例。

---

## 测试计划审查

v2 Test Plan **包名正确**，覆盖主路径。建议 **额外增加**：

| 用例 | 原因 |
|------|------|
| verification-only 仍执行 coverage，且最多 2 轮 supplement | 🔴#1 |
| verification-only seed registry 含 `output.references` 中 URL | 🔴#2 |
| `initialDisplayError` 在 deliverable + verification FAILED 时不出现全屏错误 | 🟠#8 |
| `runSection` 后 `hasFreshVerification=false` | 🟡#9 |

`DeepReadSectionWriterToolsTest` 仍不存在 — 继续放在 `DeepReadRepositoryTest.kt` 即可。

---

## 一句话总结

**v2 可以开工**；相较 v1 已是可执行的收敛版。开工前把 **「verification-only = coverage tail + verification tail，而非只验真」** 和 **「registry seed 含稿件已引用 URL」** 写进 §2 实现说明，并补齐 UI retry 文案与 `initialDisplayError` 分流，即可避免最主要的逻辑回归与重试误伤。
