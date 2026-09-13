# UI 视觉/动效条目复核（只读）— F01–F13、F41、F42

日期：2026-09-13
仓库：`/Users/mi/Downloads/AI/AmberAgent-harmony-preview`（branch `harmony-preview`，HEAD `bdce551dd373f3d7e4c393b194997cae19289c40`，工作树含 54 个未提交改动）
复核环境：`hdc list targets` 为空 → **无模拟器/真机、无截图、无 UI 注入、无帧率/耗电实测**。本文件为隔离新增材料；未改任何产品代码、未格式化、未提交、未读写真实用户数据、无真实网络外呼。

## 0. 方法与边界（先说不能说什么）

- 本次只做**源码锚点复核**：逐条打开原报告指向的 `.ets`/`.ts`，确认行号、条件、生命周期与调用链是否如报告所述；不重新做 74 页逐页全文通读。
- **不凭百分比几何推算判定「实际裁切/命中/出屏」**。凡结论依赖运行期渲染、字体排版、安全区、键盘、帧时间、命中的，一律标 `需设备`，只陈述可静态闭合的事实（元素尺寸声明、条件分支、调用点、生命周期钩子）。
- F07/F08/F09/F10 只保留**可证的规模与复杂度**，不写帧率/耗时数字（无 profiling）。
- 覆盖结论以 [2026-09-13-harmony-ui-ux-coverage.md](../2026-09-13-harmony-ui-ux-coverage.md) 为**来源声明**引用；本次未逐页重读 74 页，故不冒称「本次逐页全读」，也不为其「全文」自述背书。
- 命名沿用 [merge-policy.md](./merge-policy.md) 的 UI-Fxx 口径；本文用原报告的 F 编号书写（F-01…F-13、F-41、F-42），不新增计数。
- 级别（P2/P3）沿用原报告，本次唯一调整的是**证据强度与措辞**，不是重新定级；若与原文有出入，以本文显式列出的「修正/反证」为准。

### 状态口径

| 状态 | 含义 |
|---|---|
| `实际可证` | 源码路径闭合，能推出确定的用户可见行为（与设备无关的部分） |
| `性能控制缺口` | 复杂度/规模可证，但后果强度（帧率、耗时）无实测 |
| `纯打磨` | 一致性/规格差异，非功能缺陷 |
| `需设备` | 结论成立与否或量值依赖运行期，本次无法验证 |

---

## 1. 逐条复核

