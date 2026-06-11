# AmberAgent 活跃代码风险审计

日期：2026-06-11  
范围：当前根目录 `/Users/arquiel/Downloads/AI/amberagent` 的活跃 Gradle 工程。  
状态：本文件只记录已确认风险、疑似风险和复核结论；生成本文档时未修改 Kotlin/Gradle 源码。

## 0. 边界与口径

### 活跃模块

以根 `settings.gradle.kts` 为准，本轮审查对象仅包括以下 included modules：

- `:app`, `:app:baselineprofile`
- `:ai`, `:search`, `:tts`, `:common`, `:document`, `:highlight`
- `:core:agent-runtime`, `:core:agent-store-room`, `:core:agent-utils`, `:core:app-infra`, `:core:model`, `:core:event`, `:core:usage`, `:core:llm`, `:core:agent-runtime-impl`, `:core:settings`, `:core:ai-prompts`, `:core:memory:api`, `:core:sync:api`, `:core:context:api`, `:core:ai:api`, `:core:ai:transformers:api`, `:core:ai:generation:api`, `:core:automation:api`
- `:feature:deepread:api`, `:feature:chat:api`, `:feature:history`, `:feature:webview`, `:feature:task`, `:feature:workspace`, `:feature:icloud`, `:feature:terminal:api`, `:feature:board:api`, `:feature:live:api`, `:feature:modelcouncil:api`, `:feature:office:api`, `:feature:subagent:api`, `:feature:subagent`, `:feature:tools:impl`, `:feature:board:impl`, `:feature:runtime:api`, `:feature:tools:api`, `:feature:terminal`, `:feature:modelcouncil`, `:feature:tools:access`, `:feature:system`

### 非活跃候选

仓库中存在 `legacy/`, `jank-opt/`, `main/`, `arch/`, `ui-graphite/`, `OpenOmniBot/`, `web/`, `web-ui/` 等历史副本或并存项目。它们不在根 `settings.gradle.kts` include 范围内，本轮不作为活跃代码审查对象。

### 敏感配置

- 活跃 `app/` 下未发现 `google-services.json`。
- 仅发现非活跃副本中的 `google-services.json`：`legacy/app/google-services.json`, `jank-opt/app/google-services.json`, `main/app/google-services.json`。
- 本文档不包含 Firebase client secret、token、Remote Config 具体值或 keystore 内容。

### 严重程度

- P0：核心构建、启动、聊天/持久化阻断，或明确密钥泄露/数据丢失。
- P1：常见路径核心功能错误、数据一致性破坏、工具/权限/流式响应明显不可用。
- P2：边界条件、特定 provider/文件/设备/配置下失败。
- P3：低概率风险、资源泄漏、测试缺口或策略性残余风险。

## 1. 总览

### 已确认但未修复

| ID | 严重度 | 模块 | 摘要 |
| --- | --- | --- | --- |
| APP-LOG-001 | P0 | `app`, `common`, `ai`, `search` | 请求日志泄露 headers、body、API key、prompt/tool 参数 |
| APP-DATA-001 | P1 | `app` | 删除对话后 Undo 只恢复 DB，不恢复附件和生成图 |
| APP-FILE-001 | P1 | `app` | `deleteChatFiles()` 对任意 `file:` URI 删除边界过宽 |
| APP-WORK-001 | P1 | `app` | WorkManager worker 自身重排同名 unique work 且使用 `REPLACE` |
| APP-TOOL-001 | P1 | `app` | 下载和 zip 解压静默截断但返回成功 |
| APP-APPROVAL-001 | P1 | `app`, `feature:tools:api` | `wm_site_remove` 可被全局 auto approval 放行 |
| MCP-001 | P1/P2 | `app`, `core:ai:api` | 动态 `mcp__*` 工具默认审批 fail-open |
| AI-OPENAI-001 | P1/P2 | `ai` | OpenAI Responses image generation 在 final/non-stream 结果中丢失 |
| APP-SHARE-001 | P2 | `app` | 系统分享文本被双重 base64 |
| APP-SHORTCUT-001 | P2 | `app` | 相机快捷方式把 `EXTRA_STREAM` 作为 String 传递 |
| APP-FAVORITE-001 | P2 | `app` | 删除对话后收藏悬空，点击可创建空对话 |
| WORKSPACE-001 | P2 | `feature:workspace`, `app` | 外部分享文件名可用 `../` 写出 uploads 边界 |
| PERM-001 | P2 | `feature:system`, `feature:tools:access` | SMS read 权限判定与实际查询所需权限不一致 |
| PERM-002 | P2 | `feature:system`, `feature:tools:access` | Calendar create 只申请 WRITE 但实际先查询 calendars |
| TERMINAL-001 | P2 | `feature:terminal`, `feature:workspace` | 未配置 SAF workspace 时停止持久终端 session 返回错误 |
| WEBVIEW-001 | P2 | `app` | WebView links 查询过滤后的 index 与打开 index 不一致 |
| TTS-001 | P2 | `tts` | 无音频 chunk 时可返回 0 字节 MP3 成功 |
| COMMON-HTTP-001 | P2 | `common` | OkHttp `Call.await()` 取消协程不取消底层请求 |
| DOC-001 | P2 | `document`, `app` | 文档转 prompt 全量读取；PDF native 对象释放风险 |
| TOOL-READ-001 | P2 | `app`, `feature:workspace`, `feature:icloud`, `feature:office` | 多处“读完整文件后再截断”导致内存/网络放大 |
| AI-GOOGLE-001 | P2 | `ai` | Google 非流式响应对 safety-block/空 candidates 处理脆弱 |
| AI-FILE-001 | P2 | `ai` | 多模态文件编码 MIME 硬编码 mp4/mp3 |
| APP-IMAGE-001 | P2 | `app` | base64 图片损坏时 Bitmap decode 后 NPE |
| APP-MSG-001 | P2 | `core:model`, `app` | 损坏的 `MessageNode.selectIndex` 可导致越界崩溃 |
| SETTINGS-001 | P2 | `core:settings` | DataStore 偏好 JSON/UUID 损坏时直接 halt 进程 |
| SYNC-001 | P2 | `app` | 备份/同步/skill 导入缺少尺寸上限，存在内存 DoS |
| TOOL-HTTP-001 | P2 | `app`, `feature:tools:api` | `http_request` GET/HEAD 可读 localhost/内网 |
| DEEPREAD-001 | P2 | `app`, `feature:board:impl` | DeepRead seed URL 允许私网抓取，审批后仍有 SSRF 面 |
| RUNTIME-001 | P2/P3 | `core:agent-runtime-impl`, `app` | 持久化 unfinished run 恢复链路未接入 |
| BUILD-001 | P2/P3 | `app` | release Firebase client 缺失会失败；debug 跳过 google-services |
| BUILD-002 | P2/P3 | `app` | embedded terminal runtime 构建期远端下载无 checksum |
| OAUTH-001 | P2/P3 | `ai` | OpenAI/Google OAuth token 明文 SharedPreferences |
| TTS-002 | P3 | `tts` | System TTS 初始化回调理论 race |
| AI-CLAUDE-001 | P3 | `ai` | Claude redacted thinking 直接 `println` |
| HIGHLIGHT-001 | P3 | `highlight` | Highlighter executor/context 生命周期释放不完整 |
| RUNTIME-002 | P3 | `core:agent-runtime-impl` | InProcessAgentRunner 完成后 maps 不清理 |
| AI-STREAM-001 | P3 | `ai`, `app` | 旧 `handleMessageChunk()` tool index merge 有潜在错配 |
| SYNC-TEST-001 | P3 | `app` | sync table coverage test 固定旧 schema 版本 |
| SYNC-OLD-001 | P2/P3 | `app` | 旧 WebDAV/S3 restore 可能部分应用 settings 后失败 |
| MINIAPP-001 | P2/P3 | `app` | MiniApp bridge 用 `runBlocking` 长时间占用 JavaBridge 线程 |
| DEBUG-001 | P2/P3 | `app` | debug 包 exported smoke receiver 可被其他 app 触发测试入口 |

