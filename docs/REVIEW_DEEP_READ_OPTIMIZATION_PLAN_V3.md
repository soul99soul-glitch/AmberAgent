# Deep Read 可交付体验与自恢复优化计划 v3 — 架构审查结论

> 审查日期：2026-06-03
> 对照基线：v1 / v2 审查结论
> 审查方式：现场核对源码与单测

---

## 总体结论：**同意（可开工）**

v3 已闭合 v2 审查中的全部 🔴 / 🟠 项：

| v2 遗留问题 | v3 回应 |
|-------------|---------|
| verification-only 绕过 coverage | §2 强制共享 `coverage → verification → finish` tail |
| EvidenceRegistry 丢历史 scrape URL | §2 明确 seed prefetch + output 已引用 URL |
| `initialDisplayError` 误抬全屏红页 | §1 改用 `sectionFailureMessage()` |
| 按钮仍显示「重试」 | §1 verification warning 按钮「重新验真」 |
| `markVerificationRunning` 与保留 FAILED 文案冲突 | §4 明确 UI running 优先，不做 remember 兜底 |
| diagram prompt 与工具规则不一致 | §4 同步改 `buildPrompt` 726 行措辞 |

**在按 v3 实现的前提下：不会把未验真稿标成 verified complete，不会放宽 refuted / evidence_urls / evidence_excerpt / token 硬门。**

剩余事项仅为 **实现层面的 refactor 注意事项**（🟡），不构成 plan 阻断项。

---

## 与代码事实对照

### 已对齐 / 无需重复做

| 项 | 证据 |
|----|------|
| `isComplete()` 三门闩 | `DeepReadModels.kt:90-94`；`finishTool` `DeepReadSectionWriterTools.kt:478` |
| `markVerificationFailed` 不 clobber READY section | `DeepReadSectionWriterTools.kt:100-109`；`DeepReadRepositoryTest.kt:416-436` |
| Coverage 当前单轮 + 验真前执行 | `DeepReadAgentRunManager.kt:443-470` |
| Coverage supplement 支持 READY 段补写 | `allowReadyRewrite = coverageReport != null`（310-341 行）；测试 `fallbackSectionCanSupplementReadySectionDuringCoverageRepair` |
| overview 门槛 40 | `DeepReadSectionWriterTools.kt:1178-1179` |
| diagram 相邻边 + 测试锁定 | `DeepReadSectionWriterTools.kt:880-887`；`DeepReadRepositoryTest.kt:704-720` |
| prompt「流程/因果只写主链路」 | `DeepReadAgentRunManager.kt:726` — v3 §4 要求同步修改 |
| 验真 prompt 依赖 registry 白名单 | `buildVerificationReminder` 880-891 行 — v3 seed 策略直接服务此路径 |
| 预算提示测试 | `DeepReadPromptWordingTest.kt:29-55` — **无需重复做** |
| `runSection` 清空 verification | `markRunning` 初始值清 `verificationState`（75-80 行）— v3 §3 明确保留 |

### v3 待实现（plan 描述准确）

| 项 | 当前代码 |
|----|----------|
| `isDeliverableDraft()` 等 helper | 不存在，需加于 `DeepReadModels.kt` |
| `runVerificationOnly()` + `runCoverageAndVerificationTail()` | 不存在；tail 逻辑嵌在 `generateStages` 内层（396-478 行） |
| 替换 `sectionsReady() -> emptyList()` | 仍存在 `DeepReadAgentRunManager.kt:95-96` |
| `MAX_COVERAGE_SUPPLEMENT_PASSES = 2` | 当前单轮（449-463 行） |
| 中性 verification notice + retry 文案 | 当前全走 `DeepReadPartialErrorNotice`（565-587 行） |

---

## 问题清单

### 🔴 硬伤

**无。** v3 相对 v2 无新增阻断项。

---

### 🟠 必改（实现时注意，plan 已覆盖方向）

#### 1. 抽取 tail 时禁止对 supplement 阶段调用 `markRunning`

`markRunning` 的 fold **初始值**即清空 `verificationState`（`DeepReadSectionWriterTools.kt:77-80`），与 v3 §2「不重置 verification 为 PENDING」冲突。

Coverage supplement 调用 `runStageSupervisorLoop(targetStage, coverageReport)`（457 行）时，现有 `generateStages` 已在 225 行对**整批 stages** 调过 `markRunning`；verification-only **全程不得**调用 `markRunning`，包括 supplement 阶段。实现 `runCoverageAndVerificationTail` 时勿「为方便 UI 显示 RUNNING」而对 `targetStage` 单独 `markRunning`——应只用 `markPhase(VERIFYING)`（现有 456 行模式）。

---

#### 2. `runCoverageAndVerificationTail` 需提升嵌套函数为类级私有方法

当前 tail 依赖 `generateStages` 内 `runCatching` 块内的局部 suspend 函数：

- `runStageSupervisorLoop`（231-350 行）
- `runVerificationPass`（356-394 行）
- `runVerificationSupervisorLoop`（396-409 行）

以及闭包捕获的 `writer`、`hiddenSettings`、`model`、`assistant`、`verificationTools`、`evidenceRegistry`、`articlePlan`、`evidencePack`、`researchHarness` 等。

v3 §2「复用 supervisor 代码、避免复制大块逻辑」= **refactor 为 class-level private + 参数 context**，而非 copy-paste。这是本轮回 **最大工程量**，建议在 plan 排期中单列 half-day refactor，但不改变 plan 正确性。

---