| 编号 | 原级别 | 本次状态 | 是否保留 | 一句话结论 |
|---|---|---|---|---|
| F-01 | P2 | 几何差异实际可证；实际裁切 `需设备` | 保留 | 4 个 pane 根 `height('100%')` vs `chatPane` 的 `layoutWeight(1)` 属实；溢出「存在」可证，裁切量不可证 |
| F-02 | P2 | 占位超父宽实际可证；呈现 `需设备` | 保留 | `width('100%')`+左右 margin 16 属实；是裁切还是全出血取决于祖先 clip，未证 |
| F-03 | P2 | 算式实际可证；窄屏真实体验 `需设备` | 保留（措辞修正） | 展开态输入文本余宽 = `W − 320vp`：**320→0、360→40、384→64**；不是全程「逼近零」，360 仍有约 2 汉字宽 |
| F-04 | P2 | 硬编码像素实际可证；表现 `需设备` | 保留 | `anchor:{windowOffset:{x:660,y:2200}}` 写死属实 |
| F-05 | P2 | 尺寸写死实际可证；多行断裂 `需设备` | 保留 | 竖线 `height(54)` 与事件文本行数解耦属实 |
| F-06 | P3 | 部分可证、**「总高差 ~15vp」未证** | 保留但收窄 | 标题 x 基准确实 60/72/66/44；总高差具体值本次未闭合 |
| F-07 | P2 | 性能控制缺口 | 保留（修正规模） | `heatmapDates()` 每 build 重建 371 个 Date；排序只发生在**非零天**，非 371 次全排序 |
| F-08 | P2 | 性能控制缺口 | 保留（修正 2N→N） | 双入口与未 await 扇出属实；`refreshSeq` 守卫使冷启动 fan-out 只跑后发起的一次 |
| F-09 | P2 | 实际可证（timer 与 streaming 解耦）+ 性能缺口 | 保留（含隐藏页） | 非 user 消息无条件起 525ms timer；**被覆盖的隐藏页组件不销毁，timer 不停** |
| F-10 | P2 | 实际可证（结构）/ 量级 `需设备` | 保留 | 小说时间线全量 `ForEach`、无 Chat 侧尾窗；进入强制滚底、`activeMessages=[]` 属实 |
| F-11 | P3 | 清空行为实际可证；观感 `需设备` | 保留 | `loading=true; searched=true; results=[]` 在 debounce 之前属实 |
| F-12 | P3 | 实际可证 | 保留 | 子代理阶段是「有时间函数、无 tick 驱动」；议会组件 `startPhaseRotation` 空实现且注释前提错误；`ModelCouncilRun` 无 `phase` 字段 |
| F-13 | P3 | 实际可证 | 保留 | `greetTimer` 仅 `aboutToDisappear` 清，`onPageHide` 未清，回调无可见性守卫 |
| F-41 | P2 | 实际可证（本次未提交改动） | 保留 | `onPageShow` 为工作树新增 hunk；`loadProjects` 开头无条件 `loading=true`，build 在 loading 时整屏只渲染加载态 |
| F-42 | P2 | 实际可证 | 保留 | ChatPage 无 `conversationLoading`（0 命中）；CouncilPage 无 `modelsLoaded` 门 |

---

## 2. 布局/对齐/密度（F-01…F-06）

### F-01 NovelWorkspacePage pane 高度：保留，实际裁切 `需设备`

- 锚点核实（`harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets`）：
  - `chapterPane` 根：L1690 `.width('100%').height('100%')`；
  - `chapterReader` 根：L1735 同型；
  - `materialLibraryPane` 根：L1841 同型；
  - `settingsPane` 根：L1872 同型；
  - 对照 `chatPane` 根：L1529–1533 用 `.layoutWeight(1)`，**无** `height('100%')`；
  - 父容器：`build()` 的 Column L1959 `.width('100%').height('100%')`，同列内先放顶栏（L1902–1931）、页签（L1934–1939）、errorMsg（L1941–1944），再条件挂 pane（L1951–1957）。
- 静态可闭合的事实：pane 根声明为「父容器满高」，但它被排在顶栏+页签之后；同一父列内若干定高子项 + 一个满高子项，其纵向和必然超过父高，越界量即该 pane 未扣掉的 chrome 高度（顶栏约 `statusBar+61`、页签约 56vp，具体随 `statusBarHeight` 变）。`chatPane` 用 `layoutWeight(1)` 吃剩余空间，是唯一正确写法。
- 修正：原报告「百分比按最近固定大小祖先（整屏高）解析」是 CSS 绝对定位式的表述；ArkUI 的 `height('100%')` 是相对**直接父组件**解析，本页父列本身满高，两者对「超出剩余区」的结论一致，但机制描述应改为「相对满高父列」。此外原报告未提该页根节点无 `clip`，越界部分是「被绘制到屏外/被窗口裁」还是要靠框架兜底，**必须设备确认**，本次不判实际裁切量。
- 修复方向不变：`height('100%')` → `layoutWeight(1)`（4 处）；底部是否补 navBar 内距需先确认该页安全区语义，不无条件叠加。

### F-02 PaperSeparator 占位超父宽：保留，呈现 `需设备`