## 2. 根构建与配置

### BUILD-001 - release Firebase client 缺失会失败，debug 跳过 google-services

严重度：P2/P3  
模块：`:app`

触发条件：

- 活跃 `app/` 未放置 `google-services.json`。
- release 构建走 `processReleaseGoogleServices`，且配置中没有 `app.amber.agent` client。

影响：

- debug 编译可通过，因为非 release 任务用 `onlyIf` 跳过无匹配 client 的 google-services。
- release 构建会在 `doFirst("require release google-services client")` 抛 GradleException。

证据：

- `app/build.gradle.kts:55-58`：查找 `src/<variant>/google-services.json` 或 `app/google-services.json`。
- `app/build.gradle.kts:301-315`：release 要求存在匹配 Firebase client；其他 variant 无匹配时跳过。
- `find . -path '*/build/*' -prune -o -name 'google-services.json' -print` 只发现历史副本目录。

建议：

- 私有环境恢复 release Firebase 配置到受控私密位置。
- 文档化 debug/graphite/release packageName 与 Firebase client 的对应关系。
- 不要把真实配置提交到公开仓库。

### BUILD-002 - embedded terminal runtime 构建期远端下载无 checksum

严重度：P2/P3  
模块：`:app`, `:feature:terminal`

触发条件：

- fresh clone 或清理 `app/build/generated/assets/embeddedTerminalRuntime` 后构建。
- Gradle 执行 `prepareEmbeddedTerminalRuntime`。

影响：

- 构建依赖远端 GitHub raw 与 Alpine CDN 可用性。
- `expectedChecksum` 默认 null，调用处未传 checksum；下载产物完整性不可验证。
- 当前本机已有缓存，因此不是当前构建阻断。

证据：

- `app/build.gradle.kts:485-509`：`downloadRuntimeFile()` 只有传入 `expectedChecksum` 时才校验。
- `app/build.gradle.kts:518-529`：下载 `proot`, `libtalloc.so.2`, `alpine.tar.gz`，均未传 checksum。
- `app/build.gradle.kts:533-534`：`preBuild` 依赖下载任务。

建议：

- 固定版本与 SHA-256。
- 或把运行时资产交给受控 release asset / private artifact repository。

### DEBUG-001 - debug 包 exported smoke receiver 可被外部触发

严重度：P2/P3  
模块：`:app`

触发条件：

- 安装 debug variant。
- 其他 app 发送 `app.amber.agent.debug.SMOKE_*` broadcast。

影响：

- 可触发 debug-only terminal/screen/system/tool smoke 流程。
- 高风险工具需要 `allow_high_risk=true`，但入口本身 exported。
- 不影响 release，但 debug 包如果带真实账号/权限使用，风险较高。

证据：

- `app/src/debug/AndroidManifest.xml:5-17`：`AmberAgentSmokeReceiver` `android:exported="true"`。
- `app/src/debug/java/app/amber/agent/debug/AmberagentSmokeReceiver.kt:48-198`：执行 terminal、screen、screenshot、system access、agent tool smoke。

建议：

- debug receiver 增加 signature permission、package/self check，或仅 adb shell/debuggable 可触发。
- 保留 smoke 能力但减少外部 app 可直接调用面。

## 3. app：日志、聊天、对话与文件

### APP-LOG-001 - 请求日志泄露 headers、body、API key、prompt/tool 参数

严重度：P0  
模块：`:app`, `:common`, `:ai`, `:search`

触发条件：

- 任意使用全局 OkHttp client 的 AI/Search/TTS/工具请求。
- 用户打开日志页，或设备 Logcat 被收集。

影响：

- `Authorization`、API key、自定义 headers、AI prompt、工具参数、搜索 query 进入 in-app log 或 Logcat。
- SerpApi 使用 query parameter 传 key，URL logging 会直接包含 key。
- OpenAI Responses/ChatCompletions 直接 `Log.i` 完整 request body，prompt/tool data 进入 Logcat。

证据：

- `app/src/main/java/app/amber/core/di/DataSourceModule.kt:388-392`：添加 `RequestLoggingInterceptor` 与 `HttpLoggingInterceptor(Level.HEADERS)`。
- `app/src/main/java/app/amber/core/ai/RequestLoggingInterceptor.kt:14-18`：复制 request headers/body。
- `common/src/main/java/app/amber/common/android/Logging.kt:23-46`：保存 request log。
- `app/src/main/java/app/amber/feature/ui/pages/log/LogPage.kt:242-259`：日志页展示 headers/body。
- `ai/src/main/java/app/amber/ai/provider/providers/openai/ResponseAPI.kt:96,182,271`：记录 request body。
- `ai/src/main/java/app/amber/ai/provider/providers/openai/ChatCompletionsAPI.kt:115,174`：记录 request body。
- `search/src/main/java/app/amber/search/SerpApiSearchService.kt:62-65`：API key 放在 URL query。
- `search/src/main/java/app/amber/search/SearXNGService.kt:89`：记录 URL。

建议：

- 默认关闭 request body logging。
- headers 白名单或敏感字段统一 redaction。
- URL query redaction：`api_key`, `key`, `token`, `access_token`, `code`, `client_secret`。
- provider 内部 `Log.i` 改为只记录 model/status/长度，不记录原文。

### APP-DATA-001 - 删除对话后 Undo 只恢复 DB，不恢复附件和生成图

严重度：P1  
模块：`:app`

触发条件：

- 对话包含本地附件或生成图片。
- 在 History 页面删除对话。
- snackbar 点 Undo。

影响：

- DB conversation/message nodes 被恢复。
- 附件文件和 `filesDir/chat_images/<conversationId>` 已被删除，消息中的 URI 悬空。
- `deleteChatFiles()` 异步运行，Undo 与文件删除存在竞态。

证据：

