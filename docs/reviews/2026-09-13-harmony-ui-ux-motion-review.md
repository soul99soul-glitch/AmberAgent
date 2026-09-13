# AmberAgent Harmony（ArkTS）UI/UX 审查报告

日期：2026-09-13

- 仓库：`/Users/mi/Downloads/AI/AmberAgent-harmony-preview`（分支 `harmony-preview`，工作树含未提交改动）
- 说明：本报告在页面线（A–I）审查与随后多轮独立复核的基础上汇总定稿，结论以复核后的判断与行号为准；正文链接相对最终保存位置 `docs/reviews/`，指向 `../../harmony/...`。
- 路径写法：行号以纯文本 `L…` 书写，不拼进链接锚点。

---

## 0. TLDR 与审查边界

### 0.1 结论摘要

1. **P0：没有已证实项。** 「`ExportDialog`/`WorkspaceStyle` 使用未导入 `INK` → 编译阻塞」经复核撤销为死码维护债（两文件全仓无导入方、不在上次 ArkTS 编译输入与产物、缺陷自 HEAD 起跨多次构建未阻塞）。
2. **P1 共 3 项**：
   - F-20 文件管理「清理孤立文件」在登记库损坏后会全量 `unlink` 真实 upload 文件（真数据损坏）；
   - F-30 深度阅读取消/离页后再进入会隐式发起新一轮深读并 REPLACE 现行任务（高 token 核心流程）；
   - F-43 WebMount `wm_open` 开页 5s 后确定性失败，整块站点浏览链不可用（已独立复现）。

   其余所有条目最高 P2。
3. **大量初判已被反证或降级**：字号写入/读取类型不匹配、双入口陈旧显示、恒真 opacity「重播」、防连点「吞点击」、原地状态「失刷」、SectionHeader 系统性错位、安全区两策略「必有一错」、「未设键盘避让即遮挡」、定时任务开关矛盾、生成完成通知无深链等，均不作用于现行用户故障（见 §3.1）。
4. **不可达页面不计现行用户问题**：`Assistant*` 整族（10 页）、`MemoryPage`、`PromptTemplatePage`、`HotListSourceSettingsPage`、仅做跳转的 `Setting*Full` 兼容壳、`Index` 等，其内部缺陷代码成立但用户不可达，统一转维护债（见 §3.2）。
5. **证据状态总口径**：本次审查全程**无在线设备**（`hdc list targets` 为空），**未运行 UI、未截图、未做全量构建**。凡「几何/渲染/键盘/主题/命中率」结论，一律为**源码约束或推算**，不是实测；须与「设备待验」明确区分。

### 0.2 审查边界

- 页面以 `main_pages.json` 注册的 74 个路由页为准；组件覆盖 `components/`、`components/ui/`（44）与 `design/`。页面覆盖证据另见同目录 [2026-09-13-harmony-ui-ux-coverage.md](./2026-09-13-harmony-ui-ux-coverage.md)。
- 只做 UI/UX 面（布局、动效、交互、保存反馈、页面衔接、返回、异步状态）；域内算法、构建签名、用户数据均不在范围。
- 只读：未修改任何产品代码；除下述两次单文件 lint 外，未构建、未 lint、未连设备。
- 覆盖结论中的「57 条有引用入边」是引用入边统计，不是根可达图，不能解读为「57 页全局可达」。

---

## 1. 正式发现

分组：1.A 布局/对齐/密度/触控；1.B 动效/滚动/性能；1.C 交互/保存/反馈；1.D 页面衔接/返回/异步状态。
级别：P1 仅真数据损坏或高成本核心流程/整面板不可操作；P2 可达且成立；P3 低/打磨。
证据状态：`静态确定` = 源码路径闭合；带「待设备/待验」限定（如 `静态确定·裁切待设备`）表示结论成立但具体量值、呈现或触发强度需真机/模拟器实测；`设备待验` = 后果整体依赖运行期。

### 1.A 布局 / 对齐 / 密度 / 触控

**F-01 [P2][静态确定·裁切待设备] NovelWorkspacePage 两个页签 pane 用 `height('100%')`，占高超出可用区风险**
- 位置：[harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets](../../harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets) `chapterPane` L1690、`chapterReader` L1735、`materialLibraryPane` L1841、`settingsPane` L1872；正确对照 `chatPane` L1529–1533 用 `layoutWeight(1)`。
- 场景：进入小说工作区，切「正文」或「设定」。
- 后果：pane 百分比按「最近固定大小祖先」（整屏高）解析，而非父容器扣掉顶部 chrome 后的剩余空间，pane 占高因此大于可用区，底部内容存在被裁出可视区的风险（具体裁切量、是否仍可滚出，需设备量测）。章节目录末章、正文底部、资料库底部按钮/滚动区受影响。
- 建议：`height('100%')` 改 `layoutWeight(1)` 吃剩余空间；底部是否还需额外导航条内距，须先在设备确认该页安全区避让语义后再定（不宜无条件叠加）。

**F-02 [P2][静态确定·渲染细节待设备] `PaperSeparator` 同时 `width('100%')` 与左右 margin 16，占位宽超出父容器**
- 位置：[harmony/entry/src/main/ets/components/PageTemplate.ets](../../harmony/entry/src/main/ets/components/PageTemplate.ets) L118–126；同型复制 [harmony/entry/src/main/ets/pages/SkillsPage.ets](../../harmony/entry/src/main/ets/pages/SkillsPage.ets) L155。
- 依据：交叉轴上「子宽 + margin = 整体」，左右各 16 的 margin 使该元素占位为父宽 + 32；设计注释自述目标是「左右内缩 16 的 1px 分隔线」，实现与之相反。
- 场景/后果：`PaperSeparator` 被多个设置与列表页面共享。发丝线可能被裁（两端不齐）、退化为全出血线，或与嵌套 `padding 16` 容器正好铺满外沿而与行文字不对齐。具体呈现属渲染细节，需截图；「占位超出父宽」是确定的几何事实。
- 建议：外层 `Row().width('100%').padding({left:16,right:16})` 内放 1px 线；SkillsPage 删除本地副本改用共享组件。

**F-03 [P2][静态确定] ChatInput 展开附件后把输入框挤到几乎为零宽（窄屏）**
- 位置：[harmony/entry/src/main/ets/components/ChatInput.ets](../../harmony/entry/src/main/ets/components/ChatInput.ets) attach 固定宽 L177（展开 136）、外框 L183–184、margin L192；中间 TextArea `layoutWeight(1)` L199，`constraintSize` 只有 `minHeight`（L208/L214）**无 minWidth**；发送键 L227/L240 固定 44；整行 padding L268 为 20/20。
- 场景：≤320–360vp 窄屏点「+」展开拍照/图片/文件动作组。
- 后果：`layoutWeight(1)` 只能分配剩余空间，固定件合计逼近屏宽时输入框剩余宽度趋零，文字几乎不可见/无法编辑。算式静态成立，具体 vp 需窄屏实机确认。
- 建议：展开态给输入框 `constraintSize({minWidth:96})`，或收起中间胶囊、改浮层/Sheet 呈现三个动作。

**F-04 [P2][静态确定·表现待设备] 分享面板锚点写死像素坐标**
- 位置：[harmony/entry/src/main/ets/pages/ChatPage.ets](../../harmony/entry/src/main/ets/pages/ChatPage.ets) L2663 `anchor: { windowOffset: { x: 660, y: 2200 } }`。
- 场景：消息动作 → 分享（Share Kit）。
- 后果：锚点按某台机器分辨率写死；更小屏/不同密度/横屏下锚点越出窗口，popover 可能贴屏外或错位。硬编码本身即缺陷，具体表现需设备。
- 建议：改为在触发时按实际点击位置计算锚点（或省略锚点用默认居中），避免写死像素坐标。

**F-05 [P2][静态确定] DeepRead 文章时间轴竖线固定 54vp，事件多行时轨道断裂**
- 位置：[harmony/entry/src/main/ets/pages/DeepReadArticlePage.ets](../../harmony/entry/src/main/ets/pages/DeepReadArticlePage.ets) L681–689（`Stack().width(18).height(54)`），右侧 `MarkdownText` L693–699。
- 场景：任一 timeline 事件正文超过约 2 行。
- 后果：竖线只覆盖文本上半段，圆点/连线浮在中间，时间轴视觉断裂；该页本身是长文阅读场景。
- 建议：竖线高度动态化（`Row` 内 `Column` 撑满行高，或按行数估算）。

**F-06 [P3][静态确定] 可达设置类页面的顶栏标题水平起点与总高规格不一致**
- 位置（可达同语义设置/管理页）：`PageTitleBar`（多页共享，title.x≈60）、[harmony/entry/src/main/ets/pages/ChatMcpSettingsPage.ets](../../harmony/entry/src/main/ets/pages/ChatMcpSettingsPage.ets) L549–584（≈72）、[harmony/entry/src/main/ets/pages/ChatAssistantsPage.ets](../../harmony/entry/src/main/ets/pages/ChatAssistantsPage.ets) L346–356（≈66）、[harmony/entry/src/main/ets/pages/ChatProviderDetailPage.ets](../../harmony/entry/src/main/ets/pages/ChatProviderDetailPage.ets) L319–371（≈66）、[harmony/entry/src/main/ets/pages/SearchPage.ets](../../harmony/entry/src/main/ets/pages/SearchPage.ets) L341–346（≈44）；对应顶栏总高在这些页面间相差约 15vp。
- 场景/后果：同语义设置页之间切换时，标题左基准与首行内容起始 y 不完全一致，属一致性/打磨问题（非功能缺陷）；不声称所有页面标题都会抖动。
- 建议：以 `PageTitleBar` 为基准收敛这些页的内距，或抽 `tokens` 顶栏常量。

