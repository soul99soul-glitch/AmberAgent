# UI/交互专项合并复核（F14/F15/F16/F16a/F17/F18/F19/F21/F22/F23/F24/F26/F29/F31/F32/F33/F36/F37/F38/F40）

日期：2026-09-13
范围：仅复核上一轮 UI 报告（`docs/reviews/2026-09-13-harmony-ui-ux-motion-review.md`）指定的 19 个编号；本文件为逐项**源码重读 + 活入口/上游保护核验**，不复制原结论。
方式：只读。逐项打开真实 `.ets`/`.ts`（当前工作树），核对调用链、渲染消费点与守卫；未运行 UI、未截图、未构建、未连设备、未做真实网络。未新增 probe（无需；本轮结论均可由源码闭合，隔离材料如需另见同目录他项）。

> 与深审（`docs/reviews/2026-09-13-harmony-deep-audit/findings.md`）去重：逐项做了编号映射核对，19 项**均无同根重复**；仅 F15 与深审 R05 同属「子串启发式」但代码路径/后果不同（见 F15 去重说明）。深审 R01「当前工作树不可编译」是本轮所有运行时后果的横切前提，凡涉及运行期表现者一律标注待设备，不据此升级为已实测。

## 判定口径（本轮统一）

- **确认 bug（conf）**：源码路径闭合、当前可达、守卫不能阻止用户可见的故障；不需要设备也成立（后果可为「控件无效/不可达/静默丢弃」这类代码事实）。
- **未验证 UI 表现（runtime）**：机制（几何/命中/光标/时序）为代码事实或强静态约束，但**具体表现/量值必须设备目测**；不得写成实测。
- **产品建议（sugg）**：一致性/可发现性/审美/产品取向，**不是**功能性缺陷；显式标注为「禁止/暂不可用」的控件不自动算「撒谎」，无二次确认也不自动算 bug（除非不可逆且无保护）。

级别沿用原报告：P1 仅真数据损坏或高成本核心流程整块不可用；P2 可达且成立；P3 低/打磨。

---

## 逐项结论

### F14 — 历史页「选择」按钮写反，多选/导出整链不可达

- **目标**：`harmony/entry/src/main/ets/pages/HistoryPage.ets`
- **判定**：**确认 bug · P2**
- **证据（当前树）**：
  - `@State selecting=false` L39；`exitSelecting()` L101–104；`actionText: this.selecting ? '取消' : '选择'` L168；`onAction: (): void => { this.exitSelecting(); }` L169。
  - 全文对 `selecting` 的写入只有 L39（初值）、L102（置 false）；**无任何 `selecting = true`**（grep 复核）。
  - 依赖 `selecting` 的 UI：行勾选 L187 / `toggleSelect` L90、编辑态点击 L191、底部导出条 `if (this.selecting)` L211。因 `selecting` 恒 false，这些分支永不出现。
  - 活入口：`SettingPage.ets` L107 `this.go('pages/HistoryPage')`；已在 `main_pages.json` 注册（L38）。
- **上游保护 / 反证**：无。按钮文案分支本身也永停在「选择」；无其它设置入口或持久化写入 `selecting`。工作树对同文件的新增仅是把加载抽为 `reload()` 并加 `onPageShow`（`git diff` 确认），不触及选择逻辑。
- **去重**：深审无对应 R。
- **最小修复**：`onAction: (): void => { if (this.selecting) { this.exitSelecting(); } else { this.selecting = true; } }`。

### F15 — 工具执行状态用输出子串猜，成功结果会被误标「失败」

- **目标**：`harmony/entry/src/main/ets/components/ChatMessageTools.ets`
- **判定**：**确认 bug（判定逻辑缺陷）· P2**；触发频率依赖工具真实输出（数据相关，非设备相关）
- **证据（当前树）**：
  - `resolveStatus()` L33–54：先看 `approvalState.type`（pending→待授权、denied→已取消，正确），随后对每条 text 输出 `.toLowerCase()` 后 `includes('error'|'exception'|'failed')` → `Failed`，否则 `Succeeded`（L39–49）。不读任何结构化字段。
  - 数据结构：`UIMessagePartTool`（`harmony/deepread/src/main/ets/agent/message.ts` L81–89）仅含 `toolCallId/toolName/input/output/approvalState/metadata`，**无 status/ok/exit_code**；因此当前实现除了文本扫描没有可读字段（修复需先在数据层引入结构化终态，或限定词边界）。
  - 渲染后果：`AgentToolCallCapsule` 用 `toolStatusColor/toolStatusIcon`，Failed → ERROR 色 + `✕`（`AgentToolCapsule.ets` L88–101），标题动词「失败」（L67–69）。
  - 活入口：`AgentMessageRow.ets` L305 实例化 `ChatMessageTools`，`ChatPage` 时间线 `ForEach` 逐条渲染（ChatPage L4348–4410 一带）。工具输出为 `search_web/scrape_web/file_read/terminal_*` 的**原始正文**，命中 `error/failed/exception` 概率真实存在。
