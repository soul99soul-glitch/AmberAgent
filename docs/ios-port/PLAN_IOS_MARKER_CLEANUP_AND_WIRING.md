# Plan: iOS 接线状态标记收口 + 已就绪能力接 UI

> 目标：把 iOS 设置/功能页面里仍存在的「执行待接 / 待接」标记，按"底层真实就绪度"逐条处理——
> 该撤的撤（底层已接、标记陈旧），该接的接（底层就绪、UI 没调），真没底的诚实保留并改写文案。
> 绝不"撤标记 = 造假"：每撤一处必须有可验证的真功能支撑。

## 现状（按当前 HEAD 复核，2026-06-18）

> **收口复扫（2026-06-19）：Swift UI 当前目标 marker 已清零。** 在 ABCD 大工程完成后的本轮收口中，`iosApp/iosApp/**/*.swift` 的 `执行待接|待接|尚未接|未接线` 基线为 `84`，收口后为 `0`。剩余真缺口改用更具体的状态词（如 `缺桥`、`未实现`、`未编排`、`未消费`、`缺实时`、`需写入桥`），避免继续把大块已完成能力显示成泛化待办。下方 Slice 记录保留历史原文，用作执行追溯，不代表当前 UI 文案。

### 起点记录

- `git status --short --branch`：`codex/ios-port-wip...origin/codex/ios-port-wip`，工作区已有非本轮改动（含 Android 测试/文档）和未跟踪 `iosApp/iosApp/CouncilChatRuntimeView.swift`；本轮不触碰该未跟踪文件。
- `git log --oneline --decorate -25`：HEAD 为 `81c7cf3ca Board 今日看板内容 persistence (narrowed scope: content only, no task-flow)`，此前 Slice 1-6、MiniApp WebView MVP、Board 内容持久化均已 commit。
- `rg -o "执行待接|待接|尚未接|未接线" iosApp/iosApp --glob "*.swift" | wc -l` 起点：`121`。

### 当前标记分类

当前不再使用旧的"114 处"作为事实基线；以本轮起点 121 个 marker 为准。大部分剩余 marker 属于真缺口或 v1 明确不做范围，只有少量是过期状态文案。

| 类别 | 性质 | 本轮处理 |
|---|---|---|
| **A. 陈旧标记 / 过期说明** | 底层已真接，页面仍说待接或入口摘要误导 | Search、SettingsHome、Skill 详情 |
| **B. 小闭环** | 桥已存在，UI 只缺最小只读接线 | Skill 列表进入真实 SKILL.md 只读详情 |
| **C. 真缺口** | 底层缺失、未导出、依赖外部账号/key/runtime，或 v1 明确不做 | 改写为具体缺口/不做说明 |

### 已就绪的底层资产（不要重造，直接用）

| 资产 | 位置 | 真实状态 | iOS 已调用? |
|---|---|---|---|
| `IosSkillFactory.listSkills/listIssues` | feature/task commonMain | ✅ 真扫 `Documents/skills/*/SKILL.md` 解析 frontmatter | ✅ SkillsView 扫描；本轮 SkillDetailView 只读读取 SKILL.md |
| `IosMemoryFactory` + `IOSMemoryPersistence` | core/memory/api + iosApp | ✅ 真 CRUD + `Documents/memories/memories.json` 文件持久化 | ✅ MemoryEditView / AppShell 在调 |
| `IosBoardFactory` + `IosBoardAgent` + `TimeAnchorBoardSignalCollector` | feature/board/api | ✅ **最完整的桥**，BoardView 真跑端到端 OpenAI 链 | ✅ BoardView 手动生成在调 |
| `IOSBoardPersistence` + `IOSBoardSignalRepository` | iosApp | ✅ 今日看板 Markdown 按日期落 `Documents/boards/`；Board signals 落 `Documents/boards/signals/board_signals.json`，支持 sourceRef/contentHash 去重、processed 标记 | ✅ BoardView 启动恢复最近内容 + 最近 signals |
| `IosCouncilFactory.createWithRealProvider` + `ModelCouncilManager` | feature/modelcouncil iosMain | ✅ 带 key 真推理（stub 仅无 key 时） | ✅ CouncilRunner.swift 在调 |
| `IosSubAgentFactory.createWithRealProvider` + `SubAgentManager` | feature/subagent iosMain | ✅ 带 key 真推理 | ✅ SubAgentRunner.swift 在调 |
| `IOSMcpManager` + `IOSMcpClient` | iosApp | ✅ 真 JSON-RPC over URLSession | ✅ McpServersView 在调 |
| `IOSSearchExecutor` | iosApp | ✅ 真 DuckDuckGo Lite；`search_web` 已由 ChatViewModel 声明、执行、回填并 resume | ✅ ChatViewModel / IOSSearchExecutor 在调 |
| `IOSConversationStore` | iosApp（刚做的） | ✅ 真文件持久化 | ✅ ConversationsView/ChatView 在调 |
| `IosDatabaseFactory` + `AgentRuntimeDao` | core/agent-store-room | ✅ Room DB，insertRun/updateRun/observeRun 真实 | ⚠️ ChatViewModel 写 run，但没读统计 |
| `TokenUsage` / `TokenBudget` | ai-core | ✅ 数据类，UIMessage.usage 里就有 | ❌ 没人聚合显示 |
| `IOSSyncBackup` (export/import AES-GCM) | iosApp | ✅ 真加密导出导入 | ✅ SyncBackupView 在调（仅 settings 数据集） |