### 1.B 动效 / 滚动 / 性能

**F-07 [P2][静态确定·量化待设备] 个人中心热力图每格重算并全量排序**
- 位置：[harmony/entry/src/main/ets/pages/ProfilePage.ets](../../harmony/entry/src/main/ets/pages/ProfilePage.ets) L590（`ForEach(this.heatmapDates(),…)` 方法每 build 重生成 53×7=371 个 Date）、L596 每格调 `heatColor(heatAlphaForDay(...))`、L631–641 每格执行 `Array.from(dayCounts.values()).filter().sort()`。
- 场景：打开个人中心（`dayCounts.size>0`）时。
- 后果：单次渲染约 371 次数组拷贝+过滤+排序，活跃天数越多越重，集中在 build 主线程 → 可能明显卡顿/掉帧，随使用时长单调恶化。量化首帧耗时需设备。
- 建议：`loadStats` 完成时一次性算好热力值存入 `@State`，build 只读；日期数组同样缓存。

**F-08 [P2][静态确定·体感待设备] 会话首页冷启动重复全量刷新 + 每会话并发 RDB 读 + O(N²) 预览回填**
- 位置：[harmony/entry/src/main/ets/pages/ChatListPage.ets](../../harmony/entry/src/main/ets/pages/ChatListPage.ets) `onPageShow` L60–69 与 `aboutToAppear` L71–82 都调 `refresh()+loadDisplaySetting+consumeDeepLink`；L139–166 未 await 的 `repo.getById` 循环（无并发上限）；L157–165 逐条复制全量 key 重建 `previews/msgCounts`。
- 场景：冷启动或每次回到首页，会话较多时。
- 后果：首次进入 `repo.list()` 跑两遍、约 2N 并发读、单次刷新 O(N²) 键拷贝/状态赋值；列表首帧后仍在后台跑，预览先「暂无消息」后替换（闪动），长列表滚动掉帧。
- 建议：二选一保留加载入口 + 首帧跳过标记；预览改批量接口或限首批条数；`Record` 一次构建新对象。

**F-09 [P2][静态确定] ChatMessageAvatar 对非流式消息也常驻 525ms 定时器**
- 位置：[harmony/entry/src/main/ets/components/ChatMessageAvatar.ets](../../harmony/entry/src/main/ets/components/ChatMessageAvatar.ets) L19–24（条件只有 `role !== 'user'`，与 `streaming` 无关）、L41–46（光标仅 `streaming` 时渲染）、清理 L26–31 正确。活链：components/AgentMessageRow.ets L433 每条消息实例化一次 → [harmony/entry/src/main/ets/pages/ChatPage.ets](../../harmony/entry/src/main/ets/pages/ChatPage.ets) 时间线 `ForEach` L4348–4410 逐条渲染（AgentMessageRow 引用处 L4408–4410）。
- 场景：长会话视口内静止/滚动时。
- 后果：非流式时视口内每条助手消息各跑一个 525ms 定时器并翻转 `@State cursorOn`，约 2N 次/秒无效状态翻转与重渲染，挤占主线程（量化帧率需设备）。
- 建议：仅 `streaming` 时启动定时器，加 `@Watch('streaming')` 起停；或改用 `animateTo({iterations:-1,playMode:Alternate})` 去掉定时器。

**F-10 [P2][静态确定·性能量级待设备] 小说工作区时间线全量渲染 + 进入/返回强制滚底**
- 位置：[harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets](../../harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets) `novelTimelineRows` L1326–1347（全表进 `ForEach`，无尾窗/分页；对照 ChatPage L994 `getConversationTailWindow`）；`reload` L274 `initialScrollArmed` + L1463–1474 → `scheduleNovelScrollToBottom` L797–805；`attachNovelRun` L927；闪断 `activeMessages=[]` L920。
- 场景：项目对话较多时进入工作区，或生成中返回。
- 后果：首帧构建全部 `AgentMessageRow`，进入迟滞（量级需真机大项目实测）；每次进入被强制拉到最后一条，长文回看场景丢失阅读位置；生成中返回气泡闪断一次。
- 建议：接入 Chat 侧同款尾窗 + 按需前插；纯回看时不强制滚底，仅在 `activeRunId !== ''` 或首帧已在底部时跟随。

**F-11 [P3][静态确定·运行体验待验] SearchPage 即输即清空，结果列表塌成居中转圈**
- 位置：[harmony/entry/src/main/ets/pages/SearchPage.ets](../../harmony/entry/src/main/ets/pages/SearchPage.ets) L251–253 `loading=true; searched=true; results=[]`（早于 L254 起的 300ms debounce）；渲染 L364–368。
- 场景：已有一条结果后再敲一个字。
- 后果：已读一半的结果消失、列表塌成居中 loading，滚动位置重置，反复闪动；与状态切换时的垂直跳变（`layoutWeight(1)` 居中 vs 空/失败态 `margin top:64`，L364–368/L369–384）叠加。清空行为为代码事实，闪烁节奏与丢滚动位置属观感推断，需实机节奏确认。属可变体验问题，非数据或功能损坏，定级 P3。
- 建议：保留旧结果（stale-while-revalidate：降透明度或顶部细进度条）直到新结果就绪；四态统一锚点。

**F-12 [P3][静态确定] 子代理/议会阶段标签非时间驱动，长任务看起来卡住**
- 位置：[harmony/entry/src/main/ets/components/ChatMessageSubAgentStep.ets](../../harmony/entry/src/main/ets/components/ChatMessageSubAgentStep.ets) L77–81（`Date.now()/5000 % phases`，无定时器/时间源）；[harmony/entry/src/main/ets/components/ChatMessageCouncilStep.ets](../../harmony/entry/src/main/ets/components/ChatMessageCouncilStep.ets) L86–102（`startPhaseRotation` 空实现，注释「ArkUI 不支持 setInterval」前提不成立）；[harmony/entry/src/main/ets/pages/CouncilRunPage.ets](../../harmony/entry/src/main/ets/pages/CouncilRunPage.ets) L110–116/L165（1200ms 无条件轮转 `phase`）；`ModelCouncilRun` 无 `phase` 字段。
- 场景/后果：运行中子代理/议会卡片阶段文案与状态点长时间静止或按固定节奏假跳，与同屏真实 `statusText` 自相矛盾，用户误判卡死/提前完成。
- 建议：用 `setInterval` 驱 `@State tick`（运行中启动、离页清理）；议会阶段改为由真实数据推导，无可靠字段时只显示状态动词。

**F-13 [P3][静态确定] 问候句定时器在 `onPageHide` 未清理，被覆盖页持续空转**
- 位置：[harmony/entry/src/main/ets/pages/ChatPage.ets](../../harmony/entry/src/main/ets/pages/ChatPage.ets) `greetTimer` L889–894（3200ms，回调内 `animateTo` 改 `greetOpacity/greetIdx`，无可见性守卫）；`onPageHide` L767–775 只清 `cursorTimer`；`greetTimer` 仅 L687–690 `aboutToDisappear` 清。
- 场景：生成中点开设置等子页（ChatPage 被覆盖未销毁）。
- 后果：隐藏页每 3.2s 无效 `animateTo`+状态写入（功耗/主线程唤醒）；返回时可能停在中间态。
- 建议：`onPageHide` 一并 `clearInterval`，`onPageShow` 重挂，对齐 `cursorTimer` 现有模式。

### 1.C 交互 / 保存 / 反馈

**F-14 [P2][静态确定] 历史页「选择」按钮写反，多选/导出整链不可达**
- 位置：[harmony/entry/src/main/ets/pages/HistoryPage.ets](../../harmony/entry/src/main/ets/pages/HistoryPage.ets) L39 `selecting=false`、L101–104 `exitSelecting`、L166–170 `actionText` 与 `onAction` 均指向 `exitSelecting()`；全文无任何 `this.selecting = true`。
- 场景：历史记录页点右上角「选择」。
- 后果：`selecting` 永为 false → 点击无反应，勾选 `toggleSelect`（L90）与底部「导出 MD/JSON」操作条（L211）永不出现；宣称的多选导出全部不可用。
- 建议：`onAction` 改为 `this.selecting ? this.exitSelecting() : (this.selecting = true)`，并给选中行加背景高亮。

