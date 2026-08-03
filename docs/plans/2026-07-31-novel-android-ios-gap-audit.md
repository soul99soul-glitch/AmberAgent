# Android 小说创作 × iOS 全面差距盘查

> Date: 2026-07-31  
> Method: 对照 `amberagent-ios/iosApp/iosApp/NovelCreation/**` 与 Android  
> `feature/novel/**` + `app/.../ui/pages/novel/**` 的真实代码，不以旧 handoff 为准。  
> Related: `docs/plans/2026-07-30-novel-android-ios-parity-plan.md`（M1 已落地部分）

## 一句话

Android 已有「能写、能收、能读、能同步剧情、能导入导出」的主路径，  
但相对 **当前 iOS 主线**，仍是 **半套领域 + 薄 UI**：很多 iOS 能力要么字段/op kind 空转，要么根本没有 schema。

图例：

| 标记 | 含义 |
|------|------|
| ✅ | Android 用户路径可用且与 iOS 意图对齐 |
| 🟡 | 有半截：领域/VM 有、UI 无，或简化版 |
| ❌ | Android 基本没有（或不可用） |
| ⚠️ | 有但行为/契约落后 iOS（会伤害体验或互通） |

---

## 0. 已对齐 / 基本可用（避免重复抱怨）

| 能力 | 状态 | 备注 |
|------|------|------|
| 项目 CRUD + degraded restore | ✅ | |
| 讨论 / 续写 / 整章生成 | ✅ | RunKind 无 regenerate |
| QuickStart 四类建议确认 | ⚠️ | 人物仍是单条总览（见下） |
| 收录 Sheet（目标 + 段落） | ✅ | M1 P0-B |
| 中断稿可收录 | ✅ | M1 |
| 收录后 state-delta（事件/摘要/建议） | ⚠️ | 有链路；模型未拆 stateSync |
| 手动改章 → needsSync → 同步 | ✅ | 用同一写作模型 |
| 全屏连读 + 编辑本章 + 单章润色 | ⚠️ | 无版本历史/重写/废弃/批量 |
| 设定浏览 + 建议确认 + 经历匹配 | ⚠️ | 只读、人物合写、无删改资料 |
| 导入/导出包 + Markdown | ✅ | M1 P0-C；无 keep-both |
| 分支 Fork / 重命名 / 设主 / 撤销收录 | ✅ | 设置页；工作区 header 仍不能点切分支 |
| 后台中断 + recovery sidecar | ✅ | 简化版 |
| `.ambernovel` codec 基础互通 | ⚠️ | 缺 iOS 新字段时会丢语义 |

---

## 1. 用户每天都会碰到的缺口（高感知）

### 1.1 设定资料：不能改、不能删、不能新建  ✅（P0′ S1，2026-07-31）

| | iOS | Android |
|--|-----|---------|
| 编辑角色/世界观/大纲/写作要求 | ✅ MaterialEditor | ✅ Living 编辑 Sheet → `reviseMaterial` |
| 删除资料 | ✅ | ✅ 软删 `deleteMaterial` + 确认 |
| 新建自定义资料 | ✅ custom kind | ✅ 角色/世界观/自定义 + 空总纲/写作要求 |
| 分支资料覆盖（override） | ✅ | 🟡 outcome/op 有，无 intent/UI |

### 1.2 QuickStart 人物：一人一条 vs 合写一条  ✅（P0′ S2，2026-07-31）

| | iOS | Android |
|--|-----|---------|
| Prompt | `novel.quick-start.v3`，`characters: [{title, content}…]` 每人一条 | ✅ 同 v3 契约 |
| 解码 | 兼容 object / array | ✅ array + 旧 object |
| 落库 | 多条 character material | ✅ 每人一条 Character proposal |

### 1.3 剧情同步模型独立配置  ✅（P0′ S3，2026-07-31）

| | iOS | Android |
|--|-----|---------|
| Schema | `stateSyncModelPolicy` | ✅ 可选字段 |
| 设置 UI | 创作模型 + 状态同步模型 | ✅ 双模型选择器 |
| Resolve | `purpose: .creation / .stateSync` | ✅ collect/sync 用 `effectiveStateSyncModelPolicy()` |
| 全局默认偏好 | `NovelCreationModelPreferences` | 无（null 回落**写作模型**，与 iOS null→Global 略不同） |

### 1.4 阅读 / 章节管理薄  ⚠️（P1-A/B 部分收口 2026-07-31）

| | iOS | Android |
|--|-----|---------|
| 版本历史 + 恢复版本 | ✅ | ✅（同 fact 链 restore + Sheet） |
| 重写本章 + replaceChapter 收录 | ✅ regenerate | ✅ |
| 废弃/恢复章 | ✅ discardedAt | ✅ discardedAt + 目录废弃（恢复经 intent） |
| 批量润色 | ✅ | ✅ 串行批量 MVP |
| 编辑禁用原因文案 | ✅ | 弱 |
| 查找替换 | ✅（编辑器） | ❌ 明确延期 |

### 1.5 创作会话能力  ⚠️/❌

| | iOS | Android |
|--|-----|---------|
| 讨论归档 + decisionLog | ✅ | ✅ MVP（手动确认；无 LLM 蒸馏） |
| 注入上下文只读面板 | ✅ | ✅ 最近 injection receipt |
| 气泡就近撤销收录 | ✅ | ✅ head 气泡 + 设置第二入口 |
| 中断可收 | ✅ | ✅ |
| 替换章后状态回撤语义 | 部分在做 | 🟡 delta 输入标明替换；无完整回撤机 |
| 流式长列表/完成闪烁 | 多轮硬化 | 🟡 pin-bottom + 完成静窗 |

### 1.6 长篇工具

