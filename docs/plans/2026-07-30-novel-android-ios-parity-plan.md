# Android 小说创作：iOS 能力补齐实现计划

> Status: **COMPLETE track** (M1–P2 MVP landed 2026-07-31; XL ledger/pacer deferred)  
> Date: 2026-07-30  
> Scope: Android 主仓 `amberagent` 小说创作功能  
> Baseline: Android MVP 已落地（见 `docs/NOVEL_ANDROID_PROJECT_STATE.md`，2026-07-13）  
> Reference (iOS evidence): `amberagent-ios` 的 `iosApp/iosApp/NovelCreation/**`、  
> `NOVEL_HANDOFF_2026-07-26.md`、`docs/NOVEL_SESSION_MEMORY_PLAN_2026-07-19.md`、  
> `docs/NOVEL_UX_SIMPLIFICATION_PLAN.md`、`docs/PROJECT_STATE.md`（批量润色等）

---

## 1. 目标一句话

把 Android 小说创作从「能生成、能收录、能读」提升到与 iOS 当前主路径**同级的创作闭环质量**：  
收录后设定跟着长、收录可控、分支/导入导出可达、能重写消矛盾、长讨论不崩设定、长篇有工具链，并逐步对齐长流式手感。

完成标准不是「页面有按钮」，而是每条用户路径有：

1. 可见入口  
2. 领域闭环（提交 / 回滚 / 失败可理解）  
3. 定点测试或可复现手测步骤  
4. 与 iOS V1 项目包不破坏互通（schema 单向兼容；新增字段无则旧行为）

---

## 2. 当前基线（诚实快照，2026-07-30）

### 2.1 Android 已有（可用主路径）

| 区域 | 已有能力 |
|------|----------|
| 项目 | 列表、新建空白 / 快速开始、重命名、删除、degraded restore |
| 生成 | 讨论 / 续写 / 整章、QuickStart 建议、停止、后台中断 |
| 收录 | 一键收录到正文（自动猜目标）、supersede 候选 |
| 正文 | 目录、全屏连读、编辑本章、单章润色 adopt |
| 设定 | 角色 / 世界观 / 剧情资料浏览、建议确认、角色经历摘要 |
| 设置 | 项目模型、润色偏好、撤销最近收录 |
| 领域 | reducer / package codec / recovery sidecar / lifecycle bridge |

### 2.2 关键假象（领域有、UI 无 / 行为被关掉）

下列能力在 ViewModel / domain **已有方法或字段**，但当前 Compose **没有可达入口**，或行为被刻意降级：

| 项 | 证据位置 | 现状 |
|----|----------|------|
| 项目包导入 / 导出、Markdown 导出 | `NovelProjectsViewModel.importPackage/exportPackage/exportMarkdown`、`NovelWorkspaceViewModel.exportMarkdown` | **无 UI 调用点** |
| Fork / 重命名分支 / 设主分支 | Workspace + Settings VM | **无 UI 调用点** |
| 段落选择 | `NovelParagraphSelection` + collect 内默认全选 | **UI 未接** |
| 收录状态增量 | `CollectCandidate(runStateDelta=…)` | UI **默认 `false`**（为了快） |
| keep-both 导入 | `NovelProjectImportPolicy.KeepBoth` | schema 有，**未完整 remapping + UI** |
| polish / manualSync 账本字段 | document 字段存在 | **未按 iOS 完整事务机接线** |
| `replaceChapter` / `regenerate` | 无 | **领域 + UI 均无** |

### 2.3 与 iOS 的产品契约差异（不能混写）

- iOS 信息架构：工作区 **创作 \| 资料**（资料下正文 / 角色 / 世界观 / 剧情 / 更多）。  
- Android 当前：**创作 \| 正文 \| 设定** 三 Tab + 独立设置页。  
- **本计划默认不强制改成两 Tab**；优先把缺失闭环补上。若某切片需要收敛入口，可局部迁到「更多 / 设置」而不做大改版。  
- 禁止把小说 Session 塞进普通 Conversation / Memory / Room chat 表。  
- 禁止 provider await 持有写锁。  
- schema 变更必须过 validator，旧文档无新字段 = 旧行为。

