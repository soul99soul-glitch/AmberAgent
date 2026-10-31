# 等价偏差登记（Parity Debt）

> 鸿蒙实现与 Android 生产语义的已知偏离。每条必须有：Android 基准锚点 / 偏差内容 / 理由 / 消除条件。
> 原则：等价优先级 数据与协议 ＞ 核心闭环 ＞ 错误恢复 ＞ 交互语义 ＞ 视觉。前两档不允许无声偏差。

| ID | 日期 | 领域 | Android 基准 | 鸿蒙现状 | 偏差 | 理由 | 消除条件 | 状态 |
|---|---|---|---|---|---|---|---|---|
| PD-001 | 2026-07-28 | deepread agent | 空 prefetch → hard fail，提示"没有抓到足够的来源"（Android DeepReadSourcePrefetcher 调用方） | 空/异常 prefetch → 用标题构造 fallback source，走 fallbackPlan 产出降级文章 | 错误恢复语义变化（用户可见） | 搜索源全挂时体验：降级文 > 直接失败 | G1 人工确认；若否决则改回 hard fail | open |
| PD-002 | 2026-07-28 | deepread prefetch | SOURCE_COLLECTION_TIMEOUT_MS = 36s（DeepReadSourcePrefetcher.kt:688） | PREFETCH_WALL_BUDGET_MS = 15s | 预算常量减小，可能收集到更少来源 | 移动端等待容忍度；与 HTTP 超时匹配 | 真机 spike 后按实际来源完整度回调 | open |
| PD-003 | 2026-07-28 | 构建目标 | Android targetSdk 37 | harmony targetSdkVersion 5.0.0(12)（API 12 兼容档） | 目标 API 档位远低于 compileSdk 26 | 支持 API 24 模拟器安装（commit 1db3ba8c2 的延续） | 真机设备到位后按真机 API 重新定档 | open |
| PD-004 | 2026-07-28 | chat 时间戳线格式 | UIMessage.createdAt/finishedAt 为 LocalDateTime（无时区，如 `2026-07-28T10:00:00`） | chat-domain 统一 ISO 8601 UTC 带 `Z`（Instant 语义） | persisted JSON 中时间字符串格式不同；语义无歧义且更显式 | ArkTS 侧无时区 LocalDateTime 处理成本高；UTC+Z 更不易错 | 若需与 Android 导出包字节级对齐，做导入归一化 | open |
| PD-005 | 2026-07-28 | chat assistant regex | AssistantRegexProcessor：Java Pattern 编译；非法规则跳过；Rust native 路由为性能优化 | chat-domain replaceRegexes 用 JS RegExp 解释同一批规则；无 native 路由 | 用户自定义规则中 Java-only 语法（\Q\E、possessive quantifier、部分 \p{}）在 Harmony 编译失败被跳过，Android 能生效 → 替换结果不同 | JS RegExp 是 ArkTS 唯一正则引擎；语法差异无法对齐 | 文档化支持的 regex 子集；或引入原生 regex 引擎 spike（G-D 阶段评估） | open |
| PD-006 | 2026-07-28 | chat 生成错误恢复 | GenerationHandler：runProviderCallWithRetry + vision fallback + 工具执行/审批 + 生成守卫 | ~~抛错直接传播~~ **retry 已落地(D-050)；vision fallback 已落地(D-071a/D-071b)；工具执行/审批已落地(D-056/D-057)**；残余仅：生成守卫（reasoning-only/invalid-widget stream guard，依赖 GenerativeUiPlanner — generativeUi 大特性 P1，未移植时守卫恒不触发，与 Android generativeUi 关闭态等价） | 错误恢复语义部分缺失（第三档等价优先级） | retry 纯逻辑可移植（已做）；vision fallback 依赖 transformer 链；审批/工具循环依赖工具定义落地 | vision fallback + 工具执行落地时关闭 | open |
| PD-007 | 2026-07-28 | chat 多模态图片 | FileEncoder.kt:68-103：file:// 统一 compressAndEncode 压缩后 base64；选图复制到应用私有目录存 file:// 引用 | **压缩缺口已消除(D-079)**：选图物化即压缩（ImageCompress.compressImageAsDataUrl — gif 直通，其余 JPEG q85 长边 ≤2048 + EXIF 归一，错误串逐字）；残余：① 选图不落私有目录（data: URL 直接存消息体）② file:// 来源（D-076 upload 落盘图）encode 期原样不压缩 ③ 压缩时机 = 物化一次 vs Android 每次发送 | ① 消息体携带完整图片数据而非文件引用（会话 JSON 体积增大）② D-076 落盘图若超大，请求体大于 Android（选图链路产出的图已压缩，不受影响） | 压缩依赖设备端 PixelMap 管线，已落地 ImageKit；data: URL 避免 picker 临时授权过期 | 落盘记库已完成(D-086 FilesRepository + trackUploadFile 接线);P1：file:// 私有目录存储评估 + file:// encode 期压缩 + 会话删除/附件清理接线(依附撤销窗) | open(压缩已闭环,残余存储形态) |
| PD-008 | 2026-07-28 | chat provider 密钥 | KeyRoulette：一 provider 多 apiKey 轮换选择（OpenAI/Claude/Google 共用） | ~~鸿蒙 ProviderSetting 单 apiKey 直用~~ **D-078 已消除**：claude/google api 层 + openai adapter 每请求 keyRoulette.next(apiKey, id)；entry 恒 LRU（cacheDir/lru_key_roulette.json，KeyRouletteSupport.ets 单例）；OcrSupport vision api 同接线 | 多 key 轮换能力缺失（功能缺失，非行为偏差） | source-only 阶段裁剪：KV 模型仅存单 key；轮换 UI/存储未设计 | provider 编辑面落地时评估（P1） | **closed(D-078)** |
| PD-009 | 2026-07-28 | chat 多模态图片 | FileEncoder.kt:94-97：http(s) URL 原样当 base64 返回（mime 兜底 image/png） | encodeImageDetailed 命中 http(s) 直接抛错（D-046） | Android 对 Claude inline base64 source / Google inlineData 会产出垃圾数据（Android 已知缺陷）；鸿蒙不照搬，显式失败 | 宪章禁止照搬 Android 已知问题；聊天选图链路物化 data: URL，不产出 http(s) part | 若需支持远程图片 URL，落地下载→编码管线（P1） | open |