- **上游保护 / 反证**：`approvalState` 对 pending/denied 判定正确，所以问题**只在** succeeded/failed 的推断；无结构化状态字段可兜底。属「成功被标失败」的误报方向（非漏报）。
- **去重**：深审 R05 也是子串启发式，但那是 Provider 错误正文含 abort → 被当取消，代码在 `chat_kernel_host.ts`/`ChatPage` 消息回退链，**根因与后果均不同**，不合并。深审无工具状态渲染项。
- **最小修复**：优先消费结构化终态（需补数据字段）；无法结构化时把词扫描限制为行首/词边界并只作降级，且与 `approvalState` 配合。

### F16 — 显示设置中「当前页可见、渲染层零消费」的一批开关

- **目标**：`harmony/entry/src/main/ets/pages/SettingDisplayPage.ets`（写侧）对渲染层消费侧
- **判定**：**确认 bug（设置无效果）· P2**
- **逐键核验（要求：每键真实 UI 存在 / 无消费）**：

| 键 | 设置页真实 UI（行） | 全仓读取/渲染消费点 | 结论 |
|---|---|---|---|
| `showAssistantBubble` | L96–100 开关 | 仅 `ProfilePage` L240/L340 原样回写 | **无消费** |
| `showModelName` | L101–105 | 仅 `ProfilePage` L242/L342 回写 | **无消费** |
| `showModelIcon` | L106–110（标签误为「显示用户头像」，见 F16a） | 仅 `ProfilePage` L241/L341 回写 | **无消费** |
| `sendOnEnter` | L118–122 | 无（`ChatInput.ets` 全文件无 Enter/`sendOnEnter` 处理，只有 send/stop 按钮 L223–258） | **无消费** |
| `pasteLongTextAsFile` | L123–127 | 无（`ChatInput` 无 paste 处理） | **无消费** |
| `codeBlockAutoCollapse` | L130–134 | 无（`MarkdownText.RenderCodeBlock` L646–722 代码块不折叠） | **无消费** |
| `showLineNumbers` | L135–139 | 无（同上，无行号列） | **无消费** |
| `fontSizeRatio` | L141–154 | 无（`MarkdownText` 只有字面量 `fontSize`/`fontSize` prop；`AgentMessageRow` 无该 prop） | **无消费** |

- **反证（防止过度泛化）**：同一 `DisplaySetting` 的其它键**确有消费**——`enableAutoScroll`（ChatPage L842/969–974/1467…）、`autoCloseThinking`（ChatPage L756/973/4355…、`ChatMessageReasoning` L51/84）、`userNickname`/`userAvatar`（ChatPage L750–751、ChatListPage L65/77、Profile L406）、`accentColor`（EntryAbility L189–192）。因此这是**一批特定键未接线**，不是「显示设置整体失效」。
- **活入口**：`SettingPage.ets` L72 → `SettingDisplayPage`；已在 `main_pages.json` L59 注册。
- **去重**：深审无对应 R。
- **最小修复**：ChatPage 装载 `DisplaySetting` 后把 `fontSizeRatio/codeBlockAutoCollapse/showLineNumbers/pasteLongTextAsFile/sendOnEnter` 下发到 `MarkdownText`/`AgentMessageRow`/`ChatInput`；暂不接线者从设置页下线。

### F16a — 「显示用户头像」开关实际绑定模型图标字段（子项）