---

## 3. 优先级总览（按用户价值）

```text
P0  创作闭环质量 + 用户点得到的基础设施
    ├─ P0-A  收录后剧情状态跟着更新（state-delta 默认开、可失败可重试）
    ├─ P0-B  收录可控（目标选择 + 段落多选 + 中断草稿可收）
    └─ P0-C  导入导出 / 分支管理 UI 入口（能力写了但点不到）

P1  安全感与改剧情能力
    ├─ P1-A  整章重写 + replaceChapter 收录
    ├─ P1-B  版本历史 + 气泡就近撤销收录
    └─ P1-C  讨论归档 MVP + 注入上下文可见

P2  长篇工具链与手感
    ├─ P2-A  剧情一致性审计
    ├─ P2-B  批量整章润色
    ├─ P2-C  流式滚动 / 完成闪烁 / presentation pacing
    └─ P2-D  keep-both 导入 + 完整 polish 事务账本 + multi-chunk manual sync ledger
```

依赖关系（硬约束）：

```text
P0-A ─┬─> P1-A（重写后状态增量要知道「替换」语义）
      └─> P1-C（归档蒸馏依赖稳定的 session / material 写入）

P0-B ──> P1-A（replace 目标复用收录 Sheet）

P0-C 独立，可与 P0-A/B 并行

P1-B 独立，可与 P1-A 并行

P2-A 依赖结构化模型执行路径（可先建 executor 骨架）
P2-B 依赖单章润色路径稳定（已有）+ 串行 busy 语义
P2-C 独立打磨，不挡功能切片
P2-D 可穿插在 P1 之后，偏正确性边角
```

---

## 4. 切片计划

每个切片交付形态固定为：

1. **用户故事**（一句话）  
2. **现状缺口**  
3. **iOS 参考**  
4. **改动边界**（允许 / 禁止）  
5. **实现步骤**  
6. **验收**  
7. **测试**  
8. **预估规模**（S ≤1 天 · M 1–2 天 · L 3–5 天 · XL >5 天，单人）

---

### P0-A · 收录后剧情状态更新

**用户故事**：我点「收录到正文」后，角色经历 / 剧情摘要 / 待确认建议会跟着更新，而不是只有正文变了。

**现状缺口**

- `NovelWorkspaceViewModel.collectCandidate` 固定 `runStateDelta = false`。  
- 领域 `DefaultNovelCreation` / `NovelCollectionReducer` 已支持可选 state-delta。  
- 手动同步路径已有类似 structured 解码。

**iOS 参考**

- 收录 fact transaction 完成时写事件 + 状态快照 + 可选 setting proposals。  
- 失败时保留正文收录或 pending（以 iOS 当前不变量为准，Android 对齐「正文不丢」）。

**改动边界**

- 允许：`NovelWorkspaceViewModel`、`DefaultNovelCreation` collect 路径、进度 / 错误文案、设定 Tab 刷新。  
- 禁止：改普通 Chat；把 state-delta await 放进 write mutex。

**实现步骤**

1. 收录默认改为 `runStateDelta = true`；在 UI 显示非阻塞进度（「收录中…」「更新剧情状态…」两阶段文案）。  
2. state-delta 失败策略（二选一，实现前写死进测试）：  
   - **推荐**：正文已提交 + 分支标记 `needsSync` / 可「重试状态更新」——不丢正文；  
   - 或整单回滚（体验更差，仅当无法表达半成功时使用）。  
3. 成功后自动 `refresh`，设定 Tab 未决建议角标更新。  
4. 同步文案：收录成功提示从「可在正文查看」扩展为「正文已更新 · 剧情状态已同步 / 待同步」。  
5. 可选设置：「快速收录（跳过状态更新）」放在高级区，默认关。

**验收**

- 整章收录含角色名 → 角色经历新增事件。  
- 剧情摘要 / 分支走向变化可见（非空项目）。  
- 断网或模型失败：正文仍在，用户能看到可操作的失败原因与重试。  
- 生成中 / needsSync 时收录仍被正确阻塞。

