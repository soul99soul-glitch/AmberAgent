# Plan: iOS「执行待接」标记清理 + 已就绪能力接 UI

> 目标：把 iOS 设置/功能页面里 114 处「执行待接」标记，按"底层真实就绪度"逐条处理——
> 该撤的撤（底层已接、标记陈旧），该接的接（底层就绪、UI 没调），真没底的诚实保留并改写文案。
> 绝不"撤标记 = 造假"：每撤一处必须有可验证的真功能支撑。

## 现状（已勘查确认，2026-06-17）

### 关键发现：大部分底层其实已经接好了

两次 subagent 审计（KMP 导出 + iOS 桥现状）颠覆了"15 个独立大工程"的初判。真相分三类：

| 类别 | 数量 | 性质 |
|---|---|---|
| **A. 陈旧标记**（底层已真接，标记没回头撤） | ~30 处 | 纯文档债，撤标记即可，零代码 |
| **B. 桥已存在但 UI 没调**（Runner/Factory 在，UI 没接或标错） | ~40 处 | 接线工作，每条 0.5-2 天 |
| **C. 真缺口**（底层缺失或不导出，需新工程） | ~44 处 | 独立工程，每条 1-5 天 |

### 已就绪的底层资产（不要重造，直接用）

| 资产 | 位置 | 真实状态 | iOS 已调用? |
|---|---|---|---|
| `IosSkillFactory.listSkills/listIssues` | feature/task commonMain | ✅ 真扫 `Documents/skills/*/SKILL.md` 解析 frontmatter | ✅ SkillsView:132,153 在调 |
| `IosMemoryFactory` (addMemory/deleteMemory/getAllRecords) | core/memory/api | ✅ 真 CRUD，⚠️ 仅内存（重启丢） | ✅ MemoryEditView:91 在调 |
| `IosBoardFactory` + `IosBoardAgent` + `TimeAnchorBoardSignalCollector` | feature/board/api | ✅ **最完整的桥**，BoardView 真跑端到端 OpenAI 链 | ✅ BoardView:255-280 在调 |
| `IosCouncilFactory.createWithRealProvider` + `ModelCouncilManager` | feature/modelcouncil iosMain | ✅ 带 key 真推理（stub 仅无 key 时） | ✅ CouncilRunner.swift 在调 |
| `IosSubAgentFactory.createWithRealProvider` + `SubAgentManager` | feature/subagent iosMain | ✅ 带 key 真推理 | ✅ SubAgentRunner.swift 在调 |
| `IOSMcpManager` + `IOSMcpClient` | iosApp | ✅ 真 JSON-RPC over URLSession | ✅ McpServersView 在调 |
| `IOSSearchExecutor` | iosApp | ✅ 真 DuckDuckGo Lite | ✅ ChatViewModel:436 在调 |
| `IOSConversationStore` | iosApp（刚做的） | ✅ 真文件持久化 | ✅ ConversationsView/ChatView 在调 |
| `IosDatabaseFactory` + `AgentRuntimeDao` | core/agent-store-room | ✅ Room DB，insertRun/updateRun/observeRun 真实 | ⚠️ ChatViewModel 写 run，但没读统计 |
| `TokenUsage` / `TokenBudget` | ai-core | ✅ 数据类，UIMessage.usage 里就有 | ❌ 没人聚合显示 |
| `IOSSyncBackup` (export/import AES-GCM) | iosApp | ✅ 真加密导出导入 | ✅ SyncBackupView 在调（仅 settings 数据集） |

### 真缺口（底层确实没有）

- `Settings.providers` / `.ttsProviders` / `.searchServices` / `.agentRuntime.*` 的 **KMP 写回桥**——iOS 任何"增删模型/provider/搜索服务/TTS/SubAgent override"都只进 UserDefaults 旁路存储，从不 merge 回 `snapshot`，重启后 KMP 集合回到 seed（详见 IOSSharedSettingsStore 审计）
- **Chat 工具注入**：`subagent_*` / `model_council_*` / `mcp_*` 工具声明都没注入 ChatViewModel（只有 `search_web` 注了）
- **Memory / Council run / SubAgent run 的持久化**（全内存，重启丢）
- **`SyncBackupInterface` 的实现类**（接口导出了，零实现；远端云同步完全没做）
- **`feature/board/impl`**（调度器、真实采集器、DeepRead）——JVM-only，没导出
- **Terminal runtime 启动器**（只导出能力元数据，没 PTY/job-launch 类）
- **MiniApp / WebMount 的 WKWebView 层**（iOS 零基础）

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

### Slice 7-9（真缺口，大工程，按需排期）

| Slice | 内容 | 工作量 | 阻塞 |
|---|---|---|---|
| 7 | **远端云同步**（SyncBackupInterface 实现 + S3/WebDAV/Google OAuth） | 3-5 天 | 需 OAuth 凭证；工作区有 PLAN_SYNC_BACKUP.md 未提交 |
| 8 | **Board 真实采集器**（导出 feature/board/impl 到 iOS + calendar/feishu collector） | 3-5 天 | 飞书/calendar 数据源；DeepRead 链 |
| 9 | **MiniApp / WebMount WKWebView 层** | 3-5 天/个 | 全新 iOS WebView 工程；权限模型 |

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

补完 Slice 1-6 后，114 处「待接」清掉 ~70 处，剩余 ~44 处都是 Slice 7-9 级别的真·独立工程，届时按需排期。iOS 从"满屏待接"跨进"核心能力可用、剩余明确"。
