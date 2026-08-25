# Harmony 全项目第三轮 Review（2026-08-25）

## 范围与方法

- 基线：`bc34e9925`（工作区干净）。本轮在 8/24 两轮 review（逻辑层 33 项 + UI 专项）之后，重点覆盖**上轮未触及的新子系统**：miniapp、agent cron/task、subagent、context/memory、MCP、workspace/技能、搜索编排、council/novel、entry 平台层与大页面。
- 方法：11 个分区子代理并行深读 + 对头条候选逐条人工核验（防误报）。标注 `✅亲验` = 主会话已读代码确认；`◻` = 子代理给出代码证据（含部分脚本实证），修复时建议先复核。
- 结论：**去重后约 75 项真实问题**：数据损坏级 2 项、P1（功能错误/竞态/安全）约 40 项、P2（边界/泄漏/契约）约 33 项；另判 3 项假阳性、6 项已知接线缺口。

---

## P0 — 数据损坏 / 丢失

### P0-1 JSON 文件存储族：损坏 → 静默清空整库 + 非原子写 ✅亲验
- 位置：`entry/src/main/ets/platform_impl/MemoryStore.ets:39-65`；同型 `ManagedFileStore.ets`
- `readJsonArray` catch → `return []`（注释自认"备份策略待落地"）；`writeJsonArray` 以 `TRUNC` 打开后直写。任何一次半截写入/外部损坏后，下一次**任意写操作**（包括召回路径的 `touchMemories`）都会以 `[]` 为基准覆盖原文件 → 记忆/候选/事件全部永久丢失。写入本身也非原子（TRUNC 与 write 之间被杀即损坏）。
- 修复：临时文件写 → flush/close → rename 原子替换；解析失败进入错误态（保留 `.bad` 备份、拒绝后续写、上报），不得与"合法空库"混同；读路径纳入写队列（当前 `readAll` 不经 `enqueueWrite`，存在撕裂读）。

### P0-2 `writeBytes` 写出整个 backing buffer（解压文件必损坏）✅亲验
- 位置：`entry/src/main/ets/platform_impl/WorkspaceStore.ets:140`；同型 `SkillStore.ets:148-156`
- `fileIo.writeSync(file.fd, bytes.buffer as ArrayBuffer)`。`workspace_artifacts.ts` 的 zip **stored 条目**是 `bytes.subarray(dataOff, …)`（恒非零 byteOffset）→ 写出的是**整个 zip 原始字节**而非该条目。`archive_extract` 解 stored 包 100% 得到损坏文件。
- 修复：`bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength)`（或显式复制精确长度）。两处同修。

---

## P1 — 功能错误 / 竞态 / 安全

### A. 流式与 Provider

1. **usage-only chunk 被 accumulator 丢弃** ✅亲验 — `chat/stream_accumulator.ts:264-266`（`appendChunkToMessage:305`、`handleMessageChunk:404` 同）在 usage 合并前 `choice === undefined` 提前 return；而 `openai_parse.ts:256-275` 明确产出 `choices:[] + usage` 的尾块（注释"usage 仍随行"）。流式对话 token 统计丢失。修复：choice 判空前先 `mergeUsage`（三处同修 + 回归测试）。
2. **图片 data URI 只认 PNG** ✅亲验（prefix 侧）◻（parse 侧）— `openai_parse.ts` 只剥 `data:image/png;base64,`；`stream_accumulator.ts:201/364` 新建 image part 硬编码 PNG 前缀 → JPEG/WebP data URI 变成 `data:image/png;base64,data:image/jpeg;base64,…`。修复：解析端识别任意 `data:image/<mime>;base64,` 并保留原样；accumulator 不再补前缀（或按真实 mime 补）。

### B. Chat 主链路与持久化

