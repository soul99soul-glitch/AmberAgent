# 全项目深度 Review（2026-07-13）

- **方法**：6 个独立只读审查方向并行（novel 纯 Kotlin 模块 / app 侧 novel 集成 / ai 流式层 / chat 数据层与生成链路 / agent runtime + council / 外围模块），主线程对全部 P0 与大部分 P1 逐条亲验证据链（文件、行号、调用链均二次核实）。
- **旧问题核验**：`docs/reviews/novel-review-summary-e7c32a6f.md` 的 8 个 P0/P1 与 `council_room_review.md` 的问题 1/6/8 均确认已修复，不在本报告重复；council review 问题 2/3（部分）、7 未修，见 P2-C2、P2-C3。

---

## P0（数据丢失 / 功能性失效，建议立即修）

### P0-1 regenerate 用户消息可静默清空整个会话并落库（不可逆数据丢失）

- **位置**：`app/src/main/java/app/amber/core/service/ChatService.kt:1093-1100`；根因 `core/model/src/main/kotlin/app/amber/core/model/Conversation.kt:47-49`
- **链路**：`regenerateAtMessage` 用 `getMessageNodeByMessage(message)`（**值相等** `contains(message)`）查找节点。`UIMessage` 是 data class，只要调用方持有的引用与重载后的版本有任一字段不同（典型：上一个生成任务的 `onCompletion` 并发写入 `finishedAt`——`session.getJob()?.cancel()` 并未 join），查找返回 null → `indexOf(null) == -1` → `subList(0, 0)` 得到**空列表且不抛异常** → `saveConversation` 对已存在会话不拦截空节点（空列表保护只针对新会话）→ `ConversationRepository.updateConversation`（`app/src/main/java/app/amber/core/repository/ConversationRepository.kt:322-332`）在事务里 `deleteByConversation` 删光全部消息节点后插入空列表。整个会话内容被永久清空，无 undo 路径。
- **对照**：同文件 `forkConversationAtMessage` / `buildConversationAfterMessageDelete` 在找不到节点时都显式抛错，唯独 regenerate 两处分支无保护；assistant 分支（:1103）同样用值相等查找，后果是 `IndexOutOfBoundsException` 被兜住（不丢数据但同源）。
- **修复**：两处改用已有的 `getMessageNodeByMessageId(message.id)`；`node == null` 时 `addError` 后 return，绝不让 `-1` 流入 `subList`。

### P0-2 流式 token usage 被静默丢弃（ChatCompletions 全系 provider）

- **位置**：`ai/src/main/java/app/amber/ai/ui/MessageStreamAccumulator.kt:25`；触发源 `ai/src/main/java/app/amber/ai/provider/providers/openai/ChatCompletionsAPI.kt:217-244, 324-330`
- **链路**：请求默认开启 `stream_options.include_usage=true`（除 Mistral）。按协议 usage 以**最后一个空 `choices` 的独立 chunk** 到达；`ChatCompletionsAPI` 正确解析并发出 `MessageChunk(choices=emptyList(), usage=...)`，但 `MessageStreamAccumulator.append()` 第一行 `chunk.choices.getOrNull(0) ?: return` 直接丢弃，永远走不到 usage 合并。主生成路径 `GenerationHandler` 就用这个累加器，无旁路。
- **影响**：所有走 ChatCompletionsAPI 的 provider（DeepSeek、SiliconFlow、Moonshot、OpenRouter 等）流式模式下消息 `usage` 恒为 null，上下文占用环 / 用量面板（ContextRing、ChatSizeChecker、ChatInputUsage）显示占位或陈旧数据。测试矩阵无「空 choices + usage」用例。
- **修复**：
  ```kotlin
  fun append(chunk: MessageChunk) {
      val choice = chunk.choices.getOrNull(0)
      if (choice == null) {
          chunk.usage?.let { active.usage = active.usage.merge(it) }
          return
      }
      ...
  }
  ```
  （`Message.kt:240` 的 `handleMessageChunk` 同模式，建议一并修。）

### P0-3 novel 导入「替换」非事务：先删旧项目再建新项目，中途失败旧项目永久丢失