- 锚点核实：`harmony/entry/src/main/ets/components/PageTemplate.ets` L118–126 `Row().width('100%').height(1).backgroundColor(LINE).margin({left:16,right:16})`；同型本地副本 `harmony/entry/src/main/ets/pages/SkillsPage.ets` L154–156。
- 静态可闭合：`width('100%')` 解析为父内容宽，margin 在其外，元素总占宽 = 父宽 + 32，居中时左右各越界 16；设计注释 L116 自述目标是「左右内缩 16 的 1px 线」，实现相反。使用面广：全仓 70 处 `PaperSeparator` 引用（`McpPaperRows`、`PageTemplate` 内部 `NavRow/ToggleRow/SelectRow`、Cron/Files/Search/Provider 等多个设置页）。
- `需设备` 部分：祖先是否 `clip` 决定「被裁两端」还是「全出血延到屏边」；报告未断言具体呈现，本次同样不判。
- 修复：`Row().width('100%').padding({left:16,right:16})` 内嵌 1px 子线；SkillsPage 删本地副本改用共享组件。

### F-03 ChatInput 展开附件后的窄屏余宽：保留，措辞修正（关键算术）

- 锚点核实（`harmony/entry/src/main/ets/components/ChatInput.ets`）：
  - 附件胶囊：加号 44（L145）+ 动作组 `width(this.attachExpanded ? 136 : 0)`（L177），外层 `padding left/right 2`（L184）、`margin right 8`（L192）→ 展开态占宽 192；
  - 中段 Row：`layoutWeight(1)`（L213）、`padding left/right 18`（L218）、`margin right 8`（L220）；内层 TextArea `layoutWeight(1)`（L199），`constraintSize` 只有 `minHeight`（L208/L214），**无 minWidth**；
  - 发送/停止键：44（L227/L240）；
  - 行 padding `left/right 20`（L268）。
- 窄屏真实余宽（展开态）：可用 `W−40`；固定件 `192+8+44 = 244`；中段外框余 `W−284`；扣除内边距 36 → **文本可编辑宽度 = `W − 320vp`**。
  - `W=320` → **0vp**（字面为零，非「趋零」）
  - `W=360` → **40vp**（约 2 个汉字，明显窄但可输入）
  - `W=384` → 64vp；`W=411` → 91vp
  - 收起态（未展开）：固定件 108 → 文本宽 `W − 184`，320vp 屏仍有 136vp，**只有展开态才触发**。
- 修正/反证：原报告「≤320–360vp … 剩余宽度趋零」把 360 也说成趋零，实测算式下 360 仍有 40vp；真正归零的临界是 320vp。故「实际可用性差」成立，但不应用「320–360 一律趋零」描述。`NovelWorkspacePage` 传 `showAttachments:false`（L1510），本项只作用于 ChatPage（默认 `showAttachments=true`）。
- 修复：展开态给 TextArea/中段 `constraintSize({minWidth:96})`；或展开动作改 Sheet/浮层。

### F-04 分享锚点写死像素：保留

- 锚点核实：`harmony/entry/src/main/ets/pages/ChatPage.ets` L2662–2664 `controller.show(ctx, { anchor: { windowOffset: { x: 660, y: 2200 } } })`，失败落 `fallbackCopyShare`。
- 静态可闭合：锚点与设备分辨率/密度/方向无关地写死，本身即缺陷；是否真越界、popover 是否贴屏外 `需设备`。
- 修复：触发时按实际点击位置算锚点，或省略 anchor 用默认。

### F-05 时间轴竖线固定 54vp：保留

- 锚点核实：`harmony/entry/src/main/ets/pages/DeepReadArticlePage.ets` L679–689 `Stack(){ Column().width(1).height(54); Circle(...) }.width(18).height(54)`；右侧 `MarkdownText` L693–699 `lineHeightScale:1.6`、`fontSize 16*fontScale`。
- 静态可闭合：轨道高度与事件文本行数解耦；事件跨 2 行以上时线只覆盖上半段。具体断行与观感 `需设备`。
- 修复：轨道改在 `Row` 内由 `Column` 撑满行高，或按行数估算。

### F-06 顶栏标题起点/总高不一致：保留但**收窄为纯打磨**

