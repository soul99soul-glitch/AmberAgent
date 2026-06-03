# Deep Read 成功率与可交付体验优化计划 — 架构审查结论

> 审查日期：2026-06-03
> 审查范围：对照当前代码库，验证 plan 与代码事实一致性、回归风险、过度设计、遗漏项及更小 diff 替代方案。
> 审查方式：现场核对源码与单测，非纯文字推断。

---

## 总体结论：**部分同意**

Plan 的大方向与当前代码主干一致：保留 `isComplete()` / 验真硬门、把「四段 READY 但未验真」从整篇失败体验中拆出来、改善 coverage / writer feedback 循环——这些都对。

但 plan 对 **重新验真调用链**、**UI notice 优先级**、**测试路径** 几处与代码事实不符或存在遗漏。若按 plan 原样落地，可能出现：验真失败被「基础稿」banner 盖住、重试时验真状态被清空、或依赖隐式 `empty stages` 的脆弱路径。

**Plan 不会（在正确实现前提下）把未验真稿件误标为 verified complete，也不会放宽 refuted / evidence_urls / evidence_excerpt / token 硬门。**

---

## 审查对象（Plan 摘要）

| 模块 | 目标 |
|------|------|
| 1. 可交付状态与 UI | 四段 READY = 可交付稿；验真失败 = 警告而非整篇失败 |
| 2. Coverage 补漏 | 最多 2 轮 supplement，不放松 coverage 判定 |
| 3. VerificationGate | 结构化 reject reason，不降低硬门 |
| 4. Writer / overview / diagram | 反馈优化；overview 24 字；diagram 放宽相邻边 |
| 5. 预算提示 | 不重做，只补测试 |
| 6. GenerationHandler | 不改公共 API |

---

## 与代码事实对照

### 已对齐 / 无需重复做

| Plan 项 | 代码事实 | 结论 |
|---------|----------|------|
| `isComplete()` 语义不变 | `DeepReadModels.kt:90-94` 要求 `generationComplete && sectionsReady() && verification READY` | ✅ 一致 |
| `markVerificationFailed` 不污染 READY section | `DeepReadSectionWriterTools.kt:100-109`；测试 `DeepReadRepositoryTest.kt:416-436` | ✅ **已实现，plan 是重申约束** |
| Coverage 一轮后 hard fail | `DeepReadAgentRunManager.kt:449-468` 仅 1 次 supplement | ✅ plan 改动点准确 |
| Overview 门槛 40 | `DeepReadSectionWriterTools.kt:1178-1179` `hasOverviewContent() >= 40` | ✅ plan 改动点准确 |
| Diagram 相邻边限制 | `DeepReadSectionWriterTools.kt:880-887` | ✅ plan 改动点准确 |
| 预算提示改造 | `DeepReadPromptWordingTest.kt:29-55` 已覆盖 | ✅ **无需重复做** |
| 预抓为空硬失败 | `DeepReadAgentRunManager.kt:166-176` | ✅ plan 正确声明不改 |
| VerificationGate 硬门 | `DeepReadSectionWriterTools.kt:892-958` + `DeepReadEvidenceRegistry.kt:62-88` | ✅ plan 声明不放宽，与代码一致 |

### 尚未实现 / plan 描述与现状有 gap

| Plan 项 | 代码现状 |
|---------|----------|
| `isDeliverableDraft()` | 不存在；仅有 `sectionsReady()`（`DeepReadModels.kt:96-97`） |
| 验真失败中性 banner | 走 `DeepReadPartialErrorNotice` 红色 `errorContainer`（`DeepReadScreen.kt:565-587`） |
| `firstFailureMessage` 优先级 | 验真 FAILED **优先于** section FAILED（`DeepReadScreen.kt:492-499`），与 plan 目标相反 |
| Coverage 2 轮 supplement | 当前仅 1 轮（`DeepReadAgentRunManager.kt:449-463`） |
| Overview 24 字 + 拆分 missing 反馈 | 统一 `missing("overview", "summary")`（`DeepReadSectionWriterTools.kt:212`） |
| Writer `accepted/dropped/drop_reasons` | `ok()`/`missing()` 仅返回 status/section（`DeepReadSectionWriterTools.kt:590-610`） |
| 「重新验真」按钮文案 | 验真失败时 `failureRetryLabel` 仍为「重试」（`DeepReadScreen.kt:234`） |

---

## 问题清单（按严重级别）

### 🔴 硬伤

#### 1. 「复用 `runAll(force = false)` 重新验真」依赖隐式空 stage 路径，且会清空验真状态

**Plan 假设**：四段 READY 后点「重新验真」走 `runAll(force = false)` 即可，不清正文。