- **位置**：`feature/novel/src/main/kotlin/app/amber/feature/novel/DefaultNovelCreation.kt:675-687`
- **链路**：`importPackage` 在 `replaceProjectId != null` 时先 `deleteProject`（连 `previous.json`、recovery 一并删），再 `createProject(document)`。后者内部有校验、staging、re-read 比对、原子 move——任何一步失败（磁盘不足、包超限、IO 异常）都会抛出，且 `perform()` 无补偿逻辑：旧项目已删、新项目未建，两头落空。
- **缓解现状**：当前 UI 层 `replace` 参数是死代码恒为 null（见 P2-N1），所以此路径暂时不可达——但契约层的炸弹在，UI 一旦接通即触发。
- **修复**：先 `createProject` 成功后再删旧项目（新旧 id 相同时用临时改名/staged swap）；或失败时用先前 `loadProject` 拿到的 `existing.document` 回写补偿。

---

## P1（特定场景错误行为）

### 生成链路 / ai 层

**P1-A1 用户自定义正则无超时，33ms 流式热路径存在 ReDoS 挂死风险**
`app/src/main/java/app/amber/core/ai/transformers/AssistantRegexProcessor.kt:135-144`。编译期只挡语法错误，挡不住 `(a+)+$` 类灾难性回溯；`RegexOutputTransformer` 的 `visualTransformTail` 在每 ~33ms 的流式刷新对越来越长的全文重跑，回溯爆炸是线程挂起而非异常，`try/catch` 防不住，生成协程永久卡死。
**修复**：`runInterruptible` + `withTimeoutOrNull` 包裹 `regex.replace()`；或保存规则时做嵌套量词静态检测；至少把用户正则移出每帧热路径（只在 `onGenerationFinish` 跑一次）。

**P1-A2 Codex OAuth `/models` 非 2xx 分支 response 未关闭，连接泄漏**
`ai/src/main/java/app/amber/ai/provider/providers/OpenAIProvider.kt:699-710`。非 401 失败码（429/5xx）直接 `return defaultCodexOAuthModels()`，body 未消费未关闭；反复刷新模型列表会耗连接池，殃及同 client 的对话请求。
**修复**：失败分支补 `response.close()`，或整体 `response.use { }`。

**P1-A3 Claude `redacted_thinking` 块整体丢弃，扩展思考+工具多轮续接被服务端拒绝**
`ai/src/main/java/app/amber/ai/provider/providers/ClaudeProvider.kt:559-561`（空分支）。Anthropic 要求带工具调用回合的思考块（含密文）原样回放，丢弃后下一轮请求 400。
**修复**：把 `data` 存入 `Reasoning.metadata`（类比 `signature` 的处理），`toContentBlock()` 时重建 `redacted_thinking` 块回传。

### chat 输入 / council

**P1-B1 ask_user 超时后用户回答被静默丢弃**
`feature/modelcouncil/src/main/kotlin/app/amber/feature/modelcouncil/CouncilRoomManager.kt:879-892`（`withTimeoutOrNull(totalTimeoutMs)`，finally 移除 deferred）→ 超时后提交走 `:907-908` 返回 `Err("no_pending_question")` → `CouncilRoomVM.kt:137-141` 丢弃结果，无 toast 无日志 → `CouncilTimelineTab.kt:1263-1269` 无条件清空输入框。用户的回答凭空消失、无法重试，council 无视回答继续跑。
**修复**：VM 处理 Err 给出 toast/恢复输入框内容；或 Err 时退化为「追加 USER 消息 + 唤醒 orchestration」。

**P1-B2 Council 无崩溃恢复：进程死亡后 INTERRUPTED 房间被硬性锁死**
`app/src/main/java/app/amber/core/repository/CouncilRoomRepository.kt:56-67`（冷加载原样反序列化，无修复投影）；`pendingAskUser` 纯内存（`CouncilRoomManager.kt:174`）。杀进程重开后：提交回答 → `no_pending_question` 被丢弃（同 B1）；composer 因 `room.status.running == false` 隐藏；「重新开始」只在 terminal 态出现而 INTERRUPTED 非 terminal——房间无任何 UI 出口。EXPLORING/DEBATING 崩溃恢复同理成僵尸「进行中」。
**修复**：启动/冷加载时对非终态房间做恢复投影（参考 chat 侧 `ChatEventProjector.replayUnfinished`）：活跃态 → 标 INTERRUPTED/FAILED 并收尾悬空消息；INTERRUPTED → 允许无 deferred 时以「追加回答+重启」方式恢复。

