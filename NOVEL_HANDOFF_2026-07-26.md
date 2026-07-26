# 小说创作 / 流式渲染 交接文档（2026-07-26）

分支 `feat/ios-provider-parity-claude`，全部改动**未提交**，留在工作区。
本文档按「已定罪根因 → 已完成 → 未竟事项 → 已证死路 → 纪律与命令」组织。
**已证死路和结论同样值钱**——它让下一轮不用再死一遍。

---

## 一、已定罪的根因（带证据）

### 1. 小说列表「整列表大幅位移 + 看到本条更早内容」

**用户症状锁窄**（真机逐条确认）：内容是本条回复自己更早的部分、整个列表整体位移、生成一段时间后突然开始。

**真机取证**（自建的异常记录器 dump）：

```
t=24.19  content = 13233
t=24.20  content = 37500   ← 一帧 +24267pt
t=24.21  content = 19563   ← 又塌回
t=24.30  content = 37529
最终稳定 ≈ 38200，同期正文只从 353 字涨到 367 字
```

**根因（已修，用户真机确认解决）**：`NovelSessionView.handleListSignalChange` 原本「仅当**不贴底**时历史窗口才吸收新增行」。贴底时不吸收 → `startIndex = totalCount - limit` 前移 → **已渲染的顶部行被踢出窗口** → contentSize 在视口上方骤缩，而底锚已在 `0eea3d38b` 撤除，无任何补偿。

**第二根因（已修，未真机验证）**：吸收用的是 `rowCount`（含活动 run），而 `startIndex` 用的是 `historicalRows.count`。单位错配导致**每次发送 `startIndex` 净下降 activeRunRowCount（实测 2）**，等于在视口上方插入两条旧历史。这是「残留小幅抖动」的最强嫌疑。

> 方法论教训：症状最初被我判成「offset 之争」，实际是**测量之争**。此前数天一直在滚动层打补丁，方向就是错的。

### 2. 整章重新生成曾是完全的死代码

第一版设计把 `sourceChapterVersionID` 复用到 `.prose` run 上，而**三处不变量**要求 prose ⇒ 该字段为 nil：
- `NovelGenerationReducer.swift` 的 shape 守卫（发起即抛）
- `NovelGenerationDocumentValidator` 的 run shape（落盘后文档判损坏、项目打不开）
- 中断候选的归一化

且 `.proseWholeChapter` 的提示词原文是 *"Continue from the prior chapter without recapping or rewriting it."* ——**提示词本身禁止重写**，注入的也只是「最后一章的结尾片段」，被重写的那一章根本不进上下文。

**已按正确设计重做**：给它自己的 `NovelRunKind.regenerate` + `NovelPromptKind.wholeChapterRegeneration`。

---

## 二、本轮已完成（全部已编译 + 测试验证）

### 小说创作
| 项 | 文件 | 验证 |
|---|---|---|
| 历史窗口不再驱逐已渲染行 | `NovelSessionView` / `NovelSessionPresentation` | 真机确认 |
| 窗口吸收改用历史行口径 | `NovelSessionView:1047` | 单测，未真机 |
| 整章重新生成（新 run 类型 + 提示词 + 注入 + 中断保留目标） | `NovelActions` / `NovelGenerationReducer` / `NovelGenerationLifecycle` / `NovelPromptCatalog` / `NovelInjectionPlanner` / `NovelSessionViewModel` / `NovelChapterReaderView` | **红→绿有实证** |
| `.replaceChapter` 收录目标（替换而非追加） | `NovelDomainModels` / `NovelFactTransactions` / `NovelDocumentValidator` / `NovelCompatibilityLineage` | 单测 |
| 领域层守卫：替换只能落到候选自己重写的那一章 | `NovelFactTransactions` | 单测 |
| 导出跳过废弃章（导出此前**零测试覆盖**） | `NovelProjectPackage` | 单测（先改后测，未跑红） |
| 收录面板过滤废弃章 | `NovelProjectWorkspaceView` | — |
| 替换后不再弹「归档讨论」 | `NovelProjectWorkspaceView` | — |
| 回滚承诺文案改成如实 | `NovelSessionSheets` | — |
| 生成中状态条移到输入框上方 + 按 run 真值显示文案 | `NovelSessionView` / `NovelSessionBubble` | — |
| 润色路径补流式门控 | `NovelSessionBubble` | — |
| 跟随停靠留白 96→44（小说独立常量，不碰 `nearBottomResumeThreshold`） | `NovelSessionView` | — |

