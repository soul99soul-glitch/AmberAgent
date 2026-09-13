# Batch 5A — Chat/Novel 共享组件与显示/技能设置

范围：UI-F09 / F12 / F15 / F16 / F17 / F19 / F44 + R08（组件侧）。仅改分配给本 agent 的独占文件；未触碰 ChatPage / NovelWorkspacePage / AppContainer / index.ts / chat|deepread barrel。

约束遵守：加载 `harmony-arkts`；API 基线 12；状态装饰器全 V1；无新依赖/新框架；不 hvigor 全仓构建。

---

## 修改文件与修复 ID

| 文件 | 修复 | 最小机制 |
|---|---|---|
| `harmony/entry/src/main/ets/components/ChatMessageAvatar.ets` | F09, F16 | blink 定时器改为随真实 `streaming` 启停（@Watch 对称 + aboutToDisappear 清理）；模型名/图标显示设置消费 |
| `harmony/entry/src/main/ets/components/ChatMessageSubAgentStep.ets` | F12 | 删除墙钟 phase 轮播；阶段仅取 output 结构化 `phase/phase_label/stage/stage_label`，缺失只显示运行状态 |
| `harmony/entry/src/main/ets/components/ChatMessageCouncilStep.ets` | F12 | 同上；删除空实现的 `startPhaseRotation`/`phaseIndex` 与 `COUNCIL_PHASES` 固定轮换 |
| `harmony/entry/src/main/ets/components/ChatMessageTools.ets` | F15 | 复用 chat-domain 既有 `toolActivityStatus` + `toolOutputJson` 结构化终态；删除正文 includes(error/exception/failed) 误判 |
| `harmony/entry/src/main/ets/components/ChatMessageAskUserStep.ets` | F17 | 单问题时 heading 已渲染问题，正文跳过重复渲染 |
| `harmony/entry/src/main/ets/components/ContentAttachmentChip.ets` | F44 | 可选 `openable`/`onOpen`；仅宿主声明可打开时渲染可点态，用 `priorityGesture` 独占点击不冒泡父级编辑 |
| `harmony/entry/src/main/ets/components/AgentMessageRow.ets` | F16, F44, R08 | 新增显示 props 透传；`openDocument` capability + `onOpenDocument`；审批回调补 locator |
| `harmony/entry/src/main/ets/components/MarkdownText.ets` | F16 | 新增 `codeBlockAutoCollapse`/`showLineNumbers`（默认 false 保持原样），代码块行号与 >10 行折叠 |
| `harmony/entry/src/main/ets/pages/SettingDisplayPage.ets` | F16 | 「显示用户头像」标签修正为「显示模型图标」（原错绑 showModelIcon）；sendOnEnter/pasteLongText 显式「未支持」不可编辑 |
| `harmony/entry/src/main/ets/pages/SkillsPage.ets` | F19 | 移除误导性「Slides 字体资源」入口 |
| `harmony/entry/src/main/ets/pages/SettingSlidesFontPage.ets` | F19 | 显式未支持：不加载伪配置、禁编辑、去「完成」动作 |

---

## 各修复细节

### F09 — 静止助手头像不再空转定时器
`shouldBlink() = role!=='user' && streaming`；`aboutToAppear` 与 `@Watch('onStreamingChange')` 均走同一开关，`aboutToDisappear` 清理并复位 `cursorOn=true`。非 streaming 行零定时器。

### F12 — 真实阶段，不伪造进度
`parseLatestPhase`(subagent) 取 `phase_label/phase/stage_label/stage`；council 另支持数字 `round` → `第 N 轮`。无结构化字段时不显示阶段（文案仅 `@name 状态` / `@Council 状态`），不再用 `Date.now()/5000` 轮换。

### F15 — 结构化工具终态（复用既有 helper）
`tool_activity.ts` 的 helpers **已可由 `@amber/chat-domain` barrel 导入**（`index.ts:1047` 导出 `toolOutputJson/toolActivityStatus/indicatesFailure` 等，type 亦导出）。故零新增依赖，直接 `import { toolActivityStatus, toolOutputJson } from '@amber/chat-domain'`。判定：`pending→WaitingApproval`、`denied→Cancelled`、`indicatesFailure→Failed`、有 output 且非失败→Succeeded、无 output→Running。新增 `@Prop streaming` 传入（AgentMessageRow 传 `this.streaming`）。

### F17 — 单问题只渲染一次
`headingShowsQuestion(qi) = questions.length===1 && qi===0`；此时正文不重复。多问题 heading 仍为「询问 N 个问题」+ 逐条正文。