**P1-B3 close()/取消不收尾流式消息：终态房间永久显示「正在发言」**
`CouncilRoomExecutor.kt:194`（同模式 :305、:402）`CancellationException` 直接 rethrow 不 `completeMessage`；`CouncilRoomManager.kt:2077-2101` `close()` 只翻房间状态持久化，STREAMING 消息与 SPEAKING 参与者原样写盘。重开后 CANCELLED 房间里有永久转圈气泡，且 `isStreaming = messages.any { it.status.running }` 恒真，follow 状态机把终态房间当流式处理。
**修复**：`close()` 持久化前 sweep：`status.running` 的消息 → INTERRUPTED，SPEAKING → IDLE；或 executor 取消路径 `NonCancellable` finally 补 `completeMessage`。

**P1-B4 附件去重是 no-op**
`app/src/main/java/app/amber/feature/ui/hooks/ChatInputState.kt:90-148` 按**目标** Uri 去重，但所有调用点传入的是 `FilesManager.createChatFilesByContents` 的产物——每次调用 `buildUuidFileName` 都生成全新 UUID 文件名（`FilesManager.kt:151`），注释声称防的「用户重复点击」场景永远两个不同 URL，去重永不命中，重复文件照常拷贝+添加。
**修复**：按源 Uri（或内容 hash）在拷贝**之前**去重；目标层去重删掉。

### novel 功能

**P1-N1 注入预算超限直接抛错、无裁剪兜底，长期项目永久无法生成**
`feature/novel/src/main/kotlin/app/amber/feature/novel/runtime/NovelInjectionPlanner.kt:187-194`。Always 素材与最近 12 条会话消息不裁剪整体塞入，总量超 16k 预算即每次生成必抛 `InvalidInput`，无降级、无 UI 清理手段；`NovelInjectionSelectionReason.BudgetTrimmed` 枚举定义了但全仓库零使用（设计未落地）。
**修复**：实现裁剪（优先丢最旧会话消息/可选素材直到满足预算），失败仅作最后手段。

**P1-N2 `NovelPendingOperationRecord` 缺 `manualSyncProgress` 字段，iOS↔Android 往返静默丢同步进度**
`feature/novel/src/main/kotlin/app/amber/feature/novel/model/NovelRecords.kt:312-334`（注释自认省略）+ `NovelSwiftCompatibleJson.kt:27` 的 `ignoreUnknownKeys=true` → 解码静默丢、重编码不回写。iOS 端带 ManualSync 进度的项目经 Android 一次读写往返即丢失续传状态。
**修复**：以不透明 `JsonObject?` 字段保留回写；或解码时显式告警。

**P1-N3 模型适配器取消处理：吞 CancellationException + 注册窗口竞态**
`feature/novel/src/main/kotlin/app/amber/feature/novel/runtime/AndroidNovelModelAdapter.kt:75-137`。`catch (error: Exception)` 捕获 `CancellationException`，用户取消被转成 `Failed("provider_error")` 事件（channelFlow 缓冲未满时 send 不挂起即成功发出）；且 `jobs[runID] = job` 在 `launch` 之后注册，`interrupt()` 落在窗口内时 `cancel()` 静默 no-op，底层网络流继续消耗 token 直到自然结束（仅靠上层 tombstone 在 UI 层丢弃事件）。同模式：`DefaultNovelCreation.kt:183、:279` 也 `catch (Exception)` 吞取消。
**修复**：`catch (e: CancellationException) { throw e }` 前置；注册移到 `launch` 之前（先注册占位再启动）。

