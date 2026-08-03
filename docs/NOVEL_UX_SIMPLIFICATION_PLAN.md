# 小说创作 UX 简化与信息架构重组计划

> Status: Implemented, post-review corrections verified
> Date: 2026-07-12
> 关系：**补充** `docs/NOVEL_CREATION_SPEC.md`（产品契约不变）与 `docs/NOVEL_CREATION_IMPLEMENTATION_PLAN.md`（Phase A-F 已完成的实现）。主体实施只做 UI/呈现层重组与默认值修复；完成后的独立 review 证明聚合人物输出无法满足独立角色页，因此仅升级 Quick Start 模型输出为向后兼容的 v3/v2 角色数组。`NovelProjectDocumentV1` 与项目包 schema 未改。

> 当前实现校正（2026-07-31）：后续迭代已把工作区演进为「创作 / 正文 / 设定」三个一级入口，并加入独立的「创作模型 / 剧情同步模型」。下文“两 Tab”与单模型描述保留为本轮历史设计目标，不再代表当前运行时事实；当前契约以 `docs/NOVEL_CREATION_SPEC.md` 和 `docs/PROJECT_STATE.md` 为准。

## 背景：真实用户反馈（原话要点）

1. 「我的核心诉求就是两个：跟 Agent 讨论和生成小说内容；像查项目资料一样查到角色、世界观、剧情走向，而且它们随收录进正文的内容不断更新。这两个部分要更易用、更明显。」
2. 「第一次进来找不见设置在哪——比如想专门给小说换 Provider/模型，找不到。」
3. 「创作、讨论界面里按钮特别多。」
4. 「片段模式和整章模式，还有一个像魔法棒的按钮，摁不了，我也不知道是干什么的。」（魔法棒 = 整章润色按钮，无章节时被静默禁用，无任何解释）
5. 「正文偏向阅读模式，就像看小说来看。」
6. 角色经历与档案的关联：第一版接受**按名字自动关联**（用户已确认）。

## 目标一句话

把功能收敛成用户心智里的两件事——**「创作」（聊天生成）和「资料」（活的项目百科）**——把工程概念（revision、head、检查点编号、token、事务）和低频设置全部移出主视野，同时**一条正确性边界都不放松**。

## 红线（不可违反）

- 不改 `NovelReducer`、fact/polish/collect 事务、检查点与分支语义、`NovelProjectDocumentV1`/项目包 schema、注入计划器。
- 不改普通 Chat / Council / Memory 任何文件；继续复用 `ChatUserBubble`、`ChatAssistantMarkdownView`、Composer 全套、`ComposerModelSheet`。
- 候选 ≠ 正文、收录原子提交、`needsSync` 阻正式生成、润色漂移 fail-closed、不可变历史——全部保持。
- 遵守根 `AGENTS.md` 与 `iosApp/AGENTS.md`：脏工作区最小 hunk、新文件跑 `xcodegen generate` 并核对 stable/Experimental/test 三个 target membership、不 commit/push、设置项必须"控件+持久化+运行时消费"三点闭环。
- 禁用按钮从此必须**要么隐藏、要么给出可见原因**，不允许静默变灰。

## 目标信息架构

```
Session 首页「小说创作」→ 项目列表（不变）→ 项目工作区：

┌────────────────────────────────────────────┐
│ ← 项目名 · 分支名（点击=切换分支）          │  ← 齿轮按钮删除
│ [ 创作 | 资料 ]                             │  ← 三 Tab 收敛为两 Tab
└────────────────────────────────────────────┘

创作 = 现有 NovelSessionView，composer 简化（见 S4）

资料 = 新容器（子分类横向切换）：
  正文   章节列表（书目式）→ 阅读视图（沉浸排版；"⋯"菜单：
         版本历史 / 编辑本章 / 整章润色）
  角色   角色列表 → 单页 = 档案(可编辑) + 经历时间线(按名字关联，
         随收录生长) + 本角色待确认建议
  世界观 世界观资料 + 本类待确认建议
  剧情   总剧情大纲 + 当前分支状态摘要 + 分支走向 + 事件时间线
  更多   写作要求 / 自定义资料 / 项目模型与润色偏好 / 分支管理 /
         注入规则预览 / 导入导出 / 项目重命名
```

创作页 composer 最终形态（回应"按钮特别多"）：