- 锚点核实（标题水平起点）：
  - `PageTemplate.ets` `PageTitleBar`：`padding left 4`（showBack）+ 返回 48 + 标题 `margin left 8` → **x≈60**；`constraintSize minHeight 64`，`padding top statusBarHeight, bottom 8`（L44–66 段）。
  - `ChatMcpSettingsPage.ets` L548–584：`padding left 16` + 返回 48 + `margin left 8` → **x≈72**。
  - `ChatAssistantsPage.ets` L346–369：`padding left 14` + 返回 44 + `margin left 8` → **x≈66**。
  - `ChatProviderDetailPage.ets` L319–371：`padding left 20` + 返回 48 + `margin left -10` + 内列 `margin left 8` → **x≈66**。
  - `SearchPage.ets` L341–346：`padding left 14` + 返回 icon 22 + `margin left 8` → **x≈44**。
- 修正/反证：x 基准 60/72/66/44 可静态证；但原报告「顶栏总高相差约 15vp」本次**未闭合**——各页高度取决于 `statusBarHeight + 内容高 + 上下 padding`，`PageTitleBar` 的 `minHeight 64` 在 `statusBarHeight=44` 时并不生效（实际被内容撑到约 `statusBar+56`），逐页差值需按实际内容测量，不能由常量相减直接得 15。故本项降为**纯一致性打磨**，不称功能缺陷，也不称标题会抖动。
- 修复：抽 `tokens` 顶栏常量或以 `PageTitleBar` 为准收敛。

---

## 3. 动效/滚动/性能（F-07…F-13）

### F-07 个人中心热力图每格重算：保留（性能控制缺口，规模修正）

- 锚点核实（`harmony/entry/src/main/ets/pages/ProfilePage.ets`）：
  - L590 `ForEach(this.heatmapDates(), …)`，`heatmapDates()` L615–628 每次调用重建 **53×7 = 371 个 `Date` 与 dayKey 字符串**；
  - L594–596 每格 `backgroundColor(this.heatColor(this.heatAlphaForDay(dayKeyStr)))`；
  - L631–641 `heatAlphaForDay`：`count===0` 提前返回 0；非零才执行 `Array.from(dayCounts.values()).filter(v>0).sort(...)`。
- 修正：原报告「每格执行 `Array.from(...).filter().sort()`、371 次数组拷贝+过滤+排序」偏大——**只有非零天**进入排序分支，排序次数 ≤ `min(371, 活跃天数)`；但 371 个 Date/字符串分配与 371 次函数调用是每次 build 恒定的。活跃天数越多，单次 build 越重，且集中在主线程。
- `需设备`：首帧耗时与掉帧与否无帧率实测，不写具体 ms。修复：`loadStats` 完成时预算热力值存 `@State`，日期矩阵同样缓存。

### F-08 会话首页重复刷新 + 并发 RDB 读 + O(N²) 键拷贝：保留（修正 2N→N）

- 锚点核实（`harmony/entry/src/main/ets/pages/ChatListPage.ets`）：
  - `onPageShow` L60–69 与 `aboutToAppear` L71–82 都调 `refresh() + loadDisplaySetting + consumeDeepLink`；
  - `refresh()` L118–169：`repo.list()` 后对每个 summary `repo.getById(summary.id)` **未 await、无并发上限**（L139–166）；
  - L157–165 逐条复制全量 key 重建 `previews`/`msgCounts`（`for key of Object.keys(...)`) → 单次刷新的拷贝量约 `Σk ≈ N²/2`。
- 修正/反证：`refreshSeq`（L120）在每次 `refresh()` 自增；冷启动 `aboutToAppear`→`onPageShow` 在同一帧内先后触发，前者 seq=1、后者 seq=2，第一个 `list()` 回调因 `seq !== this.refreshSeq` 被丢弃（L123），**per-conversation 的 `getById` 扇出只由后一次执行**。所以「`repo.list()` 跑两遍」属实，「约 2N 并发读」应改为 **≈N**（一次重复的是 list 查询与设置加载，不是两倍扇出）。
- 其余结论成立：列表首帧后仍在后台跑、预览先「暂无消息」后替换的闪动、`Record` 整体赋值的 O(N²) 键拷贝。无帧率实测。
- 修复：二选一保留加载入口 + 首帧跳过标记；预览改批量接口或限首批；`Record` 一次构建新对象。