**P1-N4 事件流 DROP_OLDEST + UI 增量累加：消费慢时正文出洞；TextReplacement 契约不一致**
`DefaultNovelCreation.kt:172-176`（`MutableSharedFlow(replay=16, extraBufferCapacity=64, DROP_OLDEST)`）+ `NovelWorkspaceViewModel.kt:151`（`full += event.text`）。丢任何一个 Delta，`streamingText` 即永久缺片段直到 Completed 全量替换。另：domain 层把 `TextReplacement`（全量替换语义，`DefaultNovelCreation.kt:244-250` 会清空累积）也映射为 `Delta` 发给 UI，UI 按增量追加——目前生产 adapter 不发 Replacement 暂未触发，属埋雷。
**修复**：Delta 事件携带累计文本（或序号，UI 检测缺口时向 domain 拉全量）；`TextReplacement` 单独定义 `NovelRunEvent.Replace` 事件。

**P1-N5 六处「操作后 refresh()」fire-and-forget：busy 提前复位 + 并发 refresh 旧数据覆盖新数据**
`app/src/main/java/app/amber/feature/ui/pages/novel/NovelWorkspaceViewModel.kt:68-92`（`refresh()` 内部自行 launch）；调用点 collectCandidate/resolveProposal/forkFromHead/undoHead/setModelPolicy/restorePrevious。连续两个写操作产生两个互不等待的加载协程，完成顺序不保证，晚发先至时 UI 被旧文档回覆；`busy=false` 早于数据落地。
**修复**：`refresh()` 改 suspend 并在调用处等待；或加单调请求序号丢弃过期写回。

**P1-N6 创建项目失败的错误提示被模态对话框遮挡，表现为「点了没反应」**
`app/src/main/java/app/amber/feature/ui/pages/novel/NovelProjectsPage.kt:203-221`。`errorMessage` 渲染在 Scaffold 内容层，创建失败时 `showCreate` 不关闭，AlertDialog 遮罩盖住错误文案。
**修复**：错误信息传入对话框内渲染，或失败时先关对话框。

### 外围模块

**P1-P1 Docx/Pptx/Epub 解析无解压后大小上限，zip 炸弹 OOM**
`document/src/main/java/app/amber/document/DocxParser.kt:40-70`、`PptxParser.kt:77-102`、`EpubParser.kt:16-46`。调用方（`DocumentAsPromptTransformer.kt:87-129`、`WorkspaceArtifactTools.kt:280-311`）的 64MB 限制只防压缩体积、截断发生在全量物化之后；OOXML/EPUB 压缩比可达数百倍。`PdfParser` 有 maxChars 保护，这三个没有。
**修复**：解析器内部累计输出超阈值即中断返回 `[TRUNCATED]`，与 PdfParser 对齐。

**P1-P2 `extractQuotedContent` 从未匹配中文全角引号，中文用户「复制引文 / TTS 引文朗读」实质失效**
`app/src/main/java/app/amber/core/utils/StringUtils.kt:99-118`。注释写中文引号，但 4 个 pattern 字节核对全是 ASCII `"`/`'`（两两重复）。调用方 `ChatMessageActions.kt:142`、`TTSAutoPlay.kt:26`。
**修复**：补 `“([^”]*?)”` 与 `‘([^’]*?)’` 两条真全角 pattern。

**P1-P3 search 模块约 15 个 Service 非 2xx 分支不读/不关 body，连接泄漏**
`ExaSearchService.kt:109`、`ZhipuSearchService.kt:93`、`BochaSearchService.kt:99`、`TavilySearchService.kt:129,168`、`SerperSearchService.kt:74` 等（SearXNG/LinkUp/Jina/Perplexity/Ollama/Metaso/Brave/HackerNews/Wikipedia/SerpApi 同模式）。API key 错误/限流/5xx 场景持续泄漏 socket。对照组 `AmberAgentSearchService.kt:85`、`GrokSearchService.kt:137` 写法正确。
**修复**：统一 `response.use { }`，或 else 分支把 `response.body?.string()` 拼进错误信息。