**F-15 [P2][静态确定] 工具执行状态用输出子串猜，成功结果会被误标「失败」**
- 位置：[harmony/entry/src/main/ets/components/ChatMessageTools.ets](../../harmony/entry/src/main/ets/components/ChatMessageTools.ets) L39–49：对 output 文本 `.toLowerCase()` 后 `includes('error'|'exception'|'failed')` 即 `Failed`，其余 `Succeeded`；不读结构化字段。
- 场景：`search_web` 搜到讲 error handling 的文章、`scrape_web` 抓到含 failed 的正文、`file_*` 读到源码/日志、`terminal_*` 正常输出含这些词。
- 后果：成功的工具调用渲染成红色「失败」胶囊 + ✕ 徽章，用户误判任务失败并重复催促/重试。Android 基准优先读结构化 JSON，Harmony 版未做。
- 建议：优先解析结构化 `status/ok/exit_code`；文本扫描仅在无结构化字段且词边界匹配时降级启用。

**F-16 [P2][静态确定] 显示设置中「当前设置页可见、渲染层零消费」的一批开关改了无效**
- 位置（消费侧）：[harmony/entry/src/main/ets/components/MarkdownText.ets](../../harmony/entry/src/main/ets/components/MarkdownText.ets) L646–722（代码块写死横向滚动、无行号、字号/字体字面量）、[harmony/entry/src/main/ets/components/AgentMessageRow.ets](../../harmony/entry/src/main/ets/components/AgentMessageRow.ets) L180–195（思考态自算）。
- 纳入范围（仅列**当前设置页真实显示且在渲染层无消费**的键）：`showAssistantBubble`、`showModelName`、`showModelIcon`、`sendOnEnter`、`pasteLongTextAsFile`、`codeBlockAutoCollapse`、`showLineNumbers`、`fontSizeRatio`。**不泛化**其余存储字段。
- 场景/后果：用户改动上述项（尤以字号比例、代码自动折叠、显示行号、助手气泡高频可感知）后聊天渲染毫无变化，形成「设置看似生效实则无效」的信任损伤。其中 `showModelIcon` 的标签错位与死开关另见 F-16a。
- 建议：ChatPage 装载 `DisplaySetting` 后下发到 `MarkdownText`/`AgentMessageRow`，或从设置页下线未接线项。
- **F-16a [P2] 相关子项：设置页「显示用户头像」开关实际绑定的是模型图标字段** [harmony/entry/src/main/ets/pages/SettingDisplayPage.ets](../../harmony/entry/src/main/ets/pages/SettingDisplayPage.ets) L106–110「显示用户头像」实际 `checked: this.setting.showModelIcon`，而 `showModelIcon` 全仓无读取/渲染点 → 点击无任何可见变化；用户头像 `userAvatar` 不受任何开关控制始终显示。建议改绑或新增真正控制用户头像的开关。

**F-17 [P3][静态确定] ask_user 单问题时问题文本重复显示两遍**
- 位置：[harmony/entry/src/main/ets/components/ChatMessageAskUserStep.ets](../../harmony/entry/src/main/ets/components/ChatMessageAskUserStep.ets) `heading()` L169–174（`qs.length===1` 返回 `qs[0].question`），同页 L41–43 渲染 heading、L45–52 又 `ForEach` 渲染同一 `Text(q.question)`。
- 场景/后果：Agent 只问 1 个问题时，同一句先以 accent 小标题、再以正文出现，视觉冗余像渲染错位。
- 建议：单问题时 heading 返回固定文案或仅 `qs.length>1` 时渲染标题行。

**F-18 [P3][静态确定] MiniAppChatCard「⋯」菜单只能长按，点按无反应**
- 位置：[harmony/entry/src/main/ets/components/MiniAppChatCard.ets](../../harmony/entry/src/main/ets/components/MiniAppChatCard.ets) L409–418 `.bindContextMenu(this.cardMenu(), ResponseType.LongPress)`，该 Row 无 `onClick`；同卡「运行」「修改」为点按。
- 场景/后果：用户点「⋯」期待弹出「查看源码/导出 HTML/版本历史」却无反应，需猜到长按；交互范式不一致。
- 建议：改 `.bindMenu(this.cardMenu())` 或另加 `onClick`，长按可保留。

**F-19 [P2][静态确定·持久化需设备] SettingSlidesFontPage 整页「假保存」：完成=返回，设置不落盘**
- 位置：[harmony/entry/src/main/ets/pages/SettingSlidesFontPage.ets](../../harmony/entry/src/main/ets/pages/SettingSlidesFontPage.ets) L21–23 `placeholderLoadFontSettings()` 返回常量、L32–37 填本地 `@State`、L39–47 setter 只改 `@State`、L49–51 `done()` 仅 `router.back()`、L117 `actionText:'完成'`；全文件无任何持久化 API。
- 场景：技能页 → Slides 字体资源 → 改字体族/字号 → 点「完成」→ 重进恢复默认。
- 后果：「完成」有明确提交语义却不保存，生成式幻灯片字体设置永远无效；`PillSelect` 选的字体族在预览也仍用 `FONT_SANS`（L144–148），预览与选择不一致。
- 建议：接真实存储并在保存成功后 back；或下线入口登记为未移植，去掉「完成」提交语义。

**F-20 [P1][静态确定·破坏范围待设备] 文件管理「清理孤立文件」：登记库损坏 → 空快照 → 无确认全量 unlink 真实文件**
- 位置：[harmony/entry/src/main/ets/pages/SettingFilesPage.ets](../../harmony/entry/src/main/ets/pages/SettingFilesPage.ets) `reload` L111–128、`cleanOrphans` L157–195（`known` 仅由 `this.rows` 构建 L163–164，`fileIo.unlinkSync` L183，守卫仅 `!this.loaded` L159，触发是单击文本、无 AlertDialog/无撤销 L205–208）。
- 机制说明：单次数据库查询失败并不导致误删（底层 `ManagedFileStore.readShape` L44–71 对失败一律返回空表、不会向上抛错）；真实危险路径是 `managed_files.json` 损坏/缺失时 `readShape` 静默返回空表 → `rows=[]` → `known=[]` → 把 `filesDir/upload/*` 全部当孤儿删除，而该目录确有真实受管文件（`Base64ImageSupport.ets` L60–93、`McpFilesStore.ets` L39–68）。
- 后果：不可逆数据丢失。`skills/` 目录结构大概率因 `unlinkSync` 对目录失败被 catch 侥幸幸存；`chat_images/`、`images/` 无物理写入方。破坏范围需设备确认。虽然需要登记库损坏/缺失这一前提，但一次常规「清理孤立文件」操作即可不可逆删除仍被引用的附件，因此按数据丢失问题优先处理。
- 建议：加载失败置可区分 error 态并禁用清理；清理前用 AlertDialog 列出将删除的数量与目录。

**F-21 [P2][静态确定·返回手势需设备] SubAgent/ModelCouncil「展开编辑、折叠才保存」：编辑后直接返回静默丢保存**
- 位置：[harmony/entry/src/main/ets/pages/SettingSubAgentPage.ets](../../harmony/entry/src/main/ets/pages/SettingSubAgentPage.ets) L237–245（展开态再点同行才 `saveSetting()`，非展开态仅置 `expandedRoleId`）、L304–316 只写 `@State`；[harmony/entry/src/main/ets/pages/SettingModelCouncilPage.ets](../../harmony/entry/src/main/ets/pages/SettingModelCouncilPage.ets) L259–266、L309–324 同构；两页无 `aboutToDisappear` flush、无脏标记、无「保存」按钮。
- 机制收窄：`saveSetting()` 从**整份 `@State`** 序列化，切行后只要再触发任意一次保存点（折叠、动滑块/开关/SelectRow），原编辑会一并写盘 —— 「切行即永久丢」不成立；真正丢的是「编辑后未触发任何保存点即系统返回/`router.back()`」。
- 后果：提示词/席位名等关键 Agent 行为配置在直接返回时无提示丢失，界面只有 ▸/▾ 无「保存」字样，用户无从知道「收起=提交」。
- 建议：展开区加显式「保存/取消」；`aboutToDisappear` flush；至少加未保存提示。

**F-22 [P2][静态确定] ChatMcpSettingsPage 滑动删除 MCP 服务器无确认、无撤销**
- 位置：[harmony/entry/src/main/ets/pages/ChatMcpSettingsPage.ets](../../harmony/entry/src/main/ets/pages/ChatMcpSettingsPage.ets) `deleteServer` L94–98、`deleteAction` L455–468、swipeAction L595。
- 场景/后果：左滑点「删除」即 `updateMcpServers(filter)`，服务器与已配置请求头（含 Authorization token）立即消失且不可恢复；同仓 SkillsPage/CronTasksPage/FilesPage 同类删除均走 AlertDialog，属不一致。
- 建议：复用 `AlertDialog.show`（带服务器名）或接二次确认；补 `accessibilityText`。

**F-23 [P3][静态确定] ChatMcpSettingsPage 名称为空时「保存」静默 no-op**
- 位置：[harmony/entry/src/main/ets/pages/ChatMcpSettingsPage.ets](../../harmony/entry/src/main/ets/pages/ChatMcpSettingsPage.ets) L104 `if (draft.commonOptions.name.trim().length === 0) return;`；保存按钮 L486 无 `enabled`/无错误文案。
- 场景/后果：新建服务器名称留空点「保存」→ 无任何反应、sheet 停留，用户判定应用卡死；对比 ChatModelParamsPage 有行内 ERROR 文案。
- 建议：保存按钮加禁用态或 toast「请填写名称」，名称输入行加错误边框。

