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

## D-016（L0，2026-07-28）Assistant 全量模型：DataStore JSON 线格式、默认模板口径校正、Float 字节级保真

- **背景**：Android Assistant 不存 Room，存于 Settings DataStore 的 kotlinx JSON blob（core/settings PreferencesStore.kt:89）。持久化切片 = 全量模型 + 线格式 1:1 + KV Port。
- **决定**：
  - `Assistant` 扩为 30 字段全量模型（默认值逐字，Uuid→string/Set→array/Float→number）；嵌套 Avatar(sealed,FQN 鉴别名 `app.amber.core.model.Avatar.X`)/LocalToolOption(13 variant snake_case)/MainAgentToolProfile(6 值)/CustomHeader 一并迁移。
  - **口径校正（子集→全量暴露的两处先前偏差）**：
    1. `messageTemplate` 类型 `string | null`(默认 null)→ `string`(默认 `'{{ message }}'`，Android 原值）；`TemplateTransformer` 去掉 null-skip，**总是套用**（默认模板恒等，对齐 TemplateTransformer.kt:24-38）。
    2. `AssistantRegex.affectingScope` 默认 `['user','assistant']` → `[]`（Android `emptySet()`）；regex 规则不显式给 scope 即不适用——集成/单元测试显式补 scope 锁定。
  - **Float 字节级保真**：kotlinx `Float 1.0` → `"1.0"`，`JSON.stringify` → `"1"`；`serializeAssistant` 手工键序拼接 + `floatStr`(整数补 `.0`)，golden blob 测试锁定整条默认 Assistant 字节串。
  - 存储 Port：`KeyValueStore{get/put/delete}` + memory 实现；`assistants` blob 键为我方 schema（Android 是整个 Settings blob 内嵌字段，设备端 adapter 定最终形态）；`loadAssistants` 无键 → `null`（对齐 DataStore 无值语义，默认值由调用方落）。
  - 解析容忍：ignoreUnknownKeys（未知键忽略）、缺失字段取默认、缺 `id` 抛 `missing field 'id'`；`isLenient/allowTrailingComma` 同 serialize.ts 沿用 JSON.parse 严格解析（已有口径，不重复记 PD）。
- **可逆性**：线格式 golden 锁定；后续 Android 导入工具按此 blob 直接读写。

## D-017（L0，2026-07-28）ProviderSetting 持久化：sealed 3 变体线格式、静息 secret 归属、请求侧/持久化双模型并存

- **背景**：providers 同存 Settings DataStore（PreferencesStore.kt:88）。基准 = ProviderSetting.kt(openai/google/claude)+ Model.kt(含 BuiltInTools sealed 与 providerOverwrite 递归)。
- **决定**：
  - **静息 secret 归属**：`apiKey/privateKey/serviceAccountEmail` 是 DataStore 静息字段，进持久化线格式；请求侧 `provider_model.ts` 的 `ProviderSettingOpenAI` 保持 secret-free（D-014 不变），adapter 做 persisted→request 映射（apiKey→bearerResolver 等价物）。宪章"日志不落 token"不受影响——golden 测试用哑值。
  - **双模型并存**：请求侧 `ChatModel`（子集）与持久化 `ProviderModel`（全量：id/type/customHeaders/customBodies/tools/contextWindowTokens/providerOverwrite）并存，adapter 映射；避免请求层背持久化字段。
  - `@Transient` 的 `builtIn/description/shortDescription`（Compose lambda）不迁移——UI 关注点，不进线格式（kotlinx 也不序列化）。
  - 线格式：sealed 鉴别 `"type"` 最前（openai/google/claude），声明序键序，枚举大写（ModelType/Modality/ModelAbility），BuiltInTools `{"type":"search"}` 形态，`contextWindowTokens/providerOverwrite` null 省略，`providerOverwrite` 递归同格式；旧 JSON 缺 `brand/authMode` → generic/api_key（ProviderSetting.kt:183-190 注释口径，未知枚举值回退默认）。
  - 纯逻辑 helper 一并迁移：`hasUsableAuth`（picker 与 data fallback 共用判定矩阵）、`openAIBrandAvailableAuthModes`、`openAIAuthModeFixedBaseUrl`/`googleAuthModeFixedBaseUrl`。
  - KV 复用 D-016 Port：`PROVIDERS_KEY='providers'` blob。
- **可逆性**：golden + round-trip 测试锁定；Google/Claude provider 的请求构建不在本切片（P1），仅持久化面。

## D-018（L0，2026-07-28）上下文组装：静态 system 块子集、截断次序、会话默认注入、预设播种时机

- **背景**：发送列表组装是核心回路最后一块 source-only 逻辑。基准 = GenerationHandler.kt(:413-468,:712-767)+ PreferencesStore.resolveSessionDefaults(:419-438)+ ChatService.kt:567-572。
- **决定**：
  - `buildStaticSystemPromptParts` 只迁移**静态块**（[agentSoul, assistant.systemPrompt].filter{非blank}.join \n\n + metadata {system_prompt_cache_control: ephemeral, system_prompt_block: static}，常量对齐 SystemPromptMarkers.kt）；dynamic/tool prompt（memory/generativeUI/loopBudget/recentChats/工具 prompt）裁到 P1 → 不记 PD（功能分期，非偏差）。
  - **次序忠实**：先 `limitContext` 截断会话消息，再前置 system（Android preparedContext 在 system 组装之外）；`contextMessageSize=0` 不截断。ConversationContextEngine 的 compact 路径裁 P1。
  - `resolveSessionDefaults(assistant, groupDefault, defaultReasoningLevel)`：组默认与模型默认由调用方注入（settings 反查是 adapter 关注点）；回退链逐字（AUTO→注入默认；contextSize 0→组默认→0；maxTokens assistant??组默认）。
  - `mergeCustomParams`：customHeaders/customBodies **assistant 在前 model 在后**（:460-467 顺序即覆盖优先级，customBody 深合并时后者胜）；temperature/topP 取自 assistant。
  - `toChatModel`：D-017 双模型映射落地；`provider_model.Modality` 补 `'audio'`（Android Modality 三值，请求侧只消费 image，类型拓宽无行为变化）。
  - **预设播种时机**：presetMessages 在**会话创建时**成初始节点（ChatService:567-572），非每轮注入；`seedConversationWithPresets` 只在空会话生效。
- **可逆性**：全部为组装层纯函数；P1 加回 dynamic 块时 static 块语义不变。

## D-019（L0，2026-07-28）Chat UI 首版（编译门）:容器装配、Provider 选择策略、memory 仓库过渡、auth 头纯逻辑

- **背景**：M3 设备端交付物中可编译验证的部分（运行验证仍待 G-C）。chat HAR 全部纯逻辑已闭环，本切片接线 entry。
- **决定**：
  - **entry 依赖 @amber/chat-domain**（file:../chat + ohpm install，D-015 门）。
  - **ChatKvStore adapter**：chat `KeyValueStore` 复用 deepread `Storage`（PreferencesStorage）实现——assistants/providers 真实持久化落 Preferences。
  - **会话仓库过渡**：`createMemoryConversationRepository()`（chat-domain 自带 memory 实现，真实语义、非空实现）——**重启丢失，登记在案**；spike B 验证 RDB 后换 D-013 schema 的 RDB adapter。选择 memory 而非抢跑 RDB：RDB 建表/迁移未经设备验证，宪章禁止未验证的破坏性 schema 决策。
  - **Provider 选择策略（MVP）**：第一个 `type=openai && models 非空 && hasUsableAuth` 的 provider + 其首个 model；选择器 UI 裁 P1。请求侧 setting 由 persisted variant 映射（secret 经 `openAIAuthHeaders(authMode, baseUrl, token)` 注入 headers，对齐 ChatCompletionsAPI.kt:545-557 的 api-key/Bearer 分流）。
  - **新会话播种**：`seedConversationWithPresets`（D-018）在 ChatPage 无 conversationId 进入时生效；assistant 取 KV 首位，无配置 → 内置"默认助手"。
  - **错误路径**：runChatTurn 抛错 → errorMsg 展示 + 从仓库重取会话刷新（user 快照已持久化，对齐 D-012 错误语义）。
  - **UI 状态模式**：沿用 CouncilRunPage 惯用法（@State 原始/数组 + 容器单例 + router params Record 显式声明）；ArkTS 限制修法登记：imports 必须置顶、对象字面量需显式类型。
- **可逆性**：页面/装配均为新增；memory→RDB 换 adapter 不改 UI。

## D-020（L0，2026-07-28）Provider 设置页 MVP：单 OpenAI 变体编辑、模型逗号录入、P1 裁剪面

- **背景**：设备端可用性关键路径——无 provider 配置则 ChatPage 只能报错。KV(Preferences)已是真实持久化，设置页即可用。
- **决定**：
  - **MVP 编辑面**：列表中第一个 `type=openai` 变体的 enabled/name/baseUrl/apiKey/chatCompletionsPath/models；其余变体原样保留（替换 by id 后回写整个 blob）。brand/authMode 固定 generic/api_key（UI 默认值），Google/Claude 编辑、多 provider 管理、模型能力(abilities/modalities)编辑裁 P1。
  - **模型录入**：逗号分隔 modelId → `makeProviderModel({modelId, displayName: modelId})`；`resolveChatRuntime` 取首模型（D-019 策略）即刻生效。
  - API Key 输入用 Password 型；**不落日志**（页面无任何日志输出，宪章）。
  - ArkTS 限制修法登记：`enabled` 与 CustomComponent 基类属性冲突 → 状态字段命名避让（providerEnabled）。
- **可逆性**：纯新增页面；P1 编辑器扩展不改变 KV 线格式。

## D-021（L0，2026-07-28）会话仓库换真实 RDB：薄桥接层 + init 失败显式回退 memory

- **背景**：D-019 过渡态用 memory 会话仓库(重启丢失)。G-C 设备未解锁,spike B 真机验证仍挂起,但 relationalStore API 形态稳定,源码层 adapter 不阻塞。
- **决定**：
  - **ChatRdbRepository.ets(entry/platform_impl)**:ConversationRepository 真实实现。schema + 行映射整体复用 chat-domain persistence.ts(D-013,node 测试已锁定);本层只做 relationalStore 桥接(getRdbStore/executeSql/insert ON_CONFLICT_REPLACE/RdbPredicates query/delete),零业务逻辑——spike B 若 API 形态不符只改这一个文件。
  - **失败策略(非静默)**:init 抛错 → hilog.warn 记录错误(不含会话内容,宪章隐私条款) + 回退 memory 实现,行为与 D-019 过渡态一致。
  - **级联语义**:Harmony RDB 外键默认不开启 → delete 显式先删 nodes 再删 conversation,语义对齐 FK CASCADE;save = conv upsert + nodes 整体替换(memory 实现同口径)。
  - **排序/搜索口径**:ORDER BY is_pinned DESC, update_at DESC;title LIKE '%text%'(SQLite ASCII 大小写不敏感,对齐 Android DAO)。
  - deepread 侧 RdbRepository(memory fallback)本切片不动——它是另一契约,随 spike B 一并处理。
- **可逆性**:getChatRepository 一行换回 memory 即可回退;schema IF NOT EXISTS 幂等。

## D-022（L0，2026-07-28）会话列表交互闭环：搜索 + 滑动置顶/删除(确认弹窗)

- **背景**：ChatListPage 此前只读;repo Port 的 search/delete 与 RDB 路径(刚落地 D-021)缺真实调用方。
- **决定**：
  - **搜索**:TextInput onChange 即时过滤;query 非空走 `repo.search`(title LIKE,ASCII 大小写不敏感,SQLite 语义),空走 `repo.list`——与 Android 会话列表搜索同口径。
  - **置顶切换**:swipeAction「置顶/取消置顶」→ getById + 翻转 isPinned + save(upsert 路径);排序由 repo 层 pinned DESC,updated DESC 保证,UI 不重排。
  - **删除**:swipeAction「删除」→ AlertDialog 二次确认(标题明示不可恢复)后 repo.delete(显式级联)。删除是用户主动发起且经确认,属 L0 用户数据操作,非 agent 自主删除。
  - UI 文案硬编码中文(AGENTS.md:未明确要求本地化时先实现功能)。
- **可逆性**:纯 UI 变更;repo Port 未动。

## D-023（L0，2026-07-28）Provider/模型选择器：用户选择优先 + D-019 回退;Android 无种子列表(已核实)

- **背景**：D-019 把「选择器 UI」裁到 P1,但单 provider 场景验证完,多模型已是真实使用阻塞(逗号录入可配多模型却无法切换)。
- **决定**：
  - **选择持久化**:Preferences 键 `chat_selected_provider_id`/`chat_selected_model_id`(entry 层常量,经 Storage Port;不进 chat HAR 线格式)。
  - **解析顺序**:用户选择(provider id + model id 均命中且 hasUsableAuth)→ 命中即用;未选/失效 → 回退 D-019 策略(首可用 openai variant + 首模型)。`resolveChatProviderChoice` 私有函数统一供 resolveChatRuntime/describeChatModel 消费,选择逻辑单点。
  - **ChatModelPickerPage**:列出 usable openai variants × models,当前选中打「当前」标;空态引导去 Provider 设置页。ChatPage 头部标题下显示「provider / modelId」标签(每次 aboutToAppear 刷新),点击进入选择器。
  - **种子数据核实(否决项)**:Android ProviderTemplatePickerSheet 只提供 generic `ProviderSetting.OpenAI()/Google()/Claude()` 空模板(ProviderTemplatePickerSheet.kt:151,159-172),无预置 baseUrl 种子列表 → 不做 provider 种子,保持忠实。
- **可逆性**:删两个 Preferences 键即回到 D-019 行为;回退策略保证旧状态可用。

## D-024（L0，2026-07-28）assistant 分支再生 + 分支切换：扁平节点等价语义,窗口化 merge 记偏差

- **背景**：消息分支(MessageNode 多选)是 Android 核心交互;domain 原语已有(selectIndex),缺再生编排与 UI。
- **决定**：
  - **runRegenerateAt(chat-domain,TDD 9 测试)**:assistant 节点 regenerate = 目标节点之前的 currentMessages 为上下文(对齐 ChatService.kt:1105 messageRange 0..<nodeIndex)→ 组装+input transforms → provider 流式 → 新 assistant 消息**追加进同一节点**(追加不覆盖)且 selectIndex 指向新分支;provider 抛错 → 传播且会话不变(runChatTurn 同语义)。**user 节点 regenerate(截断重跑)裁 P1,domain 显式抛错**(宪章禁静默 no-op)。
  - **accumulator 占位修法**:base 以 assistant 结尾时直接 seed 会把新流并入旧消息(accumulator 末条=活动流式消息)→ 追加 `makeUIMessage('assistant',[])` 空占位作流式尾部,与 Android 生成尾部占位同构;无 chunk 到达 → 会话不变。
  - **selectMessageBranch**:夹取越界索引(不环绕);未知 nodeId 原样返回(UI 竞态安全)。
  - **与 Android 偏差(记 PARITY_DEBT)**:Android 窗口化 merge(streamingTail/mergeGeneratedMessagesIntoWindow/时间线分页)+ invalidateCompacts 依附其分页架构与 ContextEngine(P1);本实现为扁平节点模型等价语义。
  - **UI(ChatPage)**:渲染源从扁平 messages 改为节点 rows(buildRows);assistant 行 ◀ n/m ▶ 分支切换(>1 分支时)+ 末条 assistant「重新生成」;chat-turn 流式经 streamingTail 渲染(切片起点=现有消息+新 user),regenerate 流式预览裁 MVP(spinner)。
- **可逆性**:纯新增文件 + ChatPage 重构;runChatTurn 路径不变。

## D-025（L0，2026-07-28）会话自动命名：AiAuxiliaryGenerator 等价 + titleModel 裁剪

- **背景**：Android 生成完成后自动命名空白标题会话(AiAuxiliaryGenerator.kt:27-71,独立 launch 非阻塞)。
- **决定**：
  - **chat-domain auto_title.ts(TDD 8 测试)**:DEFAULT_TITLE_PROMPT 逐字(TitleSummary.kt:3-15 trimIndent 后,golden 测试锁定);buildTitlePrompt = {locale}/{content} 替换,content = currentMessages.takeLast(4) 的 summaryAsText 摘要(复用已移植的 summaryAsText);runAutoTitle:title 非空白且非 force → 不调用模型(测试锁定);trim 后保存;模型抛错 → 传播、会话不变、不持久化(调用方上浮,对齐 runCatching→addError 的错误上浮语义)。
  - **裁剪**:Android 用独立 titleModelId 设置 + resolveTaskChatModel 回退;鸿蒙 MVP 注入 generateText Port,entry 用当前 runtime 的 api.generateText(**reasoning OFF**,对齐 Android 参数)——titleModel 设置项 = P1。
  - **locale**:调用方传入(Android 取系统 Locale displayName);entry MVP 硬编码'简体中文'(AGENTS.md 本地化条款)。
  - **entry 接线**:send 成功路径尾部非阻塞 maybeAutoTitle(holder 模式传 runtime);失败 → errorMsg 上浮(非静默)。
  - ArkTS 修法登记:接口对象禁用 spread(arkts-no-spread)→ makeTextGenerationParams 全字段工厂;holder 对象字面量类型需具名 interface(RuntimeHolder)。
- **可逆性**:纯新增;命名失败不影响会话本体。

## D-026（L0，2026-07-28）会话建议：generateSuggestion 等价 + 失败处理偏差(非静默 hilog)

- **背景**：Android 每轮生成完成后刷新 chatSuggestions(AiAuxiliaryGenerator.kt:81-134)。
- **决定**：
  - **chat-domain suggestion.ts(TDD 7 测试)**:DEFAULT_SUGGESTION_PROMPT 逐字(Suggestion.kt:3-21 trimIndent 后,golden 锁定);content = takeLast(8) 摘要;split('\n') → trim → 滤空 → **take(10)**(次序忠实:先滤后取);模型返回空 → 清空旧建议(替换语义,非保留);抛错 → 传播、不持久化、会话不变。
  - **偏差(显式选择,非 PARITY_DEBT)**:Android 建议失败仅 printStackTrace(静默);鸿蒙 domain 传播 + entry hilog.warn 记录——满足宪章非静默条款且不打扰 UI(建议为辅助功能,失败不上浮 errorMsg,与 Android 不打扰用户的意图一致)。
  - **触发点**:send 成功 + regenerate 成功(对齐 Android handleMessageComplete 后触发);均为非阻塞。中间态清空(in-memory clear)裁 MVP——鸿蒙一次性生成后替换,无中间写库。
  - **UI**:输入框上方横向滚动 chips,点击填入输入框(不直接发送,发送由用户确认);sending 中隐藏。
  - 裁剪:同 D-025,suggestionModelId 设置 = P1;locale 硬编码'简体中文'。
- **可逆性**:纯新增 + ChatPage 渲染增项;建议生成失败不影响会话。

## D-027（L0，2026-07-28）助手管理 UI MVP：4 字段编辑 + patchAssistant helper(ArkTS 限制系统性修法)

- **背景**：Assistant 30 字段已全量移植(D-016)且 KV 持久化可用,但无管理 UI;系统提示词是 Android 助手核心能力。
- **决定**：
  - **ChatAssistantsPage**:列表(loadAssistants,空回退默认)+ 内联编辑器;MVP 编辑面 = name/systemPrompt/temperature/contextMessageSize 4 字段,其余 26 字段保留原值(替换 by id 回写整个 blob,与 Provider 设置页同构)。全量编辑器(tags/regexes/presetMessages/localTools/mode/lorebook 等) = P1。
  - **patchAssistant(chat-domain,2 测试)**:语义 = Kotlin data class copy()。**系统性修法登记**:entry .ets 禁用接口对象展开(arkts-no-spread),凡领域对象不可变更新一律在 HAR 内提供 copy helper(chat HAR 的 .ts 不受此限,已验证 assembleHar 通过)。
  - temperature 空输入 → null(助手默认跟随全局,对齐 Android temperature: Float? 语义);contextMessageSize 空/非法 → 0(不截断)。
  - 助手绑定会话(conversation.assistantId 已在线格式)+ 每会话选助手 = P1;当前 resolveChatRuntime 取首个助手(D-019 策略延续)。
- **可逆性**:纯新增页面;KV 线格式不变(全字段序列化早已锁定)。

## D-028（L0，2026-07-28）会话-助手绑定：assistantId 全链路(runtime 解析/新对话指定/列表入口)

- **背景**：conversation.assistantId 早在线格式(D-013),但 runtime 一直取首助手(D-019),助手管理页(D-027)落地后绑定成为真实可用性缺口。
- **决定**：
  - **resolveChatRuntime(assistantId?)**:绑定 id 命中即用;未传/未命中 → 首助手(D-019 延续,老会话 DEFAULT_ASSISTANT_ID 自然回退)。ChatPage send/regenerate 均传 conversation.assistantId。
  - **新对话指定助手**:ChatPage 读 router 参数 assistantId → 命中则 makeConversation({assistantId}) + 用该助手 seedConversationWithPresets(presetMessages 按助手隔离,对齐 Android 每助手隔离会话语义);未命中回退首助手。
  - **入口**:ChatAssistantsPage 行内「对话」→ 带 assistantId 直开新会话。
  - 会话列表按助手分组/过滤 = P1(Android 有助手维度隔离视图)。
- **可逆性**:resolveChatRuntime 参数可选,旧调用零影响。

## D-029（L0，2026-07-28）消息删除：节点级语义忠实 + 长按确认 + 生成中屏蔽

- **背景**：ChatService.buildConversationAfterMessageDelete(ChatService.kt:2182-2208) 是消息管理核心;鸿蒙此前无消息删除。
- **决定**：
  - **chat-domain delete_message.ts(TDD 5 测试,逐分支忠实)**:目标 message 从 node 移除;node 空 → 整节点移除;否则 selectIndex 夹到 lastIndex;failIfMissing 默认 true → 抛错(对齐 NoSuchElementException),false → 原样返回(UI 路径用 false,对齐 Android deleteMessage(message) 重载)。invalidateCompacts 无对应物(ContextEngine = P1,PARITY_DEBT 已记)。
  - **UI**:MessageRow 长按 → AlertDialog 确认(明示不可恢复)→ 删除当前分支消息 + 持久化;**sending 中不提供入口**(对齐 ChatVM 生成中禁删语义)。
  - 删除是用户主动发起且经确认,L0 用户数据操作。
- **可逆性**:纯新增;节点模型不变。

## D-030（L0，2026-07-28）user 节点 regenerate 落地：截断先持久化 + 新节点续跑(D-024 P1 项清零)

- **背景**：D-024 把 user 消息 regenerate(截断重跑)裁为 P1 并显式抛错;assistant 路径落地后该裁剪成为功能缺口(Android 完整支持)。
- **决定**：
  - **语义(ChatService.kt:1091-1099 逐行对齐)**:截断目标 user 节点之后的所有节点(subList 0..indexAt)→ **先持久化截断** → 以截断后会话为基底生成 → 新 assistant 作为**新节点**入列(非分支追加,与 assistant 路径语义区分)→ 再持久化。provider 抛错 → 截断保持已持久化(Android 同序,测试锁定 saved[0]=截断/saved[1]=终态)。
  - **accumulator 占位**:user 路径基底以 user 结尾,新 assistant delta 自然开新消息,**不加占位**(assistant 路径仍需占位);两条路径在实现内显式分叉并注释。
  - **regenerateAssistantMsg=false 分支**(仅保存不生成)= 冷门路径,裁 P1(UI 无入口)。
  - **UI**:user 消息行也显示「重新生成」(任意 user 消息可截断重跑,语义良定义);D-024 P1 裁剪项清零。
- **可逆性**:两路径共享流式骨架;assistant 路径行为经既有 8 测试锁定无回归。

## D-031（L0，2026-07-28）消息编辑：分支追加语义忠实 + UI 组合「编辑并重发」一步化

- **背景**：ChatService.editMessage(ChatService.kt:2054-2086);Android 为编辑(新分支)+ 用户再触发 regenerate 两步。
- **决定**：
  - **chat-domain edit_message.ts(TDD 5 测试,逐分支忠实)**:空/全空白 parts → 原样返回(isEmptyInputMessage 等价);目标 node 追加新消息(role=node.role,新 id)为新分支,selectIndex 指向新分支,旧分支保留;未知 messageId → 原样返回(edited=false,不抛错)。
  - **UI 组合偏差(显式选择)**:Android 两步(编辑→再点 regenerate);鸿蒙「编辑并重发」一步 = editMessage + 持久化 + runRegenerateAt(user 节点截断续跑,D-030 路径)。用户意图等价且少一次往返;旧分支完整保留,行为可回退到两步(直接点「重新生成」不编辑)。
  - **编辑态 UI**:user 行「编辑」→ 内容填入输入框(placeholder 提示重发),「取消编辑」退出;发送键复用。
- **可逆性**:domain 纯新增;UI 编辑态不改变 send 主路径。

## D-032（L0，2026-07-28）推理力度编辑：assistant.reasoningLevel 7 档 chips(助手级,忠实 Android 归属)

- **背景**：reasoningLevel 7 档表与 per-host 请求矩阵早已移植(D-014/D-018),resolveSessionDefaults 已消费 assistant.reasoningLevel;缺编辑入口。
- **决定**：
  - **归属忠实**:Android ReasoningButton 在助手设置页写 `assistant.reasoningLevel`(AssistantBasicPage.kt:477-482)——鸿蒙同:ChatAssistantsPage 编辑器加 7 档 chips(自动/关闭/低/中/高/超高/最大,序与 ReasoningLevel.entries 一致),经 patchAssistant 持久化到 assistants blob。
  - **生效链**:resolveChatRuntime → resolveSessionDefaults(assistant) → params.reasoningLevel → buildChatCompletionRequest per-host 矩阵(已被 chat 测试锁定),无需新接线。
  - rememberedReasoningLevelsByModelId(30 字段之一,模型已移植)Android 端未见写入点(grep 无引用)→ 不实现写路径,字段保留线格式兼容。
  - 聊页内快捷切换(Android 无此入口,助手级而已)→ 不做,避免超 Android 语义。
- **可逆性**:纯 UI 增项;默认 auto 行为不变。

## D-033（L0，2026-07-28）会话 fork：forkConversationAtMessage 等价 + 文件复制/compact 复制裁剪

- **背景**：Android 消息菜单 fork(ChatService.kt:2088-2127)是分支探索核心;鸿蒙分支切换/regenerate 已备,fork 成自然下一步。
- **决定**：
  - **chat-domain fork.ts(TDD 3 测试,忠实)**:复制 0..targetIndex(含)节点;**node id 全部换新、message id 保留**(Android 同);新会话 id 新、assistantId 相同、**title 默认空**(Android Conversation() 默认参数,自动命名下轮补);未命中抛错(NoSuchElement 语义)。
  - **裁剪**:copyWithForkedFileUrl(file: 协议文件复制)= P1(鸿蒙多模态输入未落地,无文件类 part 来源,恒等处理);copyValidCompacts(ContextEngine = P1)。
  - **UI**:所有消息行加「分叉」→ 保存新会话 → 跳转新 ChatPage 实例(conversationId 参数)。
- **可逆性**:fork 产生独立新会话,原会话零影响。

## D-034（L0，2026-07-28）会话手动改名 + patchConversation(copy helper 家族补齐)

- **背景**：Android ChatVM.updateTitle(copy title + save);自动命名(D-025)已备,手动改名缺失。
- **决定**：
  - **patchConversation(chat-domain,1 测试)**:Kotlin copy 语义;与 patchAssistant(D-027)同一系统性修法(entry .ets 禁接口展开)。saveTitle 改经此 helper。
  - **UI**:ChatPage 标题点击 → 内联 TextInput 编辑(保存/取消);子组件 onClick 优先,模型标签点击仍进选择器;编辑态屏蔽父 Column 导航。
  - 改名后 auto-title 不再覆盖(title 非空白,D-025 条件天然满足)。
- **可逆性**:纯增项;标题清空后下一轮生成会重新自动命名(Android 同语义)。

## D-035（L0，2026-07-28）停止生成：abort 全链路 + 部分保存语义 + EntryAbortController

- **背景**：Android stopGeneration 取消 job,流收集 onFailure(CancellationException)→ checkpointConversation(force) **保存部分生成内容**,错误不上浮。
- **决定**：
  - **链路**:HttpClient.fetchStream(signal,RcpHttpClient 早已实现 destroy 收尾)→ OpenAIChatApi.streamText(CallOpts.signal,已有)→ ChatStreamProvider 新增可选第三参 StreamOpts{signal}(向后兼容,asChatStreamProvider 透传)→ runChatTurn deps.abortSignal。
  - **语义(chat_turn,TDD 4 测试锁定)**:provider 抛错且 signal.aborted → 已累积快照**照常走 onGenerationFinish transforms** → 部分 assistant 节点入列 + 持久化 → **正常 resolve**(对齐 checkpoint force 语义);无 chunk → 仅 user 快照,resolve;非 abort 错误维持 D-012 传播。signal 透传由测试锁定。
  - **EntryAbortController**:ArkTS 无全局 AbortController — deepread runtime_api 双运行时设计(接口而非全局类型)的预期 entry 实现:aborted 标志 + abort 监听列表,满足 RcpHttpClient addEventListener 形态。abort 前注册/abort 后注册均触发。
  - **UI**:发送中按钮变「停止」(红)→ abort();部分消息立即可读,可继续对话或 regenerate。
  - regenerate 路径 abort 处理 = P1(当前 abort 仅接 send;regenerate 用同 provider 但不传 signal,行为同前)。
- **可逆性**:StreamOpts 可选,全链路向后兼容;不传 signal 行为与 D-012 完全一致。

## D-036（L0，2026-07-28）regenerate abort 落地：D-035 停止生成 P1 项清零

- **背景**：D-035 停止生成仅接 send 路径,regenerate abort 裁为 P1。
- **决定**：
  - **RegenerateDeps.abortSignal**(TDD 2 测试):assistant 路径 abort → 部分分支照常追加进节点 + selectIndex 指向 + 持久化 + 正常 resolve(chat_turn D-035 同语义);非 abort 错误维持传播、会话不变;user 路径 abort → 截断保持已持久化(Android 同序,逻辑复用同一 catch 分叉)。
  - **UI**:regenerate 也创建 EntryAbortController 并传入;「停止」按钮对 send/regenerate 均生效(同一 this.sending/abortController 状态机)。
  - D-035 P1 项清零,停止生成语义全路径一致。
- **可逆性**:abortSignal 可选;不传行为与 D-030 完全一致。

## D-037（L0，2026-07-28）助手删除/复制：级联语义忠实 + 确认弹窗明示

- **背景**：助手管理(D-027)缺删除/复制;Android AssistantVM 语义明确。
- **决定**：
  - **复制(AssistantVM.kt:73-84 忠实)**:新 id + name " (Clone)" + avatar image→dummy(其余保留),追加到列表末尾。
  - **删除(AssistantVM.kt:47-61 忠实)**:**级联删除该助手所有会话**(deleteConversationOfAssistant 等价:repo.list(assistantId) → 逐个 delete,显式级联 D-021);AlertDialog 明示「及其所有对话将被删除,且不可恢复」。memory/files 清理无对应物(未落地,PARITY 层面无需记——功能不存在而非偏差)。
  - 删除是用户主动发起且经确认的 L0 用户数据操作;级联范围在弹窗中明示(比 Android 的 ActionSheet 更显式,Android 弹窗未提示级联——此处**超出 Android 明示度**,符合宪章数据安全精神,登记为有意增强)。
- **可逆性**:删除不可逆(用户确认);复制纯增量。

## D-038（L0，2026-07-28）多模态图片输入：picker 物化 data: URL + 请求侧多模态

- **背景**：Android 图片输入链路 = PhotoPicker → FileEncoder(data:/http(s): 直通,file:// 读+压缩+base64,FileEncoder.kt:68-103)→ UIMessage image part → OpenAI 请求侧 image_url;isEmptyInputMessage(Message.kt:255) 允许"仅图片无文本"。
- **决定**：
  - **runChatTurn 输入泛化(chat-domain,TDD 3 测试)**:`userInput: string | UIMessagePart[]`;`isEmptyInput` 判定(parts 全空或全空白文本 → no-op 返回原会话不持久化,对齐 Android isEmptyInputMessage)。
  - **EntryImageEncoder(entry adapter)**:ImageEncoder Port 同步实现 — data:/http(s): 直通;file:// → fileIo 读 + util.Base64Helper → `data:{mime};base64,` 前缀(mime 按扩展名,png 兜底);其他 scheme 抛错(对齐 Android 编码失败即抛,D-014 missingEncoder 同口径)。
  - **选图物化**:ChatPage 「图片」→ PhotoViewPicker(IMAGE_TYPE,≤3 张)→ 即刻 readImageAsDataUrl 物化为 data: URL 存入待发预览(可单张移除);发送时组装 text+image parts 传入 runChatTurn;失败/异常后 pendingImages 清空(图片已随 user 快照持久化,防重发重复)。读取失败逐张 hilog.warn + errorMsg(非静默)。
  - **渲染**:MessageRow image part → `Image(p.url)`(ArkUI Image 支持 data: URL,真机验证列 Spike C 登记项)。
  - **裁剪与偏差登记(PARITY_DEBT PD-007)**:① Android file:// 统一压缩(compressAndEncode)裁 P1,鸿蒙原样编码;② Android 选图复制到应用私有目录存 file:// 引用,鸿蒙 MVP 消息体直接存 data: URL(消息体变大,RDB 字段无大小限制,后续按存储体积评估)。
  - 编辑态不携带图片(对齐 Android 编辑仅文本)。
- **可逆性**:纯增项;无图片时发送路径与 D-035 前完全一致(string 输入)。

## D-039（L0，2026-07-28）助手编辑补齐 topP/maxTokens：参数组闭环

- **背景**：助手编辑器(D-027)仅 4 字段,topP/maxTokens 虽已在模型/wire/请求侧全链路落地(D-016/D-014,openai_request.ts:389-393),但无写入入口 → 字段恒为默认 null。
- **决定**：
  - **编辑器新增 topP/maxTokens 文本输入**(与 temperature 同款 MVP 输入;Android 用 Switch+Slider 为视觉层,语义等价:空白 = null 继承 provider 默认)。
  - **语义忠实**:maxTokens 按 Android AssistantBasicPage.kt:496-501 — 空白/非数/≤0 → null(parsePositiveIntOrNull);topP 按 parseFloatOrNull(空白 → null)。
  - **链路核实(无新逻辑)**:mergeCustomParams(context_assembly.ts:105-110)取 assistant.topP;resolveSessionDefaults(:82-94)maxTokens 回退链 assistant → 组默认 → null;openai_request.ts:389-393 仅非 null 时写 temperature/top_p/max_tokens(MiMo 走 max_completion_tokens)。编辑即生效,无需 provider 侧改动。
  - wire round-trip 已由 D-016 assistant_serialize 测试字节级锁定;参数合并/回退由 context_assembly.test.ts 锁定 → 本切片不新增领域测试(entry-only)。
- **可逆性**:纯增项;不填时行为与 D-027 完全一致(全 null)。