```
[ 讨论 | 续写 | 整章 ]   (⋯)          ← 一个三段控件 + 一个溢出菜单
[ 输入框……………………………… ] (发送/停止)
```

- 三段控件合并现有"模式 segmented + 粒度 Menu"两个控件：讨论=discussPlan；续写=writeProse+continuation；整章=writeProse+wholeChapter。选中 续写/整章 时照旧写回 `project.lastGenerationGranularity`（语义与 SPEC 完全等价，只是控件合一）。
- (⋯) 菜单内含带文字的两项：「本次上下文…」「项目模型…」。
- 「整章润色」魔法棒从 composer 移除，入口迁至阅读视图（见 S3）。

---

## 切口划分（每片独立交付、独立回归，按顺序执行）

### S1 收录默认值修复（P0，最先做，可独立发布）

**问题**：第二章起收录 Sheet 默认"追加当前章"，整章候选会被并进上一章。
证据：`NovelSessionSheets.swift:54` `self._targetChoice = State(initialValue: chapters.isEmpty ? .createNext : .appendCurrent)`；Sheet init（`:40-56`）不接收粒度。

**改动**：
1. `NovelCollectCandidateSheet` 增加 init 参数 `suggestedTarget: TargetChoice`（或直接传 `NovelGenerationGranularity?`）。
2. 调用点 `NovelProjectWorkspaceView.swift:376-395`：根据候选的生成粒度计算默认值——整章→`.createNext`，片段→`.appendCurrent`。粒度回溯优先走真实记录（候选 `sourceMessageID` → 对应消息/run 记录；先核实 `NovelDomainModels.swift:665` 附近的 `granularity: NovelGenerationGranularity?` 属于哪个 record），无法可靠回溯时兜底 `project.lastGenerationGranularity`（`NovelDomainModels.swift:188`，写回点 `NovelGenerationReducer.swift:125`）。
3. 目标选项文案带章号（见文案表）。

**验收**：
- 已有 N 章 + 整章候选 → Sheet 默认"新开第 N+1 章"；片段候选 → 默认"并入第 N 章"。
- 新增单测覆盖两种初值（放 `NovelSessionViewModelTests` 或新建 Sheet 层测试）。
- `NovelCollectionTests` 全绿（收录事务未被触碰）。

### S2 两 Tab + 资料区骨架 +「更多」设置区（解决"找不到设置"）

**改动**：
1. `NovelProjectWorkspaceView.swift`：
   - `NovelWorkspaceSection` 收敛为 `.creation / .compendium`，segmented 标题「创作 / 资料」（现有定义 `NovelPresentationSupport.swift:3-16`、picker `:174-185`）。
   - 删除齿轮按钮（`:157-165`）与 `NovelProjectToolsSheet`（`:704-889`）；其内容迁往「更多」。
   - header 点击行为不变（打开分支选择 Sheet），无障碍标签与 Sheet 标题统一为「切换分支」（现状不一致：a11y「项目与分支」`:138`，Sheet 标题「项目分支」`:644`）。
2. 新建 `NovelCompendiumView.swift`：资料容器，子分类 正文/角色/世界观/剧情/更多 用横向 chips 或第二层 segmented；各子页第一版直接**挪现有视图**：
   - 正文 → `NovelChapterManagementView`（现藏于齿轮→「正文与版本」，`NovelProjectWorkspaceView.swift:757-762`）。本片先平移，S3 再升级为阅读模式。
   - 世界观 / 角色 → `NovelMaterialsView` 的资料列表按 `NovelMaterialKind` 分流（`.world` / `.character`）。
   - 剧情 → 总纲资料（`.masterOutline`）+ 现有 `NovelBranchesView` 的「当前剧情状态 / 事件记录」两段（`NovelBranchesView.swift:165-224`）平移过来。
   - 更多 → 一个静态 List：项目模型（含「跟随全局模型 / 选择固定模型」，逻辑照搬 `NovelMaterialsView.swift:44-66`）、整章润色偏好、写作要求与自定义资料、分支管理（`NovelBranchesView` 去掉状态/事件段后的剩余部分：分支列表、设主分支、重命名、删除、从检查点 Fork、撤销、分支设定覆盖）、注入规则预览（现「本次上下文预览」`NovelMaterialsView.swift:79-88`，改名见文案表）、导出项目包/正文 Markdown（逻辑照搬 `NovelProjectWorkspaceView.swift:534-567` 与 `exportPackage/exportMarkdown`）、项目重命名。