### 流式渲染（与上面无关的独立工作）
- vendor 新增 `.coalescedText`：相邻纯正文段落合并成一个 TextKit 文本视图。主线程 CPU **-17%**（`ratio=0.830`，24KB 散文 / 60 delta）。开关 `长文正文合并渲染（实验性）`，**默认关**。
- 审查发现并修掉两处硬伤：段内换行吃到块间距（`paragraphSpacingBefore` 泼在整段范围上）、占位→解析交接身份翻转（改用 run 序号做 id）。vendor 91/91 绿。
- Chat 与小说的呈现节奏合并为 `StreamPresentationPacingPolicy`（仅统一「每拍多少字」；「多久一拍」的 48ms 仍是三处硬编码）。

---

## 三、未竟事项（按优先级）

### P0 矛盾性检查（用户最早提的核心需求，一个字没写）
目标：扫描全部正文，列出重复剧情、前后相悖、「明明见过却写成初次见面」这类问题。

**已确定的实现路径**：走**结构化任务**（`NovelStructuredModelExecutor`），不是会话 run。照 `polishDrift` 的模式，需要：
1. `NovelContinuityAuditV1` schema（问题类型 / 严重度 / 涉及章节 / 证据）
2. **`StrictJSON` 严格校验器**——解码器强制走它，不可跳过
3. `NovelPromptKind` 新增 case + 模板 + 版本号，并更新 `NovelPromptCatalogTests` 的目录快照哈希
4. `NovelStructuredModelTaskKind` / `NovelStructuredModelTask` / `NovelStructuredModelOutput` 三处分发 + 消息构造
5. 执行入口与失败处理
6. 结果界面：能列出问题、点进去跳到对应章节

### P0 替换章节后剧情状态只加不减
重写第 3 章把「A 死了」改成「A 活着逃走」，状态摘要里两条并存 —— **直接削弱「用重写消除矛盾」的效果**。

**已查证的事实（要更正一个流传的错误说法）**：注入给模型的 `CURRENT BRANCH STATE` 用的是 `summary` + `branchOutline` + 角色身份图（`NovelInjectionPlanner:764-796`），**不是事件列表**。事件是只增的历史账本，陈旧事件不会直接进上下文。

真正的病灶：`finalizeCollection` 的状态增量步骤拿到「基线状态 + 新正文」，**不知道这段正文是替换而非追加**，所以只加不撤。
**修法不需要改 schema**：把「这是对第 N 章的替换、原文如下」传给状态增量步骤，让它能撤回被取代的事实。要动提取输入、状态增量提示词、结构化输出契约。

### P1 其它
- 替换后旧版本**不能直接回滚**（`.collected` 另起事实链，`restoreChapterVersion` 会抛）。文案已改成如实，但缺「以手工编辑恢复」的便捷入口。
- 章节列表仍会列出废弃章（导出和收录已过滤）。若改成默认隐藏，**必须同时提供恢复入口**，否则废弃即不可逆。
- 「多久一拍」的 48ms 在 `ChatGenerationCoordinator` / `NovelSessionViewModel` / `CouncilRunner` 三处硬编码。
- `ChatRowDigest` 的行高 digest 不含任何 iOS 端 `@AppStorage` 显示偏好（`fontScale` / `chatFont` / `streamingBlockMarkdown` / `coalescedTextBlocks` 全在外面）。**既有的洞**，要修得一起修。
- 两个**死开关**：`microsoftStreamingMarkdown` / `liyananStreamingMarkdown`。`shouldUseFadeStreamingRenderer` 与 `shouldUseExperimentalMarkdownRenderer` 函数体逐字相同，导致第 3、4 个分支**永远不可达**；现有测试还在给这段死代码发绿灯。
- 「Chat 原生时间线」一个 UI 开关写两个 key（`staticRender` + `streamingTail`），非原子。残留态会导致**每个 delta 在两条列表路径间来回换**。
- `[TEMP-DIAG]` 诊断代码仍在（已全部收进 `#if DEBUG`）。定罪完成后可整体删除，搜索 `TEMP-DIAG`。
- 一条**预先存在**的失败测试：`NovelGenerationLifecycleTests.testModelWindowClampsPlannerBudgetInsteadOfRejectingSmallRequest`（3072 vs 7168）。已在干净 HEAD worktree 上 A/B 确认与本轮改动无关。