**F-24 [P2][设备待验] ChatMcpSettingsPage 请求头 onChange 内 trim 后回写 `TextInput({text})`，空格可能被吃**
- 位置：[harmony/entry/src/main/ets/pages/ChatMcpSettingsPage.ets](../../harmony/entry/src/main/ets/pages/ChatMcpSettingsPage.ets) name L353–363、value L364–374（`v.trim()` → `patchCommon` 换新 `draft` → 重新渲染 `TextInput({ text: ... })`）。
- 场景：在「值」里输入 `Bearer token123`，敲到 `Bearer ` 时尾部空格被 trim 回灌，后续字符/光标可能错位或丢空格。
- 后果：请求头值内容可能不对，用户看到光标跳动，属「输入被悄悄改写」。trim-on-write 为代码事实，ArkUI 单向 `text` 回写的光标/空格行为需设备确认。
- 建议：草稿层保留 raw，仅在 `confirmDraft()` 时 trim；或改 `TextInput({text: $$this.x})` 双向绑定。

**F-25 [P2][静态确定] SettingBackupPage 导出「取消」被当作成功：落应用私有目录并报「已导出」**
- 位置：[harmony/entry/src/main/ets/pages/SettingBackupPage.ets](../../harmony/entry/src/main/ets/pages/SettingBackupPage.ets) L98–107（`uris.length>0` 取 else 落 `${filesDir}/backups/...`）、L112–113 无条件 `statusMsg='已导出…'`+toast、页面无路径回显 L360–362；恢复入口 L173–197 走系统选择器，找不到私有目录文件。
- 场景：点「导出全部会话」→ 在系统保存面板按返回/取消。
- 后果：用户误以为已保存到可见位置，实际备份在应用私有目录（卸载即丢）且报「已导出」，恢复入口找不到。附带：`restoreSettings` 逐会话 `repo.save` 无事务（半恢复，L237–249）；`passphrase` 在导出 L274 与恢复 L204 间复用同一 `@State` 且未标示。
- 建议：`uris.length===0` 视为取消（不弹成功）；回退私有目录必须回显完整路径并说明「仅本应用可见」。

**F-26 [P2][静态确定] SelectRow「点击即循环取值」不可发现，Slider 拖动逐帧落盘**
- 位置：[harmony/entry/src/main/ets/components/PageTemplate.ets](../../harmony/entry/src/main/ets/components/PageTemplate.ets) L434–496 `SelectRow` 不绘制 chevron；[harmony/entry/src/main/ets/pages/SettingSubAgentPage.ets](../../harmony/entry/src/main/ets/pages/SettingSubAgentPage.ets) L130–142 与 [harmony/entry/src/main/ets/pages/SettingModelCouncilPage.ets](../../harmony/entry/src/main/ets/pages/SettingModelCouncilPage.ets) L125–137 点击取 `options[(idx+1)%n]`（无菜单/无候选集提示）；SubAgent L120–128、Council L110–123 Slider `onChange` 内直接 `saveSetting()`。
- 场景/后果：想「选择」超时时间只能盲目轮转 1→3→5→10，无法知道错过了哪个值；拖动滑块一次 2→8 触发 7 次 `get+update` 读改写，放大写入并可能并发互相覆盖。
- 建议：SelectRow 加候选弹层并补 chevron；Slider 的落盘时机改为「拖动结束提交」而非逐帧提交（或在拖动过程中做防抖），以降低读改写放大与并发覆盖。

**F-27 [P2][静态确定·副作用待设备] MiniAppRunner 图片预取后整页 `refresh()`，小应用二次加载、onload 副作用执行两遍**
- 位置：[harmony/entry/src/main/ets/pages/MiniAppRunnerPage.ets](../../harmony/entry/src/main/ets/pages/MiniAppRunnerPage.ets) L230–231 守卫、L539–542 `onPageEnd → prefetchImagesAndReload()`、L262–268 `if (fetched.count>0) this.controller.refresh()`。
- 场景：运行任何 HTML 含 https 图片的小应用。
- 后果：首次必重载一次；在 onload/首帧执行副作用的小应用（`host.sendToConversation`/`createArtifact`/`ai.generate`）可能重复提交、重复计费，滚动/交互状态重置。
- 建议：预取完成前不发首帧，或仅在确有未命中图片时 reload 并提示。

**F-28 [P2][静态确定] 小说工作区「删除章节」「删除资料」无二次确认，且不可恢复**
- 位置：[harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets](../../harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets) 章节删除入口 L1711–1713、执行 `deleteChapter` L1155–1167；资料 L1820–1822、`deleteMaterial` L1205–1215；无 AlertDialog。对照：项目删除有确认，见 [harmony/entry/src/main/ets/pages/NovelProjectsPage.ets](../../harmony/entry/src/main/ets/pages/NovelProjectsPage.ets) L394–429。
- 场景/后果：误触即永久删除整章正文（删除与「编辑」仅隔 margin 8），资料同样；版本历史不覆盖手工删除，无可恢复入口。
- 建议：复用 ConfirmDialog，文案给出章节名/字数与不可撤销提示。

**F-29 [P2][静态确定·观感待设备] editorial_slant 模板下 A-/A+ 与字体模式是死控件**
- 位置：[harmony/entry/src/main/ets/pages/DeepReadArticlePage.ets](../../harmony/entry/src/main/ets/pages/DeepReadArticlePage.ets) 顶栏缩放按钮 L389–399、`zoomOut/zoomIn` L306–313 只改 `fontScale`；模板分支 L464–479 用 `renderEditorialSlantHtml` 直渲染 Web，不消费 `fontScale/fontSerif`（原生分支 L515/L569/L695 才用）。
- 场景：设置「文章模板」选 `editorial_slant` 后打开文章。
- 后果：A-/A+ 有按钮但文字大小不变，用户反复点击无反馈；衬线模式同样被忽略。
- 建议：模板模式隐藏/禁用缩放按钮，或把 `fontScale` 注入模板 HTML（CSS 变量）。

**F-30 [P1][静态确定·需设备确认一次] DeepRead 取消/离页后再进入会隐式发起新一轮深读并 REPLACE 现行任务**
- 位置：[harmony/entry/src/main/ets/pages/DeepReadArticlePage.ets](../../harmony/entry/src/main/ets/pages/DeepReadArticlePage.ets) `cancelGeneration` L282–288（只 abort+`running=false`，不落 COMPLETE）；`loadArticle` L133–142 仅 `isComplete` 才短路、否则 L171–176 `scheduler.run(topicId,title,{force:false,seedUrl})`；一次性首用门 L152–156；取消态 `cancelled/errorMsg` 为页内 @State 不持久化。[harmony/deepread/src/main/ets/agent/run_manager.ts](../../harmony/deepread/src/main/ets/agent/run_manager.ts) L293–308 非 force 未完成即重走 collect/plan/write；[harmony/deepread/src/main/ets/agent/scheduler.ts](../../harmony/deepread/src/main/ets/agent/scheduler.ts) L122–131（新建 job + REPLACE：abort + `waitForAbort` + 注册新轮）、`waitForAbort` L108–115（5s 兜底）；页面无 `scheduler.isRunning(` 调用。
- 场景：(a) 用户点「取消生成」后返回、再从历史/热榜/通知进入同一 topicId；(b) 生成进行中按返回、立刻重开同一文章（离页不 abort，仅退订 L116–127）。两者都会在重进时经 `loadArticle` 隐式发出新的 `run`，并对同 topicId 的现行任务执行 REPLACE。
- 后果：深度阅读是高 token 消耗功能（页面自身文案 L414 承认），用户取消/离开后再进入会被隐式重新发起请求，产生潜在额外费用；`waitForAbort` 最长 5s 期间页面可能停在「收集来源 第1/6 步」。缓存已完成段落经 `applyOutput(L136)` 与 `repository.observe` 首回调保留、`writer.initialOutput=repository.get()` 复用旧稿，故不涉及「进度清零」。
- 机制附注（超出 UI 定级范围，仅登记）：若某轮实现不遵守 abort signal，`waitForAbort` 5s 超时后新旧两轮可能同时持有同 topicId，存在并发写同一缓存条目的窗口。
- 建议：`loadArticle` 检测 `scheduler.isRunning(topicId)`（scheduler 已导出 `isRunning`）则复用现有轮次订阅，仅确认无在跑轮次才发新 `run`；取消/中断后进入改为「上次已中断/已取消 → 继续生成」卡片，或 KV 记录取消意图并在重进前确认。
- 复核证据：静态断言脚本 36/36 pass、仓库回归 `scheduler_signal.test.ts` 5/5 pass（确证 REPLACE / `waitForAbort` / token guard 语义；通过不代表 UI 验证）。