- `app/src/main/java/app/amber/feature/ui/pages/history/HistoryPage.kt:129-140`：删除后 snackbar undo 恢复 fullConversation。
- `app/src/main/java/app/amber/feature/ui/pages/history/HistoryVM.kt:41-44,60-63`：delete/restore 分别 launch；restore 只 insert conversation。
- `app/src/main/java/app/amber/core/repository/ConversationRepository.kt:400-418`：删除 DB 后删除 chat files 与 chat images dir。
- `app/src/main/java/app/amber/core/files/FilesManager.kt:220-255`：文件异步删除。
- `app/src/main/java/app/amber/core/files/FilesManager.kt:307-310`：生成图目录递归删除。

建议：

- 删除进入可撤销阶段时先 tombstone，Undo 取消 tombstone；超时后再清文件。
- 或删除前把文件移入 recycle/staging，Undo 时恢复。
- 至少阻塞到文件清理结束并禁止 Undo 伪恢复。

### APP-FILE-001 - `deleteChatFiles()` 删除边界过宽

严重度：P1  
模块：`:app`

触发条件：

- conversation 中含 `file:` URI。
- URI 指向非 workspace mirror 且 app 进程可删除的路径。
- 用户删除对话或移除附件。

影响：

- 任意 `file:` URI 都会执行 `file.delete()`。
- canonical filesDir 检查只用于收集 repository path，不是删除前置条件。
- 导入/同步/损坏数据可能造成 app 删除非自身 upload 目录文件。

证据：

- `app/src/main/java/app/amber/core/files/FilesManager.kt:231-240`：对所有 `file:` URI 转 File 并删除。
- `app/src/main/java/app/amber/core/files/FilesManager.kt:237`：`getRelativePathInFilesDir()` 结果未作为 delete guard。
- `app/src/main/java/app/amber/core/files/FilesManager.kt:466-474`：已有 canonical filesDir helper，但未用于阻止外部路径删除。

建议：

- 只允许删除 canonical `filesDir/upload` 或 app 自建 chat file 目录内文件。
- workspace mirror、cache export、外部路径全部跳过或单独策略处理。

### APP-IMAGE-001 - base64 图片损坏时 NPE

严重度：P2  
模块：`:app`

触发条件：

- AI provider 返回 `data:image...base64,...`。
- base64 可解码但不是 Android 可识别 bitmap，或数据截断。

影响：

- `BitmapFactory.decodeByteArray()` 返回 null。
- 后续调用 `bitmap.compressToPng()` 触发 NPE。

证据：

- `app/src/main/java/app/amber/core/files/FilesManager.kt:197-200`：decode 后未判空。

建议：

- decode 失败时保留原 part 并返回可见错误，或抛出受控异常。

### APP-MSG-001 - 损坏的 `MessageNode.selectIndex` 可越界崩溃

严重度：P2  
模块：`:core:model`, `:app`

触发条件：

- DB、同步恢复或旧版本迁移产生 `select_index` 超出 `messages` 范围。
- UI 或 repository 访问当前消息。

影响：

- `IndexOutOfBoundsException`。
- 聊天页/历史恢复可能无法打开某个 conversation。

证据：

- `core/model/src/main/kotlin/app/amber/core/model/Conversation.kt:42-45`：直接 `messages[selectIndex]`。
- `app/src/main/java/app/amber/feature/ui/components/message/ChatMessage.kt:83`：直接索引。
- `app/src/main/java/app/amber/core/repository/ConversationRepository.kt:550-575`：decode DB 后未 clamp selectIndex。
- `app/src/main/java/app/amber/agent/data/db/entity/MessageNodeEntity.kt:31-34`：持久化 `select_index`。

建议：

- repository decode 时 clamp 到 `messages.indices`。
- 对空 messages 节点做显式丢弃或错误节点。

### APP-SHARE-001 - 系统分享文本被双重 base64

严重度：P2  
模块：`:app`

触发条件：

- Android 系统 share text 到 AmberAgent。

影响：

- 输入框里出现 base64 字符串，而不是原文。

证据：

- `app/src/main/java/app/amber/core/utils/ChatUtil.kt:13-31`：`navigateToChatPage()` 会 base64 encode `initText`。
- `app/src/main/java/app/amber/feature/ui/pages/share/handler/ShareHandlerPage.kt:52`：传入前又 `shareText.base64Encode()`。
- `app/src/main/java/app/amber/feature/ui/pages/chat/ChatPage.kt:281`：只 decode 一次。

建议：

- `ShareHandlerPage` 传原文给 `navigateToChatPage()`。
- 增加分享文本最小单测或 instrumentation smoke。

### APP-SHORTCUT-001 - 相机快捷方式 `EXTRA_STREAM` 类型错误

严重度：P2  
模块：`:app`

触发条件：

- 用户使用 launcher shortcut “Take Picture & Ask”。
- 拍照成功后跳转 RouteActivity。

影响：

- RouteActivity 按 `Uri` 读取 stream，实际传入 String。
- 图片不会进入 chat。

证据：

- `app/src/main/java/app/amber/feature/ui/activity/ShortcutHandlerActivity.kt:29-30`：`putExtra(Intent.EXTRA_STREAM, it.toString())`。
- `app/src/main/java/app/amber/agent/RouteActivity.kt:747`：`getParcelableExtra<Uri>(Intent.EXTRA_STREAM)`。
- `app/src/main/res/xml/shortcuts.xml:3-12`：定义相机快捷方式。

建议：

- 传递 `Uri` 本体，并附加 read grant。

### APP-FAVORITE-001 - 删除对话后收藏悬空

严重度：P2  
模块：`:app`

触发条件：

- 收藏某个 message node。
- 删除对应 conversation。
- 在收藏页点击该收藏。

影响：

- favorite 没有 FK/cascade，继续保留旧 conversationId/nodeId。
- ChatService 找不到 conversation 后会用旧 UUID 创建空 conversation。

证据：

- `app/src/main/java/app/amber/agent/data/db/entity/FavoriteEntity.kt:9`：无 FK/cascade。
- `app/src/main/java/app/amber/core/repository/ConversationRepository.kt:400`：删除 conversation 时未删除 favorites。
- `app/src/main/java/app/amber/feature/ui/pages/favorite/FavoritePage.kt:109`：点击旧 conversationId/nodeId。
- `app/src/main/java/app/amber/core/service/ChatService.kt:542`：conversation 不存在则 initialize 新 conversation。

建议：

- 删除 conversation 时级联删除 favorite。
- 或 favorite click 前校验 conversation/node 存在，不存在则提示清理。

### TOOL-HTTP-001 - `http_request` GET/HEAD 可读 localhost/内网

严重度：P2  
模块：`:app`, `:feature:tools:api`

触发条件：

- Agent 调用 `http_request`，method 为 GET/HEAD。
- URL 指向 `localhost`, `127.0.0.1`, `10.x`, `192.168.x`, link-local 或内网 host。

影响：

- 读取本机或局域网服务响应。
- 工具策略中 GET/HEAD 视为 read-only，可自动通过。
- 这可能是产品能力，也可能是 SSRF 风险，需产品决策。

证据：

- `app/src/main/java/app/amber/feature/tools/WorkspaceArtifactTools.kt:430-470`：仅校验 http/https，无 DNS/IP 私网 guard。
- `feature/tools/api/src/main/kotlin/app/amber/feature/tools/ToolRegistry.kt:187-195`：GET/HEAD 判定 safe，不需要审批。