**代码事实**：

`run()` 在四段齐全时把 `stagesToGenerate` 设为 **空列表**：

```kotlin
// DeepReadAgentRunManager.kt:93-99
val stagesToGenerate = when {
    missing.isNotEmpty() -> missing
    cached?.sectionsReady() == true -> emptyList()
    else -> DeepReadGenerationStage.entries
}
generateStages(topicId, topicTitle, stagesToGenerate, seedUrl, force)
```

验真分支能否进入，依赖 `stages.all { ... }` 对 **空集合** 的 vacuous truth：

```kotlin
// DeepReadAgentRunManager.kt:439-443
val allTargetedReady = stages.all {
    writer.currentOutput().statusOf(it) == DeepReadSectionStatus.READY
}
val allSectionsReady = writer.currentOutput().sectionsReady()
if (allTargetedReady && allSectionsReady && !writer.hasFreshVerification) {
```

更严重：即使 `stages` 为空，`markRunning(stages)` 仍会在 fold 初始值里把 `verificationState` 重置为默认 PENDING：

```kotlin
// DeepReadSectionWriterTools.kt:75-80
suspend fun markRunning(stages: Collection<DeepReadGenerationStage>): DeepReadOutput =
    update { current ->
        stages.fold(
            current.copy(
                generationPhase = DeepReadGenerationPhase.WRITING,
                verificationState = DeepReadSectionState(),
```

**影响**：

- 重新验真会抹掉 `FAILED` 错误信息
- 取消/中断后用户可能看不到上一次失败原因
- 整条路径仍附带 prefetch + article plan 全量开销，并非「只重跑验真」
- 依赖 vacuous `all` 的隐式约定，后续改 `generateStages` 极易踩雷

**必改建议**：在 `run()` / `generateStages()` 增加显式 `verificationOnly` 分支；verification-only 时跳过 `markRunning` 对 `verificationState` 的清空。

---

#### 2. Plan 未要求同步改的两套 UI notice，且 `hasBasicDraft` 会盖住验真失败

`DeepReadScreen.kt` 里 **模板分支**（约 319–344 行）与 **默认分支**（约 428–461 行）各有一套相同的 `when { ... }` notice。Plan 只抽象描述 UI，未点名必须改两处。

更关键的是 notice **优先级**：`hasBasicDraft && !complete` 排在 `firstFailureMessage` **之前**：

```kotlin
// DeepReadScreen.kt:443-460
hasBasicDraft && !complete -> TemplateFallbackNotice(
    message = "基础稿，可继续增强",
    ...
)
...
firstFailureMessage != null && !complete -> DeepReadPartialErrorNotice(
    error = firstFailureMessage,
    ...
)
```

Fallback 补漏路径会把 section 标为 `BASIC`（`DeepReadSectionWriterTools.kt:160-164`；已有测试 `fallbackSectionCanSupplementReadySectionDuringCoverageRepair`）。此时即使用 plan 的新 warning，**验真失败 banner 仍可能被「基础稿，可继续增强」完全挡住**。

---

### 🟠 必改

#### 3. `firstFailureMessage()` 现状与 plan 描述相反，且仍走红色 error UI

当前实现 **验真失败优先于 section 失败**：

```kotlin
// DeepReadScreen.kt:492-499
private fun DeepReadOutput.firstFailureMessage(): String? =
    verificationState
        .takeIf { it.status == DeepReadSectionStatus.FAILED }
        ?.errorMessage
        ?: sectionStates
            .values
            .firstOrNull { it.status == DeepReadSectionStatus.FAILED }
            ?.errorMessage
```

Plan 要求：

- 未四段 READY → section failure 优先
- 四段 READY → verification failure 只进 warning，不当整篇失败

方向对，但 helper 在 **Screen 私有函数**（492 行），应下沉到 `DeepReadModels.kt`（与 `isComplete()` 同文件 90–97 行）才方便单测。

当前验真/section 失败都走 `DeepReadPartialErrorNotice`，使用 `errorContainer` 红色样式（565–587 行），与 plan「中性/提醒型 banner」不符。

---

#### 4. `markVerificationFailed()` — plan 描述正确，核心逻辑无需重复做

```kotlin
// DeepReadSectionWriterTools.kt:100-109
suspend fun markVerificationFailed(message: String): DeepReadOutput =
    update { current ->
        current.copy(
            generationPhase = DeepReadGenerationPhase.IDLE,
            verificationState = DeepReadSectionState(
                status = DeepReadSectionStatus.FAILED,
                errorMessage = message.safeTake(220),
            ),
            generationComplete = false,
        )
    }
```

