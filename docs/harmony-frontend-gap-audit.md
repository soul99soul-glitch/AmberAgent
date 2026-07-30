# AmberAgent 鸿蒙迁移 — 前端缺口完整 Audit

> 产出时间：2026-07-30
> 分支：`harmony-preview`
> 仓库 HEAD：`bc6ac7a87`（D-132c state-pointer）
> 审计方法：逐文件对照 Android 源码 + Harmony 源码，标注完整/部分/空壳/缺失、后端就绪度、依赖关系与优先级。
> 目的：形成可执行的前端迁移路线图，而非笼统百分比。

---

## 1. 执行摘要

### 1.1 前后端覆盖率总览

| 维度 | Android 基准 | Harmony 现状 | 覆盖率 | 说明 |
|---|---|---|---|---|
| 后端域逻辑/工具/协议 | ~同等规模 | 2170 测试，D-001..D-132c | **~90%** | subagent/search/MCP/memory/skills/workspace/deepread 全链路 source-only 闭环 |
| 设置类 UI 页面 | 223 Composable | 24 页面 + 2 组件 | **~65%** | 表单型设置页多数可用，但缺通用原语层 |
| 核心聊天交互 UI | ChatPage.kt 2554 行 + 10 模块 | ChatPage.ets 2767 行（内联） | **~20%** | 消息渲染质量远低于 Android，无 Markdown/代码高亮 |
| 消息渲染组件 | 17 ChatMessage*.kt + 7 依赖 = 10,020 行 | 0 独立组件（全内联 ChatPage） | **~5%** | 仅 MessageRow 一个粗粒度组件 |
| 设备端验证 | — | 0 项 verified | **0%** | Gate 0 blocked，全部 2170 测试为 JVM/node |

### 1.2 代码量对比

| 维度 | Android | Harmony |
|---|---|---|
| UI 总代码 | 83,468 行（223 Composable） | 12,066 行（24 页面 + 2 组件） |
| 消息渲染组件 | 10,020 行（17 + 7 文件） | 0 行独立组件（内联在 ChatPage.ets） |
| richtext（Markdown/高亮/表格） | ~8,000 行（23 文件） | 0 行 |
| 聊天输入组件（ai/） | ~5,800 行（12 文件） | 0 行独立组件（内联 TextInput） |
| 通用 UI 原语（ui/） | ~6,500 行（41 文件） | 0 行（仅有杂志风 design/components） |

### 1.3 三大硬缺口

1. **Markdown 渲染完全空白** — ChatPage 所有 assistant 文本用裸 `Text()` 直出，标题/列表/链接/粗斜体/代码块全部丢失。ArticleRenderer.ets 自承「MVP 用 Text 直出，TODO(phase6)」。
2. **消息组件族未拆分** — Android 17 个独立 `ChatMessage*.kt` 在 Harmony 一个都没有；全部渲染逻辑内联在 `MessageRow.build()` 内。
3. **4/8 part 类型静默丢弃** — `video`/`audio`/`document`/`mini_app` 四种 part 无渲染、无占位、无 else 兜底。assistant 回复这些类型时用户看不到任何痕迹。

---

## 2. 消息渲染组件审计

Android 源码目录：`app/src/main/java/app/amber/feature/ui/components/message/`

### 2.1 逐组件清单（按依赖 Tier 排序）

#### Tier A — 基础/叶节点

| # | 文件 | 行 | 功能 | 域依赖 | Harmony 后端 | 复杂度 |
|---|---|---|---|---|---|---|
| 1 | ChatMessageCot.kt | 319 | 消息分组与折叠纯逻辑（`groupMessageParts`/`ThinkingStep`/`MessagePartBlock`） | UIMessagePart, MessageRenderCache | **缺** | 复杂 |
| 2 | ChatMessageCommon.kt | 165 | 可选中容器、引用注释列表、citation 点击 | UIMessageAnnotation, Tool | 部分 | 中等 |
| 3 | ChatMessageAvatar.kt | 167 | 用户/助手头像（昵称/日期/模型名/状态点） | UIMessage, Assistant | **就绪** | 简单 |
| 4 | ChatMessageBranch.kt | 93 | 分支选择器（◀ n/total ▶） | MessageNode | **就绪** | 简单 |
| 5 | ChatMessageCopySheet.kt | 130 | 复制底部 sheet（全部 Text part 列表） | UIMessagePart.Text | **就绪** | 简单 |

#### Tier B — 依赖 Tier A