**F-31 [P2][静态确定] 聊天页会话重命名入口缺失，`startEditTitle` 零调用者**
- 位置：[harmony/entry/src/main/ets/pages/ChatPage.ets](../../harmony/entry/src/main/ets/pages/ChatPage.ets) `startEditTitle()` 定义 L2010–2016，全文无第二处出现（无调用者）；`editingTitle` 状态 L432；`saveTitle` L2016、`cancelEdit` L2053 与编辑态 UI L4527–4574 均只为该入口服务；标题区点击被绑定为 `toggleModelMenu()` L4560–4564，不进入编辑。
- 场景：用户想在聊天页修改当前会话标题。
- 后果：聊天内无法重命名会话，已写好的标题编辑 UI 全程不可达（死代码）；`editingTitle` 仅由 `startEditTitle` 置真，编辑态永不出现。
- 建议：给标题区一个明确入口（如长按标题或标题旁编辑图标），或在确认无替代入口的情况下移除该段死代码以免误导。

**F-32 [P2][静态确定] 「收进章节」后跳到设定页签且落错二级分段**
- 位置：[harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets](../../harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets) `doCollect` L951–965（L959 只 `this.tab = 2`，注释称「跳到活资料查看建议」）；`settingsSection` 初值 0 L109；`settingsPane` L1845–1873（section 0 → `NovelSettingsPane`，否则 `materialLibraryPane`）；建议渲染在 `materialLibraryPane` L1756。
- 场景/后果：正在读正文时执行「收进章节」，被跳出上下文到「设定」的「工作区设定」而非「资料库」，刚生成的资料建议不在眼前，需再点一次；注释意图与实现不符。
- 建议：`doCollect` 成功后 `tab=2` 且 `settingsSection=1`，或留在正文用 toast/角标提示。

**F-33 [P2][静态确定] 「重新分析」只扫描最后一章，用户以为整项目重扫**
- 位置：[harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets](../../harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets) 入口 L1744–1746；实现 `reanalyze` L1229–1242（L1234 `chapters[chapters.length-1]`，L1235 只传末章 id）。
- 场景/后果：标签「重新分析」无范围限定，实际只重扫末章且丢弃返回的 `warning` 不做提示；用户整本书改动后点它，产生「资料与正文不一致」的隐性错误。
- 建议：标签改「分析最新章节」或提供范围选择，至少 toast 明示范围。

**F-34 [P2][静态确定·需构造数据] 助手绑定模型会覆盖全局选择，但模型菜单仍显示全局选择**
- 位置：运行时优先级 [harmony/entry/src/main/ets/di/AppContainer.ets](../../harmony/entry/src/main/ets/di/AppContainer.ets) L1003–1021（`assistant.chatModelId` 非空即优先返回、短路全局选择）；展示/写入只认全局 [harmony/entry/src/main/ets/pages/ChatPage.ets](../../harmony/entry/src/main/ets/pages/ChatPage.ets) L2814–2822/L2840–2849、[harmony/entry/src/main/ets/pages/ChatModelPickerPage.ets](../../harmony/entry/src/main/ets/pages/ChatModelPickerPage.ets) L60–74。
- 场景：打开绑定到「带 `chatModelId` 的助手」的会话（该字段当前可达写入点是备份恢复 `SettingBackupPage.restoreSettings` L237–249）→ 在顶部模型菜单切换模型。
- 后果：切换只写全局键，而实际发消息走助手绑定模型 → 切换静默无效；菜单的「当前」标记仍显示全局所选，与实际生效模型不一致，标签/上下文窗口/推理档位也按错模型显示。
- 建议：菜单与选择器的「当前」与写入改为与运行时同源（有助手绑定模型时以其为准展示），并提供「恢复为跟随全局选择」的清除动作。

**F-35 [P2][设备待验] 三个 ChatAssistant* 页 toggle 采用 load-modify-save，快速连点丢更新**
- 位置：[harmony/entry/src/main/ets/pages/ChatAssistantExtensionsPage.ets](../../harmony/entry/src/main/ets/pages/ChatAssistantExtensionsPage.ets) L84–135、[harmony/entry/src/main/ets/pages/ChatAssistantLocalToolsPage.ets](../../harmony/entry/src/main/ets/pages/ChatAssistantLocalToolsPage.ets) L135–160、[harmony/entry/src/main/ets/pages/ChatAssistantMcpPage.ets](../../harmony/entry/src/main/ets/pages/ChatAssistantMcpPage.ets) L97–123：均基于尚未更新的 `this.assistant` 算 ids，各自 `loadAssistants → 覆盖 → saveAssistants`，最后才更新 `this.assistant`。
- 场景/后果：同一列表内快速连切两个开关，后完成者覆盖前者，改动被静默丢弃；开关 UI 停在乐观动画值，退出重进才发现没存上。仓库既有 `ExtensionPrefs` 的 `sharedKvMutex` 只保护 `mode_injections/lorebooks` 键，未覆盖 assistants blob。
- 建议：写回纳入同一 KV 串行锁；或发起前先本地乐观更新再落库、失败回滚。窗口窄，需实测。

**F-36 [P2][静态确定] 系统访问「通讯录」是不可用死控件，但整页文案承诺「点击未授权项可发起申请」**
- 位置：[harmony/entry/src/main/ets/pages/SettingSystemAccessPage.ets](../../harmony/entry/src/main/ets/pages/SettingSystemAccessPage.ets) L72 `status='acl'`、L94–95 `if (p.status !== 'denied') return;`、L150 整行 `onClick`、L176–179 说明条。
- 场景/后果：点「通讯录 · 暂不可用」→ 无任何反应、无 toast、无解释，与页面自身文案矛盾。附带 L104 申请时硬编码 `READ_CALENDAR` 不用 `p.key`（当前仅 calendar 可达故潜伏）。
- 建议：`acl/exempt` 状态不绑 onClick，或点击弹 toast 说明需在 AGC 后台配置 ACL。

### 1.D 页面衔接 / 返回 / 异步状态

**F-37 [P2][静态确定·命中待设备] 遮罩弹窗内卡片未消费点击 → 点卡片空白即关闭并丢输入**
- 位置：多例同型 —— [harmony/entry/src/main/ets/pages/NovelProjectsPage.ets](../../harmony/entry/src/main/ets/pages/NovelProjectsPage.ets) L378–380 卡片 vs L386–390 遮罩（同时清 `errorMsg`）；[harmony/entry/src/main/ets/pages/MiniAppListPage.ets](../../harmony/entry/src/main/ets/pages/MiniAppListPage.ets) L411–417 vs L420（关闭即 `renameTarget=null`，丢已输入名称/描述；版本历史 L484 同型）；[harmony/entry/src/main/ets/components/MiniAppChatCard.ets](../../harmony/entry/src/main/ets/components/MiniAppChatCard.ets) L344–348 vs L350–352；[harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets](../../harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets) L2061 vs L2062。
- 场景：弹窗打开时点卡片内标题/说明/留白（非输入框、非按钮）。
- 后果：输入型弹窗内容作废、错误提示被静默清除、列表弹窗反复闪关。
- 建议：把遮罩的点击关闭与内容区隔离（用独立遮罩层承接关闭、内容区消费点击），或在内容区加消费点击，避免点到内容空白即关闭。反例参考：`components/ui/ConfirmDialog.ets` L74、`ChatPage.ets` L4930 均在卡片上消费点击。

**F-38 [P2][静态确定] 小说工作区未接管系统返回，弹窗草稿与页内层级被跳过**
- 位置：[harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets](../../harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets) 全文无 `onBackPress`；层级返回仅靠页内按钮（阅读态 L1701–1703）；页面级叠层 `collectDialog` L2030–2063、`chapterEditor` L2065–2094、`materialEditor` L2097–2145、`chapterMenuOverlay` L2147–2168、版本 sheet L2199–2240，均无返回处理。
- 场景：阅读态/设定资料详情/非首屏页签下按系统返回；或编辑章节标题/正文/资料时按返回。
- 后果：直接退出整个工作区，丢失当前 tab、选中章节、展开资料与未保存输入（用户预期「返回=关弹窗/回上一层」）。
- 建议：实现 `onBackPress` 优先级 —— 任一弹窗 open 先关弹窗（编辑类先询问是否丢弃）→ `selectedChapterId!==''` 回目录 → `tab!==0` 回创作页签 → 根层再 `router.back()`（层级返回与弹窗返回合并处理）。