- **目标**：`SettingDisplayPage.ets` L106–110
- **判定**：**确认 bug · P2**
- **证据**：开关标签「显示用户头像」`desc:'在用户消息旁显示头像'`，但 `checked: this.setting.showModelIcon`、`onToggle` 写 `showModelIcon`（L108–109）。`showModelIcon` 全仓除 `ProfilePage` L241/L341 回写外**无读取/渲染点**。用户头像 `userAvatar` **不受任何开关控制**始终显示：`ChatListPage` L611–613、`ChatDrawer` L76–78、`ProfilePage` L406–409。
- **上游保护 / 反证**：无。字段名 `showModelIcon` 语义应为「模型图标」，标签却写成用户头像，属绑定错位 + 死键叠加。
- **去重**：深审无对应 R（R34 是助手绑定模型，不同域）。
- **最小修复**：改绑真正控制用户头像显示的键（或新增），并把 `showModelIcon` 接到助手/模型图标渲染或下线。

### F17 — ask_user 单问题时问题文本重复显示两遍

- **目标**：`harmony/entry/src/main/ets/components/ChatMessageAskUserStep.ets`
- **判定**：**确认 bug（视觉冗余）· P3**
- **证据**：`heading()` L169–174：`qs.length===1` 时 `return qs[0].question`；`build()` L41–43 渲染 heading，L45–52 又 `ForEach` 渲染 `Text(q.question)`。单问题时同一句先以 accent 小标题、再以正文出现。多问题时 heading 为「询问 N 个问题」不重复。
- **上游保护 / 反证**：无按 `qs.length` 抑制正文/标题的分支；pending 与 answered 两态都会重复（answered 时正文下方是答案行，标题仍是问题）。
- **活入口**：`AgentMessageRow.ets` L292 实例化；`ask_user` 工具卡在 ChatPage 可达。
- **去重**：深审无对应 R。
- **最小修复**：`heading()` 单问题时返回固定文案（如「请回答」），或仅 `qs.length>1` 渲染标题行。

### F18 — MiniAppChatCard「⋯」菜单只能长按，点按无反应

- **目标**：`harmony/entry/src/main/ets/components/MiniAppChatCard.ets`
- **判定**：**确认 bug（交互范式不一致）· P3**
- **证据**：L409–418 的「⋯」Row 仅 `.bindContextMenu(this.cardMenu(), ResponseType.LongPress)`，该 Row **无 `onClick`**；同卡「运行」L392、「修改」L406 为点按。`cardMenu()` L445–464 提供查看源码/导出/版本历史等动作。
- **上游保护 / 反证**：`canOpenList` 默认 true（L31），ChatPage 能力位 `openMiniAppList:true`（ChatPage L188），故该入口**可达**；`ResponseType.LongPress` 是有意选择，但图标「⋯」的可发现性预期是点按。
- **活入口**：`AgentMessageRow.ets` L378–382 → ChatPage。
- **去重**：深审无对应 R。
- **最小修复**：改 `.bindMenu(this.cardMenu())` 或在该 Row 加 `onClick`（长按可保留）。

### F19 — SettingSlidesFontPage 整页「假保存」：完成=返回，设置不落盘

- **目标**：`harmony/entry/src/main/ets/pages/SettingSlidesFontPage.ets`
- **判定**：**确认 bug · P2**
- **证据**：`placeholderLoadFontSettings()` L21–23 返回常量 `(0,18)`；`aboutToAppear` L32–37 填入本地 `@State`；setter `setFamily` L39–42 / `setSize` L44–47 只改 `@State`；`done()` L49–51 **仅 `router.back()`**；顶栏动作文案「完成」L117。全文件**无任何 KV/文件持久化 API 调用**；`SlidesFontSettings` 仅本文件私有。
- **上游保护 / 反证**：无存储键、无读取方（全仓 grep `slides_font`/`SlidesFont`/`fontFamilyIndex` 仅命中本页）。预览文本虽用 `this.s.fontSize`（L144–148），但字体族分支恒用 `FONT_SANS`（L148），与 `PillSelect` 所选字体族不一致（预览也不忠实）。活入口：`SkillsPage.ets` L173。
- **去重**：深审无对应 R。
- **最小修复**：接入真实存储并在保存成功后 `back()`；或下线入口、去掉「完成」的提交语义（改「返回」）。

### F21 — SubAgent/ModelCouncil「展开编辑、折叠才保存」：编辑后直接返回静默丢保存