| # | 文件 | 行 | 功能 | 域依赖 | Harmony 后端 | 复杂度 |
|---|---|---|---|---|---|---|
| 6 | ChatMessageRenderers.kt | 436 | Markdown/Widget 分发、虚拟化文本、bubble 分段 | GenerativeWidgetParser, MarkdownBlock, MessageRenderCache | **缺** | 复杂 |
| 7 | ChatMessageActions.kt | 416 | 操作按钮（复制/重生成/TTS/更多）+ 底部菜单 | UIMessage, MessageNode, TTS Flow | **就绪** | 中等 |
| 8 | ChatMessageTools.kt | 985 | 工具调用胶囊+审批+generate_image+workspace预览+search | UIMessagePart.Tool, ToolApprovalState, tool_activity | **部分就绪** | 复杂 |
| 9 | ChatMessageAskUserStep.kt | 461 | 多问题表单（single/multi/text+提交+已答展示）+拒绝弹窗 | UIMessagePart.Tool, ask_user_questions | **就绪** | 中等 |
| 10 | ChatMessageReasoning.kt | 451 | 思考卡片（collapsed/preview/expanded 三态+时长+budget） | UIMessagePart.Reasoning, reasoning_display | **就绪** | 复杂 |

#### Tier C — 依赖 Tier A/B

| # | 文件 | 行 | 功能 | 域依赖 | Harmony 后端 | 复杂度 |
|---|---|---|---|---|---|---|
| 11 | ChatMessageActionFooter.kt | 91 | 引用+操作行统一尾部（Hidden/Reserved/Visible） | UIMessageAnnotation, Actions | **就绪** | 简单 |
| 12 | ChatMessageCouncilStep.kt | 705 | Council 运行卡片（phase 轮播+席位 sheet+轮次分隔） | ThinkingStep.CouncilTaskStep, ModelCouncilManager | **缺** | 复杂 |
| 13 | ChatMessageSubAgentStep.kt | 393 | SubAgent 任务卡片（phase 轮播+实时流式 sheet） | ThinkingStep.SubAgentTaskStep, SubAgentManager.liveTextFlow | **部分就绪** | 复杂 |
| 14 | ChatMessageToolPreviewSheet.kt | 365 | 工具详情 sheet（搜索/抓取/JSON 高亮/memory 删除） | tool_activity, HighlightText, MemoryRepository | 部分 | 中等 |

#### Tier D — 块分发

| # | 文件 | 行 | 功能 | 域依赖 | Harmony 后端 | 复杂度 |
|---|---|---|---|---|---|---|
| 15 | ChatMessageMessagePartsBlock.kt | 565 | 按 groupMessageParts 分发 Thinking/Content/SubAgent/Council 块 | groupMessageParts, Renderers, Reasoning, Tools, Council, SubAgent | **缺** | 复杂 |

#### Tier E — 虚拟化与顶层入口

| # | 文件 | 行 | 功能 | 域依赖 | Harmony 后端 | 复杂度 |
|---|---|---|---|---|---|---|
| 16 | ChatMessageVirtualItems.kt | 544 | 消息拆分为 LazyColumn item（Header/Thinking/Content/Footer） | groupMessageParts, richtext, PartsBlock | **缺** | 复杂 |
| 17 | ChatMessage.kt | 240 | 单条消息顶层组合（头像+PartsBlock+Footer+Sheets） | MessageNode, UIMessage, Assistant | **就绪** | 中等 |

**依赖文件（非 ChatMessage 前缀但强依赖）：**

| 文件 | 行 | 功能 | Harmony |
|---|---|---|---|
| MessageRenderCache.kt | — | `visualRegexText`/`toolInputJson`/`toolOutputJson`/`markdownParseResult` 聚合门面 | 部分（toolInputJson/toolOutputJson 由 tool_activity.ts 覆盖；visualRegexText/markdownParseResult 缺） |
| SearchPresentation.kt | — | `rememberSearchPresentation`/`deriveSearchPresentation`/`SearchImageGallery` | **缺** |

### 2.2 后端就绪度分类汇总

#### A. 后端就绪，可直接做 UI（7 个）

| 组件 | 就绪的 Harmony 模块 |
|---|---|
| ChatMessageReasoning | `reasoning_display.ts`（D-053，逐行复刻三态/时长/budget/标题/流式状态迁移） |
| ChatMessageAskUserStep | `ask_user_questions.ts`（D-105，解析/载荷/提交判定/已答展示全复刻） |
| ChatMessageBranch | `conversation.ts`（MessageNode/selectIndex） |
| ChatMessageAvatar | `assistant.ts` + `message.ts` |
| ChatMessageCopySheet | `message.ts`（Text part / toText） |
| ChatMessageActions | `message.ts`/`conversation.ts`（TTS 为平台服务，待接） |
| ChatMessage（顶层） | 同上模型齐备（纯编排层） |