3. 新文件跑 `xcodegen generate`，核对 PBX membership（stable + Experimental + 不进 test target）。

**验收**：
- 原有全部能力可达性点检清单逐项通过（模型、润色偏好、分支全部操作、覆盖、导入导出、章节、建议确认）。
- 「给小说单独换模型」路径：资料 → 更多 → 项目模型，两击可达且行名可读。
- 域层零 diff；29 个 Novel 测试类基线不变（既有 `NovelSessionReplayTests.swift` 自身编译错误按 PROJECT_STATE 记录单独归因，不算新增红灯）。

### S3 正文阅读模式 + 编辑本章 + 润色入口迁移

**改动**：
1. 新建 `NovelChapterReaderView.swift`：
   - 全屏阅读：章节标题 + `ChatAssistantMarkdownView(markdown: version.content, renderCacheNamespace: "novel:chapter:\(version.id)")`（渲染方式照搬 `NovelChapterViews.swift:281-285`）；底部/滑动到底提供「上一章 / 下一章」。
   - 右上「⋯」菜单：**版本历史**（弹现有 `NovelChapterVersionsSheet`，`NovelChapterViews.swift:238-392`）、**编辑本章**（新增）、**整章润色**（对当前章直接调用现有 `NovelSessionViewModel.startWholeChapterPolish(chapterID:)`，成功后自动切回「创作」Tab 看候选流式生成；needsSync/pending 时菜单项显示禁用原因而不是消失）。
2. 「编辑本章」：TextEditor Sheet（标题/正文），保存调用现成 `NovelCreationViewModel.saveManualRewrite(chapterID:title:content:)`（`NovelCreationViewModel.swift:758-783`）→ 自动进入现有 `needsSync` banner 流程（`NovelSessionView.swift:244-281`）。Sheet 内固定一行说明：「保存后需要同步剧情状态，正式生成前会先提醒你同步。」
3. 章节列表（正文子页）改书目式行：`第 N 章 · 标题 · 字数`，去掉 `kind.displayName` 前缀噪音；删除「创作记录」段里的「当前 head」行（`NovelChapterViews.swift:174-183`）。
4. composer 移除整章润色魔法棒（`NovelSessionView.swift:493-508`）——它的两个问题（无章节时静默禁用、用户不知含义）随入口迁移一并消失。

**验收**：
- 工作区任意位置 ≤2 击进入任意章节全文阅读；上一/下一章切换正常。
- 编辑保存 → 分支「待同步」、正式生成被阻、同步成功生成 manualSync 检查点（`NovelManualEditSyncTests` 域层已覆盖；补一条 ViewModel 接线测试）。
- 阅读视图发起润色 → 创作页出现润色候选气泡；漂移路径文案与现有一致。
- composer 不再有魔法棒；润色偏好仍在 更多。

### S4 composer 简化（解决"按钮特别多"+"片段/整章看不懂"）

**改动**（`NovelSessionView.swift`）：
1. 删除 `modePicker`（`:424-436`）与 `granularityMenu`（`:379-422`），替换为一个三段 segmented：「讨论 / 续写 / 整章」。
   - 绑定：讨论 → `mode = .discussPlan`；续写 → `mode = .writeProse; granularity = .continuation`；整章 → `mode = .writeProse; granularity = .wholeChapter`。
   - 初值：项目 `lastGenerationGranularity` 决定 续写/整章 档位，默认落「整章」；`mode` 初值维持 `.writeProse`（`NovelSessionViewModel.swift:22-23,49,186` 现有逻辑不动，只是控件映射）。
   - 占位符沿用现有三分支文案（`:581-584`）。
2. 删除 shippingbox「本次上下文」按钮（`:463-471`）与 cpu「选择项目模型」按钮（`:479-491`），替换为一个 `Menu`（ellipsis 图标，复用 `ComposerIconButton`），内含带文字的两项：「本次上下文…」（打开现有 `NovelSessionContextSheet`）、「项目模型…」（打开现有 `ComposerModelSheet` 路由）。当 `injectionOverrides != .none` 时 ⋯ 图标用现有 prominent/tint 态提示。
3. `ComposerModelSheet` 打开前的路由处（`NovelProjectWorkspaceView.swift:344-351`）补一项「跟随全局模型」：最小实现为在 sheet 上方 confirmationDialog 二选一（跟随全局 / 选择固定模型），或在 `ComposerModelSheet` 顶部加可选 header 行——二选一，不改普通 Chat 的调用点默认行为。
4. `NovelSessionContextSheet` 里的「输入预算 tokens」Stepper（`NovelSessionSheets.swift:536-538`）折叠进 `DisclosureGroup("高级")`。

