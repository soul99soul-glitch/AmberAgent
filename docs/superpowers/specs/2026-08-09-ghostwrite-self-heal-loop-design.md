# 多章代笔自纠正 Loop 设计

> Status: Draft for implementation  
> Date: 2026-08-09  
> Product names: **代笔模式** / 本批多章  
> Depends on: Phase 0–4 in `docs/NOVEL_COCREATION_GHOSTWRITE_PLAN.md`  
> Evidence: 真机镜像 2026-08-09 — 第 1/5 章验收失败后整批停在 0/5；继续路径对 `acceptanceFailed` 复用旧稿再验，空转。

## 1. Outcome

多章代笔在「写 → 验 → 收录 → 同步 → 拟下一章」上成为**有界可自愈循环**：

1. 基建失败尽量无感重试。  
2. 质量失败在同一合同标准下自动改稿（有 attempt 预算）。  
3. 用尽预算后做**有限、可审计**的合同微调（薄升级）。  
4. 仍过不去时才打断用户；手工解套**优先走润色式修订链路**（自动推荐润色需求、可手改，像重新生成建议确认面），润修不过再给其它出口。  
5. 永不静默把未过质量门的稿自动进正史；失败上下文不污染下一章长期注入。

## 2. Non-goals

- 不保证一键成书 / 不保证文笔。  
- 不做多分支并行代笔。  
- 不把小说状态写入普通 Chat / Memory。  
- 不把失败全文长期灌进状态摘要或下一章默认注入。  
- 不在严重连续性上默认自动放行。  
- 手工解套**不**先把坏稿硬收录再走正史润色（候选尚未进书）。

## 3. Decisions Locked

| 决策 | 选择 |
| --- | --- |
| 默认策略 | **A′ 有界自愈** + **薄 C′ 可审计升级**；硬门不静默放行 |
| 同稿空转 | **禁止**：质量门失败后不得用同一候选对同一失败指纹再验 |
| 章内自动重写 | 默认开，最多 **3** 次（可配置，clamp 1…5） |
| 基建自动重试 | 默认开（同步 / 结构化解码 / 瞬态网络） |
| 合同自动微调 | 默认开但保守：单条 must 措辞对齐 / 追加 mustNot；改 goal/冲突 / 删多条 must → 要人 |
| 严重连续性 | 默认仍停人（沿用 `pauseGhostwriteOnBlockingContinuity`） |
| 本批打扰预算 | 默认最多 **1** 次硬打断（可调 0…3；0=仅硬失败才打断） |
| 手工解套第一路径 | **候选润修（polish-shaped）**：按阻塞生成可编辑润色需求 → 用户确认/改 → 产新候选 → 再过合同验收 |
| 润修失败后 | 编辑合同重写 / 整章重写（无 brief）/ 停批留候选 共创处理 |
| 领域权威 | 仍是小说项目文档；pipeline 状态可序列化在 progress / 批账本，不另起库 |

## 4. Why current batch sticks（证据 → 设计）

当前 `runGhostwriteBatch` / `runOneGhostwriteChapter`：

- 任一质量门失败 → `pauseGhostwritePipeline` → 等人。  
- `acceptanceFailed` 时 `obtainGhostwriteCandidate` **复用**旧候选；继续 ≈ 同一坏稿再验。  
- 失败 detail 只展示，不进入下一 attempt 的注入。  
- 面板无「系统下一步会干什么」的动作菜单。

真机症状：第 1/5 章红字（缺 must + 开篇复读），0 章收录，用户不知道改合同还是点继续。

## 5. Architecture

```text
                    ┌─────────────────────────────────────┐
                    │         Batch Loop (1…N)            │
                    │  plan → write → gate → collect →    │
                    │  clear plan → sync → credit → next  │
                    └───────────────┬─────────────────────┘
                                    │ chapter fail
                                    ▼
                    ┌─────────────────────────────────────┐
                    │     Chapter Heal FSM (per chapter)  │
                    │  Tier0 基建 · Tier1 同合同改写       │
                    │  Tier2 薄合同升级 · Tier3 打断       │
                    └───────────────┬─────────────────────┘
                                    │ Tier3 human
                                    ▼
                    ┌─────────────────────────────────────┐
                    │  Recovery Sheet（润修优先）          │
                    │  预填 brief ← failure receipt        │
                    │  用户可改 → 润修候选 → 再验收         │
                    │  仍失败 → 次级动作                   │
                    └─────────────────────────────────────┘
```