### 真缺口（底层确实没有或本轮明确不做）

- **Search**：`search_web` 已接 DuckDuckGo Lite；`scrape_web`、SearchOrchestrator、多 provider 选择、Reader/Firecrawl/自定义 provider 自动编排仍未接。
- **Skills**：扫描与只读详情已接；启用/禁用、删除、编辑/创建 SKILL.md、assistant.enabledSkills 写入仍缺独立文件写入/校验流程。
- **Memory**：现有 CRUD + 文件持久化已接；MemoryRepository/recall/worker/compaction 仍是更深 KMP 能力。
- **Council / SubAgent**：手动运行和 chat 工具注入已接；liveText/transcript 实时快照、总开关/高级运行策略仍缺实现入口。
- **iOS Remote Sync**：本机 Settings `.amberbackup` 已扩到 Swift provider 协议、本机文件夹 provider、WebDAV mockable transport、快照列表/上传/下载预览/删除、冲突检测与 iOS 侧状态镜像；Google Drive OAuth、S3 签名配置、Room tables、secrets、文件树和自动同步仍是独立真缺口。
- **Board 剩余缺口**：iOS 已有本地 signal repository、聊天历史/EventKit/轻量热榜/时间锚点前台 runOnce；仍缺 BGTaskScheduler、通知读取、飞书账号/MCP、DeepRead、任务流/机会/日报派发（后几项本轮明确不做）。
- **Terminal runtime 启动器**（只导出能力元数据，没 PTY/job-launch 类）
- **MiniApp / WebMount**：MiniApp 已推进到本地仓库、Runner、grant bridge 和 chat 生成保存链路；WebMount 已推进到 registry/settings、真实 WKWebView、allowlist 导航、cookie summary/clear、受限 bridge 和最小 `wm_*` 工具。MiniApp 仍缺部分设备/系统高级 bridge 与全局设置写回；WebMount 仍缺 OAuth、signed fetch、站点 adapter 和完整 Android primitive parity。

---

## 分阶段执行

按"性价比 + 风险"排序。每个 Slice 单独 commit，按 Slice 1-3 既定的 4 维度 review（调用链闭环 / 链路断裂 / 造假数据 / Android 无回归）。

### Slice 1：纯撤陈旧标记（零代码风险，~2 小时）

> **状态（2026-06-18）：✅ 已完成并 commit。** Badge 级标记 baseline 61 → 57（-4）。撤/升级：
> SkillsView 扫描×2 + MCP（IosSkillFactory.listSkills / IOSMcpManager 真链）；CouncilView "iOS 运行桥" 升级为已接（CouncilRunner.createWithRealProvider+m.start）；BoardView "iOS 数据源桥" 拆为诚实"时间锚点采集已接；日历/飞书/热榜待接"。诚实保留：SkillsView 启用/禁用/删除本地 Skill；CouncilView/SubAgentsView 的 chat-injection（Slice 3）与 settings-writeback（Slice 4）标记；Board repository/worker/source（Slice 8）标记。验收：xcodebuild build SUCCEEDED；subagent review APPROVE 4 维 0 P0/P1。

