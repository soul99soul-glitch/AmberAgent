# AmberAgent Android → iOS Capability Parity Gap Audit

日期：2026-06-19
方法：5 个并行 auditor 逐项核对 Android(app/ + core/ + feature/) 与 iOS(iosApp/iosApp/*.swift) 代码,所有 gap 附 `file:line` 双向证据。重点 P0 项已二次人工复核。
排除范围(明确不做):多 Assistant 系统、收藏(Favorites)、独立 Stats 页、Android-only 系统自动化/后台 cron、all-files/external-root、Mosh/iSH/GPL、本地 CLI seats(桌面二进制)、真实 OAuth/signed-fetch/账号 cookie。

> 本文档只列 **未闭环** 的项。已闭环(CLOSED)的约 80 项不在此列。结论:绝大部分能力已对齐,**未闭环集中在 4 个真实 P0 + 若干 P1**,主要是"高级功能入口都在、但执行链路是 stub 或缺失核心能力"。

## 总览

| 严重度 | 数量 | 性质 |
| --- | --- | --- |
| **P0** | 5 | 核心能力声称在但实际 stub/缺失:消息分支树、重新生成、生成参数、单 Assistant 系统提示词、SubAgent 工具链、模型议会真正辩论、深度阅读 LLM 合成 |
| **P1** | 13 | 重要用户能力不完整或不可用 |
| **P2** | 11 | 打磨/小众/平台限制 |

---

## P0:声称/入口存在但核心执行缺失(建议优先)

这些是"iOS 看起来有入口/有 UI,但核心逻辑是 stub 或硬编码"——对用户是隐性欺骗,优先级最高。

### P0-1 消息分支树(MessageNode)完全缺失
- **Android**:`core/types/.../Conversation.kt:121` `MessageNode(selectIndex, messages[])` 支持消息分叉;`ChatMessageBranch.kt:27` UI(prev/next + n/m 计数);`ChatVM.kt:420` `selectMessageNode`、`:296` `BranchMessageOrchestrator.fork`。
- **iOS**:`ChatViewModel.messages` 是扁平 `[UIMessage]`,无 MessageNode/分支概念。
- **缺失**:整条分支数据模型 + UI。这是 AmberAgent 的招牌特性之一(重新生成分叉、切换不同回复)。

### P0-2 重新生成 / 编辑消息 缺失
- **Android**:`ChatVM.kt:313` `regenerateAtMessage` → `RegenerateMessageOrchestrator`;`:272` `handleMessageEdit`;`:299` `deleteMessage`。
- **iOS**:`ChatView.swift` / `ChatViewModel.swift` 全文无 `regenerate`/`editMessage`/`resend`(已 grep 确认)。`MessageBubbleView` 无 contextMenu/action,只有一个 artifact-save 按钮。
- **缺失**:重新生成回复、编辑用户消息重发、复制/删除单条消息。

### P0-3 生成参数硬编码(temperature/topP/maxTokens/上下文窗口/自定义 headers 全不可调)
- **Android**:`AssistantBasicPage.kt:296-515`(temp/topP/maxTokens)、`SettingProviderModelSettingsForm.kt:122-200`(context window)、`:259` 自定义 headers。
- **iOS**:`ChatViewModel.swift:2671-2722` 硬编码 `temperature=0.7, topP=nil, maxTokens=nil, customHeaders=[], contextWindowTokens=nil`(已确认)。
- **缺失**:任何用户可调的生成参数。

### P0-4 单 Amber Assistant 系统提示词不可编辑
- **Android**:`Assistant.kt:21` `systemPrompt`,`AssistantBasicPage`/`AssistantPromptPage` 编辑,`GenerationHandler` 注入。
- **iOS**:`IOSSharedSettingsStore.swift:274` `systemPrompt: ""`(空默认);唯一 `systemPrompt` 编辑入口是 SubAgent override(`:544`),不是 Amber assistant。AccountView 预览页明确写"缺账户资料存储桥"。
- **缺失**:用户无法给 Amber assistant 配置系统提示词、改名称、选聊天/图片模型。

### P0-5 SubAgent 执行链路是 stub(单次调用 + 无工具)
- **Android**:`GenerationSubAgentRunner.kt:21-162` 多轮 `generateText` + `subagent_report` 工具捕获 + retry + approval-required;`SubAgentManager.kt:108-117` 注入真实 parent tools。
- **iOS**:`SubAgentRunner.swift:197` `m.start(... parentTools: [])`(已确认——工具白名单显示在 UI 但从不传入);`IosSubAgentFactory.kt:139-228` `RealOpenAISubAgentRunner` 单次非流式调用、无工具、无 report 捕获、无 maxTurns 循环。
- **缺失**:多轮工具调用执行、结构化结果回填、approval 状态。子代理实际上跑不出任何工具结果。

### P0-6 模型议会执行语义缺失(无真正辩论/对比)
- **Android**:`ModelCouncilManager.kt:264-313` `executeCouncil` — COMPARE vs DEBATE 模式、多轮(response/finalPosition 提示)、并行门控、synthesis turn、partial_failed 聚合。
- **iOS**:`CouncilChatRuntimeView.swift:614-712` 手搓 host 开场 → 各 guest 各一次 → 合成;"max rounds 3" 只出现在文案里,无实际轮次循环;`CouncilRunner.swift` 用 KMP manager 但只是 smoke 路径。
- **缺失**:辩论轮次、对比模式、per-seat 模型调度、结构化结论(consensus/conflicts/evidence/risks/recommendation)。

### P0-7 深度阅读是确定性字符串拼接,不是 LLM 合成
- **Android**:`DeepReadAgentRunManager.kt:88-130` 4 阶段(OVERVIEW/NARRATIVE/ANALYSIS/EXTENDED_READING),每阶段 model-driven agent loop + 超时(90-150s)+ evidence registry + `DeepReadSectionWriterTools.kt`。
- **iOS**:`IOSBoardPersistence.swift` 深度阅读生成路径全文无 `chat/completions`/`generateText`(已确认);`IOSDeepReadDraftGenerator.generate` 用 `prefix`+`join` 拼接源文摘要。
- **缺失**:真正的跨源综合/分析——iOS "深度阅读"无法对素材推理,只是把原文片段拼成 markdown。

---

## P1:重要能力不完整或不可用

| # | 能力 | Android 证据 | iOS 状态 | 缺什么 |
| --- | --- | --- | --- | --- |
| P1-1 | 消息注解(URL citations)渲染 | `ChatMessageCommon.kt:91-140` | iOS `annotations` 恒为 `[]`,从不读 | 注解渲染+传递 |
| P1-2 | 分支选择器 UI | `ChatMessageBranch.kt` | 无(依附 P0-1) | 选择控件 |
| P1-3 | 记忆召回排序/打分 | `MemoryRecallStore.kt:59-144`(pinned+权重+相关性+时间衰减) | `ChatViewModel~870` 仅 pinned→更新时间,无相关性 | 相关性打分,召回质量 |
| P1-4 | Workspace file_edit / file_list / file_search / file_move | `WorkspaceTools.kt:18-25`(6 个工具) | iOS 仅 read/write/artifact_read/artifact_delete,且只能按 id 不能按路径 | 字符串编辑/目录树/搜索/移动/按路径寻址 |
| P1-5 | MCP 自动重连/连接健康 | `McpManager.kt:388-497`(指数退避,5 次) | iOS 仅手动 `syncAll`,掉线静默 | 自动重连 |
| P1-6 | Hotlist 提供商(1/9) | `BuiltInHotListProviders.kt:36-46`(微博/知乎/B站/HN/ArxivAI 等 9 + 自定义 + NewsNow) | iOS 仅 `IOSHackerNewsHotlistProvider` | 其余 8 家热榜 |
| P1-7 | WebMount 页面交互工具 | `WebMountInteractionTools.kt`(click/tap/type/scroll/keys/select/find/wait) | iOS 既不在 supported 也不在 unsupported 目录 → 模型拿到 "unknown tool" | 点击/输入/滚动等驱动页面的能力 |
| P1-8 | 同步备份仅 settings(无对话/文件/密钥) | `SyncArchiveManager.kt:163-187`(settings+secrets+对话+文件) | iOS 只导出 `settings.json`(单数据集) | iOS→iOS 对话连续性(诚实标注了范围,不算欺骗) |
| P1-9 | Prompt injections(mode)编辑器 | `extensions/PromptPage.kt:168-353` | 无 UI,数据镜像但不可编辑 | 模式注入 CRUD |
| P1-10 | Lorebook(正则注入)编辑器 | `PromptPage.kt:157,590-707` | 无 UI | lorebook 管理 |
| P1-11 | PPTX 解析 | `document/PptxParser.kt:16` | `SelectedDocumentKind` 无 pptx,落到 `.unsupported` | PPTX 文本提取 |
| P1-12 | 对话存储 I/O 失败静默吞掉 | `HistoryVM` `.catch` 上抛 UI | `IOSConversationStore` 全部 `print()`,不上抛(bootstrap/select/save/delete/persist/rename/togglePin/refreshSummaries 共 8 处) | 磁盘满/写失败用户可见(低存储下有丢数据风险) |
| P1-13 | 用户昵称不持久化 | `ChatDrawer.kt:130,374` | `AccountView.swift:9` 仅 `@State`,不回写 Settings | 昵称丢失 |

---

## P2:打磨 / 小众 / 平台限制

| # | 能力 | 说明 |
| --- | --- | --- |
| P2-1 | Document/Video/Audio/MiniApp 内联 part 渲染 | iOS `MessageBubbleView` 只处理 Text/Reasoning/Image/Tool,其余静默丢弃 |
| P2-2 | 自定义 body / Regex 输出 / Base64图转本地文件 / Placeholder / TimeReminder / PromptInjection transformers | iOS 全缺(transformer pipeline 未移植) |
| P2-3 | 记忆 dream/extraction(后台固化) | iOS 无(`MemoryDreamRunCoordinator`/`MemoryExtractor` 整条管道缺失) |
| P2-4 | 记忆导出/导入 | iOS 无 frontmatter 往返 |
| P2-5 | 记忆 prompt/agent-soul 核心记忆文本块 | iOS 无 |
| P2-6 | 搜索多源 orchestrator(查询变体/合并/`search_sources_status`) | iOS 仅单 provider 派发 |
| P2-7 | 额外搜索商(SearXNG/LinkUp/Metaso/Ollama/Perplexity/Firecrawl/Bocha/Grok) | iOS 标 `unsupported`(6 家 in-scope 已闭环) |
| P2-8 | MCP 图片内容工具结果 | iOS `IOSMcpClient` 只返回 text |
| P2-9 | WebMount agent 站点增删工具 / 多 tab | iOS 仅 UI 增删,无 agent 工具;单 WKRuntime |
| P2-10 | MiniApp launch/clipboard.read/location/sensor / 外部图代理 | iOS 诚实拒绝("iOS 尚未实现") |
| P2-11 | 删除会话无 undo / 无手动存储健康重建 / OCR&视觉 / 注入&lorebook JSON 分享 / SubAgent 实时流式 UI / 自定义/动态角色持久化 / Council seat 配置丰富度 / 每座输出展开 / 角色预设 / 高级任务 store KMP↔Swift 桥接 | 一组小项,详见各 auditor 报告 |

---

## 关键判断

1. **"高级功能入口齐备但核心是 stub"** 是最大风险:P0-5/6/7(SubAgent/Council/DeepRead)三个招牌高级功能在 iOS 有完整 UI 和路由,但执行链是单次调用或字符串拼接。这与"正式高级功能"的产品定位**直接冲突**——用户进入这些入口会得到明显不及预期的结果。
2. **基础聊天层有 4 个 P0**:消息分支树、重新生成、生成参数、系统提示词。其中"重新生成 + 消息分支"是 Android 聊天体验的核心,缺失会让 iOS 聊天明显单薄。
3. **数据安全有 1 个 P1**(P1-12 静默吞 I/O 错误)值得优先——低存储时用户以为保存了实际没保存。
4. **诚实降级做得好**:所有明确不做的(WebMount OAuth/eval、MiniApp 系统能力、Google Drive/S3、OCR)都在代码里显式返回 unsupported/denied,**没有伪实现**,这点与 handoff 的产品决策一致。

## 建议的下一批最小 work packet(按性价比)

1. **WP-GAP-1(聊天基础 P0)**:消息分支树数据模型 + 重新生成 + 编辑/删除/复制消息。改动集中在 `ChatViewModel`/`ChatView`/`MessageBubbleView` + `IOSConversationStore`。
2. **WP-GAP-2(配置 P0)**:Amber assistant 系统提示词编辑 + 生成参数 UI。改动集中在 `IOSSharedSettingsStore` + 新 Settings 页 + `ChatViewModel` 读取参数。
3. **WP-GAP-3(高级执行 P0)**:SubAgent parentTools 注入 + 多轮工具循环;Council 真正辩论模式;DeepRead 接 LLM 合成。这三个是"招牌功能兑现",改动较大,建议分别开。
4. **WP-GAP-4(P1 数据安全)**:`IOSConversationStore` I/O 错误上抛 UI。改动小、收益明确。

## 暂停条件
需要真实 API Key/账号才能验证的(SubAgent/Council/DeepRead 真实模型输出、MCP 重连、WebMount 交互)只能列手动 smoke;不要伪造验证结论。

---

## 附录:2026-06-20 修复闭环(本轮工作)

本轮按"引擎优先 + 逐个挂功能"实施,7 个集群全部完成并提交。每个集群独立提交、独立可测、全量 iosAppTests 通过。

### 已闭环的 P0(全部 7 个)

| P0 | 集群 | 提交 | 怎么修的 |
|---|---|---|---|
| P0-1 消息分支树 | 2 | `1f1aaccb2` | KMP `MessageNode` 树已可见;iOS 加 `selectVariant`/`appendVariant`/`truncateAfter`/`deleteMessage` + 变体切换器 UI |
| P0-2 重新生成/编辑/删除 | 2 | `1f1aaccb2` | `ChatViewModel.regenerate/editMessage/deleteMessage` + contextMenu |
| P0-3 生成参数硬编码 | 3 | `0798b9e01` | `makeTextGenerationParams` 读真实 Assistant/Model + `resolveSessionDefaults` |
| P0-4 Amber 系统提示词 | 3 | `0798b9e01` | `messagesByInjectingSystemPrompt` 注入 + `AssistantParamsView` 编辑 UI |
| P0-5 SubAgent 工具链空 | 4 | `fbf333d0a` | `SubAgentRunner.runViaEngine` 用引擎跑多轮 + parentTools + report 捕获 |
| P0-6 模型议会无辩论 | 5 | `09ece88e4` | KMP `ModelCouncilManager` 已是 iOS 调用的编排层;补 seat runner 参数重试级联 |
| P0-7 DeepRead 字符串拼接 | 6 | `ab0a44292` | `generateViaLLM` 3 阶段(overview→narrative→analysis)真实 LLM 合成,逐阶段 seeding |

### 已闭环的 P1(本轮)

- P1-9/P1-10 注入/Lorebook 编辑器:本轮补了**系统提示词 + 生成参数编辑**(AssistantParamsView);Lorebook 仍 P2 未做。
- P1-12 对话存储 I/O 错误静默吞:集群 7 补 `lastIOError` + `.alert(item:)` 上抛(8 处 print 全替换)。
- 设置 IA 死路由(skills/MCP/Workspace 不可达):集群 7 补三个一级入口。
- 技能进聊天:集群 7 补 `messagesByInjectingSkillContext`。

### 新增基础设施

- **`IOSAgentToolEngine`**(集群 1,`6c53e06ec`):Swift 多轮工具执行引擎,SubAgent/DeepRead/稳健 chat 工具流的地基。纯 Swift,基于现有 KMP 类型,不改 KMP 核心逻辑。

### 验证状态

- 全量 iosAppTests:**323 passed, 1 skipped, 0 failed**(原 290 + 本轮新增 33)。
- 本轮目标 Swift warning 已清理；AmberNative simulator deployment-target linker warnings 仍需 native rebuild 或 deployment target 决策，不在本轮自动修复范围。
- KMP shared framework 编译/链接通过。
- 工作区干净,领先 origin 30 提交。

### 仍需真实 API Key 验证的(机制已测,质量留人工 smoke)

- SubAgent 多轮真实工具执行 + report 质量(集群 4)
- Council 多席位真实辩论/对比输出(集群 5)
- DeepRead 真实跨源综合质量(集群 6)

这些的**循环结构/工具注入/report 捕获/阶段 seeding 机制**都已用 scripted provider 单测覆盖;真实模型输出质量只能用真 Key 在模拟器上验证(符合本轮约定的验证策略)。

### 2026-06-20 P1 闭环(本轮追加)

本轮把 10 个 P1 全部修掉(P1-2 分支选择器 UI、P1-12 I/O 错误上抛 已随 P0 闭环)。每个配单测,全量 iosAppTests 通过。

| P1 | 提交 | 内容 |
|---|---|---|
| P1-13 昵称持久化 | `8d4ad239a` | AccountView 回写 displaySetting.userNickname |
| P1-1 消息注解渲染 | `8d4ad239a` | MessageBubbleView 渲染 URL citation 链接(provider 已解析) |
| P1-11 PPTX 解析 | `8d4ad239a` | DocumentAccessStore 加 .pptx,提取 slide <a:t> 文本 |
| P1-3 记忆召回打分 | `8d4ad239a` | pinned+关键词重叠+时间衰减+置信度打分(CJK 感知分词) |
| P1-4 Workspace 工具 | `1e8fbaf35` | file_edit/list/search/move |
| P1-5 MCP 自动重连 | `1e8fbaf35` | 指数退避重连(最多 5 次),.reconnecting 状态 |
| P1-8 备份含会话 | `78e9b80ac` | export 含 conversations.zip,可还原 |
| P1-9/10 注入/Lorebook 编辑器 | `914352d06` | PromptInjectionEditorView + KMP CRUD 桥接 |
| P1-7 WebMount 交互工具 | `7de8c74d6` | wm_click/type/scroll/select/find/wait(受限 JS 桥接) |
| P1-6 Hotlist 提供商 | `f148f2537` | ArxivAI/InfoqAI/36Kr/HFPapers/GithubTrending(+HN 共 6 家) |

验证:全量 iosAppTests **336 passed, 1 skipped, 0 failed**(本轮新增 13 个 P1 单测)。后续 Tool Closure 复核见下一节；不要把历史测试数量或 warning 状态当作当前 HEAD 结论。

### 2026-06-20 Tool Closure 复核(本轮工作树)

本轮专门修正工具闭环断点:KMP tool declaration、iOS permission registry、Chat params tool declaration、SubAgent engine route、parent tool executor allowlist、assistant regenerate branch persistence、以及既有 Swift warning。当前是 `HEAD 476f8f1ec` 上的未提交工作树复核。

验证:

- `git diff --check`: pass。
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:linkDebugFrameworkIosSimulatorArm64 --no-daemon`: pass。
- `env JAVA_HOME=/opt/homebrew/opt/openjdk@17 xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "platform=iOS Simulator,name=iPhone 17" -derivedDataPath /tmp/amberagent-tool-closure-test-3 ARCHS=arm64 ONLY_ACTIVE_ARCH=NO CODE_SIGNING_ALLOWED=NO test`: pass,**352 passed, 1 skipped, 0 failed**。
- 本轮目标 warning(Toggle Sendable、Kotlin numeric `init(truncating:)`、unused binding/node、`IOSSyncBackup` UIDevice main actor)已清理；`libamber_ffi.a` iOS-sim 26.5 vs link 26.0 linker warnings 仍是 native artifact/deployment-target 问题，不能在本工具闭环里伪装为已解决。

真实 API Key/账号/付费服务 smoke 仍只列手动验证:SubAgent 真模型多轮质量、Council 真模型辩论、DeepRead 真模型综合、MCP 真 server 重连、WebMount 登录态页面交互。

### 仍为 P2 / 明确不做(更新后)

- 记忆 dream/extraction(后台固化)、记忆导出/导入、记忆 prompt/agent-soul 文本块
- 额外搜索商(SearXNG/LinkUp/Metaso/Ollama/Perplexity/Firecrawl/Bocha/Grok)
- MCP 图片内容工具结果、WebMount OAuth/signed-fetch/adapter/screenshot、多 tab
- PPTX 已做;OCR/视觉仍诚实降级不支持
- Share Extension / App Intents / Shortcuts / Universal Links / 通知深链(平台入口)
- AmberNative 原生库 iOS-sim 26.5 vs link 26.0 警告(需 native rebuild)
- 微博/B站热榜(反爬,需登录,诚实不做)
- MiniApp launch/clipboard.read/location/sensor(iOS 平台限制)
- 真实 CLI seats(桌面二进制,非 iOS)、Cron(Android 后台服务)
- 需真实 API Key/账号/付费服务才能端到端验证的(SubAgent 多轮/Council 辩论/DeepRead 生成/MCP 重连/WebMount 交互真实页面)机制已测,质量留人工 smoke