3. **Chat RDB 无 schema migration** ◻ — `ChatRdbRepository.ets` 初始化只逐句执行 `CHAT_SCHEMA_SQL`（全部 `CREATE TABLE IF NOT EXISTS`），无 `PRAGMA user_version`/`ALTER`。旧库升级遇到新增列即读写失败（可能静默降级内存库 → 会话"消失"）。修复：版本化迁移事务。
4. **并发发送基于同一旧会话快照** ◻ — `ChatPage.ets:3705` `sendParts → ensureFullConversationForMutation().then(runSendParts)`，互斥在异步回调内才生效；时间线分页态下双发可互相覆盖保存。修复：进入异步前占用发送位 / 每会话 turn 串行队列。
5. **appendActive 路径丢消息且失败不复位** ◻ — `ChatPage.ets:3559-3575, 3474`：输入先清空、后 `if (this.appendActive) return`（消息既不入队也不保留）；`ensureFullConversationForMutation` 无 catch，失败后 `appendActive` 永久 true、pending queue 永久停滞。修复：检查前置；finally 复位；失败回灌队列。
6. **压缩规划与执行使用两份会话快照** ◻ — `ChatPage.ets:2431-2450` 规划用 `conversationWithMessagesAsNodes(conv, messages)`，执行 `compactConversation(conversation, …)` 仍传原始 `conversation` → `sourceMessageIds`/索引与实际切片错位，摘要可能覆盖错误范围。修复：执行传同一不可变快照 + 写前版本校验。
7. **删除前面的 alternative 会切换当前分支** ✅亲验 — `chat/delete_message.ts:40` `Math.min(selectIndex, nextMessages.length-1)` 未随删除前移。Android 基准同 bug（`ChatService.kt:2182` `coerceAtMost`）——双端共有。修复：按选中 message id 重定位索引；建议同步修 Android。

### C. Subagent / Agent 任务

8. **超时不收口：永久 RUNNING** ✅亲验 — `subagent_manager.ts` `launchRun` catch 对 `controller.signal.aborted` 直接 return；超时路径先 `abort()` 再 reject，无人调用 `finish` → run/task 停留 running、占并发额度。修复：区分超时与用户取消，超时也走 `finish(timed_out)`。
9. **cancel 与 start 竞态** ◻ — 取消只 abort 已有 controller；register/transcript/launchRun 之间的 await 窗口内取消，`finish(cancelled)` 后 runner 仍会启动。修复：terminal 状态门 + 提前创建 controller。
10. **approval_required 映射为 RUNNING 且无恢复通道** ◻ — `subagent_manager.ts:202-208`；runner 返回待审批后任务卡 RUNNING、cancelCapability 已关。修复：持久化审批态（parent 侧续跑）或映射 interrupted/failed。
11. **重试成功后旧错误永不清除** ✅亲验 — `AgentTaskStore.update`（agent_task.ts:582-595）以 `null` 表"保留旧值"，而 `scheduleTaskUpdate` 成功路径传 `error: null` 意图清除。修复：区分"未提供"与"显式清空"（undefined=不动，null=清除，或专用 clear 哨兵）。
12. **禁用 cron 重启后被复活** ◻ — `recoverOnStartup` 对 `type==='cron'` 无条件置 QUEUED/SCHEDULED，不查 enabled。
13. **历史会话 grant 自动铸造无用户授权边界** ◻ — `subagent_manager.ts:286-309`：只要 subagent 携 `source_session_ids` 即 create grant 并 `needsApproval:false` 自动放行读历史。安全项：应由用户审批签发。
14. **runs Map 只增不减 / launchRun 无顶层 catch / 初始化失败留幽灵 run** ◻ — 三处生命周期收口问题（内存泄漏 + unhandled rejection）。
15. **dynamic subagent 上限分支不可达** ◻ — `subagent_manager.ts:369-382` 两个条件完全相同。
16. **toolCallId 同毫秒碰撞** ◻ — `tool_activity.ts:197` `` `${toolName}_${Date.now()}` ``；活动互相覆盖。
17. **readTail 全文件读入 + 字节预算当字符数** ◻ — `SubAgentStore.ets:100-118`：整个 transcript 进内存（OOM 风险）；`substring(maxBytes)` 按 UTF-16 截断可切 surrogate。
18. **temperature 接受 NaN/Infinity；持久化 definition 无下限校验** ◻（脚本实证 NaN 通过）— `subagent_validator.ts:563-679`。

### D. Context / Memory

