# 决策日志

> 分级：L0 自行决定不记录；L1 自行决定并记录于此；L2 按推荐默认继续，记录于此并挂 PENDING_DECISIONS；L3 阻断等人工。
> 每条含：背景 / 决定 / 理由 / 影响 / 可逆性。

## D-001（L1，2026-07-28）HTTP 栈：RCP → @ohos.net.http

- **背景**：entry 的 HttpClient 实现原用 `@kit.RemoteCommunicationKit`（RCP）。
- **决定**：改用 `@ohos.net.http`（系统 NetStack）。
- **理由**：RCP 自带 DNS 解析器，部分设备默认 DNS 为回环地址，导致 `Couldn't resolve host name`；NetStack 走系统 DNS 配置，稳定。
- **影响**：仅 entry adapter 层，deepread 领域层 `HttpClient` Port 不变；与 Android OkHttp 的语义对应关系不变（都是"系统级 HTTP 客户端"）。
- **可逆性**：可逆，adapter 内聚。后续若需 RCP 高级特性（如更细粒度流控）可再评估。
- **关联**：plan §5.2 中 "OkHttp → `@ohos.net.http` `requestInStream()` 或 RCP" 决策落地为前者。

## D-002（L1，2026-07-28）SSE 解码器注入，不在领域层用全局 TextDecoder

- **背景**：ArkTS 应用层无全局 `TextDecoder`（Node 全局）；`SseAssembler` 属纯逻辑层，禁止依赖 `@kit`。
- **决定**：`DecodeChunk` 函数注入。Node 测试用 globalThis 探测 + ASCII 兜底；entry 注入 `util.TextDecoder`。
- **理由**：保持 deepread HAR 零 SDK 依赖、可用 node+tsx 单测；避免 ArkTS 编译期扫描到不存在的全局字面量。
- **影响**：`SseAssembler`/`createSseStreamHandler` 构造函数签名扩展（带默认值，向后兼容）。
- **可逆性**：可逆。

## D-003（L2，2026-07-28）prefetch 失败：hard-fail → 降级 fallback source

- **背景**：Android `createRunContext` 对应路径中，空 prefetch 直接失败并提示"没有抓到足够的来源"。
- **决定**：鸿蒙版 prefetch 为空或异常时，用 topicTitle 构造最小 fallback source，走 fallbackPlan 产出降级文章。
- **理由**：搜索源全挂是常见真实场景（无 key/网络限制），hard-fail 体验差；降级文章至少给出结构化输出。
- **影响**：**用户可见行为变化**，偏离 Android 生产语义 → 记 PARITY_DEBT PD-001，挂 PENDING_DECISIONS 等批量确认。
- **可逆性**：可逆（改回 early return 即可）。
- **验证**：`run_manager.test.ts` 已更新断言（ok=true + fallback source）。

## D-004（L2，2026-07-28）PREFETCH_WALL_BUDGET_MS 36s → 15s

- **背景**：Android `SOURCE_COLLECTION_TIMEOUT_MS = 36s`（DeepReadSourcePrefetcher.kt:688）。
- **决定**：鸿蒙版降为 15s。
- **理由**：移动场景下 36s 无反馈等待过长；15s 与 HTTP 客户端 connect/read 超时（15s/20s）匹配。
- **影响**：偏离 Android 常量 → PARITY_DEBT PD-002。可能影响来源收集完整度（更少时间 = 可能更少来源）。
- **可逆性**：可逆，单常量。

## D-005（L1，2026-07-28）debug 签名配置入库

- **背景**：DevEco 自动签名在 `harmony/build-profile.json5` 写入 signingConfigs，含本地绝对路径与加密后的 keyPassword/storePassword。
- **决定**：提交入库。
- **理由**：密码为 DevEco 加密形态（非明文），密钥文件本体（.p12/.cer/.p7b）留在 `~/.ohos/` 不入库；preview 分支单机开发，入库保证 `build-harmony-app.mjs` 直接产出已签名 HAP。另有 `scripts/sign-hap.sh` 离线签名作为无 AGC 环境的备选路径。
- **影响**：其他机器 clone 后需替换为自己的签名配置或清空 signingConfigs；发布版（release）签名必须另行管理，**禁止复用此 debug 材料**。
- **可逆性**：可逆（清空 signingConfigs 数组）。