> 补充：`ChatMessageTools` 的核心状态/失败/标题/输出 JSON 逻辑也基本就绪（`tool_activity.ts` + `tool_approval.ts` + `message.ts`），仅胶囊图标映射表与 R.string 标题属 UI 层需自行补；审批交互纯 UI。可视为"后端就绪、UI 待补图标资源"。

#### B. 后端部分就绪（3 个）

| 组件 | 已有 | 缺失 |
|---|---|---|
| ChatMessageSubAgentStep | `subagent_models.ts` + `subagent_transcript.ts` + `subagent_manager.ts`（snapshot/start/cancel/finish） | 公开 `liveTextFlow(runId)`/`livePartsFlow(runId)`——卡片静态展示+transcript 回退可做，实时 token 流式 sheet 缺 flow API |
| ChatMessageToolPreviewSheet | `tool_activity.ts`（JSON 提取） | 搜索结果结构解析、memory 删除、HighlightText 渲染层 |
| ChatMessageCommon | `UIMessageAnnotation` 类型已移植 | 引用点击/展开折叠属 UI；`MessageRenderCache.visualRegexText` 缺（边界） |

#### C. 后端纯逻辑缺失（6 个）

| 组件 | 缺失的纯逻辑 | 优先级 |
|---|---|---|
| **ChatMessageCot** | `groupMessageParts`/`ThinkingStep`/`MessagePartBlock`/`coalesceSubAgent`/`coalesceCouncil`/`mergeDuplicateRunBlocks` 全缺 | **最高**（解锁 PartsBlock/VirtualItems/Council/SubAgent 卡片 lifting） |
| ChatMessageMessagePartsBlock | 依赖 Cot 的 groupMessageParts + `MessageRenderCache.visualRegexText`/`markdownParseResult` + 生成式 widget + richtext | 高 |
| ChatMessageVirtualItems | `buildChatMessageVirtualItems`/`ChatMessageVirtualItem`/`shouldVirtualizeMarkdownContent` 全缺 | 高 |
| ChatMessageRenderers | `GenerativeWidgetParser`/richtext(`MarkdownBlock`/`topLevelBlockCount`)/`visualRegexText` 全缺 | 高 |
| ChatMessageCouncilStep | `ModelCouncilManager` 运行时类 + `liveTextFlow` + 全部 council 提取纯函数（status/席位/轮次/综合裁决）全缺 | 中（独立于前 4 项） |
| SearchPresentation | `rememberSearchPresentation`/`deriveSearchPresentation`/`SearchImageGallery` 全缺 | 中 |

---

## 3. ChatPage.ets 现状审计

文件：`harmony/entry/src/main/ets/pages/ChatPage.ets`（2767 行）

### 3.1 渲染覆盖

`UIMessagePart` 联合类型共 8 种变体（`message.ts:37-111`），ChatPage 仅渲染 4 种：

| part 类型 | 渲染方式 | 行号 | 状态 |
|---|---|---|---|
| `text` | 纯 `Text(p.text)`，fontSize 15 | 324-330 | **无 Markdown** |
| `image` | `Image(p.url)`，固定 width 160 | 331-338 | 基本可用 |
| `reasoning` | 可折叠卡片（三态） | 339-379 | 后端就绪，UI 内联 |
| `tool` | 工具卡片（MVP） | 380-548 | 后端就绪，UI 内联 |
| `video` | — | — | **静默丢弃** |
| `audio` | — | — | **静默丢弃** |
| `document` | — | — | **静默丢弃**（仅有发送侧 transformer） |
| `mini_app` | — | — | **静默丢弃**（注释标 P1 未移植） |

> 链结尾无 `else` 兜底分支（行 548 闭合）。assistant 回 `mini_app`/`document`/`audio`/`video` part 时，用户在 UI 上看不到任何痕迹。

### 3.2 关键缺口