19. **token 预算拟合无法收敛超大 system/静态块** ◻（脚本实证 5000 字 system + maxTokens=100 原样返回）— `context_compact.ts:1065-1087`；且 `assembleInternalMessages` 在 fit 之后追加 system 块 → 超窗请求。修复：最终组装后二次预算检查 + system 块分级裁剪/明确失败。
20. **自动 precompact 无并发门 + insertCompact 非原子 RMW** ◻ — 重复摘要/后写覆盖先写。修复：会话级 in-flight 门 + 按 sourceMessageIds 幂等。
21. **Dream plan 可并发双应用** ◻ — `memory_dream_ops.ts:109-120` 无 claim；重复 supersede/归档/事件。修复：`pending→applying` 条件更新。
22. **savePending 非事务可留多个 pending** ◻ — dismiss+insert 分两次入队。
23. **extraction 日配额跨会话竞态 + 去抖在模型解析前置位** ◻ — `memory_extractor.ts:253-271`。
24. **assistant.enableMemory 保存但无任何运行时消费者** ◻ — 召回/工具/抽取只看全局开关，设置 UI 与行为脱节。
25. **touchMemories 失败阻断整次生成** ◻ — 遥测性写入应 best-effort（`.catch(logOnly)`）。

### E. Workspace / 技能 / 文档解析

26. **zip bomb：按声明大小直接分配** ◻ — `zip_archive.ts:101-108` + `EntryDocumentParser.ets:160-183`：中央目录 `uncompressedSize` 是不可信声明，单条目 GB 级分配即崩。修复：单条目/总输出上限 + 流式解压 + 输出长度校验。
27. **归档总输出量无上限；tar.gz 按 ISIZE 分配绕过全部限制** ◻ — `workspace_artifacts.ts:583-672`。
28. **技能导入非 zip 分支无 4MB 上限** ◻ — `skills.ts:828-872`（注释自认"无上限"）。
29. **HTTP 响应限制在完整接收之后** ◻ — `ArtifactPorts.ets:45-87` 先整包 `ArrayBuffer` 再截断。修复：Content-Length 预检 + 流式计数取消。
30. **EPUB href 不做 URI 解析** ◻ — `epub_parser.ts:42-59`：`../`、百分号编码、fragment 均不处理 → 合法 EPUB 报"No readable content"。修复：去 fragment/query + 解码 + 相对路径规范化。
31. **归档 `..` 单段 entry 逃逸（Zip Slip）** ◻ — `workspace_artifacts.ts:293-300` 只查 `'../'` 子串，`entryName === '..'` 通过。修复：按路径段拒绝 `..` + 归一化后 containment 检查。
32. **stored 条目无长度/CRC 校验** ◻ — 截断文件被当成功写出。
33. **技能目录名 ≠ frontmatter name 时列表/解析脱节；zip 多根/同名静默覆盖** ◻ — `skills.ts:360-375, 642-655, 801-823`。
34. **editText 无锁 RMW 丢并发编辑** ◻ — `workspace.ts:239-257`。

### F. MCP

35. **Legacy SSE endpoint 前 EOF → connect 永久挂起** ◻ — `mcp_transports.ts:217-339`：`closeResources` 不 reject `endpointPromise`。修复：endpoint 未决时 reject。
36. **initialize 协议版本不回写 transport** ◻ — `mcp_protocol.ts:168-189` 读取校验后未 set → 后续请求缺 `mcp-protocol-version` 头。
37. **transport.start() 失败不清理** ◻ — `mcp_protocol.ts:159-166`：start 在 try 外，失败后 client 持半开 transport。
38. **SSE GET 不带 `Accept: text/event-stream`；openSse 接受任意 2xx 不校验 Content-Type** ◻ — JSON-only fallback 分支实际不可达。修复：平台层校验 Content-Type，非 `text/event-stream` 抛带 status/contentType 的错误。
39. **平台固定 20s readTimeout 覆盖 domain 120s 工具超时** ◻ — `McpHttpPortImpl.ets:285-294`；超时应随请求贯穿。
40. **Streamable SSE EOF 不触发 close/重连** ◻ — `mcp_transports.ts:525-552`。
41. **manager add/remove/reconnect 在 await 期间可误删新 client** ◻ — `mcp_manager.ts:378-607` 无 generation/token。
42. **Stop 不能取消已发出的 MCP 工具调用** ◻ — `tool_dispatcher.ts:399-401` 执行不带 signal，`McpManager.callTool` 无取消入口。
43. **assistantIdsProvider 全局可变 → 多会话互相改 MCP 工具范围** ◻ — `McpSettingsStore.ets:80-90` + `ChatPage.ets:3101-3105`；应作显式会话参数传递。