### F-09 ChatMessageAvatar 非流式也常驻 525ms timer：保留（并补强：隐藏页不停）

- 锚点核实：`harmony/entry/src/main/ets/components/ChatMessageAvatar.ets` L19–24 条件仅 `message.role !== 'user'`，与 `streaming` 无关，起 `setInterval(..., 525)`；光标仅在 L41–46 `streaming` 时渲染；清理 L26–31 正确。
- 调用链：`AgentMessageRow.ets` L433 每条消息实例化一次；`ChatPage.ets` L4348–4410 时间线 `ForEach` 逐条渲染，`ChatMessageAvatar` 装配在 L433。视口内每条助手消息各跑一个 525ms timer 翻转 `@State cursorOn`。
- 补强（隐藏页问题，见 §4）：该 timer 只在 `aboutToDisappear` 清。被 `pushUrl` 覆盖的 ChatPage 组件树不销毁 → 隐藏期间这些 timer **持续运行**，`onPageHide` 不涉及。故 F-09 的无效状态翻转在隐藏页仍发生，比原报告的范围更大。
- `需设备`：帧率/耗电无实测。修复：仅 `streaming` 时启动，或 `@Watch('streaming')` 起停；亦可改 `animateTo({iterations:-1, playMode:Alternate})` 免 timer。

### F-10 小说时间线全量渲染 + 强制滚底：保留（结构实际可证，量级需设备）

- 锚点核实（`harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets`）：
  - `novelTimelineRows` L1326–1347：`ForEach(this.activeRunId ? project.messages.slice(0, activeHistoryCount) : project.messages, …)`，**无尾窗/分页**；对照 `ChatPage.ets` L994 `getConversationTailWindow(convId, 50)`（`TIMELINE_PAGE_SIZE=50`，L242）。
  - `initialScrollArmed` L274 → `scheduleNovelScrollToBottom` L797–805 → `chatScroller.scrollEdge(Edge.Bottom)`；`InteractiveMessageTimeline` 的 `onTimelineAppear/onVisibleRangeChange` L1463–1474 触发。
  - `attachNovelRun` L908–927：L920 `this.activeMessages = []`（生成中返回闪断一次）。
- `需设备`：首帧构建全部 `AgentMessageRow` 的迟滞、大项目实测。修复方向：接入 Chat 侧尾窗 + 按需前插；纯回看不强制滚底。

### F-11 SearchPage 即输即清空：保留（清空可证，观感需设备）

- 锚点核实（`harmony/entry/src/main/ets/pages/SearchPage.ets`）：L251–253 在 `setTimeout(..., SEARCH_DEBOUNCE_MS)`（L254）**之前**执行 `this.loading = true; this.searched = true; this.results = [];`。渲染 L364–368 为居中 loading；空/失败态用 `margin top: 64`（L374/L379/L384），与 loading 的 `layoutWeight(1)+justifyContent(Center)` 锚点不同。
- 静态可闭合：旧结果被立即清空、`searched` 立即置真。闪烁节奏、滚动位置丢失、垂直跳变的观感 `需设备`。属体验问题非数据损坏，P3 合理。
- 修复：stale-while-revalidate 保留旧结果，四态统一锚点。

### F-12 子代理/议会阶段标签非时间驱动：保留（措辞修正）

- 锚点核实：
  - `ChatMessageSubAgentStep.ets` L77–81：`currentPhase()` 用 `Date.now()/5000 % DEFAULT_PHASES.length`。**修正**：这不是「非时间驱动」，阶段是墙钟函数，但**没有任何 tick / @State 驱动重渲染**，故只在其它状态变更引发 rebuild 时才更新。
  - `ChatMessageCouncilStep.ets` L86–102：`aboutToAppear` 调 `startPhaseRotation()`，而后者 L93–96 **空实现**；注释「ArkUI 不支持 setInterval」前提不成立——本仓库多处正常使用 `setInterval`（如 `ChatPage`、`CouncilRunPage`、`MarkdownText`）。`currentPhase()` 同用墙钟取模，无 tick。
  - `CouncilRunPage.ets` L110–116 `setInterval(..., 1200)` 无条件轮转 `phase`；L165 顶栏显示 `statusText + ' · ' + phase`。
  - `harmony/deepread/src/main/ets/council/models.ts` L120–131 `ModelCouncilRun` 无 `phase` 字段 → 议会阶段只能来自本地 `PHASES` 假轮转，与真实 `statusText` 可能自相矛盾。