**目标**：撤掉"底层已真接、标记没撤"的标记。每撤一处必须 grep 到真实调用链证据。

| 标记位置 | 真实状态 | 证据 | 处理 |
|---|---|---|---|
| SkillsView:165,184 "Skill 扫描尚执行待接" | ✅ `IosSkillFactory.listSkills` 真扫 | SkillsView:132,153 在调 | 撤标记，改成"已扫描 N 个 skill（Documents/skills/）" |
| SkillsView:212 "重新扫描尚执行待接" | ✅ 同上，重新扫描按钮已工作 | SkillsView:153 onTap | 撤标记 |
| SkillsView:182 MCP 行 "执行待接" | ✅ IOSMcpManager 真连接 | McpServersView 在用 | 撤标记，改成"已接（见 MCP 服务器页）" |
| CouncilView:81 "iOS 运行桥执行待接" | ✅ CouncilRunner + createWithRealProvider 真推理 | CouncilRunner.swift:38,60 | 改文案为"运行链已通（带 API key 真推理；无 key 时 stub）" |
| CouncilView:100 长说明 | 同上 | 同上 | 精简，去掉"不发送模型请求"（带 key 会发） |
| SubAgentsView 类似说明 | ✅ SubAgentRunner 真链 | SubAgentRunner.swift:33 | 同 Council 处理 |
| BoardView:146 "iOS 数据源桥待接（需 Board 采集链）" | ⚠️ TimeAnchor 采集器已接，真实采集器（calendar/feishu）没接 | BoardView:255 | 改成"TimeAnchor 采集已接；日历/飞书采集待接"（部分诚实） |
| 各 View 顶部的 "调用链已通(stub)" 类脚注 | 部分已升级为真链 | 见上 | 逐条核对，能升级的升级 |

**验收**：grep "执行待接" 数量下降 ≥20；每一处改动有 file:line 证据注释。

---

### Slice 2：ConversationStorageView 真接（会话持久化延伸，~1 天）

> **状态（2026-06-18）：✅ 已完成并 commit。** ConversationStorageView 的 8 处「待接」全部处理：对话文件数（summaries.count）、按时间清理（updateAt 过滤+批量 deleteConversation）、删除全部（确认+循环删）、用量统计（扫 Documents/conversations/ 求和）真接；清除缓存诚实改成"v1 无附件缓存，无需清理"。验收：xcodebuild build SUCCEEDED；模拟器运行（PID 29483）；容器 Documents/conversations/ = 503 字节 / 2 文件，与视图扫描一致；subagent review APPROVE（P1 ByteCountFormatter 字节显示已修）。

**目标**：用刚做好的 `IOSConversationStore` 接掉 ConversationStorageView 的 8 处待接。

| 标记 | 现状 | 接法 |
|---|---|---|
| :16 "对话文件 待接（需 iOS 文件系统扫描）" | store.summaries 可读 | 显示 `store.summaries.count` 个 |
| :107,:303 "清除缓存尚执行待接" | 无缓存概念（v1 不做附件缓存） | **诚实保留**，但改文案为"v1 无附件缓存，无需清理"（撤假动作，留真说明） |
| :117,:304 "按时间清理尚执行待接" | store.deleteConversation 可循环 | 真接：按 updateAt 过滤 + 批量 delete |
| :134,:305 "删除全部对话尚执行待接" | store.deleteConversation 可循环 | 真接：确认 alert + 循环删 |
| :178 "用量统计执行待接" | 文件大小可算 | 真接：扫 conversations/ 目录求和 |

**注意**：`@Environment(IOSConversationStore.self)` 注入；AppShell 已 `.environment(conversationStore)`（Phase 2 做的），所以 ConversationStorageView 直接能拿。

**验收**：进设置→对话存储→看到真实文件数；点"删除全部"→确认→列表清空→重启仍空。

---

### Slice 3：Chat 工具注入——MCP/SubAgent/Council 进 chat（~2-3 天，**核心价值**）