- **目标**：`harmony/entry/src/main/ets/pages/SettingSubAgentPage.ets`、`SettingModelCouncilPage.ets`
- **判定**：**确认 bug · P2**
- **证据**：
  - SubAgent：角色行 L237–245 `onClick` 仅在**已展开再点同行**时 `saveSetting()` 并折叠（L239–241），非展开态只置 `expandedRoleId`（L243）；编辑写入只改 `@State`（`updateRolePrompt` L304–316），无「保存/取消」按钮（只有「恢复默认」L263）。
  - Council：同构 L259–266（展开再点才 `saveSetting()`）、`updateSeatName` L309–315 / `updateSeatPrompt` L318–324 只改 `@State`，无保存按钮。
  - 两页**均无 `aboutToDisappear`/`onBackPress`/脏标记**（grep 确认；`saveSetting` 是 async 且 `catch` 静默，见 SubAgent L70–91、Council L70 起）。
  - 顶栏 `PageTitleBar({title:...})`（SubAgent L95、Council L94）无保存动作。
- **机制收窄（反证）**：`saveSetting()` 从**整份 `@State`** 序列化（SubAgent L74–85），因此「切行随后再触发任一保存点（折叠/开关/滑块/SelectRow/恢复默认/删除席位）」会把先前编辑一并写盘——**「切行即永久丢」不成立**；真正丢失的是「编辑后未触发任何保存点即系统返回/`router.back()`」。
- **上游保护 / 反证**：折叠即保存提供了部分保护；无离页 flush。
- **活入口**：`SettingExperimentalPage.ets` L25/L27；已在 `main_pages.json` L66/L73 注册。
- **去重**：深审无对应 R。
- **最小修复**：展开区加显式「保存/取消」；`aboutToDisappear` flush；至少加未保存提示。

### F22 — ChatMcpSettingsPage 滑动删除 MCP 服务器无确认、无撤销

- **目标**：`harmony/entry/src/main/ets/pages/ChatMcpSettingsPage.ets`
- **判定**：**确认 bug（不可逆删除无保护）· P2**
- **证据**：`deleteServer` L94–98 立即 `updateMcpServers(filter)`；`deleteAction` L455–468（红底「删除」`onClick` 即删）；挂载点 `.swipeAction({ end: this.deleteAction(config) })` L595。全文件无 `AlertDialog`（grep 确认）。请求头（含 Authorization token）随 `commonOptions` 一并消失，无撤销。
- **上游保护 / 反证**：左滑本身是刻意手势，构成弱保护；但无二次确认、无 undo。**同类删除对比**：`SkillsPage.ets` L129–133 走 `AlertDialog.show`（带技能名）；`SettingCronTasksPage` L146、`SettingFilesPage` L137 亦用 AlertDialog → 属仓内不一致。
- **活入口**：`SkillsPage.ets` L169（另 `SettingMcpPageFull` 兼容壳 `replaceUrl`）；`main_pages.json` L21。
- **去重**：深审 R11 是「会话删除清理不统一」，入口/对象不同，不合并。
- **最小修复**：复用 `AlertDialog.show`（带服务器名）或二次确认；补 `accessibilityText`。

### F23 — 名称为空时「保存」静默 no-op

- **目标**：`ChatMcpSettingsPage.ets`
- **判定**：**确认 bug（无反馈）· P3**
- **证据**：`confirmDraft()` L101–115，L104 `if (draft.commonOptions.name.trim().length === 0) return;` 直接返回，无 toast/无错误态；保存按钮 L485–488 `BtnFill({ label:'保存', onTap: ... })` **未传 `enabled`**（`BtnFill`/`BtnAccent` 支持 `enabled`，见 `AmberPrimitives.ets` L36/L60），故按钮外观与可点性不变。sheet 停留原地，用户误判卡死。
- **上游保护 / 反证**：非空校验是**正确的阻止逻辑**，缺的是可见反馈/禁用态；不构成数据错误。
- **去重**：深审无对应 R。原报告附录也登记过 ChatAssistantsPage 同类静默，属同型但不在此编号。
- **最小修复**：保存按钮 `enabled: name.trim().length>0` 或 toast「请填写名称」+ 输入行错误边框。

### F24 — 请求头 onChange 内 trim 后回写 `TextInput({text})`，空格可能被吃