### G. MiniApp

44. **getMiniAppPlatform 异步单例竞态** ◻ — `MiniAppPlatform.ets:144`：并发首访创建双实例双写同一目录。修复：init Promise 作锁。
45. **Storage 配额检查跨 Runner TOCTOU** ◻ — `miniapp_storage.ts:35`：快照-检查-写入非临界区，可突破 128 key/512KB。修复：appId 级互斥。
46. **Runner 图片预取无并发/数量/总量上限** ◻ — `MiniAppRunnerPage.ets:196` 全量 `Promise.all`。
47. **网络响应在大小检查前整体缓冲** ◻ — `MiniAppBridgeImpl.ets:453`。
48. **fetch 重定向后返回原始 plan.url（与 Android 契约不一致）** ◻ — `MiniAppBridgeImpl.ets:453` 逐跳校验却返回初始 URL。

### H. 搜索

49. **html_dom 标签扫描不识别引号** ✅亲验 — `search/html_dom.ts:128-147`：`indexOf('>')` 不跳过引号 → `<a href="…?a=>b">` 被截断、URL 残缺。同文件：文本 `<` 被吞（"2 < 3" → "2  1"）、无引号 URL 尾 `/` 误判 self-close、数字实体 `fromCharCode` 非 BMP 错（emoji 乱码）+ `#X` 不解码。修复：逐字符带引号状态扫描 + `fromCodePoint` + 正则收 `#X`。
50. **recency_days 在内置源上静默忽略** ◻ — `search_orchestrator.ts:271-285, 540-548`：仅 configured 源拼时间词。
51. **聚合器部分失败顶层 status 仍 'ok'** ◻ — `search_aggregator.ts:391-418`；现有测试还断言了错误行为（钉死掩盖）。修复：`partial` + 改测试。
52. **标题去重基本失效 + orchestrator 标题合并遗留同 URL 重复** ◻（脚本实证）— `search_aggregator.ts:275-289` 只 `byKey.set(key)` 不建 titleKey 别名；`search_orchestrator.ts:368-406` 标题合并后未 `byKey.set(canonical, existing)`。
53. **ChatPage 从搜索设置页返回不重载 searchSettings** ◻ — `onPageShow` 未刷新，继续用旧工具集。
54. **HotListSourceSettingsPage 与真实数据源完全不匹配且不持久化** ◻ — 占位 id（`github` vs `github-trending-today` 等）、开关不写 `source_${id}`、刷新间隔无消费者 → 设置全部无效。
55. **搜索请求不随 Stop 取消** ◻ — signal 未贯穿 orchestrator/providers。
56. **收藏 add/del 非原子 RMW 互相覆盖 + 多处 rejection 未处理** ◻ — `FavoriteStore.ets:56-75`、`FavoritePage/ChatPage` 调用无 catch。

### I. Council / Novel / DeepRead（库级）

57. **deepread url_filter 整数 IPv4 绕过私网 gate** ◻ — `research/url_filter.ts:52-67` 只认四段点分；`http://2130706433/`（=127.0.0.1）放行后台抓取。上轮只在 miniapp 修了 inet_aton，此处遗漏。修复：host 规范化复用同套全形态识别。
58. **CouncilPage 忽略持久化设置与 enabled 开关** ◻ — `CouncilPage.ets:69-75` 每次 `makeRuntimeSetting` 全新构造。
59. **totalTimeoutMs 从不生效；seat 超时不 abort 底层请求** ◻ — `council/manager.ts:194-300`：`withTimeout` 只 reject。
60. **council liveText/listeners 永不清理（泄漏）；snapshot 浅拷贝可篡改内部态** ◻。
61. **2 轮 debate 不进 finalPositionPrompt** ◻（脚本实证）— `manager.ts:279-284` `round >= 3` 条件把 rounds=2 排除。
62. **重复 seatId 合并流/结果** ◻ — `validator.ts:91-110` 不查重。
63. **无 key/非 200 记为 completed 空文本；裁决不解析 consensus/conflicts/risks** ◻ — `ModelCouncilRunner.ets:41-104` + `manager.ts:341-345`。
64. **novel repository RMW 丢更新** ◻（脚本实证：并发追加丢 1 条）— `novel/repository.ts:74-81` 无 per-project mutex。
65. **novel 流启动同步抛错 → TDZ uncaught + timer 残留** ◻（脚本实证）— `creation.ts:323-349` `unsub` TDZ。
66. **replace 事件绕过 MAX_OUTPUT_CHARS** ◻ — `creation.ts:351-367` 只对 delta 限长。
67. **NovelWorkspacePage 终结后不退订 emitter** ◻ — `NovelWorkspacePage.ets:130-145` 只置 null 不调用 unsub。
68. **小说项目文件非原子覆盖写** ◻ — `novel/repository.ts:34-37` + `FileStore.writeText` TRUNC 直写；中断即整个项目损坏且 `listProjects` 静默跳过。
69. **prefetch deadline 到期反而返回原始 Promise 继续等** ◻ — `source_prefetcher.ts:239-248`。
70. **Tavily 只执行 queries[0]** ◻ — `search_provider.ts:23-27`，多变体查询实际只查一个。