| 缺口 | 现状 | Android 对照 |
|---|---|---|
| **Markdown 渲染** | 全裸 `Text()`，无富文本/无代码高亮/无列表/无链接 | richtext/ 23 文件 ~8000 行 |
| **代码块高亮** | 无 | HighlightCodeBlock.kt 517 行 |
| **tool 卡片展开/折叠** | 无，output 永远只显示首条 text 的 3 行 | ChatMessageTools.kt 985 行 |
| **多条 output** | 丢弃（仅取 output[0]） | 完整展示 |
| **非 text output** | 丢弃（image/document 等） | 各类型有独立渲染 |
| **LazyForEach** | `ForEach` 全量构建（行 2493） | LazyColumn 虚拟化 |
| **流式路径对称性** | send 直接 slice vs regen 经 visualTransformers | 两条路径一致 |
| **独立组件** | 仅 MessageRow 一个；part 渲染全内联 | 17 个独立组件 |

### 3.3 已有功能（非全空）

| 功能 | 状态 | 行号 |
|---|---|---|
| 流式渲染（streamingTail/regenTail） | 有 | 2519-2529 |
| reasoning 卡片（三态+时长） | 有（内联） | 339-379 |
| 工具审批按钮 | 有 | 508-525 |
| ask_user 表单 | 有（内联） | 393-507 |
| 分支切换 UI | 有 | 550-581 |
| 消息编辑/重生成/fork/删除 | 有 | 567-589 |
| 工具循环/agent task/cron/subagent 装配 | 有（D-057..D-132c） | buildToolLoop |
| 沙盒活动时间线 | 有（内联） | sandboxSheet |

---

## 4. 非消息类 UI 组件审计

Android 源码目录：`app/src/main/java/app/amber/feature/ui/components/`

### 4.1 richtext/（Markdown / 代码高亮 / 公式 / Mermaid）— Harmony 全缺

| 文件 | 行 | 功能 | Harmony |
|---|---|---|---|
| Markdown.kt | 3476 | 主 Markdown 渲染器（AST→Compose） | **缺** |
| MarkdownNew.kt | 1555 | HTML-based Markdown 渲染 | **缺** |
| HighlightCodeBlock.kt | 517 | 代码块高亮+行号+复制 | **缺** |
| Mermaid.kt | 448 | Mermaid 图表渲染（WebView） | **缺** |
| LatexText.kt | 120 | LaTeX 公式渲染 | **缺** |
| MathBlock.kt | 53 | 行内/块级数学公式 | **缺** |
| SimpleHtmlBlock.kt | 696 | 简单 HTML 块渲染 | **缺** |
| StreamingDisplay.kt | 373 | 流式文本显示控制（速度/回溯/尾光标） | **缺** |
| ZoomableAsyncImage.kt | 71 | 可缩放异步图片 | **缺** |
| 其余 14 文件 | ~1400 | Markdown 树/native bridge/search 图片块/diff 归一化等 | **全缺** |

> 小计：~8000 行，Harmony 0 行。这是聊天内容可读性的头号缺口。

### 4.2 ai/（聊天输入与选择器）— Harmony 仅内联 TextInput

| 文件 | 行 | 功能 | Harmony |
|---|---|---|---|
| ChatInput.kt | 868 | 主聊天输入框（展开态/发送门） | **缺**（内联单 TextInput） |
| ChatInputComposers.kt | 1030 | 输入行+斜杠命令面板+思考页脚 | **缺** |
| ChatInputAttachments.kt | 603 | 图片/媒体附件输入行+预览+chip | 部分（D-038 选图，无组件化） |
| ChatInputSandbox.kt | 1161 | 沙盒/WebView 预览操作状态 | **缺** |
| ChatInputUsage.kt | 663 | 上下文用量指示器 | **缺**（ChatPage 有基本指示） |
| ModelList.kt | 1260 | 模型选择器+列表+分组 | 有独立页（非可复用组件） |
| AssistantPicker.kt | 246 | 助手选择器 sheet | 有独立页 |
| TopModelMenu.kt | 274 | 顶部模型菜单 | **缺** |
| ReasoningPicker.kt | 303 | 推理深度选择器 | **缺** |
| ExtensionContent.kt | 194 | 模式注入/Lorebook/Skills 内容 | 有独立页 |
| CompressContextDialog.kt | 175 | 压缩上下文对话框 | **缺** |
| MentionRoles.kt | 66 | @ 角色提及项 | **缺** |

> 小计：~5800 行，Harmony 仅内联 TextInput + 基本附件选图。

### 4.3 ui/（通用原语）— Harmony 全缺

按功能分组（41 文件 ~6500 行）：