### 5.1 权威对象

| 对象 | 职责 |
| --- | --- |
| `NovelGhostwriteProgress` | 批进度、相位、暂停原因、candidate、pendingSyncCredit（既有） |
| **新增** `NovelGhostwriteHealState`（挂 progress 或同生命周期 storage） | 当前章 attempt、tier、failure fingerprint 环、本批打断计数、last receipt |
| **新增** `NovelGhostwriteFailureReceipt` | 结构化失败回执（注入用，有界） |
| **新增** `NovelGhostwriteRevisionBrief` | 润修需求文案（系统推荐 + 用户可改） |
| 本章合同 `NovelChapterPlanRecord` | 质量标准；Tier2 变更必须经 reducer + 账本 |
| 候选 `NovelCandidateRecord` | attempt 产物；绑定 plan digest；失败 attempt 标记不再用于空转 |

## 6. Failure taxonomy

| 类 | 例 | 默认策略 |
| --- | --- | --- |
| **Infra** | 同步失败、JSON 坏、超时、瞬态 provider | Tier0 自动重试；不进质量 attempt |
| **Quality-fixable** | 缺 must、明显复读、轻微可点名的问题 | Tier1 同合同改写 / 润修 brief |
| **Quality-structural** | 严重连续性 blocking | 默认 Tier3（可配置）；不自动硬收录 |
| **Domain-hard** | 收录事务失败、plan clear 失败、planMismatch | Tier3；提示领域修复，不空重写 |
| **User** | 暂停 / 取消 | 立即停；保留可续跑语义 |

## 7. Chapter Heal FSM

### 7.1 Tier 0 — 基建自愈

- 适用：`syncFailed`、结构化执行 `isRetryable`、短暂无输出超时（若可区分）。  
- 行为：指数退避重试（如 3 次）；看板 `同步重试 2/3`。  
- 成功：回批循环，不增加质量 attempt。  
- 耗尽：记 receipt，可再进 Tier3 或带 `pendingSyncChapterCredit` 等人（同步类）。

### 7.2 Tier 1 — 同合同自动改写（主自愈）

触发：`acceptanceFailed` / `obviousRepetition` /（可选）可自动的 minor 信号。  

每 attempt：

1. **封存**当前候选：标记 `supersededByHeal`（或移出「可复验集合」），**禁止**再被 `obtainGhostwriteCandidate` 选中。  
2. 从验收 / 复读 / 连续性输出构造 `NovelGhostwriteFailureReceipt`（有界字段，见 §8）。  
3. 生成新整章候选：注入 = 本章合同 + **本 attempt 的 receipt** + 既有 RECENT BEATS / UPCOMING ARC；**不**注入失败全文。  
4. 再跑既有门禁链（验收 → 复读 → 连续性 → 收录…）。  

预算：`qualityAttemptIndex` 1…`maxQualityAttempts`（默认 3）。  
指纹：`fingerprint = hash(reason + normalized missingMust + normalized repetition)`；若连续两次 fingerprint 相同且稿未实质变化 → 提前升 Tier2（防空烧）。

**强制规则：质量失败后的下一动作永远是 mustRewrite（新候选），绝不是 re-accept 旧候选。**

### 7.3 Tier 2 — 薄合同升级（可审计小 C）

仅当 Tier1 耗尽且失败类允许：

| 失败模式 | 允许自动动作 | 禁止 |
| --- | --- | --- |
| 缺 1 条 must，其余过 | 将该条 must 改写为与稿一致的措辞，或标 soft（仍保留 intent 一句） | 删多条、改 goalAndConflict |
| 纯复读 | 把撞车 beat 追加进 `mustNotHappen` | 关闭复读门 |
| 严重连续性 | **不**自动 | — |
| 验收模型空/矛盾 | 重跑审稿一次或换 effective review 策略一次 | 当 accepted 放行 |

