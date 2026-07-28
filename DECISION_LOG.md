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

## D-040（L0，2026-07-28）非流式输出：streamOutput=false → generateText 单次补全

- **背景**：Android AssistantBasicPage.kt:459-463 有 streamOutput 开关;GenerationHandler.kt:255 `stream = assistant.streamOutput` — false 时走 ChatCompletionsAPI.generateText(非 SSE 单次补全)。鸿蒙 generateText(D-015)已备但无路由 → streamOutput=false 无效果。
- **决定**：
  - **ChatStreamProvider 新增可选 generateText**(Port 可选;Android provider 接口双实现恒在,缺失时回退流式——登记为实现选择,非偏差:语义降级方向安全)。
  - **chat_turn/runRegenerateAt 双路径路由(TDD 6 测试,stream_output.test.ts)**:assistant.streamOutput=false 且 generateText 存在 → 单次补全,chunk 以 message 形式进同一 accumulator(replaceActive 语义,stream_accumulator.ts:264-293 既有);abort/finalize/persist 语义与流式完全一致(复用同一 try/catch 分叉);signal 透传 generateText opts(StreamOpts 复用)。
  - **asChatStreamProvider 透传 generateText** → OpenAIChatApi.generateText(非流式请求 + 单 chunk 解析,D-015 既有)。
  - **编辑器新增流式输出 Switch**(Android 同语义:默认 true;关闭则整段返回)。
- **可逆性**:generateText 可选,streamOutput 默认 true → 不传/默认时行为与 D-039 完全一致。

## D-041（L0，2026-07-28）regenerate 流式预览落地：D-024 MVP 裁剪项清零

- **背景**：D-024 把 regenerate 流式预览裁为 MVP(spinner 代替,PARITY_DEBT 登记 P1);runRegenerateAt 的 onUpdate 端口(D-030)已备,仅缺 entry 接线。
- **决定**：
  - **渲染位置**:onUpdate 快照末条即活动流式消息(assistant 路径 = 占位合并后的流式尾部;user 路径 = 截断后尾部追加,均为末条),渲染在**目标节点行下**(regenNodeId 匹配),完成后 regenTail 清空、buildRows 重排。
  - **visual transforms 与 send 路径一致**:applyVisualTransformersStreamingTail(thinkTag + regex,ctx.assistant = rt.assistant)——原始快照直出会泄漏 <think> 标签,与 send 不一致。
  - 非流式(D-040)regenerate:onUpdate 单次触发,预览即终态,无缝衔接。
  - PARITY_DEBT regenerate 分组"流式预览"条目标记消除。
- **可逆性**:纯 UI 增项;领域层零改动(复用 D-030 onUpdate 端口)。

## D-042（L0，2026-07-28）会话建议长按直发：Android 双交互对齐

- **背景**：Android 建议 chip 双交互(ChatPage.kt:812-830):点击 = 填入输入框(setMessageText);长按 = 直接发送(editingMessage=null + handleMessageSend)。鸿蒙 D-026 仅落地点击填充。
- **决定**：
  - 建议 chip 加 LongPressGesture → 填入 + send();生成中不响应(见下)。
  - **裁剪登记**:Android 长按直发走 queueMode=FOLLOWUP(生成中排队待发,ChatService.kt:505 clearPendingUserMessages 配套);鸿蒙发送排队机制整体裁 P1 → 生成中长按不响应(sending 拦截),登记进 M3 gap 审计。
  - 长按时若有待发图片会一并发送(与鸿蒙输入模型一致;Android 长按仅发文本 parts——交互组合差异,影响面小,登记)。
- **可逆性**:纯 UI 增项;点击行为不变。

## D-043（L0，2026-07-28）M3 Chat MVP 收尾盘点：source-only 退出判定

- **背景**：D-012..D-042 共 23 个 chat 切片后,source-only 主线功能面接近穷尽,需对照权威计划 §M3 做退出判定。
- **决定**：
  - 产出 docs/harmony-m3-gap-audit.md:M3 六项交付物逐条锚点核对(全部 source-only 达成)/M4 提前完成项/偏差汇总(PD-001..007)/G-D 设备验证清单(4 项,含 D-038 登记项)/P1 剩余清单(10 项,Android 锚点)。
  - MIGRATION_STATE M3 里程碑状态更新为"source-only 完成;设备验证待 G-D(G-C 阻塞)"。
  - 后续 source-only 方向:P1 清单中无设备依赖纯逻辑项(FOLLOWUP 排队/Google+Claude 请求构建/customHeaders-customBodies 编辑)。
- **可逆性**:文档与状态记录,无代码改动。

## D-044（L0，2026-07-28）Claude 请求构建移植：Messages API 全链路(协议档)

- **背景**：M5「多 provider」核心项;Android ClaudeProvider.kt(621 行)语义明确;鸿蒙 ProviderSettingClaude 模型/wire 已备(D-017)但无请求构建 → Claude 变体空壳。
- **决定**：
  - **claude_request.ts(TDD 12 测试锁定)**:buildMessageRequest 忠实移植 — 默认 max_tokens=64000;**temperature 仅 reasoningLevel=off 时携带**(:302-305);top_p 无条件;system 抽出为块数组(cache_control 仅打显式 EPHEMERAL 标记的末个 part,DISABLED 全禁,无标记不打);thinking adaptive+summarized(OFF→disabled,显式档→output_config.effort);tools input_schema + caching 末位 cache_control;assistant 已执行 tool → tool_use + 紧跟 user tool_result(groupPartsByToolBoundary 复用);reasoning part → thinking 块 + metadata 并入(signature);insertMessagesCacheControl(倒数第二条非 tool_result user 末位 content)。
  - **claude_parse.ts(TDD 11 测试锁定)**:parseMessage(text/thinking+signature/redacted_thinking 跳过/tool_use/input_json_delta);parseTokenUsage(input+cache_read+cache_creation 合计 prompt,message.usage 回退);响应体提取;**parseClaudeStreamEvent**(event: 字段分发 — SseAssembler 双运行时设计预期;content_block+delta 合并复用 parseMessage;**顶层 index 注入 tool metadata** — 并行 tool use 唯一关联键,Android 注释逐字;message_stop→done;error→parseErrorDetail 复用 openai 泛型实现)。
  - **claude_chat_api.ts**:createClaudeChatApi(streamText/generateText,复用 OpenAIChatApi 结构签名)+ asClaudeChatStreamProvider(D-040 双路径);x-api-key + anthropic-version:2023-06-01 头;abort signal 透传;**entry 接线未做**(设置页无 Claude 变体编辑 UI — provider 编辑面 P1,登记)。
  - **裁剪登记(PD-008)**:keyRoulette 多 key 轮换 → 单 apiKey;ClaudeImageEncoder Port(mime + 无前缀 base64)由 entry adapter 实现时落地(D-038 EntryImageEncoder 加 detailed 形态即可,当前未接)。
- **可逆性**:纯增模块,零既有路径改动;OpenAI 链路不受影响。

## D-045（L0，2026-07-28）Google 请求构建移植：Gemini generateContent 全链路(协议档)

- **背景**：D-044 Claude 后最后一个 provider 空壳;Android GoogleProvider.kt(949 行)。鸿蒙 ProviderSettingGoogle 模型/wire 已备(D-017)。
- **决定**：
  - **google_request.ts(TDD 12 测试锁定)**:buildCompletionRequestBody 忠实移植 — systemInstruction(text 合并 \n\n,图片输出模型不带);generationConfig(temperature/topP/maxOutputTokens/responseModalities);**thinkingConfig 全分支**(AUTO 仅 includeThoughts;OFF:3 系→thinkingLevel minimal(OAuth 传输不强制)/2.5 pro→不覆盖/其余→budget 0+includeThoughts false;显式档:3 系→thinkingLevel low/medium/high(XHIGH/MAX→high),非 3 系→thinkingBudget);tools functionDeclarations + **parameters 六键递归裁剪**(removeElements 移植);contents(assistant tool→model functionCall+user functionResponse 配对;reasoning part 不进 Google);safetySettings 5 条 OFF 逐字。**ModelDsl tokenize+TokenSequenceMatcher 忠实移植**(GEMINI_3_SERIES 四成员 token 序列,非简化正则)。
  - **google_parse.ts(TDD 8 测试锁定)**:parseMessagePart(text thought→reasoning/functionCall→tool(thoughtSignature metadata,args 缺失→"null")/inlineData→image(草稿图→[Draft Image] reasoning,非 image mime 抛错));grounding url_citation(缺 uri/title 跳过);usage(completion=candidates+thoughts);响应体提取(blockReason/空 candidates/content 缺失+finishReason 三抛错);**流式 tool id 稳定分配器**(google-fc-<uuid8>-<ordinal> + stream index,Android :331-349 — functionCall 无 id,随机 id 会导致 merge 串线);SSE {response} 解包(cloudcode-pa 兼容)。
  - **google_chat_api.ts**:buildUrl 三形态(vertex/service account→**抛错**(P1,非静默));api key:vertex→key query 参数,否则 x-goog-api-key 头;streamGenerateContent?alt=sse;**OAuth 模式命中即抛错**(v1internal 包装 = P1);malformed event 吞掉即抛(Android :407-412 注释语义)。
  - **裁剪登记**:BuiltInTools(googleSearch/urlContext)— 鸿蒙 ChatModel 无 tools 字段(P1);Vertex service account / Code Assist OAuth = P1;keyRoulette 已由 PD-008 覆盖;entry 接线未做(provider 编辑面 P1,与 Claude 同)。
- **可逆性**:纯增模块;三 provider 协议层齐备(openai/claude/google),entry 接线可独立推进。

## D-046（L0，2026-07-28）provider 运行时接线：三变体统一解析与分发(entry 面)

