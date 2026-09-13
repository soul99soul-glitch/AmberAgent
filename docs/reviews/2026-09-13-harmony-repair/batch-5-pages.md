# Batch 5C/6 — 页面级并发/加载/返回层级修复

范围：F07 / F08 / F12(CouncilRunPage) / F14 / F32 / F38 / F41 / F42 / R08(Novel 侧)。

独占文件（本 agent 只改这些）：
`ProfilePage.ets`、`ChatListPage.ets`、`HistoryPage.ets`、`NovelProjectsPage.ets`、`NovelWorkspacePage.ets`、`CouncilPage.ets`、`CouncilRunPage.ets`。

约束遵守：已加载 `harmony-arkts`；API 基线 12；状态装饰器全 V1；无新依赖/新框架；未跑 hvigor 全仓构建（主代理统一串行）；未改 `ChatPage.ets`/`AppContainer.ets`/`chat|deepread index.ts`；未覆盖用户已补的 `openDocument:false`（NovelWorkspacePage 保留该键，仅新增其它字段）。

---

## 修改文件与修复 ID

| 文件 | 修复 | 最小机制 |
|---|---|---|
| `harmony/entry/src/main/ets/pages/ProfilePage.ets` | F07 | 新增 `HeatmapModel` + `@State heatmap`；`loadStats` 后一次算 53×7 日期矩阵与活跃日四分位阈值，build 只读，删除每次 build 的 `Array.from(...).filter().sort()` |
| `harmony/entry/src/main/ets/pages/ChatListPage.ets` | F08 | 预览 `getById` 限制并发 8（worker pool）+ 结果聚合到局部 Record 后一次批量赋值；保留 `refreshSeq` 挡旧回调；`aboutToAppear`/首次 `onPageShow` 去重，避免首屏双刷新 |
| `harmony/entry/src/main/ets/pages/HistoryPage.ets` | F14 | 顶栏「选择」改为 `toggleSelecting()`（原恒 `exitSelecting`，选择模式不可达）；首屏加载去重 |
| `harmony/entry/src/main/ets/pages/NovelProjectsPage.ets` | F41（+F41/Chat） | `loadedOnce`：仅首次加载置 `loading=true`，后续刷新保留既有列表；首屏加载去重 |
| `harmony/entry/src/main/ets/pages/CouncilPage.ets` | F42（+F41） | 新增 `modelsLoaded`：加载前显示「正在加载模型库…」，加载后为空才报错；`startCouncil` 未加载时提示稍候而非误报空库 |
| `harmony/entry/src/main/ets/pages/CouncilRunPage.ets` | F12 | 删除 `PHASES`/`phaseTimer`/`phaseIdx`/1200ms 轮播；改用 `deriveProgress(snapshot)` 由真实 `turns` 推导「已完成 N/M 席 / 综合裁决中…」，无结构化阶段只显示运行状态 |
| `harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets` | F32 / F38 / R08 | 见下 |

---

## 各修复细节

### F07 — 热力图一次算，build 只读
`loadStats` 聚合出 `dayCounts` 后调用 `buildHeatmap()`：一次生成 53×7 `dates` 矩阵 + `q1/q2/q3`（对活跃日 `sort` 一次）。`HeatmapGrid` 改为 `ForEach(this.heatmap.dates, ...)`，`heatAlphaForDay` 只查 `this.heatmap.q*`。修复前每次 build 重建 371 个 `Date`，且每个非零格都重排一次活跃日（次数 ≤ min(371, activeDays)）；现在 build 期间零排序、零重建。行为不变：`count===0 → 0`，四分位索引与原先一致。