**P1-P4 SystemTTSProvider 初始化失败不 shutdown，TTS 引擎绑定泄漏**
`tts/src/main/java/app/amber/tts/provider/providers/SystemTTSProvider.kt:109-119`。`status != SUCCESS` 只 `resumeWithException`；`invokeOnCancellation` 仅取消触发，异常 resume 不算取消，已创建的 TextToSpeech 实例永不释放。
**修复**：失败分支补 `tts?.shutdown()`，三条路径（成功/失败/取消）统一走单点清理。

---

## P2（次要 / 埋雷）

### ai / chat

- **P2-A1** `ResponseAPI.kt:879-890`：`response.completed` 兜底分支引用已判 null 的 `response` 变量，`usage` 恒 null（应为 `jsonObject["usage"]`）。
- **P2-A2** `ThinkTagTransformer.kt:57-71`：同一 Text part 多个 `<think>` 块时 `.replace` 删全部、`.find` 只取第一个，其余推理内容凭空消失。改用 `findAll` 逐块生成 Reasoning part。
- **P2-A3** `Base64ImageToLocalFileTransformer.kt` 未实现 visualTransform：>10s 的流式期 checkpoint 命中 `ConversationRepository.kt:456` 的 `require(无 base64)` 断言，被 `runCatching` 吞掉 → 该次检查点静默未落盘，进程被杀则丢这段进度。流式期即转换或 checkpoint 前先转换。

### council / agent runtime

- **P2-C1** `CouncilRoomManager.kt:2109`：`close()` 从 `locks` 移除 mutex 产生窗口，两个并发 `openRoom` 可在不同锁下同时执行（注释的辩护不成立——openRoom 对 evicted 状态恰是继续创建）。建议 mutex 常驻或 CAS 二次校验。
- **P2-C2** `CouncilRoomManager.kt:1870-1892`：`runHostToolTurn` 的 ASK_USER 仍是两段写、第二段 mutate 无 terminal guard 且返回值未检查，失败仍 `awaitUserAnswer`（`peekRoom` null 时超时上界 `Long.MAX_VALUE/2`）。与 review-turn 路径（:1129-1147）对齐为单次 mutate。
- **P2-C3** `AppCouncilHostToolProvider.kt:191`：`HOST_TOOL_NAMES` 白名单保守，用户搜索工具名不匹配时 FULL 模式调研静默跳过（`CouncilRoomManager.kt:1642` return null 无提示）。
- **P2-C4** `InProcessAgentRunner.kt:157-165`：`cancel()` 无状态检查改写快照，已完成 run 可被翻成 CANCELLED（DB 仍 completed，内存/持久化分叉）；且取消路径 DB 写 `interrupted`、快照写 CANCELLED，语义漂移。仅 RUNNING/AWAITING_PERMISSION 时改写。
- **P2-C5** `AmberAgentApp.kt:94,155-160` + `AgentRuntimeDao.kt:44-45`：`replayUnfinished` 与新 run 启动无排序，活 run 可被误标 interrupted 并删 checkpoint；大多自愈，但随后真崩溃时一次性恢复已被消耗。查询加 `started_at < appStartTs` 截止。
- **P2-C6** `MarkdownNew.kt:1020-1024`：deep-read strict 模式把未验证链接的**锚文本连同链接**整体删除，模型把断言写进链接文本时正文静默残缺。strip 时保留 children 纯文本。
- **P2-C7** `CouncilTimelineTab.kt:189-194,342-458`：`followEvents` buffer=1 + DROP_OLDEST，`"chunk"` 可覆盖未消费的 `"new-content"`，一次性跳底丢失、viewport 差几 px 永不贴底（正是该 commit 要修的症状；Compose 调度时序未运行时复现，标 plausible）。

### novel