## D-006（L1，2026-07-28）治理文件体系：沿用仓库原生 + 新增三份轻量索引

- **背景**：上游提示词要求 9 份治理文件；仓库已有 capability-matrix/gate0-report/harmony-inventory 原生体系。
- **决定**：不新建 9 份。保留仓库原生文件为主干，新增 `MIGRATION_STATE.yaml`（进度快照）、`DECISION_LOG.md`（本文件）、`PARITY_DEBT.md`（等价偏差）、`PENDING_DECISIONS.md`（待确认队列）四份。
- **理由**：避免双轨维护成本；审计矩阵（STREAMING/DATA_SCHEMA）按计划书要求以 CSV 形式补入 `docs/harmony-inventory/`。
- **可逆性**：可逆。

## D-007（L1，2026-07-28）ObjectBox 预研关闭：无实际使用

- **背景**：迁移输入提到"§4.1 ObjectBox 替代"预研。
- **决定**：关闭该预研项，不做 ObjectBox 替代方案设计。
- **理由**：`settings.gradle.kts` 仅有 plugin resolutionStrategy 的坐标映射；app/core/feature/ai 源码中**零引用**（无 entity、无依赖、无 import）。Android 存储基准实为 Room（core:agent-store-room）+ DataStore（core:settings）。
- **影响**：DATA_SCHEMA_MATRIX 以 Room/DataStore 为唯一审计对象。
- **可逆性**：若后续发现隐藏使用（如动态加载），重开。

## D-008（L1，2026-07-28）Chat 核心领域层独立 HAR：`harmony/chat`（@amber/chat-domain）

- **背景**：Chat MVP 主线需要完整版 UIMessage/MessageNode/Conversation；deepread HAR 内的 `agent/message.ts` 是 Deep Read 功能子集（仅 text/image/reasoning/tool，无 metadata/annotations/usage）。
- **决定**：新建 `harmony/chat/` HAR 承载 Chat 核心领域层。deepread 子集暂不动，待 Chat 层稳定后评估统一（登记为技术债候选，不阻塞）。
- **理由**：职责分层（core vs feature）；避免 deepread 子集被动膨胀；tsx 单测 + hvigor 编译双验证模式已验证有效。
- **关键实现决策**：
  - JSON 鉴别器字段名用 `type`（kotlinx 默认 classDiscriminator，`ai/util/Json.kt` 未覆盖）—— persisted blob 与 Android 线格式 1:1。
  - sealed class → discriminated union interface + 工厂函数（ArkTS 友好，同 deepread 模式）。
  - 时间戳统一 ISO 8601 UTC 带 `Z`（Android UIMessage.createdAt 为 LocalDateTime 无时区）→ 偏差记 PD-004。
  - uuid/时间通过 globalThis 探测 + 注入模式（D-002 同款），领域层不依赖 `@kit`。
- **可逆性**：可逆（模块内聚，未被 entry 依赖前可整体调整）。

## D-009（L0，2026-07-28）limitContext 级联语义以 Android 算法为准

- 测试初稿假设"已执行 tool 起点只前扩一级"，实际 Android 算法会**级联**（已执行结果→tool call→user 消息，直至 0 号位）。实现与测试均以 Android 生产语义为准修正。无代码偏差。

## D-010（L0，2026-07-28）chat HAR 校验门增加 `tsc --noEmit`

- **背景**：node+tsx 测试走 esbuild，只转译不做类型检查；切片 2 中 `coalesceStreamParts` 的 TS2698（闭包赋值使裸 let 控制流收窄失效）测试全绿但类型错误。
- **决定**：chat HAR 提交门 = `npx tsc --noEmit` 干净 + `npm test` 绿 + hvigor BUILD SUCCESSFUL，三者缺一不提交。
- **影响**：TS 严格性向 ArkTS 编译器行为靠拢，提前暴露 esbuild 漏掉的类型错误。

## D-011（L0，2026-07-28）chat HAR 依赖 deepread-domain 复用 SseAssembler；OpenAiStreamError 增补 payload