> **状态（2026-06-18）：⚠️ 编译级+逻辑完成（真链路运行时验证待 API key）。** KMP `Tool.kt` 新增 `createMcpCallToolDeclaration`/`createSubAgentDispatchToolDeclaration`/`createModelCouncilRunToolDeclaration`；Swift `SubAgentRunner.run(objective:)`/`CouncilRunner.run(objective:)` 驱动 startInput+manager；`IOSMcpConfigStore.shared` 让 ChatViewModel 复用配置；`ChatViewModel` 在 `makeTextGenerationParams` 注入 3 工具、`onComplete` 检测 pending 调用并 dispatch（IOSMcpManager.callTool / SubAgentRunner.run / CouncilRunner.run），结果回填 `messagesByFinishingToolCall` 后 `startStreaming` resume（`maxToolResumeCount=1` 防死循环）。验收：xcodebuild build SUCCEEDED；subagent review APPROVE 4 维 0 P0/P1。**真链路运行时验证（chat 里触发→看到步骤→拿到结果→模型继续）按用户授权延后，需 API key**。诚实保留待接：@mention extractMentions、liveTextFlow/transcript 实时面板、subAgent.enabled 总开关、operationPreviewMode/generativeUi/maxToolLoopSteps（只读 KMP 默认值）。

**目标**：现在 MCP/SubAgent/Council 三个能力都能跑，但**在 chat 里用不了**——用户问"帮我用 subagent 查 X"时 ChatViewModel 不认识 `subagent_*` 工具。这是把"能跑的能力"变成"用户日常可用"的关键一跳。

参照 Slice 64（search_web 注入）的成熟模式：
- `ChatViewModel.makeTextGenerationParams()` 已声明 `search_web` 工具（当 enableWebSearch）
- `onChunk` 检测 toolCall → `executeSearchToolCall` → 真执行 → 回填 → 重新 stream

**接入点**：
1. `makeTextGenerationParams().tools` 增加 `mcp_*`（来自 IOSMcpManager.listTools()）、`subagent_dispatch`、`model_council_run` 的 ToolDeclaration
2. `onComplete` 的 toolCall 检测分支增加：检测到 `subagent_*` → 调 `SubAgentRunner.run(...)`；`model_council_*` → `CouncilRunner.run(...)`；`mcp_*` → `IOSMcpManager.callTool(...)`
3. 结果作为 Tool output 回填消息，重新 stream（同 search_web 的 resume 机制）

**风险**：OpenAI tool-calling 协议要求 tool 声明 + tool_call_id 严格配对；MCP 工具名带 `mcp_` 前缀要做命名空间隔离；多工具并发执行（当前 maxToolResumeCount=1）。

**验收**（需 API key）：chat 里输入"用 subagent 写个 hello world"→ 看到工具调用步骤 → 拿到结果 → 模型继续。无 key 时降级为"工具未配置 API key"诚实提示。

---

### Slice 4：Settings 写回桥——让"增删"真生效（~2-3 天，**基础设施**）

> **状态（2026-06-18）：✅ 5/5 写回全部完成。** 已接并验证：council seats（commit 57390c15，4 XCTest）+ providers/ttsProviders/searchServices（commit 495f25b5，9 XCTest）+ subAgent overrides（putSubAgentOverride/removeSubAgentOverride，4 XCTest）。验收「新增/删除→重启→还在/真没了」对席位/provider/tts/search/subAgent 全部达成。注：先前误判 `settings.subAgent` 为"KMP metadata bug"，实为路径错误——`subAgent` 是 `AgentRuntimeSetting` 的字段，正确路径是 `settings.agentRuntime.subAgent.overrides`，已用该路径接通并撤去错误注释。**仍诚实保留的子功能标记**（非写回类）：ModelEditView 自定义 Headers/Body、底部信息态删除桩；ProviderDetailView Response API/协议选择/类型切换；非 OpenAI 的 TTS 引擎类型；SearchProviderView 批量清空桩；SubAgentRoleView 的 modelId/reasoningLevel/turns（无 iOS 编辑入口）。