- **目标**：`ChatMcpSettingsPage.ets` L353–374
- **判定**：**未验证 UI 表现（机制为代码事实）· 建议 P2 待设备**
- **证据**：name 输入 L353–363 `headers[index] = [v.trim(), headers[index][1]]`；value 输入 L364–374 `headers[index] = [headers[index][0], v.trim()]`；两者经 `patchCommon` 换新 `draft`（L133–144），再以 `TextInput({ text: header[0/1] })` 重新渲染。`trim-on-write` 是确定的代码事实——输入 `Bearer ` 时尾部空格被去除后回灌。
- **上游保护 / 反证**：无草稿层保留 raw 字段；无 `$$` 双向绑定。但**tail 空格是否真的导致丢字符/光标错位**取决于 ArkUI 单向 `text` 回写与选区重置行为，必须设备确认，不得写成已复现。
- **活入口**：同 F22（SkillsPage L169）。
- **去重**：深审无对应 R。
- **最小修复**：草稿层保留 raw，仅在 `confirmDraft()` trim；或改 `TextInput({text: $$this.x})` 双向绑定。

### F26 — SelectRow「点击即循环取值」不可发现，Slider 拖动逐帧落盘

- **目标**：`harmony/entry/src/main/ets/components/PageTemplate.ets` + `SettingSubAgentPage.ets` / `SettingModelCouncilPage.ets`
- **判定**：**确认 bug（可发现性 + 写入放大）· P2**；并发覆盖后果待设备
- **证据**：
  - `SelectRow`（PageTemplate L442–496）只有 label/desc/valueText，**不绘制 chevron/候选提示**；点击整行 `onRowClick()`（L489）。
  - 调用侧点击即循环：SubAgent `selectRow('超时时间'...)` L130–135、`输出预算` L137–142 取 `options[(idx+1)%n]`；Council `席位超时` L125–130、`总超时` L132–137 同构。用户无法知道错过了哪个候选值。
  - Slider 逐帧落盘：SubAgent L120–128 / L125–128、Council L110–123 的 `Slider(...).onChange(v => { this.xxx = round(v); this.saveSetting(); })`；`saveSetting` 为 get→改→update 的读改写（SubAgent L70–91、Council L70 起）。拖 2→8 会触发 7 次 get+update。
- **上游保护 / 反证**：无。注意「无 chevron」本身只是审美，**核心缺陷是「选择」只能盲轮转 + 逐帧写**。逐帧写是否导致并发互相覆盖依赖真实调度，属设备/时序待验；写入放大（多次 RMW）本身是代码事实。
- **活入口**：同 F21。
- **去重**：深审无对应 R。
- **最小修复**：`SelectRow` 加候选弹层并补 chevron；Slider 改为「拖动结束提交」或防抖。

### F29 — editorial_slant 模板下 A-/A+ 与字体模式是死控件

- **目标**：`harmony/entry/src/main/ets/pages/DeepReadArticlePage.ets`
- **判定**：**确认 bug（死控件）· P2**；附加：`fontSerif` 全仓无消费
- **证据**：
  - 顶栏缩放按钮 L388–399 恒渲染；`zoomIn/zoomOut` L305–313 只改 `fontScale`。
  - 渲染分支：`output!==null && templateId==='editorial_slant'` L464–479 用 `renderEditorialSlantHtml(this.title, this.output)` 直接 Web 渲染，**签名不含 `fontScale/fontSerif`**（`DeepReadTemplate.ets` L151）；只有原生分支 L515/L569/L695 等消费 `fontScale`。
  - **附加发现**：`fontSerif`（L77，L148 写）**全仓无读取点**（grep 仅命中赋值行），即「衬线模式」在**所有模板/所有分支**都未接线，比原报告更强。
- **上游保护 / 反证**：`templateId` 默认 `'none'`（L69），走原生分支时 A± 正常 → 属**用户主动选 editorial_slant 后**才死；非默认路径。
- **活入口**：DeepRead 文章页可达（历史/看板/通知链）。
- **去重**：深审无对应 R。
- **最小修复**：模板模式下隐藏/禁用缩放按钮，或把 `fontScale` 注入模板 HTML（CSS 变量）；`fontSerif` 接 `fontFamily` 或从设置页下线。

### F31 — 聊天页会话重命名入口缺失，`startEditTitle` 零调用者