建议：

- 默认阻止私网/localhost，提供显式高风险开关。
- 或 GET/HEAD 内网访问强制人工审批。

### APP-TOOL-001 - 下载/解压静默截断但返回成功

严重度：P1  
模块：`:app`, `:feature:workspace`

触发条件：

- `download_file` 下载内容超过 `MAX_DOWNLOAD_BYTES`。
- `archive_extract` zip entry 超过 `MAX_ARCHIVE_ENTRY_BYTES`。

影响：

- 文件被截断写入 workspace。
- 工具返回 `status=saved` 或正常统计，未标记 truncation。
- 下游处理损坏文件。

证据：

- `app/src/main/java/app/amber/feature/tools/WorkspaceArtifactTools.kt:105-118`：download limited bytes 后返回 saved。
- `app/src/main/java/app/amber/feature/tools/WorkspaceArtifactTools.kt:512-515`：zip entry limited bytes 后写入。
- `app/src/main/java/app/amber/feature/tools/WorkspaceArtifactTools.kt:718-732`：到 limit 后 break，无 truncation flag。

建议：

- 超限直接失败，或返回 `truncated=true` 且不写入默认目标。
- zip entry 超限时停止解压并报告具体 entry。

## 4. app：后台任务、工具审批与 runtime

### APP-WORK-001 - WorkManager worker 自身重排同名 work 且用 `REPLACE`

严重度：P1  
模块：`:app`

触发条件：

- Cron/Board/Memory Dream anchor worker 开始执行。
- worker 入口先安排下一次同名 unique work。

影响：

- Android WorkManager `ExistingWorkPolicy.REPLACE` 会取消/删除未完成同名 work。
- 当前运行中的 worker 可能取消自身或造成执行不稳定。

证据：

- `app/src/main/java/app/amber/feature/cron/AgentCronWorker.kt:36`：worker 开始调用 `prepareTriggeredRun()`。
- `app/src/main/java/app/amber/feature/cron/AgentCronManager.kt:264-275`：同名 `workName(task.id)` 使用 `ExistingWorkPolicy.REPLACE`。
- `app/src/main/java/app/amber/feature/board/worker/BoardWorker.kt:55-58`：anchor worker 开始重排。
- `app/src/main/java/app/amber/feature/board/worker/BoardScheduler.kt:57-60`：anchor work 使用 REPLACE。
- `app/src/main/java/app/amber/core/memory/dream/MemoryDreamWorker.kt:29-31`：worker 开始 schedule next。
- `app/src/main/java/app/amber/core/memory/dream/MemoryDreamScheduler.kt:59-62`：night run 使用 REPLACE。

建议：

- 将“当前运行”和“下一次计划”使用不同 unique work name。
- 或在 worker 完成后调度下一次。
- 或使用 KEEP/APPEND_OR_REPLACE 并验证 WorkManager 状态。

### APP-APPROVAL-001 - `wm_site_remove` 可被全局 auto approval 放行

严重度：P1  
模块：`:app`, `:feature:tools:api`

触发条件：

- 用户开启 global auto-approval。
- Agent 调用 `wm_site_remove`。

影响：

- 工具描述要求 explicit human approval。
- ToolRegistry 设置 `needsApproval=true`，但 `autoApprovable = allowsAutoApproval`，如果 Tool 默认/调用路径允许 auto approval，则可跳过显式确认。
- 删除站点会清 cookies、OAuth credentials、tokens、profile。

证据：

- `app/src/main/java/app/amber/feature/webmount/tools/WebMountSiteTools.kt:266-275`：描述要求 explicit human approval。
- `app/src/main/java/app/amber/feature/webmount/tools/WebMountSiteTools.kt:311-316`：清 cookie/token/credential。
- `feature/tools/api/src/main/kotlin/app/amber/feature/tools/ToolRegistry.kt:291-299`：`autoApprovable = allowsAutoApproval`。
- `app/src/main/java/app/amber/feature/runtime/PermissionDecisionResolver.kt:184-185`：global auto approval 放行 autoApprovable。

建议：

- `wm_site_remove` 强制 `autoApprovable=false`。
- destructive WebMount management 工具统一 fail-closed。

### MCP-001 - 动态 `mcp__*` 工具默认审批 fail-open

严重度：P1/P2  
模块：`:app`, `:core:ai:api`, `:feature:tools:api`

触发条件：

- MCP server 暴露某个会写外部系统的工具。
- 配置中的 `McpTool.needsApproval` 没有显式设 true。
- ChatService 动态注入 `mcp__<tool>`。

影响：

- `mcp_call_tool` 包装工具本身强制审批。
- 动态 `mcp__*` 绕过该包装，使用 tool 自身默认 `needsApproval=false`。
- ToolRegistry 只能通过工具名启发式抓 create/update/delete 等；任意命名的写操作可能 fail-open。

证据：

- `core/ai/api/src/main/kotlin/app/amber/core/ai/mcp/McpConfig.kt:17-22`：`needsApproval` 默认 false。
- `app/src/main/java/app/amber/core/service/ChatService.kt:2230-2237`：动态工具 `needsApproval = tool.needsApproval`。
- `feature/tools/api/src/main/kotlin/app/amber/feature/tools/ToolRegistry.kt:233-240`：只有 `mcp_call_tool` 强制审批。
- `feature/tools/api/src/main/kotlin/app/amber/feature/tools/ToolRegistry.kt:424-463`：mutating 依赖命名启发式。

建议：

- 动态 MCP 默认 `needsApproval=true`，仅用户显式标记 read-only 才放行。
- 或保留 `mcp_call_tool` 为唯一执行入口。

### MCP-002 - MCP logs 泄露 headers/tool args

严重度：P1/P2  
模块：`:app`, `:core:ai:api`

触发条件：

- 配置 MCP server headers，例如 Authorization/API key。
- 调用 MCP tool，args 含敏感内容。

影响：

- Logcat 打印 MCP configs、headers、tool args。

证据：

- `core/ai/api/src/main/kotlin/app/amber/core/ai/mcp/McpConfig.kt:8-13`：配置包含 headers。
- `app/src/main/java/app/amber/core/ai/mcp/McpManager.kt:91,98-99`：打印 configs/toAdd/toRemove。
- `app/src/main/java/app/amber/core/ai/mcp/McpManager.kt:141,188`：打印 tool args。

建议：

- headers redaction。
- tool args 只记录 keys/size/hash，不记录完整值。

### RUNTIME-001 - unfinished run 恢复链路未接入

严重度：P2/P3  
模块：`:core:agent-store-room`, `:core:agent-runtime-impl`, `:app`

触发条件：

- agent run 处于 `running` 或 `awaiting_permission`。
- 进程被杀或崩溃后重启。

影响：

- Room store 能列 unfinished run，但 app 未在启动时 replay/mark interrupted。
- `InProcessAgentRunner.listUnfinishedRuns()` 只读内存 snapshots，重启后为空。

证据：