**目标**：当前"新增模型/provider/搜索服务/TTS/SubAgent 角色"全部只进 UserDefaults 旁路，重启后 KMP `snapshot` 回到 seed。这是 ModelEditView / ProviderDetailView / SearchProviderView / TTSSettingsView / SubAgentRoleView / SeatEditorView 一堆"编辑待接（需 Settings 持久化）"的共同根因。

**做法**：在 `IOSSharedSettingsStore` 增加一组写回方法，把旁路存储 merge 回 snapshot 并 re-encode 落盘：

```swift
// 新增（IOSSharedSettingsStore.swift）
func mutateProviders(_ transform: (inout [ProviderSetting]) -> Void) {
    var copy = snapshot
    transform(&copy.providers)  // KMP data class copy
    restoreSnapshot(copy)        // 已有的全量 JSON 落盘路径
}
// 同理：mutateTtsProviders / mutateSearchServices / mutateCouncilSeats / mutateSubAgentOverrides
```

KMP `Settings` 是 data class，`copy(...)` 可用；`restoreSnapshot` 已实现（SyncBackupView import 在用）。关键是把现有 `addCouncilSeat`/`addCustomModel`/`addSearchProvider`/`addTtsEngine`/`addSubAgentOverride` 改成调 `mutateXxx`，而不是写旁路 key。

**验收**：新增一个自定义模型 → 重启 → 模型还在；删除一个 provider → 重启 → 真没了；SeatEditor 改席位 → 重启 → 保留。

**影响面**：撤掉 ModelDefaultsView / ModelEditView / ProviderDetailView / SearchProviderView / TTSSettingsView / SubAgentRoleView / SeatEditorView 里所有"编辑待接（需 Settings 持久化）"标记（~20 处）。

---

### Slice 5：AccountView 统计 + ChatView Token 统计（~1 天）

> **状态（2026-06-18）：✅ 已完成并 commit。** ChatView：`ChatContextSnapshot` 增加 prompt/completion/total/cached tokens（reduce `messages.usage`，TokenUsage 由 provider 在完成时填充）；ComposerContextPanel 显示真实 token 总数 + 拆分行；ContextRingButton 环按 token 比例填充。AccountView：新增 DAO `listAllRuns()`；AccountHeatmap 从 `dao.listAllRuns(completionHandler:)` 加载真实 run，按 startedAt 的日分桶着色 level 0-4；空状态诚实"暂无运行记录"。撤 3 处标记（ChatView Token 统计、ContextRingButton label、AccountView 热力图）。验收：xcodebuild build SUCCEEDED；2/2 XCTest（listAllRuns 读写闭环）PASS；subagent review APPROVE 4 维 0 P0/P1。

**目标**：AgentRuntimeDao 在写真 run 记录（ChatViewModel.recordRun），但没人读统计；TokenUsage 在 UIMessage.usage 里，但没聚合显示。

**做法**：
- `AgentRuntimeDao` 增 jvm/ios 共通的 count 查询（或 Swift 侧直接 `db.runs` 全量读 + reduce——规模小可接受）
- AccountView 显示：总运行数、成功/失败/中断分布、最近 7 天趋势
- ChatView 顶部 "Token 统计执行待接" → 用 `messages.compactMap { $0.usage }` 求和显示 prompt/completion/total

**验收**：发几条消息后，chat 顶栏显示真实 token 数；Account 页显示真实运行统计。

---

### Slice 6：Memory 持久化（~1-2 天）

> **状态（2026-06-18）：✅ 已完成并 commit。** 新增 KMP `IosMemoryFactory.replaceAll/snapshotRecords`（commonMain，内存 StateFlow + Swift 驱动持久化）；新增 Swift `IOSMemoryPersistence` 读写 `Documents/memories/memories.json`（原子写 + Codable 镜像 MemoryRecord）；AppShell.init 启动时 `load()`；MemoryEditView 增/删/清空后 `persist()`；撤 MemoryEditView 保存/删除/不保存 标记。验收「加记忆→杀进程→重启→仍在」由 3 个 XCTest 全过证明。诚实保留：MemoryOverviewView 的 MemoryRepository/recall/worker/compaction 标记（Slice 6 仅给现有内存 CRUD 加文件持久化，Room 召回/worker 是更深 KMP 能力）。

**目标**：`IosMemoryFactory` 真 CRUD 但全内存，重启丢。