### J. Entry 平台 / DI / 页面

71. **平台层"异步 read-modify-write 无串行化"系统性问题** ✅亲验（模式）— 同一 JSON/KV 键的并发更新互相覆盖，实例：`AgentRuntimePrefs` + `PromptConfigStore`（共享 `agent_runtime`）、`search_prefs.updateSearchPrefs`、`FavoriteStore`、`ExtensionPrefs`/ChatPromptsPage、`MiniAppSettingsPage`、`ChatAssistantExtensionsPage`/`LocalToolsPage`、`AssistantImporter` 重入。修复：按键建立统一串行 mutation 队列（一个共享 helper 即可覆盖大半实例）。
72. **Base64ImageSupport 不释放 ImageSource/ImagePacker** ◻ — 只 `pixelMap.release()`；native 泄漏。修复：三层嵌套 finally（参照已修的 ImageCompress 模式）。
73. **subagent 设置缓存保存后不刷新** ◻ — `AppContainer.ets:348-357` 只写 `updateSubAgentSetting`，`cachedSubAgentSetting` 不失效 → 进程内改 prompt 不生效。
74. **AssistantPropertyEditor 参数与保存链路断裂** ◻ — 详情页只传 `assistantId`，编辑器读 `propertyName/value/type` 且 `save()` 只 `router.back()`。
75. **AssistantDetailPage 子页返回不刷新**（无 `onPageShow` refresh）◻；**AssistantImporter** 无运行时类型校验（`name:123` 卡"导入中"）、无重入门、延迟 back timer 不清理 ◻；**"+ 添加 Body" 无 `.onClick`** ✅亲验 — `ChatAssistantsPage.ets:540`。
76. **ChatPage 若干异步回调缺守卫** ◻ — compacts 加载串会话（`:1835`）、reasoning picker 串模型（`:2818`）、初始化加载族无 `pageAlive`（`:1193` 起）、发送准备 Promise 离页后仍可启动生成（`:2303/:3705`）。修复：统一 requestSeq/pageAlive 门。
77. **会话列表排序无稳定 tie-breaker** ◻ — `ChatRdbRepository.ets:332` 与内存实现都只按 pinned+update_at；同毫秒顺序抖动、分页边界重复/遗漏。修复：追加 `id ASC`。

---

## P2 — 边界 / 泄漏 / 契约（择要）