**验收**：
- composer 可见控件 = 三段 segmented + ⋯ + 输入框 + 发送，共 4 个。
- 三段控件切换后发送的 `NovelRunRequest.mode/granularity` 与旧控件组合完全一致（补接线测试：三档 → 三种请求参数）。
- 讨论/续写/整章 的粒度记忆行为与现状一致（`lastGenerationGranularity` 写回不变）。

### S5 角色合并页（档案 + 经历）

**改动**：
1. 新建 `NovelCharacterPagesView.swift`（列表 + 详情）：
   - 列表：`.character` 类资料，每行 标题 + 最近一次相关事件时间（无事件则显示"暂无经历"）。
   - 详情：上半=档案（点击进现有 `NovelMaterialEditorSheet`）；下半=「经历」时间线：当前分支 `currentState.eventIDs` 对应事件中 `entityReferences` 含该角色名（去空白、完全匹配优先，其次包含匹配）者，按 `sequence` 排列，渲染 `summary + kind`（事件渲染样式照搬 `NovelBranchesView.swift:211-219`）。页脚固定说明：「经历按名字自动关联，改名后可能需要手动核对。」
   - 详情页内联显示该角色相关待确认建议（origin kind == `.character`）。
2. 匹配逻辑做成纯函数（如 `NovelCharacterEventMatcher`）+ 单测：完全匹配、包含匹配、多角色同事件、空引用。

**验收**：收录一章含角色 X 的正文后，X 的经历时间线新增对应事件；匹配函数单测全绿。

### S6 建议就地化 + 快速开始断桥 + 撤销收录就近化

**改动**：
1. 各资料子页（世界观/角色/剧情）顶部内联本类待确认建议卡（复用 `NovelMaterialsView.swift:92-127` 的卡片与 `NovelProposalAcceptanceSheet`，按 `suggestedKind` 分流）；「资料」Tab 标签带未决数量角标。
2. 快速开始完成的「# 创作建议」消息气泡（内容构造 `NovelGenerationReducer.swift:272-285`；建议生成 `:287-317`）追加动作按钮「查看并确认设定建议」→ 切到 资料 Tab（气泡动作管道现成：`NovelSessionBubble.swift:136-160` actionBar + `NovelSessionRowAction`，新增一个纯 UI 路由 action，不进领域层）。
3. 已收录且仍为分支 head 的候选气泡，动作条追加「撤销收录」：可用性判断照搬 `cloneCollectedProse` 的 head 检查（`NovelSessionPresentation.swift:666-682`），点击弹 confirmationDialog（样式照搬「放弃润色」`NovelSessionView.swift:77-94`），确认后调用现有 `undoBranchHead`（`NovelCreationViewModel.swift:693`）。文案见文案表。分支管理页的撤销按钮保留并同样加确认。
4. Fork 成功后（`selectingBranch` 自动切换，`NovelCreationViewModel.swift:675`）在会话插入一条系统胶囊消息「已切换到分支「XX」」（系统消息渲染现成：`NovelSessionBubble.swift:273-284`）——若插入持久消息触碰领域层，则降级为创作页顶部 3 秒轻提示，不动领域。

**验收**：
- 快速开始 → 气泡按钮 → 落在资料区对应建议卡；全部建议确认/拒绝后角标消失；未确认前项目资料仍为空（边界不变）。主要角色各自生成独立建议，旧版单人物建议仍可读取。
- 撤销收录仅出现在 head 候选气泡；确认后正文与剧情状态一起回退（域层 `NovelBranchLifecycleTests` 已覆盖）；非 head 气泡不出现。

### S7 文案、错误中文化与禁用原因可见

**改动**：
1. `NovelActions.swift:1216-1250` 全部 `NovelError` 用户可见描述中文化（`projectBusy` 现为英文 `"The novel project has an active operation."`，`:1248`）。先 grep 测试目录确认无断言英文原文。
2. 气泡动作按钮禁用且有 blocker 时，动作条下方加一行 caption 显示 `blocker.displayName`（文案已全部存在：`NovelSessionBubble.swift:324-339`；当前只接到 accessibilityHint `:237`）。
3. 落地下方文案表；同步清理内部术语：项目行 `r\(revision)`（`NovelProjectListView.swift:308`）、分支行 `head \(headRevision)`（`NovelBranchesView.swift:272`）、Fork 起点 `"\(kind) · head N+1"`（`NovelBranchesView.swift:399-401`，改为「第X章 收录 · M月D日」，章号由检查点锁定的 chapterVersionID 推导，做成纯函数+单测）。