- `core/agent-store-room/src/main/kotlin/app/amber/core/agent/store/AgentRuntimeDao.kt:36`：可查询 unfinished。
- `core/agent-store-room/src/main/kotlin/app/amber/core/agent/store/RoomAgentEventStore.kt:33-37`：暴露 list/markInterrupted。
- `app/src/main/java/app/amber/feature/chat/impl/ChatEventProjector.kt:95-103`：定义 `replayUnfinished()`。
- `core/agent-runtime-impl/src/main/kotlin/app/amber/core/agent/runtime/impl/InProcessAgentRunner.kt:164-168`：只读内存 snapshots。

建议：

- app 启动时扫描 unfinished persisted runs，统一标记 interrupted 或恢复 projector。

### RUNTIME-002 - InProcessAgentRunner 完成后 maps 不清理

严重度：P3  
模块：`:core:agent-runtime-impl`

触发条件：

- 长时间运行大量 agent runs。

影响：

- jobs/snapshots map 增长，内存占用累积。

证据：

- `core/agent-runtime-impl/src/main/kotlin/app/amber/core/agent/runtime/impl/InProcessAgentRunner.kt:35-37`：保存 jobs/snapshots。
- `core/agent-runtime-impl/src/main/kotlin/app/amber/core/agent/runtime/impl/InProcessAgentRunner.kt:62-136`：完成后未清理。

建议：

- 仅保留最近 N 个完成 run 或按 TTL 清理。

### MINIAPP-001 - MiniApp bridge `runBlocking` 长时间占用 JavaBridge 线程

严重度：P2/P3  
模块：`:app`

触发条件：

- MiniApp 调用 `fetch`, `search`, `ai.generate`, `sharedStore.*`, `launch` 或需要 confirm 的 host API。
- 用户长时间不响应确认弹窗，或网络/AI 请求耗时。

影响：

- `@JavascriptInterface` 调用线程被 `runBlocking` 占用。
- WebView bridge 后续消息可能排队，MiniApp 表现为卡住或超时。
- 确认实现切 Main 弹窗，逻辑上不死锁，但阻塞调用线程。

证据：

- `app/src/main/java/app/amber/feature/miniapp/bridge/MiniAppBridge.kt:73-98`：`@JavascriptInterface postMessage` 同步处理并发送响应。
- `app/src/main/java/app/amber/feature/miniapp/bridge/MiniAppBridge.kt:135-145,199-205,208-231`：多个 `runBlocking`。
- `app/src/main/java/app/amber/feature/miniapp/bridge/MiniAppBridge.kt:351-356`：confirm 使用 `runBlocking` 等用户。
- `app/src/main/java/app/amber/feature/miniapp/MiniAppV3Runtime.kt:43-54`：confirm 切 Main 并显示 dialog。

建议：

- bridge 改为异步分发：收到 request 后立即 launch coroutine，完成后 callback response。
- JS 侧已经有 request id，可自然支持异步。

## 5. ai provider 与消息流

### AI-OPENAI-001 - OpenAI Responses image generation final/non-stream 丢图片

严重度：P1/P2  
模块：`:ai`

触发条件：

- 使用 OpenAI Responses API 内建 `image_generation_call`。
- 非流式 `generateText()` 或流式收到 `response.completed`。

影响：

- streaming `response.output_item.done` 能解析 image。
- final `parseResponseOutput()` 不处理 `image_generation_call`。
- 最终 chat message 可能只保留文本/usage，图片丢失。

证据：

- `ai/src/main/java/app/amber/ai/provider/providers/openai/ResponseAPI.kt:695,755`：stream item 处理 `image_generation_call`。
- `ai/src/main/java/app/amber/ai/provider/providers/openai/ResponseAPI.kt:811-814`：`response.completed` 返回 `parseResponseOutput(response)`。
- `ai/src/main/java/app/amber/ai/provider/providers/openai/ResponseAPI.kt:845-900`：`parseResponseOutput()` 未处理 `image_generation_call`。
- `ai/src/main/java/app/amber/ai/provider/providers/openai/ResponseAPI.kt:113`：非流式也走 `parseResponseOutput()`。

建议：

- 在 `parseResponseOutput()` 中支持 `image_generation_call.result`。
- 增加 non-stream 与 completed event 单测。

### AI-GOOGLE-001 - Google 非流式响应对 safety-block/空 candidates 脆弱

严重度：P2  
模块：`:ai`

触发条件：

- Gemini 非流式响应只有 `promptFeedback` 或 candidates 为空。
- 响应缺少 `usageMetadata`。
- functionCall part 缺少 `name` 或 `args`。

影响：

- `!!` 触发异常，错误信息不友好。
- streaming 路径已有更细处理，非 streaming 不一致。

证据：

- `ai/src/main/java/app/amber/ai/provider/providers/GoogleProvider.kt:246-247`：`candidates!!`, `usageMetadata!!`。
- `ai/src/main/java/app/amber/ai/provider/providers/GoogleProvider.kt:331-345`：streaming 对 promptFeedback/empty candidates 有处理。
- `ai/src/main/java/app/amber/ai/provider/providers/GoogleProvider.kt:647-651`：functionCall name/args 使用 `!!`。

建议：

- 非流式复用 streaming 的 safety/empty 处理策略。
- functionCall 缺字段时返回 provider error chunk。

### AI-FILE-001 - 多模态文件编码 MIME 硬编码

严重度：P2  
模块：`:ai`

触发条件：

- 输入视频不是 mp4，或音频不是 mp3。

影响：

- provider request MIME 与真实内容不匹配。
- 可能被 Google/OpenAI compatible provider 拒绝。

证据：

- `ai/src/main/java/app/amber/ai/util/FileEncoder.kt:104-115`：video hardcoded `video/mp4`。
- `ai/src/main/java/app/amber/ai/util/FileEncoder.kt:121-132`：audio hardcoded `audio/mp3`。

建议：

- 从 ContentResolver / file extension / metadata 推断 MIME。
- 无法推断时提示不支持，而不是伪装。

### AI-STREAM-001 - 旧 `handleMessageChunk()` tool index merge 潜在错配

严重度：P3  
模块：`:ai`, `:app`

触发条件：

- 仍使用 `UIMessage.handleMessageChunk()` 的路径接收 tool call delta。
- 首 delta 只有 index/无 id，后续 delta 有 id。

影响：

- 旧 merge 逻辑可能不能把 blank-id part 替换为带 id part。
- 主 streaming path 已改用 `MessageStreamAccumulator`，因此降级为 latent/test gap。

证据：

- `ai/src/main/java/app/amber/ai/ui/Message.kt:126`：查找 by stream index 后替换仍按 id 匹配。
- `ai/src/main/java/app/amber/ai/ui/MessageStreamAccumulator.kt:24`：主 accumulator 路径独立。
- `app/src/main/java/app/amber/core/ai/GenerationHandler.kt:655,684`：部分 non-stream/final chunk 路径仍调用旧函数。

建议：

- 统一弃用旧 merge 或补 tool index/id 升级测试。

### OAUTH-001 - OpenAI/Google OAuth token 明文 SharedPreferences

严重度：P2/P3  
模块：`:ai`

触发条件：