合同变更：

- 走既有 `upsertChapterPlan`（confirmed），digest 更新。  
- 写入 **批账本** `contractAmendments[]`：`{ chapterIndex, beforeDigest, afterDigest, fields, reason, at }`。  
- 面板可展示「系统改过本章合同」；提供「撤回本批系统合同修订」（还原到开批时快照或上一条用户确认版——实现取最小：按 amendment 逆序回放）。

升级后：重置质量 attempt 计数 **一次**（仅 Tier2 入口给一轮额外写），再失败进 Tier3。

### 7.4 Tier 3 — 打断用户（润修优先）

触发：Tier1+2 用尽 / 严重连续性 / domain-hard / 死循环指纹 / 本批打扰预算策略要求呈现。

打断时 **不**只丢红字。主 CTA 序：

1. **按审稿意见润修**（默认高亮）— 打开 §9 Recovery Sheet  
2. **编辑本章合同后重写**  
3. **停本批，留下候选**  

次级（折叠或「其它方式」）：整章无 brief 重写、改审稿模型后再试、（可选）放弃本候选。

本批 `humanInterruptCount` 递增；超过预算后同类 soft 失败可合并为一次打断（避免刷屏）。

## 8. Failure receipt（注入隔离）

```text
NovelGhostwriteFailureReceipt
  reason: acceptanceFailed | obviousRepetition | blockingContinuity | …
  summary: String          // ≤ 400 字
  missingMustHappen: [String]  // ≤ 6 条，每条 ≤ 120 字
  forbiddenHits: [String]      // 撞 mustNot
  repetitionBeats: [String]    // ≤ 4
  continuityNotes: [String]    // ≤ 4，仅 blocking 摘要
  attemptIndex: Int
  sourceCandidateID: NovelCandidateID?
  planDigest: String
  fingerprint: String
```

**注入白名单（写 / 润修）**

- 当前 confirmed 合同  
- 本 receipt（短）  
- RECENT WRITTEN BEATS / UPCOMING ARC / 状态摘要（既有预算）  
- Tier2 后的新合同  

**注入黑名单**

- 失败候选全文（可在润修路径作为 *source body* 输入生成器，但不进入状态摘要 / 下一章默认上下文）  
- 更早 attempt 的叠床架屋 receipt（只保留最新 1 份 + fingerprint 环最多 3）  

**持久化**

- receipt 挂 heal state / progress；章成功收录后可只留 fingerprint 审计，不把长文进 state snapshot。

## 9. Human recovery：润修链路（优先）

### 9.1 为何不是「先收录再润色」

失败时候选 **未进正史**。硬收录会污染章节与剧情同步。  
因此手工第一路径是对 **未收录候选** 做「润色形态」的修订，再走 **同一套合同验收 → 自动收录**。

与既有整章润色差异：

| | 正史整章润色 | 代笔解套润修 |
| --- | --- | --- |
| 源 | 已收录 chapter version | 失败 prose 候选 |
| 用户 brief | 项目 `polishPreference` | **由阻塞生成的 revision brief**（可手改） |
| 闸 | polish drift（禁止改剧情事实） | **合同验收 + 复读 + 连续性**（允许补合同内必发生，禁止自由开新线） |
| 成功后 | polish adopt 事务 | ghostwrite `systemAutoCollect` 既有路径 |

实现上可复用 polish 的生成/完整章校验骨架，但 **不得** 走 `adoptPolishCandidate` 假装源章已存在；输出仍是 `kind: .prose` 候选并绑定原 `chapterPlanDigest`（若 Tier2 改过合同则绑新 digest）。

### 9.2 Recovery Sheet UX（对齐「重新生成建议」确认感）

入口：代笔看板 Tier3 / 「按审稿意见润修」。

Sheet 内容：

