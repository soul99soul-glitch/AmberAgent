# P0′ 三件套：设定可改 + 人物分条 + 状态同步模型

> Status: **COMPLETE (S1–S3 reviewed & fixed)**  
> Date: 2026-07-31  
> Parent: `docs/plans/2026-07-31-novel-android-ios-gap-audit.md` §1.1–1.3  
> Related: `docs/plans/2026-07-30-novel-android-ios-parity-plan.md`（M1 已完成）  
> Workflow: **写方案 → 按阶段推进 → 每阶段子代理审核 → 修完再进下一阶段**

---

## 1. 目标一句话

把用户每天都会踩到的三个 iOS 差距补成 **可点、可回滚、可测** 的闭环：

1. **设定资料可编辑 / 删除 / 新建**（不再只读）
2. **QuickStart 人物一人一条**（经历匹配能对上标题）
3. **状态同步模型独立配置**（收录/手动同步不再被迫用写作模型）

完成标准：每条路径有入口 + 领域闭环 + 测试或手测步骤 + schema 向后兼容（旧文档无新字段 = 旧行为）。

---

## 2. 阶段总览

| 阶段 | 名称 | 用户价值 | 依赖 | 预估 | 审核重点 |
|------|------|----------|------|------|----------|
| **S1** | 设定资料 CRUD UI + deleteMaterial 领域 | 设定「能改」——最高频抱怨 | 无 | 中 | 编辑/新建/删除闭环、busy 防双提交、删除确认、已删不展示 |
| **S2** | QuickStart 人物数组 v3 | 一人一条提案；经历匹配可用 | 无（与 S1 独立） | 中 | 解码兼容 object/array、多条 proposal、旧 JSON 不崩、测试锁死 |
| **S3** | `stateSyncModelPolicy` + 双模型 UI + resolve | 同步与写作可拆模型 | 无硬依赖 S1/S2 | 中高 | 字段缺省回落、序列化互通、resolve 路径、设置 UI 双卡 |

执行顺序：**S1 → 审核修 → S2 → 审核修 → S3 → 审核修 → 更新状态文档**。  
S1 优先：领域大半已有（`reviseMaterial`），只差 UI + delete；用户感知最强。

```text
S1 ──审核──> S2 ──审核──> S3 ──审核──> docs
```

---

## 3. 现状证据（实现前快照）

| 项 | 证据 | 缺口 |
|----|------|------|
| 编辑/新建资料 | `NovelIntent.ReviseMaterial` + `NovelReducer.reviseMaterial` + VM `reviseMaterial` | **UI 未接**；`LivingDetailPane` 只读 |
| 删除资料 | `NovelOperationKind.DeleteMaterial` 枚举存在 | **无 Intent / Reducer / VM / UI** |
| QuickStart 人物 | `NovelQuickStartSuggestionV1` 单对象；prompt 写「可多人写在 content」 | 恰好 1 条 character proposal |
| 状态同步模型 | `NovelProjectRecord.modelPolicy` 仅一个；sync/collect 用 `document.project.modelPolicy` | **无 `stateSyncModelPolicy`**；设置页仅「写作模型」 |

---

## 4. S1 — 设定资料编辑 / 删除 / 新建

### 4.1 范围

| 做 | 不做（本阶段） |
|----|----------------|
| 详情页编辑 title/content 并保存 | 分支 material override |
| 删除资料（软删 `isDeleted=true`） | 自定义 kind 完整分类管理器 |
| 新建资料（`materialId=null` → revise 创建路径） | 资料版本历史浏览 |
| 列表隐藏已删；详情删除确认 | 注入模式 / tags 高级编辑 |

### 4.2 文件触点

| 层 | 文件 | 变更 |
|----|------|------|
| Domain | `NovelCommands.kt` | `NovelDeleteMaterialCommand` |
| Domain | `NovelReducer.kt` | `deleteMaterial(...)` 软删 + op 记录 |
| Intent | `NovelCreation.kt` | `NovelIntent.DeleteMaterial` |
| Runtime | `DefaultNovelCreation.kt` | 分发 delete |
| Outcome | 现有 outcome 或新增 `MaterialDeleted`（与 iOS/op 对齐，优先复用已有序列化形状） | 见实现时 grep |
| VM | `NovelWorkspaceViewModel.kt` | `deleteMaterial`；`reviseMaterial` 失败/成功 status |
| UI | `NovelWorkspacePage.kt` `LivingDetailPane` + 设定列表 | 编辑 sheet、新建入口、删除确认 |
| Test | `NovelReducerRepositoryTest` 或新测 | revise 更新、create、delete 后不可再 revise、列表过滤 |

### 4.3 交互稿（最小）