- **目标**：`harmony/entry/src/main/ets/pages/ChatPage.ets`（全局 rename 入口核验）
- **判定**：**确认 bug · P2**
- **证据（本轮重新核验，不盲信历史结论）**：
  - `startEditTitle()` L2010–2014（自注「手动改名(D-034)」）全文**无第二处出现**（grep：定义 + 内部 `editingTitle=true` L2013）。`saveTitle` L2016–2024、`cancelEdit` L2053 与之配套。
  - `editingTitle` 写入点仅：L2013（startEditTitle）、L2021（saveTitle 置 false）、L4574（取消置顶 false）→ **恒为 false**；编辑态 UI L4527–4545、保存/取消 L4567–4575 全程不可达。标题区点击绑 `toggleModelMenu()` L4560–4564，不进入编辑。
  - **全局是否另有 rename 入口**：ChatListPage 行长按菜单 L434–448 = 置顶/复制 Markdown/导出/删除，**无重命名**；ChatListGrouped 抽屉行菜单 L152–162 = 置顶/重新生成标题/删除，**无重命名**（`重新生成标题` 是 AI 重写，非用户改名）。
  - **当前 drawer 是否不可达**：是。`drawerOpen` 唯一置 true 在 `openDrawer()` L2713；`openDrawer()` **零调用者**（全仓 grep 仅定义 L2704，无 `this.openDrawer()` 调用；相关调用只出现 `openDrawerConversation/Board/Apps/Settings`）。故抽屉及其中「重新生成标题」也整体不可达。
  - 结论：聊天域**完全没有可达的会话重命名入口**；`NovelProjectsPage.rename`/`MiniAppListPage` 属其它对象，不算会话 rename。
- **上游保护 / 反证**：无。`maybeAutoTitle` 只在生成后自动起标题，非用户改名。
- **去重**：深审无对应 R（无 rename 项）。
- **最小修复**：给标题区明确入口（编辑图标/长按标题）并接线 `startEditTitle`；若确认不做，删除该段死代码以免误导。

### F32 — 「收进章节」后跳到设定页签且落错二级分段

- **目标**：`harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets`
- **判定**：**确认 bug · P2**
- **证据**：`doCollect` L951–965，成功后 L959 `this.tab = 2;`（注释「跳到活资料查看建议」）——**未改 `settingsSection`**。`settingsSection` 初值 0（L109）；`settingsPane` L1845–1873：`settingsSection===0` → `NovelSettingsPane`（工作区设定），否则 `materialLibraryPane`。资料建议渲染在 `materialLibraryPane`（L1756 「待确认建议」）。故落在「设定」的「工作区设定」而非「资料库」，刚生成的建议不在眼前。
- **上游保护 / 反证**：用户可手动点「资料库」chip（L1849/L1876–1888）纠正；注释意图与实现不符。
- **活入口**：正文消息「收进章节」入口（`collectMessageId` 由 L884 置入），页内可达。
- **去重**：深审无对应 R。
- **最小修复**：`doCollect` 成功后 `settingsSection = 1`；或留在正文 toast 提示。

### F33 — 「重新分析」只扫描最后一章

- **目标**：`NovelWorkspacePage.ets`
- **判定**：**确认 bug · P2**
- **证据**：入口 L1744–1746 `Text('重新分析')`；实现 `reanalyze` L1229–1242：L1234 `const last = p.chapters[p.chapters.length - 1]`，L1235 只传 `last.id` 调 `refreshMaterialSuggestions`。服务端 `creation.ts` L718–737 会返回 `warning`（零建议时），但页面**未接收/未提示**（`await` 结果被丢弃）。标签无范围限定。
- **上游保护 / 反证**：用户无法从 UI 得知范围；无「全部章节」分支。
- **活入口**：设定/资料库面板内可达。
- **去重**：深审无对应 R。
- **最小修复**：标签改「分析最新章节」或提供范围选择，至少 toast 明示范围与 warning。

### F36 — 系统访问「通讯录」不可用死控件 vs 整页文案