| 类别 | 关键文件 | 功能 | Harmony |
|---|---|---|---|
| 输入原语 | Input(202)/TextArea(254)/Select(117)/Switch(180) | 表单基础控件 | **缺**（裸 TextInput/TextArea/Toggle 内联） |
| 容器/布局 | CardGroup(192)/Form(83)/ToggleSurface(36)/StickyHeader(23)/ListSelectableItem(39) | 卡片/表单/折叠容器 | **缺** |
| 反馈/加载 | DotLoading(48)/RabbitLoading(35)/ConfirmDialog(37)/ErrorCard(175)/Tooltip(28) | 加载/确认/错误/提示 | **缺** |
| 数据展示 | JsonTree(310)/Tag(94)/TagList(209)/NotionListRow(124)/NotionSlider(147) | JSON 树/标签/列表行/滑块 | **缺** |
| 媒体 | ImagePreviewDialog(286)/Favicon(95)/QRCode(46)/BitmapComposer(144) | 图片预览/favicon/二维码/位图 | **缺** |
| 功能 | ShareSheet(131)/Export(99)/KeepScreenOn(19)/Greeting(36) | 分享/导出/常亮/问候 | **缺** |
| 头像 | UIAvatar(410)/SubAgentAvatar(289) | 通用头像/子代理头像 | **缺** |
| TTS | TTSController(213) | TTS 播放控制器 | **缺**（仅工具开关文案，无引擎） |
| 悬浮窗 | FloatingWindow(72) | 悬浮窗 | **缺** |
| 权限 | permission/ 5 文件 810 行 | 运行时权限说明弹窗+状态机 | **缺**（仅数据层+策略开关页） |
| 图标 | icons/ 4 文件 | 推理/Discord/Heart/QQ 图标 | **缺** |
| 推理展示 | ChainOfThought(600) | 思维链展开折叠 | 部分（ChatPage 内联 reasoning 卡） |

### 4.4 ds/（设计系统原语）— Harmony 仅有杂志风

| Android | 行 | 功能 | Harmony |
|---|---|---|---|
| AmberPrimitives.kt | 186 | `pressable`/`AmberCard`/`Hairline`/`BtnInk`/`BtnAccent` | **缺**（design/components.ets 是杂志风 RubricHead/LeadCard/IndexRow，非聊天原语） |
| AmberPreview.kt | 116 | 设计系统预览脚手架 | **缺** |
| CoreScreenPreview.kt | 160 | 多主题预览 | **缺** |

> Harmony `design/tokens.ets` 有颜色/间距 token（BG/SURFACE/INK/ACCENT/RADIUS），与 Android token 概念对齐，但**无聊天组件原语层**。

### 4.5 其他目录

| 目录 | 文件 | 行 | 功能 | Harmony |
|---|---|---|---|---|
| workspace/ | 3 文件 | 974 | 文件 sheet/预览/VM | **缺**（仅 WorkspaceStore.ets 数据层） |
| webview/ | 1 文件 | 389 | 通用 WebView 容器 | **缺**（明确标 P1 未移植） |
| table/ | DataTable.kt | 289 | Markdown 表格渲染 | **缺** |
| nav/ | BackButton.kt | 28 | 返回按钮 | 无组件（系统返回） |
| debug/ | StreamProfilerOverlay.kt | 95 | 流式性能浮层 | **缺** |

---

## 5. Markdown 渲染路径决策

### 已确认方案：ArkUI RichText + HTML

**技术路径：** markdown → HTML 转换 + ArkUI `RichText` 组件渲染

**Android 对照：**
- Android 有两条路径：`Markdown.kt`（自建 AST→Compose，3476 行）和 `MarkdownNew.kt`（HTML-based，1555 行）
- Harmony 选 HTML 路径，对齐后者思路

**markdown→html 转换实现选项（待 spike 确认）：**
1. 引入 `marked.js` via ohpm（JS/ArkTS 互操作）— 功能最全，但需验证 ohpm 可用性 + ArkTS JS 互操作开销
2. ArkTS 自建轻量 markdown→html converter — 无外部依赖，但覆盖 Markdown 子集有限

**RichText 能力边界（预期）：**
- ✅ 支持：标题(h1-h6)、段落、粗体/斜体、链接、列表(ul/ol)、代码块(pre/code)、引用(blockquote)、图片(img)、表格(table)、水平线(hr)
- ❌ 不支持：语法高亮（需后续 HighlightCodeBlock 独立组件）、Mermaid 图表（需 Web 组件）、LaTeX 公式（需 KaTeX 或 Web 组件）、流式逐字揭示（需自建）