```text
设定 Tab
  ├─ [+ 新建] → Sheet：kind 选择（角色/世界观/大纲/写作要求/自定义）+ title + content → 保存
  └─ 条目 → 详情
        ├─ 只读展示 + [编辑] → 同 Sheet 预填 → 保存 → reviseMaterial
        └─ [删除] → 确认对话框 → deleteMaterial → 回列表
```

样式：复用 `NovelControl` / `NovelQuietButton` / 现有 dialog 模式；busy 时禁用保存/删除。

### 4.4 领域语义

- **Create**：`materialId == null` → 新 ID，`reviseMaterial` 已有路径。  
- **Update**：已有 material，kind 不可变；title/content 新 revision。  
- **Delete**：`isDeleted = true`；已删禁止 revise；列表/详情过滤 `!isDeleted`。  
- **幂等**：op 带 `payloadSHA256` + `replayIfPresent`（与现有 revise 一致）。

### 4.5 验收

| # | 验收项 |
|---|--------|
| S1-1 | 打开任意已有角色/世界观详情 → 编辑 → 保存 → 详情与列表标题/正文更新 |
| S1-2 | 新建「角色」→ 列表出现 → 可再编辑 |
| S1-3 | 删除 → 确认后列表消失；刷新/重进项目仍不出现 |
| S1-4 | 生成中/busy 时按钮不可重复提交 |
| S1-5 | 单元测试：delete 后 revise 抛错；create 产生 material |

### 4.6 风险

| 风险 | 缓解 |
|------|------|
| 删除后提案/事件仍引用 materialId | 软删保留 ID；UI 不展示；不级联硬删 |
| 自定义 kind 文案 | Custom 用用户输入 kind 名或固定「自定义」 |
| 双提交 | busy + 保存后关 sheet 仅在成功时 |

### 4.7 审核清单（子代理）

- [ ] Intent → Reducer → Repository 写回完整  
- [ ] 失败时 sheet 不丢用户输入（或明确提示）  
- [ ] 已删资料不在 Living 列表  
- [ ] 无 crash：空 title、空白 content 校验  
- [ ] 与 iOS 软删语义一致（`isDeleted`）

---

## 5. S2 — QuickStart 人物数组（v3 契约）

### 5.1 范围

| 做 | 不做 |
|----|------|
| Prompt 要求 `characters: [{title,content},…]` | 改 UI 信息架构为 iOS 两 Tab |
| 解码兼容 **array 与旧 object** | 重写全部 prompt 目录版本号体系（只升 QuickStart） |
| 每个 character 一条 `SettingProposal` | 经历匹配算法大改（只保证标题可对） |
| Markdown 摘要展示多人 | QuickStart 后自动批量 accept |

### 5.2 文件触点

| 层 | 文件 | 变更 |
|----|------|------|
| Structured | `NovelStructuredOutput.kt` | `characters` 解码：JsonArray → List；JsonObject → 单元素 list |
| Prompt | `NovelPromptCatalog.kt` | 契约改为数组；version bump（如 `v3`） |
| Generation | `NovelGenerationReducer.kt` | `toQuickStartMarkdown` + proposal 展开：characters 循环多条 |
| Test | `NovelGenerationLifecycleTest` + decoder 单测 | 多 character proposals；object 回落 1 条 |

### 5.3 解码策略

```text
characters:
  - array  → map { title, content }，过滤空
  - object → listOf(single)  // 旧模型/旧包兼容
  - 空数组 → 解码失败或至少 1 条非空（与现 require 一致：至少一个有效人物）
```

`NovelQuickStartSuggestionsV1.characters` 类型改为 `List<NovelQuickStartSuggestionV1>`（或保留兼容包装类型 + 自定义 serializer）。

### 5.4 验收

| # | 验收项 |
|---|--------|
| S2-1 | 模型返回数组 → N 条 character proposal |
| S2-2 | 模型返回单对象 → 1 条 character proposal（兼容） |
| S2-3 | Markdown 摘要列出每人标题 |
| S2-4 | 测试：lifecycle 不再 assert 恰好 1 条 character（改为 ≥1 且 array fixture 多条） |

### 5.5 风险

| 风险 | 缓解 |
|------|------|
| 旧测试锁死 1 条 | 改断言为列表语义 |
| 模型仍吐 object | 兼容解码 |
| schemaVersion 仍 1 | 保持；靠字段形状兼容，不必升 schemaVersion |

### 5.6 审核清单

- [ ] 严格/宽松解码边界清晰  
- [ ] proposal origin 仍带 QuickStart + Character kind  
- [ ] 无回归：world / outline / writingRequirements 仍单条  
- [ ] prompt 文案明确「每人一条，不要合写」