- **目标**：`harmony/entry/src/main/ets/pages/SettingSystemAccessPage.ets`
- **判定**：**产品建议 / 维护债 · P3（不判为确认 bug）**
- **证据**：`contacts` 状态 `'acl'`（L72），badge 文案「暂不可用」（L83）；`requestPermission` L94–95 `if (p.status !== 'denied') return;` → acl 项点击**无反应**；整行 `onClick` L150 仍绑定；说明条 L176「点击未授权项可发起申请」。附带 L104 申请时硬编码 `'ohos.permission.READ_CALENDAR'` 不使用 `p.key`（当前仅 calendar 可达，潜伏）。
- **为什么不判确认 bug**：通讯录项**显式标注「暂不可用」**且 desc 写明「调试 Profile 需加入 ACL 权限(AGC 后台)后解禁」（L72）。页面文案说的是「**未授权**项可发起申请」，而 acl 项不是「未授权」，二者不矛盾；「显式不可用」不等于「撒谎」。缺的是点击后的解释/无操作反馈，属打磨与信息架构，非功能故障。
- **上游保护 / 反证**：`status !== 'denied'` 早退是**有意的禁用**；不可达的其它 status 也让硬编码 `READ_CALENDAR` 暂不触发。
- **活入口**：`SettingExperimentalPage.ets` L41；`main_pages.json` L74。
- **去重**：深审无对应 R。
- **最小修复**：acl/exempt 状态不绑 `onClick`，或点击弹 toast 说明需在 AGC 后台配置 ACL；`requestPermission` 用 `p.key` 映射权限。

### F37 — 遮罩弹窗内卡片未消费点击 → 点卡片空白即关闭并丢输入

- **目标**：`NovelProjectsPage.ets`、`MiniAppListPage.ets`、`MiniAppChatCard.ets`、`NovelWorkspacePage.ets`
- **判定**：**未验证 UI 表现（机制为代码事实/强静态约束）· P2 待设备命中确认**
- **证据（当前树，逐例复核）**：
  - `NovelProjectsPage` 新建/重命名卡 L379–380（卡片 `.onClick` 无）vs 遮罩 `.onClick` L386–390（关闭并清 `errorMsg`）。
  - `MiniAppListPage` rename 卡 L412–416（内层无消费点击）vs 遮罩 `.onClick` L420（`renameTarget=null`，丢已输入标题/描述）；version 卡 L483 vs 遮罩 L484 同型。
  - `MiniAppChatCard` modify 卡 L344–348 vs 遮罩 L350–352（`modifyDialogOpen=false`，丢修改输入）。
  - `NovelWorkspacePage` collectDialog 卡 L2058 vs 遮罩 L2062（`collectMessageId=''`）。
  - **反例对照**：`components/ui/ConfirmDialog.ets` L74 卡片显式 `.onClick(() => {})` 消费；`ChatPage.ets` L4930 deny 弹窗卡同样 `.onClick(() => {})` 并有注释「消费点击,防穿透到遮罩」→ 说明这是已知的 ArkUI 命中穿透模式且已有正确先例。
- **上游保护 / 反证**：输入型弹窗（rename/modify/chapterEditor/materialEditor）的卡片区**没有**消费点击；`chapterEditor`/`materialEditor`（Novel L2090/2141）也未见卡片 onClick（同样风险）。命中是否落到遮罩由 ArkUI 命中测试决定，**必须设备目测**确认误关频率，不能仅凭源码称已复现。
- **去重**：深审无对应 R。
- **最小修复**：内容卡片加 `.onClick(() => {})`（或在独立遮罩层承接关闭），对齐 ConfirmDialog/ChatPage 先例。

### F38 — 小说工作区未接管系统返回，弹窗草稿与页内层级被跳过

- **目标**：`NovelWorkspacePage.ets`
- **判定**：**确认 bug · P2**
- **证据**：全文 **`onBackPress` 命中 0 次**（grep -c = 0）；`aboutToDisappear` L210 只做清理。叠层：`collectDialog` L2030–2063、`chapterEditor` L2065–2094、`materialEditor` L2097–2145、`chapterMenuOverlay` L2147–2168、版本 sheet（后续）均无返回处理；层级返回仅靠页内按钮（阅读态返回 L1701–1703）。编辑类弹窗（章节标题/正文、资料）在直接系统返回时连同未保存输入一起被丢弃。
- **上游保护 / 反证**：无 `onBackPress`、无离页 flush；默认返回直接退出整页。
- **去重**：深审无对应 R（与 F40 是不同页面的同型问题）。
- **最小修复**：实现 `onBackPress` 优先级：任一弹窗 open 先关（编辑类先询问是否丢弃）→ `selectedChapterId!==''` 回目录 → `tab!==0` 回创作 → 根层再 `router.back()`。

### F40 — ChatPage 自绘覆盖层不参与 `onBackPress`，系统返回直接退出会话