---

## 四、已证死路（别再走一遍）

1. **Chat 的 LOD 冻结振荡 / 渲染与更新判据不对称** —— 机制真实存在（`renderViewportState` 把 `liveRenderingFarFromBottom` 硬钉为 false，而更新侧用真值），但**用户在小说界面，这条路径不参与**。
2. **小说呈现节奏层的内容回退** —— 插桩后 `[AA-NOVEL]` 零命中，且 `src`/`shown` 全程为 0，说明小说正文根本不走那个 pacer。已证伪。
3. **长文正文合并渲染** —— 用户确认症状早于该功能存在。
4. **UICollectionView cell 复用 / 行高缓存 digest** —— 该开关组合下走的是 `NativeChatTimelineView`（SwiftUI ScrollView + LazyVStack），collection 路径完全不在链路上，`ChatScrollArbiter` 的 feature flag 也默认关。
5. **跨消息缓存串味** —— 症状是本条回复自己的内容，机制不成立。
6. **`print` 做真机诊断** —— iOS 应用 stdout 在非 TTY 下全缓冲，`devicectl --console` 抓不到。**必须用 `NSLog`**。仓库里原有的 `[AA-NATIVE-SCROLL]` 诊断也是 `print`，在真机上等于没有。

---

## 五、纪律清单（本轮踩过的坑）

1. **没有端到端红测试就不算完成。** 整章重新生成第一版编译通过、数据层单测全绿，但一次都跑不起来——因为没有任何用例走过「发起」。
2. **编译器在前面文件出错时不会继续报后面的。** 「报错只有别人的文件」不等于「我的文件干净」。
3. **子代理的归因必须复核。** 本轮三路审查里，头号嫌疑被推翻过两次（合并渲染击穿身份对齐、事件列表污染上下文）。
4. **对照实验要条件对等。** 三条测试在全量跑里红、独占跑绿，是并发负载导致；在干净 worktree 上 A/B 才能定性。
5. **绝对毫秒阈值的性能门禁天生假红**（如 `testPerfGrowingTableStreamingKeepsDisplayLinkResponsive` 的 40ms）。比值 canary 才稳定。
6. **诊断代码本身会说谎。** 自建记录器的环形缓冲截断没有修正绝对下标，异常检测会静默死掉而心跳照常输出——「日志无异常」曾经是假的。

---

## 六、常用命令

```bash
# 模拟器测试
cd iosApp && xcodebuild -project AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:iosAppTests/NovelSessionViewModelTests test

# 真机构建（project.yml 里没有 DEVELOPMENT_TEAM，必须显式带；.xcodeproj 是 xcodegen 生成物，不在版本控制里）
cd iosApp && xcodebuild -project AmberAgent.xcodeproj -scheme iosApp -configuration Debug \
  -destination 'id=<设备UDID>' -derivedDataPath /tmp/amber-dd \
  DEVELOPMENT_TEAM=89QRFX9548 -allowProvisioningUpdates build

# 装机 + 抓日志（print 抓不到，诊断必须用 NSLog）
xcrun devicectl device install app --device <UDID> /tmp/amber-dd/Build/Products/Debug-iphoneos/iosApp.app
xcrun devicectl device process launch --device <UDID> --console --terminate-existing app.amber.ios

# vendor 测试
cd iosApp/vendor/SwiftStreamingMarkdown && xcodebuild -scheme SwiftStreamingMarkdown \
  -destination 'platform=iOS Simulator,name=iPhone 17' test
```

> 注意：本仓库可能有**多个会话同时在改**（本轮遇到过 `IOSEmbeddedIshRuntime.swift` 被并发修改导致构建失败、以及 xcodebuild 数据库锁冲突）。开工前先确认工作区能编译。