- `需设备`：用户误判强度属观感。修复：用 `setInterval` 驱 `@State tick`（离页清理）；议会阶段改由真实数据推导，无字段则只显状态动词。

### F-13 问候句定时器 `onPageHide` 未清：保留（隐藏页实际可证）

- 锚点核实（`harmony/entry/src/main/ets/pages/ChatPage.ets`）：
  - `aboutToAppear` L889–894：`greetTimer = setInterval(..., 3200)`，回调 `animateTo` 改 `greetOpacity/greetIdx`，**无 `pageVisible` 守卫**；
  - `onPageHide` L767–775：只 `clearInterval(cursorTimer)`（L769–772），未动 `greetTimer`；
  - `aboutToDisappear` L687–690：才清 `greetTimer`。
- 静态可证：生成中点开设置子页（ChatPage 被覆盖、未销毁）时，隐藏页每 3.2s 仍执行一次 `animateTo` + 状态写入；返回可能停在中间态。修复：`onPageHide` 一并清、`onPageShow` 重挂（注意 `greetTimer` 目前只在 `aboutToAppear` 启动，若只在 hide 清而不在 show 重挂，返回后将永久停摆——修时必须成对）。

---

## 4. 隐藏页 timer / 页面状态是否确实未清（专项结论）

按 `pushUrl` 的 ArkUI 语义：被覆盖页的组件树保留，只触发 `onPageHide`，不触发 `aboutToDisappear`。据此逐项核对「页面被覆盖（非销毁）」时是否有清理：

| 资源 | 位置 | 覆盖时是否清理 | 结论 |
|---|---|---|---|
| `ChatPage.greetTimer`（3200ms） | `ChatPage.ets` L889 / 767 / 687 | **否**（仅 aboutToDisappear） | 实际未清（=F-13） |
| `ChatPage.cursorTimer`（525ms） | L726/885 / L769 | 是（onPageHide 清，onPageShow 重挂） | 已清，是正确对照 |
| `ChatMessageAvatar.blinkTimer`（525ms×每条助手消息） | `ChatMessageAvatar.ets` L19–31 | **否**（仅 aboutToDisappear） | 隐藏 ChatPage 内视口消息的 timer 继续跑（放大 F-09） |
| `ChatMessageReasoning.durationTimer`（250ms，仅 loading 时） | `ChatMessageReasoning.ets` L32–37/56–61 | **否**（仅 aboutToDisappear） | 若覆盖时该组件仍处 loading，timer 继续跑 |
| `MarkdownText.pacingTimer` | `MarkdownText.ets` L138–152 / 200–212 | **否**（仅 aboutToDisappear；`streaming=false` 会 settle 停表） | 仅流式尾在跑；被覆盖时若仍在 streaming 则继续 |
| `CouncilRunPage.pollTimer`（400ms）/`phaseTimer`（1200ms） | `CouncilRunPage.ets` L91–123 | **否**（仅 aboutToDisappear；该页**无** onPageHide） | 覆盖后仍轮询/轮转 |
| 小说 `scheduleGhostwritePoll/schedulePolishPoll`（1200ms 递归 setTimeout） | `NovelWorkspacePage.ets` L302–375 | 仅靠 `pageAlive`（仅 aboutToDisappear 置 false） | 覆盖时继续轮询 |
| 页内 `@State`（`selectedChapterId`/`tab`/`settingsSection`/`draft` 等） | 各页 | 覆盖时不重置，返回保留 | 属 ArkUI 语义；仅当产品要求「返回重置」时才是缺陷，本次不判 |