**测试**

- Domain：collect + stateDelta 提交 / 失败半成功契约。  
- VM 或 instrumentation：默认 flag 为 true；快速收录路径可关。  
- 回归：`:feature:novel:testDebugUnitTest`。

**规模**：M

---

### P0-B · 收录可控（目标 · 段落 · 中断草稿）

**用户故事**：收录时我能选择并入哪一章 / 新开章，能只收部分段落；点停止后已生成的内容仍可收录。

**现状缺口**

- 一键收录，目标自动 `suggestTarget`，无 Sheet。  
- 段落逻辑仅 domain 全选。  
- `collectCandidate` 要求 `Available`；中断候选不可收。

**iOS 参考**

- `NovelCollectCandidateSheet`：目标选择 + 段落多选 + 全选/反选。  
- Session memory S1：interrupted prose 可 collect，文案「生成已中断 · 可收录已生成部分」。

**改动边界**

- 允许：Compose 收录 Sheet、candidate action bar、collection reducer 对 `Interrupted` 合法性、气泡状态行。  
- 禁止：自动续写中断内容；改 candidate 其他状态机语义。

**实现步骤**

1. **Collect Sheet**  
   - 目标：`并入第 N 章` / `新开第 N+1 章`（默认规则与 iOS 一致：整章 → 新开，续写 → 并入；无章 → 第一章）。  
   - 段落列表：复用 `NovelParagraphSelection.splitParagraphs`；默认全选；至少选 1 段才可确认。  
   - 显示字数 / 选中段数。  
2. Action bar：「收录到正文」→ 打开 Sheet，不再直接 perform。  
3. **Interrupted 收录**  
   - domain 放开 interrupted prose（保留 needsSync / stale / branch 等 blocker）。  
   - UI 对 interrupted 显示主按钮「收录已生成部分」+ 次按钮「丢弃/重试」（重试已有 resend 可复用）。  
4. 文案与 hint：Sheet 标题与确认按钮体现目标（「收录并新开第 3 章」）。

**验收**

- 整章候选默认新开；续写默认并入；用户可改。  
- 只选前两段 → 正文仅含两段。  
- 停止后 partial 可收录；收录后不可再当 Available 收一次。  
- needsSync 时 Sheet 确认按钮禁用并显示原因。

**测试**

- `NovelParagraphSelection` 已有单测；补 Sheet 默认目标与 interrupted collect 域测。  
- UI 至少手工清单；有余力补 Compose 测试。

**规模**：M

---

### P0-C · 导入导出与分支管理 UI 入口

**用户故事**：我能导出 / 导入项目包和 Markdown；能 Fork 分支、改名、设主分支，而不需要懂工程命令。

**现状缺口**

- VM 方法存在，Compose 无调用；项目卡片菜单只有重命名 / 删除。  
- Settings 页只有模型 / 润色 / 撤销收录。

**iOS 参考**

- 项目列表：导入；工作区「更多」：导出包 / Markdown、分支管理。  
- 导入冲突：拒绝 / 替换 / 保留两份（keep-both 本切片可先做 **拒绝 + 替换**，keep-both 放 P2-D）。

**改动边界**

- 允许：`NovelProjectsPage` SAF、`NovelSettingsPage` 或工作区「更多」段、错误提示。  
- 禁止：改 package codec 语义；本切片不做 keep-both ID 重映射。

**实现步骤**

1. **项目列表**  
   - TopBar 或 FAB 旁：「导入项目」。  
   - 卡片菜单：「导出项目包」「导出 Markdown」「重命名」「删除」。  
   - 导入同 id：对话框「取消 / 替换现有」（替换走已有 `replaceProjectId`）。  
   - 超大包 / 读失败：可见错误（已有 oversize 常量 `MAX_ENVELOPE_BYTES`）。  
2. **设置页或工作区入口「分支」**  
   - 列表当前分支、主分支标记。  
   - 操作：重命名、设为主分支、从当前 head Fork（输入名称）、撤销最近收录（已有，可并列）。  
   - 忙碌 / 生成中：禁用并给原因。  