**F-39 [P2][静态确定·触发需设备] 通知深链：sourceUrl 断链窗口 + 前台无响应式消费 + 清键先于导航**
- 位置：
  1. **丢失 sourceUrl（窗口已收敛）**：[harmony/entry/src/main/ets/platform_impl/NotificationNotifier.ets](../../harmony/entry/src/main/ets/platform_impl/NotificationNotifier.ets) `buildArticleWantAgent` L35–45 声明并携带 sourceUrl，但调用点 L151/L156/L162 第三实参恒 `''`；[harmony/entry/src/main/ets/entryability/EntryAbility.ets](../../harmony/entry/src/main/ets/entryability/EntryAbility.ets) `routeFromWant` L89–116 只解析 topicId/title（文件内无 sourceUrl 键）。**纠正**：完整且未过期缓存命中时不会断链（`loadArticle` L136–140 直接渲染缓存并 return，不用 sourceUrl）；真实断链窗口为 **(a) 部分完成**（`generationComplete=false`，通知文案「深度阅读部分完成」，`loadArticle` 走到 L175–176 重跑但 `sourceUrl=''` → `seedUrl:null`）与 **(b) 缓存过期/被清理**（24h TTL，`purgeExpired` 删行 → 全量重跑且无种子）。且**无恢复路径**：缓存 `source_url` 字段虽建列，但唯一写入口 `AppContainer.ets` L186 `sourceUrl: previous?.sourceUrl ?? null` 首建恒 null、`materializeFresh`/`getByUrl` 无生产调用者 → 字段实为死字段。可点通知里 `notifyFailed` 分支无生产调用者，**不可达**，不计入窗口。
  2. **前台点通知无消费者（窗口较广）**：`onNewWant` L84–87 只写 `AppStorage.wantTopicId/wantTitle`；唯一消费者是 `ChatListPage.onPageShow` L60–69 与 `aboutToAppear` L71–82，且无 `@StorageLink/@StorageProp('wantTopicId')` 订阅（轮询式 `AppStorage.get`）。**代码层确定**：只要栈顶不是 `ChatListPage`（如在 ChatPage 或任一子页），回前台只有栈顶页收 `onPageShow`，ChatListPage 不触发 → 深链悬挂直到用户手动回根页才消费（「点通知没反应，过一会儿自己跳」）。**系统是否为同 ability 前台切换给栈顶补发 `onPageShow` 仍需设备确认**。
  3. **清键早于导航且无失败回滚**：[harmony/entry/src/main/ets/pages/ChatListPage.ets](../../harmony/entry/src/main/ets/pages/ChatListPage.ets) L103–109 先 `setOrCreate('wantTopicId','')` 再 `router.pushUrl`，未 await/未 catch；pushUrl reject 时键已清、深链永久丢失；消费被放在 `aboutToAppear`（L81），早于首帧路由就绪。
- 建议：wantAgent 传入真实 sourceUrl 并在 `routeFromWant`/`consumeDeepLink` 转发；`ChatListPage` 用 `@StorageLink('wantTopicId')` 在 onChange 消费；先确认导航成功再清键；或接线 `materializeFresh` 让 `source_url` 真正落库作为后备。

**F-40 [P2][静态确定·实测待设备] ChatPage 自绘覆盖层不参与 `onBackPress`，系统返回直接退出会话**
- 位置：[harmony/entry/src/main/ets/pages/ChatPage.ets](../../harmony/entry/src/main/ets/pages/ChatPage.ets) `onBackPress` L777–781 仅处理 `drawerOpen`（其余 `return false`）；未处理的自绘覆盖层：`modelMenuOpen` L4858–4879、`contextMeterOpen` L4631–4640、`denyDialogToolCallId` L4899–4936。
- 场景：打开模型菜单/上下文占用面板/拒绝工具弹窗后按系统返回或侧滑返回。
- 后果：直接弹掉整个 ChatPage 回会话列表，输入态与滚动位置丢失；与「点遮罩可关」的手势语义不一致。附带：`drawerOpen` 唯一写点 L2713，而 `openDrawer()` L2704 无调用者 → 该分支当前为死逻辑。
- 建议：`onBackPress` 依次判定 `denyDialogToolCallId / modelMenuOpen / contextMeterOpen`，命中则关闭并 `return true`。系统 sheet 的返回行为可能由框架自行消费，不在本项合并断言。

**F-41 [P2][静态确定·需设备往返] NovelProjectsPage `onPageShow` 过度重置（本次未提交改动回归）**
- 位置：[harmony/entry/src/main/ets/pages/NovelProjectsPage.ets](../../harmony/entry/src/main/ets/pages/NovelProjectsPage.ets) `aboutToAppear` L33–38 与新增 `onPageShow` L40–45 均调 `loadProjects()`；`loadProjects` 开头 `if (pageAlive) loading=true` L51–54；build L283–294 loading 时列表完全不渲染。
- 场景：新建/导入项目 → 进工作区 → 返回项目库。
- 后果：冷启动重复加载；每次返回先闪整屏「加载中…」再交错重排，滚动位置丢失，高频往返体验退步。
- 建议：`onPageShow` 仅在已有数据时静默刷新，加 `initialLoad` 标志仅首次置 `loading=true`。

**F-42 [P2][静态确定·闪烁时长待设备] ChatPage 打开既有会话闪空态 hero；CouncilPage 异步加载前误报「模型库为空」**
- 位置：ChatPage [harmony/entry/src/main/ets/pages/ChatPage.ets](../../harmony/entry/src/main/ets/pages/ChatPage.ets) L4836 `if (rows.length===0 && !sending)` 渲染 AMBER hero，`conversationLoading` 全文 0 命中；`rows` 由 `getConversationTailWindow` 异步回调赋值，期间 `sending===false`。CouncilPage [harmony/entry/src/main/ets/pages/CouncilPage.ets](../../harmony/entry/src/main/ets/pages/CouncilPage.ets) L43 `models=[]`、L68–71 `await getModelRegistry().list()`、L235–237 `models.length===0` 即红字「模型库为空」，无 `modelsLoaded`。
- 场景/后果：进既有会话头一两帧见居中「AMBER+问候」再被消息替换，长会话停留更久像会话为空；进议会页首帧闪红字「模型库为空」，慢机停留更久像配置丢失。无骨架/加载门。
- 建议：`convId.length>0` 路径加 loading 门/骨架；Council 加 `modelsLoaded`，仅加载完成且为空才告警。

---

## 2. 正式发现（续）：页面衔接与交互的复核确认

> 编号与第 1 节连续（F-01…F-44），本节为复核后并入的两项正式发现。

**F-43 [P1][静态确定·已独立复现] WebMount `wm_open` 开页在 5s 后确定性失败，整块站点浏览链不可用**
- 位置：[harmony/entry/src/main/ets/platform_impl/WebMountSession.ets](../../harmony/entry/src/main/ets/platform_impl/WebMountSession.ets) `markHostMounted()` L109–111 全仓无调用者；`WebMountHost` L220–240 仅 `onPageEnd`（无 `.onAppear`/`.onControllerAttached`）；`open()` L139–149 在 `!hostMounted && hooks!==null` 时 `onMountNeeded()` 后轮询 50×100ms（5s）再 `throw 'WebMount host not mounted'`。触发链：设置页开启 WebMount → `ChatPage.aboutToAppear` L875–877 绑单例钩子 → 工具组 L2971–2973 注册 → `WebMountTools.ets` L77–95 `wm_open` → `session.open(url)` 抛错，**从未走到 `loadUrl`**。
- 机制：`hostMounted` 是 `private` 且唯一写点即 L109–111（外部无法直写，也无别名或生成物旁路）；`Web` 渲染完成后无人标记，标志恒 false，`open()` 每次稳定 5s 后失败（不依赖网络或时序）。
- 影响范围：`wm_open` 是 WebMount 工具组唯一页面打开入口，其失败使 `wm_extract/wm_click/wm_type/wm_scroll/wm_screenshot/wm_eval/wm_wait/wm_read_page` 全部因 `pageReady=false` / `no page loaded` 不可用 → 站点浏览操控功能**整块不可用**。经复核更正：`wm_tab_open`（仅新建 tab，不含 url）不受影响；`wm_tab_select` 仅对被 `wm_open` 写过 url 的 tab 失败（新建 tab 走守卫正常返回）；`wm_state` 返回 `ready:false` 不抛错。
- 证据：独立内存化校验脚本 `/tmp/amber-webmount-verify.cjs`（mock webview，纯本地）T1–T4 —— T1 复现 `open` 在 `loadUrl` 前抛错；T2 补 `markHostMounted()` 后立即到达 `loadUrl`；T3 新建 tab 不抛；T4 带 url tab 抛错。
- 后果：用户开启 WebMount 后任何「让 Agent 打开/操控站点」的请求 5s 后报 `host not mounted`，功能整块不可用。
- 修复方向：须在**真实 controller 就绪的生命周期**上闭环回填宿主挂载标记（而非在 `onMountNeeded` 回调里直接置真，否则 `Web` 尚未渲染时 `loadUrl` 会作用于未挂载的 controller）。修复后需在设备复测 `wm_open` 能真正加载并截图。

**F-44 [P2][静态确定] 聊天文档附件缺少打开/预览路径**
- 位置：[harmony/entry/src/main/ets/components/ContentAttachmentChip.ets](../../harmony/entry/src/main/ets/components/ContentAttachmentChip.ets) 整个 `build()` L15–33 无任何点击/回调；调用侧 [harmony/entry/src/main/ets/components/AgentMessageRow.ets](../../harmony/entry/src/main/ets/components/AgentMessageRow.ets) L351–376 的 video/audio/document 分支未传打开回调。
- 两种情形（区分对应用户/助手消息）：用户消息里的文档 chip 点击后进入的是编辑，而非用户预期的打开/预览；助手消息里经历史导入产生的附件为纯展示，没有点击行为。共同点是文档附件缺少「打开/预览」的可达路径。
- 条件限定：音视频附件仅在「历史导入数据」条件下才会出现，当前代码路径不生产音视频附件，故不泛化为「当前都会遇到音视频附件」。
- 后果：文档附件 pill 无法按附件语义打开或预览，与可打开的图片内容相比交互不一致。
- 建议：给 `ContentAttachmentChip` 增加可选 `onOpen` 回调并在 `AgentMessageRow` 接线到既有的打开/预览路径；不可打开的类型降级为纯展示并去掉可点视觉暗示。