### F16 — 八键处置（不造假保存）
- **已接线（6 键）**：`showAssistantBubble`（助手文本块 SURFACE2 气泡）、`showModelName`（真实 `message.modelId`，空才回退 'amber' 品牌标签，不冒充模型名）、`showModelIcon`（monogram 首字母，不引新 asset）、`fontSizeRatio`（`15*ratio` 四舍五入一位小数，默认 1.0==原值）、`codeBlockAutoCollapse`、`showLineNumbers`。前 3 项由 ChatMessageAvatar 消费，全部经 AgentMessageRow → MarkdownText/Avatar；默认值与旧渲染一致。
- **未支持（2 键）**：`sendOnEnter`/`pasteLongTextAsFile` 在 SettingDisplayPage 显式替换为不可编辑的「未支持」行（NavRow，无 toggle、不写盘）。输入层无管线，不新建。
- 修正设置页误导标签：「显示用户头像」→「显示模型图标」。

### F19 — Slides 字体占位
SkillsPage 删除「Slides 字体资源」入口（定时任务行改为 isLast）。SettingSlidesFontPage 加 `SLIDES_FONT_SUPPORTED=false` 常量：不调用伪加载、setFamily/setSize no-op、移除「完成」按钮（仅返回），正文明确「当前版本未支持」。未新增「禁用」prop，避免改 PageTemplate。

### F44 — 文档附件打开路径
`ContentAttachmentChip` 增 `@Prop openable=false` + `onOpen`；`openable=true` 时显可点箭头并使用 `priorityGesture(TapGesture)` 抢占点击，避免冒泡到 user 消息父级编辑手势。AgentMessageRow：`AgentMessageRowHostCapabilities` 增 `openDocument`，`NO_..._CAPABILITIES` 置 false；新增 `onOpenDocument:(part:UIMessagePartDocument)=>void`；仅 `capabilities.openDocument` 为真时 `document` chip 传 `openable:true`（video/audio 保持纯展示，与当前无生产路径一致）。ChatPage/Novel 的 capabilities 字面量已含 `openDocument`。

### R08 — 精确 part locator（按协调者纠正）
签名（已与 ChatPage 对齐）：
- `onApproveTool: (toolCallId: string, locator?: ToolApprovalPartLocator) => void`
- `onDenyTool: (toolCallId: string, locator?: ToolApprovalPartLocator) => void`
- `onAnswerTool: (toolCallId: string, answer: string, locator?: ToolApprovalPartLocator) => void`

`locatorFor(t)`：先 `this.message.parts.indexOf(t)` 取真实 parts 下标；`<0`（group 复制体）时用 `streamToolIndex(t)` 作**匹配键**在原 parts 中唯一查找工具 part，取候选真实下标；`count!==1` 则返回 undefined（拒歧义，不传 locator）。**不把流内 stream index 直接当 partIndex**。`step.tool` 由 indexOf 定位；Novel 旧 `(id)=>` 回调兼容（少参函数可赋给多可选参类型）。

---

## 共享接线需求（主代理/其他 owner）
1. ChatPage 已自行接入 `openDocument:true` 与三处可选 locator（已核对 ChatPage:173-190/3373-3413/4422-4491）。
2. F16 props 需 ChatPage 从 `loadDisplaySetting` 读取后透传给三处 AgentMessageRow；命名：`showAssistantBubble/showModelName/showModelIcon/fontSizeRatio/codeBlockAutoCollapse/showLineNumbers`（默认值与旧渲染一致，未接线亦不回归）。NovelWorkspacePage 未接 F16 props（默认值保持原样）。
3. F44 ChatPage 的 `onOpenDocument` 修复 owner 已说明会支持 `data:` 与 `file://`（用户 pick 文档多为 data URL）；组件侧已就绪。
4. `openDocument` 为 capabilities **必填** 字段，任何新 capabilities 字面量须补该键（ChatPage/Novel 已补）。

## 测试与静态检查
- 未新增测试（纯 UI/展示，无设备不可断言；契约要求不堆 assert-only/source-string 测试）。未跑 hvigor/全仓 lint（主代理统一）。
- `harmony/scripts/lint-arkts.sh <file>`（LINT_EXIT_ON=none）逐文件：
  - ChatMessageAvatar / ChatMessageTools / ContentAttachmentChip / ChatMessageAskUserStep / AgentMessageRow / ChatMessageSubAgentStep / ChatMessageCouncilStep / MarkdownText / SettingDisplayPage / SkillsPage / SettingSlidesFontPage → **0 error**。
  - ContentAttachmentChip、ChatMessageAskUserStep 各 1 条既有告警 `@performance/avoid-overusing-custom-component-check`（组件定义行，非本次引入的回归，与基线同类告警一致）。
- 未做设备/模拟器截图目测（当前无在线设备）；不声称 UI 已验证通过。

## 遗留/未解决
- F12 阶段依赖工具 output 是否真有 `phase` 等字段；若上游从不输出，则 UI 恒只显示状态（符合「无阶段事件则显示运行状态」要求，但阶段文案在鸿蒙端可能长期不出现）。已按契约不伪造。
- SettingSlidesFontPage 路由 `pages/SettingSlidesFontPage` 仍存在（仅供潜在直连），已无入口；如需彻底下线可删除该页，本 agent 未删以免影响其他 owner 的 route 表。