1. **阻塞摘要**（只读）：来自 receipt 人话。  
2. **润修需求**（可编辑多行 TextEditor）：  
   - 系统 `recommendRevisionBrief(receipt, plan)` 预填，例如：  
     - 补写：主角对京娘「碍事/心里不爽」的明确内心或动作（或与合同对齐的等价表达）  
     - 开篇禁止再写「赵大放缓步子等京娘」同类节拍；换新开场  
     - 保持其余已写情节与 POV  
   - 用户可改、可清空重写；「重置为推荐」恢复系统稿。  
3. **可选**：是否同时采用项目 `polishPreference` 文风句（Toggle，默认开若已配置）。  
4. 主按钮：**开始润修**；取消回看板。

交互约束：IME 经 `NovelTextInputCommitter`；热区 ≥44pt；Reduce Motion 尊重既有 sheet。

### 9.3 润修执行

1. 校验仍有可用失败候选 + 当前 confirmed 计划 digest 匹配（或用户已确认 Tier2 新合同）。  
2. `phase = .revising`（新相位，计入 isGhostwriting）。  
3. 启动有界生成：source = 候选正文，user/system brief = 编辑后的 revision brief + 合同要点 + receipt 短列表。  
4. 产出新 prose 候选，绑定 plan；旧候选 supersede。  
5. **不**自动跳过门禁；完整再跑验收链。  
6. 通过 → 自动收录 → 同步 → 计章 → 批循环继续（打断态清除）。  
7. 失败 → 更新 receipt；同一 Recovery Sheet 可再开（润修 attempt ≤ 2）；仍失败展开 **其它方式**：  
   - 编辑合同后重写  
   - 丢弃候选并整章重写  
   - 停批  

### 9.4 与「资料建议 / 重新生成」体验对齐的点

- **先推荐、确认后执行**，不静默开写。  
- 推荐字段可编辑，提交前本地 state，取消不落盘 brief（receipt 仍保留）。  
- 主路径只有一个明确 CTA，次级动作不抢主按钮。

## 10. Batch loop 整合（替换「一失败就 return false」）

伪流程：

```text
while completed < target:
  ensure plan (propose if needed)  // Tier0 on sync
  loop chapter heal:
    write or revise candidate      // never re-accept dead candidate
    run gates
    if pass: collect → clear plan → sync → credit → break heal
    if infra: Tier0
    if quality and attempts left: Tier1
    if quality exhausted and amendable: Tier2 then continue heal
    else: Tier3 wait human (revision sheet / edit plan / stop)
      on human resume: continue heal or abort batch
  next chapter
finish batch
```

取消 / binding 切换：沿用既有 cancel；heal state 随 progress 清或保留同 binding 续跑字段。

## 11. Progress / UI 契约

### 11.1 相位扩展

既有：`writing | accepting | collecting | syncing | planning | paused | waitingUser | failed`  
新增：`revising`（润修生成中）、可选 `healing` 总括或用 detail 表达。

### 11.2 看板文案

- `写✓ · 验收重试 2/3`  
- `同合同改写中 · attempt 2/3`  
- `合同已自动微调 · 再写`  
- `待你处理 · 建议润修`  
- `润修中`  
- 成功批：`k/N` 不变语义  

### 11.3 按钮

- 批可续跑且非完批：`继续代笔` / 运行中 `暂停`  
- Tier3：主按钮 `按审稿意见润修`，次 `编辑合同`，`停本批`  
- **删除语义陷阱**：验收失败后「继续」= 进入 heal/重写或打开润修，**不是**再验旧稿  

### 11.4 继续路径修正（P0 必做）

`obtainGhostwriteCandidate` / start 续跑：

- `acceptanceFailed` / `obviousRepetition` / `blockingContinuity` / 任意质量失败 → **mustRewrite 或 mustRevise**，不保留旧 candidate 作验收输入。  
- 仅「验收通过后的基建失败」可保留 candidate 身份做 sync/collect 续跑。

## 12. 数据字段（最小增量）