- 佐证：全仓仅 `ChatPage.ets` 与 `Index.ets` 实现 `onPageHide`（`Index` 未注册在 `main_pages.json`）；其余页面无该钩子。
- 结论回应任务提问：**是**，隐藏页确有 timer 未清——`greetTimer` 是原报告已列的一条，此外 `ChatMessageAvatar.blinkTimer`、`CouncilRunPage` 双 timer、小说双 poll 也仅在销毁时清；`cursorTimer` 是唯一已按可见性正确起停的对照。以上均为静态可证，实际耗电/唤醒强度需设备。

---

## 5. 页面衔接/异步（F-41、F-42）

### F-41 NovelProjectsPage `onPageShow` 过度重置：保留（本次工作树新增改动）

- 锚点核实：`harmony/entry/src/main/ets/pages/NovelProjectsPage.ets`
  - `aboutToAppear` L33–38 调 `loadProjects()`；`onPageShow` L40–44 再次调用；`git diff` 确认该 `onPageShow` 是**未提交新增 hunk**（HEAD 版本无此方法）。
  - `loadProjects` L51–54：开头 `if (this.pageAlive) this.loading = true;`（每次调用无条件）。
  - `build` L282–294：`if (this.loading)` 走整屏「加载中…」分支，`else if (this.projects.length===0)` 才是空态。
- 静态可证：返回项目库时先被置 loading → 整屏替换为加载态 → 列表重排；冷启动 `aboutToAppear`+`onPageShow` 双调用 `listProjects()`（`loadToken` 守卫丢弃前者结果，但 loading 置真两次）。滚动位置丢失与「闪屏」为直接结果，无需设备即可判定为回归。原报告 `需设备往返` 可收窄为**结构可证**，具体闪烁时长仍属观感。
- 修复：`onPageShow` 仅在已有数据时静默刷新；加 `initialLoad` 标志仅首次置 `loading=true`。

### F-42 ChatPage 闪空态 hero / CouncilPage 误报模型库为空：保留

- 锚点核实：
  - `ChatPage.ets` L4836 `if (this.rows.length === 0 && !this.sending)` 渲染 AMBER hero（L4837–4853，含 `GREETINGS[this.greetIdx]`、`greetOpacity`）；`rows` 由 L994 `getConversationTailWindow` 异步回调赋值；全文 `conversationLoading` **0 命中**（无加载门）。
  - `CouncilPage.ets` L43 `models: ModelConfig[] = []`，L68–71 `await getModelRegistry().list()` 后才赋值；L235–237 `if (this.models.length === 0)` 直接显红字「模型库为空。请到 设置 → 模型库 添加模型。」，无 `modelsLoaded` 门。
- 静态可证：进既有会话首帧 `rows=[]` 且 `sending=false` → 短暂显示 hero 再被消息替换；进议会页首帧 `models=[]` → 闪红字。停留时长取决于异步快慢，属观感 `需设备`。修复：`convId.length>0` 路径加 loading 门/骨架；Council 加 `modelsLoaded`，加载完成且为空才告警。

---

## 6. UI 附录 / 驳回表 / 覆盖表的抽检

> 本节只抽检，不重做全表；「未抽检」不等于「已确认」。

### 6.1 驳回表（原报告 §3.1）抽检