---

## 3. 驳回与死码维护债（不与正式发现混计）

### 3.1 已驳回，不作为现行用户故障

下表为初判提出、但复核后不成立的项，保留以便读者理解结论取舍；「复核后不予计入的原因」即不列入现行故障的依据。

| 初判项 | 初判结论 | 复核后不予计入的原因 |
|---|---|---|
| 深度阅读字号写入/读取类型不匹配 | P1 静默无效或整页失败 | 以 SDK `@ohos.data.preferences.d.ts` 与 OpenHarmony 原生实现（`preferences_impl.cpp`/`preferences_enhance_impl.cpp`/`js_common_utils.cpp`）核对：`Get` 不做默认值类型比较、null 默认值不触发 401、JS number 以 double 存取读回仍为 number、`parseFloat(number)` 生效 → 字号实际生效 |
| 同一设置两个入口、无 `onPageShow` 导致显示陈旧 | P2 跨页显示旧值 | 两页为各自独立路由（各含 `@Entry`）、彼此无导航、返回即销毁 → 每次进入都会重新读取；仅存在命名/信息架构重复 |
| 恒真 opacity 乘数导致整页重播入场 | 切主题/返回整页重播 | 该透明度无路径写回 0、根节点不重建、`onAppear` 不二次触发 → 不会重播；仅为恒 1 死表达式 |
| 300ms 导航冷却吞掉连点 | P2 静默吞点击 | 属刻意防连点叠页的设计取舍，仅 300ms 内连点命中，未证实用户可感知失败 |
| 过滤逻辑对 `@State` 嵌套对象原地赋值 | P2 状态不同步 | 当前唯一调用点在同一帧内先整体赋值再调用，渲染正确；仅未来单独调用才可能脱钩 |
| `SectionHeader` 叠 padding 造成系统性错位 | P2 普遍错位 16vp | 逐页核对后基本自洽，仅自带 18 gutter 的少数页相差 16vp，属刻意密度差 |
| 两套安全区策略「不可能同时正确」 | 缺陷 | 未扩展安全区的页面靠系统默认避让、已扩展的页面需自补 inset，各有适用；补偿缺口仅存在于主动扩展 BOTTOM 的页面 |
| 收藏页/历史页等缺导航条内距 | 必然遮挡 | 这些页面未调用 `expandSafeArea`，默认布局在安全区内 → 不必然被遮挡 |
| 未显式设置键盘避让模式 | 输入框被键盘遮挡 | 默认避让模式会上抬界面 → 不能由「未显式设置」推出遮挡；仅剩与小说页模式不一致 |
| 定时任务开关显示与数据矛盾 | P1 状态反馈相反 | 该功能可用性常量在本仓固定为 true → 前提场景不存在 |
| 助手详情页 Tab 高亮返回后不复位 | P2 现行 bug | 该页所在助手族无外部入口，用户不可达 |
| 生成完成通知不带深链 | 缺陷 | 属产品意图未定，列为可选增强建议 |
| 议会运行面板流式输出不自动跟滚 | 缺陷 | 属产品偏好，列建议而非缺陷 |

### 3.2 死码 / 维护债（用户不可达，代码问题真实但不计入现行故障）

1. **两个死组件使用未导入的 `INK`**：`components/ui/ExportDialog.ets` L19、`components/ui/WorkspaceStyle.ets` L95 的 `fontColor(INK)` 未在文件内导入；两文件全仓零导入、不在上次 ArkTS 编译输入与产物、该缺陷自 HEAD 起跨多次构建未阻塞 → 死码维护债（一旦被引用或升为页面即触发未解析标识符）。**注意**：单文件 lint 未报该错，因其不覆盖跨模块名解析，不能作为编译通过或失败的证据。
2. **`Assistant*` 整族孤岛（约 2000 行，10 页）**：`AssistantListPage` 无外部 caller，族内 7 页仅互指；唯一可达助手面是 `ChatAssistantsPage`（ProfilePage L510）。内含缺陷成立但不可见：仅改名称就可能按 `modelIds[0]` 静默改写或清空助手模型、多世界书条目被合并进第一个、保存后缺存活守卫导致重复返回、删除记忆无确认且失败静默，以及助手级模型无 UI 入口。与可达的 `ChatAssistantsPage` 字段/行为已分叉。
3. **`MemoryPage` 不可达**：`main_pages.json` 注册但全仓无 `pages/MemoryPage` 入栈边，被 `SettingAgentMemoryPageFull` 取代；加载失败伪装空态、图标热区偏小（14–15vp）两问题随之降为潜伏债。可达侧 `SettingAgentMemoryPageFull` 编辑保存无空值守卫是真实可达的 P3。
4. **`PromptTemplatePage` 不可达**：注册零调用，唯一 KV `'prompt_templates'` 无外部读写方，已被 `ChatPromptsPage` 取代 → 死路由债。
5. **`HotListSourceSettingsPage` 不可达**，与 `SettingDeepReadPage.ets` L448–452 双写 `source_<id>` 键。
6. **仅做兼容跳转的 `Setting*Full` 壳**（`SettingModelPage`/`SettingMcpPageFull`/`SettingAgentExecutionPageFull`/`SettingAgentExtensionsPageFull`/`SettingAgentPermissionsPageFull`/`SettingExperimentalModelCouncilPageFull`/`SettingExperimentalSubAgentPageFull`/`SettingSearchListCards`/`SettingSearchServiceEditorSheet` 等）：`aboutToAppear` 内 `replaceUrl` + `Blank()`，零入栈边。**注意：不含 `SettingAgentMemoryPageFull`** —— 该页是有真实 UI 的可达记忆管理页（`SettingPage` L77 进入），不在壳之列。`DeepReadTemplateSettingsPage` 例外（SettingExperimentalPage 引用）。
7. **`Index`**：`@Entry` 但已移出 `main_pages.json`，冷启动为 `ChatListPage`；其 Continue 聚合卡整体不可达。
8. **死组件**：`ToolResultPreview`（分类预览被 `ChatMessageTools` 简化为 3 行截断，`onOpenPreview` 实际已接线，仅信息密度降级）、`GenerativeWidgetCard`、`StageProgressCard`、`UnreadBadge`、`ContextRing`（第三套上下文指示器）、`MarkdownRichText`+`chat/markdown_html.ts`（唯一 UI 消费者为死组件）、`ArticleRenderer`、`WorkspaceFile{Sheet,Preview,VM}`、`ChatExportSheet`、`ChatListSnackbar`、`ProviderConnectionTester`、`AssistantPicker`、`ProviderCard`、`ModelList`、`ProviderPrimitives`、`ProviderBalanceText`、`ExtensionContent`、`AmberTextArea`，以及 `components/ui/` 中零引用的一批。
9. **死代码/工程债**：`SettingAppearancePage.ets` L112 与 `SettingDisplayPage.ets` L174 恒真 opacity；`ChatPage.ets` L4941 恒 1 乘数；`DeepReadArticlePage.ets` 死方法；`ChatMessageAvatar` 定时器「该不该启动」问题见 F-09；`BoardPage` focus_only 原地改（当前调用路径恰好正确，属潜在债，见 §5 附录）。

---

## 4. 运行时待验清单（单列，不混入正式定级结论）

> 前置：本机无在线设备/模拟器，以下均需真机或模拟器量测；括号内为对应条目。