- `claude_parse`：`message_delta.stop_reason` 不入 finishReason（协议完整性）。
- 多候选响应统一压成 choices[0]；Google 非流式候选 index 全写 0。
- `RcpHttpClient` abort 的 Error 未设 `name='AbortError'`（下游按名判取消会误分类）。
- `serialize.ts:77`：reasoning `finishedAt:null` 持久化省略字段，反序列化补 `now()`（未完成被恢复成已完成）。
- `MemoryStore.getPendingCandidates` 用 `reverse()` 当时间倒序（文件序 ≠ createdAt 序）。
- 记忆抽取：confidence 用 `parseFloat` 前缀解析（`"0.86oops"`→0.86 可自动入库）；`expires_in_days` 接受负数；中文年月正则无数字边界（`12026年9月` 误判）；导入 dedup 用固定快照（多文件互相重复）；frontmatter 引号/反斜杠转义不回解（round-trip 失真）。
- MCP：自定义 header 可覆盖协议头且合并区分大小写；同名工具可致 ToolRegistry 构建抛错；inputSchema 裁剪为 properties/required 丢 `$defs/oneOf`；JSON-RPC envelope 不校验 `jsonrpc`/result-error 互斥。
- MiniApp：空字符串值 key 被当"不存在"（配额误拒已有 key 更新）；`<source>`/`srcset` 校验放行但预取/拦截不覆盖 → 图片不显示。
- 搜索：空 URL 条目进入聚合污染排序；URL 规范化丢端口（`:8443` 与默认端口误判同源）、IPv6 authority 截断；query 后续 variant 未 trim；UI 结果数上限 50 vs orchestrator 40。
- 技能/归档：`overwrite=false` 检查 TOCTOU；解压不创建空目录；section writer 辅助字段（keyEntities/references）随主字段不足被整体丢弃；visual diagnostics candidateCount/rejectedImages 失真；prefetch LRU 命中不更新序。
- Council：outputBudgetChars 不换算 max_tokens（服务端仍可超发）；novel mutations 用 UTF-16 `slice` 可切 surrogate。
- `agent_cron` 查找视界用固定毫秒 366 天（Android 用 `plusDays(366)` 历法语义，DST 边界差异）。
- cron 手动/定时触发无运行锁可并发同一任务；`AgentCronStore.isPathInside` 不规范化 `..`。
- `SettingCronTasksPage`/`PromptTemplatePage`/`ChatPromptsPage` 多处 Promise 无 catch（rejection 静默）。
- 动态编辑行（Header/Body/世界书）用数组索引当 ForEach key（删中间行后输入焦点/组合态错位）。

---

## 已核对：判定不成立（假阳性，未来勿再报）

1. **"双自动批准开关绕过 alwaysAsk（sms/contacts/call）"** — ✅亲验 Android 基准 `PermissionDecisionResolver.kt:122-128`：同样把 both-toggles 放行排在 `alwaysAsk` 之前，注释明示"双开=选择无人值守执行"。Harmony 为忠实移植，属刻意产品语义，不是回归。
2. **"LIKE 未转义 %/_ 会丢搜索结果"** — 未转义通配符只会**多匹配**（超集），后置 TS 字面量精滤兜底，无记录丢失。这正是 8/24 修复"LIKE 粗筛+字面量精滤"的设计。
3. **"deepread run_manager/repository 编译失败"** — `RunManagerDeps.repository` 自带同步 `{get/save/clear}` 内联类型，当前可编译。真实问题是与 `DeepReadRepository`（异步、upsert/delete）**契约分叉**，接线时必须先统一（见下）。

（8/24 两轮确立的假阳性清单继续有效。）

---

## 已知接线缺口（代码已自我声明，非本轮新 bug，但建议排期）

- `DeepReadArticlePage` 占位报"尚未接通"；`RdbRepository.ets` 是全 no-op 内存 fallback（历史页恒空）。
- `OpenAiCompatibleAiClient` 未实现 deepread `AiClient` 的 tools/stream/tool 消息契约（tool 消息被映射成 system、`stream:false` 写死）——接线 collectRun 前必须补齐。
- `RunManagerDeps.repository` 与 `DeepReadRepository` 契约分叉（同步 output-only vs 异步 entry 级）。
- `SearchPage` 返回伪造结果且点击必进上述占位文章页。

---

## 建议修复批次

1. **第一批（数据安全，2 项）**：P0-1 原子写+损坏拒写；P0-2 writeBytes slice。
2. **第二批（高影响功能）**：#1 usage 丢弃、#8 subagent 超时收口、#3 RDB migration、#26/27 zip bomb 上限、#49 html_dom 解析器、#11 error 清除语义、#57 SSRF 整数 IP。
3. **第三批（并发治理）**：#71 统一 RMW 串行队列（一次基建覆盖 10+ 实例）、#4/5 发送与 append 状态机、#41 MCP generation、#20/21/64 各存储 mutex/claim。
4. **第四批（MCP/MiniApp/搜索协议细节）+ P2 清尾。**

每批修完跑 `cd harmony/chat && npm test`、`harmony/deepread` 同款、双模块 `tsc --noEmit`、`hvigorw assembleApp`（注意 hvigor 增量缓存会掩盖预存错误）。