- **决定**：
  - chat HAR 经 `file:../deepread` 依赖 `@amber/deepread-domain`，复用 SseAssembler/SseEvent（deepread index 补导出）；与 MIGRATION_STATE 记载的"platform 层 Port 同居 deepread HAR"结构一致。node 侧经 tsconfig paths 解析。
  - 流式 error payload 抛 `OpenAiStreamError(message, payload)`：message 与 Android `HttpException`（ErrorParser.parseErrorDetail）逐字一致；`payload` 为增补的原始 error 对象（保留 `type` 等字段供诊断，Android 侧丢弃）。抛出信号语义不变，不记 PD。
- **连带**：tsc 门经 paths 覆盖 deepread 源码后暴露 `novel/creation.ts` 3 处 TS2352（同类闭包收窄失效，`x as T` 强转不自洽）；按 holder 模式修复并加 null 检查抛错，运行时行为不变（原强转在异常路径上本就会返回 null）。

## D-012（L0，2026-07-28）transformer/chat_turn 纯逻辑子集裁剪与 Port 化

- **决定**（切片 4 范围内的一组 L0 裁剪，合并记录）：
  - `TransformerContext` 只含 `assistant` + `clock`：Android 的 android.Context/Settings/Model/processingStatus 属 adapter 层关注点，不进纯逻辑层；clock 可注入（模板 {{time}}/{{date}}、think 关闭时间戳），默认本机时间。
  - `TemplateTransformer` 模板直接取 `ctx.assistant.messageTemplate`（Android 经 settingsStore 按 assistant id 反查的等价扁平化）。
  - `replaceRegexes` 去掉 Rust native 路由（Android 性能优化，纯 JVM 路径语义），统一 JS RegExp → 语法子集偏差记 PD-005。
  - `ChatStreamProvider`/`ConversationStore` Port 化；chunk 用回调式（对齐 deepread `HttpClient.fetchStream` 风格，规避 AsyncIterable 在 ArkTS 的不确定性）。
  - chat_turn 错误路径：provider 抛错直接传播；retry/vision fallback/工具执行/审批/生成守卫明确裁到 P1 → PD-006。
  - `RegexOutputTransformer` 忠实保留 Android 的"只实现 visualTransform/visualTransformTail、onGenerationFinish 缺省恒等"行为（尽管看起来可疑，Android 即如此，不改）。
- **可逆性**：全部为模块内裁剪，设备端阶段可逐层加回。

## D-013（L0，2026-07-28）Chat 持久化 schema：RDB 表结构、行映射与序列化 quirk 复刻

- **背景**：M3「本地保存/重启恢复」前置。基准 = DATA_SCHEMA_MATRIX T01/T02 + ConversationEntity/MessageNodeEntity + ConversationDAO + ai/util/Json.kt。
- **决定**：
  - `conversation` 表**丢弃 Android 废弃 `nodes` 列**（矩阵注明"迁移时可丢弃"；Android 侧恒 `"[]"`）。
  - 表名用 `conversation`/`message_node`（矩阵已定）；Android Room 实际表名为 `conversationentity`——**未来 Android DB 导入工具需做表名映射**，登记在案。
  - `create_at`/`update_at` 为 epoch ms INTEGER（Android Long），领域层 ISO 字符串在行映射处互转（Date.parse/toISOString，精度毫秒无损）。
  - 消息正文 = `message_node.messages` JSON blob，序列化严格对齐 kotlinx（鉴别器优先 + 声明序、encodeDefaults、explicitNulls=false、ignoreUnknownKeys）→ golden blob 测试锁定键序。
  - **quirk Q-1 忠实复刻**：`Reasoning.finishedAt` 声明默认 `Clock.System.now()`，null 省略后解码时复活为解析时刻；`parseUIMessage(blob, now)` 的 now 参数即此来源（可注入）。
  - Repository Port 覆盖 DAO 语义子集：upsert+整体替换 nodes、list/search 一律 `is_pinned DESC, update_at DESC`、title 搜索 ASCII-only 大小写折叠（SQLite LIKE 语义）、delete 级联。Paging/Flow 响应式为 Android 框架关注点，不进 Port。
- **可逆性**：schema 首版；设备阶段 spike B 验证 RDB 兼容后冻结，后续变更走迁移策略。




## D-014（L0，2026-07-28）OpenAI 请求侧纯逻辑：Provider 模型裁剪、ImageEncoder Port、matcher 简化、node-only 文件出 ets 树