- **目标**：`harmony/entry/src/main/ets/pages/ChatPage.ets`
- **判定**：**确认 bug · P2**（附：现有分支为死逻辑）
- **证据**：`onBackPress` L777–781 仅 `if (!this.drawerOpen) return false; this.closeDrawer(); return true;`。未处理的自绘覆盖层：`modelMenuOpen`（L4858–4879 TopModelMenu）、`contextMeterOpen`（L4631–4640）、`denyDialogToolCallId`（L4900–4936）。这些层在打开时按返回会 `return false` → 弹掉整个 ChatPage，输入态/滚动位置丢失。附带：`drawerOpen` 唯一置 true 在 `openDrawer()` L2713，而 `openDrawer()` **零调用者**（全仓仅定义，见 F31）→ L778 分支实际永不命中，是死逻辑。
- **上游保护 / 反证**：各覆盖层自身有遮罩点击关闭（如 contextMeter L4635、denyDialog L4935），但均非返回键路径；系统 sheet 的返回由框架消费，不在本项断言内。
- **去重**：深审无对应 R。
- **最小修复**：`onBackPress` 依次判定 `denyDialogToolCallId / modelMenuOpen / contextMeterOpen`，命中则关闭并 `return true`；清理死分支。

---

## 汇总表

| 编号 | 判定类别 | 建议级别 | 一句结论 | 与深审去重 |
|---|---|---|---|---|
| F14 | 确认 bug | P2 | `selecting` 无置真点 → 选择/多选导出永不出现 | 无 R |
| F15 | 确认 bug | P2 | 工具输出子串判失败，成功可能被标红 | 与 R05 同族不合并 |
| F16 | 确认 bug | P2 | 8 个设置键渲染层零消费（非全部键） | 无 R |
| F16a | 确认 bug | P2 | 「用户头像」开关绑 `showModelIcon`，头像不受控 | 无 R |
| F17 | 确认 bug | P3 | 单问题重复显示两遍 | 无 R |
| F18 | 确认 bug | P3 | 「⋯」仅长按，点按无反应 | 无 R |
| F19 | 确认 bug | P2 | 字体设置无任何持久化，「完成」=返回 | 无 R |
| F21 | 确认 bug | P2 | 编辑后直接返回丢保存；切行不永久丢 | 无 R |
| F22 | 确认 bug | P2 | 滑删 MCP 无确认/撤销 | 与 R11 不同根 |
| F23 | 确认 bug | P3 | 名称空点保存静默 no-op | 无 R |
| F24 | 未验证 UI 表现 | P2（待设备） | trim-on-write 事实；空格/光标待测 | 无 R |
| F26 | 确认 bug | P2 | 选择只能盲轮转 + Slider 逐帧落盘 | 无 R |
| F29 | 确认 bug | P2 | editorial 下 A± 死；`fontSerif` 全仓无消费 | 无 R |
| F31 | 确认 bug | P2 | 会话改名入口全缺；抽屉不可达 | 无 R |
| F32 | 确认 bug | P2 | 收章节落错二级分段 | 无 R |
| F33 | 确认 bug | P2 | 重新分析只扫末章且丢弃 warning | 无 R |
| F36 | 产品建议/维护债 | P3 | 显式「暂不可用」，不算撒谎；缺反馈 | 无 R |
| F37 | 未验证 UI 表现 | P2（待设备） | 卡片未消费点击，命中需目测 | 无 R |
| F38 | 确认 bug | P2 | 小说页无 onBackPress，草稿/层级被跳过 | 无 R |
| F40 | 确认 bug | P2 | ChatPage 返回未处理三覆盖层；现有分支死逻辑 | 无 R |

**计数**（分列，不机械相加）：确认 bug **17** 项 = F14/F15/F16/F16a/F17/F18/F19/F21/F22/F23/F26/F29/F31/F32/F33/F38/F40；未验证 UI 表现 **2** 项 = F24/F37；产品建议 **1** 项 = F36。覆盖指定编号 F14–F40（含 F16a）共 20 条目，无遗漏。**没有 P1**；无同根重复需并入深审。

## 横切说明

- 本轮全部为源码级复核；深审 R01 指出当前工作树 `CompileArkTS` 报错，意味着本轮「可达」均指**源码可达图**，不等于在可安装 HAP 上已验证。
- F24/F37 明确为「机制成立、表现待设备」；不得在后续报告中升级为「截图证实」。
- 对同语义但不同对象的 rename 已排除：`NovelProjectsPage.rename`（小说项目）、`MiniAppListPage` 重命名（小应用）不构成会话 rename。