- 用户使用 OpenAI Codex OAuth 或 Google Gemini OAuth。

影响：

- access/refresh/id token 存入普通 SharedPreferences。
- Manifest `allowBackup=false` 降低外泄面，但 root/调试/本地取证仍可读。

证据：

- `ai/src/main/java/app/amber/ai/provider/providers/openai/OpenAICodexOAuth.kt:72-82`：raw tokens 保存。
- `ai/src/main/java/app/amber/ai/provider/providers/google/GoogleGeminiOAuth.kt:91-104`：raw tokens 保存。
- `app/src/main/AndroidManifest.xml:86-87`：`allowBackup=false`。

建议：

- 迁移到 EncryptedSharedPreferences 或 Android Keystore 包装。

### AI-CLAUDE-001 - Claude redacted thinking 直接打印

严重度：P3  
模块：`:ai`

触发条件：

- Claude stream 返回 `redacted_thinking`。

影响：

- 原始 data 被 `println` 到 stdout/logcat。
- 可能泄漏 provider 返回的受限 reasoning 元数据。

证据：

- `ai/src/main/java/app/amber/ai/provider/providers/ClaudeProvider.kt:547-549`。

建议：

- 移除 println，或只记录事件类型/长度。

## 6. search 与 common HTTP

### COMMON-HTTP-001 - OkHttp `Call.await()` 取消协程不取消底层请求

严重度：P2  
模块：`:common`

触发条件：

- 使用 `common` 的 `Call.await()`。
- coroutine timeout/cancel 发生在 OkHttp 请求仍在运行时。

影响：

- 底层网络请求继续占用连接、流量与 provider 额度。
- 与 search 模块的安全 await 实现不一致。

证据：

- `common/src/main/java/app/amber/common/http/Request.kt:11-27`：`suspendCancellableCoroutine` 未注册 `invokeOnCancellation { cancel() }`。
- `search/src/main/java/app/amber/search/SearchService.kt:295-313`：search 里的 await 有取消处理。
- `common/src/main/java/app/amber/common/http/SSE.kt:89`：SSE awaitClose 会 cancel eventSource，非问题。

建议：

- 在 common await 中加入 cancellation hook。

### APP-LOG-001 的 search 子项

SerpApi/SearXNG/Perplexity/Grok/AmberAgentSearch 等搜索服务仍有 query/URL/body logging。它们统一归入 APP-LOG-001，因为根因是日志 redaction 策略缺失。

## 7. tts

### TTS-001 - 无音频 chunk 时返回 0 字节 MP3 成功

严重度：P2  
模块：`:tts`

触发条件：

- Qwen/MiniMax 等 streaming TTS provider 返回结束但没有 audio data。
- provider parse 对空 audio 返回 null，flow 无 chunk。

影响：

- `TtsSynthesizer` 返回空 `audioData`，format 默认 MP3。
- UI/播放器可能尝试播放空音频，用户看到成功但无声音。

证据：

- `tts/src/main/java/app/amber/tts/controller/TtsSynthesizer.kt:29-42`：collect 后无 chunk 仍返回 response。
- `tts/src/main/java/app/amber/tts/provider/providers/QwenTTSProvider.kt:100-118`：无 audio 返回 null。
- `tts/src/main/java/app/amber/tts/provider/providers/MiniMaxTTSProvider.kt:77,110-123`：有 `hasEmittedAudio` 但未在 closed false 时统一抛错。

建议：

- `TtsSynthesizer` 对 `output.size == 0` 抛受控异常。
- provider 层也可在 finish 无音频时报错。

### TTS-002 - SystemTTS 初始化回调理论 race

严重度：P3  
模块：`:tts`

触发条件：

- Android `TextToSpeech` 构造期间同步触发 init listener。

影响：

- listener 读取 `tts ?: error(...)`，而赋值发生在构造返回后。
- 实际平台通常异步回调，概率低。

证据：

- `tts/src/main/java/app/amber/tts/provider/providers/SystemTTSProvider.kt:30-33`。

建议：

- 用 holder/deferred 初始化，避免 listener 捕获未赋值字段。

## 8. document 与 transformers

### DOC-001 - 文档转 prompt 全量读取与 PDF native resource 风险

严重度：P2  
模块：`:document`, `:app`

触发条件：

- 用户附加大 PDF/DOCX/PPTX/text 文件。
- DocumentAsPromptTransformer 把文档转 prompt。

影响：

- 多个文档全文进入内存和 prompt，易 OOM/卡顿。
- MuPDF document/page/text 对象未显式 destroy，native 资源依赖 finalization。

证据：

- `app/src/main/java/app/amber/core/ai/transformers/DocumentAsPromptTransformer.kt:27-37,87`：读取全文。
- `document/src/main/java/app/amber/document/PdfParser.kt:8-18`：打开 MuPDF document/page/text，未显式释放。

建议：

- 文件大小和提取字符数双上限。
- PDF parser 用 try/finally destroy native 对象。

## 9. workspace、terminal、icloud、office

### WORKSPACE-001 - 外部分享 displayName 路径穿越写出 uploads 边界

严重度：P2  
模块：`:feature:workspace`, `:app`

触发条件：

- 恶意 ContentProvider 返回 displayName 包含 `../`。
- ShareHandler 将该 displayName 传给 workspace upload。

影响：

- `copyUriToUploads()` 用 displayName 拼接目标路径。
- 可写出 `/workspace/uploads`，甚至触及 mirror 内其他路径。
- 普通 workspace path 走 `WorkspacePaths.normalize()`，这条风险限于 displayName upload。

证据：

- `app/src/main/java/app/amber/feature/ui/pages/share/handler/ShareHandlerPage.kt:43`：使用外部 displayName。
- `feature/workspace/src/main/kotlin/app/amber/feature/workspace/WorkspaceManager.kt:222-229`：`copyUriToUploads(sourceUri, displayName)`。
- `feature/workspace/src/main/kotlin/app/amber/feature/workspace/WorkspaceManager.kt:254-257`：`dir.resolve(name)`，无 separator/canonical child check。
- `feature/workspace/src/main/kotlin/app/amber/feature/workspace/WorkspacePaths.kt:3-18`：普通 workspace path 有 normalize，upload displayName 绕开该保护。

建议：

- displayName 只取 basename，替换 path separators。
- target canonical 必须位于 uploadsDir canonical 下。

### TERMINAL-001 - 未配置 SAF workspace 时停止持久终端 session 返回错误

严重度：P2  
模块：`:feature:terminal`, `:feature:workspace`

触发条件：

- 用户未选择 SAF workspace。
- 启动 builtin Alpine persistent session。
- 停止 session。

影响：

- 进程已 destroy，但 `stopSession()` 后调用 `workspaceManager.syncFromMirror()`。
- `syncFromMirror()` 要求 selected SAF root，返回错误。
- 工具层可能把停止操作报告为失败。

证据：

