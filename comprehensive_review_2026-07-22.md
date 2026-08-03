# AmberAgent 全面代码审查报告（2026-07-22）

> 审查方式：4 路并行（AI/流式层、数据/并发、安全/平台、feature 层自查），全部发现均经主审查者对原始代码逐条复核行号与触发路径。
> 范围：`settings.gradle.kts` 内全部模块（app 约 26.5 万行、ai/core/feature/tts/search/common/document/highlight 约 6.8 万行）。`legacy/`、`arch/`、`jank-opt/`、`web/`、`main/`、`ui-graphite/`、`OpenOmniBot/`、`web-ui/` 不在模块列表或为参考目录，未纳入。
> 基线：`main @ ed822d944`。

---

## P1（建议优先修复，共 5 条）

### 1. SSE onFailure 把 HTTP 错误静默吞成"正常结束" → 用户得到空回复且不报错
- `ai/src/main/java/app/amber/ai/provider/providers/openai/ChatCompletionsAPI.kt:276`
- `ai/src/main/java/app/amber/ai/provider/providers/ClaudeProvider.kt:271`
- `ai/src/main/java/app/amber/ai/provider/providers/openai/ResponseAPI.kt:226`

OkHttp EventSource 在非 2xx 时回调 `onFailure(eventSource, t=null, response)`。当错误 body 为空或非 JSON（Cloudflare/nginx 502 HTML 页、网关 401/429 空 body，极常见）时，body 解析分支跳过或解析异常被 catch，`exception` 保持 null，`close(null)` 使 flow **正常完成且零 chunk**。上层据此视为成功：无错误提示、不重试、空消息入库。`GoogleProvider.kt:446` 的 `close(exception ?: Exception("Stream failed"))` 是正确写法。
**修复**：三处改为 `close(exception ?: Exception("HTTP ${response?.code ?: "unknown"}"))`。

### 2. networkSecurityConfig 全局放行明文 HTTP，覆盖 manifest 的 `usesCleartextTraffic="false"`
- `app/src/main/res/xml/network_security_config.xml:9`

`<base-config cleartextTrafficPermitted="true" />` 对所有域名生效（networkSecurityConfig 优先于 manifest 属性）。注释意图只是放行 Synara LAN companion，实际效果是全局：用户填任何 `http://` 自定义 provider base URL 时 `Authorization` 头（API key）明文过网；WebMount 站点走 http 时 cookie 同样明文。
**修复**：base-config 改回 `false`，仅保留 localhost/127.0.0.1/10.0.2.2 的 domain-config；Synara 已有 `isAllowedCleartextHost` 私网校验，LAN 场景需要在 OkHttp 层单独放行而不是全局放开。

### 3. APK 内置可解码的 SiliconCloud API key
- `app/src/main/res/xml/remote_config_defaults.xml:4`

`silicon_cloud_api_key` base64 解码后是真实形态的 `sk-…` key。base64 不是加密，任何拿到 APK 的人可解码盗用额度并嫁祸 key 所有者。当前唯一使用点（`AIRequestInterceptor.kt:38-40`）已被注释，但 key 已随包分发即构成泄漏（且已进入 git 历史）。
**修复**：删除该默认值并吊销轮换此 key；免费模型改走后端代理签发短期凭证。

### 4. 备份恢复对单表设 64MB 硬上限而导出无上限 → 大备份永远无法恢复
- `app/src/main/java/app/amber/core/sync/core/SyncArchiveManager.kt:287`（常量 `MAX_TABLE_ENTRY_BYTES = 64MB` 在 `:721`）

导出 `writeTableEntry`（`:199`）流式写表无大小限制；恢复 `restorePayload` 对每个 `tables/*.jsonl` 调 `readBytesWithinLimit(64MB)`，超限直接抛异常。重度用户 `message_node` 表（含 reasoning/工具输出全文）超 64MB 后，自己导出的备份自己无法恢复，本地和 Google Drive 两条路径同死。另外 `tableRows` 把全部表行先读进内存再落库，低端机有 OOM 风险。
**修复**：恢复改逐行流式 insert，上限与导出对齐或移除。

### 5. 恢复后 ChatService 内存会话不失效 → 恢复数据与旧内存状态互相写回混合
- `app/src/main/java/app/amber/core/service/ChatService.kt:1932`

EVERYTHING 恢复会 DELETE 全部会话表，但 ChatService 单例里的 `ConversationSession`（含正在流式生成的会话）原样保留。`saveConversation` 查 `existsConversationById` 得到 false 后走 `insertConversation`，把恢复前的旧会话快照整个插回新库。`BackupVM.kt:567` 仅提示"建议重启"而不强制，用户回到聊天页发一条消息即触发数据混合。
**修复**：恢复成功后强制重启进程，或恢复前清空 ChatService 全部 session 并取消在途生成。