挂在 `NovelGhostwriteProgress`（或并列 heal storage，同 binding 生命周期）：

```text
maxQualityAttempts: Int = 3
qualityAttemptIndex: Int = 0
healTier: none | infra | rewrite | amend | human
lastFailureReceipt: NovelGhostwriteFailureReceipt?
recentFingerprints: [String] // ≤3
humanInterruptCount: Int
contractAmendments: [Amendment]
revisionBriefDraft: String?  // sheet 编辑中可仅内存
maxRevisionAttempts: Int = 2
revisionAttemptIndex: Int = 0
```

序列化：批中断恢复需要的字段进可持久化 progress；纯 UI draft 可不落盘。

## 13. 注入与 Prompt

- 新模板或扩展：`novel.ghostwrite-revision.v1`（润修 brief + 源章候选 + 合同）。  
- Tier1 自动改写：userText / system 附加 `HEAL RECEIPT` 段（catalog 约束长度）。  
- 验收 / 复读 prompt 不改契约字段名；decoder 仍 fail-closed。  

## 14. 测试计划（实现时）

最小红/绿：

1. `acceptanceFailed` 后续跑 **不得** 再验同一 candidateID。  
2. Tier1：两次失败后第三次写收到最新 receipt；旧 receipt 不叠成超预算。  
3. fingerprint 连撞提前 Tier2/3。  
4. Tier2 amendment 改 digest；候选绑定新 digest 才能收录。  
5. Recovery brief 预填含 missingMust / repetition；用户编辑后生成用编辑稿。  
6. 润修成功 → autoCollect → completedChapterCount+1（经 sync credit）。  
7. 润修两次仍失败 → 展示次级出口；不自动硬收录。  
8. 严重连续性默认不进 Tier1 空转（直视配置）。  
9. 取消 / binding 切换不串 run。  
10. 回归：`NovelCollaborationModeTests` / proposal / acceptance / polish 门禁套件。

## 15. 分期实现建议

| Slice | 内容 | 可验证 |
| --- | --- | --- |
| **P0** | 禁止质量失败同稿再验；继续=重写；看板写清原因与下一步 | 真机不再 0/5 空点继续 |
| **P1** | Failure receipt + Tier1 有界自动改写 + 看板 attempt | 多数缺 must/复读无打断过关 |
| **P2** | Tier0 基建重试；Tier2 保守合同微调 + 账本 | 少打扰、可审计 |
| **P3** | Tier3 Recovery Sheet 润修优先 + 次级出口 | 手工解套路径完整 |
| **P4** | 打扰预算、fingerprint 熔断、文案打磨、真机 3～5 章批跑 | 产品手感 |

## 16. 文案口径

- 代笔会先自己改，改不动再请你看。  
- 请你看时，优先用「按意见润修」，需求可改。  
- 仍不过关不会假装进书。  
- 避免：「全自动完美」「智能保证质量」。

## 17. 文档同步（实现落地时）

- 更新 `NOVEL_COCREATION_GHOSTWRITE_PLAN.md` 增加 Phase 5 指向本 spec。  
- 更新 `PROJECT_STATE.md` 当前下一刀。  
- 必要时 `NOVEL_CREATION_SPEC.md` 代笔节补「自愈与润修解套」一句。

## 18. Open points（实现前可默认）

| 项 | 默认 |
| --- | --- |
| maxQualityAttempts | 3 |
| maxRevisionAttempts（人工润修） | 2 |
| 本批 humanInterrupt 预算 | 1 |
| Tier2 默认 | 开（保守） |
| 严重连续性 | 不自动升级合同、不停在 Tier1 空转 |
| 润修模型 | 创作模型（与写整章同 role）；验收仍 review |

---

**成功标准（产品）**

- 批跑 5 章时，偶发缺拍/复读大多在无人打断下过关。  
- 真停住时，用户 10 秒内知道点「润修」还是「改合同」，且润修需求已预填可改。  
- 不出现「继续 = 同一坏稿再验」；不出现失败全文污染下一章。  
- 未过门禁的稿永不 `systemAutoCollect`。