**做法**：参照 ConversationStorage 模式——给 memory 落 JSON 文件（`Documents/memories/{id}.json` + index），或更简单：把 `IosMemoryFactory` 的 `recordsFlow` 状态序列化到一个 `memories.json` 单文件（memory 量小）。KMP 侧 `IosMemoryFactory` 加 `persist()`/`load()`，iOS 侧在 App 启动调 load、变更后调 persist。

**验收**：加一条记忆 → 杀进程 → 重启 → 记忆还在；MemoryOverviewView 显示真实记录数。

**注意**：这要动 KMP（core/memory/api 的 iosMain），需双端编译 + Shared.h 验证。

---

### Slice 6.5：当前 HEAD 收口（本轮）

> **状态（2026-06-18）：✅ 已完成（完整构建受本机环境阻塞）。** 目标是只做小闭环，不混入 Board、MiniApp、Sync、WebMount 大工程：校准本 plan，清理当前 HEAD 上已完成能力对应的过期文案，完成 Search 状态收口与 Skill 只读详情。验证：marker 121 → 115；本轮 Swift 文件 `swiftc -parse` 通过；Gradle 被本机缺 Java 阻塞；xcodebuild 被本机缺 iOS Simulator runtime 阻塞。

| 位置 | 当前代码事实 | 本轮处理 |
|---|---|---|
| SearchServicesView / SearchProviderView | `ChatViewModel` 已在 `enableWebSearch` 时声明 `search_web`，`IOSSearchExecutor` 真执行 DuckDuckGo Lite 并回填 resume | 改为"search_web 已接"；诚实保留 `scrape_web`、SearchOrchestrator、多 provider orchestration 待接 |
| SkillsView → SkillDetailView | `IosSkillFactory.listSkills` 已扫描真实 `Documents/skills/*/SKILL.md`；旧详情页仍是静态"未读取" | 列表条目进入详情并携带 `dirName`；详情页只读读取对应 SKILL.md，展示 description / allowed-tools / version / 文件路径 |
| SettingsHomeView | Memory、Skill、ConversationStorage、Board、Council、SubAgent、MiniApp 的入口摘要仍有旧的泛"待接" | 改为已接能力 + 真缺口并列：例如 Memory CRUD+持久化已接、Board 内容持久化/时间锚点已接、MiniApp WebView MVP 已接 |

**仍为真缺口（本轮不做）**：Skill 编辑/删除/启用状态写入；Search `scrape_web` 与多 provider 编排；Memory recall/worker/compaction；Board 后台/通知/飞书/任务流/DeepRead；MiniApp 设备/系统高级 bridge 与全局设置写回；远端 Sync 真实云账号与 secrets；WebMount OAuth/signed fetch/站点 adapter。

---

### Slice 6.6：MiniApp 只读 appId Runner 链路（本轮后续薄片）

> **状态（2026-06-18）：✅ 已完成（完整构建受本机环境阻塞）。** 在不接真实 MiniAppRepository/DAO、不写入文件/数据库、不碰权限 grant 的前提下，接通 iOS 最小只读链路：`MiniAppReadOnlyCatalog` 提供一个本地样例 appId，MiniApp 列表展示只读条目，点击进入 `Route.miniAppRunner(appId:)`，Runner 按 appId 读取 HTML，经 `MiniAppHtmlValidator` 校验后加载到 `MiniAppRunnerWebView`，继续保留 AmberNative bridge MVP。

| 位置 | 当前代码事实 | 本轮处理 |
|---|---|---|
| MiniAppListView | 旧文案称 iOS 没有 appId -> runner 链路，且不提供可点击卡片 | 新增只读 catalog section，展示 `ios-miniapp-mvp-sample`，点击用 appId 导航 |
| AppShell / Route | 旧路由只携带 display title | 改为 `miniAppRunner(appId:)`，避免用标题伪装身份 |
| MiniAppRunnerView | 旧 Runner 只接收 title，样例 HTML 只在页面内部 | Runner 按 appId 解析 `MiniAppReadOnlyCatalog`，未找到时显示安全占位 HTML |