**验收**：全应用无英文错误弹窗；无 r/head/token 裸露（「高级」折叠区除外）；禁用的收录/采用按钮均有可见原因。

---

## 文案表（当前 → 新）

| 位置 | 当前 | 新 |
|---|---|---|
| 工作区 Tab | 创作 / 设定 / 分支 | 创作 / 资料 |
| 收录 Sheet 目标 | 追加当前章 / 创建下一章 | 并入第 N 章《标题》 / 新开第 N+1 章 |
| composer 模式+粒度 | [写正文\|讨论规划] + 菜单[续写片段/生成整章] | [讨论 \| 续写 \| 整章] |
| header 点击区 a11y / Sheet 标题 | 项目与分支 / 项目分支 | 切换分支（两处统一） |
| 设定 Tab 上下文行 / 其 Sheet | 本次上下文预览 / 本次上下文 | 注入规则预览（入口与标题一致，footer 首句注明"仅用于诊断，不影响实际发送"） |
| composer 模型入口 | 选择项目模型（a11y） | 项目模型… |
| 撤销按钮 | 撤销最新创作节点 | 撤销上一次收录（head 为润色/同步检查点时相应为 …润色 / …同步） |
| Fork 起点选项 | 正文收录 · head 3 | 第 2 章收录后 · 7月12日 |
| 项目行副标题 | 3 分钟前 · r17 | 3 分钟前 · 共 N 章 |
| 分支行副标题 | 已同步 · head 5 | 已同步（删编号） |
| 章节管理「创作记录」 | 检查点 N / 当前 head N | 存档点 N（删"当前 head"） |
| projectBusy | The novel project has an active operation. | 项目正在处理其他操作。请先停止生成，或稍后再试。 |
| 润色漂移转换 | 转为剧情改写（按钮）/ 转为手动改写（Sheet 标题） | 统一「保存为剧情改写」 |
| 空白项目创建表单 | 主分支名称（`NovelProjectListView.swift:378`） | 删除字段，默认"主线"（分支管理可改名） |
| 上下文预算 | 输入预算 约 16,000 tokens | 收进「高级」折叠区，标签「上下文长度」 |

## 回归与验证

每片完成后按需运行（命令沿用 `docs/NOVEL_CREATION_IMPLEMENTATION_PLAN.md`）：

```bash
env JAVA_HOME=/opt/homebrew/opt/openjdk@17 xcodebuild -quiet \
  -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -parallel-testing-enabled NO \
  -only-testing:iosAppTests/NovelCollectionTests \
  -only-testing:iosAppTests/NovelSessionViewModelTests \
  -only-testing:iosAppTests/NovelCreationViewModelTests test
```

- S2/S3/S5 涉及新文件：`xcodegen generate` 后核对 stable、`iosAppExperimentalGPL`、test 三个 target 的 membership。
- 最终合并门禁：全部 29 个 Novel 测试类 + stable/Experimental arm64 Simulator build。
- 既有基线（单独归因，不算新增红灯）：`NovelSessionReplayTests.swift` 自身编译错误；`IOSSettingsWiringTests/testStreamingBlockMarkdownToggleIsConsumedByTableBlockRenderer` 既有失败。
- 模拟器手工走查清单：新建（快速开始）→ 气泡按钮确认建议 → 整章生成 → 收录默认"新开第1章" → 第二章收录默认"新开第2章" → 资料/正文读全文 → 编辑本章 → 同步 → 阅读页发起润色 → 采用 → head 气泡撤销收录 → Fork → 更多里换模型/导出。

## 明确不做（Out of scope）

- 不做角色-事件显式关联（ID 级绑定）；名字匹配不准时再立项。
- 不做检查点时间线可视化、分支树图。
- 不改快速开始的建议确认门（AI 建议必须用户确认后才写入共享设定）。
- 不引入教程卡片/引导浮层；一切靠默认值、命名与就地入口解决。
- 不动 `AppShell.swift` 路由结构与 Session 首页入口（`PlaceholderViews.swift:735-740` 保持）。