| 原驳回项 | 本次抽检结果 |
|---|---|
| 恒真 opacity 乘数 | 属实且为死表达式：`SettingAppearancePage.ets` L112 `.opacity((accentColorStore.length>=0 && (darkMode||!darkMode)) ? 1 : 1)`；`SettingDisplayPage.ets` L174 `.opacity(accentColorStore.length>=0 ? 1 : 1)`；`ChatPage.ets` L4941 `.opacity(pageEnterOpacity * (themeTick>=0 ? 1 : 1))`。三者恒 1，不构成用户故障——驳回成立。 |
| 定时任务开关显示与数据矛盾 | 前提不成立：`harmony/entry/src/main/ets/platform_impl/AgentCronStore.ets` L48 `AGENT_CRON_EXECUTION_AVAILABLE = true`，`SettingCronTasksPage.ets` L26 据此启用开关。原文「可用性常量固定为 true」核实无误——驳回成立。 |
| 两个死组件使用未导入的 INK | 属实：`components/ui/ExportDialog.ets` L4 只导入 `INK2,INK3,...` 却在 L19 `fontColor(INK)`；`components/ui/WorkspaceStyle.ets` L6–9 导入集不含 `INK` 却在 L95 `fontColor(INK)`。两者全仓无 `import` 消费方（仅注释提及），死码维护债——驳回成立。 |
| 字号写入/读取类型不匹配 | 本次未复核 SDK/OpenHarmony 原生实现，沿用原报告结论（`静态确定`→已驳回），不作为本次新证据。 |

### 6.2 覆盖表（coverage.md）来源声明抽检

- `main_pages.json` 实测 `src` 数组 **74** 项，`pages/Index` **不在注册表**（与覆盖表 §2「Index 未注册」一致）。
- `pages/MemoryPage`、`pages/PromptTemplatePage`、`pages/HotListSourceSettingsPage` 均在注册表（与「注册但 0 入边/被取代」一致）。
- `pages/AssistantListPage` 全仓除自引用外**无外部 caller**（与「Assistant 整族孤岛、族根无入口」一致）。
- 声明：以上仅验证覆盖表的**结构性断言**；覆盖表列出的「全文/仅扫描」为历史报告自述，本次**未**逐页重读 74 页，不为其覆盖深度背书，亦不冒称本次完成页面级全读。

### 6.3 附录（原报告 §5）抽检（不改判定，只确认代码事实）

- `HistoryPage.ets`：L39 `selecting=false`，L168–170 `actionText/onAction` 均指向 `exitSelecting()`，全文无 `this.selecting = true` → 「选择」入口不可达属实（F-14）。
- `ChatMessageTools.ets` L39–49：对 output 文本 `toLowerCase()` 后 `includes('error'|'exception'|'failed')` 即判 `Failed`，不读结构化字段 → F-15 属实。
- `QuickMessagesPage.ets` L9 `const DANGER='ERROR'` 作 `backgroundColor`（非法逻辑色字面量），L155 白字落在该背景 → 附录所述「近乎不可见」的事实成立（呈现需设备）。
- `BoardPage.ets` L235–249 `openOriginal` 两个分支都提示「已复制链接」但函数内无剪贴板写入 → 反馈失真属实。
- `ChatAssistantsPage.ets` L243–245 名称为空静默 `return` → 静默保存属实。
- `SearchPage.ets` L341–342 / `CouncilRunPage.ets` L159：返回仅有 icon 本身、无 `constraintSize minHeight 44` 热区包裹 → 「返回键命中区 <44」的代码事实成立（实际命中需设备）。

---

## 7. 本次未做 / 不能声称

1. 无设备：未启动模拟器、未安装 HAP、未截图、未 UI 注入；`hdc list targets` 为 `[Empty]`。
2. 无构建/无 lint：本次为只读复核，未跑 `assembleApp`/`hvigorw`/codelinter；不据此声称可编译或不可编译。
3. 无帧率/耗时/耗电实测：F-07/F-08/F-09/F-10 只给规模与复杂度，**不含**任何 ms / fps 数字。
4. 不判实际裁切/出屏/命中：F-01/F-02/F-03/F-04/F-05/F-06 的呈现面保持 `需设备`；几何层只陈述元素尺寸声明与算式。
5. 未逐页重读 74 页；覆盖深度以 coverage.md 自述为准（来源声明）。
6. 未复核 SDK/原生实现（字号偏好、安全区、IME 默认避让等），这些沿用原报告与深审的既有结论，不在本文新增证据内。
7. 未验证 F-41 之外的运行时时序；F-41 的「闪烁时长」仍属观感，只有「返回整屏 loading + 冷启动双调用」是结构可证。