**仍为真缺口**：Android 等价的 MiniAppRepository/Room DAO、MiniAppEntity 持久化、版本历史、rename/delete/export、Prompt/Output transformer、Chat UIMessagePart.MiniApp 渲染、permission grant store、完整 MiniAppBridge executor、shared store/event bus/sensor/location/clipboard/host writes。

---

### Slice 7-9（真缺口，大工程，按需排期）

| Slice | 内容 | 工作量 | 阻塞 |
|---|---|---|---|
| 7 | **远端云同步深化**（Google OAuth、S3 live、Room/secrets/file tree、自动同步） | 3-5 天 | 本机文件夹/WebDAV mockable 闭环已推进；真实账号、key、覆盖策略与数据范围仍需单独决策 |
| 8 | **Board 后台/外部账号/任务流**（BGTaskScheduler + 通知/飞书 + DeepRead + 任务流） | 3-5 天 | iOS 本地前台 collectors 已接；剩余需要系统权限、飞书账号/MCP、后台策略或产品决定恢复任务流范围 |
| 9 | **MiniApp 高级 bridge + WebMount parity** | 3-5 天/个 | MiniApp 本地仓库/Runner/grants/chat 保存已接；剩余是设备/系统高级 bridge、全局设置写回。WebMount 核心 runtime 与最小 `wm_*` 已接；剩余是 OAuth、signed fetch、站点 adapter 和 Android primitive parity |

这些每个都是独立大工程，建议单独立 PLAN 文档（工作区已有 PLAN_SYNC_BACKUP.md）。

---

## 明确不做（本 plan 范围外）

- ❌ 动态主题色（AmberTheme 重构成可变 token，影响面太大，单独工程）
- ❌ 消息渲染器（代码高亮/LaTeX/paste-as-file，单独渲染工程）
- ❌ Terminal 真实 PTY（ios_system/a-Shell 链接 + GPL 审核）
- ❌ 云端 TTS provider 试听（依赖 Slice 4 的 Settings 写回 + provider 网络栈）
- ❌ Skills 的 SKILL.md 编辑/创建 UI（写入文件系统，单独工程）

---

## 验证要求（每 Slice 通用，沿用 Slice 1-3 既定流程）

- 涉及 KMP 改动：`compileKotlinJvm` + `compileKotlinIosSimulatorArm64` 双端；`:shared:linkDebugFramework`；Shared.h grep 新符号
- iOS：`xcodegen generate`（新 .swift）→ `xcodebuild build` SUCCEEDED → 模拟器启动验证
- 每 Slice 完成后 subagent review（4 维度）；P0/P1 必须修完再 commit
- **特别审查**："撤标记"类改动必须附 file:line 证据注释，证明底层真接；review 重点查"撤了标记但功能其实没接"的造假

## 提交要求

- 每 Slice 单独 commit，message 写清楚撤了哪些标记 / 接了什么能力
- 只 stage 本 Slice 文件
- 更新 `docs/ios-port/IOS_CAPABILITY_WIRING_AUDIT_2026-06-15.md`（每 Slice 加条目）
- 更新本 plan 的"现状"表格（标记剩余数量）

## 工作量预估

| Slice | 预估 | 撤标记数 | 真功能 |
|---|---|---|---|
| 1 陈旧标记 | 2 小时 | ~20 | 0（纯文档） |
| 2 ConversationStorage | 1 天 | 8 | 对话清理/删除/统计 |
| 3 Chat 工具注入 | 2-3 天 | ~10（间接） | MCP/SubAgent/Council 进 chat |
| 4 Settings 写回桥 | 2-3 天 | ~20 | 增删模型/provider 真生效 |
| 5 统计 | 1 天 | 4 | 运行/token 统计 |
| 6 Memory 持久化 | 1-2 天 | 8 | 记忆落盘 |
| **1-6 合计** | **7-11 天** | **~70** | 6 个真能力 |
| 7-9（大工程，按需） | 9-15 天 | ~30 | 云同步/Board/WebView |

补完 Slice 1-6 和 ABCD 大工程后，iOS 从"满屏泛化缺口"跨进"核心能力可用、剩余明确"。2026-06-19 收口复扫：Swift 侧中文接线 marker 基线为 84，收口后为 0；后续只需要按具体阻塞项继续推进，不再用泛化 marker 表达当前状态。