- `feature/terminal/src/main/kotlin/app/amber/feature/terminal/TerminalRuntime.kt:270-272`：start 只 ensure mirror。
- `feature/terminal/src/main/kotlin/app/amber/feature/terminal/TerminalRuntime.kt:330-337`：stop 后 syncFromMirror。
- `feature/workspace/src/main/kotlin/app/amber/feature/workspace/WorkspaceManager.kt:319-321`：syncFromMirror 要求 selected root。

建议：

- stop session 时未配置 workspace 则跳过 sync，并在 syncNote 说明。

### TOOL-READ-001 - 多处先全量读取再截断

严重度：P2  
模块：`:app`, `:feature:workspace`, `:feature:icloud`, `:feature:office`

触发条件：

- Agent 读取大文件或远程 iCloud 文件。
- 调用工具设置 max chars/bytes。

影响：

- 内存和网络仍按完整文件消耗。
- 大文件可导致 OOM/卡顿，即使输出已截断。

证据：

- `app/src/main/java/app/amber/feature/tools/ICloudDriveTools.kt:123-131`：工具层切片。
- `feature/icloud/src/main/kotlin/app/amber/feature/icloud/ICloudDriveClient.kt:396-403`：`bodyAsText()` 读完整远程文件。
- `app/src/main/java/app/amber/feature/tools/ExternalFileTools.kt:86-95`：`file.readText()` 后截断。
- `feature/workspace/src/main/kotlin/app/amber/feature/workspace/WorkspaceManager.kt:61-72`：`readText/readBytes` 全量读取。
- `app/src/main/java/app/amber/feature/office/FeishuOfficeEnhancementManager.kt:508-522`：读完整 workspace text 后 take。

建议：

- 提供 streaming/capped read API。
- 工具层对未配置 capped reader 的路径设置文件大小上限。

## 10. permissions 与 system access

### PERM-001 - SMS read 权限判定与查询需求不一致

严重度：P2  
模块：`:feature:system`, `:feature:tools:access`

触发条件：

- 用户只授予 `RECEIVE_SMS` 或 `RECEIVE_MMS`。
- Agent 调用 SMS read/list 工具。

影响：

- broker 用 `RuntimeGrantMode.Any` 判定 capability granted。
- 实际 SMS Provider 查询需要 `READ_SMS`。
- 工具运行时失败，用户已看到“权限已授予”的状态。

证据：

- `feature/system/src/main/kotlin/app/amber/feature/system/AgentPermissionBroker.kt:258-263`：`sms_read` 包含 READ/RECEIVE 且 Any。
- `feature/tools/access/src/main/kotlin/app/amber/feature/tools/SmsAccessTools.kt:109-130`：查询 SMS Provider。

建议：

- `sms_read` 改为 READ_SMS 必需；RECEIVE_* 另拆 capability。

### PERM-002 - Calendar create 只申请 WRITE，但实际先 READ

严重度：P2  
模块：`:feature:system`, `:feature:tools:access`

触发条件：

- 用户授予 WRITE_CALENDAR，未授予 READ_CALENDAR。
- Agent 调用 calendar create。

影响：

- 工具 gate 通过 `calendar_write`。
- 创建前查询 calendars 失败。

证据：

- `feature/tools/access/src/main/kotlin/app/amber/feature/tools/CalendarAccessTools.kt:51-53`：gate `calendar_write`。
- `feature/system/src/main/kotlin/app/amber/feature/system/AgentPermissionBroker.kt:312-317`：只请求 WRITE_CALENDAR。
- `feature/tools/access/src/main/kotlin/app/amber/feature/tools/CalendarAccessTools.kt:106-127`：insert 前查询 calendars。

建议：

- `calendar_create` capability 同时要求 READ + WRITE。
- 或不查询 calendars，使用明确 calendarId 参数，但这会改变产品语义。

## 11. WebView、WebMount、DeepRead

### WEBVIEW-001 - links 查询过滤 index 与 open index 不一致

严重度：P2  
模块：`:app`

触发条件：

- `webview_links` 带 query 过滤。
- 过滤结果不是从原列表第一个开始。
- Agent 使用返回的 index 调 `open_link`。

影响：

- 展示给 Agent 的 index 是过滤后位置。
- `open_link` 使用原始 `state.links[index]`。
- 可能打开错误链接。

证据：

- `app/src/main/java/app/amber/core/ai/tools/WebViewTools.kt:353-368`：过滤并输出 filtered index。
- `app/src/main/java/app/amber/core/ai/tools/WebViewTools.kt:402-403`：open 使用未过滤 state.links index。

建议：

- 返回原始 index。
- 或保存最近一次 filtered mapping，并让 open_link 使用 mapping。

### DEEPREAD-001 - DeepRead seed URL/private fetch after approval

严重度：P2  
模块：`:app`, `:feature:board:impl`

触发条件：

- 用户批准或 Agent 调起 DeepRead seed URL。
- URL 指向 localhost/private network。

影响：

- DeepRead prefetcher 会 GET/HEAD 抓取私网资源。
- auto-approval 已复核为 fail-closed；剩余风险是审批后能力边界。

证据：

- `app/src/main/java/app/amber/core/ai/tools/DeepReadOpenRequest.kt:68-76`：只校验 HTTP(S)/host。
- `app/src/main/java/app/amber/feature/board/hotlist/deepread/DeepReadSourcePrefetcher.kt:337-366,443-451`：执行 GET/HEAD，无私网 IP guard。

建议：

- 与 `http_request` 一样加私网/localhost policy。
- 或私网 seed URL 强制高风险确认。

## 12. sync、backup、settings

### SETTINGS-001 - DataStore 偏好损坏时直接 halt

严重度：P2  
模块：`:core:settings`

触发条件：

- DataStore 中 JSON/UUID 偏好损坏。
- settings flow collection 抛异常。

影响：

- 代码调用 `Runtime.getRuntime().halt(1)`，直接杀进程。
- 单个偏好损坏会导致 app 无恢复 UI。

证据：

- `core/settings/src/main/kotlin/app/amber/core/settings/SettingsFlowExt.kt:24-31`。
- prefs decode JSON/UUID 分散在 `core/settings/src/main/kotlin/app/amber/core/settings/prefs/*Prefs.kt`。

建议：

- 对单项 pref decode 做 fallback/default。
- 提供 safe mode / reset corrupted settings。

### SYNC-001 - 备份/同步/skill 导入尺寸 DoS

严重度：P2  
模块：`:app`

触发条件：

- 用户选择超大本地备份、Google Drive 备份或 skill zip。
- zip 内 manifest/settings/secrets/table JSONL entry 超大。

影响：

- 多处 `readBytes()` 完整读入内存。
- 可能 OOM 或长时间卡顿。

证据：

- `app/src/main/java/app/amber/core/sync/local/LocalBackupRepository.kt:51-59`：URI 完整复制到 temp。
- `app/src/main/java/app/amber/core/sync/google/GoogleDriveAppDataClient.kt:55-70`：Drive file 完整下载。
- `app/src/main/java/app/amber/core/sync/core/SyncArchiveManager.kt:260-287`：settings/secrets/table JSONL `zip.readBytes()`。
- `app/src/main/java/app/amber/core/sync/core/SyncArchiveManager.kt:584-590`：manifest `zip.readBytes()`。
- `app/src/main/java/app/amber/core/ai/tools/SkillsTools.kt:393`：skill zip entry `readBytes()`。