3. 导出成功：系统分享或 SAF `CreateDocument` 落盘；Toast 文件名。  
4. 补文案：导入成功 disposition（created / replaced）。

**验收**

- 空项目 → 导出包 → 另一安装导入 → 可打开继续写。  
- Markdown 导出含当前分支章节顺序。  
- Fork 后自动切到新分支（与 iOS 一致）并有轻提示。  
- 替换导入后旧 revision 数据被新包覆盖且可写。

**测试**

- 已有 codec / import domain 测；补 VM 层 replace 路径与「无 UI 时不可达」的反例不再需要。  
- 手测 SAF 真机必做（模拟器可部分覆盖）。

**规模**：M（真机 SAF 验证拉长时算 M–L）

---

### P1-A · 整章重写 + replaceChapter 收录

**用户故事**：我对某一章不满意时可以「重写本章」，收录后替换原文（允许改剧情），而不是只能润色或手改。

**现状缺口**

- 无 `NovelRunKind.Regenerate`。  
- 无 `NovelCollectionTarget.ReplaceChapter`。  
- 阅读菜单无「重写本章」。

**iOS 参考**

- `NovelRunKind.regenerate` + `NovelPromptKind.wholeChapterRegeneration`。  
- 必须带 `sourceChapterVersionID`；产出 prose 候选；收录默认 `replaceChapter`。  
- 替换后状态增量应理解「替换第 N 章」而非纯追加（iOS handoff 仍记 P0 残留：状态只加不减；Android 实现时**尽量一次做对**，见步骤 5）。

**改动边界**

- 允许：enums / generation reducer / collection reducer / prompt catalog / injection planner / reader menu / collect sheet。  
- 禁止：把 regenerate 伪装成 polish；禁止用 `sourceChapterVersionID` 塞进普通 prose 却不改 kind（iOS 已证死路）。

**实现步骤**

1. Schema / wire：`regenerate` run kind；`replaceChapter(chapterID)` collection target；Swift 兼容序列化与 fixture。  
2. Prompt：整章重写专用提示（允许改剧情；注入被重写章全文 + 前后文策略与 iOS 对齐）。  
3. Generation 守卫：regenerate 必须 source 版本；prose 禁止该字段；document validator 同步。  
4. UI：阅读 ⋯ →「重写本章」；busy / needsSync / 只读时显示禁用原因。  
5. Collect Sheet：`hasRegenerationTarget` 时默认 replace，并展示「将替换第 N 章 · 原文版本保留在历史」。  
6. **状态增量**：state-delta / 提取输入标明「替换章 + 旧正文」，避免只加不撤（对齐 iOS 未竟修复目标）。  
7. 导出 / 注入：被替换链上的废弃版本不进成稿（若引入 discarded 标记则与 iOS 一致；否则至少 head 选择正确）。

**验收**

- 重写第 3 章 → 候选标注「重写本章 · 收录后替换原文」→ 收录后第 3 章内容为新文，章数不 +1。  
- 版本历史仍能看到旧版（P1-B 若未做，至少数据层有多版本）。  
- 润色路径不受影响；regenerate 不走 drift fail-closed。  
- 错误：对非目标章 replace 被拒。

**测试**

- 红→绿：发起 regenerate、shape 守卫、replace collect、错误目标。  
- Prompt catalog 快照 / 版本号更新。  
- 跨端 fixture：含 regenerate 候选的 package 双向解码。

**规模**：L

---

### P1-B · 版本历史 + 气泡就近撤销收录

**用户故事**：我能查看一章的历史版本并恢复；刚收录错了可以在对话气泡上撤销，不必钻进设置。

**现状缺口**

- `RestoreChapterVersion` 等 operation kind 存在，UI 无版本历史。  
- `undoHead` 仅设置页危险操作。

**iOS 参考**

- 阅读菜单「版本历史」Sheet。  
- 已收录且仍为 head 的候选气泡：「撤销收录」+ confirmation。

**改动边界**