---

## P2（共 20 条，按域分组）

### AI / 流式层

**6. 手写 SSE 读循环不可取消，停止生成后连接最长挂 10 分钟**
`ai/src/main/java/app/amber/ai/provider/providers/openai/ResponseAPI.kt:294`、`ai/src/main/java/app/amber/ai/provider/providers/OpenAIProvider.kt:626`
`streamCodexText` 与 `generateOneCodexImage` 用 `while (!source.exhausted()) readUtf8Line()` 手写解析；`readUtf8Line()` 阻塞不可取消。Codex OAuth 路径上服务端长 reasoning 无事件时，用户点停止后协程仍阻塞在 socket 读，连接与 token 消耗持续到 readTimeout（10 分钟）。
修复：读循环内检查 `isActive`，取消时主动关闭 response。

**7. Google listModels 吞掉非 2xx 且失败分支不关闭响应**
`ai/src/main/java/app/amber/ai/provider/providers/GoogleProvider.kt:188`
401（key 错）时静默返回空列表，设置页显示"无模型"而非鉴权失败；失败分支从未读/关 body，连接无法归还连接池。
修复：else 分支读关 body 并抛含状态码的异常。

**8. Gemini OAuth 全量请求/响应体打入 release logcat，异常消息夹带 token 端点原始响应**
`ai/src/main/java/app/amber/ai/provider/providers/google/GoogleGeminiOAuth.kt:458`、`:461`、`:214`、`:543`
两处 `Log.i(TAG, "… request: $body")` / 响应日志（截断 2000 字符）无 DEBUG 门控，release 也写 logcat（含账号元数据）；`:214`/`:543` 解析失败时把 token 端点原始响应前 300 字符塞进异常消息，若响应含 refresh_token/id_token 会经 ChatService.addError 进入 UI/日志。
修复：日志加 DEBUG 门控；异常消息只保留状态码与 error 字段。

**9. runCatching 吞掉 CancellationException，破坏取消传播**
`ai/src/main/java/app/amber/ai/provider/providers/OpenAIProvider.kt:685`、`ai/src/main/java/app/amber/ai/provider/providers/google/GoogleGeminiOAuth.kt:241`
取消被吃成"返回兜底模型列表 / null"，后者继而抛业务错误而非取消，用户点停止后仍见错误提示且协程不能即时退出。
修复：`onFailure { if (it is CancellationException) throw it }`。

### 数据 / 并发

**10. restoreTables 把文件树替换放进 DB 写事务，长事务阻塞所有其它写者**
`app/src/main/java/app/amber/core/sync/core/SyncArchiveManager.kt:435-456`
`replaceFileTreesFromStage`（附件多时 GB 级复制）在事务内执行，写事务可持有数分钟；期间流式落库、memory/board worker 写库 SQLITE_BUSY。
修复：文件切换移出 DB 事务，DB 提交后原子切换文件。

**11. 恢复直接写 SupportSQLiteDatabase 绕过 Room InvalidationTracker → 全部活跃 Flow 陈旧**
`app/src/main/java/app/amber/core/sync/core/SyncArchiveManager.kt:434`
恢复完成后会话列表/收藏/记忆计数等 Flow 收集者继续展示恢复前数据直到重启。
修复：与 #5 合并处理（恢复后强制重启）。

**12. SettingsAggregator.update(fn) 读-改-写竞态，并发静默丢更新**
`core/settings/src/main/kotlin/app/amber/core/settings/prefs/SettingsAggregator.kt:182`
`update(fn(settingsFlow.value))` 读旧快照后把 55 个 key 整体写回。UI 线程与 IO writer（BackupVM、MemoryDreamScheduler）并发时后写覆盖先写，`deviceId`、`lastRemoteRevision` 或用户设置被静默回滚。
修复：Mutex 串行化 read-modify-write，或改 `dataStore.updateData` transform。

**13. 排队消息持久化用 runBlocking(Dispatchers.IO) 阻塞主线程**
`app/src/main/java/app/amber/core/service/ChatService.kt:1027`
ChatVM（Main）→ `SendMessageOrchestrator.send` → `ChatService.sendMessage` → 生成中排队时 `persistPendingMessagesDurably`，全程非挂起，每次排队在主线程同步写 JSON 文件，磁盘慢时卡 UI。
修复：发送路径改挂起 + `withContext(Dispatchers.IO)`，或投递串行 actor。