建议：

- archive manifest/settings/secrets/table entry 加 hard cap。
- zip total uncompressed size 与 entry count 限制。

### SYNC-OLD-001 - 旧 WebDAV/S3 restore 部分应用 settings 后失败

严重度：P2/P3  
模块：`:app`

触发条件：

- 使用旧 `S3Sync` 或 `WebDavSync` restore。
- archive 中 settings 先恢复成功，后续 DB/files 恢复失败。

影响：

- settings 已应用，但 DB/files 未完成替换，产生部分恢复状态。
- zip path traversal 已由 `BackupArchiveUtils` 降低风险；这里是事务一致性问题。

证据：

- `app/src/main/java/app/amber/core/sync/S3Sync.kt:218-225`：处理 `settings.json` 时立即应用 settings。
- `app/src/main/java/app/amber/core/sync/S3Sync.kt:292-298`：DB later replace。
- `app/src/main/java/app/amber/core/sync/webdav/WebDavSync.kt:248-255`：同样先 settings。
- `app/src/main/java/app/amber/core/sync/webdav/WebDavSync.kt:322-328`：DB later replace。

建议：

- restore 先完整 stage 与 validate，再一次性 commit。
- settings 应在 DB/files 成功后最后应用。

### SYNC-TEST-001 - sync table coverage test 固定旧 schema 版本

严重度：P3  
模块：`:app`

触发条件：

- Room schema version 增加新表。
- 测试仍读取旧 schema 版本。

影响：

- 新表可能未纳入 sync archive，但测试仍绿。
- 当前 version 4 到 5 只加列，因此不是当前数据丢失 bug。

证据：

- `app/src/test/java/app/amber/agent/data/sync/SyncArchiveTableCoverageTest.kt:34-38`：读取 schema 4。
- `app/src/main/java/app/amber/agent/data/db/AppDatabase.kt:122`：当前 DB version 5。

建议：

- 测试自动读取最新 schema version。

## 13. highlight

### HIGHLIGHT-001 - Highlighter executor/context 生命周期粗糙

严重度：P3  
模块：`:highlight`

触发条件：

- 长时间使用代码高亮。
- highlighter 被 destroy/recreate。

影响：

- `destroy()` 销毁 QuickJS context，但未 shutdown executor。
- 异常路径可能存在 QuickJSArray 未释放。
- coroutine cancellation 不会及时停止 executor 上任务。

证据：

- `highlight/src/main/java/app/amber/highlight/Highlighter.kt:31`：single thread executor。
- `highlight/src/main/java/app/amber/highlight/Highlighter.kt:74-111`：`suspendCancellableCoroutine` 包装 executor。
- `highlight/src/main/java/app/amber/highlight/Highlighter.kt:113-115`：destroy 仅销毁 context。

建议：

- destroy 时 shutdown executor。
- QuickJS handles 用 try/finally 释放。

## 14. 疑似但未判定为 bug

### SUBAGENT-DISMISSED-001 - SubAgent 审批绕过

结论：暂不成立。

理由：

- `SubAgentManager` 只在 scoped history grant 下把 `session_read/session_expand` 改为 auto approve。
- 其他 allowed tools 保留原 approval policy，并由 `ToolRegistry.from` 包装。

### SEARCH-DISMISSED-001 - Perplexity/Grok citation `!!`

结论：暂不成立。

理由：

- Perplexity 先过滤 `title/url` 非空。
- Grok annotation 先过滤 `url` 非空。

### COUNCIL-DISMISSED-001 - ProviderModelCouncilTextRunner `exceptionOrNull()!!`

结论：暂不成立。

理由：

- 前面已有 `if (first.isSuccess) return`，到 `exceptionOrNull()!!` 时 first 必为 failure。

### TASK-DISMISSED-001 - `AgentTaskStore.publish()` 空 list

结论：暂不成立。

理由：

- `_tasksFlow.value = list()` 解析为类内 `list()` 方法，而不是 Kotlin `emptyList()`。

### WORKSPACE-DISMISSED-001 - 普通 workspace path traversal

结论：暂不成立。

理由：

- 普通 workspace paths 经 `WorkspacePaths.normalize()`。
- 已确认风险限于 `copyUriToUploads(displayName)`。

### MINIAPP-DISMISSED-001 - MiniApp network SSRF

结论：暂不成立。

理由：

- `MiniAppUrlGuard` 要求 HTTPS，DNS 解析后阻止 private/reserved/loopback/multicast。
- OkHttp DNS 也绑定同一 guard。
- redirects 手动复检，禁止 cookie/authorization header。

### MODELC-CLI-DISMISSED-001 - Model Council external CLI 命令注入

结论：暂不成立。

理由：

- external tool 白名单固定。
- `allow_external_cli=true` 且 ToolRegistry 对 external-cli council 强制审批。
- external model 使用正则白名单。
- prompt 写入随机 here-doc delimiter 的 temp file。
- shell command 使用 `shellSingleQuoted()` 包装。

## 15. 已运行验证命令

以下命令此前已在同一工作区执行通过，用于建立当前只读审计基线：

- `./gradlew --no-daemon :ai:testDebugUnitTest --tests 'app.amber.ai.provider.providers.openai.*' --tests 'app.amber.ai.ui.MessageStreamAccumulatorTest' --tests 'app.amber.ai.ui.MessageTest'`
- `./gradlew --no-daemon :app:compileDebugKotlin`
- `./gradlew --no-daemon :app:testDebugUnitTest --tests 'app.amber.feature.runtime.PermissionDecisionResolverTest' --tests 'app.amber.feature.tools.ToolApprovalPolicyLintTest' --tests 'app.amber.feature.webmount.*'`
- `./gradlew --no-daemon :tts:testDebugUnitTest :document:testDebugUnitTest`
- `./gradlew --no-daemon :feature:history:compileKotlin :feature:webview:compileKotlin :feature:task:compileDebugKotlin :feature:workspace:compileDebugKotlin :feature:tools:access:compileDebugKotlin :feature:terminal:compileDebugKotlin :feature:board:impl:compileDebugKotlin :core:agent-runtime-impl:compileKotlin :core:usage:compileKotlin`
- `./gradlew --no-daemon test`

本文档生成时仅新增 Markdown，没有改源码，因此未重新运行 Gradle。

## 16. 建议修复顺序

1. P0 日志泄漏：先做 redaction/关闭 request body logging，风险收益最高。
2. P1 数据一致性：删除 Undo、文件删除边界、WorkManager self-REPLACE。
3. P1/P2 工具审批：`wm_site_remove` 与动态 MCP fail-closed。
4. P1/P2 provider 协议：OpenAI Responses image generation final/non-stream。
5. P2 易崩路径：share double-base64、shortcut URI、selectIndex clamp、base64 bitmap null。
6. P2 权限和文件边界：SMS/Calendar permission、workspace upload displayName、read capped APIs。
7. P3 资源与测试缺口：Highlighter、runner map cleanup、sync schema test。