- 允许：reader menu、versions sheet、chat action bar、undo 确认文案。  
- 禁止：物理删除历史版本；破坏兼容链的「真删」。

**实现步骤**

1. 版本历史 Sheet：列表 kind / 时间 / 字数；预览；「恢复为此版本」（走 domain restore，触发 needsSync 规则与 iOS 一致）。  
2. 恢复失败原因用户可读（head 不匹配、busy、只读）。  
3. 候选气泡：collected + isHead →「撤销收录」→ 确认 → `undoHead`。  
4. 设置页撤销保留作第二入口。  
5. 可选：章节目录显示版本数角标。

**验收**

- 润色 / 手改 / 收录产生多版本后，历史可浏览可恢复。  
- 撤销收录后正文回退，候选状态正确，可再次生成。  
- 非 head 候选不显示撤销或不允许。

**测试**

- Domain restore / undo 已有则补 UI wiring；缺则补 reducer 测。

**规模**：M

---

### P1-C · 讨论归档 MVP + 注入上下文可见

**用户故事**：长讨论不会静默失忆；我能归档讨论结论到资料，并看见本次生成模型实际吃到了哪些上下文。

**现状缺口**

- 无 `decisionLog`、无 `archiveCursor`、无归档 UI、无注入面板。  
- Android material kind 目前：world / character / masterOutline / writingRequirements / custom。

**iOS 参考**

- `NOVEL_SESSION_MEMORY_PLAN` S1–S3（S1 中断已在 P0-B；本切片做 S2+S3）。  
- 注入 receipt 面板只读；归档蒸馏 → 确认流 → decisionLog + 折叠卡片。

**改动边界**

- 允许：schema 扩展（兼容）、structured 蒸馏任务、session 投影折叠、composer 上下文面板、资料「决定」分区。  
- 禁止：自动定时归档；删除原始消息；改普通 Chat 记忆系统。

**实现步骤**

1. **S2 注入可见（可先单独合并）**  
   - 确认 / 扩展 injection receipt 字段（资料清单、是否带状态摘要、窗口轮数、排除计数）。  
   - Composer「本次上下文」面板只读展示。  
2. **S3 讨论归档**  
   - material kind `decisionLog` + session `archiveCursor` + 归档记录列表。  
   - 触发：整章收录成功后可选步骤；会话菜单手动「归档当前讨论」。  
   - 蒸馏 structured JSON → 用户逐条确认 / 编辑 / 拒绝。  
   - 确认后写入 decisionLog；投影折叠游标前讨论为卡片。  
   - 注入：游标前原始讨论退出窗口，由 decisionLog + 摘要替代。  
3. 资料区展示「决定」有效集（supersede 语义可放本切片末或 P1-C.1 跟随）。

**验收**

- 长讨论后打开上下文面板，能看到注入了哪些资料 / 是否带状态。  
- 归档确认后讨论折叠，再次生成不再塞满旧闲聊。  
- 旧项目无 archive 字段行为不变。  
- 蒸馏失败可重试，不落半截。

**测试**

- 解码 / validator 兼容；归档提交顺序；注入排除已归档消息。  
- 真实 provider 质量标为外部验证项。

**规模**：L–XL（建议拆：C1 注入面板 M；C2 归档 MVP L）

---

### P2-A · 剧情一致性审计

**用户故事**：我能扫描全书，看到重复剧情、前后矛盾等问题列表，并跳到对应章节。

**现状缺口**：Android 无 continuity audit。

**iOS 参考**：`NovelContinuityAudit*`、`NovelContinuityAuditView`、chunked structured executor。

**实现步骤**

1. Schema：`NovelContinuityAuditV1` 问题类型 / 严重度 / 章节引用 / 证据。  
2. **StrictJSON** 解码，不可跳过。  
3. Prompt + `NovelStructuredModelTask` 分发；分块计划与 token 估算。  
4. 入口：正文目录或资料「剧情」→「检查一致性」。  
5. 结果列表 → 点进阅读定位；报告相对 head 的 stale 标记。

**验收**：有正文项目跑出可点列表；取消中途安全；stale 报告提示「正文已变」。