**14. togglePinStatus 为翻转布尔位加载整条会话（全节点 JSON 解析）且读-改-写无保护**
`app/src/main/java/app/amber/core/repository/ConversationRepository.kt:497`
大会话下是列表页明显卡顿源；两次快速 toggle 并发互相覆盖。
修复：原子 SQL `UPDATE … SET is_pinned = NOT is_pinned WHERE id = ?`。

**15. MemoryRepository.acceptCandidate 两次写库无事务，中途死亡产生重复记忆**
`app/src/main/java/app/amber/core/memory/store/MemoryRepository.kt:178`
`addMemory` 成功、`updateCandidate(ACCEPTED)` 前进程死亡 → candidate 保持 PENDING，同内容记忆重复入库。
修复：两次写包进一个 `@Transaction`。

**16. FTS 索引更新在 DB 事务外，崩溃窗口导致搜索索引漂移**
`app/src/main/java/app/amber/core/repository/ConversationRepository.kt:312-331`、`:373`
事务提交会话+节点后才 `indexConversation`，之间崩溃 → 消息已入库但搜不到（或已删仍可搜出），只能手动全量重建。
修复：`message_fts` 是普通表，纳入同一事务；或失败落补偿标记。

**17. saveConversation 存在性检查与 insert 之间竞态，新会话并发保存抛主键冲突**
`app/src/main/java/app/amber/core/service/ChatService.kt:1932-1952`
ChatVM 多处并发触发；两个协程同见 `exists=false` 都走 insert，`ConversationDAO.insert` 无 `onConflict=REPLACE`，第二个抛 `SQLiteConstraintException`。
修复：`@Insert(onConflict = REPLACE)` 或每会话 Mutex 串行化。

### 安全 / 平台

**18. 云备份/换机传输包含全部数据库与 SharedPreferences**
`app/src/main/res/xml/backup_rules.xml:4`、`data_extraction_rules.xml:4-12`
聊天记录、cron 任务、WebMount 站点配置随 Google 云备份离开设备。API key 在 DataStore（未 include）确认不泄漏，但聊天内容本身敏感。
修复：exclude 敏感库，或改 client-side 加密 BackupAgent。

**19. FileProvider 共享根配置过宽**
`app/src/main/res/xml/file_paths.xml:3`
`files-path "."` 把整个内部 files 目录（含 `datastore/`）纳入可构造 content:// URI 范围。provider `exported="false"`、当前无确认的可控分享调用点，属 latent 风险。
修复：收窄到具体子目录，排除 `datastore/`。

**20. exported 的 RouteActivity 无差别消费任意应用传入的 Intent extras**
`app/src/main/java/app/amber/agent/RouteActivity.kt:294`（manifest `:118`）
任意已安装应用可显式 Intent 读 `conversationId` 直接导航到对应会话、`openChatPrompt` 预填输入框（已确认不自动发送）。越权 UI 控制面。
修复：校验调用方来源，或忽略陌生来源的导航 extras。

**21. InlineLoginActivity exported + BROWSABLE，可被任意网页反复拉起**
`app/src/main/AndroidManifest.xml:187`
`amberagent://webmount/login?station=…` 任意网页/应用可拉起登录 WebView（station 须已注册、URL 不可注入，已确认），但可未经同意反复弹窗。
修复：deeplink 加一次性内部 token，或改非 BROWSABLE 仅应用内跳转。

**22. ShortcutHandlerActivity exported，任意应用可触发相机流程**
`app/src/main/AndroidManifest.xml:167`
`amberagent://shortcut` 无权限门槛，拉起后请求 CAMERA 并拍照回传（需用户交互、无数据外泄），属无门槛 UI 骚扰入口。
修复：若非对外契约，去掉 intent-filter 改应用内显式调用。

**23. .gitignore 未覆盖签名密钥与 google-services.json**
`.gitignore:1`
当前索引干净（已 `git ls-files` 确认），但缺 `*.jks`、`*.keystore`、`**/google-services.json` 防护规则，`git add .` 易误提交。
修复：追加三条规则。

### Feature 层（主审查者自查）

**24. Synara 工作台 WebView 无条件开启 WebContentsDebugging + MIXED_CONTENT_ALWAYS_ALLOW**
`app/src/main/java/app/amber/feature/ui/pages/synara/SynaraWorkspacePage.kt:127`、`:135`
`WebView.setWebContentsDebuggingEnabled(true)` 硬编码且未按 BuildConfig 门控——同仓 MiniAppRunnerPage 用设置项门控（`:261`/`:365`），WebViewPool 用构造参数控制（默认 false）。release 包中任何能 adb 的设备可 chrome://inspect 该 WebView，页面 URL 带 `?token=`，token 直接可见。混合内容 ALWAYS_ALLOW 在 LAN http 场景有实际意义，但应只限该 origin。
修复：debug 开关改 BuildConfig.DEBUG 或设置项；mixedContentMode 按需收窄。