已有测试 `finalVerificationFailureDoesNotClobberExtendedReadingSection`（`DeepReadRepositoryTest.kt:416-436`）证明不会 clobber READY section。

---

#### 5. Coverage 补漏 2 轮 loop 合理，但 `targetStage == null` 分支几乎不可达

现状仅 **1 次** supplement（`DeepReadAgentRunManager.kt:449-468`），失败即 `markVerificationFailed`。

`DeepReadCoverageReport`（`DeepReadResearchHarness.kt:506-513`）在 `needsSupplement=true` 时，`targetStage` 几乎总有值：

- missing item 自带 `targetStage`（528-538 行 enum）
- 或 `missingRequiredSourceIds` 非空时固定 `EXTENDED_READING`（217-218 行）

Plan 单独强调 `targetStage == null` 停止补漏，对当前代码是 **防御性描述**，不是主要改动点。

---

#### 6. Overview 门槛 40→24 — plan 准确，但需对齐 fallback 阈值

Writer 门槛：

```kotlin
// DeepReadSectionWriterTools.kt:1178-1179
private fun DeepReadOutput.hasOverviewContent(): Boolean =
    summary.trim().length >= 40
```

Fallback 判定已是 24（1137-1139 行 `isUsefulFallbackText`）。Plan 只改 `hasOverviewContent` 即可对齐。

**注意**：降低 overview 门槛只会提高 `sectionsReady()` / 可交付稿比例，**不会**单独把稿件标成 `isComplete()`——`finishTool` 仍要求 `hasFreshVerification`（478 行）。

---

#### 7. Diagram 非相邻边 — plan 与现有测试直接冲突

当前 `process_flow` / `causal_chain` 强制相邻边（880-887 行）。

测试 `diagramToolCompactsDenseSpecsBeforePersisting`（704-720 行）明确断言 `n1->n5` 被过滤。Plan 若放宽，**必须同步改此测试**，否则 CI 必挂。

---

#### 8. VerificationGate 结构化 reason — 部分已实现，plan 是增强而非放松

`verificationGate()`（892-958 行）已有 `checked_claims[$index]`、未知 URL、excerpt 不匹配等 reason；token 校验走 `DeepReadEvidenceRegistry.containsEvidence`（62-88 行）。此项是 **文案细化**，不误伤硬门。

---

#### 9. 测试计划路径/类名错误

| Plan 写法 | 实际 |
|-----------|------|
| `app.amber.feature.board.hotlist.*Test` | `app.amber.agent.data.agent.board.hotlist.*Test` |
| `DeepReadSectionWriterToolsTest` | **不存在**；writer/diagram/verification 测例在 `DeepReadRepositoryTest.kt` |

Section 5「预算提示不污染 SearchTools」——**无需重复做**：`DeepReadPromptWordingTest.deepReadToolDescriptionsCarryStageBudgetWithoutChangingGlobalSearchText`（29-55 行）已覆盖。

**正确测试命令示例**：

```bash
./gradlew :app:testDebugUnitTest \
  --tests app.amber.agent.data.agent.board.hotlist.DeepReadPromptWordingTest \
  --tests app.amber.agent.data.agent.board.hotlist.DeepReadSectionStateTest \
  --tests app.amber.agent.data.agent.board.hotlist.DeepReadOutputQualityTest \
  --tests app.amber.agent.data.agent.board.hotlist.DeepReadEvidenceRegistryTest \
  --tests app.amber.agent.data.agent.board.hotlist.DeepReadRepositoryTest
```

---

#### 10. 重新验真按钮文案与 retry 语义未闭环

验真失败时 `firstFailedStage()` 只看 section FAILED（501-504 行），不会指向验真；`retryFirstFailure()` 会 fallback 到 `runAll(force = false)`（235-236 行），但 `failureRetryLabel` 仍是「重试」（234 行），不是 plan 的「重新验真」。

---

### 🟡 可选细化

#### 11. 未验真误标 complete — plan 约束足够，风险低

```kotlin
// DeepReadModels.kt:90-94
fun DeepReadOutput.isComplete(): Boolean =
    generationComplete && isVerifiedComplete()

fun DeepReadOutput.isVerifiedComplete(): Boolean =
    sectionsReady() && verificationState.status == DeepReadSectionStatus.READY
```

`DeepReadSectionStateTest`（40-72 行）已锁死「四段 READY + verification 未 READY ≠ complete」。只要不改 `finishTool`（478 行）和 `isVerifiedComplete()`，**不会**把未验真稿标成 verified complete。

---

#### 12. Writer `accepted/dropped/drop_reasons` 覆盖面过大，可分期