**测试**：mapper / planner 单测；executor fake；UI 手测。

**规模**：L

---

### P2-B · 批量整章润色

**用户故事**：我选中多章后一键按序润色，漂移章跳过，失败可汇总，不必逐章点。

**现状缺口**：仅单章润色。

**iOS 参考**：`NovelBatchPolishSheet` + 串行 adopt（项目同时仅一 run）。

**实现步骤**

1. 目录多选 UI +「批量润色」Sheet（选择 / 运行 / 报告三态）。  
2. 串行：start polish → adopt（漂移 skip）→ next；可取消。  
3. 润色偏好未设置时引导去设置。  
4. 报告：采用 / 跳过 / 失败计数与章名。

**验收**：多章串行；漂移不改原文；取消停在下一章边界；旧版本仍在历史。

**测试**：batch 驱动单元测（fake model）；回归单章 polish。

**规模**：M–L

---

### P2-C · 流式滚动与呈现手感

**用户故事**：长章生成时列表不整页跳、完成不闪一下、向上翻历史不被「踢出窗口」。

**现状缺口**

- 基础 pin-bottom；无 iOS 级 history window 吸收、size-change 锚、terminal quiet window、presentation pacer。

**iOS 参考**

- `NOVEL_HANDOFF_2026-07-26` 列表位移根因；Session history window；presentation pacing；terminal 静窗退役。

**实现步骤**（可多小 PR）

1. 历史窗口 / 列表 identity：完成瞬间不驱逐已渲染行。  
2. 流式高度增长：贴底时同帧吸收，避免二次滚动打架。  
3. 完成闪烁：streaming → durable 交接 identity 稳定；可加短静窗。  
4. 可选：小说独立 presentation pacer（与 Chat 解耦）。  
5. 真机 长文 + 中文键盘 目测清单。

**验收**：长章流式无「整列表大幅位移」；完成瞬间无明显闪白；上翻历史不被强制拉回除非用户点回底。

**测试**：能单测的 policy / 窗口算术；手感真机必做。

**规模**：L（可持续迭代）

---

### P2-D · 互通与正确性边角

**用户故事**：从 iOS 导入冲突项目时能「保留两份」；润色 / 手动同步崩溃可恢复；账本可审计。

**包含**

1. **keep-both 导入**：新 project id + 全图 ID remapping + disposition UI。  
2. **完整 polish 事务账本**：transactions / attempts / assessments 与 abandon / retry 对齐 iOS。  
3. **multi-chunk manual sync progress ledger**：长稿同步可断点续跑、进度可见。  
4. **跨端 fixture 扩容**：regenerate、decisionLog、archive、continuity 报告等 golden。  
5. **真机 provider + 双向 package** 证据写入 `NOVEL_ANDROID_PROJECT_STATE.md`。

**验收**：iOS 导出包在 Android keep-both 后两项目独立可写；polish 中杀进程可恢复或明确 abandoned；manual sync 进度杀进程后续跑。

**规模**：XL（拆三个子切片 D1/D2/D3）

---

## 5. 建议执行顺序（里程碑）

| 里程碑 | 切片 | 用户可感知结果 | 建议退出标准 |
|--------|------|----------------|--------------|
| **M0 调研冻结** | 本计划评审 | 范围与默认策略确认（state-delta 半成功策略、是否拆两 Tab） | 本文 Status → Accepted |
| **M1 闭环质量** | P0-A, P0-B | 收录像「写进书」而不只是贴正文 | 手测清单 100%；novel 单测绿 |
| **M2 点得到** | P0-C | 导入导出 / 分支普通人可用 | SAF 真机导入导出各 1 次成功 |
| **M3 改剧情** | P1-A, P1-B | 重写替换 + 历史回退 + 就近撤销 | regenerate 红→绿 + 手测 |
| **M4 长讨论** | P1-C | 归档 + 看见注入 | 兼容旧文档；蒸馏可重试 |
| **M5 长篇工具** | P2-A, P2-B | 审计 + 批量润色 | fake 全绿；可选真机 |
| **M6 手感与硬核** | P2-C, P2-D | 长流式稳 + 互通边角 | 状态文档更新证据 |