- **背景**：D-044/D-045 完成 Claude/Google 协议层,但 entry 运行时仍 openai-only —— resolveChatProviderChoice 与 ChatModelPickerPage 均过滤 `p.type === 'openai'`,三协议层空转。
- **决定**：
  - **AppContainer.ets**:`ChatProviderChoice.provider` 泛化为 `ProviderSetting` 联合;用户选择(D-023 优先消费持久化选择)与 D-019 回退(首个 hasUsableAuth + 非空 models)语义不变,仅去类型过滤;`resolveChatRuntime` 按 `chosen.type` 分发 —— openai→既有 createOpenAIChatApi + openAIAuthHeaders(adapter 注 auth);claude→createClaudeChatApi(x-api-key/anthropic-version 由 api 层从 setting.apiKey 注入);google→createGoogleChatApi(x-goog-api-key/key query 由 api 层注入)。未落地传输模式(codex_oauth token store/Gemini Code Assist OAuth/Vertex service account)沿用 api 层显式抛错(非静默,D-044/D-045 登记延续)。
  - **EntryImageEncoder.ets**:新增 `encodeImageDetailed` —— FileEncoder.kt:68-102 `encodeBase64(withPrefix=false)` 忠实移植(data: 剥头提取 mime/file:// 读文件 + mime 猜测 + 无前缀 base64)。**http(s) 偏差(登记 PD-009)**:Android 把 URL 原样当 base64 返回(mime 兜底 image/png)—— 对 Claude inline base64 source/Google inlineData 是垃圾数据,属 Android 已知缺陷,宪章禁止照搬,鸿蒙显式抛错;聊天选图链路(picker→data: URL 物化)不产出 http(s) part,不受影响。
  - **ChatModelPickerPage.ets**:去 openai-only 过滤,行内副标题显示「provider 名 · 类型」;点选/持久化/选中态逻辑不变。
  - **provider 设置编辑 UI 仍 openai-only**:google/claude 变体仅在其数据已存在于 KV(如后续编辑面落地或导入)时被运行时消费;编辑 UI = P1(登记于 M3 gap audit)。
  - 三 createXxxChatApi 均返回结构 `OpenAIChatApi`(streamText/generateText),`ChatRuntime.api` 类型无需变更;streamOutput=false 非流式路径(D-040)对三 provider 自动生效。
- **可逆性**:openai 路径行为逐行不变(同构代码搬入 else 分支);choice/picker 仅放宽过滤,既有 openai 选择与回退结果不受影响。

## D-047（L0，2026-07-28）provider 设置编辑 UI：三变体列表 + 逐变体表单

- **背景**：D-046 后运行时/选择器可消费 google/claude 变体,但设置页(D-020 MVP)只能编辑首个 openai provider —— 用户无从创建 google/claude 配置,运行时接线空转。
- **决定**：
  - **ChatProviderSettingsPage.ets 重写**(Android 基准 SettingProviderConfigPage.kt):provider 列表(名 · 类型 · 停用/编辑中标记,点选载入表单)+ 三个新增按钮(+ OpenAI/+ Google/+ Claude,makeProviderSettingXxx 默认值)+ 删除(AlertDialog 确认,删后选首个;用户持久化选择失效由 resolveChatProviderChoice 的 D-019 回退自然兜底)。兼容旧行为:进入默认选中首个 openai(D-020 延续),无则首个。
  - **逐变体表单忠实 Android 字段条件**:openai —— Base URL/API Path(useResponseApi 时隐藏,:545)/useResponseApi 开关;google —— apiKey(vertex+serviceAccount 时隐藏,:456)/Vertex AI 开关/useServiceAccount 开关(vertex 时显示)/!vertex → Base URL,vertex+serviceAccount → email/privateKey/location/projectId 四字段(:590-622);claude —— Base URL/promptCaching 开关(:637-641)。公共:启用/名称/API Key/模型(逗号分隔)。
  - **保存合并语义**:表单未覆盖字段(authMode/brand/balanceOption)从选中快照原样保留,不被默认值覆盖。
  - **裁剪登记**:authMode 选择不呈现(openai 固定 api_key — codex_oauth token store P1;google 固定 api_key — Code Assist OAuth 传输 P1,api 层显式抛错,避免用户配置出必败 provider);brand 编辑/余额区/service account JSON 导入按钮 = P1;编辑中切换选中丢弃未保存修改(MVP 草稿语义)。
- **可逆性**:纯 entry 页面重写;KV 线格式未变(D-017 三变体序列化既有);旧单 openai 数据载入后字段完全等价。

## D-048（L0，2026-07-28）FOLLOWUP 发送排队：队列纯逻辑 + 循环派发 + chips UI

- **背景**：D-042 登记项 —— 生成中用户再发送被 sending 拦截(Android 走 PendingUserMessage 队列,ChatService.sendMessage:687-732 生成中入队/launchPendingMessageLoop:801-867 循环派发)。
- **决定**：
  - **chat/pending_queue.ts(TDD 26 测试锁定,919 总数)**：PendingUserMessage.kt 全量忠实移植 —— MAX_PENDING_USER_MESSAGES=20;mode 三值(wire 保大写 FOLLOWUP/STEER/COLLECT 与 Android 序列化一致);isCollectable(COLLECT+全文本);asFollowup(已 FOLLOWUP 引用原样);previewText(text/图片/视频/音频/文件占位,\n 拼接 trim,maxChars 截断 trimEnd+'...',else 分支 JSON.stringify — Kotlin data class toString 无对应,登记);buildCollectedPendingUserMessage(空抛错/单条 asFollowup/多条合并 — 模板逐字「下面是用户在上一轮运行时连续排队补充的消息，请按顺序处理：」+「\nQueued #n:\n」,id 以 + 连,answer any,createdAtMs min);队列操作纯函数不可变版(ConversationSession.kt:110-232):enqueue(满拒原引用/STEER 插 STEER 前缀后)/dequeueNext/dequeueSteer 前缀/dequeueLeadingCollectable 前缀/cancel(未命中原引用)/move(offset clamp [0,lastIndex],原地 false)/convertSteerToFollowup;preparePendingMessageForDispatch(ChatService.kt:1057-1073:collectable 头合并后续 leading collectable,STEER→asFollowup,否则原样)。
  - **ChatPage.ets 接线**:send() 生成中 → enqueuePendingParts(FOLLOWUP,answer=true;满 20 错误文案逐字「消息队列已满，请先等待或取消一些排队消息。」),清空输入与待发图;派发重构为 sendParts(parts)(单文本 part 退化 string 与既有路径一致);.then/.catch 末尾 drainPendingQueue(出队 → prepare → sendParts;**失败也继续**,Android 循环逐条 catch → addError 后 dequeue 续行);停止按钮路径自然覆盖(abort → catch → drain,Android stopGeneration:2419-2454 同语义 — STEER 降级为 no-op 因鸿蒙队列不产 STEER);建议长按生成中 → 入队(D-042 登记项消除);排队 chips UI(「排队中」+ preview + ✕ 取消,横滑,Android ChatListIndicators pending 区语义);编辑态生成中发送 → 入队并退出编辑态(登记偏差:Android 无此交互组合)。
  - **裁剪登记**:队列持久化(Android PendingMessageStore 进程杀恢复)→ 内存态(P1);STEER 进行中注入(生成中流内插队,依赖 GenerationHandler 注入点)= P1;answer=false(只记录不回答)发送路径 = P1(enqueue 恒 answer=true);工具阻塞跳出(hasPendingOrUnexecutedTools)→ 鸿蒙无工具执行,循环无条件排空;regenerate 完成不 drain(Android 同 — 循环仅 initialize/send/stop 触发)。
- **可逆性**:纯增模块 + send 路径重构;未生成中发送行为逐行不变(同构代码搬入 sendParts);测试 893 → 919。

## D-049（L0，2026-07-28）customHeaders/customBodies：助手级编辑 UI + 运行时注入

- **背景**：M3 gap 审计 P1 项 —— 模型/wire 已备(D-016 assistant.customHeaders/customBodies;D-017 序列化;mergeCustomParams(D-018)已拼接 assistant+model),但无编辑面且运行时未消费 → 只加 UI 会成静默 no-op(宪章禁止),必须编辑+消费同切片。
- **决定**：
  - **运行时消费(AppContainer.resolveChatRuntime)**:customBodies 进 params.customBody(三 provider 请求构建层均 mergeCustomBody,即插即通);customHeaders(assistant+model 拼接,GenerationHandler.kt:453-468 忠实)构建 Record —— blank name 跳过、name/value trim、重名后值覆盖(toHeaders 语义);注入:openai → auth 头之后后写覆盖;claude/google → api 层 headers? 回调(层内 extra 覆盖内建头语义一致)。**custom 覆盖 auth 为有意选择**(与 api 层 extra-headers 覆盖顺序一致,登记)。
  - **助手编辑 UI(ChatAssistantsPage)**:PropertyEditor.kt 忠实 —— headers 行(名/值双输入+删除;+ 添加 Header 追加 CustomHeader("",""),:108-113);bodies 行(键输入+JSON 值 TextArea+删除;+ 添加 Body 追加 CustomBody("",JsonPrimitive("")) 文本态显示 '""',:213-218);**JSON 逐键解析**:解析成功落 value、失败行内报「无效 JSON: <msg take 100>」并保留旧值(:160-176);trim 在保存点归一(Android 逐键 trim 等价收敛);编辑器包 Scroll(行数增长防溢出)。
  - **裁剪/偏差登记**:JSON 解析为 JS 严格模式(Android jsonLenient 允许尾逗号/未加引号等 — 引擎差异,PD-005 同类);代码高亮编辑(HighlightCodeVisualTransformation)无对应;**model 级 customHeaders/customBodies 编辑面 = P1**(数据已被 mergeCustomParams 消费,仅缺 UI);助手编辑其余字段(mcpServers/localTools/modeInjection/lorebook 等)仍保留原值。
- **可逆性**:无 custom 配置的用户行为零变化(空数组 → 空 Record/空 customBody,请求构建逐字节不变);纯 UI + adapter 注入增项。

## D-050（L0，2026-07-28）生成错误恢复(重试档)：GenerationRetry 全文移植 + 循环集成

- **背景**：PD-006 第三档等价优先级最大缺口 —— provider 抛错直接传播(Android 有 runProviderCallWithRetry:指数回退 + 失败分类 + 状态文案)。retry 纯逻辑无设备依赖,可 source-only 移植。
- **决定**：
  - **chat/generation_retry.ts(TDD,11 测试锁定,930 总数)**:GenerationRetry.kt 全文 166 行忠实 —— GenerationRetrySetting(enabled/5 次/1000ms 起步/16000ms 上限/jitter 0.15);12 类 GenerationFailureCategory;**classify 分支顺序即语义**(CONTEXT→QUOTA→SAFETY→AUTH(401/403 不同 reason)→BAD_REQUEST→RATE_LIMIT→TIMEOUT→SERVER→NETWORK→UNKNOWN);errorText cause 链遍历(类名:消息 拼接 lowercase,seen 防循环);hasNetworkCause 四类名;decide(禁用/超次数/不可重试 → false);**delayForAttempt**(shl 指数 → JS <<、cap、jitter trunc 截断、[-jitter,+jitter] 闭区间整数、coerceAtLeast 0 — 种子化测试锁定 850/1150 边界)。JS 映射登记:CancellationException → Error.name==='AbortError';Error.cause 经交叉类型 CausedError(ArkTS SDK lib 未声明 ES2022 cause,运行时存在)。
  - **chat_turn.ts / regenerate.ts 重试循环**(runProviderCallWithRetry:774-820 忠实):每次失败 → decide → 可重试则 **onBeforeRetry 等价**(base/seed 快照全量 onUpdate,失败尝试的部分输出丢弃,:625-628)→ 状态文案(**strings.xml:386 逐字** "Connection interrupted. Retrying in %1$d s (%2$d/%3$d): %4$s",秒 = delayMs/1000 整除 coerceAtLeast 1)→ sleep → 整轮重跑;**每次尝试全新 accumulator**(部分输出不混入成功结果,测试锁定);成功/不可重试抛错前 onRetryStatus(null)(processingStatus 清零语义,:787/:797);调用次数 = 1 + maxRetries(锁定);**首次尝试必跑**(abort 检查在 catch 与 sleep 后 — D-035「provider 抛错时 signal.aborted=true」语义不回归,修复 loop-top 检查吞掉首轮的回归);重试等待中 abort → 不发起新尝试走部分快照终态化。deps 新增 retrySetting/sleep(测试即时注入)/onRetryStatus,默认全开(Android 默认 enabled=true 忠实)。
  - **ChatPage 接线**:sendParts/regenerate 均接 onRetryStatus → @State retryStatus,输入区上方 ACCENT 色展示。
  - **裁剪登记**:vision fallback(forceImageToText 依赖 image→text transformer 链,鸿蒙无 → 做了会是静默 no-op,违宪章)留 PD-006;生成守卫/generative-ui 重试 = P1;retry 设置的设置面编辑 = P1(用默认值);AgentToolDispatcher 的工具重试(同 classifier)随工具循环落地。
- **可逆性**:deps 全可选,未注入时行为 = Android 默认;不可重试错误路径逐字节不变;既有 3 个用可重试错误文本的测试注入即时 sleep 后断言原样通过。

## D-051（L0，2026-07-28）answer=false 发送路径：只记录不生成(长按发送)

- **背景**：D-048 登记项 —— enqueue 恒 answer=true,Android 有 sendMessageWithoutAnswer 路径(ChatVM → sendMessage(answer=false, queueMode);ChatInput.kt:272 长按发送按钮 → `queueMode = loading ? STEER : FOLLOWUP`),鸿蒙缺失。
- **决定**：
  - **chat_turn.ts runAppendUserMessage(TDD,3 测试锁定,933 总数)**:ChatService.kt:847-850 派发分支的独立助手 —— user append + persist,**不触发生成**(answer=false 时 Android 不调用 handleMessageComplete);isEmptyInput 空输入 no-op 引用原样返回(string 经 makeUserMessage 归一,parts 直用)。
  - **ChatPage 接线**:enqueuePendingParts 增 answer 参,**mode 逐字忠实 ChatInput.kt:272** —— answer=false 且生成中 → STEER(插 STEER 前缀位,派发时 preparePendingMessageForDispatch asFollowup 转换),否则 FOLLOWUP;**drainPendingQueue answer=false 分支** → runAppendUserMessage 落库 + UI 刷新 + 递归继续出队(Android 循环语义:append 后 loop 继续 dequeue);**sendWithoutAnswer()**(生成中 → STEER 入队;否则直接 runAppendUserMessage,清空输入与待发图);发送/停止按钮 **LongPressGesture** → sendWithoutAnswer;**停止按钮点击追加 STEER→FOLLOWUP 转换**(Android stopGeneration:2419-2454 语义 — D-048 时因鸿蒙无 STEER 来源降级 no-op,本切片恢复为真实转换)。
  - **偏差登记**:编辑态长按发送 → 取消编辑态按新消息处理(Android 无编辑态+长按组合交互);COLLECT 合并消息 answer=any(既有 D-048 语义,answer=false 消息参与合并时可能被并入 answer=true 派发,与 Android buildCollectedPendingUserMessage 逐行一致)。
- **可逆性**:点击发送路径逐字节不变(answer=true 恒 FOLLOWUP 同 D-048);长按手势为纯增量交互;STEER→FOLLOWUP 转换仅在队列含 STEER 时 changed=true。

## D-052（L0，2026-07-28）发送队列持久化：PendingMessageStore 全文移植 → KV + 进程死亡恢复

- **背景**：D-048 登记项 —— 队列为内存态,进程杀后丢失(Android PendingMessageStore 文件镜像 + 审计 jsonl,进程死亡恢复语义)。
- **决定**：
  - **chat/pending_queue_store.ts(TDD,14 测试锁定,947 总数)**:PendingMessageStore.kt 全文 104 行忠实 —— serialize(kotlinx 线格式 1:1:声明序 id/parts/answer/mode/createdAtMs,parts 复用 D-013 serializePart);parse(ignoreUnknownKeys 容忍未知键;缺省 answer=true/mode=FOLLOWUP/createdAtMs=注入 now,对齐 Kotlin 默认值;非法 mode 抛错);**persist**(:48-60 空删非空写);**load**(:32-40 无键 → [],损坏 → onError 回调 + [](Android Log.w + getOrDefault(emptyList) 非静默语义));**审计**(recordEvent:62-87 buildJsonObject 键序逐字 created_at_ms/conversation_id/event/message_id?/count?/detail? + '\n',可选缺省即省略)。存储复用 KeyValueStore Port(键 `pending-queue/<convId>` / `<convId>.events`)。
  - **ChatPage 接线**:**每次队列变更持久化**(Android ConversationSession 通知点 :128-231 + durable :712/:1046-1053)—— enqueue/cancel/dequeue/停止时 STEER→FOLLOWUP 转换后 persistQueue();**页面打开恢复**(session 创建时 load,:334)→ launchPendingMessageLoopIfNeeded(:588-595 逐字:**未生成且队列非空 → 自动恢复派发循环**,进程死亡后重开会话即续跑);审计事件子集 enqueue/dequeue(detail=mode 小写)/cancel(messageId),失败均 hilog.warn 非静默。
  - **偏差登记**:Android 原子写(tmp+rename,:95-103)→ KV put 单键天然原子;文件 append → KV read-concat-write(单页面无并发写者);审计子集 — clear/move/pending_tool_* 事件随对应 UI/工具循环落地接入(ops 已在域层备妥);revision 防乱序通道(:361-378)省略 — Harmony 端 persist 调用点全在同一线程顺序触发。
- **可逆性**:纯增模块 + 页面接线增点;无持久化数据时行为与 D-048/051 逐字节一致(无键 → 空队列,无自动 drain);KV 键空间独立于 assistants/providers blob。

## D-053（L0，2026-07-28）reasoning 折叠/展开交互：ChatMessageReasoning 显示语义全文移植

- **背景**：M3 gap 审计 P1 项 —— 鸿蒙 reasoning 渲染为整段灰盒全文,无 Android 的三态卡片(Collapsed/Preview/Expanded)+ 流式迁移 + 秒数头部。
- **决定**：
  - **chat/reasoning_display.ts(TDD,20 测试锁定,967 总数)**:ChatMessageReasoning.kt 全文 452 行显示语义忠实 —— 显示上限三值(1600/6000/18000,:69-71);ReasoningCardState 三态+expanded 标志(:77-81);**onExpandedChange**(loading ? (expand→expanded : preview) : (expand→expanded : collapsed),:92-99);**reasoningDisplayLimit 分支序忠实**(:413-424);**toDisplayReasoningText**(超限 → 「… 已省略前 N 字，以保持流式思考界面流畅。\n\n」+ takeLast(limit),文案逐字,:398-406);duration 三分支(finished/loading/else 0,:114-120);**formatThoughtSeconds**(values-zh:261 逐字「思考了 %1$.1f 秒」→ toFixed(1));budgetLabel(null/off→null/auto→'auto'/≤NK tokens,:387-396);**extractThinkingTitle**(MarkdownUtils.kt:35-55:从后往前首个独占加粗行,内文空白即 null 不续找);流式始末迁移纯函数化(:122-149 — start:!expanded&&showThinking→preview;end:sawStreaming→不变/expanded→autoClose?collapsed:expanded)。
  - **ChatPage MessageRow 卡片化**:头部行(🧠 + title/秒数/深度思考 + ▸/▾)点击切换(onReasoningExpandedChange 语义);collapsed 仅头;preview 1600 尾窗 5 行截断;expanded 18000 尾窗全文;2dp 左竖线+14dp 缩进(:244-256);**loading 判定 = streaming prop && finishedAt==null**(:107 messageLoading 语义 — 流式尾/regen 预览传 streaming:true);**默认态迁移**(:88+:125-126+:129-133)— 历史行 collapsed,流式开始 preview(用户未点),流式过保持 preview(sawStream 经 @Watch+aboutToAppear 记录);duration 随 chunk 重渲染刷新(250ms ticker 裁剪登记 — 流式期间 chunk 驱动等效,结束定格)。
  - **裁剪登记**:Compose LaunchedEffect 生命周期/300ms 自动折叠延迟/scrollTo 尾随(流式场景由 sawStream→preview 默认覆盖,非流式场景初态 collapsed 无折叠需求);preview 的 100dp 滚动窗+上下 fade → 静态 5 行 Ellipsis 截断(视觉层,Spike C);budgetLabel 头部后缀(需 assistant 注入 MessageRow)= P1;流式 title 时右侧 duration extra = P1;shimmer/MarkdownBlock 富渲染 = Spike C;showThinkingContent/autoCloseThinking 设置项未接(默认值 true/false 语义已内嵌)。
- **可逆性**:reasoning 渲染由全文直铺改为卡片,信息可及性增强(collapsed 历史行亦可点开);无持久化/协议变更;纯增模块 + 单组件改造。

## D-054（L0，2026-07-28）上下文压缩纯逻辑层：Planner/Estimator/Payload/Models 全文移植

- **背景**：M3 gap 审计 P1 最大项 ContextEngine+compact 的第一刀 —— 鸿蒙上下文组装仅 limitContext 窗口截断(context_assembly.ts:60),Android 有完整的 token 估算 → 压缩规划 → 摘要 payload → 注入替换管线(2355 行子系统)。本切片落地**纯逻辑全文**,引擎执行(compactConversation 调模型生成摘要)与集成(失效/复制/替换 limitContext)归 D-055。
- **决定**：
  - **chat/context_compact.ts(TDD,49 测试锁定,1016 总数)**:
    - **模型**(ConversationContextModels.kt:7-28/:116-127):ConversationCompact/CompactPolicy(默认 enabled/0.70/0.85/keepRecentTurns=12/maxSummaryTokens=2000;ContextCompactionSetting.keepRecentTurns=8 由设置层经 toCompactPolicy 传入,PreferencesStore.kt:236-254)/CompactPlan+sourceMessageCount。
    - **估算**(planner:12-21/:255-294 + estimator:18-31/:104-131):weightedTokenChars(**UTF-16 码元迭代** — Kotlin Char.code 语义,JS for...of 码点迭代会偏离,用 charCodeAt);**双口径忠实** — planner partEstimatedChars(tool 输出恒计入/document fileName 不加权)vs estimator partInputFootprintChars(tool 输出仅 executed/document 加权);image/video/audio 恒 4500;estimateTokens(chars/4 下限 size*4)/estimateContextWindow(null/<=0→128000)。
    - **规划**(planner:23-78/:80-135/:216-253):planCompaction 全分支(disabled/below_threshold/not_enough_history/already_compacted/tool 边界 start++/end--/not_enough_new_history/precompact vs force reason);planForceCompaction(keepRecentTurns 阶梯 [n,n/2..,1] distinct,最深满足 target 方案,reason 恒 force_threshold);estimateAfterCompaction + transitiveCompactIds(含自身闭包)。
    - **注入**(planner:137-163):prepareMessagesWithCompacts — 无效/禁用 → 纯 limitContext;有效 → selectCompactsForInjection 摘要 system 前置 + 覆盖消息剔除 + keepLimit(contextMessageSize>0 否则 max(keepRecentTurns*2,12))。
    - **payload**(CompactSummaryPayload.kt 全文 575 行):cleanRaw/cleanHumanText/cleanMarkdown/ensureTerminalPeriod/coerceTimelineSummary(1200)/looksLikeJsonFragment/locateJsonObject;stringValue/stringList/intValue 窄化(嵌套 array/object 递归);parse(v2 形状判定/legacy handoff 合成模板逐字);timelineSummary 四分支;searchableText;injectionText(模板逐字含 covered ids 条件行);validCompletedCompacts(稳定排序);**selectCompactsForInjection**(无 payload → 全部;最新 payload + 传递覆盖剔除);sentenceCount/isHighQualityPayload(≥80 且 ≥4 句);**normalizeModelOutput**(malformed/no-readable 文案逐字/legacy 字段保留/**quirk 忠实**:parsed 非空时 putLegacyFields 以 RAW source_message_ids 覆盖 distinct 版,:504);**fallbackPayload**(片段提取中英模板逐字/carriedHandoff 门控 covered ids);**remapCoveredCompactIds**(fork 复制用,handoff 引用替换+前言保留)。
    - **fitMessagesToTokenBudget**(:165-189 system 保留+尾部倒序纳入+空回加);buildCompressionInput/summaryLine/takeMiddle(Int 截断)/ToolResultCompactor(nested_tool 递归)。
  - **偏差登记**:normalizeModelOutput parser 参省略;ToolResultCompactor else 分支 JSON.stringify(D-048 同口径);inputFingerprint(Java hashCode+Long 溢出,Compose 缓存键)裁剪;engine/设置面/集成 = D-055。
- **可逆性**:纯增模块,无既有路径改动(chat_turn 仍 limitContext — D-055 集成时切换);49 测试全绿,tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。

## D-055（L0，2026-07-28）上下文压缩引擎执行+集成：ConversationContextEngine 全文移植 + ChatPage 接线

- **背景**：D-054 落地了 compact 纯逻辑层(规划/估算/payload),本切片落地**引擎执行**(compactConversation 调模型生成摘要/失效/复制/prepareContext 管线)并**替换 limitContext 窗口**接入 send/regenerate 双路径 —— M3 gap 审计 P1 最大项 ContextEngine+compact 闭环。
- **决定**：
  - **chat/context_engine.ts(TDD,19 测试锁定,1035 总数)**:ConversationContextEngine.kt:108-806 + PreparedContextEditor.kt(205 行)+ CompressPrompt.kt + CompactSummaryNormalizer.kt 忠实 —
    - **editPreparedContext**(PreparedContextEditor 全文):trim 阶段(工具结果 >16000 字符 → trimmed_tool_result JSON 保留 8000)+ clear 阶段(clearable 工具 13 名 + conversation_/session_ 前缀规则,>2000 → cleared_tool_result;SENSITIVE_SESSION_TOOLS session_read/session_expand 恒不清);editable 判定 = isExecuted && !pending && !multimodal && !failedOrDenied;仅 index < count-keepRecent 可编辑。
    - **compactConversation**(engine:346-605):plan(force ? planForceCompaction : planCompaction)→ skip 记录事件 → buildCompressionInput + previousCompacts 上下文 → prompt 模板(DEFAULT_COMPRESS_PROMPT 逐字,{content}/{target_tokens}/{additional_context}/{locale} 占位符 split/join 替换,StringUtils.kt:37-44)→ streamText 流式 → normalizeCompactModelOutput → **质量重试一次**(非高质量 → COMPACT_RETRY_SUFFIX 逐字拼接重跑)→ 仍不合格 → fallbackPayload → ConversationCompact(level=1,tokenEstimate=estimateTokens([system(injectionText)]))insert → CompactResult;**AbortError 原样 rethrow**;其余异常 → failed + 事件(非静默)。
    - **prepareContext**(engine:112-286 全管线):edit → plan(extraTokenEstimate=overhead)→ **force_threshold:同步 await compactConversation('auto_force'),失败 → 抛 ContextCompactionFailedError**(phase/compactionReason 字段)/ precompact:**launchPrecompact 异步 launch**(deps 可注入,entry 侧 fire-and-forget + hilog)→ reload compacts → prepareMessagesWithCompacts 注入替换 → 仍超 forceRatio → **fitPolicy**(keepRecentTurns/2 coerce 2)compactConversation(force=true,'auto_fit_model_window')二次压缩 → 仍超 → fitMessagesToTokenBudget(max(trunc(window*forceRatio),4000)-overhead coerce 1000) 兜底。
    - **CompactStore Port + memory 实现**(ConversationContextRepository.kt 的域层形态:getCompacts/insertCompact/deleteByConversation/recordEvent?);**invalidateCompacts**(repository:85-88 删除 + 'compact_invalidated' 事件);**copyValidCompactsToConversation**(repository:38-84:eligible 过滤 completed && sourceEndIndex<=targetLast && sourceMessageIds 全在目标 → id 重映射 + remapCoveredCompactIds + insert + 事件);withEffectiveMessages/effectiveContextNextAction/conversationWithMessagesAsNodes(entry ArkTS 禁对象展开,helper 置 HAR 内)。
  - **chat_turn.ts / regenerate.ts 集成**:deps + prepareContextMessages —— 提供时以引擎结果为组装基底且 **contextMessageSize 强制 0**(引擎内部已截断,避免双重截断;次序对齐 GenerationHandler:434-446);未提供时行为与 D-018 逐字节一致。
  - **EntryCompactStore.ets**:CompactStore 的 KV 实现 — 键 `compacts/<convId>`(ConversationCompact[] JSON blob)+ `<convId>.events`(审计 jsonl read-concat-write,D-052 同口径);getCompacts 损坏 → [](非静默路径由引擎事件层承担)。偏差登记:Room 行级 → KV blob(单页面无并发写者);search/expand(repository:97-134)随工具循环落地接入。
  - **AppContainer**:ChatRuntime + **modelContextWindowTokens**(Android Model.contextWindowTokens,provider_settings.ts:33;null → 引擎默认 128000 窗口);getCompactStore() 单例(复用 getChatKvStore)。
  - **ChatPage 接线**:
    - **buildPrepareContext 闭包**(send + regenerate 双路径):policy 默认 keepRecentTurns=8(**PreferencesStore.kt:241 ContextCompactionSetting 默认值**,设置面未做前硬编码);基线消息 → conversationWithMessagesAsNodes 重建节点(message id 保持,含当轮新 user 消息)→ prepareContext;precompact launchPrecompact = fire-and-forget + hilog.warn(非静默)。
    - **四失效钩子**(reason 逐字 ChatService.kt):regenerate 生成前 await 'message_regenerated'(:1097,先于 prepareContext 避免读过期摘要)/ sendEdit 'message_edited'(:2082)/ switchBranch 'message_branch_changed'(:2152)/ confirmDeleteMessage 'message_deleted'(:2171);失效失败 hilog.warn 不阻断交互。
    - **forkAt → copyValidCompactsToConversation**(:2120,新 id + Date.now 注入,失败 hilog 不阻断分叉)。
    - **ContextCompactionFailedError 统一文案**「上下文已满，压缩失败，请配置任务模型」(send/regenerate catch 映射)。
  - **偏差登记**:Android invalidateFromMessage 按消息定位失效 → 本移植为整会话失效(repository:85-88 deleteByConversation 语义 — Android 失效面在四钩子场景均覆盖全量 compact,等价);compress 任务模型(独立模型配置)未接 — 摘要生成复用聊天模型(provider 同 deps);compact 设置面/ContextUsageIndicator 环 = P1(estimateConversationInputTokens 已备妥);entry 侧 ArkTS 禁对象展开/unknown → conversationWithMessagesAsNodes helper + JSON.parse as T 模式。
- **可逆性**:prepareContextMessages 为可选 deps,未提供时 send/regenerate 与 D-050 行为逐字节一致;policy.enabled=false 时 prepareContext 退化为纯 limitContext(engine:624-636);KV 键空间独立;1035 测试全绿(deepread 603 + chat 432),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。

## D-056（L0，2026-07-28）工具执行核心：Tool 模型 + 策略 + 权限解析 + 调度器全文移植

- **背景**：M3 gap 审计 P1 项「工具执行+审批循环」的第一刀 —— 鸿蒙侧请求/解析已全通(ChatToolDefinition 进 params、三 provider 序列化、tool_calls 解析进 UIMessagePartTool),缺**执行层**:Tool 定义模型、调用策略(风险/审批)、权限决策链、批量调度执行。chat_turn 循环集成与审批 UI 归 D-057,内置工具归 D-058。
- **决定**：
  - **chat/tool.ts**:ai/core/Tool.kt 全文 37 行 —— AgentTool(name/description/parameters/systemPrompt/needsApproval/allowsAutoApproval/mandatoryApproval/execute;mandatoryApproval 注释语义:绕过普通 auto-approval 与 run 内信任,仅高风险开关可无人值守);InputSchemaObj(serialName "object" → type:'object');toolParametersToJson(required null 省略)/toChatToolDefinition(接既有请求管线)。
  - **chat/tool_policy.ts(TDD,29 测试)**:ToolRegistry.kt 策略全文(:167-707)—— ToolInvocationPolicy 16 字段/ToolRisk 三档;**invocationPolicy 全派生链**(mutatesState 名称启发 hasToken 逐字/riskProfile 显式表/alwaysAsk(sms_send/contacts_write/call_phone direct_call)/fail-closed 类目/concurrencySafe/sensitiveRead/foregroundPackageRequirement/speculativeBlockReason 判定序/outputBudgetChars 覆盖表+组合常量 200000*8*5+512000 与 264000);**逐名覆盖表全量**(http_request method/私网降级、memory_tool op 系、发现类白名单、agent_prompt_config、mcp_call_tool、wm_* 九名、model_council_start 外部 CLI 席位);category 全表;isPrivateNetworkTarget(localhost 后缀/IPv4 全私段含 100.64/169.254/IPv6 本地);enforceOutputBudget + truncatedEnvelope(键序逐字,tailChars=max-512 coerce 1024);withDisplayTitleHint(subagent_start 豁免);EXTERNAL_CLI_COUNCIL_RUNNER_TYPES 七值。**偏差**:java.net.URI.host → 手写 parseUrlHost(ArkTS 无全局 URL;scheme://authority → 去 userinfo/path,IPv6 保留方括号对齐 removePrefix/Suffix)。
  - **chat/tool_permission.ts(TDD,17 测试)**:PermissionDecisionResolver.kt 全文 237 行 —— resolve **优先级链逐字**(tool_lookup DENY → approval_state → ask_user hitl → settings_unattended → always_ask → mandatory_approval → subagent(history grant/subagent 门/high_risk_subagent)→ policy → risk → run_trust → settings_high_risk → settings → ui);PermissionDecisionTrace.toJson 键序逐字(policy 可选字段缺省即省略);approvalStateSimpleName(Kotlin simpleName 五态映射);hasSessionGrant(grant_id 非空白);requiresSubAgentApproval 六类目;HISTORY_READ_TOOLS_AUTO_APPROVED_FOR_SUBAGENT;ToolInvocationContext 四值(ToolInvocationContext.kt 全文);shouldPauseForApproval。
  - **chat/tool_dispatcher.ts(TDD,20 测试)**:AgentToolDispatcher.kt 全文 372 行 + ToolInvocationHooks.kt 全文 118 行 + ToolFailure.kt 全文 33 行 —— **executeBatch**(prefetch 复用 name+input+output 非空/全并行资格 Promise.all 否则顺序/按原 toolCallId 重排);**execute**(permission_trace 恒注入 metadata;Denied→denied JSON(reason 空白回退 'No reason provided')/Answered→答案文本/Pending→null/resolver DENY→failed 不可恢复 JSON);**executeWithHooks**(blank→'{}' 解析/display_title 剥离/before 短路/after 改写/onError 归一/hook 异常吞掉 AbortError 除外);**executeResolvedToolWithRetry**(canRetrySafely 四门:enabled+Auto+只读策略;decideGenerationRetry 复用 D-050);canRunInParallel 五门;withPermissionTrace/withHookMetadata 合并语义;**hooks 三件套**(ToolTraceHook log 注入/ToolArgumentValidationHook 三校验文案逐字/ToolFailureNormalizeHook);ToolFailure(sanitized 首非空行 360 截断去栈帧压空白;**偏差**:VirtualMachineError/ThreadDeath 无 JS 对应物 → 恒 recoverable=true)。
  - **chat/agent_loop_budget.ts(TDD,6 测试)**:AgentLoopBudgetPrompt.kt 全文 53 行 —— SMALL_LOOP_MAX_STEPS=4 双阈值族(stepIndex>0 门);build 三文案逐字;shouldHideTools。
  - **范围登记**:ToolRegistry 注册/去重/包装层(from/tools()/ToolMetadata)随内置工具落地(D-058);SpeculativeToolRunner(146 行)与生成循环集成(maxSteps/budget 提示注入/结果回写)归 D-057;SubAgent/Cron/ModelCouncil 上下文已建模但调用方未接。
- **可逆性**:纯增模块 5 个 + index 导出,零既有路径改动;无工具注入时 chat_turn 行为逐字节不变;1115 测试全绿(deepread 603 + chat 512),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。

## D-057（L0，2026-07-28）chat_turn 工具循环集成 + 审批闭环 + 分支再生工具化

- **背景**：D-056 落地了工具执行核心(调度/权限/策略/预算),本切片把它接进**生成主回路** —— GenerationHandler.kt:161-391 agentic 循环全文移植 + ChatService 审批/idle 阻塞/续跑语义 + ChatPage 审批 UI,并以首个真实内置工具 get_time_info(TimeTool.kt)打通端到端(非占位,章程反空实现条款)。
- **决定**：
  - **chat/tool_loop.ts(TDD,17 测试)**:GenerationHandler 主循环忠实 —
    - **runAgenticToolLoop**(:161-391):pendingTools = 末条消息 canResumeExecution 工具(:164-166)→ 有则跳生成直接执行(resume 路径 :342-346);无则每步 prepareContext(对齐 generateInternal 内部位置 :434-446)+ budget prompt 经 **extraSystemBlocks dynamic system 块**注入(buildSystemPromptParts:726-736 现存子集,context_assembly 扩展,metadata {system_prompt_block:'dynamic'} 逐字)→ generateAssistantOnce(chat_turn.ts 提取 :166-275,单跑行为逐字节不变)→ 无工具调用 break(:281-285)→ ASK 决策置 Pending + permission_trace metadata + emit + break 等用户(:288-339)→ executeBatch 结果**写回同一 assistant 消息**(:366-373,不发 TOOL 角色消息)→ steer 消息步间消费(:386-390)。maxSteps coerceIn(16,512) 默认 256(ChatService:1349-1352 + PreferencesStore.kt:268-270)。
    - **关键语义修正(循环调试发现)**:Android 多步循环**共用同一条 assistant 消息**(ai/ui/Message.kt:537-541 注释逐字「多轮 tool 循环共用同一条 assistant 消息」)— accumulator 以末条 base 消息播种,同 role delta 就地合并;第二步文本/工具调用并入带工具结果的消息而非新开消息。初版实现按「追加新消息」理解丢了合并内容,修正为 rewriteLastMessage 节点写回(finalMessages 末元素引用不等 → 写回;slice 新增 → 追加),测试断言同步对齐该语义。
    - **runChatTurnWithTools**(空工具集 → 退化 runChatTurn 逐字节一致;user 消息先入列再循环,D-051 同序;空输入引用原样返回);**runToolLoopContinuation**(审批后续跑,不追加 user 消息);**runRegenerateAtWithTools**(ChatService.regenerateAtMessage:1078-1118)— user 节点截断先持久化后直跑循环;assistant 节点追加**空 assistant 占位节点**作流式尾(runRegenerateAt:124-129 占位同构,否则新流被 accumulator 并入基底末条 assistant),产出合入占位节点后**并入目标 alternatives**(分支追加不覆盖,selectIndex 指新分支),占位无产出(abort/无 chunk)→ 目标不变(!sawChunk 同语义);空 parts 消息不进 internalMessages(占位过滤,hook 输入同滤)。regenerate.ts 提取 **prepareRegenerateSeed**(node 查找/截断/持久化)供双路径共享,runRegenerateAt 重构复用(30 测试锁行为)。
  - **chat/tool_approval.ts(TDD,17 测试)**:ChatService.kt:869-975 + 1122-1190 忠实 — applyToolApprovalToConversation(全节点全消息改写,answer>approved>denied 三态 :1136-1140);conversationHasPendingTools 续跑门(:1166-1172);**resolveIdleToolBlocker**(:869-943:「继续」词表 15 词 + startsWith 继续/可以继续,CJK/ASCII 标点剥离,仅纯文本消息;ask_user pending + 非空文本 → Answered(4000 截断);否则 cancelToolForNewUserMessage 逐字 cancelled JSON + Denied reason;事件 pending_tool_resume/cancel + count + 逗号工具名)。
  - **chat/builtin_time_tool.ts(TDD,3 测试)**:TimeTool.kt 全文 45 行 — payload 键序逐字(year..timestamp_ms 12 键),ISO weekday Monday=1,utcOffsetId('Z'|±HH:mm);nowProvider 可注入。
  - **ChatPage 接线**:
    - **MessageRow 工具卡片 MVP**:名称 + 状态文案(待审批/已批准/已拒绝/已应答/已执行)+ pending → **批准/拒绝按钮** + 已执行首条 output 3 行预览(完整卡片视觉 = P1 登记)。
    - **handleToolApproval**(ChatService:1122-1188):改写 → 持久化 → conversationHasPendingTools 门 → **resumeToolLoop** 续跑(流式预览复用 D-041 regenTail 机制 — 写回末条消息,末条 assistant 行下渲染)。
    - **sendParts 派发前 idle blocker**(:757/:838 位置):纯文本判定 + 文本 join 应答;changed → 审计(pending_tool_resume/cancel,detail 逗号工具名)+ 持久化;shouldResume → 消息不入列直接 runToolLoopContinuation(「继续」语义,Android 同);否则以更新后会话走 runChatTurnWithTools。
    - **drainPendingQueue 阻塞门**(:860 break):conversationHasPendingOrUnexecutedTools → 停止派发,审批/续跑解阻后再 drain。
    - **regenerate/sendEdit 全量走 runRegenerateAtWithTools**(工具循环覆盖重生成路径)。
    - **buildToolLoop**:tools=[createTimeTool()];makeProviderForStep 按步内工具定义逐字段重建 params(arkts-no-spread,不用 rt.params 展开;temperature/topP/maxTokens/reasoningLevel/customBody 透传);**consumeSteerMessages** = pendingQueue STEER 出队 → user 消息注入(session.dequeueSteerPendingUserMessages 语义,出队 durable persist + dequeue/steer 审计)。**autoApprove 默认 false**(autoApproveAllToolCalls/autoApproveHighRiskToolCalls/conversation.autoApproveToolCalls 设置面 = P1,登记 — 鸿蒙 Assistant/Conversation 模型尚无该字段)。
  - **偏差登记**:ToolExposureState 懒暴露/SpeculativeToolRunner = P1(D-056 既登,prefetchedTools 恒空);每步 finishedAt 戳(:275-278)未加(与单跑路径同,PARITY_DEBT);assistant 分支再生中途持久化形态 = 基底+占位节点(追加形态),完成改写为分支形态(Android 窗口化 merge 依附分页架构,PARITY_DEBT 既登);entry tsc 未 typed object literal → ChatTurnDeps 显式标注(arkts-no-untyped-obj-literals)。
- **可逆性**:空工具集全路径退化(runChatTurn/runRegenerateAt/原样返回)与 D-055 行为逐字节一致;审批 UI 无回调默认 no-op;1152 测试全绿(deepread 603 + chat 549),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。

## D-058（L0，2026-07-28）内置工具首批 + ToolRegistry 注册/包装层

- **背景**：D-056 登记「ToolRegistry 注册/去重/包装层随内置工具落地」。本切片把注册层(D-056 策略层之上的 from/tools()/ToolMetadata 面)与**首批内置工具**(ChatService.createRunTools:2231-2330 装配中不依赖外部子系统的 9 件:ask_user + conversation_* 4 + session_* 4)落地,并接入 ChatPage 工具循环。
- **决定**：
  - **chat/tool_registry.ts(TDD,12 测试)**:ToolRegistry.kt:31-161 忠实 —— ToolMetadata 9 字段(name/category/mutates/sensitiveRead/needsApproval/autoApprovable/outputBudgetChars/risk/mandatoryApproval);**toMetadata 派生逐字**(:99-124:needsApproval = mandatoryApproval || tool.needsApproval || mutates || risk==high;autoApprovable = !mandatory && allowsAutoApproval && risk!=high && !fail-closed);from 去重 require 文案逐字 'Duplicate tool names registered: <sorted>';tools() 返回包装副本(flag 覆盖 + display_title 注入(subagent_start 豁免)+ execute 经 enforceOutputBudget);metadataFor/evaluateInvocation(接 D-056 toolInvocationPolicy)。
  - **chat/builtin_ask_user_tool.ts(TDD,2 测试)**:AskUserTool.kt —— name 'ask_user',needsApproval=true,描述逐字;questions 数组 schema(items required ['id','question'],selection_type enum text/single/multi);execute = Promise.reject('ask_user tool should be handled by HITL flow')(Android 同 — 实际应答走 idle blocker/D-057 审批链)。
  - **chat/session_grant_store.ts(TDD,6 测试)**:SessionAccessGrantStore.kt 全文 70 行 + SessionHistoryModels.kt:6-27 —— SessionAccessGrant 10 字段(queryScope='selected_sessions'/assignedSubagentRunId 可空/usedChars=0 默认);create(sessionIds 去重+trim+MAX_SESSIONS=24 require 逐字);validate 4 拒绝文案逐字(expired/unknown grant/session out of scope/char budget exhausted);recordUse 超上限截断;GRANT_DEFAULT_TTL_MS=30min/GRANT_MAX_GRANT_CHARS=120_000/GRANT_MAX_SESSIONS=24;now/idGen 可注入。**范围登记**:签发方 history subagent 未移植,store 仅承载 validate/recordUse(D-056 权限链 hasSessionGrant 已就位)。
  - **chat/context_engine.ts D-058 追加(随会话工具测试锁定)**:ConversationContextRepository.kt:85-170 + ConversationContextEngine.kt:620-666 —— ContextSearchResult{source/id/preview/nodeIndex};**previewAround**(空/miss → slice(0,240),命中 ±120 窗口);**searchConversationContext**(compact searchableText asciiLower 子串 + 消息 toText ignoreCase 双源扫描,remaining-quota 语义,limit coerce 1..20);**expandConversationContext**(compact id → sourceMessageIds 过滤;否则索引 ± radius coerce 0..8);**conversationContextStatus**(planCompaction + estimateConversationInputTokens,键序逐字 enabled/notify_only/estimated_tokens/raw_tokens/context_window_tokens/pressure_ratio/raw_pressure_ratio/summary_count/latest_status/compact_lifecycle_status='idle'(偏差:生命周期 StateFlow 未移植)/next_action/raw_next_action)。
  - **chat/builtin_conversation_tools.ts(TDD,7 测试)**:ConversationContextTools.kt 全文 166 行 —— conversation_context_status(空 schema)/conversation_search(query 必填,limit 默认 8 coerce 1..20,结果项键序 source/id/node_index?/preview)/conversation_expand(source_id 必填,radius 默认 2,文本 slice(0,12000))/conversation_compact(force=true,reason 'manual_compact_tool',policy enabled:true 展开,payload status/summary_id?/source_message_count/estimated_tokens_before/estimated_tokens_after/error?,?? 0 默认)。
  - **chat/builtin_session_tools.ts(TDD,12 测试)**:ConversationHistoryTools.kt 全文 391 行 —— prop builders 描述逐字;inputScope('all' else 'current_assistant');errorPayload{status:'failed',code,error};localIsoDateTime(系统时区,.SSS 仅 ms≠0);formatMessage 逐字([tool:name input=slice(0,800) output_tail=slice(-1200)]/executed=bool;[image]/[video]/[audio]/[document:fileName]/[reasoning:slice(0,300)]);buildTranscript 逐字(+2 检/'\n...[truncated]...'/trim().slice(0,maxChars));session_list/session_search/session_read/session_expand 四件。**session_read truncated 长度启发忠实**(transcript.length >= maxChars — 标记存在但长度不足时 truncated=false,测试双态锁定)。
  - **Entry 接线**:AppContainer **getSessionGrantStore()** 单例(签发方未移植,登记);ChatPage **buildToolLoop(rt, conv)** — 原始工具 [createTimeTool, createAskUserTool, ...createConversationContextTools(conversationProvider=() => this.conversation ?? conv(Android getConversationFlow().value 语义), compactStore=getCompactStore(), policy=makeCompactPolicy({keepRecentTurns:8})(与 buildPrepareContext 同口径), modelContextWindowTokens=rt.modelContextWindowTokens, compactEngineDeps={provider 复用聊天模型, store}), ...createConversationHistoryTools(repository=getChatRepository(), currentConversationProvider 同上, grantStore=getSessionGrantStore())] 经 createToolRegistry 包装;三调用点(sendParts×3/resumeToolLoop/regenerate)传 conv/blocker.conversation。
  - **偏差登记(沿用既有 + 新增)**:session_search FTS → 全量子串扫描(命中序 = summary 列表序,FTS 索引 = P1);countConversationNodes → getById 整载计数;node 分页 → 整载切分;ToolProfileFilter(assistant.toolProfile 字段未在鸿蒙模型)/scopedToConversation activityStore/tools_list/tool_policy_explain 自省工具 = P1 登记;compact 生命周期状态恒 'idle';ask_user 问题渲染 UI(ChatMessageAskUserStep)= P1(当前应答走纯文本 idle blocker,功能闭环)。
- **可逆性**:全部为新模块 + buildToolLoop 内部扩展;registry 包装不改变 D-057 循环语义(tools() 输出形态与裸数组等价,flag 恒 false 时 schema 仅多 display_title 提示 — Android 同);1191 测试全绿(deepread 603 + chat 588),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。

## D-059（L0，2026-07-28）conversation_queue_* 工具对

- **背景**：D-058 落地首批内置工具后,createRunTools(ChatService.kt:2231-2330)装配面中**无未移植子系统依赖**的下一组 = conversation_queue_status/cancel(:2457-2544)—— 数据源 pendingUserMessages 队列已全量移植(D-048/D-051/D-052)。其余组(search/skill/mcp/memory/agent_task/subagent/model_council)均依赖未移植管理器,维持 P1 登记。
- **决定**：
  - **chat/builtin_queue_tools.ts(TDD,10 测试)**:ChatService.kt:2457-2544 忠实 ——
    - **conversation_queue_status**(:2458-2489):空 object schema;payload 键序 status/count/messages,条目键序 index/id/mode(mode.name.lowercase() → toLowerCase())/answer/created_at_ms/preview(pendingPreviewText 默认 180,PendingUserMessage.kt:53 同默认);只读。
    - **conversation_queue_cancel**(:2490-2539):needsApproval=true;schema message_id/clear_all 描述逐字;clear_all = toBooleanStrictOrNull()==true(字符串 'true' 不算,测试锁定);require 文案逐字 'message_id is required unless clear_all=true';changed = clearAll ? 队列非空 : id 命中;**cancel/clear 无条件派发**(Android service 未命中 no-op,工具层仍调用);payload status(cancelled/not_found)/remaining(操作后重读队列长度)。
    - **调用面 deps**:pendingProvider/cancelOne/clearAll 注入(Android 闭包直读写 session;鸿蒙由 ChatPage 承载持久化+审计)。
  - **ChatPage 接线**:buildToolLoop 注册表追加 createConversationQueueTools —— pendingProvider = 页面 pendingQueue 快照;cancelOne 复用既有 cancelPendingById(changed 才 persist+审计 'cancel',ChatService:498-503 同语义);clearAll 新增页面路径(count>0 → 清空 + syncPendingRows + persistQueue + 审计 'clear' detail=count=N,:505-511 同语义)。
  - **范围登记**:findToolName/hasPendingOrUnexecutedTools/toolOutputPreview(:2547-2570)为 ChatService 私有助手,非本工具组范围(hasPendingOrUnexecutedTools 等价物 conversationHasPendingOrUnexecutedTools 已于 D-057 存在)。
- **可逆性**:新模块 + buildToolLoop 追加,零既有路径改动;1201 测试全绿(deepread 603 + chat 598),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。

## D-060（L0，2026-07-28）自省/发现工具组：tools_list + tool_policy_explain + tool_search + isResidentTool

- **背景**：D-058 登记「tools_list/tool_policy_explain 自省工具 = P1」—— 唯一阻塞是 ToolRegistry 注册层未就位,D-058 落地后解除。createRunTools 收尾三件套(ChatService.kt:2326-2329:registry.tools() + createToolSearchTool + registryIntrospectionTools)全量移植,run 工具目录装配面闭合(除依赖未移植子系统的组)。
- **决定**：
  - **chat/builtin_introspection_tools.ts(TDD,19 测试)**:
    - **isResidentTool**(ToolSearch.kt:313-321 判定序逐字 + DISCOVERY_UTILITY/RESIDENT_EXACT/RESIDENT_CONTEXT/RESIDENT_MODEL_COUNCIL 四集合 :323-370 逐字;agent_task_ 白名单仅 list/read,context 类目门)。
    - **tools_list**(ToolsListTool.kt 全文):四 schema props 描述逐字;过滤(category contentOrNull 精确等值 / query ignoreCase 名称+描述子串);顶层键序 enabled_count/include_disabled_supported=false/catalog_mode='debug'/callability_note/next_action 逐字 + include_disabled→note 逐字;条目键序 16 键(name..required_permissions),invoke policy 条件键(parallel_group/requires_foreground_app_package/speculative_block_reason 仅非空输出),resident=isResidentTool,description take(240)。
    - **tool_policy_explain**(ToolPolicyExplainTool.kt 全文):tool_name 必填;input JSON 字符串 runCatching 解析(非法 → null 评估);payload status/tool_name + policy 13 键条件输出,risk 小写。
    - **tool_search + ToolSearchIndex**(ToolSearch.kt:30-268):searchPayload 键序逐字(status/query/[category]/limit/total_tools/resident_tools/matches_count/expanded_tools/callability_note/trace{mode(>40 lazy else bypass)/query/hit_tools/expanded_tools/estimated_*4 键,savings coerceAtLeast 0}/tools/[无命中:category_candidates top16+debug_hint | 命中:next_step] 文案逐字);**scoreTool 评分表逐字**(category+25/exact name+240/token==name+180/name==token 120|startsWith 80|contains 55|category==35|contains 24|desc 16;别名 query==100|contains 55|反含+len≥2 32|token==80|token 含+len≥2 24;split('_') token+30;只读常规+2);**searchAliases 中文别名表逐字**(screen_/wm_/feishu_docs_/subagent_/model_council_/file_/terminal_/mcp_ 八组);排序 = 分降序+名升序;limit coerce 1..20 默认 5;systemPrompt trimIndent 六行模板逐字(类目计数 desc+key 排序拼接、resident 计数动态)。
    - **Kotlin data class toString 形态忠实**:schema 字段/schemaFootprintChars = 'Obj(properties=<json>, required=[a, b])'(InputSchema.Obj 无 toString 覆盖;JsonObject 段 JSON、List 段原始 join、null 段 'null')—— 非 JSON,复刻。
    - **createRegistryIntrospectionTools**(LocalTools.kt:86-92 对)。
  - **偏差登记**:AgentPermissionBroker 未移植 → tools_list capabilities 恒空(required_permissions=[]、risk 回落 metadata.risk.name 大写 — 与 Android 零 capability 逐字节一致);MainAgentToolProfile/ToolProfileFilter 未移植 → profile 恒 null(trace 无 profile/profile_filtered 键);ToolExposureState 懒暴露机制(toolsForStep/exposeToolNames/observeExecutedTools/expandedToolNames)= P1 既登,isResidentTool 纯函数先行。
  - **ChatPage 接线**:buildToolLoop tools = [...registry.tools(), createToolSearchTool(registry), ...createRegistryIntrospectionTools(registry)](createRunTools:2326-2329 顺序逐字;自省三件不经 registry 包装,与 Android 同)。
  - **调试记录**:三处测试期望初版误判,逐一对 Android 源核实后改测试不改实现 —— ① registry.tools() 包装注入 display_title(withDisplayTitleHint,ToolRegistry.kt:66-75)→ schema 含 display_title;② ask_user 只读常规 → policy.parallel_group='utility'(:390);③ needsApproval=true → speculative_block_reason 出现(approval 阻塞)。
- **可逆性**:新模块 + buildToolLoop 尾部追加;1220 测试全绿(deepread 603 + chat 617),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。

## D-061（L0，2026-07-28）本地工具三件：run_plan_update + clipboard_tool + deep_read_open

- **背景**：createRunTools 装配面余量扫描 —— LocalTools.getTools 中**无未移植子系统依赖**的三件:runPlanUpdateTool(零依赖)、clipboardTool(仅需剪贴板 Port)、deepReadOpenTool(仅需事件总线 + SHA-256,深读页路由已存在)。其余(javascript/tts/webview/workspace/terminal/screen/system/icloud/feishu/webmount/image gen/permissions_status/agent_prompt_config/agent_task_*)均依赖未移植管理器或设备子系统,维持 P1。
- **决定**：
  - **chat/builtin_local_tools.ts(TDD,15 测试)**:
    - **run_plan_update**(RunPlanUpdateTool.kt 全文 52 行):payload 键序 status/current_step_index/steps/note 逐字;**原始 JsonElement 直通**(put(key, obj[key] ?: default) — 非字符串 status 也回显);schema required [steps,status] 描述逐字。
    - **clipboard_tool**(ClipboardTool.kt 全文):描述 trimIndent+换行压平逐字;schema action enum[read,write]+required[action];execute — action/text 缺失与未知 action 三处 error 文案逐字('action is required'/'text is required'/'unknown action: X, must be one of [read, write]');read → {text},write → {success:true,text}。**ClipboardPort 注入**(readText/writeText;Android Context.readClipboardText/writeClipboardText)。
    - **deep_read_open**(DeepReadOpenTool.kt 全文 + DeepReadOpenRequest.kt createDeepReadOpenEvent:13-34 + 私有助手):**createDeepReadOpenEvent 纯函数导出**(url trim+空白丢弃 → normalizeDeepReadSourceUrl(scheme http/https require 逐字 + host 非空 require 逐字);title trim+空白+等于 normalizedUrl 三重丢弃 → titleFromDeepReadUrl(slug = 末路径段 substringBeforeLast('.') → '-'/'_'→空格 trim,host 兜底去 www.,'深度阅读' 兜底)→ error 'topic_title or source_url is required' 逐字;title take(120),DEEP_READ_TITLE_MAX_CHARS=120;topicId = 'chat_deep_read_' + sha256Hex(key).take(24),key = url?.trim.lowercase ?: title.trim.lowercase);execute — force_regenerate toBooleanStrictOrNull 语义,hasCollectors → emit+opened / 否则 not_opened(不 emit),payload 键序 status/topic_id/title/[source_url]/cache_ttl_hours=24/force_regenerate/note 双文案逐字。**DeepReadOpenBus Port**(hasCollectors/emit ← AppEventBus)+ **sha256Hex 注入**(MessageDigest 等价)。
  - **entry/platform_impl/EntryLocalToolPorts.ets**:createEntryClipboardPort(pasteboard getSystemPasteboard/hasDataSync/getPrimaryText/createData MIMETYPE_TEXT_PLAIN;读取异常 hilog.warn + '' 回退,非静默)+ **sha256HexUtf8**(CryptoArchitectureKit createMd('SHA256') updateSync/digestSync,UTF-8 TextEncoder;真实 SHA-256 — deepread HAR 的 FNV topicId 为另一处既有登记偏差,不复用)。
  - **ChatPage 接线**:注册表追加三件;deep_read bus 收集器 = router.pushUrl('pages/DeepReadArticlePage',{topicId,title})(Index.startDeepRead:120-127 同路由),页面存活即 hasCollectors=true。
  - **偏差登记**:java.net.URI → 手写 scheme/authority 拆分(D-056 parseUrlHost 同手法;URI.toString() 重建规范化未复刻,合法输入 trim 原文等价 — 调试发现并修正一处:slug 提取须先剥 authority,Java URI.path 语义);**parseDeepReadSlashCommand**(/deepread 斜杠命令 + [ROUTE:deepread] 标签,DeepReadOpenRequest.kt:36-68,属发送路径路由)= 残余登记;**LocalToolOption per-assistant 门控**(鸿蒙 Assistant 模型无 localTools 字段)→ 工具无条件注册(等价全选项开),门控 = P1。
- **可逆性**:新模块 + 注册表追加;1235 测试全绿(deepread 603 + chat 632),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。

## D-062（L0，2026-07-28）ToolExposureState 懒暴露 + 循环接线

- **背景**：D-056 登记「ToolExposureState 懒暴露 = P1」,D-060 落地 isResidentTool/tool_search 后解除阻塞。懒暴露是大目录(>40 工具)下的 token 节流机制:初始仅常驻工具入请求,tool_search 按需暴露。
- **决定**：
  - **chat/tool_exposure.ts(TDD,8 测试)**:ToolSearch.kt:270-311 + :373-380 忠实 —— createToolExposureState(from):toolCount = 非 DISCOVERY_UTILITY 三件计数;**lazyMode = toolCount > 40 && hasSearch**;非懒 → 全名暴露;懒 → runCatching createToolRegistry(失败 → category 恒 null)+ isResidentTool 初集;toolsForStep 非懒全量/懒**原序过滤**(不重排);exposeToolNames 懒守卫 + 仅已知名(toolsByName,associateBy 后赢);observeExecutedTools 仅 tool_search 工具的 expandedToolNames(output Text 段 JSON.parse → expanded_tools 字符串数组,非法 JSON/缺键/非字符串项静默,mapNotNull contentOrNull 语义)。
  - **tool_loop.ts 四接线点**(GenerationHandler.kt 位置逐字):from → 循环前 createToolExposureState(loop.tools)(:159);**exposeToolNames(pendingTools.toolName)** — 每步 pending 计算后、toolsForStep 前(:167,待恢复工具强制可见);stepTools = hideTools ? [] : toolExposure.toolsForStep()(:183 — dispatcher defs 与请求 defs 同为暴露子集,executeBatch:350 toolsInternal 语义);**observeExecutedTools(executedTools)** — executeBatch 非空后、写回前(:364)。
  - **tool_loop.test.ts 集成 2 测试**:45 工具(44 hidden + 真实 tool_search)— 首步 defs 仅 ['tool_search'],执行后第二步 ['hidden_tool_7','tool_search'](原序);≤40 → 两步 defs 恒全量(D-061 前行为不变)。调试记录:末条消息 toText 期望 '\n完成' 初判为 bug — 核实 Message.kt:164-169 toText 非文本段映射 '' 再 '\n' join,[tool,text] → '\n完成' 为 Android 逐字行为,改测试不改实现。
- **可逆性**:目录 ≤40(当前 entry 目录 17 件)时懒模式恒关,行为与 D-061 逐字节一致(集成测试锁定);1245 测试全绿(deepread 603 + chat 642),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。

## D-063（L0，2026-07-28）SpeculativeToolRunner 推测执行 + 流式观察点接线

- **背景**：D-056 登记「SpeculativeToolRunner(146 行)= P1」,工具执行行最后一块核心循环件。推测执行 = 流式期间对只读 eligible 工具提前并发跑,流终模型确认调用(name+input 一致)则复用结果,掩盖只读工具延迟。
- **决定**：
  - **chat/speculative_tool_runner.ts(TDD,10 测试)**:SpeculativeToolRunner.kt 全文忠实 —— observe 过滤链(toolCallId 非空白 && !isExecuted → isLikelyCompleteJsonObject(trim 首尾大括号)→ invocationPolicy(inputText).speculativeEligible → take(maxConcurrentTools 默认 4,PreferencesStore.kt:228));同 id 同 name+input 不重启/变则 cancel 重来;状态机 pending→completed/failed(+result/error);**reusableResults**(未进 final 或不符 → cancel+discarded;job 未完结 → cancel+discarded+跳过;否则 await 完结 job / 回落 state.result);snapshot 按 toolCallId 排序;updateStateIfCurrent(name+input 匹配才更新)。**偏差登记**:CoroutineScope.async/Deferred → Promise+标志位(JS Promise 不可取消:cancel 仅置标志,执行至完结但不覆盖 discarded/cancelled 状态、结果永不复用 — 可观察行为等价);Deferred.isCompleted/isCancelled → settled/cancelled 标志;CancellationException → cancel 处置 cancelled/discarded。调试记录:工具异常经 executeWithHooks catch-all 归一为失败载荷**结果**(非抛出)→ runner 状态 completed 且可复用,初版测试误判 failed,核实 D-056 dispatcher 语义后改测试。
  - **chat_turn.ts onRawFlushSnapshot**(ChatTurnDeps 新增可选回调):raw(未 visual transform)快照于**节流 flush 与流终**两点回调(GenerationHandler.kt:519-526/:540-543);**节流检查移出 onUpdate 门**(Android 不依赖 UI 监听者;onUpdate 定义时行为不变,未定义且未定义 raw 回调时不取快照仅推进节流戳)。
  - **tool_loop.ts 接线**:ToolLoopOptions 新增 speculativeEnabled/speculativeMaxConcurrentTools;**每步**创建 runner(:187-197 — speculativeEnabled && assistant.streamOutput !== false);stepDeps 注入 onRawFlushSnapshot → runner.observe(末条消息工具 × stepDefsMap 暴露 defs,tools.associateBy 语义);executeBatch prefetchedTools = await runner.reusableResults(toolsToProcess)(:356,替换恒空 Map)。runner 为 null 时 deps 原样传递(零包装)。
  - **集成 2 测试(tool_loop)**:enabled + 宏任务工具 → flush 推测 1 次 + 未完结丢弃 + 真实执行 1 次 = 2 次调用、输出取真实执行;默认未传 → 仅 1 次。
  - **entry**:不接线(默认禁用 = Android 默认 enabled=false 逐字;设置面 SettingAgentExtensionsPage.kt:136-227 = P1 登记)。
- **可逆性**:默认禁用,未传 speculativeEnabled 时循环与 D-062 逐字节一致(集成测试锁定);1257 测试全绿(deepread 603 + chat 654),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。

## D-064（L0，2026-07-28）/deepread 斜杠命令解析 + 发送路径拦截

- **背景**：D-061 登记「parseDeepReadSlashCommand(DeepReadOpenRequest.kt:31-68,发送路径路由)= 残余」。深读子系统(entry DeepReadArticlePage + deepread HAR 管线)与 deep_read_open 工具均已就位,斜杠命令是该入口的最后一块。
- **决定**：
  - **chat/builtin_local_tools.ts 追加(TDD,20 测试)**:parseDeepReadSlashCommand(parts, sha256Hex) — DeepReadOpenRequest.kt:31-66 忠实:仅 Text 段 join('\n') trim;前缀 `[ROUTE:deepread]` 或 `/deepread` 大小写不敏感,后者要求余文首字符 isWhitespace(Kotlin Char.isWhitespace 常用集近似:ASCII 空白 + -,NBSP 等异域空白差异登记);body/折叠后 cleaned 空白 → null;**force = 旗标 ∈ lowercased body(子串,折叠前)**;折叠 = DEEP_READ_FORCE_FLAGS 列表序**字面量**全量替换(ignoreCase)— quirk 钉住:'--regen' 先于 '--regenerate' 折叠,后者残留 'erate';URL = /https?:\/\/\S+/i 首命中;标题 = cleaned 去除 URL 字面量(大小写敏感)trim 非空,否则 null → createDeepReadOpenEvent 内 URL 派生;runCatching → 构造抛错吞为 null。导出 DEEP_READ_ROUTE_TAG/DEEP_READ_FORCE_FLAGS。
  - **entry ChatPage 接线**(SendMessageOrchestrator.kt:26-41 语义):send()/sendWithoutAnswer() 组 parts 后、排队/发送前拦截(编辑态除外 — Android 编辑走 handleMessageEdit 不经 orchestrator;D-051 已登记编辑态长按偏差,维持);命中 → router.pushUrl('pages/DeepReadArticlePage') + 清空输入(不入队、不发模型、不改会话);bus.tryEmit 恒真 = 页面存活即有收集者,与 deep_read_open 工具同口径。deep_read_open bus emit 与斜杠路由合并为 openDeepRead 单一路径。
  - **残余登记**:canSendWithoutChatModel 门(ChatPage.kt:532/675/695/823 — 无聊天模型时仅斜杠命令可发送 + '请先选择模型' toast)= 依赖模型缺失态门控面,鸿蒙发送路径尚无模型空值门,整体 = P1。
- **可逆性**:纯追加(解析器 + 发送路径前置守卫),非斜杠输入路径逐字节不变;1277 测试全绿(deepread 603 + chat 674),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。

## D-065（L0，2026-07-28）搜索 SDK 核心(SearchService.kt + KeyRoulette.kt)

- **背景**：工具执行残余「search tools(createSearchTools)」依赖整个 search 模块(17 provider ~2500 行 + Aggregator/Orchestrator/Tools ~1500 行)。按 data/protocol 优先级启动搜索史诗,切五片:D-065 核心类型 → D-066/067 provider 批次 → D-068 聚合/编排 → D-069 工具组+entry。deepread HAR 的简化 SearchProvider 抽象(preview 期为深读管线所建,非 Android search SDK 移植)维持独立,不合并。
- **决定**：
  - **chat/search/search_service.ts(TDD,14 测试)**:SearchService.kt 全文 + KeyRoulette.kt 全文忠实 ——
    - **SearchServiceOptions 17 类 type 判别联合**(SerialName 逐字:bing_local/zhipu/tavily/exa/searxng/linkup/brave/serper/serpapi/metaso/ollama/perplexity/firecrawl/jina/bocha/amber_agent/grok);构造默认逐字(tavily depth 'advanced'/linkup 'standard'/jina 双 URL/bocha summary true/perplexity 双 null/grok model+customUrl+systemPrompt 逐字/searxng 五空串);Uuid → string(newId);**SEARCH_SERVICE_TYPES** 17 显示名 mapOf 插入序('Bing HTML 兜底'/'智谱'/'秘塔'/'博查'…);DEFAULT = bing_local。
    - **序列化 kotlinx 语义**:classDiscriminator 'type' 居首 + 字段声明序 + explicitNulls=false(null 省略)+ ignoreUnknownKeys;fromJson 未知 type 抛错、缺字段回默认、类型不符回默认;17 类 round-trip 测试钉住。
    - **结果模型**(SearchResult/ScrapedResult 及嵌套):toJson 声明序 + null 省略;SearchResultItem images 恒落键(空数组不省略,explicitNulls 仅管 null)。
    - **KeyRoulette**:splitKey([\\s,]+ split → trim → 非空白 → distinct 首次序);default 轮询(注入随机源,空列表 → 原串);**LRU**(24h 过期;本 provider 过滤不在 keyList/已过期条目;优先从未使用,否则最久未使用 minBy 平手取先遇=插入序;写回嵌套 JSON;他 provider 全过期清理;load 失败 → 空缓存;save 失败吞咽 — 平台缓存非业务数据)。
    - **SearchService 接口** + **SearchSdkRuntime**(companion httpClient/keyRoulette + init)。
  - **偏差登记**:泛型 SearchService\<T\> → 联合类型参数(provider 内窄化);suspend Result\<\> → Promise+抛错;@Composable Description() 属 UI 不移植;Call.await 协程桥 → HttpClient Port(@amber/deepread-domain,chat 既有 provider 同 Port)原生 Promise;**Android 默认 OkHttpClient 不可复刻 → 鸿蒙必须 initSearchSdk 注入,未注入访问运行时装抛错**;getService(options) when 分派随 provider 批次落地(D-067)。
- **可逆性**:全新模块,无既有行为变更;1291 测试全绿(deepread 603 + chat 688),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。

## D-066（L0，2026-07-28）免费/内置搜索源(JSON 协议三件 + URL 编解码)

- **背景**：D-065 搜索史诗第二片。17 源分三类:HTML 抓取型(Bing/DuckDuckGo — 依赖 Jsoup DOM+CSS 选择器,需先建 mini-DOM,切至 D-067)、JSON API 内置源(Wikipedia/HackerNews — 普通 object 非 SearchService)、JSON API 服务型(Jina — SearchService 形态含 scrape)。本片落地后两者 + 编解码基座。
- **决定**：
  - **search/url_codec.ts(ArkTS 无全局 TextEncoder/TextDecoder → 手写 UTF-8 编解码,含代理对;截断序列单字节原样登记)**:javaUrlEncodeForm(alphanum+".-*_" 保留/空格 '+'/大写 hex,~ 编码 — Java URLEncoder 表单语义逐字)、javaUrlDecodeForm('+'→空格/%XX UTF-8)、buildQuery(okhttp addQueryParameter 插入序,encodeURIComponent 近似,!'()* 差异登记)。
  - **search/wikipedia_service.ts(全文 74 行,普通函数形态忠实)**:CJK 字符(UTF-16 code unit ∈ 0x4E00..0x9FFF,charCodeAt 等价)→ zh host 否则 en;六参数链(action=query/list=search/srsearch/srlimit coerceIn(1,10)/format=json/utf8=1);UA 'AmberAgent/1.0 search (Android)' 逐字;映射 title/url=base+javaUrlEncodeForm(title 空格→_)/snippet Regex("<[^>]+>") 剥标签/publishedAt=timestamp;非 2xx 'Wikipedia request failed #N' 逐字。
  - **search/hackernews_service.ts(全文 65 行)**:Algolia 三参数(query/tags=story/hitsPerPage coerceIn(1,20));映射 title?:story_title?:跳过/url 缺省 news.ycombinator.com/item?id=objectID 兜底/text 'HN points=X, comments=Y' null→0 逐字/publishedAt=created_at;非 2xx 'Hacker News request failed #N' 逐字。
  - **search/jina_service.ts(全文 199 行,SearchService 形态)**:parameters/scrapingParameters schema 逐字(query/url required);search — POST searchUrl(blank→默认 s.jina.ai),body {"q"},Accept/Content-Type json,**apiKey 非 blank 才加 Bearer**,响应 data take(resultSize) → title/url/description→text;非 2xx 'response failed #N';query 缺失 'query is required'(jsonPrimitive.content 语义:string 原串/number·boolean 字符串化);scrape — POST scrapeUrl(blank→r.jina.ai),body {"url"},**X-Return-Format markdown**,metadata title/description(language 无字段 → null);**Kotlin 笔误 'urls is required' 逐字保留**;非 2xx 'response failed for url X #N'。options 类型不符 → 抛错(Kotlin CCE 语义)。
  - **TDD 15 测试**:编解码 3 + wikipedia 3 + hn 3 + jina 6(schema/请求形态/错误文案全钉)。mock HttpClient 捕获请求逐字段断言。
  - **切片边界登记**:Bing/DuckDuckGo(Jsoup 依赖)→ D-067 mini-DOM(html_dom 子集:tag.class/后代/逗号/selectFirst/parents/nextElementSibling/text/attr)+ 两源。
- **可逆性**:全新模块;1306 测试全绿(deepread 603 + chat 703),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。

## D-067（L0，2026-07-28）mini-DOM(html_dom)+ Bing/DuckDuckGo HTML 抓取源

- **背景**：D-066 登记「Bing/DuckDuckGo 依赖 Jsoup(DOM+CSS 选择器)→ 先建 mini-DOM」。两源为 SearchTools 内建兜底链(Bing HTML 兜底/DuckDuckGo)与 BingLocalOptions(DEFAULT 服务)的承载体,不能用正则伪解析充数(不等价),自建选择器子集引擎。
- **决定**：
  - **search/html_dom.ts(TDD,8 测试)**:容错 HTML 解析(未闭合自动闭合于文档尾/close-tag 弹栈至匹配/void 16 标签/script·style rawtext 不解析/属性单双引号·无引号/实体含数字与 &nbsp;)+ 选择器子集(逗号组/后代空格/子代 >/复合 [tag][#id][.class]*)+ 遍历(select/selectFirst/attr 缺省 ''/hasClass/parents/parent/nextElementSibling)+ text() 递归聚合折叠 trim。**偏差登记**:不做 HTML5 implied-close(<li> 相邻不互闭,与 Jsoup 树形差异);text() 块边界空格规则近似;textChunks 集中存放致文本/子元素交错序失真(折叠空白下不影响标题/摘要提取)。
  - **search/bing_service.ts(全文 133 行,SearchService 形态)**:请求形态逐字(URL q=javaUrlEncodeForm/UA Chrome131/Accept 群/Connection/Referer/Cookie SRCHHPGUSR=ULSR=1);**Accept-Language = runtime.acceptLanguage() 注入**(Locale.getDefault() "${language}-${country},${language}" → initSearchSdk 第三参,缺省 'zh-CN,zh' 登记);风控检测三连(verify you are human/unusual traffic/captcha ignoreCase)→ 'Bing blocked the request with a verification page' 逐字;主解析 li.b_algo(h2 a + .b_caption p,.b_snippet,p 首命中)+ fallback(main h2 a,#b_results h2 a,h2 a → parent.parent text take(500));decodeBingUrl(query 首段 key∈{u,url} → URLDecoder);filter 非空标题+http → distinctBy(url) 保首序 → take(resultSize);空 → 'Search failed: no results found';scrape → 'Scraping is not supported for Bing';**Jsoup timeout 10s → HttpClient Port 默认超时(登记)**。
  - **search/duckduckgo_service.ts(全文 109 行,普通函数形态忠实)**:lite 优先(Android 移动 UA)→ 空则 html 兜底(桌面 UA);Accept/Accept-Language 'zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7'/Referer 逐字;parseLite — a.result-link **filterNot parents any hasClass('result-sponsored')** + uddg 解码 + '//'→'https:'/'/'→域前缀归一 + resultSnippet(上两级行后 3 行内首个 td.result-snippet 非空);parseHtml — .result → a.result__a ?: h2 a + .result__snippet,.result__body Elements.text() 空格 join;双空 → pageText(lite+' '+html lowercase)风控词(bots use duckduckgo/anomaly-modal)→ 'DuckDuckGo requires human verification',否则 'DuckDuckGo returned no parseable organic results' 逐字。
  - **TDD 8 测试**:Bing 5(请求形态逐字段/主解析+跳转解码+去重/风控/fallback+全空/schema+scrape)+ DDG 3(sponsored 过滤+解码+snippet/html 兜底链/双错误文案)。
- **可逆性**:全新模块;1322 测试全绿(deepread 603 + chat 719),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。

## D-068a（L0，2026-07-28）需 key 搜索源批次 A(AmberAgent/Ollama/Serper/SerpAPI/智谱)

- **背景**：D-067 后剩余 15 个需配置源(~2374 行),按体量切三子片。批次 A = 五件小体量(各 110-128 行)。新增 search/json_pick.ts 共享容错取值助手(kotlinx ignoreUnknownKeys 取字段面;等价此前各 provider 局部副本,D-068 起共用)。
- **决定**(全部 SearchService 形态,parameters/scrapingParameters schema 逐字,options 类型不符抛错 = Kotlin CCE 语义;非 2xx/缺参/缺 key 错误文案逐字)：
  - **amberagent_service**:POST api.rikka-ai.com/v1/search,体四键恒 JsonPrimitive 字符串(q/depth/outputType='sourcedAnswer'/**includeImages='false' 字符串**),Bearer 恒加;响应 answer + sources take(resultSize) → name/url/snippet;失败 'response failed #N: BODY'(带响应体);scrape 用 error()(suspend 异步抛出,非 Result.failure)→ 'AmberAgent does not support scraping'。**偏差:Log.i(query) 不移植 — 宪章禁日志落用户查询**。
  - **ollama_service**:POST ollama.com/api/web_search,体 {query, max_results coerceIn(5..10)};映射 results → title/url/content;失败 'Ollama search failed with code N: ' (**okhttp response.message 无 Port 对应 → 尾空串登记**);scrape 'Scraping is not supported for Ollama'。
  - **serper_service**:require key 'Serper API key is required';topic contentOrNull(仅 string)=news → /news 否则 /search;体 {q, num coerceIn(1,20)},X-API-KEY;映射 (news ?: organic ?: []) — **空数组不回退**(Kotlin Elvis 仅 null 触发),title/link/snippet.orEmpty()/date,take(resultSize);失败 'Serper request failed #N'。
  - **serpapi_service**:require key 'SerpAPI key is required';GET search.json query 链(engine=google/q/api_key/num coerceIn(1,20)/**tbm=nws 仅 topic=news**);映射 (news_results ?: organic_results ?: []);失败 'SerpAPI request failed #N'。
  - **zhipu_service**:POST open.bigmodel.cn/api/paas/v4/web_search,体 {search_query, search_engine='search_std', count=resultSize 数字};映射 search_result → title/link/content;三失败路径逐字('Zhipu response failed #N'/'Failed to decode Zhipu response (N chars): MSG'/'Failed to get response body' — **body 空串视为 okhttp null body,登记**)。
  - **TDD 10 测试**:请求形态逐字段/映射/错误文案全覆盖;mock 顺序响应队列。
- **可逆性**:全新模块;1332 测试全绿(deepread 603 + chat 729),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。

## D-068b（L0，2026-07-28）需 key 搜索源批次 B(Brave/秘塔/LinkUp/Perplexity/Exa)

- **背景**：D-068 第二子片。五件含两个带抓取/图片/轮询的复杂面(LinkUp scrape、Perplexity 响应级图片、LinkUp/Exa KeyRoulette)。
- **决定**(schema/错误文案逐字,options 不符抛错):
  - **brave_service**:GET res/v1/web/search?q={javaUrlEncodeForm}&count=N;X-Subscription-Token;映射 web?.results ?: [] → title/url/description?:'';**thumbnail.original 优先 src 兜底(http 前缀才取)** → images 单元素;失败 'Brave search failed with code N: '(message 尾空登记)。
  - **metaso_service**:POST metaso.cn/api/v1/search 体 {q, scope='webpage', size=N 数字, includeSummary false 布尔};webpages → title/link/snippet?:'';三失败路径逐字(含 decode/空 body 同 zhipu 手法)。
  - **linkup_service(含 scrape)**:**apiKey = keyRoulette.next(apiKey, id)**(runtime 注入轮询器);search POST /v1/search 体四键同 AmberAgent 形态;scrape POST /v1/fetch 体 {url, includeRawHtml/renderJs/extractImages 三 false 布尔} → ScrapedResultUrl(url=输入, content=markdown, metadata null);'url is required'/失败带 body 逐字。
  - **perplexity_service**:空 key error() 'Perplexity API key is required';体 {query, max_results=N, **max_tokens/max_tokens_per_page 仅 >0 落键**};**响应级 images:image_url distinct take(5) 挂首个有效结果**(title/url blank 过滤后首条;origin_url 不取);text = snippet ?: text ?: '';失败带 body。
  - **exa_service**:体 {query, numResults=N, **type = params.type content ?: 'auto'**, contents:{text:true}};keyRoulette 选 key;answer = output?.content;items → title/url/text?:'';三失败路径(decode 文案同构)。
  - **TDD 10 测试**:轮询器钉随机源验证选 key;条件落键;图片挂载规则;Elvis 空数组不回退(serper 同型已在批次 A 钉)。
- **可逆性**:全新模块;1342 测试全绿(deepread 603 + chat 739),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。

## D-068c（L0，2026-07-28）需 key 搜索源批次 C(Tavily/博查/SearXNG/Grok/Firecrawl)+ getService 分派

- **背景**：D-068 末子片。五件 + SearchService.kt:46-66 getService when 分派 → 17 类搜索源全部落位。
- **决定**(schema/错误文案逐字,options 不符抛错):
  - **tavily_service(含 scrape)**:topic contentOrNull ?: 'general',校验 ∈ [general,news,finance] 否则 'topic must be one of `general`, `news`, `finance`';体五键 {query, max_results, search_depth=depth.ifEmpty{'advanced'}, topic, include_answer='advanced'};keyRoulette 选 key;**images distinct take(5) 挂首条**(同 Perplexity 响应级规则);scrape POST /extract 体 {urls:[url]} → raw_content 映射,metadata null;'url is required'/'response failed #N' 逐字。
  - **bocha_service**:体三键 {query, summary 布尔透传, count=N};解码失败 'Failed to decode Bocha response (N chars): MSG';**code!=200 → 'Bocha API error: ' + (msg ?: 'Unknown error')**(msg null 路径钉住);items = data?.webPages?.value ?: [],text = summary ?: snippet;HTTP 失败 'Bocha response failed #N';scrape 'Scraping is not supported for Bocha'。
  - **searxng_service**:require url 非 blank('SearXNG URL cannot be empty');base trimEnd('/');URL = base/search?q={javaUrlEncodeForm}&format=json + engines/language 条件键(encodeURIComponent);**Basic auth 仅 username 且 password 均非 blank**(Credentials.basic = 'Basic '+base64(latin1),base64EncodeLatin1 手工实现);**图片解析**:thumbnail/img_src 非 blank → http 直取///→https: 前缀/相对 → URI.resolve 子集(origin+目录) → http 前缀过滤 → distinct → take(2);结果 take(resultSize);失败 'SearXNG request failed with status N';decode 失败文案同 Bocha 手法;**Log.i(query) 省略**(隐私:用户查询不入日志,章程禁止,偏差登记)。
  - **grok_service**:空 key 'Grok API key is required';体 {model, input:[system+user 双消息], tools:[web_search/x_search 双件], store:false};POST customUrl;**output 首个 type=message && role=assistant → content 首个 output_text → answer**;annotations 过滤 type=url_citation && url 非 blank → distinctBy url → take(resultSize) → **title=url 逐字**,text='';失败带 body 'response failed #N: BODY';scrape 逐字;**Log.i(query) 省略**(同上偏差)。
  - **firecrawl_service(含 scrape)**:asStringList(数组→contentOrNull 非 blank;串→逗号切分 trim 非空;否则 null)非空才落 sources/categories 键;体 {query, limit=N, [sources], [categories]};**bug 钉住:search 失败文案为字面量 'response failed #{response.code}'**(Kotlin `"response failed #${'$'}{response.code}"`);payload.data 缺失 'empty response data';web items 先于 news items;news text = "${snippet}\n${date}"(trimIndent);scrape 体 {url, onlyMainContent=contentOrNull?.toBoolean() ?: true, maxAge:172800000, parsers:[], formats:['markdown']};!2xx → 'response failed #N'(正确插值);success false → 'scrape request failed';data.markdown ?: '';'url is required'。
  - **service_registry.getSearchService**:17 类全分派,name 逐字(Bing/Zhipu/Tavily/Exa/SearXNG/LinkUp/Brave/Serper/SerpAPI/Metaso/Ollama/Perplexity/Firecrawl/Jina/Bocha/AmberAgent/Grok);**置于独立模块而非 companion**(避免 search_service ↔ provider 循环依赖,偏差登记)。
  - **TDD 10 测试**:topic 校验/图片挂首条;Bocha msg-null 'Unknown error';SearXNG Basic auth 与相对图片解析;Grok 引用去重过滤;Firecrawl bug 字面量;17 类分派 name 表。
- **可逆性**:全新模块;1353 测试全绿(deepread 603 + chat 750),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。

## D-069（L0，2026-07-28）SearchAggregator + SearchOrchestrator 忠实移植

- **背景**：搜索子系统编排层。SearchAggregator.kt(384 行)多源并行聚合 + SearchOrchestrator.kt(848 行)变体查询/内置源/状态解释/webview 兜底。
- **决定**:
  - **SearchSettings 子集接口**:Android 整 Settings 裁剪为 10 个搜索字段(enableWebSearch/searchCommonOptions/searchServices/searchEnabledServiceIds/五内置开关/webview 兜底开关);完整 Settings 面随 D-070 落地。
  - **偏差登记**:LocalDate.now().toLocalString(true) 为 locale MEDIUM 格式,跨平台不可忠实 → deps.today 注入(默认 ISO 'YYYY-MM-DD');coroutineScope async → Promise.all;Uuid.take(6) → newId().substring(0,6);java.net.URI → 手写容错解析子集(空白字符抛错走回退,同 URISyntaxException 语义)。
  - **aggregator 忠实点**:perServiceSize = floor((max+n-1)/n)+3 coerce 1..20;canonical 键非 blank 否则 titleKey(domain|normalizedTitle);dup +8/长文本胜/图片合并 cap5;排序 score→dup→rank;**图片预算按未预算 distinct 计 emitted**(Android 原版,:100-108);image_instruction 中文逐字;error take(500);选择器 trim 不滤空(aggregator 版)。
  - **orchestrator 忠实点**:topic 五值/depth 三值/allow_webview toBooleanStrictOrNull;MAX_SOURCES 5/变体调用 cap 10(先全源 v0,再逐变体轮发);评分 60-5si-3vi-rank + hit*3 + freshness12(仅 news 且 timeRange!=any 且 publishedAt 非 blank);dup +12;**byKey+byTitle 双图**;canonical 黑名单多 yclid;**toEmit 图片预算修正版**(:124-138);status partial/ok/empty 状态机;webview_fallback 三引擎建议(google/ddg/bing,URLEncoder 形式编码);sanitizeError 正则替换全 occurrence;status()/explain() 全字段;apiKeyConfigured 分类型(bing_local/searxng → null);OrchestratorSource sealed → 判别联合(id/name/kind/builtin/priority/reason 逐字);preferred_sources ?: services 选择器(滤空)。
  - **TDD 18 测试**(aggregator 9 + orchestrator 9):公式钉住(perServiceSize/perCallSize/评分 87/59/variant_count [3,3,2,2] cap10 行为);调试中发现测试误算三处(变体产生额外 dup、topic news 排除 wikipedia、recency 文本条件 :347)均以 Android 源为准修正测试。
- **可逆性**:全新模块;1373 测试全绿(deepread 603 + chat 770),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。

## D-070（L0，2026-07-28）SearchTools 工具组 + SearchPrefs 持久化 + entry 接线 — 搜索子系统收官

- **背景**：搜索 epic 末片。SearchTools.kt(256 行)四工具 + SearchPrefs.kt(92 行)持久化 + ChatService.kt:2232-2234 enableWebSearch 门 + DataSourceModule.kt:406 SDK init。
- **决定**:
  - **search_prefs**:PreferencesKeys.kt:49-58 十键字符串冻结(ADR-0001);DataStore typed → KV 字符串 Port(int/bool 存串,`!= false` 语义保留:仅 'false' 串 → false);decodeJsonOrNull → try/catch 回默认(services [bing_local]/common 10);update next==current 短路 → JSON 串相等(偏差登记);enableWebSearch 属 ChatPrefs 默认 true(设置面 = P1 登记)。
  - **search_tools**:四件注册序(search_web/search_sources_status/search_strategy_explain/scrape_web);scrape_web 门 = scrapeEnabledServices 非空 || jina 内置;描述模板 trimIndent 逐字(enabledServiceNames joinToString/builtinStatus 六件条件列/Today deps 注入);参数 schema 八键 + scrape 两键逐字;resolveScrapeService 全链(selected 索引命中 → candidates 首 → jina 内置回退;service 选择器 enabled 过滤 + jina 四别名 contains);错误文案逐字。
  - **entry 接线**:SearchSdkInit.ets — ensureSearchSdk()(幂等)= DataSourceModule.kt:406 等价;LRU store 文件名 lru_key_roulette.json 逐字(cacheDir,fs sync,读失败 → null/写失败吞咽同 Android catch);acceptLanguage = i18n.System language-region 映射 BingSearchService.kt:52 格式(偏差:Locale.getDefault → i18n.System)。ChatPage:aboutToAppear ensureSearchSdk + loadSearchPrefs → buildToolLoop 注册搜索工具(enableWebSearch 门,ChatService.kt:2232-2234 逐字;prefs 未就绪当轮不带搜索工具,与 StateFlow 就绪时序等价,登记)。
  - **SearchSettings 扩展**:searchServiceSelected 字段(resolveScrapeService 依赖,SearchPrefs.kt:25)。
  - **TDD 10 测试**(prefs 4 + tools 6):默认逐字/往返+冻结键/decode 失败回默认/`!= false`/update 短路;四件注册序+scrape 门/描述模板替换/schema 键序/无源错误对象/status+explain 直通/resolveScrapeService 三路径(jina 别名/索引命中/逐字 error)。
- **可逆性**:1383 测试全绿(deepread 603 + chat 780),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL(含 entry 接线)。

## D-071a（L0，2026-07-28）vision fallback HAR 切片 — 分类器 + LruCache + OcrTransformer + 生成三路径接线

- **背景**：PD-006 残余首片（错误恢复层）。Android 锚点：GenerationHandler.kt:425-470(canUseVisionFallback)/:595-665(内层兜底)/:822-823(hasImageParts)/:1122-1138(分类器)、FileEncoder.kt:37-40、OcrTransformer.kt(141 行全文)、OcrPrompt.kt(全文)、common/cache/LruCache.kt(全文)、ChatPrefs.kt:34-37/PreferencesKeys.kt:34-35(OCR 键)。
- **决定**：
  - **vision_fallback**:ImageEncodingError/VisualRecognitionError(ArkTS Error 构造器仅收 message 且无 cause 声明 → 显式字段承载 cause 链,偏差登记);hasImageParts(image part && url 非 blank);分类器逐字(ImageEncodingException 直 true;否则 cause 链 message join ' ' lowercase 命中 9 关键词,**环防护登记** — Kotlin generateSequence 遇环无限循环,鸿蒙侧 seen 数组防护);OcrPrompt 全文(18 行 join/旧 'You are an OCR assistant.'/'Do not interpret or translate' 迁移,大小写不敏感);javaStringHashCode(31 滚哈希 imul|0);**runWithVisionFallback 内层兜底**(:601-665 — 首轮错误 → canUse(模型 IMAGE 模态 && 有图) && 分类器 → 状态文案 '正在改用视觉识别模型读取图片...' → force 重建消息重投**同一 callProvider/accumulator**(首轮部分输出保留续写,测试锁定 '残'+'全'='残全');兜底自身错误向外传播进外层重试循环;不可兜底原错误重抛)。
  - **vision_cache**:LruCache 全文语义(capacity 64/accessOrder get 重排/deleteOnEvict 逐最旧同步 store/preload 达 capacity 即停+过期移除/expireAfterWrite 3 天/get 内存过期回源 store);CacheStore 按键接口 → 单文件整写 Port(偏差登记,SingleFileCacheStore 同语义);store 全异常吞咽。
  - **ocr_transformer**:OcrTransformer.kt 全文忠实 — transform 门(模型支持图像 && !forceImageToText → 原样引用;无图 → 原样);状态文案 '正在识别图片...'/finally null;image part → image_context trimIndent 模板文本替换;performImageRecognition 五错误文案逐字('请先配置视觉识别模型'/'视觉识别模型不支持图片输入'/'视觉识别模型的提供商不可用'/'视觉识别模型调用失败：MSG'(全角冒号+cause)/'视觉识别模型没有返回可用内容');cacheKey=`url|modelId|prompt.hashCode()`;useCache 读写门;promptOverride trim 非 blank 优先,否则 resolveVisionRecognitionPrompt(ocrPrompt)。**Log.i(识别内容) 省略**(隐私,章程禁止,登记)。
  - **管线异步化**:Android transform 为 suspend → MessageTransformer.transform 允许 Promise;applyInputTransformers 同步改异步(不变量校验 rejection 化);TransformerContext 补 forceImageToText/processingStatus 两字段(Transformer.kt:18 对齐);3 生产调用点(chat_turn/regenerate/tool_loop)+ 4 测试用法全量 await;ctx.processingStatus = deps.onRetryStatus(Android 同一 StateFlow 语义)。
  - **生成三路径接线**:ChatTurnDeps/RegenerateDeps 加 modelSupportsImageInput?(未提供 = 不兜底,等价模型无 IMAGE 模态);visionFallback 闭包(rebuildInternalMessages = applyInputTransformers(assembled, transformers, {...ctx, forceImageToText: true}),:613/:648 prepareInternalMessages(forceImageToText=true) 等价) — chat_turn generateAssistantOnce 第 5 参/regenerate 内联循环/tool_loop 逐步构建(assembled 每步不同)。
  - **TDD 31 测试**:分类器 7(ImageEncodingError 格式+直 true/9 关键词/cause 链+环/VisualRecognitionError cause/prompt 迁移/hashCode/hasImageParts)+ 兜底单元 5(首轮成功不重投/状态文案+force 重建/三不兜底路径/兜底错误传播)+ 集成 4(chatTurn 残全续写+forceFlags [undefined,true]+状态序列/模型不支持/错误不命中/regenerate 分支再生)+ 缓存 8(往返/持久化/64 逐出/accessOrder/3 天过期/preload+读失败/preload capacity/写失败吞咽)+ OCR 8(transform 门×3+状态/force 旁路/五错误链/调用面/override/缓存键)。
- **可逆性**:1414 测试全绿(deepread 603 + chat 811),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。entry 侧 OCR 接线(ChatPrefs ocr_model/ocr_prompt 键+视觉模型查找+ImageEncoder 抛 ImageEncodingError)= D-071b;设置面 OCR 配置 UI = P1 登记。

## D-071b（L0，2026-07-28）vision fallback entry 接线 — OCR prefs + 模型查找 + generateText + ChatPage 注册

- **背景**：D-071a 的 entry 半片。Android 锚点：PreferencesKeys.kt:34-35、ChatPrefs.kt:34-37/80-83、PreferencesStore.kt:379-389(findModelById)/:500-517(findProvider)、OcrTransformer.kt:32-46(cache)/:107-120(generateText)、ChatService.kt:169-177(transformer 链序,OcrTransformer 居尾)、FileEncoder.kt:97-99(recoverCatching 统一包装)。
- **决定**：
  - **OcrSupport.ets(新)**:'ocr_model'/'ocr_prompt' 键名逐字;getOcrModelId — 缺失/非 UUID 形态(8-4-4-4-12 hex 校验,parseUuidOrNull 等价)均回 newId()(Uuid.random() 语义:不命中任何模型 → '请先配置视觉识别模型',设置面 UI = P1 登记);getOcrPrompt 默认 DEFAULT_OCR_PROMPT;vision_cache.json 文件名逐字(cacheDir,fileIo sync,读失败 → null/写失败吞咽);findModelByIdInProviders/findProviderOfModel 逐字(providerOverwrite 分支未移植 = P1 延续登记);buildVisionApi 三变体分发(auth-only 头,**不带** assistant 参数/customHeaders — TextGenerationParams(model) 仅模型,逐字);generateText → choices[0].message?.toText() ?? null。
  - **偏差(登记)**:settingsFlow 实时读 → aboutToAppear 快照(loadOcrSeed;从设置页返回重触发,D-070 搜索 prefs 同模式);seed 未就绪当轮不带 OCR transformer(同搜索工具门)。
  - **EntryImageEncoder**:encodeBase64 失败语义补齐 — encodeImageForChat/encodeImageDetailed 任意失败(非 ImageEncodingError)统一包 ImageEncodingError(url, cause)(:97-99 recoverCatching 逐字;arkts-no-any-unknown 禁 unknown 参数 → inline catch);readImageAsDataUrl(picker 物化)**不包**(Android 选图复制路径非 ImageEncodingException 语义)。
  - **AppContainer**:ChatRuntime + modelSupportsImageInput(model.inputModalities.includes('image'),:431/:50 双消费点)。
  - **ChatPage**:ocrSeed 字段 + aboutToAppear 加载;buildInputTransformers(rt) = [template, ocr](OcrTransformer 居链尾忠实);3 deps 站点(regenerate/resumeToolLoop/send)统一接入 + modelSupportsImageInput 门。
  - **HAR 忠实修正**:performImageRecognition user part 改全新 image part(metadata: null,:113-117 新建 Image(part.url) 不带 metadata)。
  - entry 侧 .ets 不入 npm test 覆盖率(沿用 D-070 等先例;HAR 侧 31 测试已锁语义)。
- **可逆性**:1414 测试全绿(deepread 603 + chat 811),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL(含 entry 接线)。PD-006 残余再减:剩余 = 设置面 OCR UI/生成守卫(P1)。

## D-072（L0，2026-07-28）TimeReminderTransformer + agent_runtime gate — input 链残余首片

- **背景**：ChatService.kt:169-177 input transformer 链残余(TimeReminder/PromptInjection/MiniAppPrompt/Placeholder/DocumentAsPrompt 未移植)首片。Android 锚点：TimeReminderTransformer.kt(全文 77 行)、PreferencesStore.kt:156(enableTimeReminder 默认 false)、PreferencesKeys.kt:77('agent_runtime')、AgentPrefs.kt:47-51(decode 失败回默认)。
- **决定**：
  - **time_reminder_transformer(HAR)**:全文忠实 — gate enabled() 读取时机 = transform 调用时(settingsFlow.value 语义);i>0 且 gap **严格大于** 3600s → current 前注入 UIMessage.user;文案模板逐字('<time_reminder>Current time: DOW, TIME (GAP since last message)</time_reminder>');formatGap 三档 + Kotlin Long 除法向零截断(3600 整落 '1 h' 档,分支边界测试锁定)。
  - **偏差(登记)**:createdAt UTC+Z ISO 串(PD-004 延续;Android LocalDateTime+系统时区,同 tz 双减对 gap 抵消,DST 跨界除外)→ Date.parse 直减,解析失败 NaN 比较恒 false → 不注入(安全退化);时区/locale 格式化注入 entry(HAR 纯逻辑);TextStyle.FULL dayOfWeek 全语言 → zh/en 二档(entry i18n.System.getSystemLanguage(),非 zh 落英文)。
  - **格式化助手(HAR 可测)**:javaLocalDateTimeString — java.time LocalDateTime.toString() 规则(秒毫秒均 0 省略秒/毫秒 0 保 'ss'/否则 'ss.SSS' 毫秒恒 3 位);DAY_OF_WEEK_FULL_EN/ZH + dayOfWeekFullName。
  - **AgentRuntimePrefs.ets(entry 渐进)**:'agent_runtime' 键逐字;缺失/JSON 解析失败 → enableTimeReminder=false(AgentPrefs.kt:47-51 语义);其余 agentRuntime 字段 = P1 延续登记;systemLanguage() helper(i18n,失败回 'en')。
  - **ChatPage 链序**:[timeReminder, template, ocr] — TimeReminder 居首/Ocr 居尾(ChatService.kt:170,176 忠实);agentRuntime 快照 aboutToAppear 加载。
  - **TDD 8 测试**:gate 关原引用/3600 整不注入+3601 注入(文案逐字+user 角色)/首消息不注入+多处注入/formatGap 三档边界+截断/NaN 安全退化/完整 transform/javaLocalDateTimeString 三档+补零/dayOfWeek zh/en。
- **可逆性**:1422 测试全绿(deepread 603 + chat 819),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL(含 entry 接线)。input 链残余:PromptInjection/MiniAppPrompt/Placeholder/DocumentAsPrompt = P1。

## D-073（L0，2026-07-28）PlaceholderTransformer + PlaceholderValues entry — input 链第二片

- **背景**：ChatService.kt:174 链位。Android 锚点：PlaceholderTransformer.kt(全文 182 行)、PreferencesKeys.kt:22('display_setting')、UIPrefs.kt:56(decode 失败回默认)、PreferencesStore.kt:310-318(DisplaySetting/userNickname 默认 '')。
- **决定**：
  - **placeholder_transformer(HAR)**:13 键插入序冻结(LinkedHashMap 迭代序);replacePlaceholders 逐字 — 逐键 replace('{{key}}')+replace('{key}') ignoreCase=true 全替换,**顺序执行**(后键可命中前键产出文本,测试锁定 nickname='{char}' 链);replaceAllIgnoreCase = 正则转义 + /gi + **函数 replacer**(Kotlin replace 字面语义,值含 '$' 不解释,测试锁定);resolver blank 回退逐字(nickname/user blank→'user',char=assistant.name blank→'assistant');transform 全量 copy 无恒等短路。
  - **PlaceholderValues 注入**:ctx.model 不在 TransformerContext → model_id/model_name 由 values 供给(entry 取 rt.params.model);平台取值(battery/device/locale/timezone/version/时间格式化)entry 实现。
  - **PlaceholderSupport.ets(entry)**:Intl dateStyle/timeStyle medium(Java FormatStyle.MEDIUM 语义档,字节级偏差登记);locale displayName → language-region 原始 tag(Intl.DisplayNames 不可用,登记);timezone → IANA 名(resolvedOptions,登记);system_version → 'HarmonyOS API v${sdkApiVersion} (${osFullName})'(命名偏差登记);device_info → deviceInfo.brand+productModel;battery → batteryInfo.batterySOC(异常 → -1,BatteryManager 不可用语义);display_setting 键逐字 + userNickname decode 失败回 ''。
  - **ChatPage 链序**:[timeReminder, template, placeholder, ocr](Android 相对序忠实:TimeReminder 1 < Placeholder 4 < Ocr 6;template 为 D-016 既有位);userNickname 快照 aboutToAppear 加载。
  - **TDD 7 测试**:13 键序/双形态+大小写+全替换/$ 字面+链式顺序/blank 回退三键/全键映射/transform text 替换+非 text 不动+全量 copy/正则元字符安全。
- **可逆性**:1429 测试全绿(deepread 603 + chat 826),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL(含 entry 接线)。input 链残余:PromptInjection/MiniAppPrompt/DocumentAsPrompt = P1。

## D-074（L0，2026-07-28）DocumentAsPromptTransformer + DocumentReader entry — input 链第三片

- **背景**：ChatService.kt:175 链位。Android 锚点：DocumentAsPromptTransformer.kt(全文 144 行)。
- **决定**：
  - **document_transformer(HAR)**:transform 全文忠实 — document parts 逐个 readDocumentContent → buildDocumentPrompt → add(0) 前插(逆序效果,测试锁定);恒量 copy 无短路。**prompt 模板 quirk 钉住**:trimMargin() 无 '|' → 18 空格缩进原样保留(cat -e 核实字节),空行 → ''(首 '\n' 起、尾 18 空格行 → '');readDocumentContent 错误文案逐字('[ERROR, invalid file uri/not found(含 isFile=false 同文案)/file too large to inline (N bytes)/failed to read file]');64MB/512K 常量;mime 四分发;文本截断标记逐字;二进制占位 appendLine 全文逐字(含尾换行);isLikelyTextFile text/* + 41 扩展名集(substringAfterLast 空串语义)。
  - **DocumentReaderDeps Port**:statFile(非法 uri → null)/readTextFile(MAX+1 上限)/parsePdf/Docx/Pptx/Epub 可选 — **entry 刻意不提供**(document 模块未移植,P1 登记)→ HAR 抛错走 '[ERROR, failed to read file]'(与 Android 解析失败同文案路径);OfficeNativeSwitch 不移植。
  - **DocumentSupport.ets(entry)**:非 file scheme → null(toUri().toFile() 抛错语义);fileIo.statSync 映射 exists/isFile(S_IFMT&S_IFREG)/size/path;stat 失败 → exists=false(File("") 语义);整读 + UTF-8 decode + substring 截断(bufferedReader 语义等价)。
  - **ChatPage 链序**:[timeReminder, template, placeholder, document, ocr](Android 相对序 1<4<5<6 忠实)。
  - **TDD 7 测试**:常量+41 扩展集(含 '.hidden'/无点边界)/三错误文案+isFile=false/too large 字节数/四 mime 分发+未提供 parser+parser 抛错/readTextFile 上限+截断标记逐字/二进制占位逐字/prompt 模板 18 空格+首尾换行/transform add(0) 逆序+恒量 copy。
- **可逆性**:1436 测试全绿(deepread 603 + chat 833),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL(含 entry 接线)。input 链残余:PromptInjection/MiniAppPrompt = P1(MiniApp 依附 webmount 未移植)。

## D-075（L0，2026-07-28）PromptInjectionTransformer + ExtensionPrefs entry — input 链收官

- **背景**：ChatService.kt:171 链位 2(MiniAppPrompt 3 依附 webmount 未移植,P1 延续)。Android 锚点:PromptInjectionTransformer.kt(全文 264 行)、Assistant.kt:140-287(InjectionPosition/PromptInjection/Lorebook/isTriggered/extractContextForMatching)、SystemPromptMarkers.kt:3-4、PreferencesKeys.kt:74-75、ExtensionPrefs.kt:82-87。
- **决定**：
  - **prompt_injection_transformer(HAR)**:全文忠实 — collectInjections(modeInjection 过滤 enabled&&关联序保持;lorebook 非 SYSTEM 消息 + 逐条 scanDepth takeLast 上下文 → isTriggered);transformMessages 空注入原引用返回,否则 priority 降序(stable)+ position 分组;applyInjections 四区段:BEFORE/AFTER 拼入 system parts(before 带 {'system_prompt_cache_control':'disabled'} metadata,after 无),无 system → 新建 system 消息插 0 且 after 加 '\n' 前缀(before 非空时,:168 quirk 钉住);TOP_OF_CHAT 首 user 前(无 user → 末尾);BOTTOM_OF_CHAT 末条前;AT_DEPTH 按 injectDepth 分组深度倒序,index=(size-depth.coerceAtLeast(1)).coerceIn(0,size);三处插入点全过 findSafeInsertIndex(USER→ASSISTANT 含 Tool 回退);createMergedInjectionMessages role 分组首见序合并,assistant→UIMessage.assistant else→user。
  - **语义钉住**:Kotlin takeLast(0)=空(JS slice(-0)=全数组,特判)/takeLast(负)抛错消息逐字;isTriggered 短路序(enabled→constantActive→keywords 空);regex 构造/匹配异常 → 该 keyword false(:247-249);ignoreCase=JS /i 或双 lowercase contains;groupBy 首见序(JS Map)。
  - **偏差(登记)**:ctx.settings.modeInjections/lorebooks → deps 注入(读取时机 = transform 调用时,settingsFlow.value 语义,测试锁定后改 deps 生效);Java Regex → JS RegExp(PD-005 延续);Uuid → string id(makeModeInjection/makeRegexInjection/makeLorebook 工厂 newId() 默认);cache 常量改由 context_assembly.ts(D-045)定义本模块 re-export 消费。
  - **ExtensionPrefs.ets(entry)**:'mode_injections'/'lorebooks' 键逐字;decodeJsonOrNull 语义(缺失/解析失败 → []);JSON 逐字段 ?? 默认(kotlinx 缺字段走默认值等价)。
  - **ChatPage 链序**:[timeReminder, promptInjection, template, placeholder, document, ocr](Android 相对序 1<2<4<5<6 忠实;MiniAppPrompt 3 P1 已登记);modeInjections/lorebooks 快照 aboutToAppear 加载。
  - **TDD 14 测试**:isTriggered 短路/大小写/regex 异常/takeLast 0 与负/收集过滤+lorebook scanDepth+system 排除/findSafeInsertIndex 回退与 clamp/role 合并/system parts 顺序+metadata 逐字/无 system 新建+'\n' 前缀 quirk/TOP/BOTTOM/AT_DEPTH 索引/空注入原引用+priority 降序稳定/deps 延迟读取/cache 常量逐字。
- **可逆性**:1450 测试全绿(deepread 603 + chat 847),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL(含 entry 接线)。**input 链 6 片全移植完成**(MiniAppPrompt 除外,P1 登记);entry 链相对序与 ChatService.kt:169-177 全对齐。

## D-076（L0，2026-07-28）Base64ImageToLocalFileTransformer + Base64ImageSupport entry — output 链收官(除 MiniApp)

- **背景**：ChatService.kt:179-184 output 链序 [ThinkTag, Base64Image, MiniAppOutput(P1), RegexOutput]。Android 锚点:Base64ImageToLocalFileTransformer.kt(全文)、FilesManager.kt:183-265/469-480/643-646。
- **决定**：
  - **pipeline 异步化**:Android onGenerationFinish 为 suspend → OutputMessageTransformer.onGenerationFinish 允许返回 Promise;applyOnGenerationFinish 同步改异步(await);chat_turn/regenerate 两调用点加 await(D-071a transform 异步化同模式)。
  - **base64_image_transformer(HAR)**:convertBase64ImagePartToLocalFile 忠实 — 仅 Image part 且 url startsWith('data:image');base64 段 = substringAfter('base64,')(不含分隔符 → 整串);decodeBase64 失败抛错向外传播(Kotlin Base64.decode 同);reencodeToPng → null(不可解码)**保留 data url part 原引用**;可解码 → saveUploadImage → part.copy(url);非 data/非 image part 原引用;顺序 await(Kotlin map 序)。
  - **decodeBase64 内建(HAR 纯)**:标准 alphabet,非法符号/长度非 4 倍/pad 位置错 → 抛错(Kotlin IllegalArgumentException 语义,测试锁定三种错误)。
  - **Base64ImageDeps Port**:reencodeToPng(BitmapFactory+compressToPng 等价)/saveUploadImage(128MB require + upload/{uuid}.png 落盘 → file:// uri);Koin 全局获取 → deps 注入。
  - **Base64ImageSupport.ets(entry)**:@kit.ImageKit — createImageSource → createPixelMap → packToData(image/png, quality 100)(compressToPng 等价),任何一步抛错 → null;fileIo 写 filesDir/upload/{newId()}.png(buildUuidFileName('image.png','image/png') 语义);128MB 超界抛错文案逐字。
  - **偏差(登记)**:FilesRepository(Room ManagedFileEntity)跟踪未移植 → 仅落盘不记库(P1:upload 库观察/countChatFiles/删除跟踪待 FilesRepository 移植);PixelMap.release() 在打包后调用(资源管理补充,无语义偏差)。
  - **ChatPage output 链**:3 站点改 [thinkTag, base64Image, regexOutput](Android 相对序忠实,MiniApp P1)。
  - **TDD 9 测试**:常量/substringAfter/data 前缀/decode 往返+三种抛错/转换全链+text 原引用/不可解码保留/非 data 原引用/onGenerationFinish 全消息/非法 base64 传播;另 transformers.test.ts 既有 onGenerationFinish 用法改 await(1 处)。
- **可逆性**:1459 测试全绿(deepread 603 + chat 856),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。output 链残余:MiniAppOutputTransformer(P1,依附 webmount)。

## D-077a（L0，2026-07-28）tool system prompt 块(buildSystemPromptParts:739-741,:763-770)

- **背景**：动态 system prompt blocks 收尾切片之一。Android 锚点:GenerationHandler.kt:739-741(toolPrompt join)/:763-770(tool_prompts part);ai/core/Tool.kt:16(systemPrompt 签名)。
- **决定**：
  - **buildToolSystemPrompt(HAR,context_assembly)**:tools.map { systemPrompt(model, messages) } → blank 过滤 → '\n\n' 连接(:739-741 忠实);model/messages 透传逐工具(测试锁定)。
  - **assembleInternalMessages**:AssembleInternalInput + toolPrompt?: string;非 blank → 附加第三 text part(metadata **仅** {system_prompt_block:'tool_prompts'},无 cache_control,:766-768 逐字);块序 static → dynamic → tool_prompts(:743-770 忠实)。
  - **tool_loop**:ToolLoopOptions + toolPromptModel?: ChatModel(未提供 → 不产出,等价 tools 空列表);每步重算(对齐 Android 每次 generateInternal 重算),工具集 = 本步暴露子集 stepTools(与发 provider 的 stepDefs 同口径,D-062 懒暴露语义一致)。
  - **entry ChatPage**:buildToolLoop + toolPromptModel: rt.params.model。
  - **chat_turn 非工具路径不动**:无工具 → Android 同(空 tools → 空 prompt → 无块)。
  - **TDD 4 测试**:blank 过滤+'\n\n' 连接/model+messages 透传/块序+metadata 逐字(无 cache_control)/blank+缺省无块。
- **可逆性**:1463 测试全绿(deepread 603 + chat 860),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。动态块残余:recentChats(memory/generativeUi = P1 大特性)。

## D-077b（L0，2026-07-28）Recent Chats 动态 system 块 + enableRecentChatsReference 接线

- **背景**：动态块收尾(GenerationHandler.kt:736)。Android 锚点:GenerationPrompts.kt:219-241(无 assistant 变体)、ConversationDAO.kt:29-30(is_pinned DESC, update_at DESC LIMIT 10)、TimeUtil.kt:14-21(toLocalDate = 系统时区 MEDIUM localized date)、core/agent-utils Json.kt:22-30(JsonInstantPretty)、PreferencesStore.kt:155(默认 true)。
- **决定**：
  - **recent_chats_prompt(HAR)**:RECENT_CHATS_PROMPT_LIMIT=10;buildRecentChatsPrompt 模板逐字(appendLine 序列:'\n**Recent Chats**\n' + 文案 + '\n' + json + '\n';空列表 → '');prettyPrintRecentChatsJson = JSON.stringify(_, null, 4)(kotlinx prettyPrint 默认 4 空格,扁平对象数组形状逐字节一致,测试锁定含中文/引号转义)。
  - **偏差(登记)**:toLocalDate 格式化在 entry(Intl medium date,PlaceholderSupport D-073 同档);仓库取数走既有 ChatRepository.listRecent(排序同 DAO);HAR 收预格式化字符串。
  - **deps 通道**:ChatTurnDeps/RegenerateDeps + extraSystemBlocks?: () => Promise<string[]>(每次组装调用时取数 = Android 每次 generateInternal 重算语义);chat_turn/regenerate 直通 assemble;tool_loop 组合 [budget, ...deps](Android 动态块序 memory→loopBudget→generativeUi→recentChats,:726-736)。
  - **AgentRuntimePrefs(entry)**:snapshot + enableRecentChatsReference,默认 true(仅显式 false 才关,PreferencesStore.kt:155);缺失/损坏 → 默认快照。
  - **ChatPage**:loadExtraSystemBlocks — 关 → [];开 → listRecent(10) → title + Intl medium date(updateAt) → buildRecentChatsPrompt → 非空才成块;3 个生成 deps 站点接线。
  - **TDD 3 测试**:LIMIT 常量/pretty 4 空格形状逐字/模板逐字+空列表。
- **可逆性**:1466 测试全绿(deepread 603 + chat 863),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。动态块残余:memory(memory 子系统 P1)/generativeUi(generative UI 大特性 P1)。

## D-078（L0，2026-07-28）chat provider 多 key 轮换接线 — PD-008 消除

- **背景**：KeyRoulette 本体已随搜索移植(D-065,KeyRoulette.kt 全文:split/distinct/DefaultRandom/LRU 24h 过期/持久化结构),chat 三 provider 一直单 key 直用(PD-008 open)。Android 锚点:OpenAIProvider.kt:61(ChatCompletionsAPI.kt:102-105 bearerResolver 每请求)/ClaudeProvider.kt:68,:74/GoogleProvider.kt:77,:134/KeyRoulette.kt:43(LRU_CACHE_FILE 逐字)。
- **决定**：
  - **HAR api 层**:ClaudeChatApiDeps/GoogleChatApiDeps + keyRoulette?: KeyRoulette(未提供 → 模块级 DEFAULT_ROULETTE = createDefaultKeyRoulette());x-api-key/x-goog-api-key/vertex key query **每请求** roulette.next(setting.apiKey, setting.id)(:74/:134 语义);openai 层不动(token 注入设计,轮换点 = adapter headers() 每请求调用)。
  - **KeyRouletteSupport.ets(entry)**:LRU 单例(getChatKeyRoulette);store = cacheDir/lru_key_roulette.json 逐字文件名,read 不存在/失败 → null,write 吞咽(Android saveCache catch {} 语义),nowMs = Date.now;app 运行时恒 LRU(Android context != null 分支)。
  - **接线**:AppContainer resolveChatRuntime 三变体 — claude/google 传 keyRoulette;openai token 从一次性解析改 nextToken() 闭包(headers() 内每请求轮换,bearerResolver 语义);OcrSupport buildVisionApi 同接线(vision api 走同一 provider 池)。
  - **TDD 4 测试**:claude 每请求 next(apiKey,id)+x-api-key 轮换/google 头轮换/vertex key query encode/缺省 roulette 随机取一不抛错。
- **可逆性**:1470 测试全绿(deepread 603 + chat 867),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。PARITY_DEBT PD-008 → closed;open 偏差余 6 条。

## D-079a（L0，2026-07-28）图片压缩 HAR 纯函数 — FileEncoder.kt 无平台依赖部分

- **背景**：PD-007 登记图片压缩裁剪。压缩管线拆两层:纯函数(mime 魔数嗅探/采样步进/EXIF 映射)进 HAR 可测;PixelMap 解码/变换/打包进 entry(ImageKit)。Android 锚点:FileEncoder.kt:14-30(supportedTypes/HEIF/AVIF brands)、:53-66(EXIF 8 值映射)、:236-250(calculateInSampleSize)、:252-303(guessMimeType)。
- **决定**：
  - **image_compress(HAR)**:COMPRESS_MAX_DIMENSION=2048/COMPRESS_JPEG_QUALITY=85(:140-141 默认参);SUPPORTED_IMAGE_TYPES 6 frozen;HEIF_BRANDS 10/AVIF_BRANDS 2 逐字。
  - **guessImageMimeType(Uint8Array)**:逐字 — <12 字节抛 'File too short to determine MIME type';ftyp box(offset 4 ASCII)+ brand(offset 8-12)→ image/heic|avif;JPEG FFD8;PNG 8 字节魔数;RIFF+WEBP;GIF89a/87a;否则抛 `Failed to guess MIME type: {header6}, {bytes}`(无符号字节逗号连接,逐字)。
  - **calculateInSampleSize(w,h,req=2048)**:两个 while 逐字 — 第一循环整除(Math.trunc 对齐 Kotlin Int 除法)倍增至 ≤req;16MP 安全网(Long 乘积,Math.trunc 后相乘)再倍增。测试锁定:8000x8000 → 4(第一循环主导,16MP 网未触及);16384x16384 → 8。
  - **EXIF 常量 0-8 + mapExifOrientationToTransform**:switch 逐字,NORMAL/UNDEFINED/未知 → NONE;ExifTransformType 为字符串 union(Android internal enum 忠实)。
- **可逆性**:1476 测试全绿(deepread 603 + chat 873),tsc 双 HAR clean。entry 管线见 D-079b。

## D-079b（L0，2026-07-28）选图物化即压缩 — ImageCompress.ets + ChatPage 接线(PD-007 压缩缺口消除)

- **背景**：Android 链:picker → 复制私有目录存 file:// → 每次发送 encode 时 compressAndEncode。鸿蒙 D-038 起选图即刻物化 data: URL;encode 期链(claude/google/openai 请求构建)同步深入,异步 ImageKit 无法不重构即注入 → **压缩点移前至物化**(单次),提供商载荷等价(JPEG q85,长边 ≤2048,EXIF 归一)。
- **决定**：
  - **ImageCompress.ets(entry)**:compressImageAsDataUrl(uri) — fileIo 开只读 fd;头 16 字节 → HAR guessImageMimeType(逐字错误);**gif 原样直通**(:143-146,offset=0 重读全文件,保 image/gif);createImageSource(fd) → getImageInfo 取尺寸,失败/≤0 → 'Failed to decode image dimensions: X' 逐字(:153-155);calculateInSampleSize → createPixelMap({sampleSize}) 失败 → 'Failed to decode image: X' 逐字(:161-170,heic/avif API 等级变体鸿蒙无对应物,统一 else 分支);EXIF 经 getImageProperty(ORIENTATION) 文本→整型映射(Top-left=1…Left-bottom=8)回 HAR mapExifOrientationToTransform,读取失败按 NORMAL(:189-198 runCatching 语义);变换 = PixelMap.flip/rotate 原地顺序(TRANSPOSE=rotate90+flipH / TRANSVERSE=rotate270+flipH,与 Matrix postScale 合成等价),catch 保留原图(:221-223);packToData(image/jpeg, 85) → data:image/jpeg 前缀(:174-186 强制 JPEG)。
  - **ChatPage.pickImages**:改异步压缩物化(每张独立 then/catch,失败逐张告警非静默,语义不变);readImageAsDataUrl 删除(链路整体迁移,无遗留死代码)。
  - **偏差(登记 PARITY_DEBT PD-007 更新)**:压缩时机 = 物化一次 vs Android 每次发送(载荷等价,重复发送不重复压缩);file:// 来源(D-076 upload 落盘图)encode 期仍原样;选图仍不落私有目录(残余 P1)。
- **可逆性**:1476 测试全绿(deepread 603 + chat 873),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。PD-007 压缩缺口消除,残余:file:// 私有目录存储 + file:// encode 期压缩。

## D-080（L0，2026-07-28）canSendWithoutChatModel 模型空值门 — 发送/长按/建议三路径

- **背景**：Android ChatPage.kt:532-533(canSendWithoutChatModel = parseDeepReadSlashCommand(parts) != null)+ 五处发送门(:676-679 发送/:695-698 长按/:823-825 建议单击/:864-869/:880-885):currentChatModel == null 且非斜杠路由 → toast '请先选择模型'(Error,逐字)中止,输入保留;编辑态 canRouteWithoutChatModel 恒 false(编辑必走模型)。鸿蒙此前:无模型时一路进 sendParts → resolveChatRuntime null → reject '未配置可用的 Provider/模型…',且入队路径完全无门(排队消息到派发时才失败,Android 输入时即拦截)。
- **决定**：
  - **AppContainer**:resolveChatProviderChoice + ChatProviderChoice 导出(原私有),作为 currentChatModel == null 的等价判定(KV 异步读,Android 为 VM 缓存态 — 每次点击重读,语义等价且更严)。
  - **ChatPage.gateModelConfigured(onConfigured)**:choice null → errorMsg='请先选择模型' 逐字 return;catch → errorMsg。斜杠命令无需判 — 各路径斜杠拦截已前置(D-064),命中即路由返回。
  - **接线三路径**:① send() — 斜杠拦截后进门,门内续走 FOLLOWUP 入队/编辑重发/sendParts(编辑态同样在门内,:675 !isEditing 语义);② sendWithoutAnswer() — 门过才清输入/取消编辑态/入队/append(中止时输入框原样保留,对齐 Android 未 clearInput);③ 建议长按生成中入队 — 门内入队(非生成中走 this.send() 复用门)。
  - **输入保留语义**:send() 原本仅成功才清输入(:1317),门中止天然保留;sendWithoutAnswer 原先行前清输入,改为门内清(行为修正)。
  - **sendParts 内 rt-null reject 保留**为纵深防御(门后竞态:用户在门与派发间删 provider)。
- **可逆性**:1476 测试全绿(deepread 603 + chat 873,纯 entry UI 变更无新增测试),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。gap-audit 残余「canSendWithoutChatModel 模型空值门」消除。

## D-081（L0，2026-07-28）STEER 进行中注入修正 — 长按生成中 answer=true + mode/answer 解耦

- **背景**：核查「STEER 进行中注入」残余时对 Android 重锚,发现 D-051/D-048 引入两处偏差。Android 锚点:ChatInput.kt:259(普通发送恒 FOLLOWUP)/:265-289(长按:loading 且输入空 → onCancelClick;否则 queueMode = loading ? STEER : FOLLOWUP)、ChatPage.kt:706-710(**answer = loadingJob != null && queueMode == STEER**)、ChatService.kt:695-700(mode = isGenerating ? queueMode : FOLLOWUP)、:386-390(步间消费)、:1070(派发时 STEER → asFollowup)。
- **决定**：
  - **修正 1(answer 标志)**:鸿蒙长按生成中原先入队 STEER + answer=false(被消费则无害,但未消费降级派发时只记录不生成 — 与 Android 相反)。Android 语义:生成中长按 = STEER + answer=**true**(插话被进行中生成消费即「已应答」;未消费降级 FOLLOWUP 派发时仍触发生成)。修正为 enqueuePendingParts(inputParts, true, 'STEER')。
  - **修正 2(mode/answer 解耦)**:原 mode 推导 `!answer && sending ? STEER : FOLLOWUP` 把 answer 与 mode 耦合(Android 两者正交:FOLLOWUP+answer=true 普通发送 / STEER+answer=true 长按 / 非生成中长按 answer=false 直接 append 不入队)。enqueuePendingParts 改显式 mode 参数(默认 FOLLOWUP)。
  - **不变项(核对后确认忠实)**:普通发送生成中 FOLLOWUP+answer=true(:259 恒 FOLLOWUP);建议长按入队 FOLLOWUP+answer=true(:871);非生成中长按 answer=false 直接 append;队列满文案逐字(:706);tool_loop 步间消费+写回+emit(:386-390 等价,appendNode+save+emitSnapshot);drainSteerMessages filter-all ≡ takeWhile-prefix(入队不变式保证 STEER 仅在前缀);STEER 仅工具循环消费,非工具路径等派发降级(Android 同)。
  - **COLLECT 模式不移植(登记)**:Android 全代码库无任何 COLLECT 入队点(enum/isCollectable/合并机器/UI 标签齐备但无生产者,休眠路径);HAR pending_queue 已含 isCollectable/buildCollected 纯函数(D-048 随队列移植,测试锁定)即足够;若 Android 复活该路径再补 UI。
- **可逆性**:1476 测试全绿(deepread 603 + chat 873),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。gap-audit「STEER 进行中注入」残余消除。

## D-082（L0，2026-07-28）ToolProfileFilter — 工具画像过滤 + tool_search profile trace

- **背景**：gap-audit 工具行残余。Android 锚点:MainAgentToolProfile.kt(6 值 SerialName)、ToolProfileFilter.kt 全文(filter/isAllowed/MINIMAL 7 + WEB_READ 61 + WORKSPACE_READ 16 + CODING 4 + MOBILE_CONTROL 4)、ToolRegistry.kt:424-457(Tool.category() when 链)、ChatService.kt:2302/:2324(过滤点)/:2326-2329(tool_search(profile))、ToolSearch.kt:28-31/:115-118(profile 仅进 trace)。
- **决定**：
  - **toolCategory 复用不重复移植**:ToolRegistry.kt:424-457 when 链已由 tool_policy.ts:179 先行逐字移植(D-056 族),本模块 import 复用;测试锁定 when 链全分支。
  - **tool_profile_filter(HAR)**:ToolProfileFilterResult{profile,tools,filteredCount,filteredByCategory};filter 逐字 — FULL 直通(同引用+空 map);kept/filterNot 按 keptNames 名集合(同名重复工具同留同滤,测试锁定);filteredByCategory 插入序 = 源遍历序。isAllowed when(profile) 逐字;常量集四组冻结数组,形状锁定(MINIMAL 7)。
  - **tool_search profile 透传**:createToolSearchTool(registry, profile=null);searchPayload trace 键序 — mode,query,[profile,profile_filtered],hit_tools…(buildJsonObject 序 :113-119);profile=full → filtered=false;null → 无键(向后兼容);评分不受 profile 影响(Android 同)。
  - **ChatPage.buildToolLoop 接线**:原始工具数组先 filterToolProfile(rt.assistant.toolProfile).tools 再 createToolRegistry;tool_search 传 rt.assistant.toolProfile;自省对在过滤后追加(Android :2326-2329 同序)。登记:Android 第二过滤点(:2324)针对 subagent/modelCouncil 增广工具 — 鸿蒙无此两族工具(未移植),单过滤点等价。
  - **Assistant.toolProfile 早已在模型层**(D-016,默认 'full' → 默认行为零变化);助手编辑面选择 UI = P1(交互档)。
- **可逆性**:1485 测试全绿(deepread 603 + chat 882,+9),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。gap-audit「ToolProfileFilter」残余消除(设置面 UI 除外)。

## D-083（L0，2026-07-28）LocalToolOption 门控 — time_info/ask_user/clipboard 按助手勾选

- **背景**：gap-audit 工具行残余。Android 锚点:LocalToolOption.kt(13 variant sealed,snake_case SerialName)、LocalTools.kt:105-200(getTools:勾选门控 10 variant + 无门恒在 permissionsStatus/runPlanUpdate(:182)/deepReadPlaybook/deepReadOpen(:188)/agentPromptConfig/cron;imageGen 条件)、ChatService.kt:2237(addAll(localTools.getTools(assistant.localTools, id)))。鸿蒙此前 time/ask_user/clipboard 三工具无条件入列 — 默认助手(localTools=['time_info'],Assistant.kt:41 忠实)在 Android 下只有 time 工具,鸿蒙多给两个,行为不忠实。
- **决定**：
  - **ChatPage.buildToolLoop**:localOpts = rt.assistant.localTools;time_info/ask_user/clipboard 三处改 indexOf 门控(顺序保持:time → ask_user → …→ clipboard,与 Android getTools 追加序一致);run_plan_update/deep_read_open 保持无门(:182/:188);conversation context/history/queue/search 非 local tools(ChatService 另处添加),保持无门。
  - **空转登记**:其余 10 variant(javascript_engine/tts/workspace_files/terminal/screen_automation/system_access/webview/icloud_drive/webmount/webmount_eval)对应工具族未移植,门控为空转;移植各自工具族时按 LocalTools.kt:107-181 同序接入。
  - **assistant.localTools 模型层早已在**(D-016,默认 ['time_info'] → 默认行为变化 = 对齐 Android:ask_user/clipboard 不再默认暴露);助手编辑面勾选 UI = P1(交互档,Android AssistantLocalToolPage.kt)。
- **可逆性**:1485 测试全绿(deepread 603 + chat 882,纯 entry 接线无新增),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。gap-audit「LocalToolOption 门控」残余消除(编辑面 UI 除外)。

## D-084（L0，2026-07-28）providerOverwrite — Model.findProvider overwrite 语义落地

- **背景**：gap-audit 残余。模型层 providerOverwrite 字段 + 线格式递归早已移植(D-016/D-017),运行解析一直忽略。Android 锚点:PreferencesStore.kt:500-507(findProvider:容器反查 ?: null → checkOverwrite && providerOverwrite != null → overwrite.copyProvider(models=emptyList()))、:509-518(反查 providers × models 声明序)、ProviderSetting.kt:156-165(copyProvider = data class copy,变体字段全保留)、GenerationHandler.kt:155(生成路径 checkOverwrite 默认 true)、OcrTransformer.kt:50(vision 查找同)。
- **决定**：
  - **HAR provider_settings**:copyProviderSettingWithModels(三变体显式字段,arkts-no-spread;builtIn/description/shortDescription 为 Android @Transient UI 字段,鸿蒙模型层本不携带)+ findProviderForModel(providers, model, checkOverwrite=true)逐字(未命中容器 → null,overwrite 不兜底)。
  - **AppContainer.effectiveProvider**:resolveChatProviderChoice 两返回点(用户选择/回退)统一经 effectiveProvider — 容器命中后 overwrite 副本(models=[])替代;hasUsableAuth 预检留在容器上(Android 同:findProvider 不校验 overwrite auth,坏 auth 在 API 层显式失败)。
  - **OcrSupport.findProviderOfModel**:本地反查(无 overwrite)改委托 HAR findProviderForModel(默认 checkOverwrite=true),消除 D-071a 登记的 P1 分支。
  - **TDD 6 测试**:容器未命中 → null/无 overwrite → 容器同引用/overwrite 副本 models=[] + google 变体字段全保留/checkOverwrite=false/反查声明序/openai+claude 变体字段保留。
- **可逆性**:1491 测试全绿(deepread 603 + chat 888,+6),tsc 双 HAR clean,assembleHar+assembleHap BUILD SUCCESSFUL。gap-audit「providerOverwrite」残余消除(模型编辑面 overwrite UI = P1 交互档)。

## D-085a:记忆子系统读路径纯模块(chat HAR)

**日期**: 2026-07-28
**来源**: `core/memory/api/.../MemoryModels.kt`、`core/memory/impl/.../time/MemoryTimeAnchorParser.kt`、`MemoryPromptBuilder.kt`、`MemoryRecallStore.kt` 中 rankRecords/scoreRecord/tokenize 纯函数段

### 内容
- `memory_models.ts`: MemoryScope/MemoryKind/MemoryCandidateStatus/MemoryEventType wireName 枚举 + fromWireName 兜底(scope→long_term, kind→note, status→pending, event→extraction_skipped); MemoryRecord/MemoryCandidate/MemoryEvent/MemoryRecallSetting 接口 + 工厂(默认逐字:record confidence=1、candidate confidence=0.5/updatedAt=createdAt、recall {maxItems:12,maxPromptChars:2000,debug:false})。MemoryWorkerSetting 属后台 worker 面,随 extraction/dream 切片再迁。
- `memory_time_anchor.ts`: LocalDate/YearMonth/ZoneId.systemDefault → JS Date 本地时区;锚点校验(月 1-12、日 1..daysInMonth,非法→null)、ABSOLUTE_DATE/中文年月日/ENGLISH_FUTURE 正则逐字、FUTURE_HINTS(9)/HISTORICAL_HINTS(11) 逐字;classifyMemoryFreshness(!future||historical→current)、deriveMemoryExpiresAt(取非 before 锚点最小过期)。
- `memory_prompt_builder.ts`: buildMemoryContext 逐字 — 引导行 + '- [scope/kind(/pinned)] content',content 换行→空格(Kotlin replace 全量语义,JS 用 split/join),debug 后缀 '(id=N, confidence=X.XX[, details])',收尾 '</memory_context>';SENSITIVE_MEMORY_TERMS(12)+ isSensitiveMemoryContent。
- `memory_recall.ts`: USER_ALWAYS_ELIGIBLE_CONFIDENCE 0.70、TIME_DECAY_MULTIPLIER 0.35;tokenizeMemoryQuery(unicode 词 ≤48 + CJK 窗口 size4/step2 take80,并集 take160);scoreMemoryRecord(查询前缀 +30/slice(0,60)、term 权重 max(2,min(len,12))、no-match 早退、pinned 100、kind 52/44/24/18/12/6、scope 26/18/14、lastUsed 8/(1+days)、updated 10/(1+days)、decay ×0.35、confidence 钳 0.1-1、reasons 去重);rankMemoryRecords(slice(-16) 拼查询、钳 maxItems[1,40]/maxPromptChars[256,12000]、score>0||terms 空过滤、稳定排序 pinned>score>updatedAt、takeRecallBudget cost=len+32 首条恒收)。

### 测试
+21(memory_recall.test.ts;含 fromWireName 兜底、锚点校验/正则、freshness 门、expiresAt、prompt 逐字含 debug、敏感词、tokenize 窗口、score 各加成、rank 过滤/排序/预算/钳制)。chat HAR 888→909。

### 备注
- Store 编排(recallSelections→getActiveRecords→rank→touchMemories→buildMemoryContext)与设置/动态块接线在 D-085b。
- extraction(MemoryExtractor/CandidateFilter/Prompt)、dream、导出导入、memory_* 工具、设置面 UI 后续切片。

## D-085b:记忆召回编排 + 设置 + JSON 存储 + 动态块接线

**日期**: 2026-07-28
**来源**: `core/memory/recall/MemoryRecallStore.kt:14-53`(编排)、`MemoryRepository.kt:69-72,149-153`(读路径仓储)、`MemoryDAO.kt:19-23,43-44`(查询语义)、`PreferencesStore.kt:152-154,174`(设置默认)、`GenerationHandler.kt:414,726-736`(动态块序)

### 内容
- **HAR `memory_recall_store.ts`**:
  - `recallMemorySelections`(:38-53 逐字)— scopes 门(三开关,core→short→long 序,空 → [] 且**不触仓**)→ getActiveRecords → rankMemoryRecords。
  - `recallMemoryRecords`(:34-36)/`buildMemoryRecallPrompt`(:17-32)— selections → touchMemories(ids)→ buildMemoryContext(debug 时 details = id→score.toDebugText())。
  - DAO 语义纯函数:`filterActiveMemoryRecords`(scope IN && !archived && (expiresAt null||>now);无 ORDER BY → 插入序)、`touchMemoryRecords`(命中置 lastUsedAt,新数组不改输入)。
- **deps 通道**:`memorySystemBlocks?: () => Promise<string[]>` 加入 ChatTurnDeps/RegenerateDeps;动态块序对齐 Android(:727 memory 最前)— tool_loop 组合 `[memory, budget, extra]`,chat_turn/regenerate 组合 `[memory, extra]`。
- **entry**:
  - `AgentRuntimePrefs.ets` 快照扩字段:enableCoreMemory/enableShortTermMemory/enableLongTermMemory(默认 true,:152-154,仅显式 false 才关)+ memoryRecall(默认 {12,2000,false},:174;逐字段解析,缺失回默认)。
  - `MemoryStore.ets`(新):JSON 文件 `memory/memory_records.json` 持久化;数组序 = 插入序;getActiveRecords/touchMemories 实现 MemoryReadRepository(空 ids no-op,:150);突变串行队列;文件缺失/损坏 → 空库。
  - `ChatPage.ets`:loadMemorySystemBlocks(gate 取自 agentRuntime 快照,messages = currentMessages(当前会话,含刚入列 user))→ '' → 不进块(:727 isNotBlank 门);三处 deps 站点(send/regenerate/继续)接线。

### 不迁移登记
- **`memories: List<AssistantMemory>` 参数(GenerationHandler.kt:143/:254/:406)**:generateInternal 体内**零消费**(声明后从未读取,全仓唯一消费者路径是 memoryContextPrompt)→ Android 死参数,不迁移。AssistantMemory 桶(global/short_term/long_term 的 getMemories 面)随 memory_* 工具与设置面切片再评估。

### 测试
+12(chat HAR 909→921):编排 9(scopes 门序/部分开关/全关不触仓/rank 透传/touch 序/debug details/空召回仍 touch/record 映射/score 形状)+ DAO 纯函数 3(scope/archived/expiresAt 边界 ==now 排除/插入序/touch 命中与不改输入)。

### 残余(已登记 P1)
extraction(MemoryExtractor/CandidateFilter/Prompt)、dream 子系统、导出导入 frontmatter、memory_* 工具(memory 类)、写路径(addMemory/updateContent/archive/候选)、遥测 MemoryEvent 落库、设置面 memory UI(含 enableCoreMemory 消费的 AssistantMemory 桶)。

## D-085c:memory_* 工具四件 + 写路径纯函数 + entry 接线

**日期**: 2026-07-28
**来源**: `core/ai/tools/MemoryTools.kt`(全文 344 行)、`MemoryRepository.kt:84-153,207-340`(写路径)、`MemoryDAO.kt:16-17`、`ChatService.kt:2280,2345-2405`(接线)、`core/model/Assistant.kt:107-117`(AssistantMemory)

### 内容
- **HAR `builtin_memory_tools.ts`**(buildMemoryTools 全文):
  - memory_list(:42-85)— type 默认 all,require 集合校验(消息逐字);条目 toJson(:333-343)键序 id/type/scope/kind/content/expiresAt(仅非 null)/confidence/pinned/archived;type 字段 = memory.scope ifBlank 调用桶。
  - memory_write(:86-156)/memory_delete(:157-182)— needsApproval=true + allowsAutoApproval=false 逐字;delete payload {success:true,id}。
  - memory_tool(:183-331)— 描述 trimIndent 逐字(含弯引号示例);`Today is ${toLocalString(true)}` → deps.todayText 快照;create/edit/delete 分支,错误消息全逐字(含 `unknown action: X, must be one of [create, edit, delete]`)。
  - AssistantMemory serializer payload = JsonInstant(encodeDefaults=true + explicitNulls 默认 true,AppModule.kt:20)→ 全字段恒在声明序,expiresAt null → null 字面量;confidence Float 文本还原(整数值 '1.0',JS number 无 int/float 之分)。
  - 输入解析:kotlinx contentOrNull/intOrNull/floatOrNull/toLongOrNull 语义(数字↔文本互解,'123.5'→null,数组项 mapNotNull)。
- **HAR `memory_write.ts`**(写路径存储无关纯函数):bucket 常量/映射(bucketForScope;scopeForBucket else→long_term 逐字;kindForBucketAdd SHORT→PROJECT 否则 NOTE)、nextMemoryId(max+1,空库 1 = Room 自增)、appendMemoryRecord(:102-133,confidence coerce 0-1/supersedesIds distinct 保序/archived=false/createdAt=updatedAt=now)、updateMemoryRecordContent(:84-89,未命中 error 消息逐字)、deleteMemoryRecord(未命中 no-op = SQL DELETE 0 行)、memoryRecordToAssistantMemory(:218-227)、memoriesOfAssistant(DAO :16-17 桶匹配插入序)。
- **entry `MemoryStore.ets`** 扩写路径:addMemory/updateContent/deleteMemory/listMemoriesOfAssistant(全走 enqueueWrite 串行泛型队列,op 结果透传);getMemoryToolDeps(ChatService.kt:2345-2397 逐字)— onList 三桶 else→[]、onCreation source 非空白拼 '\nSource: '+addMemory(assistantId=memoryBucket(wire))、onUpdate/onDelete 直通。
- **ChatPage 接线**:工具数组中 memory_* 位于 local tools 之后/ConversationContextTools 之前(:2280 位次,MCP 未移植);三开关全关 → 空组(:2346-2351);todayText = Intl dateStyle:'medium'(toLocalString(true) 等价)。

### 测试
+14(chat HAR 921→935):memory_list 平铺/键序/expiresAt 省略/非法 type;memory_write 默认/审批门/serializer 形状逐字串/expiresAt 解析边界;memory_delete 门/payload/id 必填;memory_tool 描述逐字/create 全参/edit/delete/未知 action 全错误消息;写路径 bucket/id 自增/confidence 钳/distinct/update 抛错/delete no-op/toAssistantMemory;schema 键序与 required。

### 残余(已登记 P1)
候选(addCandidate/acceptCandidate)/事件遥测(addEvent/countEventsSince)→ 随 extraction 切片(D-085d);upsertRecord → dream 切片;addMemory(assistantId,content) 两参变体与助手级桶展示 UI → 设置面切片。

## D-085d:记忆抽取子系统(提示词/过滤器/编排)+ 候选与遥测事件持久化 + 触发接线

**日期**: 2026-07-28
**来源**: `core/memory/prompt/MemoryExtractionPrompt.kt`(全文)、`core/memory/extraction/MemoryCandidateFilter.kt`(全文)、`core/memory/extraction/MemoryExtractor.kt`(全文 292 行)、`core/memory/telemetry/MemoryEventLogger.kt`(全文)、`MemoryEventDAO.kt:21-22`/`MemoryCandidateDAO.kt`、`ChatService.kt:1494-1499`(触发)、`MemoryModels.kt:136-154`(MemoryWorkerSetting)

### 内容
- **HAR `memory_extraction_prompt.ts`**:build 逐字(消息块 message_id/role/text.take(4000) trimIndent;模板 Locale/JSON 骨架/Rules 九条/source ids/Conversation)。
- **HAR `memory_candidate_filter.ts`**:filter 逐字 — normalize(lowercase+Unicode 字母数字+take200)、拒绝门(sensitive||tooWeak(trim<12||conf<0.45)||duplicate(既有||本批))、reason 拼接序(原 reason→sensitive→low_value→duplicate,'; ')、拒绝副本 status=filtered、接受副本 kind NOTE→PROJECT。
- **HAR `memory_extractor.ts`**:
  - parseMemoryCandidates(:195-235)— 围栏剥离+首尾花括号截取+take(5)+content 空白跳过+confidence ?:0.55+explicitScope/Kind 标志;**Kotlin getter 保真**:根非对象/candidates 非数组/项非对象 → 抛错(非跳过),由编排 catch → EXTRACTION_FAILED。
  - resolveCandidateExpiresAt(:281-289)、shouldAutoWriteCandidate(:265-279,敏感短路→explicit 双门→0.72/0.85 阈值)、isDurableAutoWrite/autoWriteEventMessage(三分支逐字)。
  - runMemoryExtraction(:43-182 编排逐字)— 门序列五连(消息逐字:'Memory worker disabled.'/'Debounced.'/'Daily memory worker limit reached.'/'No memory worker model available.'/'Memory worker model provider not found.')、120s 去抖(模块级 Map,reset 测试钩子)、todayStart=now-now%86400000(UTC 日界逐字)、maxDailyRuns coerce≥1、started 事件 → takeLast(16)+prompt+generateText → parse → filter(对全量 active)→ rejected addCandidates / accepted 逐个 autoWrite 分流(addMemory+MEMORY_CREATED|DURABLE_MEMORY_CREATED vs addCandidate+CANDIDATE_CREATED(message=reason))、失败 EXTRACTION_FAILED(message+durationMs+messageCount)。
- **entry `MemoryStore.ets`**:IO 泛化 readJsonArray/writeJsonArray;候选(memory_candidates.json 追加,空 insertAll no-op)+ 事件(memory_events.json,message 截 2000 = toEntity:316)+ countEventsSince(event_type 匹配 && created_at >= after)+ getAllActiveRecords;getMemoryExtractionStoreOps 聚合。
- **entry `AgentRuntimePrefs.ets`**:memoryWorker 门子集 {enabled:true, extractionEnabled:true, maxDailyRuns:8}(MemoryModels.kt:137,143,152;仅显式 false 关)。
- **ChatPage**:maybeMemoryExtraction(conv, rt) — conversationHasPendingOrUnexecutedTools 门(:1494)+ 三处完成点接线(send/regenerate/继续);存储层故障 catch → hilog(不上浮)。

### 偏差登记
- **worker 模型解析**(:184-193,modelId/followCompressModel/compressModelId/chatModelId 任务模型链)→ chat 模型顶替(resolveChatProviderChoice;空 → no_model;no_provider 分支在鸿蒙不可达 — choice 捆绑 provider+model)。与 D-025 title/suggestion 同偏差类,任务模型设置面移植后闭合。
- **locale**:Locale.getDefault().displayName('中文 (中国)')→ systemLanguage()('zh-Hans-CN' 形态,无 displayName 等价)。
- **JSON 解析失败文本**:JS SyntaxError 消息(非 Kotlin 异常文本),仅入 EXTRACTION_FAILED 事件 message。

### 测试
+17(chat HAR 935→952):prompt 逐字(模板/消息块/take4000)、filter 三门+reason 序+normalize 符号大小写+kind 归一、parse 围栏/截取/take5/默认/explicit/三类抛错/candidates 缺失空、autoWrite 门序与阈值与事件文案、expiresAt 优先级、编排七例(五门各消息/去抖/配额 coerce/全链路分流/失败事件/rejected 批量)。

### 残余(已登记 P1)
dream 子系统(Planner/Applier/Scheduler/Notifier/PlanStore/DreamGate)、导出导入(FrontmatterCodec/ImportExportManager)、候选接受/忽略操作面(acceptCandidate/updateCandidate)、事件查看 UI、任务模型设置面(modelId/followCompressModel → 消偏差)。

## D-085e:记忆导出导入(Frontmatter 编解码 + ImportExportManager)

**日期**: 2026-07-28
**来源**: `core/memory/export/MemoryFrontmatterCodec.kt`(全文 78 行)、`MemoryImportExportManager.kt`(全文 116 行)、`MemoryRepository.kt:135-143`(upsertRecord)

### 内容
- **HAR `memory_frontmatter.ts`**:
  - encode(:12-29)— 键序逐字(id/kind/scope/confidence/created_at/updated_at/expires_at?/source_conversation_id?/source_message_ids/supersedes_ids/pinned/archived);quote = `\\`/`\"` 转义;confidence Float 文本(整数 → 'N.0');formatTime = 本地时区 ISO_OFFSET_DATE_TIME(手工拼组件+偏移,毫秒非零才带 .SSS;timeMs≤0 → now)。
  - decode(:31-59)— split('---', limit=3) 语义(第三段保留分隔符)、require ≥3(消息逐字)、行解析无冒号跳过/toMap 后值覆盖/值 trim('"') 首尾全去、parseInlineList(反转义顺序 `\"`→`"` 再 `\\`→`\` 全量)/parseIntList(-?\d+)、Instant.parse 失败抛错(Date.parse NaN → throw)、缺 created/updated → now、assistantId = bucketForScope(scope) 重导、confidence toFloatOrNull 严格整串校验 ?: 1。
- **HAR `memory_import_export.ts`**:exportTo(:16-56)— resolveRoot 嵌套规则、六 kind × memories|archive mkdirs、逐记录 .mem.md、manifest.json(map<String,String> 键序 version/exported_at/count/active_count/archived_count/event_count 值全字符串;exported_at = Instant.now().toString() 毫秒零省略小数)、events ndjson(MemoryEvent serializer 声明序全字段);importFrom(:58-77)— resolveExistingRoot(manifest 在→本目录/嵌套存在→嵌套/否则原样)、memories|archive 遍历 *.mem.md → decode → 首个 scope+kind+normalize 匹配 → upsertRecord(沿用 id 或 0);fileName(:79-91 日期_id_slug.mem.md,slug 归一/take48/空→memory)。
- **HAR `memory_write.ts`**:upsertMemoryRecord(:135-143 + toEntity:247-263)— id==0 insert(自增)/否则 update(updatedAt=now)/未命中 no-op(@Update 0 行,回传入参);实体映射(assistantId 空白→bucket、supersedes distinct、confidence 钳、createdAt≤0→now)。
- **entry `MemoryStore.ets`**:getRecentEvents(created_at DESC LIMIT)、upsertRecord(串行队列)、MemoryExportFs 的 fileIo 实现(递归 mkdir/listFile 遍历)、getMemoryImportExportDeps。**UI 触发(picker 选目录)随设置面 memory UI 切片(P1 已登记)。**

### 偏差登记
- walkTopDown 目录遍历序 = FS 实现序(Android 为 OS 目录序)— 仅影响多文件处理顺序,不影响导入结果集。

### 测试
+9(chat HAR 952→961):encode 键序/转义/Float 文本/可选行;decode 往返/缺分隔抛错/缺时间 now/非法 confidence/同键覆盖;fileName slug/48 截断/空→memory/resolveRoot;exportTo 目录/manifest 键序字符串值/ndjson 声明序/结果计数;importFrom 匹配沿用 id/全新 0/嵌套规则;upsertRecord 三路径+实体映射;事件 JSON null 显式。

### 至此 memory/ 目录 19 文件完成 14;残余:dream 子系统 5 文件(Planner/Applier/Scheduler/Notifier/PlanStore + DreamGate 12 行)= D-085f(P1)。

## D-085f — dream 子系统核心(Planner/Applier/Gate/Prompt)

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: MemoryDreamPlanner.kt(320)/MemoryDreamApplier.kt(189)/MemoryDreamPlanStore.kt(summary)/MemoryWorkerDreamGate.kt(12)/MemoryDreamPrompt.kt(全文)
- **决策**:
  1. `chat/memory_dream_prompt.ts`: buildMemoryDreamPrompt 逐字(模板 + `- #id [scope/kind] content` 行)。
  2. `chat/memory_dream.ts`: MemoryDreamPlan 模型 + makeMemoryDreamPlan/hasChanges(notes 不计)/summaryText(MemoryDreamPlanStore.kt:30-33 逐字);MemoryDreamWorkerGate 三谓词;planDreamMaintenance(过期归档/重复组 normalize+组排序 pinned>long_term>confidence>updatedAt+逐字 reason/promote 0.82+lastUsedAt 门/噪声候选/4 中文 notes,note 用 distinct 前 size Planner.kt:183);parseDreamModelPlanJson(fence 剥离+{} 子串+id 集校验+Kotlin getter 抛错+notes take240/take6+confidence ?:0.7);mergeDreamPlanWith(集合键去重 sorted-join,takes 12/24/48/48/12/12);runMemoryDreamPlan(local+模型 runCatching→null,merge,DREAM_PLANNED source merged/model/maintenance/none 逐字);dreamPlanOnlyApplicable(supersede 五门/merge managed+superseded 排除+managedCompare 重定向,duplicates 保 candidates 原序 Applier.kt:141-143/promote/archive 过滤);runMemoryDreamApply(无变更早退不打 DREAM_APPLIED,merge trim>=8 回退/promote '__long_term__'+expiresAt null/supersede firstNotNull 会话+distinct 列表/ignore 候选 status 'ignored',全部事件消息逐字)。
  3. entry: AgentRuntimePrefs memoryWorker += dreamMaintenanceEnabled(默认 true)/dreamModelEnabled(`raw?.dreamModelEnabled === true || raw?.dreamEnabled === true` 旧 Daydream 别名迁移,SettingsAggregator.kt:542-553);MemoryStore getPendingCandidates(status='pending',created_at DESC=反转)/getAllCandidates(DESC)/updateCandidate(updatedAt=Date.now(),MemoryRepository.kt:175)+ getMemoryDreamStoreOps 聚合。
- **偏差登记**:
  - resolveDaydreamModel(Planner.kt:102-120 任务模型链 daydreamModelId/daydreamFollowCompressModel/modelId/followCompressModel/chatModelId)→ deps.resolveDaydreamModel;鸿蒙任务模型设置未移植,entry 以 chat 模型顶替(D-085d 同类,任务模型设置面 P1 闭合)。
  - daydreamReasoningLevel(默认 HIGH)→ entry generateText 用 chat 默认 reasoning(登记)。
  - 模型/provider 空三种 null 返回合并为 unavailable(Android 无区分消费)。
  - JSON 解析失败文本 = JS SyntaxError(仅影响 runCatching 吞掉路径)。
- **未移植(P1 登记)**: MemoryDreamPlanStore 持久化(PersistedMemoryDreamPlan+DAO)、MemoryDreamScheduler/Worker/Notifier/RunCoordinator(WorkManager/notification 平台调度)、手动触发 UI、ChatPage 触发接线。
- **验证**: chat tsc 干净 + 991 测试(+30)/deepread tsc + 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-085g — dream plan 持久化 + 运行编排(PlanStore/Coordinator/Scheduler 纯逻辑/Worker)

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: MemoryDreamPlanStore.kt(143)/MemoryDreamPlanDAO.kt/MemoryDreamPlanEntity.kt/MemoryDreamRunCoordinator.kt(59)/MemoryDreamScheduler.kt(123,纯逻辑部分)/MemoryDreamWorker.kt(60)/MemoryDreamNotifier.kt(110,接口+文案常量)
- **决策**:
  1. `chat/memory_dream_plan_store.ts`: status/source 枚举 + 回退(未知→pending/manual);PersistedMemoryDreamPlan + summary getter;Entity 列形状;plan JSON 线格式(kotlinx encodeDefaults+explicitNulls,全字段声明序,mergedContent null 显式,枚举 wireName 字符串;解码缺省→声明默认,未知枚举值抛错——kotlinx 语义,不用 fromWireName 回退);DAO 端口(查询语义注释逐条)+ createMemoryDreamPlanStore(savePending/saveApplied 先无条件 dismissed 存量 pending,recordAutoRun 直插 dismissed/auto 不动存量,markApplied/markDismissed by id)。
  2. `chat/memory_dream_run.ts`: RunGate(worker 门+dreamMaxDailyRuns);Outcome 四值;MemoryDreamReviewNotifier 接口 + 通知文案/ID 常量(8201,EXTRA_OPEN_AGENT_MEMORY,failed take120/take500);runMemoryDreamRun(门关→cancel+disabled;自动日限额 今日 auto 计数>=max(1,dreamMaxDailyRuns)→cancel+auto_daily_limit;notifyRunning→plan→空:自动 recordAutoRun/手动不记录+cancel+empty;有变更→savePending(manual/auto)+notifyPendingReview);localDayStart 本地时区;dreamSyncShouldSchedule;computeDelayUntilNextNightWindowMs(夜窗 00:00–06:00,窗内 0/skip→明日 00:00,窗外→明日 00:00);WORK_NAME/MANUAL_RUN_NAME;runMemoryDreamWorkerOnce(成功 success;失败→DREAM_FAILED take(500)+notifyFailed 原文+failure;finally 非手动且未停且门开→重排 runCatching 吞错)。
  3. entry: prefs memoryWorker += runOnlyOnIdle/runOnlyOnCharging(默认 true)/dreamMaxDailyRuns(默认 1);MemoryStore memory_dream_plans.json + DAO JSON 等价(插入序数组,getPendingPlan=末条最大 createdAt pending)+ getMemoryDreamPlanStore + MemoryDreamNotifierImpl(hilog 输出,与 deepread NotificationNotifier 同策略,文案逐字)。
- **偏差登记**:
  - todayStart:ZoneId.systemDefault() → JS 本地时区等价(new Date setHours 0;语义一致);
  - WorkManager 约束(CONNECTED+batteryNotLow+idle)/unique-work REPLACE/夜窗自重排 → 鸿蒙平台调度适配层未实现(P1,设备 G-C 验证;纯延迟计算与门决策已落地);
  - runOnlyOnCharging:Android 调度器同样忽略(SettingAgentMemoryPage.kt:514 注释),仅为线格式忠实保留;
  - 通知投递:notificationManager API 26 SDK 模块冲突(deepread 同 TODO),当前 hilog(文案逐字,非空实现);真实 publish/cancel 待 DevEco 验证。
- **未移植(P1 登记)**: 鸿蒙 workScheduler 适配(夜窗调度环)/真实通知 publish/设置面手动触发 UI(runOnce)/pending plan 审查 UI(apply/dismiss 入口)。
- **验证**: chat tsc 干净 + 1006 测试(+15)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-086 — FilesRepository 跟踪(managed_files)+ upload 落盘记库接线

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: ManagedFileEntity.kt(32)/ManagedFileDAO.kt(36)/FilesRepository.kt(30)/FilesManager.kt(trackUploadFile :482-505/deleteChatFiles :263-301/FileFolders :649-656)
- **决策**:
  1. `chat/managed_files.ts`: ManagedFileEntity + makeManagedFileEntity + FileFolders 四常量 + ManagedFileDaoPort(查询语义逐条注释)+ createFilesRepository(insert 返回 copy(id=rowid),其余直通)+ trackUploadFile(已存在同 relativePath → 跳过;否则 insert folder='upload' createdAt=updatedAt=now)+ deleteChatFiles(file: 前缀过滤 → relativePath 须 'upload/' 开头 → Set 去重收集但文件删除按 URI 出现次数逐次 → 各自 runCatching 吞错 → 逐个 deleteByPath 吞错)。
  2. entry `ManagedFileStore.ets`: filesDir/managed_files.json({nextId, rows}) — Room 语义 JSON 等价:REPLACE(relative_path 唯一冲突删旧)+ AUTOINCREMENT 高水位 nextId 不复用/@Update 未命中 no-op/deleteBy* 返回行数/listByFolder created_at DESC;enqueueWrite 串行;缺失/损坏 → 空表(MemoryStore 同策略);getFilesRepository 单例。
  3. Base64ImageSupport: saveUploadImage 落盘后 trackUploadFile fire-and-forget(displayName 'image.png'/mimeType 'image/png'/sizeBytes=字节长,FilesManager.kt:200 逐字)。
- **偏差登记**:
  - trackUploadFile 失败:Android appScope.launch+runCatching 静默吞错 → 本实现 hilog.warn(可观测性增强,成功路径语义不变);
  - listByFolder Flow → 快照 Promise(观察 UI = P1,与 memory 各 Flow 同处理);
  - entry 严格 ArkTS:交叉类型工厂参数不接受对象字面量 → withId 显式构造(注释钉住)。
- **未移植(P1 登记)**: 会话删除文件清理(deleteChatFiles 接线依附 History 撤销窗流程,撤销窗未移植)/附件移除清理(ChatInputAttachments:130)/头像清理(AssistantVM:69/AssistantDetailVM:199,211)/syncFolder/getRelativePathInFilesDir 公共化/upload 库观察 UI/countChatFiles。
- **验证**: chat tsc 干净 + 1012 测试(+6)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-087 — 任务模型解析链(worker/daydream/compress/title/suggestion):消除 chat 模型顶替偏差

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: PreferencesStore.kt:479-481(resolveTaskChatModel)/MemoryExtractor.kt:185-195(worker 链)/MemoryDreamPlanner.kt:102-120(daydream 五分支)/AiAuxiliaryGenerator.kt:41-59,87(title/suggestion)/ConversationContextEngine.kt:689(compaction)/DefaultProviders.kt:95(DEFAULT_AUTO_MODEL_ID)
- **决策**:
  1. `chat/task_model.ts`: DEFAULT_AUTO_MODEL_ID('b7055fb4-39f9-4042-a88a-0d80ed76cf08' 逐字)+ TaskModelWorkerGate(modelId/followCompressModel/daydreamModelId/daydreamFollowCompressModel)+ 默认值(AUTO/true/AUTO/true)+ pickMemoryWorkerModelId(modelId≠AUTO → followCompress?compress:chat;否则 chat)+ memoryWorkerModelCandidates(=[pick, chat] 外层 ?: 链)+ pickDaydreamModelId(五分支 daydreamModelId→daydreamFollow?compress:null→modelId→follow?compress:null→chat)+ daydreamModelCandidates(=[pick, compress, chat])。
  2. entry prefs: agent_runtime memoryWorker += modelId/daydreamModelId(默认 AUTO,空串→AUTO 迁移)/followCompressModel/daydreamFollowCompressModel(默认 true)。
  3. AppContainer: buildProviderApi 自 resolveChatRuntime 抽出(三变体分发+keyRoulette 逐字);TITLE/SUGGESTION/COMPRESS_MODEL_KEY + loadTaskModelIds(空→AUTO)+ resolveCurrentChatModelUuid(selection→providers 反查 m.id,否则 AUTO)+ resolveTaskChatModelChoice(findModelByIdInProviders → {provider: effectiveProvider, model};未命中 → resolveChatProviderChoice 兜底 = resolveTaskChatModel 的 ?: getCurrentChatModel)+ resolveTaskModelChain(candidates 依序解析,首个非空)。
  4. ChatPage 接线:compressChoice 快照(aboutToAppear 加载)+ compressProvider(choice null→rt.api 默认参;否则 buildProviderApi+TextGenerationParams(model) 最小参,ConversationContextEngine.kt:689)仅接两处压缩点;maybeMemoryExtraction 闭包内缓存 extractionChoicePromise(memoryWorkerModelCandidates 链),resolveWorkerModel null→no_model,generateText 按 choice 建 api+最小参(MemoryExtractor.kt:121);maybeAutoTitle/maybeSuggestions 按 titleModelId/suggestionModelId 解析,null→throw(对齐 Android ?: return 静默跳过,由 runAutoTitle/runSuggestion 错误路径承接)+ reasoning OFF + customHeaders(record 化,trim 滤空名)+customBodies(AiAuxiliaryGenerator.kt:56-61 逐字,不再带 chat 温度/topP/maxTokens)。
- **偏差消除**: worker/daydream/title/suggestion/compress 的「chat 模型顶替」(D-025/D-026 裁剪+D-085d/D-085f 登记偏差类)全部闭合;title/suggestion 参数自 chat 全参改为 Android 忠实最小参(model+reasoning OFF+custom headers/bodies)——此前为偏差,现消除。
- **残余偏差登记**:
  - daydreamReasoningLevel 未移植(MemoryWorkerSetting 字段,Android 仅 planner 使用,P1 随 dream 手动触发 UI);
  - no_provider 解析支在 entry 不可达(resolveTaskChatModelChoice 捆绑 provider+model,findProvider 失败支无对应物;Android 该支返回 null → 任务跳过,鸿蒙 choice null 同效);
  - compressChoice/extractionChoice 为 aboutToAppear/首次调用快照,运行期改任务模型设置需重进页面生效(与 ocrSeed 快照同类,P1 设置面落地后改订阅)。
- **未移植(P1 登记)**: 任务模型设置面 UI(title/suggestion/compress/worker/daydream 模型 picker + followCompress 开关);daydream 链 entry 接线(runMemoryDreamRun 调用点待手动触发 UI)。
- **验证**: chat tsc 干净 + 1015 测试(+3)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-088 — Memory 页首片:dream 手动整理审核 + 候选记忆审核 + daydream 链 entry 接线

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: SettingAgentMemoryVM.kt(:30 阈值/:95-204 dream 与候选操作段)/MemoryRepository.kt:178-191(acceptCandidate 链)/SettingAgentMemoryPage.kt:884-906,912-991(候选区)/:993-1080(DreamReviewSection)/MemoryDreamPlanner.kt:74-100(daydream generateText)
- **决策**:
  1. `chat/memory_dream_ops.ts`(TDD 15 测试):LOW_CONFIDENCE_CANDIDATE_THRESHOLD=0.60;操作消息常量全量逐字(plan 三态/apply 两态/低置信两态/失败两函数,全角冒号);memoryDreamReviewSummaryText(页内版,'合并 N 组 · ...',与 domain summaryText 不同——忠实页实现);planMemoryDreamOp(plan → replacedPending=hasChanges&&存量 pending 非空 → savePending('manual')/no_changes,错误传播);applyMemoryDreamPlanOp(无 pending→no_plan;apply 后 hasChanges→markApplied 否则 markDismissed);dismissMemoryDreamPlanOp(无 pending→false);acceptMemoryCandidate(candidate→addMemory 七字段透传→status 'accepted';未找到 throw 'Memory candidate #id not found' 逐字);ignoreMemoryCandidate(未找到→false 不写);ignoreLowConfidenceMemoryCandidates(仅 pending&&<0.60,逐条 'ignored',非空打 candidate_ignored 事件——文案 Kotlin Float 插值 0.60f→"0.6" 逐字)。
  2. entry `MemoryDreamOps.ets`:loadMemoryDreamPlanOpDeps(prefs 快照 worker 门 + getMemoryDreamStoreOps + daydream 链——toTaskGate 四字段 → daydreamModelCandidates → resolveTaskModelChain,首次求值缓存,与 ChatPage extraction 闭包同策略;generateText = buildProviderApi + 最小参 model-only,choices.firstOrNull()?.message?.toText().orEmpty() 等价)+ loadMemoryDreamApplyOpDeps + getMemoryCandidateOpsDeps + loadPendingMemoryCandidates + logMemoryDreamOpFailure。
  3. entry `MemoryPage.ets`:DreamReviewSection 等价(标题/说明逐字/running LoadingProgress/摘要行/来源行(自动 Daydream/手动生成)/notes take(4) '• x'/merge take(5) '合并 #t <- ids'/supersede take(5) maxLines 3/空文案'还没有手动整理建议。'/三按钮 enabled 门:生成=!running,应用=!running&&plan?.hasChanges,清除=!running&&plan!=null)+ 候选区(SectionLabel/空文案/忽略低置信按钮仅 count>0 右对齐,文案'忽略低置信候选（N）'全角括号/候选卡 meta 行 [scope/kind] confidence %.2f + 高置信' · 建议人工审核'/content maxLines 4/reason 非空白 maxLines 2/接受+忽略)+ 操作反馈 toast。路由注册 + ChatListPage 头部'记忆'入口。
- **偏差/适配登记**:
  - daydreamReasoningLevel 未移植(延续 D-087 登记):generateText 最小参 model-only,Android 第二参 reasoningLevel=worker.daydreamReasoningLevel;P1 随任务模型设置面;
  - resolveDaydreamModel 的 model.findProvider 失败支 entry 不可达(choice 捆绑 provider+model,choice null ⇔ Android 任一 ?: return null,效果一致);
  - acceptCandidate 查询:Android candidateDAO.getCandidateById → 本实现 getAllCandidates find(同表按 id 唯一,查询语义等价);
  - 操作反馈:Android snackbar(operationMessage+consume)→ promptAction toast(瞬时反馈语义一致);
  - 接受/单条忽略失败:Android 无消息(runCatching 外层无)→ 本实现 hilog.warn(非静默,宪章条款)。
- **未移植(P1 登记)**: worker 开关组(本地/LLM 整理 Switch + runOnlyOnIdle + 不可运行提示)/记忆库三桶列表(records CRUD UI)/记忆事件区/导出导入按钮(UI 触发,domain 已备)/Agent Soul 卡/任务模型 picker 设置面/dream 自动调度环(workScheduler)。
- **验证**: chat tsc 干净 + 1030 测试(+15)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-089 — Memory 页二片:worker 开关组 + 记忆库三桶 CRUD + 事件日志

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: SettingAgentMemoryPage.kt:432-526(WorkerSubpage 开关组)/:570-687(Library 编排)/:1105-1154(Events/Maintenance)/:1183-1316(RecordsSection/MemoryItem)/:127-149(编辑对话框)/:254-269(删除确认)/SettingAgentMemoryVM.kt:68-93(updateAgentRuntime/add/update/delete)/values-zh/strings.xml:604-612,51,121,173,255-256(文案)
- **决策**:
  1. `AgentRuntimePrefs.ets` 写路径:MemoryWorkerTogglePatch + updateMemoryWorkerToggles(kv, patch)— 读-改-写 raw JSON 保留未识别字段(D-072 独立 KV 键适配延续,与 parseMemoryWorker 读侧互逆);dream 两开关写回时联动 dreamEnabled=false(Page :447-451/:475-479 旧别名迁移写回逐字);返回新快照。
  2. `MemoryStore.ets`:MemoryLibraryOps + getMemoryLibraryOps(listBucket=桶精确匹配插入序含 archived/addMemory/updateContent/deleteMemory/getRecentEvents(limit)— 复用 D-085c 写路径与 D-085 事件查询)。
  3. `MemoryPage.ets` 扩展:worker 开关卡(三 Switch + supporting 文案逐字——本地整理建议含'当前候选 N 条，最近事件 N 条。'实时计数;canRunDream=enabled&&isAnyDreamEnabled 门 → '立即运行不可用'+'请先开启本地整理或 LLM 整理建议。')置于 dream 审核卡上方(Android Worker 子页序);记忆库三桶(核心/短期/长期:标题+'?'信息(zh strings 逐字 AlertDialog)+ 空文案逐字 + MemoryItem #id/content maxLines 5/编辑/删除);编辑对话框(页内 overlay:标题'编辑记忆'+TextArea placeholder'记忆内容'+保存/取消 → updateContent);删除 AlertDialog('确认删除'+content+取消/确认 → deleteMemory 未命中 no-op);维护工具卡(事件日志 entry → overlay 对话框:take(6) 事件,type wireName + message 空白回退 'memory=X candidate=Y' 逐字,空文案'暂无记忆事件。')。
  4. **Android 行为忠实记录**: core 区 onAddMemory 被 RecordsSection 的 info 图标分支遮蔽(Page :1250-1255 if info else if add),新增入口在 Android UI 不可达 — 忠实移植,无新增按钮(VM addMemory :77-84 路径由 memory_* 工具覆盖,D-085c)。
- **偏差/适配登记**:
  - prefs 写回:Android settingsStore.update 全文档 kotlinx 重写 → 鸿蒙 agent_runtime 键 raw JSON 读-改-写(未知字段透传;已知键缺失即默认,wire 语义等价,D-072 适配延续);
  - 编辑/事件对话框:Android AlertDialog/EditStateContent → 页内 Stack overlay(交互等价);
  - 记忆库/事件观察:Android Flow stateIn → 操作后快照刷新(与全 entry 页面同策略,P1 观察);
  - 开关/CRUD 失败:Android 无用户可见反馈 → hilog.warn(非静默,宪章条款)。
- **未移植(P1 登记)**: Overview 导航页/Recall 开关页/Compaction 页(contextCompaction 开关)/AgentSoul 卡(agentSoulMarkdown 读写在 prefs 未移植)/记忆汇总卡('Amber 对你的了解',三组过滤器)/导入导出 UI(目录选择器,domain import-export 已备 D-085e)/toolbar '立即运行一次'(依附 workScheduler 调度环)/Compaction contextCompaction prefs 字段。
- **验证**: chat tsc 干净 + 1030 测试(无新增——entry 侧切片)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-090 — agents.md / Soul 全链:默认准则 + prompt 构建 + 生成注入 + AgentSoul 卡

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: GenerationPrompts.kt:14-28(buildAgentSoulPrompt)/PreferencesStore.kt:157,272-300(agentSoulMarkdown 字段+默认值)/GenerationHandler.kt:715-724(静态块首槽位)/SettingAgentMemoryPage.kt:792-908(AgentSoulCard+编辑对话框)/SettingAgentMemoryVM.kt:190-194/values-zh/strings.xml:583-588
- **背景**: Android 默认即将 DEFAULT_AGENT_SOUL_MARKDOWN 注入每次对话的 system 静态块首槽;鸿蒙 ChatTurnDeps.agentSoul(D-018 已留槽)从未传值 → 每会话缺一整段默认行为准则(core-loop 缺口,本切片补齐)。
- **决策**:
  1. `chat/agent_soul_prompt.ts`(TDD 5 测试):DEFAULT_AGENT_SOUL_MARKDOWN 逐字(Kotlin raw string 首尾换行属值,golden 锁定首尾);buildAgentSoulPrompt 逐字(trim → 空白 '';否则 '\n**AmberAgent Soul / agents.md**\n…<agents_md>\n{soul}\n</agents_md>\n')。默认文案引用 Android 专属工具(webview_open/terminal_*/officepro_* 等)——数据保真优先逐字,不改写。
  2. `AgentRuntimePrefs.ets`:snapshot/JSON += agentSoulMarkdown(缺省→DEFAULT;显式空串保留=合法不注入意图,kotlinx 缺省语义)+ updateAgentSoulMarkdown 写回(raw 读-改-写,D-089 同策略)。
  3. ChatPage 三处生成 deps(send :1430/regenerate :587/工具续跑 :1163)+= agentSoul: buildAgentSoulPrompt(snapshot.agentSoulMarkdown)——空白 → '' 被静态块 filter 自然滤除(GenerationHandler.kt:723 同语义)。
  4. MemoryPage AgentSoul 卡(zh strings 逐字:title/desc/空预览/编辑提示/编辑标题/label):卡片点击 → 编辑 overlay(TextArea + 保存/取消);预览 maxLines 4 + 圆角边框盒;保存 → updateAgentSoulMarkdown → 快照回读。
- **偏差/适配登记**: 编辑对话框 AlertDialog → 页内 overlay(D-089 同);保存失败 hilog.warn(Android 无反馈,非静默条款)。
- **未移植(P1 登记)**: 记忆汇总卡('Amber 对你的了解'三组过滤器)/Overview-Recall-Compaction 子页/导入导出 UI(目录选择器)/toolbar 立即运行(workScheduler)。
- **验证**: chat tsc 干净 + 1035 测试(+5)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-091 — contextCompaction 设置全链:prefs 六字段 + 生成 policy 接线 + Compaction 开关卡

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: PreferencesStore.kt:234-241(ContextCompactionSetting 六字段+默认)/:247-255(toCompactPolicy 直传)/SettingAgentMemoryPage.kt:528-568(CompactionSubpage 两 Switch)/values-zh/strings.xml:599-603
- **背景**: 鸿蒙 compact 引擎(D-054/055)已就绪,但 policy 在两处生成点硬编码(enabled=true/notifyOnly=false/ratio 默认/keepRecentTurns=8)——恰好等于 Android 默认值故无行为偏差,但用户开关不可达(改了也不生效=隐性断链)。
- **决策**:
  1. `AgentRuntimePrefs.ets`:ContextCompactionSnapshot/Json 六字段 + 默认(true/false/0.70/0.85/8/2000,:235-240)+ parse(缺省/非数 → 各自默认;ratio 不钳制,Android 同)+ updateContextCompactionToggles(enabled/notifyOnly 写回,raw 读-改-写 D-089 策略)。
  2. ChatPage 两处 policy(buildPrepareContext :969 自动路径 + createConversationContextTools :1030 工具路径)改自快照字段直传(toCompactPolicy :247-255 同口径);快照加载前 = Android 默认值,无窗口期偏差。
  3. MemoryPage compactionCard(zh strings 逐字;首项 supporting = desc + '\n' + 默认行,对齐 Android 同 item 双 Text 拼接)置于 worker 开关卡后(Android 子页序 Worker→Compaction)。
- **偏差/适配登记**: 无新增(ratio/maxSummaryTokens 无 UI,Android 同——仅 JSON 持有,线格式兼容)。
- **未移植(P1 登记)**: 记忆汇总卡/Overview-Recall 子页/导入导出 UI/toolbar 立即运行(workScheduler)。
- **验证**: chat tsc 干净 + 1035 测试(无新增——entry 侧)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-092 — 记忆开关(Recall)全链:五布尔 + memoryRecall.debug 写回 + 开关卡

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: SettingAgentMemoryPage.kt:359-431(RecallSubpage 六 Switch)/values-zh/strings.xml:589-598/SettingAgentMemoryVM.kt:68-74(updateAgentRuntime)/PreferencesStore.kt:152-156(默认)
- **背景**: enableCoreMemory/ShortTerm/LongTerm/RecentChatsReference/TimeReminder 与 memoryRecall.debug 的读侧与生成消费早已落地(D-072/D-077b/D-085b),但无写路径——用户不可改(隐性断链,D-091 同类)。
- **决策**:
  1. `AgentRuntimePrefs.ets`:RecallTogglePatch + updateRecallToggles(五布尔根字段直写;memoryRecallDebug 走嵌套 memoryRecall 对象,保留 maxItems/maxPromptChars——Page :420-424 嵌套 copy 语义;raw 读-改-写 D-089 策略)。
  2. MemoryPage recallCard 六 Switch(zh strings 逐字;末项'选择性召回' supporting 动态插值'每轮最多 N 条、M 字符，不再全量注入。'逐字,Switch 绑 memoryRecall.debug——Android 该 Switch 即 debug 开关,忠实移植)置于 Soul 卡后(Android 子页序 Recall→Worker→Compaction)。
- **偏差/适配登记**: 无新增(六 Switch 合并为单 handler + Record patch,语义逐条等价)。
- **未移植(P1 登记)**: 记忆汇总卡/Overview 子页导航/导入导出 UI/toolbar 立即运行(workScheduler)/memoryRecall maxItems/maxPromptChars 编辑(Android 亦无 UI,仅 JSON 持有)。
- **验证**: chat tsc 干净 + 1035 测试(无新增——entry 侧)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-093 — 记忆汇总卡('Amber 对你的了解'三组过滤器)

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: SettingAgentMemoryPage.kt:793-884(MemorySummarySection/MemorySummaryGroup)/:907-909(isSummarySensitive)
- **决策**:
  1. `chat/memory_summary.ts`(TDD 5 测试):memorySummaryGroups(core, longTerm, shortTerm)→ {stable, longTermProjects, currentProjects}——stable=(core+longTerm) 串联过滤(!archived && !isSensitiveMemoryContent && (scope==core || kind∈{user,feedback,routine} || pinned))后 distinctBy(id) 首次保序;longTermProjects=longTerm 过滤 kind∈{project,reference};currentProjects=shortTerm 过滤 kind==project(敏感判定复用 D-085b isSensitiveMemoryContent)。
  2. MemoryPage summaryCard 置于记忆库三桶前(Android Library 子页顶序):标题'Amber 对你的了解';三组(稳定偏好/长期项目/当前短期事项)各 take(6),行文本 '#id [scope/kind] content' maxLines 2 点击 → startEditMemory(同记录编辑对话框);三组全空 → '暂无可汇总的正式记忆。'。分组在三桶 Promise.all 加载后一次性计算入 @State(渲染期不重复过滤)。
- **偏差/适配登记**: 无新增。
- **未移植(P1 登记)**: Overview 子页导航/导入导出 UI(目录选择器)/toolbar 立即运行(workScheduler)。
- **验证**: chat tsc 干净 + 1040 测试(+5)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-094 — 任务模型设置面:五行 picker + title/suggestion/compress 提示词模板全链

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: SettingModelPage.kt:433-700(title :433-470/suggestion :472-509/compress :556-592/worker :594-645/daydream :647-700;ModelPickerRow :798;ModelPromptSheet :845)/ChatPrefs.kt:71-84,93-102/PreferencesKeys.kt:29-37
- **背景**: D-087 落地了任务模型解析链与生成消费,但 (a) title/suggestion/compress 三 KV 键无写路径;(b) title/suggestion/compress 提示词模板虽有 domain hook(auto_title.ts:60/suggestion.ts:57/context_engine.ts:485)却未接线——用户自定义提示词不可写不可用(D-025/D-026 登记偏差);(c) worker/daydream modelId/followCompressModel 无写路径;(d) 无设置面 UI。
- **决策**:
  1. `AppContainer.ets`:saveTaskModelId(key, uuid)——onClear 写 DEFAULT_AUTO_MODEL_ID 字面量(ChatPrefs.kt:94-96 writeTo 直存 uuid;读侧空/缺失亦归一 AUTO,双语义兼容);loadTaskPrompts/saveTaskPrompt('title_prompt'/'suggestion_prompt'/'compress_prompt',读 = 键缺失 → DEFAULT_*、已存值含空串逐字,ChatPrefs.kt:81-84 同语义);键常量导出供页面层。
  2. `AgentRuntimePrefs.ets`:updateMemoryWorkerModels(raw 读-改-写,D-089 策略)——onClear → modelId=AUTO+followCompressModel=true(:619-630);onSelect → modelId=id+follow=false(:631-641);daydream 同构(:678-697)。
  3. ChatPage 接线:maybeAutoTitle/maybeSuggestions 调用时新取 loadTaskPrompts → deps.template(关闭 D-025/D-026 提示词自定义偏差);buildPrepareContext 闭包(async 化)内新取 compressPrompt → prepareContext deps(context_engine.ts:485 hook 接线);compactEngineDeps 同步构建位用 aboutToAppear 快照 taskPromptsCache(未就绪 → DEFAULT_COMPRESS_PROMPT,与 deps.compressPrompt ?? DEFAULT 同义)。
  4. 新页面 ChatTaskModelsPage(五行卡片:标题/建议/压缩 follows = findModelById==null → '跟随当前聊天模型'+'当前使用：X';worker/daydream follows = followCompressModel && findById==null → '跟随压缩模型'+'当前跟随压缩模型：X',zh strings 逐字;提示词 overlay 编辑器 = ModelPromptSheet 等价:变量 hint 逐字 + '重置为默认' → DEFAULT_*)+ ChatTaskModelPickerPage(按 m.id uuid 列选,区别于 ChatModelPickerPage 的 (providerId, modelId) 对);ChatListPage 头部 '任务模型' 入口;main_pages.json 注册两页。
- **偏差/适配登记**: 提示词编辑改'保存'按钮提交(Android onPromptChange 逐字符直写,交互档);模型选择改独立 picker 页跳转(Android inline ModelSelector 下拉,交互档)。
- **关闭**: D-087 P1「任务模型设置面 UI」;D-025/D-026 titlePrompt/suggestionPrompt 自定义偏差;compressPrompt 自定义(domain hook 已接线)。
- **未移植(P1 登记)**: daydreamReasoningLevel(ModelPromptSheet reasoning 选择器)/OCR 设置面(ocr_model/ocr_prompt)/生图模型与生图提示词设置(imageGenerationModelId/ImagePromptInjectionSheet)/模型组默认规则面(modelGroupSessionDefaults)。
- **验证**: chat tsc 干净 + 1040 测试(无新增——entry 侧)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-095 — 记忆导入导出 UI(Frontmatter portability 对话框全链)

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: SettingAgentMemoryPage.kt:237-244(onExport/onImport 触发)/:650-690(导入导出对话框)/:1146-1163(MemoryMaintenanceSection)/:1166-1191(MemoryPortabilitySection)/SettingAgentMemoryVM.kt:207-237(exportMemories/importMemories)
- **背景**: D-085e 已落地导出导入 domain(exportMemoriesTo/importMemoriesFrom)与 entry 存储面(getMemoryImportExportDeps + fileIo FS 实现),但无 UI 触发——端口不可达(隐性断链)。
- **决策**:
  1. MemoryPage maintenanceCard 增'导入导出'行(Page :1152-1156 序:导入导出 → 事件日志;supporting 'Frontmatter 备份与恢复。' 逐字)→ portabilityOverlay(:650-690 对话框等价;两行标题/supporting 逐字 :1173-1183;导出/导入按钮 enabled=!running,运行中灰色)。
  2. 处理器:onExportMemories → exportMemoriesTo(filesDir, getMemoryImportExportDeps()),成功 toast '已导出 N 条记忆到 {root}'(:215 逐字)/失败 '导出失败：{message}'(:217,全角冒号);onImportMemories → importMemoriesFrom(`${filesDir}/AmberAgentMemory`, deps)(:243 File(baseDir, "AmberAgentMemory") 逐字),成功 '已导入 N 条记忆' + refresh/失败 '导入失败：{message}';双 running 门(VM _memoryTaskRunning)。
- **偏差/适配登记**: baseDir = ctx.filesDir(Android getExternalFilesDir(null) ?: filesDir 的回退分支——鸿蒙 NEXT 沙箱无外部应用目录等价物,目录选择器 picker 未落地,P1 登记);导入成功额外 refresh 三桶(UI 一致性,Android 靠 Flow 观察自动刷新——观察 Flow P1 已登记,此为等价效果)。
- **关闭**: D-088 P1「导出导入 UI」。
- **未移植(P1 登记)**: 外部目录 picker 导出(DocumentViewPicker)/记忆观察 Flow→订阅。
- **验证**: chat tsc 干净 + 1040 测试(无新增——entry 侧)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-096 — 会话删除撤销窗(HistoryPage undo)+ 会话级文件清理接线

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: HistoryPage.kt:124-153(SwipeableConversationItem onDelete + snackbar)/HistoryVM.kt:41-68(delete deferCleanup/restore/purge)/ConversationRepository.kt:400-432(deleteConversation deferCleanup + cleanupDeletedConversation)/Conversation.kt:33-37,172-184(files getter + collectAllParts/fileUri)/FilesManager.kt:263-301,512-518(deleteChatFiles + getRelativePathInFilesDir)
- **背景**: harmony 会话删除是 AlertDialog 确认后立即物理删除(D-022 适配),无撤销且无文件清理(PD-007 残余);Android HistoryPage = 滑动即删 + 4s 撤销 snackbar,超时才 purge 文件。
- **决策**:
  1. domain `conversationFileUris`(TDD 3 测试):Conversation.files getter 逐字——messageNodes 全 message 全 parts + Tool.output 递归展开(collectAllParts :172-173),Image/Video/Audio/Document 且 url 'file://' 前缀(fileUri :178-184),不去重(列表语义,去重在 deleteChatFiles 的 Set)。
  2. entry ManagedFileStore:getDeleteChatFilesDeps(getRelativePathInFilesDir = file:// 去前缀 + filesDir 前缀比较;canonical 简化登记)+ cleanupDeletedConversation(ConversationRepository.kt:425-432;favoriteDAO 无 favorites 面/deleteChatImagesDir 依附 generate_image,均未移植登记)。
  3. ChatListPage 删除改撤销窗:滑动删除 → getById 取完整会话 → repo.delete(deferCleanup 语义:仅删会话)→ 4s snackbar('对话已删除'/'撤销',strings.xml:308/:320 逐字,SnackbarDuration.Short);撤销 → repo.save(full) 恢复(insertConversation 等价:upsert conv+nodes);超时/被新删除顶替 → cleanupDeletedConversation purge(deleteChatFiles,PD-007 会话级清理接线落地)。
- **偏差/适配登记**: D-022 的 AlertDialog 确认删除为当时适配偏差,现按 Android 原语义(滑动即删+可撤销)替代——撤销窗存在期数据可恢复,窗后 purge 与 Android 逐字一致;snackbar 视觉为自绘底条(非 SnackbarHost 组件,交互档)。
- **关闭**: D-029 登记「撤销窗 (History undo)」;PD-007 会话删除清理接线。
- **未移植(P1 登记)**: checkFilesDelete(ChatService.kt:1903-1912 save 挂钩的消息/附件移除清理——挂点设计待决,Android 在 ChatService 层,harmony 无该层)/composer removePart 清理(ChatInputAttachments.kt:126-131)/checkUserAvatarDelete(ChatVM.kt:213-221)/delete-all 会话(HistoryPage toolbar)/FTS deleteConversation(依附 FTS 索引)。
- **验证**: chat tsc 干净 + 1043 测试(+3)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-097 — checkFilesDelete 持久化挂钩(消息/附件移除文件清理)

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: ChatService.kt:1868-1877(updateConversation checkDeletedFiles 默认 true)/:1903-1912(checkFilesDelete diff)/ConversationAccess.kt:13(旗标默认)
- **背景**: D-096 落地会话级 purge,但消息删除(D-029)/编辑(D-031)/分支再生等"会话内附件移除"无文件清理(PD-007 残余);Android 在会话状态更新时 diff files → deleteChatFiles(removed)。
- **决策**:
  1. domain `removedFileUris`(TDD 3 测试):oldFiles.filter { newFiles.none == it } 逐字——不去重,保持 old 序。
  2. ChatRdbRepository.save 挂钩:持久化前 getById 取库内旧态 → removedFileUris(conversationFileUris(old), conversationFileUris(conv)) → 非空 → hilog.warn(:1911 Log.w 等价,uri 为 uuid 文件名不含消息内容)+ deleteChatFiles fire-and-forget(FilesManager appScope.launch 语义);旧不存在(新会话/撤销恢复/fork)→ 跳过。
- **偏差/适配登记**: 挂点为持久化 save(Android 在 ChatService 内存态 updateConversation;harmony 无 session 层,ChatPage 保存即持久化,流式中间态不落库 → Android false 旗标位自然不触发,save 挂钩 = Android 终态 diff 的最近等价);每次 save 多一次 getById 全量读(Android 为内存比较,性能档);memory 回退分支同样生效(getById 自带回退)。
- **关闭**: PD-007 附件移除清理接线(消息级)。
- **未移植(P1 登记)**: composer removePart 清理(ChatInputAttachments.kt:126-131,依附输入附件移除 UI)/checkUserAvatarDelete(ChatVM.kt:213-221,依附头像设置面)/window 合并(mergeConversationWindowIntoFull,依附分页加载——harmony 恒全量,N/A)。
- **验证**: chat tsc 干净 + 1046 测试(+3)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-098 — OCR 设置面(ocr_model/ocr_prompt 写路径 + 视觉识别行)

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: SettingModelPage.kt:511-553(DefaultOcrModelSetting)/ChatPrefs.kt:80,83,98,102/PreferencesKeys.kt:34-35/values-zh/strings.xml:913-915
- **背景**: D-071b 落地 OCR 读侧与三路径生成接线,但 ocr_model/ocr_prompt 无写路径无 UI——视觉回退模型与提示词用户不可配(PD-006 残余「设置面 OCR UI」)。
- **决策**:
  1. OcrSupport.ets:saveOcrModelId/saveOcrPrompt(kv.put 直存,ChatPrefs writeTo 语义;OCR 行无 onClear——Android allowClear=false)。
  2. ChatTaskModelPickerPage 增 task 'ocr':按 preferredInputModality=IMAGE 过滤(:533,inputModalities 含 'image' 才列出);当前选中 = getOcrModelId(未配置 = random uuid → 无选中行,:80 逐字);pick → saveOcrModelId。
  3. ChatTaskModelsPage 增'视觉识别模型'卡(desc strings.xml:914 逐字;无清除按钮;未配置显示'未配置')+ 提示词 overlay 扩展:draft = resolveVisionRecognitionPrompt(ocrPrompt)(:543 逐字),vars '变量：图片数据：{images}'(:915 逐字),reset → DEFAULT_OCR_PROMPT(:547-549),保存 → saveOcrPrompt。
- **偏差/适配登记**: VisionModelHealthChecker 健康探测(:517-525,desc '· {label}' 后缀)未移植——真实网络探测,依附设备档验证,P1 登记;未配置态显示'未配置'(Android ModelSelector 空标签,显示档)。
- **关闭**: PD-006 残余「设置面 OCR UI」。
- **未移植(P1 登记)**: VisionModelHealthChecker 探测/生成守卫(PD-006 其余残余)。
- **验证**: chat tsc 干净 + 1046 测试(无新增——entry 侧)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-099 — 搜索设置面 UI(enableWebSearch 键接线 + 服务 CRUD/排序/启用)

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: SettingSearchPage.kt(全文)/SettingSearchListCards.kt(:96-176 hero;:267-326 内置六行;:352-372 配置行;:551-620 通用选项+stepper;:726-778 推荐组合;:804-811 resolveSelectedSearchIndex)/SettingSearchServiceEditorSheet.kt(:46-152 结构;per-type 字段 :155-933)/ChatPrefs.kt:66/PreferencesKeys.kt:26/values-zh/strings.xml:1005-1037
- **背景**: D-070 落地 SearchPrefs 持久化与工具接线,但搜索无设置面:enableWebSearch 主开关不可配(D-070 过渡偏差:harmony 硬默认 true,Android `== true` 缺失 false),六内置开关/服务 CRUD/排序/resultSize 均不可配(审计行 52 P1「搜索设置面 UI」)。
- **决策**:
  1. domain 键接线(TDD:red → green 1047):SEARCH_PREFS_KEYS += 'enable_web_search'(PreferencesKeys.kt:26);loadSearchPrefs 读 `=== 'true'`(== true 语义,区别于内置 `!= false`);saveSearchPrefs 逐字写 11 键;defaultSearchPrefs.enableWebSearch false——**关闭 D-070 硬默认 true 过渡偏差**。
  2. ChatSearchSettingsPage:hero('Agent 网络搜索'+desc+主开关;enabledCount = 服务启用数+六内置 true 数,total = services+6,`${enabled}/${total} 个服务已启用 · 多源召回` 逐字)/内置免费源六行(zh 串逐字,Android 序 jina→duckduckgo→bing→wikipedia→hackernews→webview)/已配置行(rank+上移下移+开关+› 点击编辑)/通用选项 resultSize stepper(clamp 1..50)/推荐组合三行(逐字)。
  3. 编辑器 overlay:标题'添加提供商'/'编辑',confirm'添加提供商'/'保存';类型选择 = makeSearchServiceOptions(type) 全新默认(新 id,primaryConstructor.callBy 等价);能力 tag 搜索/抓取(getSearchService().scrapingParameters != null);per-type 字段逐字(apiKey 15 类;depth 分段 tavily basic|advanced / linkup|amber_agent standard|deep;searxng 五字段;perplexity maxTokens/maxTokensPerPage toIntOrNull;jina searchUrl/scrapeUrl trim;bocha summary 开关;grok model/customUrl/systemPrompt;bing_local 无字段)。
  4. CRUD 语义逐字:新增 confirm → prepend 服务 + enabledIds prepend distinct + selected=0;编辑 → id 定位(失败回 target.index)替换,曾启用 → enabledIds 换 id distinct;删除 → guard size<=1(按钮隐),resolveSelectedSearchIndex(selectedId takeUnless 删除项),enabledIds filterNot;move → 边界守卫 + 交换 + selectedId 重解析;服务开关 → distinct/filterNot。
  5. updateSearchPrefs JSON 相等短路 → 所有 transform 经 cloneSearchSettings 深克隆(arkts-no-spread;不得原地改同一对象)。
- **偏差/适配登记**: 拖拽排序 → 上移/下移 按钮(ArkUI 无现成拖拽列表;move 语义逐字;trailing '拖动调整优先级' → '调整优先级');ModalBottomSheet → 全屏 overlay(同 D-094 overlay 先例);StepperNumberField 直输 → 仅 −/＋ 按钮(clamp 保留);每 provider Description() composable 未移植(P1 既有登记);perplexity 输入解析 toIntOrNull → 整串正则+parseInt(语义等价)。
- **关闭**: 审计行 52 P1「搜索设置面 UI」(服务 CRUD/开关部分);D-070 enableWebSearch 默认偏差。
- **未移植(P1 登记)**: webview_search_open 工具(依附 WebView 面)/搜索服务 Description composables/拖拽排序手势。
- **验证**: chat tsc 干净 + 1047 测试(+1 键接线)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-100 — 提示词注入写路径(序列化 + mode_injections/lorebooks save/update)

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: core/model/.../Assistant.kt:144-233(模型声明序/默认值/SerialName)/ExtensionPrefs.kt:49-56,58-90,92-113(update 短路/readFrom/writeTo)/core/agent-utils/.../Json.kt:12-19(JsonInstant 配置)/PreferencesKeys.kt:74-75(冻结键)
- **背景**: D-075 落地注入 domain + 读路径 + ChatPage 接线,但无写路径无 UI——mode_injections/lorebooks 用户不可配(「动态 system prompt blocks」P1 残余);D-075 读侧逐字段 ?? 合并比 kotlinx 宽松(类型错不丢)。
- **决策**:
  1. chat HAR injection_serialize(TDD 7 测试):modeInjection/regexInjection/lorebook toJson(声明序逐字 + encodeDefaults 全量;无 type 判别——存储为具体类型列表)/fromJson(kotlinx 容错语义:缺字段 → 默认,id 缺失生成(Uuid.random() 默认);未知键忽略;类型错/枚举非法/坏 JSON → 整体 null)+ encode/decode 列表对。
  2. ExtensionPrefs.ets 重写:load* 改走 decode ?? emptyList(与 readFrom 逐字;**收紧 D-075 宽松合并**,登记);新增 save*(writeTo 全量)+ update*(:49-56 短路,JSON 串相等 = data class 相等,同 D-070 登记)。
  3. 核实:DEFAULT_MODE_INJECTIONS(Learning Mode,b87eaf16)在当前 Android 读路径为 vestigial——composeRawSettings(:348)恒以 ext.modeInjections(缺失 → [])覆盖 Settings 默认,无 seed 路径 → 不移植,登记。
- **偏差/适配登记**: data class 相等 → JSON 串相等(update 短路);D-075 读侧宽松合并被 kotlinx 严格语义替代(类型错条目由「部分默认填充」变「整体 []」——Android decodeJsonOrNull 行为)。
- **关闭**: 「动态 system prompt blocks」写路径前置;为 D-101 管理面/D-102 助手链接面供数据。
- **未移植(P1 登记)**: PromptPage 管理 CRUD UI(D-101)/AssistantExtensionsPage 链接 UI + quickMessages/skills 两 tab(D-102)/注入导入导出文件 picker/拖拽排序手势。
- **验证**: chat tsc 干净 + 1054 测试(+7)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-101 — 提示词注入管理面(ChatPromptsPage:模式注入/世界书 CRUD)

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: PromptPage.kt(全文 1176 行)/values-zh/strings.xml:417-459(label 逐字)
- **背景**: D-075 注入 domain + D-100 写路径已备,但无管理 UI——模式注入/世界书用户不可建不可编(「动态 system prompt blocks」P1 残余的管理面部分)。
- **决策**:
  1. ChatPromptsPage:底部二 tab(NavigationBar 适配:模式注入/世界书);列表卡逐字(模式注入:name 空 → '未命名' + position/priority '优先级：%d'/'已禁用' tag;世界书:name 空 → '未命名Lorebook' + desc 一行 + '%d 条目'/'已禁用' tag);空态 '暂无模式注入'/'暂无世界书' + '点击下方按钮添加'。
  2. useEditState confirm 语义逐字:id 定位 → 替换,否则 append(列表与条目两级同);delete = list - item;新增 = makeModeInjection()/makeLorebook()/makeRegexInjection() 全默认新 id(Android 默认构造等价)。
  3. 编辑器 overlay:模式注入(name/启用/优先级/注入位置五值/AT_DEPTH→注入深度/注入角色二值/注入内容);世界书(name/描述/启用/条目列表 '条目（%d）' + '＋ 添加条目' + 条目卡 name 空 → '未命名条目'/'关键词：k1, k2'/'已禁用');条目编辑二级 overlay(全字段 + 关键词 chips ✕ 移除 + 新关键词 isNotBlank 守卫;**canSave = keywords 非空 || constantActive**,不可点时灰色)。
  4. int 输入:toIntOrNull()?.let 语义 = 草稿串 + 确认时解析失败回退最后有效值(整串正则校验)。
  5. 入口:ChatAssistantsPage 头部 '提示词注入' 链接;main_pages.json 注册。
- **偏差/适配登记**: HorizontalPager+NavigationBar → 底部二 tab 行;长按拖拽 → 上移/下移 按钮(D-099 先例);SwipeToDismiss → 卡内'删除'按钮;ModalBottomSheet/AlertDialog → 全屏 overlay(D-094 先例);导入/导出文件 picker + ExportDialog 未移植(外部目录 picker 未移植先例);tag 视觉简化(色边小标,交互档)。
- **关闭**: 「动态 system prompt blocks」管理面(模式注入/世界书 CRUD 全链:存储 → 管理 UI → D-075 运行注入)。
- **未移植(P1 登记)**: AssistantExtensionsPage 链接 UI(D-102)/quickMessages/skills 两 tab/注入导入导出 picker/拖拽手势。
- **验证**: chat tsc 干净 + 1054 测试(无新增——entry 侧)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-102 — 助手扩展链接面(模式注入/世界书挂接 assistant)

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: AssistantExtensionsPage.kt(全文 218 行)/ExtensionContent.kt:35-91,168-194/values-zh/strings.xml:18-28,284-285/ChatInputAttachments.kt:126-131 + ChatInputState.kt:130-147(removePart/shouldDeleteFileOnRemove 核实)
- **背景**: D-075(注入 domain+运行接线)+ D-100(写路径)+ D-101(管理 CRUD)已备,缺助手侧链接面——assistant.modeInjectionIds/lorebookIds 用户不可配,注入链对最终用户仍不可达。
- **决策**:
  1. ChatAssistantExtensionsPage(router params assistantId):顶部二 tab(模式注入/世界书);空态消息逐字 + '前往提示词管理' 按钮(→ ChatPromptsPage,D-101 已落地);非空 → 开关行(name 空 → '未命名'/'未命名Lorebook';lorebook desc 非空显示);onToggle 逐字:checked → ids + id(Set 语义去重),否则 ids - id → patchAssistant + saveAssistants 落库。
  2. 入口:ChatAssistantsPage 行操作 '扩展'(对话/复制之间);main_pages.json 注册。
  3. 核实登记两则:
     - **composer removePart 清理 N/A**:Android shouldDeleteFileOnRemove 仅对 file: 前缀 pending 附件删文件;harmony composer pendingImages 恒为 data: URL(物化发生在发送时 Base64ImageToLocalFileTransformer),pending 态不存在 file:// 附件 → 该钩子在本架构下永假,按 N/A 登记(禁止移植为空转实现)。
     - **DEFAULT_MODE_INJECTIONS vestigial**(D-100 已登,此处闭环):管理面无学习模式预置与 Android 当前读路径一致。
- **偏差/适配登记**: 4 tab 中 quickMessages(存储未移植)/skills(SkillManager 未移植)两 tab 不移植 → 本页仅 2 tab;HorizontalPager+SecondaryTabRow → 顶部二 tab 行;Set<Uuid> ± id → string[] Set 语义。
- **关闭**: 「动态 system prompt blocks」全链(存储 → 管理 CRUD → 助手挂接 → 运行注入 D-075)用户可达;审计行「动态 system prompt blocks」。
- **未移植(P1 登记)**: quickMessages 子系统/skills 子系统(两 tab)/注入导入导出 picker。
- **验证**: chat tsc 干净 + 1054 测试(无新增——entry 侧)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-103 — daydreamReasoningLevel 数据链(默认 HIGH → dream Planner generateText)

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: MemoryModels.kt:142(MemoryWorkerSetting.daydreamReasoningLevel 默认 HIGH)/MemoryDreamPlanner.kt:94-97(TextGenerationParams 第二参)/Reasoning.kt:7-30(七值 SerialName 小写)
- **背景**: D-085f/D-087 登记的残余:dream Planner 的 generateText 以最小参 model-only 调用,Android 第二参 reasoningLevel=worker.daydreamReasoningLevel 未移植。
- **决策**:
  1. AgentRuntimePrefs:MemoryWorkerGateSnapshot/MemoryWorkerJson += daydreamReasoningLevel;解析 = 合法七值('off'/'auto'/'low'/'medium'/'high'/'xhigh'/'max')原样,缺失/非法 → 'high'(:142 默认;kotlinx 非法枚举整体 rescue → AgentRuntimeSetting() 同样 HIGH,字段宽容等价);默认快照 += 'high'。
  2. MemoryDreamOps.generateText:makeTextGenerationParams += reasoningLevel: worker.daydreamReasoningLevel(Planner.kt:95 逐字)。
  3. 核实:Android 无设置 UI 暴露该字段(全仓仅 Planner.kt:95 消费)→ 仅数据链,无设置面;线格式经 updateMemoryWorkerModels RMW 自然透传(未知字段保留)。
- **偏差/适配登记**: 无新增(字段宽容解析延续既有先例)。
- **关闭**: D-087 登记「daydreamReasoningLevel 未移植」。
- **未移植(P1 登记)**: 无(该字段链完整)。
- **验证**: chat tsc 干净 + 1054 测试(无新增——entry 侧)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-104 — 权限与批准设置面(autoApprove 双开关 + 推测执行开关)

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: SettingAgentPermissionsPage.kt(全文 159 行)/SettingAgentExtensionsPage.kt:216-233(推测执行开关宿主)/PreferencesStore.kt:164-165,226-229(默认)/ChatService.kt:1345-1347(消费链)/values-zh/strings.xml:634-635,654-656,996-997,1307-1310,255
- **背景**: harmony tool_loop.ts:66-83 四字段(autoApproveTools/autoApproveHighRiskTools/speculativeEnabled/speculativeMaxConcurrentTools)自 D-056/D-063 已就绪,但快照无字段、ChatPage 未传、无写入助手、无设置面——四开关恒默认,用户不可配。
- **决策**:
  1. AgentRuntimePrefs:AgentRuntimeJson/AgentRuntimeSnapshot += autoApproveAllToolCalls/autoApproveHighRiskToolCalls(解析 `=== true`,默认 false,:164-165 逐字)+ speculativeToolExecution { enabled(`=== true` 默认 false)/maxConcurrentTools(非数 → 4,:226-229)};默认快照同步;新增 updateAgentRuntimePermissions RMW 写入助手(autoApproveAllToolCalls?/autoApproveHighRiskToolCalls?/speculativeEnabled?)。
  2. ChatPage.buildToolLoop:四字段接线——autoApproveTools = agentRuntime.autoApproveAllToolCalls || conv.autoApproveToolCalls(ChatService.kt:1345-1347 逐字,会话级覆盖优先 OR 语义);autoApproveHighRiskTools = agentRuntime.autoApproveHighRiskToolCalls;speculative* 两字段直传。
  3. ChatAgentPermissionsPage(新页):批准策略卡——全局自动批准直写;高风险自动批准开启 → AlertDialog(title/desc zh 逐字;取消重置本地态/确认写 true),关闭直写(:140-151 逐字);实验卡——推测执行开关直写 speculativeEnabled。入口:ChatListPage 头部 '权限' 链接;main_pages.json 注册。
  4. 构建修复:D-104 编辑将 SpeculativeToolExecutionJson 接口插入 AgentRuntimeJson 中段致接口断裂(9+ ArkTS 10505001)——重排为 AgentRuntimeJson 完整十二字段 + SpeculativeToolExecutionJson 独立;AgentRuntimeSnapshot 移入 import type 块(接口类型规则)。
- **偏差/适配登记**:
  - '系统权限' section(:95-110 → SettingSystemAccess)= Android 系统权限子系统(短信/拨号/联系人/日历/录音/终端),平台专属 → N/A 登记不移植;
  - 推测执行开关 Android 宿主于扩展/运行任务页(任务调度列表未移植)→ 并入本页第二卡(placement 偏差);
  - maxConcurrentTools 无 Android UI(恒 4)→ 同不暴露;AlertDialog deprecation 警告为既有 API 用法,同其他页先例。
- **关闭**: 审计行「工具执行 + 审批循环」残余「autoApprove 设置面/推测执行设置面」。
- **未移植(P1 登记)**: maxToolLoopSteps/harnessDebug/任务调度设置面(扩展页残余);系统权限子系统(Android-only)。
- **验证**: chat tsc 干净 + 1054 测试/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-105 — ask_user 问题 UI(HITL 问答卡片)

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: ChatMessageAskUserStep.kt:64-378(AskUserToolStep 全文)/values-zh/strings.xml:192,212/tool_approval.ts:76-92(answered verdict 已有)/AskUserTool.kt(needsApproval HITL)
- **背景**: D-057 审批闭环 + D-058 ask_user 工具已落地,但问题 UI 登记为 P1「MVP 经 idle blocker 自由文本应答」——pending 态用户只见 批准/拒绝(对 ask_user 语义错误,Android 经 onToolAnswer 应答),选项/多选/分题结构不可达。
- **决策**:
  1. chat HAR ask_user_questions.ts(纯逻辑 + 10 测试):parseAskUserQuestions(:87-99 runCatching 全损语义——坏 JSON/元素非对象/options 非数组/非原始选项 → 整体 [];缺省 id/question 空串、options []、selection_type 'text');buildAskUserAnswerPayload(:331-345 {answers:{id: string|[...]}},键序 = questions 序,multi → 数组缺省 []、其余 → 字符串缺省 '');parseAskedAnswers(:74-84 失败/非对象 → null);askAnswerDisplayText(:246-253 数组 join ' · '/原始 → content/非原始与缺失 → 整段 answer);isAskUserSubmittable(:348-353)/hasAnyAskAnswer(:295-296)。
  2. MessageRow 工具卡片分支:toolName === ask_user → 问题卡片(标题:单题 → 首题文案、多题 → '询问 N 个问题' zh 逐字;single/multi/text+options → chips(Flex wrap,选中 = accent 填充);text → chips + TextArea '在此输入回答…' 逐字;题间 hairline Divider;pending → 操作行 清空(任一已答时)+ 提交(accent pill,isAskUserSubmittable 使能,未使能灰态 + 点击守卫);answered → 'A' accent 标记 + askAnsweredText);其余工具保持 D-057 批准/拒绝(ask_user pending 不再显示 批准/拒绝 — 与 Android 一致,HITL 仅经应答)。
  3. ChatPage:handleToolAnswer(toolCallId, answer) → verdict {kind:'answered', answer}(tool_approval.ts:80 逐字);与审批共用 applyToolVerdict(落库 → 无 pending 则 resumeToolLoop,ChatService.handleToolApproval:1122-1188 语义)。
- **偏差/适配登记**: FlowRow → Flex wrap;Button/OutlinedTextField → Text/TextArea 扁平视觉(沿用本页既有 token);展开/折叠 ChainOfThought 容器未移植(常驻展开,与 harmony 工具卡片 MVP 一致);DotLoading/ shimmer 图标未移植;多选题选中集渲染序 = 点选序(Android Set 同无序语义)。
- **关闭**: 审计行「工具执行 + 审批循环」残余「ask_user 问题 UI」。
- **未移植(P1 登记)**: 工具卡片完整视觉(ChainOfThought 折叠/图标/shimmer);ToolDenyReasonDialog(:428-461 拒绝理由输入,harmony 拒绝恒空理由 — 与 D-057 已登一致)。
- **验证**: chat tsc 干净 + 1064 测试(+10)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-106 — 删除全部会话(History toolbar delete-all)

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: HistoryPage.kt:88-107(toolbar Delete01 IconButton)/:155-178(AlertDialog)/HistoryVM.kt:55-60(deleteAllConversations)/ConversationRepository.kt:449-453(deleteConversationOfAssistant)/:400-417(deleteConversation 语义)/values-zh/strings.xml:307,309-312
- **背景**: harmony ChatListPage(对应 Android HistoryPage)缺 toolbar 删除全部入口;单条删除撤销窗 D-096 已落地,批量路径缺失。
- **决策**:
  1. 入口:ChatListPage 头部 '删除全部'(DANGER 色,'删除全部' contentDescription zh 逐字)。
  2. 确认:AlertDialog.show — title '删除全部对话'/message '确定要删除所有对话吗？此操作无法撤销。'/取消/删除(zh 逐字;:158-174)。
  3. 执行:deleteAllConversations — repo.list() 全量 → 逐会话 getById(取 full 含 messageNodes)→ repo.delete → cleanupDeletedConversation(ManagedFileStore deleteChatFiles);串行链保证次序,完成后 refresh。无撤销窗(Android delete-all 同 — deferCleanup=false 立即清理)。
  4. 弹窗形态:showDeleteAllDialog 态改命令式 AlertDialog.show(与 D-104 高风险确认同先例)。
- **偏差/适配登记**:
  - **域偏差**:Android 域 = 当前助手(settings.getCurrentAssistant().id,HistoryVM.kt:56);harmony 无当前助手设置(D-019 首助手解析),ChatListPage 列表即全域 → 删除范围 = 本页列出集(与页面可见域一致);
  - FTS 删除(messageFtsManager)= 未移植(FTS 索引 P1 已登);
  - favorites/chat_images 清理 = 未移植(D-096 已登);
  - IconButton → 文本链接(本页头部既有惯例)。
- **关闭**: 审计行「会话列表」delete-all 残余(P1 清单「delete-all 会话(History toolbar)」)。
- **未移植(P1 登记)**: 无新增(FTS/favorites/chat_images 沿用已登项)。
- **验证**: chat tsc 干净 + 1064 测试(无新增——entry 侧)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-107 — assistant toolProfile/localTools 编辑面

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: AssistantBasicPage.kt:243-261(工具范围 FormItem)/:98-105(label() 六档 zh)/AssistantLocalToolPage.kt(全文 280 行,13 LocalToolOption 行)/values-zh/strings.xml:83-92,1294-1303 + values/strings.xml:998-1003(zh 缺失三项的 en 回退)/MainAgentToolProfile.kt(六值声明序)
- **背景**: D-082(ToolProfileFilter 门控)+ D-083(LocalToolOption 门控)数据链已落地,但两字段均无编辑面——assistant.toolProfile/localTools 用户不可配。
- **决策**:
  1. **工具范围**(编辑器内):ChatAssistantsPage 编辑器 += 六档 chips(完整/最小/网页只读/文件只读/编程/手机控制,label() 逐字);label '工具范围' + desc '限制此助手可使用的工具类别；不会自动启用未开启的本地工具。'(硬编码 zh 逐字);startEdit/startCreate/save 三处接线(patchAssistant/makeAssistant += toolProfile,默认 'full' :140)。
  2. **本地工具**(新页):ChatAssistantLocalToolsPage — 13 行行序逐字(javascript_engine → webmount_eval),title/desc zh 逐字;toggleLocalTool :71-84 逐字(enable → + option Set 去重;disable → webmount 连带 - webmount_eval,否则 - option);webmount_eval 开关 .enabled = webmount 已开(:273);保存 = patchAssistant({localTools}) + saveAssistants(同 D-102 saveAssistant 模式)。入口:助手行 '工具'(扩展/复制之间);main_pages.json 注册。
- **偏差/适配登记**:
  - workspace/terminal/screen 三项 values-zh 无键 → 按 Android zh 回退行为取 en 逐字(values/strings.xml:998-1003);
  - detail tab → 独立页(同 D-102 扩展页先例);
  - 工具范围位置:Android 居 chatModel/imageModel 之间,本编辑器无模型选择器 → 置于流式输出后;
  - 平台专属工具(terminal/screen/system_access/webview/icloud/webmount 系)harmony 无实现 — 开关仅落 localTools 配置(Android 同为纯配置面);
  - Select → chips(同推理力度选择器惯例)。
- **关闭**: 审计行「工具执行 + 审批循环」残余「ToolProfileFilter 设置面选择 UI」「LocalToolOption 编辑面 UI」。
- **未移植(P1 登记)**: 平台专属工具实体(terminal/screen/system_access 等 Android-only)。
- **验证**: chat tsc 干净 + 1064 测试(无新增——entry 侧)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-108 — ToolDenyReasonDialog 拒绝理由输入

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: ChatMessageAskUserStep.kt:428-461(ToolDenyReasonDialog 全文)/ChatMessageTools.kt:681,736-744(拒绝按钮 → showDenyDialog)/:966-974(confirm → onToolApproval(id,false,reason))/:952-960(已拒绝展示)/ChatService.kt:1122-1140(reason → Denied(reason))/values-zh/strings.xml:201-204
- **背景**: D-057 审批闭环拒绝路径恒空理由(登记「拒绝理由输入 = P1」);domain 层 tool_approval.ts denied verdict 早已支持 reason,缺 UI 输入面与展示。
- **决策**:
  1. ChatPage:拒绝按钮 onDenyTool 改 → openDenyDialog(重置理由文本 + 记录 toolCallId);Stack 根 + overlay 弹窗(title '拒绝工具执行'/TextArea hint '原因（可选）'/确认 '拒绝' 红/取消 zh 逐字);遮罩点击 = onDismissRequest;confirm → handleToolApproval(id, false, reason)(handleToolApproval += reason 参数,默认 '' — :1126 默认逐字)。
  2. MessageRow 工具卡:denied 态 += 展示 '已拒绝' + 非空白 reason → ': reason'(ASCII 冒号+空格,:955-956 逐字,红色 labelSmall);ask_user 卡早退不渲染(:676-679)→ 门控非 ask_user。
  3. denied verdict 线格式不变(domain 既有);reason 全程原样持有(trim 仅用于空白判定,:956 isNotBlank 语义)。
- **偏差/适配登记**: AlertDialog → Stack overlay(同 D-101 编辑器先例);OutlinedTextField → TextArea;卡片内点击消费防穿透遮罩。
- **关闭**: D-057/D-105 登记残余「ToolDenyReasonDialog 拒绝理由输入」。
- **未移植(P1 登记)**: 无新增。
- **验证**: chat tsc 干净 + 1064 测试(无新增——entry 侧)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-109 — model 级自定义参数编辑面(customHeaders/customBodies)

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: SettingProviderModelSettingsForm.kt:259-271(高级设置 tab 结构)/:289-312(节标)/:315-395(ModelCustomHeaders)/:397-499(ModelCustomBodies)/SettingProviderModelTabPage.kt:694-721(草稿 + 确认/取消)/values-zh/strings.xml:41-42,49-50,69-70,79-80,82
- **背景**: mergeCustomParams(context_assembly.ts:141-145)已消费 model.customHeaders/customBodies(assistant 级 + model 级串联),但模型级两字段无编辑面,用户不可配。
- **决策**:
  1. ChatModelParamsPage(新页,router params providerId+modelId):草稿语义(:694-696 编辑 dialogState,确认才落库,取消丢弃);两节节标 '自定义 Headers'/'自定义 Body' + '· N';行 'HEADER N'/'BODY N' + 删除(removeAt);name/key 确认时 trim(:363/:450 存储 trimmed 语义);body value = JSON 文本逐键解析(:458-472 — 合法 → 更新草稿值+清错;非法 → 行内 '无效的 JSON: <msg.take(100)>' 保留旧值);添加 Header(CustomHeader("",""))/添加 Body(CustomBody("",JsonPrimitive("")) → 文本 '""');确认 → provider.models by id 替换 → saveProviders。
  2. ChatProviderSettingsPage:模型区 += 已持久化模型行 '参数' 入口 → 新页;**parseModels 数据保留修正** — modelId 命中既有行原样保留(此前恒 makeProviderModel 重建会抹掉 D-109 写入的 custom params;Android 模型经独立 sheet 编辑无整体重建)。
  3. displayName 非空确认使能(:716)= 本页不编辑 displayName → N/A 登记。
- **偏差/适配登记**: Android 逐键 trim 入草稿(显示 raw)→ 确认时统一 trim(持久化结果等价);ProviderOverwriteSettings(:251-257)/内置工具 tab(:275-283)未移植(P1 已登,本页仅两节);provider sealed 变体替换经 JSON round-trip(D-017 线格式无损)。
- **关闭**: 审计行 56 残余「model 级自定义参数编辑面」。
- **未移植(P1 登记)**: 模型 overwrite 编辑面/BuiltInTools tab(沿用已登项)。
- **验证**: chat tsc 干净 + 1064 测试(无新增——entry 侧)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-110 — ContextUsageIndicator 上下文用量环

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: ChatInputUsage.kt:77-162(ContextUsageIndicator 全文)/:164-168(formatContextTokens)/core/utils/StringUtils.kt:56-87(Int.formatNumber)/ConversationContextEngine.kt:365,451(PLANNING/COMPACTING 生命周期)/values-zh/strings.xml:225
- **背景**: D-054/D-055 压缩引擎 + estimateConversationInputTokens(消费活动 compacts 的 post-substitution 足迹)已备,composer 用量指示登记 P1——用户看不到上下文预算消耗与压缩活动。
- **决策**:
  1. chat HAR format_number.ts(+4 测试):formatNumberInt(:56-87 逐字,K/M/B 档整数无小数/非整 toFixed(1)/负号前置)+ formatContextTokens(:164-168 逐字,≤0 → '0'/<1000 → '<1K')。
  2. context_engine:PrepareContextDeps += onCompactActiveChange 可选钩子;force 分支(:657 前后)true/finally false(PLANNING/COMPACTING → 终态复位语义;precompact 由 launchPrecompact 包装方自跟踪)。
  3. ChatPage:composer 上 += 指示行 — ProgressType.Ring 10vp(strokeWidth 1.6,轨道 faint 0.24,弧色 :120-125 四态:压缩中 blue '#4EA6FF'/≥0.7 red/≥0.5 amber/else muted)+ 10sp 文案(压缩中 '正在压缩上下文' zh 逐字;否则 'Context <used> / <window|-->');used = estimateConversationInputTokens(currentMessages, contextCompacts);窗口 = resolveChatProviderChoice().model.contextWindowTokens;compacts 快照于会话加载 + 六处回合完成点刷新;活动计数 = precompact 包装 ++/-- + force 钩子。
- **偏差/适配登记**:
  - **ModelRegistry.MODEL_CONTEXT_WINDOW 注册表查询未移植**(ALL_MODELS 静态表 + matchScore 模糊匹配子系统)→ 取持久化值 = Android 未知/自定义模型回退路径(:110-111),P1 登记;
  - Canvas drawArc → ProgressType.Ring;环色 red/amber 取浅主题近似(workspace 主题色动态,视觉级偏差);
  - 流式中指示按最近落库基线(流式尾不入估计,Android fingerprint 随流式重算 — 视觉级);
  - Kotlin toFixed HALF_UP vs JS toFixed 半值边界末位可能差异(显示级,登记);
  - ReasoningLevelChip/ComposerUsageSheet(同文件另两 composable)= 独立特性,未在本切片,P1。
- **关闭**: 审计行 51 残余「ContextUsageIndicator 环」。
- **未移植(P1 登记)**: ModelRegistry 注册表/ReasoningLevelChip/ComposerUsageSheet(provider 配额)。
- **验证**: chat tsc 干净 + 1068 测试(+4)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-111 — VisionModelHealthChecker 探测 + 图片附件生成守卫

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: VisionModelHealthChecker.kt(72 行全文)/ImageAttachmentValidator.kt(116 行全文)/SettingModelPage.kt:511-553(OCR 行健康后缀)/ChatInput.kt:239-264,267-292(双发送路径守卫)/ChatInputAttachments.kt:143-171(附件 inspect + onStatusClick)
- **背景**: D-071a/D-071b 视觉兜底 + OCR 设置面已备,但两处 VisionModelHealthChecker 消费点未移植:OCR 设置行健康后缀(用户不知视觉模型是否可用)与发送前生成守卫(回退场景不探测,发出去才失败 = 错误恢复缺口)。
- **决策**:
  1. chat HAR vision_health.ts(+8 测试):六健康态 + zh 标签逐字(检测中/可用/未配置/不支持图片/提供商不可用/不可用：<msg ?: 检测失败>,全角冒号);TINY_PNG/探针消息逐字(system 'Reply with OK if you can receive this test image.' + user [Text('Vision probe. Reply only OK.'), Image(TINY_PNG)]);isAvailable = kind == AVAILABLE;端口化 VisionProbeDeps(findOcrModel/modelSupportsImage/findProvider/generateText 闭包注入,ProviderManager 不进域层)。
  2. chat HAR image_attachment_guard.ts(+12 测试):四附件态 + zh 文案逐字(正在检查图片/图片可由当前模型读取/将先由视觉识别模型读取图片 + 各 blocked);MAX_IMAGES_PER_MESSAGE=4;inspectImage 判定顺序逐字(encode → chat 空 → chat IMAGE → 视觉模型三查 → FALLBACK);firstBlockingIssue(>4 拦截 → 逐张首个 blocked 短路);firstImageBlockingIssueForSend(静态拦截先行,有 FALLBACK → 探测健康,不可用 → blocked 健康标签原文);readableImageError 关键词映射表逐字(ImageEncodingError.cause 解包同 :103);encodeBase64(withPrefix=false) → 注入 ImageEncodeCheck(entry = encodeImageDetailed)。
  3. OcrSupport:generateVisionText 提取导出(OcrTransformer.kt:107-120 同口径,三变体分发;createEntryOcrTransformer 改委托)。
  4. ChatPage:gateModelConfigured 透传 choice;guardImagesForSend 包 send()/sendWithoutAnswer() 门后全路径(守卫失败 → errorMsg toast 等价面,输入/待发图片保留;无图片 part → 域内短路不探测);待发图片点按 = inspectImage,blocked → errorMsg(onStatusClick 语义)。
  5. ChatTaskModelsPage:OCR 行 desc += ' · <健康标签>'(:529 逐字);refresh 后 probeOcrHealth(初始 '检测中';探测数据源 = 未过滤 rawProviders,对齐 Android settings.providers)。
- **偏差/适配登记**:
  - 「视觉识别模型的提供商不可用」分支防御性不可达(findModelById 与 findProvider 扫同一 providers 列表,命中前者必命中后者;Android 同构)—— 分支逐字保留,无测试构造(登记);
  - 探测时序:Android produceState(key=ocrModelId+providers) 组合即探 → 鸿蒙 refresh() 尾部触发(从设置页返回 aboutToAppear 重触发,等价就绪点,D-070 模式);
  - 附件状态常驻角标(ImageAttachmentPreview 视觉)= 视觉残余,P1 登记;点按检查已落地;
  - 守卫顺序:Android 守卫内含 chat 模型空检查,鸿蒙模型门(D-080)先行 → 该分支不可达,同文案等价结果;
  - toast → errorMsg 行内错误面(既有 D-0xx 统一适配)。
- **关闭**: 审计行 29(PD-006 错误恢复残余清零 → ✅)+ 行 48 残余「VisionModelHealthChecker 探测/生成守卫」。
- **未移植(P1 登记)**: 附件状态常驻角标视觉(ImageAttachmentPreview)。
- **验证**: chat tsc 干净 + 1088 测试(+20)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-112 — DocxParser 全文移植 + XML/ZIP 平台适配(document 模块首片)

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: document/DocxParser.kt(349 行全文)/org.xmlpull.v1.XmlPullParser(kxml2 语义子集)/java.util.zip.ZipInputStream(条目扫描)/DocumentAsPromptTransformer.kt parseDocx 消费点(D-074 deps)
- **背景**: D-074 文档 transformer 的 parse* deps 刻意不提供(document 模块未移植)→ .docx 附件走 '[ERROR, failed to read file]'。document 模块四解析器中 Docx/Pptx/Epub 为纯 ZIP+XML 逻辑(可移植),PdfParser 依赖 MuPDF native(平台阻断)。
- **决策**:
  1. chat HAR xml_pull.ts:XmlPullPort(eventType/name/depth/text/getAttributeValue/next)+ XmlPullFactory + ZipEntryTextProvider 端口;getAttributeValue(null,name)= kxml2 任意命名空间通配语义。
  2. chat HAR docx_parser.ts(+14 测试):parse 双文案逐字('Unable to find document content in DOCX file'/'Error parsing DOCX file: <msg>'/'Error parsing document XML: <msg>');body 门 + p/tbl 分发;pStyle Heading*/heading* → '#'*N(末位非数字 → 1,digitToIntOrNull ?: 1);numPr ilvl/numId → 缩进列表(**number 恒 1 = Android 原样,不修正**);rPr b/i → **/*/*** 包裹;表格 markdown(maxCols 补空/首行后 --- 分隔/尾 '\n');单元格多段落 ' ' 连接;文档级 trim;toIntOrNull 严格全串(JS parseInt 前缀解析不忠实 → 自实现)。
  3. entry EntryDocumentParser.ets:SAX/token 回调两遍解析(tokenValueCallbackFunction 收集事件流(ParseInfo.getDepth/getAttributeCount 自带)+ attributeWithTagCallbackFunction 三元组按 attrCount 顺序对齐,消除配对歧义);本地名还原(kxml2 namespace-aware);CDATA → TEXT(kxml2 同);zlib.decompressFile 整包解压临时目录 → 条目名读文本 → 递归清理。
  4. DocumentSupport:createEntryDocumentReader += parseDocx: parseDocxEntry。
- **偏差/适配登记**:
  - ZIP 整包解压 vs Android 流式扫描(输出等价;临时目录 cacheDir/docparse_<uuid> 用后递归删,失败静默 = 平台缓存清理同档);
  - HarmonyOS xml 无 SAX handler 重载(ets API 仅 ParseOptions)→ 两遍回调重放,深度/属性计数由 ParseInfo 供给;
  - 测试以 token 脚本 fake 驱动域层(平台适配层薄,无 JVM 面)。
- **关闭**: D-074 残余「parse*」之 DOCX 部分。
- **未移植(P1 登记)**: PptxParser(453 行,同 Port 续切片 D-113)/EpubParser(231 行,D-114);**PdfParser = MuPDF native 平台阻断**(HarmonyOS 无 MuPDF AAR;需纯 ArkTS PDF 文本提取或 native 移植评估 → spike 登记);OfficeNativeSwitch 维持不移植。
- **验证**: chat tsc 干净 + 1102 测试(+14)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL(ArkTS 严检两轮:首轮回函 apply/call + TextEncoder 静态调用 + SAX 重载不存在三错,已修)。

## D-113 — PptxParser 全文移植 + ZIP 中央目录读取(CD 枚举序升级)

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: document/PptxParser.kt(453 行全文)/java.util.zip.ZipFile(entries 枚举序/getEntry/getInputStream 语义)/@ohos.zlib Zip 类(inflateInit2 raw deflate)
- **背景**: D-112 落地 DOCX 时 ZIP 走整包解压临时目录(偏差登记);PPTX 移植需 ZipFile 随机访问 + **枚举序忠实**(PptxParser 排序键 quirk 下枚举序决定幻灯片顺序)。
- **决策**:
  1. chat HAR zip_archive.ts(+6 测试):EOCD 尾部 64KB 反扫 → 中央目录顺序遍历(ZipFile.entries() 枚举序忠实)→ local header 数据段切片;stored 直取/deflate 经 InflateRawPort(平台注入)/未知方法抛错(非静默);UTF-8 名/文本解码域内实现。
  2. chat HAR pptx_parser.ts(+10 测试):**排序 quirk 逐字保留**(sortKey = substringAfter('slide').substringBefore('.xml').toIntOrNull ?: 0 — 'slide' 首现于 'slides' 段 → 键恒 0 → 稳定排序 = CD 枚举序);slideNumber = 排序 index+1,notesSlide<N> 同 index;'## Slide N'/'### Speaker Notes' 形态;slide 异常 → 'Error parsing slide XML: <msg>\n' 内联/notes 异常 → '' 静默;bullet(buChar/buAutoNum/lvl)→ 缩进 '- '/'1. ';表格 markdown(尾 '\n' **在 if 内**,与 docx 不同);isNotesTextShape 事件消费前瞻(ph type=='body')原样。
  3. entry:readFileBytes + inflateRawEntry(zlib.createZipSync inflateInit2(-15) FINISH;输出容量 = 声明 uncompressedSize,溢出/失败抛错非静默);**parseDocxEntry 重构为 CD 读取**(D-112 整包解压临时目录偏差消除,EOCD 失败经 provider 抛入域层原文案路径);parsePptxEntry 接线;DocumentSupport += parsePptx。
- **偏差/适配登记**: 测试夹具首版 local header 多 2 字节致 deflate 切片错位 — 按 ZIP 规格校验后修为夹具错(实现无改);HarmonyOS 无现成单条目解压 API → inflate 状态机自管(inflateEnd finally)。
- **关闭**: D-074 残余「parse*」之 PPTX 部分 + D-112 整包解压偏差。
- **未移植(P1 登记)**: EpubParser(231 行,D-114 续);**PdfParser = MuPDF native 平台阻断**(spike 登记);OfficeNativeSwitch 维持不移植。
- **验证**: chat tsc 干净 + 1118 测试(+16)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。

## D-114 — EpubParser 全文移植(document 模块解析器收尾;PDF 平台阻断定案)

- **日期**: 2026-07-28
- **状态**: 已执行
- **范围**: document/EpubParser.kt(231 行全文)
- **背景**: Docx/Pptx 已落地(D-112/D-113);Epub 为纯 ZIP+XML 三解析(container/opf/xhtml),同 Port 复用即达。PdfParser 依赖 MuPDF(com.artifex.mupdf.fitz)Android native AAR,HarmonyOS 无等价物。
- **决策**:
  1. chat HAR epub_parser.ts(+11 测试):container rootfile full-path → opf(manifest 映射 + spine 序)→ xhtml markdown;三缺失文案逐字('Unable to find OPF file in EPUB'/'Unable to read OPF file in EPUB'/'No readable content found in EPUB file');opfDir join(mediaType !contains 'html'/manifest 未命中/条目缺失 → continue);xhtml **namespaceAware=false 语义**(标签保留限定名 → entry 双工厂 createEntryXmlPull/createEntryXmlPullNoNs);li 父栈顶 ol → 共享计数 'N. '(ul 不重置,Android 原样);文本 \n→' ' \r→' ' \s+→' ';**parser.next() try/catch → break 畸形容忍保留**;结果 \n{3,}→'\n\n' + trim;异常 → '' 静默。
  2. entry:EntryXmlPull += nsAware 构造参(默认 true);parseEpubEntry 接线;DocumentSupport += parseEpub。
- **偏差/适配登记**: 无新增(no-ns 工厂为语义忠实而非偏差)。
- **关闭**: D-074 残余「parse*」全部可移植部分(DOCX/PPTX/EPUB)。
- **未移植(P1 登记)**: **PdfParser = MuPDF native 平台阻断定案** — HarmonyOS 无 MuPDF AAR;纯 ArkTS PDF 文本提取(内容流 Tj/TJ 解析)为独立子系统,需独立 spike 评估投入/保真度;OfficeNativeSwitch 维持不移植。
- **验证**: chat tsc 干净 + 1129 测试(+11)/deepread 603/assembleHar+assembleHap 双 BUILD SUCCESSFUL。