当前 `ok()` / `missing()` 仅返回 `status/section/generation_complete`（590-610 行）；timeline/core_points 等静默 `mapNotNull` 丢弃无反馈。更小做法：先改 overview + narrative + verification reject，diagram/visuals 二期。

---

#### 13. 通知/历史页语义可跟进，非阻塞

- `DeepReadNotifier.notifyCompleted(complete=false)` 已用「已部分生成」（42 行），与 deliverable draft 部分契合，但未区分「验真未完成」
- `DeepReadHistoryPage` 仅显示 TTL「有效/已失效」（133-136 行），不展示验真状态

---

#### 14. `DeepReadScreenSplit.kt` 若复用 notice 逻辑需一并检查

主路径在 `DeepReadScreen.kt`；若 split 布局也展示 failure banner，应同步，否则行为分叉。

---

## 关键文件与行号索引

| 文件 | 关键符号 / 行号 |
|------|-----------------|
| `feature/board/impl/.../DeepReadModels.kt` | `isComplete()` 90-94, `sectionsReady()` 96-97, `withSectionStatus()` 70-78 |
| `app/.../DeepReadAgentRunManager.kt` | `run()` 74-100, coverage supplement 449-468, `MAX_VERIFICATION_PASSES` 1116 |
| `app/.../DeepReadSectionWriterTools.kt` | `markVerificationFailed()` 100-109, `markRunning()` 75-88, `verificationGate()` 892-958, `hasOverviewContent()` 1178-1179, diagram edges 874-890, `finishTool()` 465-496 |
| `app/.../DeepReadScreen.kt` | notice when 319-344 & 428-461, `firstFailureMessage()` 492-499, `DeepReadPartialErrorNotice` 565-587 |
| `feature/board/impl/.../DeepReadResearchHarness.kt` | `verifyCoverage()` 172-225, `DeepReadCoverageReport` 506-526 |
| `feature/board/impl/.../DeepReadEvidenceRegistry.kt` | `containsEvidence()` 62-88, token regex 100-111 |
| `app/src/test/.../DeepReadSectionStateTest.kt` | complete 语义 40-72 |
| `app/src/test/.../DeepReadRepositoryTest.kt` | verification / diagram / overview 测例 |
| `app/src/test/.../DeepReadPromptWordingTest.kt` | 预算提示 29-55 |

---

## 认可的修正版落地顺序

按 **更小 diff、更低回归风险** 排序：

### Phase 1 — 模型层语义（`DeepReadModels.kt`）

- 新增 `isDeliverableDraft() = sectionsReady()`
- 拆分 `sectionFailureMessage()` / `verificationWarningMessage()` / 调整后的 `firstFailureMessage()`
- 单测：`DeepReadSectionStateTest` 增补 deliverable vs complete

### Phase 2 — UI 层（`DeepReadScreen.kt` 两处 notice `when`）

- 新增中性 `DeepReadVerificationWarningNotice`（非 `errorContainer`）
- 优先级：`generating` > **验真 warning（四段 READY）** > section 失败 > `hasBasicDraft`
- 验真重试文案「重新验真」；section 失败仍「仅重试这一段」

### Phase 3 — RunManager 验真-only 路径（`DeepReadAgentRunManager.kt`）

- `sectionsReady() && verification FAILED/PENDING && !force` → 显式 verification-only
- 跳过 section `markRunning`、可选跳过 replan
- coverage supplement `repeat(MAX=2)` loop
- **不要**继续依赖 `emptyList()` + vacuous `all`

### Phase 4 — `markRunning` 守卫（`DeepReadSectionWriterTools.kt`）

- verification-only / 单段重试时：若目标 stage 已 READY，不清 `verificationState`（或仅进入 `VERIFYING` 时置 RUNNING）

### Phase 5 — Writer 小步反馈

- overview 24 字门槛 + `summary missing` / `summary too short: X/24`
- `verificationGate` reason 细化（保持硬门）
- diagram 非相邻边 + **同步更新** `diagramToolCompactsDenseSpecsBeforePersisting`

### Phase 6 — Writer 广域反馈（可选二期）

- `accepted/dropped/drop_reasons` 先覆盖 overview/narrative/extended，再扩 diagram/visuals

### Phase 7 — 测试收尾

- 用正确包名跑现有测试类
- 新增 coverage 2-pass / UI helper 单测
- **不新建**重复的 budget wording 测试

---

## 审查结论一句话

Plan 在「不伪造 verified complete、不放松验真硬门」上与代码一致；主要缺口是 **重新验真的调用链与状态语义**、**双分支 UI + notice 优先级**、**测试路径错误**。优先做 Phase 1–4 即可用较小 diff 拿到大部分「可交付但未验真」体验提升；diagram 放宽与 writer 全量反馈可后置。