并行建议：

- M1 内 P0-A ∥ P0-B（同一 collect UI 时串行更安全：先 B Sheet 再 A 默认 delta，或先 A 再 B）。  
- **推荐顺序：P0-B → P0-A → P0-C**（先有 Sheet 再在确认时跑 delta，避免两套收录入口）。  
- P1-B 可在 M3 与 P1-A 并行。  
- P2-C 任何时候可插空打磨，但不占用 P0 人力。

---

## 6. 模块与文件触点（地图）

```text
feature/novel/                          # 领域 / 持久化 / runtime
  model/NovelEnums.kt                   # RunKind、CollectionTarget、MaterialKind…
  model/NovelDocument.kt / Records.kt   # archive、receipt、polish 账本
  domain/*Reducer.kt                    # collect / generation / polish / manual sync
  domain/NovelDocumentValidator.kt
  runtime/NovelPromptCatalog.kt
  runtime/NovelInjectionPlanner.kt
  runtime/AndroidNovelModelAdapter.kt   # 仅结构化任务扩展时
  serialization/*                       # Swift wire / package
  DefaultNovelCreation.kt               # start / collect / import 协调

app/.../ui/pages/novel/
  NovelProjectsPage.kt                  # 导入导出入口
  NovelProjectsViewModel.kt
  NovelWorkspacePage.kt                 # 收录 Sheet、阅读菜单、气泡动作、批量/审计入口
  NovelWorkspaceViewModel.kt
  NovelSettingsPage.kt                  # 分支管理、高级开关
  NovelSettingsViewModel.kt
  NovelUi.kt                            # 共用控件

test-fixtures/novel-v1/                 # 跨端 golden（与 iOS 共享约定）
docs/NOVEL_ANDROID_PROJECT_STATE.md     # 每里程碑更新
docs/plans/本文件
```

---

## 7. 跨切面工程约束

1. **单项目单 live run**：批量润色 / 审计 / 生成互斥，busy 文案统一。  
2. **Provider 在锁外**：state-delta、蒸馏、审计分块均在 mutex 外 await。  
3. **错误用户可读**：`humanizeNovelError` 扩展新错误码，禁止只 log。  
4. **禁用必解释**：按钮隐藏或 `操作名（原因）`，禁止静默灰。  
5. **兼容**：新 enum case 解码未知时 fail-closed 或明确忽略策略写进 validator 测试。  
6. **不做**：云同步、多人协作、embedding 检索、第二套 Conversation 套壳、Room schema。  
7. **构建验证**：  
   - `./gradlew :feature:novel:testDebugUnitTest`  
   - 相关 app 测试若有  
   - `./gradlew :app:assembleDebug`（graphite 装机）  
8. **装机**：默认 `.graphite` debug 包，避免覆盖用户正式包。

---

## 8. 手测清单（每里程碑勾选）

### M1

- [ ] 续写收录默认并入当前章，剧情摘要有变化  
- [ ] 整章收录默认新开章，角色经历增加  
- [ ] 段落多选只收部分  
- [ ] 停止后收录 partial  
- [ ] state-delta 失败后正文仍在且可重试  

### M2

- [ ] 导出 `.ambernovel` → 导入成功  
- [ ] 替换导入确认  
- [ ] 导出 Markdown 可读  
- [ ] Fork 分支并继续写  
- [ ] 重命名 / 设主分支  

### M3

- [ ] 重写本章 → 替换收录 → 章数不变  
- [ ] 版本历史恢复旧版  
- [ ] 气泡撤销收录  

### M4

- [ ] 上下文面板展示注入清单  
- [ ] 归档讨论 → 折叠 → 再生成上下文变干净  

### M5+

- [ ] 一致性审计出列表可跳转  
- [ ] 批量润色进度与跳过  
- [ ] 长章流式无整表跳动  
- [ ] keep-both 两项目并存  

---

## 9. 风险与开放决策