---

## 6. S3 — `stateSyncModelPolicy` + 双模型设置

### 6.1 范围

| 做 | 不做 |
|----|------|
| `NovelProjectRecord.stateSyncModelPolicy: NovelProjectModelPolicy?`（缺省 null） | 全局 `NovelCreationModelPreferences` 偏好系统 |
| 设置页：「写作模型」+「状态同步模型」 | 按 purpose 的完整 iOS preference store |
| resolve：sync/collect state-delta 用 stateSync ?? modelPolicy | multi-chunk ledger UI |
| 序列化：缺字段 = null = 回落写作模型 | 迁移旧包强制写死字段 |

### 6.2 文件触点

| 层 | 文件 | 变更 |
|----|------|------|
| Model | `NovelRecords.kt` | 可选字段 `stateSyncModelPolicy` |
| Domain | `NovelReducer` / commands | `SetModelPolicy` 扩展 purpose，或新增 `SetStateSyncModelPolicy` |
| Intent | `NovelCreation.kt` | purpose 或独立 intent |
| Runtime | `DefaultNovelCreation.kt` | collect/sync 的 `resolveModel` 用 stateSync 策略 |
| Encoding | `NovelDefaultEncodingShapes` / codec | 缺省兼容 |
| Settings UI | `NovelSettingsPage` + VM | 双模型选择器 |
| Test | codec + resolve 单测 | null 回落；fixed 独立 |

### 6.3 Resolve 规则

```text
purpose == creation | polish | prose | discussion | quickStart
  → project.modelPolicy

purpose == stateSync | stateDelta | manualSync
  → project.stateSyncModelPolicy ?: project.modelPolicy
```

与 iOS `setModelPolicy(purpose: .creation | .stateSync)` 对齐意图。

### 6.4 验收

| # | 验收项 |
|---|--------|
| S3-1 | 旧 `.ambernovel` 无字段可解码；行为 = 仅用写作模型 |
| S3-2 | 设置独立同步模型后，collect/sync 解析到该模型（可测 resolve 纯函数） |
| S3-3 | 清空/跟随全局时回落逻辑正确 |
| S3-4 | 设置页两行清晰：写作 vs 状态同步 |

### 6.5 风险

| 风险 | 缓解 |
|------|------|
| Swift 编码 shape 不一致 | 对照 iOS 字段名 `stateSyncModelPolicy`；跑 codec / Swift 兼容测 |
| SetModelPolicy 只写 creation | 明确 purpose，避免覆盖错字段 |
| UI 与 Fixed/Global 语义 | 复用现有写作模型选择组件 |

### 6.6 审核清单

- [ ] 所有 state-delta / manualSync 入口都走 resolve  
- [ ] 创建项目默认 null（不复制错误模型）  
- [ ] 导入 iOS 包若含字段不丢  
- [ ] 无「设置了但还是用写作模型」的静默路径 |

---

## 7. 阶段门禁流程（强制）

每阶段：

```text
1. 实现（本代理）
2. 编译相关模块 + 相关单元测试
3. spawn 子代理审核，重点：
   - 闭环（入口→领域→持久化→UI 反馈）
   - 调用路径（有无假按钮 / 假默认）
   - 竞态 / 双提交 / busy
   - 失败可理解
   - 与本阶段验收表对照
4. 按审核结果修复
5. 再跑测试 → 勾验收 → 更新本文件 Status
6. 进入下一阶段
```

子代理 prompt 模板要点：只审本阶段 diff + 相关文件；输出 P0/P1/P2 问题；区分 bug vs 建议。

---

## 8. 状态板

| 阶段 | 实现 | 测试 | 审核 | 修复 | 完成 |
|------|------|------|------|------|------|
| S1 设定 CRUD | ☑ | ☑ | ☑ | ☑ | ☑ |
| S2 QS 人物数组 | ☑ | ☑ | ☑ | ☑ | ☑ |
| S3 双模型 | ☑ | ☑ | ☑ | ☑ | ☑ |
| 文档回写 | ☑ | — | — | — | ☑ |

---

## 9. 明确不在 P0′

- regenerate / replaceChapter  
- 版本历史 UI  
- 讨论归档 / decisionLog  
- ContinuityAudit / 批量润色  
- keep-both 导入  
- 信息架构改成 iOS 双 Tab  

以上见 gap-audit P1/P2。

---

## 10. 开工检查

- [x] 计划数表写完（本文）  
- [ ] S1 实现  
- [ ] S1 子代理审核 + 修  
- [ ] S2 …  
- [ ] S3 …  
- [ ] `docs/NOVEL_ANDROID_PROJECT_STATE.md` + gap-audit 状态回写  