### F08 — 预览限并发 + 批量提交
- 新增文件级 `PREVIEW_CONCURRENCY: number = 8`（ArkTS 不支持在 struct 内写 `static readonly`）。
- `loadPreviews(list, seq)`：`cursor` 共享游标 + 8 个 worker 的 async 循环，每步先校验 `seq !== refreshSeq || !pageAlive` 再取 `getById`；单条失败 `continue` 不中断整批。
- 结果写入局部 `nextPreviews/nextCounts`（初始副本来自当前 State），`Promise.all` 后按 seq 校验**一次**赋值。修复前每条回调都复制全部已有键再赋值（累计 O(N²) 键复制）并触发 N 次 State 写。
- `refreshSeq` 语义保留：旧刷新任何阶段 `seq` 不匹配即丢弃（含最终提交）。
- 首屏去重：`firstShowDone` 标记，`aboutToAppear` 之后紧邻的首次 `onPageShow` 跳过 `refresh()`；返回本页时正常刷新。`loadDisplaySetting`/`consumeDeepLink` 仍每次 show 执行（未动深链逻辑）。

### F14 — 选择模式可达
`PageTitleBar` 的 `onAction` 由 `exitSelecting()` 改为 `toggleSelecting()`：未选中置 `true`，已选中回 `exitSelecting()`（清空 `selectedIds`）。行点击、导出、退出行为不变。

### F41 — 刷新不卸载列表 / 未加载不误报
- `NovelProjectsPage`：`loadedOnce` 为真后刷新不再置 `loading=true`，列表在刷新期间保持渲染（原实现每次刷新先移除列表导致闪烁/丢滚动）。`firstLoad` 同时用于首屏入场动画判定。
- `ChatListPage`：`loaded` 原本就只在首次置位，刷新不重置（已符合，仅补首屏去重）。
- 三个页均增加 `firstShowDone`，消除 `aboutToAppear`+首次 `onPageShow` 的双加载。

### F42 — Council 模型库加载态
`modelsLoaded` 仅在 `getModelRegistry().list()` 成功返回后置 true（配合既有 `pageAlive/loadToken` 守卫）。UI 三分支：未加载 → LoadingProgress+「正在加载模型库…」；已加载且空 → 红字引导去设置；有模型 → 列表。`startCouncil` 未加载时给「稍候再试」，避免把「尚未加载」当「空库」。

### F12 — CouncilRunPage 真实阶段
删除 1200ms `setInterval` 伪阶段与 `PHASES` 常量。新增 `deriveProgress(snap)`：`expected` = 席位×轮数（debate）或席位数（compare），统计 `turns` 中 `status==='completed'`；运行中显示 `已完成 N/M 席`，全部完成但仍在跑显示 `综合裁决中…`，终态不显示进度。顶栏仅在运行中附带进度。进入页面时同步初始 `statusText/progressText/finished`；终态直接定位「综合裁决」tab，运行中默认首个席位。无真实阶段字段时不臆造文案。

### F32 — 收录后定位资料库
`doCollect` 成功后 `tab = 2` 且 `settingsSection = 1`（资料建议在 section1；原先停在 section0，用户看不到刚生成的建议）。

### F38 / F40 — 自绘 overlay 优先返回
- 新增 `onBackPress(): boolean` → `closeTopOverlay()`，返回 true 消费返回。系统返回不再跳过自绘弹层直接退页。
- 新增 `handleBack()`：顶栏返回按钮改走同一路径 —— 有 overlay 时先关 overlay，否则 `router.back()`，保证顶栏与系统返回层级一致。
- `closeTopOverlay()` 按 ArkUI 渲染顺序逆序处理最上层：`polishCreateOpen`（busy 时不关，与 sheet 自身 onClose 守卫一致）→ `branchSheetOpen` → `versionsOpen` → `chapterMenuId` → `materialEditorOpen` → `chapterEditorOpen` → `collectMessageId`。
- 编辑脏确认：`chapterDirty()`/`materialDirty()` 对比打开时快照（`chapterOriginal*`/`materialOriginal*`）；脏则 `AlertDialog` 确认「放弃/继续编辑」，不脏直接关；「取消」按钮也走确认。
- 系统 sheet（`AlertDialog`/`promptAction.showActionMenu`）由平台自行消费返回，`onBackPress` 无对应自绘层时返回 false，不二次拦截。