| 风险 / 决策 | 影响 | 建议默认 |
|-------------|------|----------|
| state-delta 延迟导致收录变慢 | 用户嫌慢 | 默认开 + 进度文案；高级「快速收录」 |
| state-delta 半成功模型 | 实现复杂度 | 正文优先 + needsSync / 重试 |
| regenerate 与 prose 字段混用 | 文档损坏 | 独立 kind + validator，禁止复用错字段 |
| 替换章状态只加不减 | 矛盾消除失败 | P1-A 同步做「替换语义」增量 |
| 归档蒸馏质量依赖模型 | 产品信任 | 必须人工确认；失败可重试 |
| 两 Tab vs 三 Tab | 改版成本 | 本计划不强制改 IA |
| iOS 仍在演进 | 目标漂移 | 以本计划切片锁定；新 iOS 能力进 backlog 附录 |
| SAF / 厂商文件选择器 | 真机差异 | M2 必真机 |

**需要产品确认的默认（评审时勾选）**：

1. 收录默认跑 state-delta：是 / 否  
2. 快速收录开关：要 / 不要  
3. keep-both 是否进 M2：否（默认进 P2-D）  
4. 是否在 M4 前改两 Tab IA：否  

---

## 10. Backlog（已知 iOS 有、本计划不承诺首期）

- 章节废弃（discardedAt）完整产品流 + 恢复入口  
- 编辑器内查找替换  
- 独立 stateSync 模型角色（creation / stateSync 分模型）  
- decisionLog supersede 完整设定页历史  
- Dynamic Island / 系统级任务信标（Android 通知或无）  
- Chat 与小说共享 streaming vendor 级优化的全部移植  
- iOS 未竟：替换后状态回撤的进一步硬化（若 P1-A 未一次做满）  

---

## 11. 文档维护

- 本文件 Status 流转：`Proposed → Accepted → In Progress (Mx) → Done`。  
- 每完成一个切片：在切片标题下标记 `✅ yyyy-mm-dd`，并更新 `docs/NOVEL_ANDROID_PROJECT_STATE.md`：  
  - Closed loops 增补  
  - Still not iOS 1:1 删减  
  - 真机 / 互通证据链接  
- 若实现偏离计划，先改计划再改代码，避免口头范围漂移。

---

## 12. 附录：iOS → Android 能力对照速查

| 能力 | iOS | Android 现状 | 计划切片 |
|------|-----|--------------|----------|
| 讨论 / 续写 / 整章 | ✅ | ✅ | — |
| QuickStart | ✅ | ✅ | — |
| 一键收录 | ✅ | ✅（简） | P0-B 增强 |
| 收录状态增量 | ✅ | 关 | P0-A |
| 段落多选 / 目标 Sheet | ✅ | 领域半成品 | P0-B |
| 中断可收录 | ✅ | ❌ | P0-B |
| 导入导出 UI | ✅ | 无入口 | P0-C |
| 分支 Fork 等 UI | ✅ | 无入口 | P0-C |
| regenerate + replace | ✅ | ❌ | P1-A |
| 版本历史 | ✅ | ❌ | P1-B |
| 气泡撤销收录 | ✅ | 仅设置页 | P1-B |
| 注入可见面板 | ✅ | ❌ | P1-C |
| 讨论归档 / decisionLog | ✅ MVP | ❌ | P1-C |
| 一致性审计 | ✅ | ❌ | P2-A |
| 批量润色 | ✅ | ❌ | P2-B |
| 长流式手感 | ✅ 多轮 | 基础 | P2-C |
| keep-both 导入 | ✅ | 半成品 | P2-D |
| 完整 polish 账本 | ✅ | 简化 | P2-D |
| multi-chunk manual sync | ✅ | 字段在 | P2-D |

---

## 13. 第一步（Accepted 后立刻做）

1. 确认第 9 节四个产品默认。  
2. 开分支（例：`feat/novel-android-parity-m1`）。  
3. 实施 **P0-B → P0-A → P0-C**。  
4. M1 退出时更新 `NOVEL_ANDROID_PROJECT_STATE.md` 并装 graphite 包手测第 8 节 M1 清单。