| | iOS | Android |
|--|-----|---------|
| 一致性审计 ContinuityAudit | ✅ | ✅ 单次整稿 MVP |
| 批量整章润色 | ✅ | ✅ 串行 MVP |
| StructuredModelExecutor 任务分发 | ✅ | 🟡 内嵌路径 + continuity 专用 |

---

## 2. 领域 / Schema 差距（互通与正确性）

### 2.1 项目级字段

| 字段/概念 | iOS | Android |
|-----------|-----|---------|
| `modelPolicy` | ✅ | ✅ |
| `stateSyncModelPolicy` | ✅ | ✅ |
| `decisionLog` material | ✅ | ✅ |
| `discussionArchive` checkpoint | ✅ | ✅ |
| chapter `discardedAt` | ✅ | ✅ |
| session `archiveCursor` + 归档记录 | ✅ | ✅ |
| `replaceChapter` collection target | ✅ | ✅ |
| `regenerate` run kind | ✅ | ✅ |
| polish transactions/attempts/assessments **完整机** | ✅ | 🟡 简化 adopt（延期完整账本） |
| multi-chunk manual sync progress ledger | ✅ | 🟡 单次同步（延期） |
| keep-both import + ID remap | ✅ | ✅ projectId remap MVP |

### 2.2 Operation / Intent：iOS 有、Android Intent 层没有或未接通

Android `NovelIntent` **没有** 或 **UI 未接** 的（对照 iOS `NovelAction`）：

| iOS Action | Android |
|------------|---------|
| deleteMaterial | op kind 有，Intent/UI 无 |
| setBranchMaterialOverride | op/outcome 有，Intent/UI 无 |
| deleteBranch | op 有，Intent/UI 无 |
| archiveDiscussion | 无 |
| cloneCandidate | op 有，Intent/UI 无 |
| abandonPolishTransaction | op 有，完整生命周期弱 |
| restoreChapterVersion | op 有，UI 无 |
| discardChapter / restoreChapter | 无 |
| retryPending | 部分（RetryTerminal） |
| setModelPolicy(purpose:) | 仅 creation，无 purpose |

### 2.3 Prompt 目录版本

| Kind | iOS（现行） | Android |
|------|-------------|---------|
| quickStart | v3（人数组） | 标 v2，**内容仍像 v1 单对象** |
| discussion | 至 v3 | v1 |
| prose whole chapter | 至 v2 | v1 |
| wholeChapterRegeneration | ✅ | ❌ |
| discussionArchive | ✅ | ❌ |
| continuityAudit | ✅ | ❌ |
| stateDelta / polishDrift | ✅ | ✅（简化消费） |

---

## 3. 「有半截」清单（最容易骗人）

这些最容易让人以为「做了 / 没做」说不清：

| 项 | 有什么 | 缺什么 |
|----|--------|--------|
| 设定编辑 | `reviseMaterial` VM | 详情页编辑 UI |
| 删除资料 / 删分支 / 分支覆盖 | op kind / outcome | Intent + UI |
| 版本恢复 | op kind | reducer 完整路径 + 历史 UI |
| 润色事务账本 | document 数组 | iOS 级 reserve/attempt/assess/abandon |
| keep-both | import policy case | ID remapping + 对话框选项 |
| state-delta | 收录时会跑 | 独立 stateSync 模型 + 进度 banner 可取消 |
| 角色经历 | matcher + 列表摘要 | 每人一条档案才能好用 |
| 导入导出 / 分支 | M1 已接 UI | 工作区顶栏点切分支仍弱 |

---

## 4. 信息架构差异（产品形态）

| | iOS | Android |
|--|-----|---------|
| 工作区 | **创作 \| 资料**（资料内：正文/角色/世界观/剧情/更多） | **创作 \| 正文 \| 设定** + 独立设置页 |
| 设置入口 | 资料→更多 / 项目设置详情 | 顶栏齿轮 |
| 分支切换 | 顶栏点项目·分支 | 主要在设置页列表点选 |

不一定要抄两 Tab，但 **「设定=活百科且可改」** 的心智 iOS 已成立，Android 仍像只读附件。

---

## 5. 按用户价值重排（建议下一阶段）

在 M1（P0 收录/同步/导入导出）之上：

### P0′（设定闭环 — 你已连续踩中）

1. **设定可编辑 / 可删 / 可新建自定义**（接 revise + delete + UI）  
2. **QuickStart 人物数组**（对齐 iOS v3，兼容旧单对象）  
3. **`stateSyncModelPolicy` 字段 + 设置双模型 + 同步路径 resolve**  

### P1（改剧情与可回退）

4. regenerate + replaceChapter  
5. 版本历史 + 气泡撤销收录  
6. 讨论归档 + decisionLog + 注入面板  

### P2（长篇工具与硬核）

7. 一致性审计  
8. 批量润色  
9. 完整 polish 事务 / multi-chunk sync / keep-both  
10. 废弃章、分支覆盖、删分支  
11. 流式手感  

---

## 6. 诚实边界

- **不要**声称 Android 已与 iOS 1:1。  
- **可以**声称：主写作闭环可用；跨端包在 V1 公共子集上可互通；iOS 独有字段/行为在 Android 会降级或丢失。  
- 真机仍需：真实 provider 下 state-delta 质量、SAF 互通、长章流式手感。

---

## 7. 建议验收句（给自己用）

「设定像资料库」：能分人建档、能改能删、同步模型可单独指定。  
「正文像小说」：能读能改能重写能回版本。  
「讨论不丢」：能归档决定、能看见模型吃了什么。  
「长篇可治」：能审计矛盾、能批量润色。

当前 Android 大约在 **第一句的 30% + 第二句的 50% + 后两句接近 0**。