| PD-010 | 2026-07-28 | chat 权限状态 | AgentPermissionBroker.kt：android.permission.* 运行时权限名 + minSdk/maxSdk 版本门槛；六类 special access（NotificationListener/UsageStats/Overlay/IgnoreBatteryOptimizations/ExactAlarm/ManageAllFiles）可查询可跳转设置 | D-129：权限名映射 ohos.permission.*（多版本条目折叠为单条，RuntimePermissionSpec 模型字段保留）；special access 六类 HarmonyOS 无对应概念 → status=Unsupported、canOpen=false；未声明/不存在的权限名 checkAccessTokenSync 抛错 → Denied（与 Android 未声明 manifest 同语义）；how_to_grant 文案平台词替换 Android→HarmonyOS | 工具输出中 runtime_permissions/special access 状态与 Android 不同（平台真实能力差异，非协议偏差）；受限 ACL 权限（短信/通话/媒体库）恒 Denied | HarmonyOS 权限模型与 Android 不可一一对应；映射表见 permission_status.ts 头注 | 对应 capability 的工具族落地时逐个复核（picker 免权限路径可降级为 Granted-equivalent） | open |

| PD-011 | 2026-07-28 | chat cron 调度 | AgentCronManager：WorkManager 唯一工作（进程死后由系统唤醒执行）；DataStore preferences_pb 损坏时 decodeFromBackingFile 二进制恢复 | D-130：调度 = 应用存活期 setTimeout（启动时 rescheduleAll 重新武装）；持久化 = ChatKvStore 单一事实源，无 pb 恢复路径；**执行器已接线**（AGENT_CRON_EXECUTION_AVAILABLE=true，createEntryCronRunExecutor 跑 ChatTurn+通知，状态机归 scheduler.fire 的 prepare/mark*/scheduleNext） | 进程被杀后到期任务不触发（下次启动 rescheduleAll 重排，错过的时间点不补跑）；无 WorkManager 唯一工作 REPLACE 语义（in-app setTimeout only） | 进程级后台唤醒 = WorkSchedulerExtensionAbility 基础设施（G-C 设备后 spike） | WorkSchedulerExtensionAbility 落地后替换 in-app setTimeout（进程死后唤醒） | open |
| PD-012 | 2026-10-31 | chat 上下文压缩 | `CompactSummaryPayloads.fallbackPayload`:模型输出清洗后非空即采用,`fallbackTimelineFromSource(sourceContent)` 只在输出为空/JSON 片段时兜底 | `compactFallbackPayload` 逐字同序(`context_compact.ts:779-802`) | 模型返回无信息量的短文本时,压缩产物文本里不含任何源事实(needle 基线实测 0/5),只有 `source_message_ids` 可恢复原文 | 保持跨端逐字一致;鸿蒙单方面改顺序会造成同会话两端产物不同 | 两端同时改为「源内容优先,模型文本入 handoff 补充段」并同步 needle 测试;在此之前靠 `conversation_expand` 恢复 | open |

