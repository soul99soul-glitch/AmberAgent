# P1：整章重写 + 版本历史 + 气泡撤销

> Status: **COMPLETE (P1-A/B reviewed & fixed)**  
> Date: 2026-07-31  
> Parent: `docs/plans/2026-07-30-novel-android-ios-parity-plan.md` P1-A/B  
> Gap: `docs/plans/2026-07-31-novel-android-ios-gap-audit.md` §1.4–1.5  
> Workflow: 阶段实现 → 子代理审核 → 修完再进下一阶段

---

## 1. 目标

让用户能 **安全地改剧情**：

1. **P1-A** 对某一章「重写本章」，收录时 **替换** 该章（不增加章数），旧版本留在数据层。  
2. **P1-B** 浏览一章版本历史并恢复；对话里对 head 收录气泡一键撤销。

不做本阶段：讨论归档、decisionLog、废弃章、批量润色、注入面板。

---

## 2. 阶段总览

| 阶段 | 名称 | 用户价值 | 依赖 | 审核重点 |
|------|------|----------|------|----------|
| **S1 / P1-A** | regenerate + replaceChapter | 重写不满意的一章 | 无 | kind 守卫、replace 约束、UI 默认目标、润色路径不混 |
| **S2 / P1-B** | restore + bubble undo | 后悔可回退 | 与 S1 独立但受益于多版本 | restore 规则、undo head 仅 head、忙碌/只读 |

```text
S1 ──审核──> S2 ──审核──> docs
```

---

## 3. S1 / P1-A — 整章重写 + replace 收录

### 3.1 范围

| 做 | 不做 |
|----|------|
| `NovelRunKind.Regenerate` + prompt `wholeChapterRegeneration` | 状态「回撤」完整机（尽量在 delta 输入标明替换） |
| `NovelCollectionTarget.ReplaceChapter` | discardedAt 废弃章 |
| 阅读/目录「重写本章」 | 讨论归档联动 |
| Collect Sheet 默认 replace（有 regeneration 目标时） | regenerate 走 polish drift |

### 3.2 关键证据

- Collection target 仅 `AppendToChapter` / `CreateNextChapter`  
- RunKind 无 `Regenerate`  
- `sourceChapterVersionID` 已在 candidate/run 上存在（polish 复用）  
- UI 仅 polishChapter，无 regenerate  

### 3.3 文件触点

| 层 | 文件 |
|----|------|
| Enums | `NovelEnums.kt` RunKind + CollectionTarget |
| Prompt | `NovelPromptKind` / Versions / Catalog |
| Generation | `NovelGenerationReducer`, `DefaultNovelCreation.reserveRun` |
| Injection | `NovelInjectionPlanner`（注入被重写章全文） |
| Collection | `NovelCollectionReducer` replace 分支 + 守卫 |
| VM/UI | `NovelWorkspaceViewModel`, collect sheet, TOC/reader |
| Test | lifecycle regenerate、replace collect、错误目标 |

### 3.4 领域语义

**Regenerate**

- 必须 `sourceChapterVersionId`  
- 产出 **Prose** candidate（kind=Prose），带 source 版本  
- 不走 polish drift；可中断 partial 收录  
- Prompt：允许改剧情事实，整章重写  

**ReplaceChapter**

- 替换 working selection 上该章 head 版本内容为 selectedText  
- 新 version kind=Collected，`sourceChapterVersionID` = 旧 head  
- **守卫**：candidate.sourceChapterVersionID 必须属于该 chapterID（防绕过 UI 乱替换）  
- 章数不变  

### 3.5 UI

```text
正文目录 / 全屏阅读
  └─ ⋯ 或操作：编辑 | 润色 | 重写本章
       重写 → 切创作 Tab，start regenerate，状态文案「重写本章…」

收录 Sheet（candidate 带 source + 可映射 chapter）
  └─ 默认：替换「第 N 章 · 标题」
  └─ 仍可选：新开一章（逃生舱，可选）
  └─ 文案：原文版本保留在历史（数据层）
```

### 3.6 验收

| # | 项 |
|---|-----|
| A-1 | 重写第 N 章 → 候选可收 → 替换后该章正文为新文，章数不 +1 |
| A-2 | chapterVersions 多一条旧链；head 指向新 version |
| A-3 | 对未重写目标的 replace 被拒 |
| A-4 | polish / 普通 prose 收录路径无回归 |
| A-5 | busy / needsSync / 只读有禁用或错误原因 |

### 3.7 审核清单

- [ ] regenerate 与 polish 不混路径  
- [ ] replace 守卫完整  
- [ ] Sheet 默认正确  
- [ ] interrupt partial 可收且仍可 replace（若 source 在）  
- [ ] 序列化 Swift 兼容 `replaceChapter` / `regenerate`

---

## 4. S2 / P1-B — 版本历史 + 气泡撤销

### 4.1 范围

| 做 | 不做 |
|----|------|
| `restoreChapterVersion` Intent + reducer（若缺） | 真删历史版本 |
| 版本历史 Sheet：列表 / 预览 / 恢复 | 跨 factCompatibility 静默恢复（应对齐 iOS：不兼容则拒或当 manual） |
| 已收录且 isHead 气泡「撤销收录」→ undoHead | 非 head 假撤销 |

### 4.2 验收

| # | 项 |
|---|-----|
| B-1 | 多版本可浏览、恢复后 head 更新 |
| B-2 | 撤销收录后正文回退、候选状态正确 |
| B-3 | 非 head / busy / 只读不可撤销或给出原因 |

---

## 5. 状态板

| 阶段 | 实现 | 测试 | 审核 | 修复 | 完成 |
|------|------|------|------|------|------|
| S1 P1-A 重写+replace | ☑ | ☑ | ☑ | ☑ | ☑ |
| S2 P1-B 历史+撤销 | ☑ | ☑ | ☑ | ☑ | ☑ |
| 文档 | ☑ | — | — | — | ☑ |