- **P2-N1** `NovelProjectsViewModel.kt:104-124`：`importPackage` 的 `replace` 参数死代码（两分支恒 null），同名导入永远失败且无 UI 解决路径。补替换询问 UI 或删参数。
- **P2-N2** `NovelWorkspaceViewModel.kt:131-179`：`send()` 生成协程无 try/catch/finally 兜底，未来任何异常将永久卡死 `generating/busy`（输入框禁用、stop 失效），只能重进页面。
- **P2-N3** `NovelCollectionReducer.kt:107-128`：`CreateNextChapter` 不校验 chapterID 重复，靠下游 validator 兜底，错误信息笼统。
- **P2-N4** `NovelProjectRepository.kt:178-190`：`restorePrevious` 直接 `Files.copy` 覆盖 primary，不走 `stage()+atomicMove()`，破坏「写入即原子」不变式且跳过 revision 单调性校验。
- **P2-N5** `DefaultNovelCreation.kt:252`：`NovelModelEvent.Failed` 映射为 `NovelError.InvalidInput`，provider 故障对外报「输入无效」，误导排查。

### 外围

- **P2-P1** `FirecrawlSearchService.kt:124`：`${'$'}{response.code}` 转义错误，异常消息字面输出模板文本，丢真实状态码（同文件 :183 是正确写法）。
- **P2-P2** `AudioPlayer.kt:107-110`：`STATE_IDLE` 分支不 resume 不 removeListener，依赖调用方先 cancel job 的隐性契约，新调用路径会永久挂起。
- **P2-P3** `Highlighter.kt:76-110`：JSON 解码异常时 `result.release()` 被跳过，QuickJSArray 原生句柄泄漏。`release` 挪进 finally。
- **P2-P4** `EpubParser.kt:227-229`、`PptxParser.kt:399-401`：章节解析异常静默吞掉返回空串，用户无从区分「空章节」与「解析失败」。
- **P2-P5** `LoopbackOAuthCallbackServer.kt:244-249`：失败页 `{ERROR}` 未 HTML 转义，OAuth 窗口期内 `127.0.0.1:53682` 存在本地反射 XSS。拼入前做 HTML 实体转义。
- **P2-P6** `KeyCodec.kt:18-22`：缓存 key Base64 文件名无长度上限，超长 key `ENAMETOOLONG` 被 `LruCache.put` 整体吞掉。超长做 SHA-256。
- **P2-P7** `ExaSearchService.kt:89`、`ZhipuSearchService.kt:74`、`BochaSearchService.kt:75`：阻塞 `execute()` 不响应协程取消，与其余 `await()` 服务不一致。

---

## 项目卫生 / 安全

### S-1 签名密钥密码明文的 `.bak` 文件未被 gitignore 覆盖（高危卫生问题）

- `local.properties.bak`、`local.properties.bak.1781975133`（仓库根目录，未跟踪）含 keystore 绝对路径与明文 `storePassword`/`keyAlias`/`keyPassword`。
- `git check-ignore` 确认 `.gitignore` **不覆盖** `.bak*` —— 一次 `git add -A` 即把发布签名密码写进历史。
- 另：`.bak` 中发布密钥密码取值与业界公知的 Android 默认调试密码相同，若为真实生产密码，建议独立评估轮换。
- **修复**：立即移出仓库目录；`.gitignore` 补 `local.properties.bak*`（或 `*.bak`）；评估密码轮换。

### S-2 CLAUDE.md 的 `web` 模块描述过时

`settings.gradle.kts` 无 `include(":web")`，`web/` 目录下无 Kotlin 源码（仅 .gitignore），Ktor 服务器代码只存在于 legacy/jank-opt/arch 等历史目录。建议更新 CLAUDE.md 说明实际集成状态。

---

## 已核验通过、无需修改（避免后续误报）

- Room 迁移 1→8 与 `app/schemas/` 一一对应、v7 `pinned` 列三方一致、7→8 幂等，无 `fallbackToDestructiveMigration`。
- `agent_event` unique(run_id, seq) + IGNORE 幂等；`TokenUsage.merge` 的「仅新值>0 覆盖」是为 Claude 分段 usage 设计，正确。
- 工具 delta 合并（并行 tool stream-index、args 分片 append/replace）经最近两次专项 commit 修复后复核无新问题。
- novel 旧 P0/P1（发送 no-op、创建不导航、列表卡加载、扫描降级、删除原子性、幂等重放 revision）全部确认已修复。
- council 旧问题 1（异常不标 FAILED）、6、8 已修复；ask_user 单飞行保证成立，多问题并发的担忧不成立。