- **背景**：Chat MVP 请求侧切片（基准 = ChatCompletionsAPI.kt buildChatCompletionRequest/buildMessages + ProviderSetting.OpenAI + Request.kt + ProviderMessageUtils.kt）。
- **决定**：
  - `ProviderSettingOpenAI`/`TextGenerationParams` **不含 apiKey/customHeaders**：secret 与传输 header 属 adapter 关注点，纯逻辑层只产出请求 body `JsonObject`；也避免密钥进入日志/测试快照（宪章：日志不落 token）。
  - 图片 base64 编码为注入式 `ImageEncoder` Port（文件 IO 在 adapter）；**未注入或编码失败一律抛错**，忠实对齐 Android `encodeBase64().getOrThrow()` 与 "image encoding failure should fail" 语义——不放空、不吞错。
  - **ModelRegistry token matcher 简化**：`OPENAI_O_MODELS`(^o$+^\d+$ 双 token)→ `/^o\d+(-|$)/`；`GPT_5` matcher 被 Android 自己的 `startsWith("gpt-5")` 完全覆盖，只保留后者 + `contains("codex")`。边界差异：如 `o1abc`（Android token 化不匹配→允许温度；简化正则同样允许）——已用测试锁定一致口径。
  - per-host reasoning 矩阵 10 分支逐字迁移（含 SiliconFlow 26 模型名单 verbatim）；`mergeCustomBody` 深合并（同键 JsonObject 递归，否则替换，blank key 跳过）逐字迁移。
  - **连带修复（提交门堵漏）**：`topic_id_node.ts`（import node 'crypto' + 全局 URL/URLSearchParams）从 `deepread/src/main/ets/domain/` 移至 `src/test/`——文件注释自称"仅 node 测试"，但 `assembleHar` 的 HarCompileArkTS 编译 ets 树全部文件，非引用图可达集；此前门只跑过 `assembleHap`（引用图编译）漏检。本切片起 hvigor 门 = `assembleHar`（全模块）+ `assembleHap` 双绿。另修 `council_manager.test.ts` 2 处存量 TS2722（可选方法 `addEventListener` 缺 `!` 断言，与 D-010 同类）。
- **可逆性**：纯逻辑层字段增删不影响 wire 格式；matcher 简化仅影响温度/top_p 是否下发，测试锁定。

## D-015（L0，2026-07-28）OpenAI Chat API 组装层：HTTP Port 接线、错误传播优先级、ohpm 依赖链接入提交门

- **背景**：Chat 主回路 source-only 收口（请求构建 → HttpClient Port → SSE 装配 → chunk 回调）。基准 = ChatCompletionsAPI.kt streamText(:165-291)/generateText(:107-163)/onFailure(:258-278)。
- **决定**：
  - `createOpenAIChatApi({http, setting, headers?, encodeImage?})`：URL = `baseUrl + chatCompletionsPath`；默认 `Content-Type: application/json`；**auth/customHeaders/referer 由 adapter 经 `headers()` 注入**（bearerResolver/KeyRoulette/configureReferHeaders 不进纯逻辑层，secret 不出 adapter）。
  - **错误传播优先级**：流内 parse 异常/error payload（OpenAiStreamError）> 非 2xx 状态。非 2xx 且 body 可解析 → `parseOpenAiErrorDetail`（先取 normalize 首行，对齐 :266-268）；不可解析 → `Failed to get response: CODE RAW` 根因错误（不用 parse 异常覆盖根因，对齐 :272-274）。
  - 终止语义：parse 失败/[DONE] 后不再消费后续事件（对齐 :205-206 主动 cancel+close 的语义）；OkHttp `eventSource.cancel` 在本 Port 无对应，底层流由 adapter `signal` 收尾——注释登记，不记 PD（Port 层差异，设备端 spike A 验证）。
  - `asChatStreamProvider(api, paramsFor)` 薄适配 chat_turn 的 ChatStreamProvider（params 按会话供给，不烘焙进 API）。
  - **门堵漏**：chat HAR 首个 import `@amber/deepread-domain` 的 main 源文件暴露 ohpm 链接缺失（node 侧走 tsconfig paths 从未暴露）；修复 = 模块内 `ohpm install`（file: 链接，oh_modules gitignored）。提交门补充：新引入跨 HAR import 后必须 `ohpm install` + `hvigorw clean` 全量重建双绿（本次 clean assembleHap 4.9s 验证）。
- **可逆性**：组装层无 wire 格式决策；错误语义测试锁定。