**25. ChatSessionResolverImpl 在 agent runner 线程 runBlocking 取全局记忆**
`app/src/main/java/app/amber/feature/chat/impl/ChatSessionResolverImpl.kt:58`
`resolve()` 非挂起，内部 `runBlocking { memoryRepository.getGlobalMemories() }`。调用方 ChatTurnAgent 跑在 InProcessAgentRunner 的 `Dispatchers.Default` 上（`core/agent-runtime-impl/.../InProcessAgentRunner.kt:35`），每个 chat turn 阻塞一个 Default 线程等 DataStore 读。并发 turn 多时放大 Default 池压力。
修复：resolve 改挂起函数，把记忆读取挪进协程。

---

## P3（记录即可）

- **26.** `GoogleProvider.kt:370` 命中 promptFeedback 后 `close()` 未 `return`，继续对已关闭 channel trySend（静默失败，仅日志噪音）。
- **27.** `ai/util/SSE.kt` 自定义 `SSEEventSource` 在 :ai 模块内无调用方（provider 均用 OkHttp EventSources），死代码，仅标记。
- **28.** `WebDavSync`/`S3Sync` 的活库直拷备份（未 checkpoint）与先删后拷恢复确属隐患，但两类当前未接线（DI 无注册、全仓无调用方），重新启用前需先修。

---

## 已确认无问题的关键路径（抽样）

- **SSE 主路径资源管理**：四条 EventSource 流式路径均有 `awaitClose { eventSource.cancel() }`；`trySendBlocking` 阻塞背压、失败即 cancel；非流式路径 body 均消费关闭（除 #7）。
- **重试编排**：`runProviderCallWithRetry` 先 `ensureActive()` 再分类，重试前 messages 重置回 baseMessages 防重复。
- **日志脱敏**：`RequestLoggingInterceptor` 对 Authorization/Cookie/api-key 头及 `api_key`/`token`/`key` query 参数脱敏，body 仅 DEBUG；`HttpLoggingInterceptor` 仅 DEBUG 且 redactHeader；provider API key 存 DataStore 不进云备份。
- **Room 迁移 1→8** 与 `app/schemas/*.json` 逐版 diff 一致，无半迁移路径；`SYNC_TABLES` 覆盖 33 个实体（缓存表有意排除且注释明确）。
- **多表写事务**：ConversationRepository 的 insert/update/upsertWindow/delete 均在 `withTransaction` 内；app 模块 `GlobalScope` 零命中；`PendingMessageStore` 原子写 + 每会话 Mutex。
- **备份安全**：解压路径穿越防护（`requireSafeZipEntryName`/`resolveArchiveChild`）、导入 512MB 总量限制、临时文件 finally 清理均正确。
- **MiniApp 沙箱**：bridge sessionToken 校验 + 每方法 `sandbox.require` 权限门；WebView 拦截 http/file/content scheme；fetch 强制 https。
- **高权限工具门控**：terminal/SMS/通讯录/日历/Intent/音频全部 `needsApproval = true` 且 `allowsAutoApproval = false`，由 AgentToolDispatcher + PermissionDecisionResolver 强制。
- **组件暴露面**：Accessibility/NotificationListener 服务有 BIND_* 权限；Termux receiver、FileProvider 均 `exported="false"`；release `isMinifyEnabled + shrinkResources`。
- **Workspace 路径**：`WorkspaceManager.kt:286-297` canonicalFile 校验阻断 `../` 穿越。
- **Council Room 编排**：`maybeStartAutoRun` 的 jobsLock 去重 + closing gate、异常路径 `failActiveRoom` 兜底、executor 的 unbounded channel + `finally { close() }` 排干模式均成立（此前审查指出的 FAILED 标记缺失已修复）。
- **Novel 持久化**：`NovelFileProjectRepository` 全程 Mutex + 暂存文件 re-read 校验 + ATOMIC_MOVE（带 fallback）+ previous 副本回退，删除顺序防崩溃复活，实现质量高。

## 审查边界（诚实声明）

- `main/`、`ui-graphite/`、`OpenOmniBot/`、`web-ui/` 不在 Gradle 模块列表，未审查。
- app 模块 26.5 万行中 UI 纯展示层未逐行走查；本报告聚焦数据/并发/安全/流式四条高风险线 + Amber 新增 feature。
- P2 #18-23 为攻击面/配置类问题，未发现当前被利用的路径，但均建议在下次发版前收敛。