#### 3. Registry seed 的 URL 集合应对齐验真硬门语义

v3 §2 要求 seed：

- prefetch / evidence pack
- `output.references`、`output.extendedReading`
- image/source 相关 URL

验真硬门检查的是 **来源页 URL**（`DeepReadSectionWriterTools.kt:929-934`），不是图片 CDN URL。实现建议（可写进 PR 说明，不必改 plan）：

```kotlin
// 必须 seed
references + extendedReading 的 page URL
prefetch / evidencePack card.source.url

// 可选 seed（无害，但通常不进 evidence_urls）
hero_image_url、image_assets.url、timeline.image_url
```

`buildVerificationReminder` 会把 `evidenceRegistry.allowedUrls()` 注入 prompt（880-881 行）——seed 完整性直接决定重验真成功率。

---

### 🟡 可选细化

#### 4. verification-only 的 prefetch 为空应与 `generateStages` 同等失败

`generateStages` 在 prefetch 为空时返回 failure（166-176 行）。`runVerificationOnly` 应 **同样 hard fail**，plan 未写但 Assumptions 隐含「可重新 prefetch」。

---

#### 5. Coverage supplement 在 READY 段上的退出条件已存在，tail 抽取时勿改坏

`runStageSupervisorLoop` 在 coverage 模式下要求 `supplementWritten`（291-294 行）：

```kotlin
val supplementWritten = coverageReport == null ||
    writer.requiredWriteCount > initialRequiredWrites
if (stageReady && supplementWritten) return
```

即：目标段已 READY 时，必须有一次**新 writer 写入**才算 supplement 完成。抽取 tail 时保持此逻辑，否则 2-pass coverage 会空转。

---

#### 6. `targetStage == null` 分支仍为防御性描述

与 v1/v2 相同：`needsSupplement=true` 时 `DeepReadResearchHarness.kt:217-218` 几乎总能解析出 `targetStage`。v3 §3 写「停止补漏并 markVerificationFailed」与现逻辑一致。

---

#### 7. 通知 / 历史页

v3 §5 明确不做 — 与 `DeepReadNotifier.kt:42`、`DeepReadHistoryPage.kt:133-136` 现状一致，非阻断。

---

#### 8. `DeepReadScreenSplit` 仍为 scaffold

`DeepReadScreenSplit.kt:51-58` — 生产路径仅 `DeepReadScreen.kt`，v3 双分支 notice 范围足够。

---

## 专项结论

### 未验真误标 complete

| 检查点 | 结论 |
|--------|------|
| `isComplete()` 不变 | ✅ v3 §1 / §5 |
| `finishTool` 仍要 `hasFreshVerification` | ✅ `DeepReadSectionWriterTools.kt:478` |
| deliverable ≠ complete | ✅ `isDeliverableDraft() = sectionsReady()` 与 verification 解耦 |
| 单测已有锁 | ✅ `DeepReadSectionStateTest.kt:40-72` |

### 事实核查硬门

| 检查点 | 结论 |
|--------|------|
| refuted 阻断 | ✅ `verificationGate` 901-903, 948-950 行 |
| evidence_urls 白名单 | ✅ 929-934 行；v3 seed 强化重试路径 |
| evidence_excerpt 匹配 | ✅ 936-937 行 + `DeepReadEvidenceRegistry.kt:62-88` |
| token 比对 | ✅ `criticalEvidenceTokens` 100-111 行 |
| coverage 不被绕过 | ✅ v3 §2 共享 tail |

---

## Test Plan 审查

v3 Test Plan **完整且包名正确**，并吸收了 v2 建议用例：

- verification-only 仍跑 coverage tail ✅
- registry seed 含 output URLs ✅
- `initialDisplayError` 分流 ✅
- `runSection` 后 verification 不 fresh ✅

**建议实现时补充 1 条**（可选）：

| 用例 | 原因 |
|------|------|
| verification-only 不调用 `markRunning`，FAILED 的 errorMessage 在 RUNNING 前可读 | 文档 §4 UI running 优先策略的回归保护 |

测试仍放 `DeepReadRepositoryTest.kt`；无需新建 `DeepReadSectionWriterToolsTest`。

---

## 认可的 v3 落地顺序

与 plan 一致，按依赖排序：

1. **`DeepReadModels.kt`** — helper + 单测
2. **`DeepReadScreen.kt`** — 双分支 notice、`initialDisplayError`、retry 文案
3. **`DeepReadAgentRunManager.kt` refactor** — 提升嵌套函数 → `runCoverageAndVerificationTail` → `runVerificationOnly` → 替换 L95-96
4. **`DeepReadEvidenceRegistry` seed helper** — prefetch + output URLs（可放在 RunManager 或独立 private fun）
5. **`DeepReadSectionWriterTools.kt`** — overview / verificationGate / diagram + prompt 726 行
6. **测试收尾** — v3 Test Plan 命令

---

## v1 → v2 → v3 演进摘要

```text
v1: 方向对，但隐式 empty stages + UI 优先级 + 测试路径有误
v2: 显式 verification-only + UI 分流，但可能绕过 coverage / registry 未 seed
v3: 共享 coverage→verification→finish tail + registry seed + UI/initialDisplayError 闭环 → 可开工
```

---

## 一句话总结

**v3 可以按文档直接实现。** 开工时重点关注：tail 抽取时不调用 `markRunning`、registry seed 覆盖稿件 `references`/`extendedReading`、coverage supplement 保留 `supplementWritten` 语义。除此之外无 plan 级硬伤。