**风险：**
- RichText 实际渲染能力需设备验证（Gate 0 解锁后首轮 spike 项）
- 流式 Markdown（增量更新）性能待验证
- 代码块在 RichText 内无语法高亮，需独立 `HighlightCodeBlock` 组件补充

### 降级策略

若 RichText 能力不足或性能不达标：
1. 退回 Web 组件渲染（marked.js + highlight.js，功能最全但内存/性能开销大）
2. 退回自建 ArkUI 组件树（最忠实但开发量最大）

---

## 6. 已有 24 页面状态盘点

按交互密度（@State/@Prop 计数 + handler 计数）和功能完整度分类：

### 完整功能（交互密集，后端已对接）

| 页面 | 行 | @State/Prop | handlers | 功能 |
|---|---|---|---|---|
| ChatPage | 2767 | 35 | 34 | 聊天主页面（流式/工具循环/审批/分支/编辑/重生成/fork/删除/subagent 装配） |
| ChatPromptsPage | 870 | 43 | 42 | agent_prompt_config 工具（subagent/council/image/compaction 四镜像读写） |
| MemoryPage | 1115 | 33 | 28 | 记忆管理（召回开关/dream plan/候选操作/导入导出） |
| NovelWorkspacePage | 798 | 26 | 35 | 小说创作工作台 |
| ChatSearchSettingsPage | 842 | 24 | 20 | 搜索设置（16 源开关/参数/API key） |
| ChatAssistantsPage | 549 | 18 | 27 | 助手配置（参数/正则/头像/技能/MCP） |
| ChatProviderSettingsPage | 441 | 19 | 11 | Provider 设置（三变体/auth/baseUrl/model 列表） |
| HistoryPage | 449 | 16 | 17 | 历史会话列表/搜索/删除 |
| ChatTaskModelsPage | 402 | 18 | 9 | 任务模型选择（title/suggestion/compress） |
| ChatMcpSettingsPage | 644 | 10 | 23 | MCP 配置/导入/状态 |
| ChatModelParamsPage | 307 | 7 | 10 | 模型参数（temperature/topP/maxTokens/reasoning） |

### 部分功能（基本可用，有缺口）

| 页面 | 行 | @State/Prop | handlers | 缺口 |
|---|---|---|---|---|
| Index | 308 | 10 | 11 | 主导航（缺部分页面入口） |
| ChatListPage | 297 | 4 | 15 | 会话列表（基本可用） |
| CouncilPage | 229 | 7 | 7 | Council 列表（运行时 UI 缺 ChatMessageCouncilStep） |
| CouncilRunPage | 239 | 9 | 4 | Council 运行详情（同上） |
| DeepReadArticlePage | 274 | 6 | 2 | 深读文章（ArticleRenderer 用 Text 直出，无 Markdown） |
| ChatAssistantExtensionsPage | 220 | 5 | 7 | 模式注入/Lorebook（基本可用） |
| ChatAgentExecutionPage | 226 | 5 | 6 | 执行参数（maxSteps/retry/previewMode） |
| NovelProjectsPage | 195 | 7 | 13 | 小说项目列表 |
| ChatAssistantMcpPage | 214 | 4 | 2 | 助手 MCP 白名单 |
| ChatAssistantLocalToolsPage | 214 | 2 | 2 | 本地工具开关（TTS 仅有文案无引擎） |
| ChatAgentPermissionsPage | 165 | 4 | 2 | 权限策略开关（autoApprove/speculative） |

### 轻量/选择器页（功能完整但简单）

| 页面 | 行 | 功能 |
|---|---|---|
| ChatModelPickerPage | 120 | 模型选择器 |
| ChatTaskModelPickerPage | 181 | 任务模型选择器 |

### 缺失页面（Android 有，Harmony 无）

| Android 页面 | 功能 | 优先级 |
|---|---|---|
| 设置页（系统权限管理） | 运行时权限说明/跳转 | 中（依赖设备） |
| cron/任务管理页 | 后台任务看板 | 低（工具已有，UI 缺） |
| SkillsPage / SkillDetailPage | 技能库浏览/详情 | 低 |
| QuickMessagesPage | 快捷消息管理 | 低 |
| 文件 sheet | 工作区文件浏览 | 低 |

---

## 7. 迁移路线图

### 第一梯队：聊天内容可读性硬缺口（最高优先级）

> 目标：让 AI 回复从"裸文本"变为"可读 Markdown"