- **主题刷新**：`design/tokens.ets` 状态色 token 走 `export let`，消费点 `Tag/ErrorCard/WorkspaceStyle` 无 `@StorageProp`，`PageTemplate` 38 个宿主页中约 35 页不订阅 `darkMode`，写侧切主题又不 `replaceUrl` 重建 → 切深/浅后栈内非订阅页主题色是否停留旧值，须逐页截图对照。`Tag/ErrorCard/WorkspaceStyle` 本身不可达，无用户面影响。
- **安全区**：仅「主动 `expandSafeArea([SYSTEM],[…,BOTTOM])` 却未补 `navBarHeight`」的页面存在补偿缺口（CouncilPage L278、CouncilRunPage L233、ChatProviderDetail/ProviderSettings、DeepReadArticle、MiniApp*、NovelProjects、Profile、SettingAppearance/DeepRead/Experimental/Page 等）；`NovelWorkspacePage` 有 navBarHeight 但仅用于输入栏与润色 sheet，pane 底部仍 24。未扩展安全区的页面按默认避让，不必然遮挡。
- **键盘**：默认 OFFSET 下，ChatPage 手写 `keyboardHeightChange → scrollBy` 是否与自动上抬叠加（多滚一段/贴底错位）；Novel 页显式 RESIZE/OFFSET 切换为另一模式；设置域底部表单（DeepRead 模型库、WebMount 站点表单、MCP 90%/70% sheet、Search 86% overlay、SubAgent/Council 展开 TextArea）在键盘抬起时字段/保存按钮是否被挡。
- **布局渲染**：`PaperSeparator`/`width('100%')+margin` 最终是溢出裁切还是全出血（F-02）；MiniAppChatCard 卡片内浮层 `height('100%')` 的百分比基准；`ChatSearchSettingsPage`/`ChatTaskModelsPage` 嵌套 Scroll 固定高 220 的手势抢占；小说工作区 pane 的实际裁切范围。
- **控件动画**：Switch/ToggleRow 滑块动画作用于 `margin.left`（属性动画支持列表未含 margin，可能瞬移），轨道底色无过渡；消息行入场动画随主题 key 变更整片重放；`ConfirmDialog`/`CompressContextDialog` 无最大高度/滚动/安全区补偿，最大字体或小屏下按钮是否被裁。
- **交互命中**：弹窗卡片空白误关（F-37，源码强但命中需目测）；小触控群（聊天的「+」40×40、回到底部、错误条 ✕、snack 撤销、ask_user chips 约 27vp、清除按钮约 20–22vp）；分享锚点在非目标分辨率/横屏下的表现。
- **OAuth 返回路径**：飞书授权生成的 PKCE/state 为页面实例私有字段，页面一旦被系统回收或用户中途离开即可能失效，导致即使拿到正确回调 URL 也无法兑换；「浏览器往返过程中页面被销毁」的实际触发概率与系统行为尚待设备确证，不作为已确诊缺陷。
- **建议（非缺陷，供产品取舍）**：议会运行页流式输出不自动跟滚——对「逐字流式面板」建议增加仅在用户位于底部时的自动跟随；导航冷却命中时可考虑加轻微按压反馈。

---

## 5. 低优先附录（P3 / 合并登记，不逐条展开）

**交互/反馈类**
- MCP 新建服务器名称留空点保存无反应（见 F-23）；`ChatAssistantsPage` 名称为空保存静默、取消/返回无脏检查（[harmony/entry/src/main/ets/pages/ChatAssistantsPage.ets](../../harmony/entry/src/main/ets/pages/ChatAssistantsPage.ets) L243–245、L586–597）；`QuickMessagesPage` 滑删底色用了非法颜色字面量（[harmony/entry/src/main/ets/pages/QuickMessagesPage.ets](../../harmony/entry/src/main/ets/pages/QuickMessagesPage.ets) L9/L155，白字落在无效背景上近乎不可见，删除动作本身仍触发）；`BoardPage` 打开原文失败时提示「已复制链接」但函数内未写剪贴板（[harmony/entry/src/main/ets/pages/BoardPage.ets](../../harmony/entry/src/main/ets/pages/BoardPage.ets) L235–249，低优先反馈失真）；设置页同名「深度阅读」指向两个不同页且版本号/图标重复；深度阅读「测试连通」按钮点击后仅做非空校验即显示「通过」，按钮名易被理解为真实连通性测试 —— 页面自身已标注「未联网验证」，非欺骗，仅命名误导且缺加载门；颜色与命名一致性（非法逻辑色、固定导出文件名、硬编码浅色底、主按钮字色用表面色）。

**布局/触控类（P3）**
- 聊天页「+新对话」/「回到底部」40×40；多条浮动状态条作为消息列表兄弟挤压视口；ask_user 选项 chip 约 27vp、自由回答框固定 64vp；返回键命中区偏小的活跃页面（[harmony/entry/src/main/ets/pages/SearchPage.ets](../../harmony/entry/src/main/ets/pages/SearchPage.ets)、[harmony/entry/src/main/ets/pages/CouncilRunPage.ets](../../harmony/entry/src/main/ets/pages/CouncilRunPage.ets)、[harmony/entry/src/main/ets/pages/DeepReadHistoryPage.ets](../../harmony/entry/src/main/ets/pages/DeepReadHistoryPage.ets)、[harmony/entry/src/main/ets/pages/QuickMessagesPage.ets](../../harmony/entry/src/main/ets/pages/QuickMessagesPage.ets) 等，命中区 <44，不可达页不计入）；搜索页加载态与空/失败态锚点不一致导致垂直跳变；`SettingFilesPage`/`FavoritePage` 的尾部 spacer 置于滚动容器外、恒定占底 40vp；嵌套 Scroll 固定高 220 抢手势；Cron/搜索排序等处内联 42×24 开关触达区 <44；小说工作区工具栏 6 个动作窄屏可能溢出。

**衔接/异步类（P3）**
- `MiniAppListPage` 加载失败仅 toast 后落空态；小说工作区一致性检查失败仍并列旧成功结论；生成中「归档讨论」静默无响应；深度阅读过期提示仅「从历史进入」时生效；`MiniAppSettingsPage` 首帧按默认值渲染再跳变（其覆盖用户配置的竞态已反证不成立）；飞书回调 URL 解析对 fragment/error 形式处理有缺陷；`newChat`(replace) 与分叉(push) 混用导致返回落点错位；跨页暂存 `export let PENDING_PROVIDER` 残留；议会运行页定时器仅 `aboutToDisappear` 清理；`ChatModelParamsPage` 以数组索引作 key + 并行数组，删中间行后输入/错误错位；`ChatProviderDetailPage` provider 解析失败无空态、保存/删除静默；`UIAvatar` 图片加载失败无回退；`BoardPage` focus_only 未命中源渲染「无条目」噪音且对嵌套状态原地赋值。
- 死组件与维护债（`ToolResultPreview` 导致工具卡信息密度降级、`ContextRing` 第三套上下文指示器、`MarkdownRichText`+`markdown_html` 死链、`SubAgentAvatar` 状态点不覆盖审批/超时）均属低优先或维护债。

---

## 6. 覆盖与验证执行记录（精简）

### 6.1 覆盖口径

- **页面**：以 `main_pages.json` 74 个注册页为准，全部被至少一份报告在某一层面触及，无页面级缺口。深度分布为「全文 62 + 全文（UI 面）1（ChatPage）+ 仅扫描 11（10 个兼容壳 + 1）」。
- **引用入边 = 57/17，但不是根可达图**：57 条有入边仅表示「被某处引用」，不保证从冷启动可达。`Assistant*` 整族（7 个有入边页 + 3 个 0 入边页）为「族根无外部入口」的孤岛；`MemoryPage`/`PromptTemplatePage`/`HotListSourceSettingsPage`/`AssistantImporter`/`AssistantPropertyEditor` 等为 0 入边。报告不宣称「所有页面可达」。
- **组件**：`components/ui/` 44 全覆盖；`components/` 顶层 58 —— 已读 54 + 抽样 1（`McpPaperRows`）+ 未读 3（均为 0 引用死码：`ChatExportSheet`/`ChatListSnackbar`/`ProviderConnectionTester`）。`ContextRing`、`ContentAttachmentChip`、`MarkdownRichText`、`SubAgentAvatar` 4 个组件已全部补读：其中 `ContentAttachmentChip`、`SubAgentAvatar` 有引用且会在 UI 渲染，`ContextRing`、`MarkdownRichText` 为 0 引用的死码（已读并归类）。`design/` 5（`tokens.ets` 全文，其余已读/抽样）。
- **边界**：未全量 build、未 UI 运行/截图；域内算法/构建签名/用户数据不在范围。

### 6.2 实际执行的验证（仅此）

- **单文件 lint（仅 D 两文件）**：`components/ui/ExportDialog.ets` → `Defects: 1; Errors: 0; Warns: 1`，退出码 0；`components/ui/WorkspaceStyle.ets` → `Defects: 5; Errors: 0; Warns: 5`，退出码 0（均为 `@performance/avoid-overusing-custom-component-check`）。codelinter 是单文件静态规则扫描，不做跨模块名解析，**不能**作为编译通过/失败证据。
- **隔离静态断言 36/36 pass + 仓库回归 `harmony/deepread/src/test/scheduler_signal.test.ts` 5/5 pass**（确证 REPLACE / `waitForAbort` / token guard 语义）。通过不代表 UI 验证。
- **WebMount 独立校验脚本**：`/tmp/amber-webmount-verify.cjs`（内存转译未改动源码 + mock webview，纯本地）T1–T4 全 PASS，复现 `wm_open` 在 `loadUrl` 前抛 `host not mounted`，并确认补 `markHostMounted()` 后可达 `loadUrl`、新 tab 不抛、带 url tab 抛错。
- **未执行**：`hdc list targets` 为空 → 无模拟器/真机、无截图、无 UI 注入；未运行全量 `assembleApp`/`hvigorw`；未签署 HAP。

### 6.3 残留缺口

- 所有运行时待验项（§4）在拿到设备前保持 needs-runtime，不得在正式发现中升级为「截图证实」或精确出屏/命中率数值。
- §3.1 已驳回项不再进入用户故障清单；死码维护债与正式发现分开计数。
- P1 三条（文件清理损坏后误删、DeepRead 隐式重跑、WebMount 开页阻塞）为本报告最高级别结论，其余最高 P2。WebMount 修复须在真实 controller 就绪的生命周期闭环回填宿主标记，修复后需设备复测。