### R08 — Novel 审批按 id 稳定定位
- 接线到 `AgentMessageRow` 新可选签名：`onApproveTool(id, locator?)` / `onDenyTool(id, locator?)` / `onAnswerTool(id, answer, locator?)`，两处调用点（已提交列表 / 流式 tail）均透传 `locator`。
- `continueNovelTool(id, verdict, locator?)`：先 `resolveNovelToolId` 解析真实 id，无法唯一确定时 `errorMsg` 安全拒绝，不调用 continuation。
- `resolveNovelToolId`：有 `locator` 时按 `messageId + partIndex` 精确取该 part 的 `toolCallId`（越界/非工具 part → 拒绝）；无 locator 时要求 id 非空且**全项目内唯一命中**，否则拒绝。空 id / 重复 id 不再等价批准。

---

## 共享接线需求 / 未解决点

1. **ChatListPage Want 主代理统一（R23）**：本轮只改加载并发与首屏去重，**未触碰 `consumeDeepLink`/`onPageShow` 的深链消费**。主代理统一 Want 派发后，`ChatListPage` 里 `AppStorage.get('wantNovelProjectId'…) / wantTopicId` 的消费点仍需保留对接。
2. **R08 NovelService 侧**：`harmony/deepread/src/main/ets/novel/creation.ts` 的 `continueTool`/`findTool` 仍按 id 取**首个匹配**（`tool_loop`/`tool_approval` 的 locator 语义在 Chat 域已就绪，Novel 域尚无 `applyToolApprovalToConversation` 式精确改写）。本页已从 UI 侧保证只传唯一/精确 id，novel 域无需改动即可安全；若后续要在 service 层彻底消除 blank 歧义，接线点为 `creation.ts:939 findTool` 与 `:1042 runToolContinuation` 的 id 入参（需 deepread owner 决定，本 agent 未改该 service 文件）。
3. **F41 ChatPage**：`ChatPage.ets` 属主代理独占，其 loading 状态未由本 agent 触及；`ChatListPage` 已闭合。
4. **F12 阶段文案**：council run 无结构化 phase 字段，故仅显示真实席位完成计数；若产品要求「听证/辩论/权衡/裁决」文案，需要 council 域补充结构化阶段事件，不在本轮伪造。

## 测试与静态检查
- 未新增测试（纯页面状态/并发编排；契约要求不堆 assert-only/source-string 测试）。
- 未跑 hvigor/全仓 lint（主代理统一串行）。
- `harmony/scripts/lint-arkts.sh <file>`（`LINT_EXIT_ON=none`）逐文件结果：
  - `ProfilePage.ets` → **0 error**，1 warn `@performance/reuse-date-instances-check:655`（`computeHeatmapDates` 内循环 `new Date(start)`，与修复前同型；构建期一次调用，非每次 build）。
  - `ChatListPage.ets` → **0 error / 0 warn**。
  - `HistoryPage.ets` → **0 error**，1 warn `reuse-date-instances-check:83`（既有 `timeOf`，非本次引入）。
  - `NovelProjectsPage.ets` → **0 error / 0 warn**。
  - `CouncilPage.ets` → **0 error / 0 warn**。
  - `CouncilRunPage.ets` → **0 error**，1 warn `@performance/hp-arkui-no-state-var-access-in-loop:82`（既有订阅循环内 `this.liveTexts` 读取，非本次引入）。
  - `NovelWorkspacePage.ets` → **0 error / 0 warn**。
- 未做设备/模拟器截图目测（当前无在线设备）；不声称 UI 已验证通过。

## 保留事项
- 用户已补的 `NOVEL_AGENT_MESSAGE_CAPABILITIES.openDocument = false` 保持原样，未覆盖。
- 所有原 118 个未提交修改保留，未 reset/checkout/格式化。