| 序号 | 任务 | 类型 | 后端就绪 | 设备依赖 | 预估规模 |
|---|---|---|---|---|---|
| 1.1 | **Markdown 渲染器**（RichText + HTML 路径） | 新建组件 | N/A | spike 验证 | 中 |
| 1.2 | **代码块高亮** HighlightCodeBlock | 新建组件 | N/A | 否 | 中 |
| 1.3 | **DataTable** Markdown 表格渲染 | 新建组件 | N/A | 否 | 小 |
| 1.4 | **groupMessageParts 纯逻辑**（ChatMessageCot） | 域层移植 | Android 有 | 否 | 中 |
| 1.5 | **MessageRenderCache 门面**（visualRegexText/markdownParseResult 聚合） | 域层移植 | 部分 | 否 | 小 |
| 1.6 | **ChatMessageReasoning 组件**（从 ChatPage 内联抽出） | UI 拆分 | ✅ D-053 | 否 | 中 |
| 1.7 | **ChatMessageTools 组件**（工具卡片+审批+展开折叠） | UI 拆分 | ✅ D-122 | 否 | 大 |
| 1.8 | **ChatMessageAskUserStep 组件**（从 ChatPage 内联抽出） | UI 拆分 | ✅ D-105 | 否 | 中 |
| 1.9 | **补齐 4 种 part 渲染**（video/audio/document/mini_app 占位或降级） | UI 补齐 | N/A | 部分 | 小 |
| 1.10 | **ChatPage 接入 Markdown 渲染**（text part → RichText） | UI 集成 | 依赖 1.1 | spike | 小 |

### 第二梯队：输入与控制交互（中优先级）

> 目标：聊天输入从"单 TextInput"变为"完整输入组件"

| 序号 | 任务 | 类型 | 后端就绪 | 设备依赖 | 预估规模 |
|---|---|---|---|---|---|
| 2.1 | **ds/ 基础原语层**（AmberCard/Hairline/BtnInk/BtnAccent/pressable） | 新建设计系统 | N/A | 否 | 小 |
| 2.2 | **ChatInput 组件化**（输入行+发送门+附件预览+斜杠命令） | UI 新建 | 部分 | 否 | 大 |
| 2.3 | **ChatInputUsage** 上下文用量指示器组件化 | UI 拆分 | ✅ D-110 | 否 | 小 |
| 2.4 | **TTS 控制器 + 引擎** | 平台移植 | ❌ | 是（TTS 引擎） | 大 |
| 2.5 | **通用 UI 原语**（Form/Switch/Select/Tag/ConfirmDialog/ErrorCard/JsonTree 等） | 新建组件库 | N/A | 否 | 中 |
| 2.6 | **ChatMessageActions 组件**（操作按钮+底部菜单） | UI 拆分 | ✅ | 否 | 中 |
| 2.7 | **ChatMessageBranch/CopySheet/Avatar/Footer 组件** | UI 拆分 | ✅ | 否 | 小 |

### 第三梯队：能力扩展（低优先级，依赖第一梯队）

> 目标：补齐高级消息类型和聚合 UI

| 序号 | 任务 | 类型 | 后端就绪 | 设备依赖 | 预估规模 |
|---|---|---|---|---|---|
| 3.1 | **SubAgentManager liveTextFlow/livePartsFlow 公开 API** | 域层小改 | ✅ 已有私有 map | 否 | 小 |
| 3.2 | **ChatMessageSubAgentStep 组件** | UI 新建 | 部分（3.1 后就绪） | 否 | 中 |
| 3.3 | **ChatMessageCouncilStep 组件** | UI 新建 | ❌ 缺 council 运行时提取纯函数 | 否 | 大 |
| 3.4 | **GenerativeWidgetCard 渲染** | UI+域 | ❌ 缺 GenerativeWidgetParser | 否 | 大 |
| 3.5 | **GeneratedImageCarousel 渲染** | UI 新建 | ❌ | 否 | 中 |
| 3.6 | **Mermaid / LaTeX 渲染** | UI 新建 | N/A | 是（Web 组件） | 中 |
| 3.7 | **ChatMessageVirtualItems 虚拟化** | UI+域 | ❌ 缺 groupMessageParts | 否 | 大 |
| 3.8 | **LazyForEach 替换 ForEach** | UI 重构 | N/A | 否 | 中 |

### 第四梯队：平台能力（依赖设备验证）

> 目标：补齐需要平台 API 的 UI

| 序号 | 任务 | 设备依赖 |
|---|---|---|
| 4.1 | WebView 通用组件 | 是（ArkWeb） |
| 4.2 | FloatingWindow 悬浮窗 | 是 |
| 4.3 | Permission UI 组件（运行时权限弹窗/状态机） | 是 |
| 4.4 | ImagePreviewDialog 可缩放图片预览 | 是 |
| 4.5 | WorkspaceFileSheet 文件浏览 sheet | 否（数据层有，UI 缺） |