## 待 Android 侧核实的疑似偏差

- **2026-07-28 / chat 流式合并（内部不一致，非鸿蒙偏差）**：Android 两条合并路径对 reasoning `finishedAt` 处理不一致 —— `MessageStreamAccumulator.appendReasoning`（主路径）在 delta reasoning 非空时**采用 delta 的 finishedAt**（可为 null，即覆盖已有值）；`Message.kt appendChunk`（不可变变体）在相同情形下**强制置 null**。鸿蒙 chat-domain 两条路径均按 Android 各自语义忠实实现（stream_accumulator.ts / 测试锁定）。需人工确认哪条为权威语义；若统一，改其中一条并更新测试。

- **2026-07-28 / RegexOutputTransformer 的 visual 标志（已核实,忠实保留,非鸿蒙偏差）**：Android `RegexOutputTransformer.transformMessage` 在 `visualTransform`/`visualTransformTail` 内对 Text 与 Reasoning 均硬编码 `visual = false`（RegexOutputTransformer.kt:47,53），即 visual 变换命中的是 `visualOnly = false` 的规则；`visualOnly = true` 的规则不经此 transformer（由渲染层另行应用）。集成测试 integration_chat_loop.test.ts 以源码锚点锁定该口径。

## 已消除

- **OpenAI Responses API 传输层缺失**（2026-09-11，harmony-agent-core-spiral Phase 1）：鸿蒙仅有 Chat Completions。已落地 `openai_responses_request/parse/api` + `useResponseApi` 分流 + Provider 详情页开关；resume store Port 可注入（完整断线重连 GET /responses/{id} 仍后续）。Android 锚点 `ResponseAPI.kt` / `ResponseStreamReconciler.kt`。Codex OAuth 流与 image_generation built-in 强制 store 仍裁剪。

- **Grok 端点缺失**（2026-09-11 Phase 1）：常量/状态机/CLI proxy 请求构造 + TokenStore Port 已落地；系统浏览器 OAuth UI 仍 out of scope。

- **STEER 生成中注入**（确认已实现）：`tool_loop.consumeSteerMessages` + ChatPage `drainSteerMessages` + 步间单测。

- **ToolActivity 事件**（确认已实现）：`McpToolActivityPort` + mcp_tools start/complete/fail 测试。

## regenerate 窗口化 merge 偏差(D-024,2026-07-28)
- Android: regenerate 经 mergeGeneratedMessagesIntoWindow/streamingTail 合并 + invalidateCompacts(ContextEngine);依附时间线分页架构。
- Harmony: 扁平节点模型等价语义(上下文截断 + 分支追加不覆盖);ContextEngine/compact = P1,无 invalidateCompacts 对应物。
- 影响:功能等价;时间线分页(大会话性能)未移植,若移植需重估 merge 路径。
- regenerate 流式预览:Android 有;~~本实现 MVP spinner 代替(P1 补)~~ **已消除(D-041,2026-07-28):onUpdate 快照末条渲染在目标节点行下,visual transforms 与 send 路径一致。**