---

## 8. 设备验证依赖标注

### source-only 可推进（不需设备）

- 第一梯队全部（1.1-1.10）— Markdown 渲染器代码可写，RichText 实际效果待设备验证但不阻塞编码
- 第二梯队 2.1/2.2/2.3/2.5/2.6/2.7 — 组件化拆分
- 第三梯队 3.1/3.2/3.3/3.4/3.5/3.7/3.8 — 域逻辑+UI 编码
- 第四梯队 4.5 — 文件 sheet UI

### 需设备验证（Gate 0 解锁后）

- Markdown RichText 实际渲染效果（首轮 spike）
- 流式 Markdown 增量更新性能
- LazyForEach 长列表性能
- TTS 引擎（2.4）
- Mermaid/LaTeX Web 组件（3.6）
- WebView/FloatingWindow/Permission（4.1/4.2/4.3）
- ImagePreviewDialog 图片缩放（4.4）

### 设备解锁后首轮前端验证顺序

1. **RichText Markdown spike** — 写最小 Markdown→HTML→RichText demo，验证基础格式渲染
2. **LazyForEach 性能** — 验证长会话（100+ 消息）滚动流畅度
3. **流式 Markdown** — 验证增量 HTML 更新不闪烁/不卡顿
4. **代码块高亮** — 验证 RichText 内 pre/code 渲染效果

---

## 9. 方法论反思

### 逐片 TDD 的盲区

此前的迁移以 D-编号逐片推进后端域逻辑，每片都有 RED→GREEN→门禁→双提交的完整闭环。这种方法保证了**局部正确性**，但容易忽略**全局覆盖率失衡**：

- 每片都"绿" ≠ 整体可用
- 后端 2170 测试全绿 ≠ 前端可读
- 逐片推进缺乏**阶段性全局复核**，导致后端远超前端

### 建议

1. **每完成一个里程碑（M3/M4/M5）后做一次全局 audit**，而非只看当前 D-编号
2. **前后端覆盖率交叉追踪** — 在 MIGRATION_STATE.yaml 中记录前端覆盖率指标
3. **下一阶段优先级应从前端缺口驱动**，而非继续推后端新功能
4. **设备解锁后优先验证前端 spike**（RichText/LazyForEach），而非继续写 source-only 代码

---

## 附录：关键文件路径

### Android UI 源码（只读基准）

```
app/src/main/java/app/amber/feature/ui/components/
├── message/        # 17 ChatMessage*.kt + 2 依赖 = 10,020 行
├── richtext/       # 23 文件 ~8000 行（Markdown/高亮/表格/Mermaid/LaTeX）
├── ai/             # 12 文件 ~5800 行（ChatInput/选择器）
├── ui/             # 41 文件 ~6500 行（通用原语）
├── ds/             # 3 文件（设计系统原语）
├── workspace/      # 3 文件（文件 sheet/预览/VM）
├── webview/        # 1 文件（WebView 容器）
├── table/          # 1 文件（DataTable）
├── nav/            # 1 文件（返回按钮）
└── debug/          # 1 文件（性能浮层）
```

### Harmony UI 源码（当前）

```
harmony/entry/src/main/ets/
├── pages/           # 24 页面 = 12,066 行
├── components/      # 2 组件（ArticleRenderer + StageProgressCard，DeepRead 专用）
├── design/          # tokens.ets + components.ets（杂志风原语，非聊天）
├── platform_impl/   # 平台 adapter（非 UI）
└── di/              # AppContainer（非 UI）
```

### Harmony chat-domain 已就绪的后端模块（消息渲染可复用）

```
harmony/chat/src/main/ets/chat/
├── reasoning_display.ts      # D-053 reasoning 卡片纯逻辑
├── ask_user_questions.ts     # D-105 ask_user 解析/载荷
├── tool_activity.ts          # D-122 工具活动时间线/输出 JSON
├── tool_approval.ts          # D-057 审批状态机
├── message.ts                # UIMessage/UIMessagePart/MessageRole
├── conversation.ts           # MessageNode/Conversation
├── assistant.ts              # Assistant 模型
├── subagent_models.ts        # D-132a subagent 运行时模型
├── subagent_transcript.ts    # D-132a transcript reader
└── subagent_manager.ts       # D-132b manager（缺 liveTextFlow 公开 API）
```
